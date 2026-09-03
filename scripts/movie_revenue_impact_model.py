#!/usr/bin/env python3
"""
Indian Box-Office Revenue Prediction + Factor Impact Calibration
==================================================================

Predicts theatrical revenue for Indian movies released after 2000 using
`movies_data_collection` (+ `actors_data_collection`), and calibrates the
min/max impact-score band for every factor in the 80-factor catalogue the
business team supplied (Categories 1-7).

Requirements
------------
    pip install pandas numpy scikit-learn psycopg2-binary scipy

Usage
-----
    python3 movie_revenue_impact_model.py \
        --db-host localhost --db-port 5432 --db-name aura --db-user mukundv \
        --output-dir ./output

Connection defaults mirror src/main/resources/secrets.txt (the Java service's
own Postgres connection), so it works out of the box on the same host AuraMath
runs on. Override with --db-* flags or MOVIE_DB_* env vars if needed.

Methodology, in one paragraph
------------------------------
The two formulas in the brief (additive-anchor baseline B0, then a
compounding multiplier Y = B0 * prod(1 + delta_i)) are a multiplicative model,
which is exactly what a log-linear regression estimates: taking logs turns
"revenue is budget times a stack of percentage adjustments" into a sum of
terms, each with its own fitted coefficient. So instead of hand-picking the
weights in B0 and the delta_i ranges, this script (a) computes every factor it
CAN observe from the schema as a feature already rescaled onto the ~min/max
band the business team specified, (b) regresses ln(revenue) on those features
plus ln(budget) and the baseline anchors (R_star, R_director, R_concept,
franchise/R_IP), and (c) bootstraps that regression to get a calibrated
min/max for every factor that has real signal in the data. Of the 80 factors
described with a stated range, this schema supports direct measurement for
12 (see MEASURABLE below) plus the four baseline anchors. The remaining ~68
(mostly VFX/BGM/controversy/legal/distribution factors — categories 3, 6, 7
in particular) have no corresponding column anywhere in this database; for
those the script is explicit that it is reporting the literature-supplied
prior band unmodified, tagged `source="prior_literature"`, rather than
fabricating a fitted number from a signal that does not exist. That gap is
consistent with the brief's own note that unlisted/unmeasured factors
contribute a real 10-25% of variance this model cannot attribute.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import date
from typing import Callable, Optional

import numpy as np
import pandas as pd
import psycopg2
from sklearn.ensemble import GradientBoostingRegressor, HistGradientBoostingRegressor
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_absolute_percentage_error, r2_score
from sklearn.model_selection import KFold
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

MIN_RELEASE_YEAR = 2000
MIN_BUDGET_USD = 10_000
# Revenue floor mirrors the budget floor. Rows with revenue in the single/low
# dollars (e.g. $1, $3, $5,000) next to a real budget are placeholder/bad data,
# not actual box office -- they blow up log-space error metrics disproportionately.
MIN_REVENUE_USD = 10_000

INDIAN_LANGUAGES = {
    "hindi", "tamil", "telugu", "malayalam", "kannada", "punjabi", "bengali",
    "marathi", "gujarati", "urdu", "bhojpuri", "odia", "oriya", "assamese",
    "tulu", "konkani",
}

# Matches an Indian language name inside single-language or comma-separated
# multi-language values (e.g. "hindi, kannada, malayalam, tamil, telugu").
INDIAN_LANGUAGE_PATTERN = re.compile(r"(?i)\b(" + "|".join(sorted(INDIAN_LANGUAGES)) + r")\b")

# Rows lacking BOTH teaser and trailer telemetry (the overwhelming majority of
# this corpus) get their effective budget discounted, per the brief's note
# that missing post-production marketing data implies the true realized
# budget/marketing push was likely only 75-80% of what's on record.
MARKETING_DATA_HAIRCUT = 0.775
MARKETING_DATA_HAIRCUT_RANGE = (0.75, 0.80)

# Approximate, fixed/coarse Indian release-calendar windows. Diwali/Eid/Holi
# shift every year on lunisolar calendars and this table has no per-year
# festival-date column, so this is a month/day heuristic, not an exact
# per-year lookup -- documented as an approximation, not a fitted fact.
FESTIVE_WINDOWS = [  # (start_month, start_day, end_month, end_day)
    (10, 1, 11, 15),   # Dussehra-Diwali season
    (1, 10, 1, 18),    # Pongal / Makar Sankranti
    (3, 1, 3, 31),     # Holi window (coarse)
    (12, 20, 12, 31),  # Christmas/New Year
]
EXAM_SEASON_MONTHS = {2, 3, 4}     # Indian board-exam season
IPL_SEASON_MONTHS = {3, 4, 5}      # IPL has run Mar-May in most seasons since 2008
SUMMER_VACATION_MONTHS = {4, 5, 6}

BOOTSTRAP_ITERS = 400
RIDGE_ALPHA = 2.0
# Calibration multiplier clip: a coefficient of 1.0 means "the literature
# band is empirically about right"; we let the data shrink it to 30% or
# expand it to 160% of the stated band, but no further -- guards against a
# noisy bootstrap draw producing an absurd or sign-flipped calibrated range.
CALIBRATION_CLIP = (0.30, 1.60)

RNG_SEED = 42

# "Predicted correctly" tolerance bands (|predicted - actual| / actual) for the
# full-corpus out-of-fold accuracy check. No single tolerance is objectively
# "correct" for box-office prediction, so we report all three.
ACCURACY_THRESHOLDS = (0.20, 0.30, 0.50)
CV_FOLDS = 5


# ---------------------------------------------------------------------------
# Factor catalogue - every factor the business team gave a stated impact
# range for (80 of the 90 numbered slots; the ungiven category-1 slots
# 3/5/6/7/8/9/10/12/13/14 have no stated range and are intentionally omitted
# rather than invented).
# ---------------------------------------------------------------------------

@dataclass
class Factor:
    id: int
    key: str
    name: str
    category: str
    direction: str            # Positive | Negative | Bidirectional
    stated_min: float
    stated_max: float
    measurable: bool
    proxy_note: str
    proxy_col: Optional[str] = None  # populated at build time for measurable factors


FACTOR_CATALOG: list[Factor] = [
    # --- Category 1: Narrative Architecture & Screenplay Engineering -----
    Factor(1, "conflict_balance", "Protagonist-Antagonist Conflict Balance", "Narrative", "Positive", 0.25, 0.35, True,
           "movies_data_collection.conflict_balance_score (ConflictBalanceService, corpus-relative synopsis sentiment balance)"),
    Factor(2, "narrative_novelty", "High-Concept Narrative Novelty", "Narrative", "Positive", 0.30, 0.45, True,
           "movies_data_collection.narrative_novelty_score (NarrativeNoveltyService, embedding-distance percentile)"),
    Factor(4, "genre_template_adherence", "Genre Template Adherence vs. Subversion", "Narrative", "Bidirectional", -0.20, 0.20, False,
           "No genre-adherence score in schema; would require a subversion-detection model over synopsis text"),
    Factor(11, "romantic_track_integration", "Romantic Track Integration", "Narrative", "Bidirectional", -0.15, 0.15, False,
           "No subplot-quality signal available"),
    Factor(15, "twist_effectiveness", "Twist Effectiveness and Unpredictability", "Narrative", "Positive", 0.20, 0.30, False,
           "No third-act / twist annotation in schema"),

    # --- Category 2: Cast Capital, Persona Alignment, Controversies ------
    Factor(16, "persona_fit", "Star-to-Character Persona Fit", "Cast", "Bidirectional", -0.40, 0.40, False, "No persona/role-alignment signal"),
    Factor(17, "fanbase_mobilization", "Core Fanbase Mobilization Value", "Cast", "Positive", 0.30, 0.50, False,
           "Related to baseline R_star (role_position + actor popularity) but reported separately as a baseline anchor, not double-counted here"),
    Factor(18, "lead_chemistry", "Lead Actor Screen Chemistry", "Cast", "Positive", 0.20, 0.35, False, "No pairing/chemistry signal"),
    Factor(19, "support_cast_credibility", "Support Cast Performance Credibility", "Cast", "Positive", 0.15, 0.25, False, "No supporting-cast quality signal"),
    Factor(20, "director_brand_equity", "Directorial Brand Equity", "Cast", "Positive", 0.25, 0.40, False,
           "Captured as baseline anchor R_director, reported separately to avoid double-counting"),
    Factor(21, "anti_hero_appeal", "Anti-Hero Appeal and Moral Ambiguity", "Cast", "Positive", 0.20, 0.30, False, "No character-morality annotation"),
    Factor(22, "actor_controversy", "Off-Screen Actor Controversy", "Cast", "Bidirectional", -0.35, 0.35, False, "No controversy/news-event feed"),
    Factor(23, "star_overexposure", "Star Satiation and Screen Overexposure", "Cast", "Negative", -0.25, -0.15, True,
           "actors_data_collection: count of a movie's lead/support actors' OTHER releases in the trailing 12 months"),
    Factor(24, "event_speech_impact", "Off-Script Event Speech Impact", "Cast", "Bidirectional", -0.15, 0.15, False, "No promotional-event transcript data"),
    Factor(25, "actor_vulnerability", "Lead Actor Vulnerability and Range", "Cast", "Positive", 0.15, 0.25, False, "No performance-range signal"),
    Factor(26, "multi_generational_appeal", "Multi-Generational Appeal of the Star", "Cast", "Positive", 0.25, 0.35, False, "No demographic-reach signal"),
    Factor(27, "miscasting", "Miscasting and Role Incongruence", "Cast", "Negative", -0.35, -0.20, False, "No casting-fit signal"),
    Factor(28, "nostalgic_reunion", "Nostalgic Screen Reunions", "Cast", "Positive", 0.20, 0.30, False, "No reliable prior-pairing-gap detector at this data quality"),
    Factor(29, "political_dialogue", "Star Political Aspirations / Dialogue Placement", "Cast", "Bidirectional", -0.20, 0.20, False, "No dialogue-content data"),
    Factor(30, "cameo_appearances", "Cameo Appearances of Iconic Stars", "Cast", "Positive", 0.15, 0.25, False, "role_position doesn't distinguish cameo billing"),

    # --- Category 3: Production Scale, Visual Assets, Technical Logistics -
    Factor(31, "vfx_quality", "Technical Quality of Visual Effects (VFX)", "Production", "Bidirectional", -0.30, 0.30, False, "No VFX rating column"),
    Factor(32, "sound_design", "Immersive Sound Design and Mixing", "Production", "Positive", 0.10, 0.20, False, "No sound-design rating"),
    Factor(33, "action_choreography", "Action Sequence Choreography Innovation", "Production", "Positive", 0.20, 0.35, False, "No choreography rating"),
    Factor(34, "bgm_impact", "Background Score (BGM) Impact", "Production", "Positive", 0.25, 0.40, False, "No score/BGM rating"),
    Factor(35, "production_design_scale", "Production Design and Architectural Scale", "Production", "Positive", 0.20, 0.30, False, "No art-direction rating"),
    Factor(36, "cinematography", "Realistic Color Grading and Cinematography", "Production", "Positive", 0.10, 0.20, False, "No cinematography rating"),
    Factor(37, "excessive_runtime", "Excessive Runtime and Editing Lag", "Production", "Negative", -0.30, -0.15, True,
           "movies_data_collection.runtime_mins, penalty scaling in for runtimes above 160 minutes"),
    Factor(38, "editing_pacing", "Dynamic Editing and Transition Pacing", "Production", "Positive", 0.10, 0.15, False, "No editing-pace rating"),
    Factor(39, "period_authenticity", "Authenticity of Period / Cultural Setting", "Production", "Positive", 0.15, 0.25, False, "No period-setting flag"),
    Factor(40, "budget_scale_efficiency", "Budget-to-Scale Efficiency", "Production", "Bidirectional", -0.20, 0.20, True,
           "budget percentile within (primary genre, release year) peer group -- lean-budget films score toward the positive end"),
    Factor(41, "flashback_animation", "Use of Animation for Complex Flashbacks", "Production", "Positive", 0.10, 0.15, False, "No animation-usage flag"),
    Factor(42, "intrusive_song_placement", "Excessive and Intrusive Song Placements", "Production", "Negative", -0.25, -0.15, False, "No scene-level song-placement data"),
    Factor(43, "location_novelty", "Location Novelty and Aesthetic Variety", "Production", "Positive", 0.10, 0.15, False, "No filming-location data"),
    Factor(44, "practical_vs_green_screen", "Live Action over Heavy Green-Screen", "Production", "Positive", 0.15, 0.25, False, "No production-technique flag"),
    Factor(45, "graphic_violence", "Overuse of Graphic / Gratuitous Violence", "Production", "Bidirectional", -0.15, 0.15, False, "No content-intensity rating"),

    # --- Category 4: Pre-Release Marketing and Promotional Levers --------
    Factor(46, "trailer_teaser_impact", "Teaser and Trailer Impact", "Marketing", "Positive", 0.35, 0.50, True,
           "movies_data_collection.trailer_days_to_release timed against the -15%/+25% thresholds in the brief, scaled by trailer_views/comments"),
    Factor(47, "first_single_timing", "Timing of First Single Release", "Marketing", "Positive", 0.15, 0.25, True,
           "movies_data_collection.song_days_to_release; 6-8 week lead-up window scored as optimal"),
    Factor(48, "brand_extension_naming", "Use of Brand Extensions / Sequel Names", "Marketing", "Bidirectional", -0.30, 0.30, False,
           "Franchise detection is captured as baseline anchor R_IP; not separately calibrated here to avoid double-counting"),
    Factor(49, "viral_audio_trends", "Viral Music and Social Media Audio Trends", "Marketing", "Positive", 0.20, 0.35, False, "No social-audio virality feed"),
    Factor(50, "promotional_controversy", "Pre-Release Promotional Controversies", "Marketing", "Bidirectional", -0.15, 0.15, False, "No controversy feed"),
    Factor(51, "on_ground_events", "Star Attendance at On-Ground Events", "Marketing", "Positive", 0.10, 0.20, False, "No event-attendance data"),
    Factor(52, "micro_video_campaigns", "Micro-Video Social Media Campaigns", "Marketing", "Positive", 0.15, 0.25, False, "No campaign-spend/reach data"),
    Factor(53, "influencer_promotion", "Influencer-Driven Promotions", "Marketing", "Positive", 0.10, 0.15, False, "No influencer-campaign data"),
    Factor(54, "misleading_trailer", "Misleading Trailer Marketing", "Marketing", "Negative", -0.40, -0.25, False, "No trailer-content-vs-film-tone comparison"),
    Factor(55, "bts_promo_content", "High-Definition Promo / BTS Content", "Marketing", "Positive", 0.10, 0.15, False, "No BTS content metadata"),
    Factor(56, "countdown_posters", "Strategic Use of Countdown Posters", "Marketing", "Positive", 0.05, 0.10, False, "No poster-campaign data"),
    Factor(57, "oversaturated_marketing", "Excessive / Over-Saturated Marketing", "Marketing", "Negative", -0.15, -0.10, False, "No marketing-spend/frequency data"),
    Factor(58, "brand_partnerships", "Cross-Promotion and Brand Partnerships", "Marketing", "Positive", 0.10, 0.20, False, "No brand-tie-in data"),
    Factor(59, "dynamic_ticket_pricing", "Dynamic Pre-Release Ticket Pricing", "Marketing", "Bidirectional", -0.15, 0.15, False, "No ticket-pricing data"),
    Factor(60, "global_promo_tours", "Global Promotional Tours", "Marketing", "Positive", 0.15, 0.25, False, "No tour/appearance schedule data"),

    # --- Category 5: Temporal Scheduling, Holiday Windows, Market Dynamics
    Factor(61, "holiday_release_window", "Holiday Release Windows", "Timing", "Positive", 0.40, 0.60, True,
           "release_date scored against an approximate Indian festive-calendar window table"),
    Factor(62, "box_office_clashes", "Direct Box Office Clashes", "Timing", "Negative", -0.35, -0.20, True,
           "count of other same-language releases within +/-3 days in movies_data_collection"),
    Factor(63, "exam_schedules", "Student Examination Schedules", "Timing", "Negative", -0.25, -0.15, True,
           "release month falls in the Feb-Apr Indian board-exam season"),
    Factor(64, "political_events", "Political Events and Elections", "Timing", "Negative", -0.40, -0.20, False,
           "No per-state election-calendar table; national-only election-year flag was judged too coarse to calibrate reliably"),
    Factor(65, "ipl_sporting_events", "Major Sporting Events (e.g., IPL)", "Timing", "Negative", -0.20, -0.10, True,
           "release month falls in the Mar-May IPL season"),
    Factor(66, "summer_vacation_window", "Academic Summer Vacation Windows", "Timing", "Positive", 0.25, 0.35, True,
           "release month falls in the Apr-Jun summer-vacation window"),
    Factor(67, "extreme_weather", "Extreme Weather Conditions", "Timing", "Negative", -0.15, -0.10, False, "No weather data joined to release date/region"),
    Factor(68, "ott_window_strategy", "Theatrical Window / OTT Release Strategy", "Timing", "Bidirectional", -0.20, 0.20, False, "No OTT-release-date column"),
    Factor(69, "post_clash_spillover", "Post-Clash Spillover Audience", "Timing", "Positive", 0.10, 0.15, False, "No screen-sellout/spillover data"),
    Factor(70, "re_release_nostalgia", "Re-Release Timing and Nostalgia", "Timing", "Positive", 0.10, 0.20, False, "No re-release flag distinguishable from original release"),

    # --- Category 6: Legal, Administrative, and Censorship Barriers ------
    Factor(71, "cbfc_rating", "CBFC Rating Classifications (U vs. UA/A)", "Legal", "Bidirectional", -0.30, 0.30, False, "No certification column"),
    Factor(72, "state_bans", "Multi-State Political / Cultural Bans", "Legal", "Negative", -0.50, -0.30, False, "No ban/restriction data"),
    Factor(73, "pre_release_leak", "High-Definition Pre-Release Leak", "Legal", "Negative", -0.80, -0.60, False, "No piracy/leak-event data"),
    Factor(74, "title_ownership_disputes", "Legal Disputes over Title Ownership", "Legal", "Negative", -0.25, -0.15, False, "No litigation data"),
    Factor(75, "copyright_claims", "Copyright Claims on Visuals / Audio", "Legal", "Negative", -0.35, -0.20, False, "No litigation data"),
    Factor(76, "tax_exemptions", "Regional Entertainment Tax Exemptions", "Legal", "Positive", 0.15, 0.30, False, "No state-tax-policy data"),
    Factor(77, "distribution_disputes", "Inter-State Distribution Disputes", "Legal", "Negative", -0.30, -0.15, False, "No distributor-relationship data"),
    Factor(78, "plagiarism_remake_rights", "Plagiarism Allegations and Remake Laws", "Legal", "Bidirectional", -0.15, 0.15, False, "No plagiarism/remake-rights data"),
    Factor(79, "name_similarity_disputes", "Real-Life Personality Name Similarities", "Legal", "Negative", -0.20, -0.10, False, "No name-collision data"),
    Factor(80, "certification_delays", "Administrative Delays in Certifications", "Legal", "Negative", -0.40, -0.20, False, "No certification-timeline data"),

    # --- Category 7: Financial Controls and Distribution Models ----------
    Factor(81, "kdm_lockout", "Digital Key Delivery Message (KDM) Lockout", "Financial", "Negative", -0.60, -0.40, False, "No KDM/delivery-status data"),
    Factor(82, "minimum_guarantee_deals", "Minimum Guarantee (MG) Distribution", "Financial", "Positive", 0.20, 0.35, False, "No deal-structure data"),
    Factor(83, "outright_purchase_sales", "Outright Purchase Territorial Sales", "Financial", "Positive", 0.15, 0.25, False, "No deal-structure data"),
    Factor(84, "high_interest_financing", "High Interest Rates on Film Finance", "Financial", "Negative", -0.30, -0.15, False, "No financing-terms data"),
    Factor(85, "multiplex_revenue_splits", "Multiplex Revenue Share Splits", "Financial", "Bidirectional", -0.20, 0.20, False,
           "distributor_share_usd exists but is a post-hoc revenue decomposition, not a pre-release input; using it as a predictor would leak the target"),
    Factor(86, "subtitle_dubbing_quality", "Global Subtitle / Dubbing Quality", "Financial", "Positive", 0.15, 0.25, False, "No localization-quality rating"),
    Factor(87, "screen_count_allocation", "Screen Count Allocation and Show Pacing", "Financial", "Positive", 0.25, 0.40, False, "No screen-count data"),
    Factor(88, "pa_commitments", "Print & Advertising (P&A) Commitments", "Financial", "Positive", 0.20, 0.30, False, "No P&A-budget data"),
    Factor(89, "joint_production_partnerships", "Joint Production Partnerships", "Financial", "Positive", 0.15, 0.25, False,
           "production_companies exists but co-production vs. sole-production quality signal is unreliable at this data quality"),
    Factor(90, "producer_debt_solvency", "Producer Debt and Studio Solvency", "Financial", "Negative", -0.45, -0.25, False, "No studio-financials data"),
]

MEASURABLE_KEYS = [f.key for f in FACTOR_CATALOG if f.measurable]


# ---------------------------------------------------------------------------
# Database access
# ---------------------------------------------------------------------------

def get_connection(args: argparse.Namespace):
    return psycopg2.connect(
        host=args.db_host, port=args.db_port, dbname=args.db_name,
        user=args.db_user, password=args.db_password or None,
    )


# Columns the model would like to read. Several of these (`genres`, `imdb_rating`,
# `conflict_balance_score`, `narrative_novelty_score`) do not exist on the live
# `movies_data_collection` table today -- load_movies() below queries
# information_schema.columns at startup and only SELECTs whichever of these
# actually exist, then backfills the rest as an all-NaN column so every
# downstream reader (dedupe_movies's _completeness score, build_measurable_features's
# conflict_balance/narrative_novelty fallbacks, etc.) degrades to "no signal"
# instead of crashing -- and stays that way automatically if the schema drifts
# again later, without another hand-fix here.
WANTED_MOVIE_COLUMNS = [
    "movie_name", "release_date", "language", "country", "genre", "genres", "directors",
    "budget", "revenue", "runtime_mins", "imdb_rating", "rating_10",
    "gdp_usd_billions", "inflation_rate_pct",
    "trailer_release_date", "teaser_release_date", "first_song_release_date",
    "trailer_days_to_release", "teaser_days_to_release", "song_days_to_release",
    "trailer_views", "teaser_views", "trailer_comments", "teaser_comments",
    "conflict_balance_score", "narrative_novelty_score",
]

ACTORS_SQL = """
    SELECT actor_name, movie_name, release_date, language, genre, director,
           role_position
    FROM actors_data_collection
"""


def fetch_existing_columns(conn, table_name: str) -> set[str]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT column_name FROM information_schema.columns WHERE table_name = %s",
            (table_name,),
        )
        return {row[0] for row in cur.fetchall()}


def build_movies_sql(available_cols: list[str], restrict_to_india: bool) -> str:
    select_cols = ", ".join(available_cols)
    # `restrict_to_india=False` (the `global`/`all` --market modes) keeps every row
    # passing the budget/revenue/year floors regardless of language/country --
    # this is what stops the script from throwing away the ~9,033 usable
    # non-Indian rows that used to be hard-filtered out here unconditionally.
    where_market = (
        "\n      AND (language = ANY(%(indian_languages)s) OR btrim(country) = 'India')"
        if restrict_to_india else ""
    )
    return f"""
        SELECT {select_cols}
        FROM movies_data_collection
        WHERE revenue >= %(min_revenue)s
          AND budget > %(min_budget)s
          AND left(release_date, 4) ~ '^[0-9]{{4}}$'
          AND left(release_date, 4)::int > %(min_year)s{where_market}
    """


def load_movies(conn, min_year: int, min_budget: int, min_revenue: int,
                 restrict_to_india: bool) -> pd.DataFrame:
    existing = fetch_existing_columns(conn, "movies_data_collection")
    available_cols = [c for c in WANTED_MOVIE_COLUMNS if c in existing]
    missing_cols = [c for c in WANTED_MOVIE_COLUMNS if c not in existing]
    if missing_cols:
        print(f"Note: movies_data_collection is missing column(s) {missing_cols}; "
              f"treating them as always-NaN rather than failing the query.")

    sql = build_movies_sql(available_cols, restrict_to_india)
    df = pd.read_sql(
        sql, conn,
        params={
            "min_budget": min_budget, "min_year": min_year, "min_revenue": min_revenue,
            "indian_languages": list(INDIAN_LANGUAGES),
        },
    )
    for col in missing_cols:
        df[col] = np.nan
    return df


def load_actor_credits(conn) -> pd.DataFrame:
    return pd.read_sql(ACTORS_SQL, conn)


# ---------------------------------------------------------------------------
# Cleaning / entity resolution
# ---------------------------------------------------------------------------

def parse_release_year(s: str) -> Optional[int]:
    if not s:
        return None
    m = re.match(r"^(\d{4})", s.strip())
    return int(m.group(1)) if m else None


def parse_release_date(s: str) -> Optional[pd.Timestamp]:
    if not s:
        return None
    try:
        if re.match(r"^\d{4}-\d{2}-\d{2}$", s.strip()):
            return pd.Timestamp(s.strip())
    except (ValueError, TypeError):
        pass
    return None


def dedupe_movies(df: pd.DataFrame) -> pd.DataFrame:
    """movies_data_collection keys on (movie_name, release_date, language), so the
    same film appears once per dubbed-language release with identical budget/revenue.
    Randomly splitting those rows into train/test would leak the label across the
    split; collapse to one row per (movie, release year) first, preferring the most
    complete record."""
    df = df.copy()
    df["release_year"] = df["release_date"].map(parse_release_year)
    df = df[df["release_year"].notna()]
    df["release_year"] = df["release_year"].astype(int)
    df["movie_key"] = df["movie_name"].str.strip().str.lower() + "|" + df["release_year"].astype(str)

    df["_completeness"] = (
        df["conflict_balance_score"].notna().astype(int)
        + df["narrative_novelty_score"].notna().astype(int)
        + df["release_date"].str.match(r"^\d{4}-\d{2}-\d{2}$").fillna(False).astype(int)
        + df["directors"].fillna("").str.len().gt(0).astype(int)
    )
    df = df.sort_values(["_completeness", "revenue"], ascending=False)
    df = df.drop_duplicates(subset="movie_key", keep="first")
    return df.drop(columns=["_completeness"]).reset_index(drop=True)


FRANCHISE_TRAILING = re.compile(
    r"(?i)\s*[:\-–]?\s*(part|chapter|vol\.?|volume|book)\s+[ivxlcdm0-9]+\s*$"
)
FRANCHISE_ROMAN = re.compile(r"(?i)\s+[ivxlcdm]{1,6}\s*$")
FRANCHISE_NUMERAL = re.compile(r"\s+[0-9]{1,2}\s*$")


def franchise_stem(name: str) -> str:
    s = name.strip()
    s = FRANCHISE_TRAILING.sub("", s)
    s = FRANCHISE_ROMAN.sub("", s)
    s = FRANCHISE_NUMERAL.sub("", s)
    return s.strip().lower()


def primary_genre(genre_value: Optional[str]) -> str:
    if pd.isna(genre_value) or not str(genre_value).strip():
        return "unknown"
    return str(genre_value).split(",")[0].strip().lower()


def is_indian_language_field(language_value) -> bool:
    return bool(language_value) and bool(INDIAN_LANGUAGE_PATTERN.search(str(language_value)))


def is_india_market_row(language_value, country_value) -> bool:
    """Same predicate MOVIES_SQL used to use as a hard row filter -- now used as a
    per-row market tag instead, so a pooled global/all corpus can tell an Indian
    release from a non-Indian one without dropping the non-Indian rows."""
    if is_indian_language_field(language_value):
        return True
    return isinstance(country_value, str) and country_value.strip() == "India"


def name_tokens(name) -> set[str]:
    if not name:
        return set()
    return {p.strip().lower() for p in str(name).split(",") if p.strip()}


def known_indian_directors(actors_raw: pd.DataFrame) -> set[str]:
    """Directors credited on at least one Indian-language film anywhere in
    actors_data_collection. Used to catch rows that only entered the corpus via
    the country='India' fallback (e.g. a Hollywood title's India theatrical-release
    row) but aren't actually Indian productions -- country='India' alone matches
    India-market entries for foreign films, not just Indian-industry films."""
    indian_rows = actors_raw[actors_raw["language"].fillna("").map(is_indian_language_field)]
    names: set[str] = set()
    for val in indian_rows["director"].dropna():
        names |= name_tokens(val)
    return names


def filter_non_indian_productions(df: pd.DataFrame, actors_raw: pd.DataFrame) -> pd.DataFrame:
    """Drop rows that reached the corpus only via country='India' and whose
    resolved director has no Indian-language directing credit anywhere in
    actors_data_collection -- e.g. Project Hail Mary / The Devil Wears Prada 2 /
    IPL 2025, which matched the SQL filter's country fallback but are not Indian
    films (or, for IPL 2025, not a film at all)."""
    known_directors = known_indian_directors(actors_raw)
    is_indian_lang = df["language"].fillna("").map(is_indian_language_field)
    director_tokens = df["director_resolved"].map(name_tokens)
    director_is_known_indian = director_tokens.map(lambda toks: bool(toks & known_directors))
    keep = is_indian_lang | director_is_known_indian
    n_dropped = int((~keep).sum())
    if n_dropped:
        print(f"Dropped {n_dropped} rows that matched only via country='India' fallback "
              f"and have no Indian-language directing credit (e.g. foreign India-market releases).")
    return df[keep].reset_index(drop=True)


# ---------------------------------------------------------------------------
# Baseline anchors: R_star, R_director, R_concept, R_IP
# ---------------------------------------------------------------------------

def actor_popularity(films_before: int) -> float:
    """New actor 0.10-0.25, relatively known 0.25-0.70, well known 0.75-0.99,
    per the brief's bucket definitions; piecewise-linear within each bucket."""
    breakpoints_x = [0, 2, 14, 15, 35]
    breakpoints_y = [0.10, 0.25, 0.70, 0.75, 0.99]
    return float(np.interp(films_before, breakpoints_x, breakpoints_y))


def role_weight(role_position) -> float:
    if pd.isna(role_position):
        return 0.15
    if role_position == 1:
        return 1.0
    if role_position == 2:
        return 0.4
    return 0.15


def build_actor_features(actors: pd.DataFrame) -> pd.DataFrame:
    actors = actors.copy()
    actors["release_year"] = actors["release_date"].map(parse_release_year)
    actors = actors[actors["release_year"].notna()].copy()
    actors["release_year"] = actors["release_year"].astype(int)
    actors["actor_key"] = actors["actor_name"].str.strip().str.lower()
    actors = actors.sort_values(["actor_key", "release_year"])

    # Films-before-this-one, per actor, strictly prior years only (no same-year
    # peer leakage into popularity).
    actors["films_before"] = actors.groupby("actor_key")["release_year"].rank(method="first") - 1
    actors["popularity"] = actors["films_before"].map(actor_popularity)
    actors["role_wt"] = actors["role_position"].map(role_weight)
    return actors


def compute_r_star(movie_key: str, actors_by_movie: dict) -> float:
    rows = actors_by_movie.get(movie_key)
    if not rows:
        return np.nan
    num = sum(r["role_wt"] * r["popularity"] for r in rows)
    den = sum(r["role_wt"] for r in rows)
    return num / den if den > 0 else np.nan


def compute_release_overexposure(movie_key: str, actors_by_movie: dict, actors_by_actor: dict) -> float:
    """Factor 23 raw signal: how many OTHER releases each of this film's actors had
    in the trailing 12 months, averaged across the (role_position<=2) cast."""
    rows = actors_by_movie.get(movie_key)
    if not rows:
        return np.nan
    year = rows[0]["release_year"]
    leads = [r for r in rows if r["role_wt"] >= 0.4] or rows
    counts = []
    for r in leads:
        hist = actors_by_actor.get(r["actor_key"], [])
        c = sum(1 for h in hist if h["movie_key"] != movie_key and (year - 1) <= h["release_year"] <= year)
        counts.append(c)
    return float(np.mean(counts)) if counts else np.nan


def compute_historical_anchor(df: pd.DataFrame, group_col: str) -> pd.Series:
    """Strict prior-year historical mean ln(revenue) within a group (director or
    primary genre), then percentile-ranked into [0,1]. Prior-year-only avoids using
    a movie's own-year peers (or itself) to explain its own revenue."""
    out = pd.Series(index=df.index, dtype=float)
    df_sorted = df.sort_values("release_year")
    running: dict = {}
    for idx, row in df_sorted.iterrows():
        key = row[group_col]
        if pd.isna(key):
            out.loc[idx] = np.nan
            continue
        year = row["release_year"]
        hist = running.get(key, [])
        hist_prior = [v for (y, v) in hist if y < year]
        out.loc[idx] = float(np.mean(hist_prior)) if hist_prior else np.nan
        hist.append((year, row["ln_revenue"]))
        running[key] = hist
    if out.notna().sum() >= 5:
        ranks = out.rank(pct=True)
        return ranks.fillna(ranks.median() if ranks.notna().any() else 0.5)
    return pd.Series(0.5, index=df.index)


# ---------------------------------------------------------------------------
# Budget adjustment (inflation / GDP present-value normalization)
# ---------------------------------------------------------------------------

def adjust_budget(df: pd.DataFrame) -> pd.Series:
    """Present-values budget using the single inflation_rate_pct snapshot recorded
    per row (compounded forward to the corpus's most recent year) and normalizes by
    relative GDP scale, so a given nominal budget in a smaller/older economy year is
    treated as comparably 'large' to the same nominal budget today. This is a
    heuristic normalization built from the two macro columns actually present in the
    schema, not an official CPI deflator series (the table stores one inflation/GDP
    snapshot per row, not a full time series)."""
    ref_year = int(df["release_year"].max())
    gdp_recent = df.loc[df["release_year"] >= ref_year - 2, "gdp_usd_billions"]
    gdp_recent = gdp_recent[gdp_recent > 0]
    gdp_ref = float(gdp_recent.median()) if len(gdp_recent) else np.nan

    years_forward = (ref_year - df["release_year"]).clip(lower=0)
    infl = df["inflation_rate_pct"].fillna(0).clip(lower=-20, upper=50) / 100.0
    inflation_factor = (1 + infl) ** years_forward

    if np.isnan(gdp_ref):
        gdp_norm = pd.Series(1.0, index=df.index)
    else:
        gdp = df["gdp_usd_billions"]
        gdp_norm = np.where(gdp > 0, gdp_ref / gdp, 1.0)

    budget_pv = df["budget"] * inflation_factor * gdp_norm

    has_marketing_telemetry = df["trailer_days_to_release"].notna() | df["teaser_days_to_release"].notna()
    haircut = np.where(has_marketing_telemetry, 1.0, MARKETING_DATA_HAIRCUT)
    return budget_pv * haircut


# ---------------------------------------------------------------------------
# Measurable-factor proxies (raw signal -> corpus percentile -> banded value)
# ---------------------------------------------------------------------------

def percentile_into_band(raw: pd.Series, lo: float, hi: float) -> pd.Series:
    """Same convention as ConflictBalanceService/NarrativeNoveltyService: rank the
    raw signal to a corpus percentile, then rescale linearly into the factor's
    stated [min, max] band."""
    pct = raw.rank(pct=True, na_option="keep")
    pct = pct.fillna(0.5)
    return lo + pct * (hi - lo)


def compute_excessive_runtime_raw(df: pd.DataFrame) -> pd.Series:
    # Runtime under 160 min => 0 severity; scale up to 220+ min => full severity.
    over = (df["runtime_mins"].fillna(0) - 160).clip(lower=0, upper=60)
    return over  # higher = more excessive => percentile_into_band maps to the negative end


def compute_budget_scale_efficiency_raw(df: pd.DataFrame) -> pd.Series:
    peer_pct = df.groupby(["primary_genre", "release_year"])["budget"].rank(pct=True)
    # Lower relative budget within genre/year peers => higher efficiency signal.
    return 1.0 - peer_pct


def compute_trailer_timing_raw(df: pd.DataFrame) -> pd.Series:
    days = df["trailer_days_to_release"]
    views = np.log1p(df["trailer_views"].fillna(0))
    timing_score = pd.Series(np.nan, index=df.index)
    timing_score[days < 14] = -1.0
    timing_score[(days >= 14) & (days < 30)] = np.interp(days[(days >= 14) & (days < 30)], [14, 30], [-1.0, 1.0])
    timing_score[(days >= 30) & (days <= 45)] = 1.0
    timing_score[(days > 45) & (days <= 90)] = np.interp(days[(days > 45) & (days <= 90)], [45, 90], [1.0, 0.0])
    timing_score[days > 90] = -0.4
    engagement_boost = (views.rank(pct=True) - 0.5) * 0.5
    combined = timing_score.fillna(0) + engagement_boost.where(timing_score.notna(), 0)
    combined[timing_score.isna()] = np.nan
    return combined


def compute_song_timing_raw(df: pd.DataFrame) -> pd.Series:
    days = df["song_days_to_release"]
    # Optimal: 6-8 weeks (42-56 days) lead time.
    score = pd.Series(np.nan, index=df.index)
    score[days < 21] = -0.5
    score[(days >= 21) & (days < 42)] = np.interp(days[(days >= 21) & (days < 42)], [21, 42], [-0.5, 1.0])
    score[(days >= 42) & (days <= 56)] = 1.0
    score[days > 56] = np.interp(days[days > 56].clip(upper=120), [56, 120], [1.0, -0.2])
    return score


def in_festive_window(month: int, day: int) -> bool:
    for (sm, sd, em, ed) in FESTIVE_WINDOWS:
        if (month, day) >= (sm, sd) and (month, day) <= (em, ed):
            return True
    return False


def compute_holiday_window_raw(df: pd.DataFrame) -> pd.Series:
    parsed = df["release_date"].map(parse_release_date)
    flag = parsed.map(lambda d: in_festive_window(d.month, d.day) if d is not None else np.nan)
    return flag.astype(float)


def compute_clash_raw(df: pd.DataFrame) -> pd.Series:
    parsed = df["release_date"].map(parse_release_date)
    valid = parsed.notna()
    counts = pd.Series(np.nan, index=df.index)
    sub = df.loc[valid, ["language"]].copy()
    sub["date"] = parsed[valid]
    for idx, row in sub.iterrows():
        same_lang = sub[(sub["language"] == row["language"]) & (sub.index != idx)]
        window = same_lang[(same_lang["date"] - row["date"]).abs() <= pd.Timedelta(days=3)]
        counts.loc[idx] = len(window)
    return counts


def compute_exam_season_raw(df: pd.DataFrame) -> pd.Series:
    parsed = df["release_date"].map(parse_release_date)
    month = parsed.map(lambda d: d.month if d is not None else np.nan)
    return month.isin(EXAM_SEASON_MONTHS).where(month.notna()).astype(float)


def compute_ipl_season_raw(df: pd.DataFrame) -> pd.Series:
    parsed = df["release_date"].map(parse_release_date)
    month = parsed.map(lambda d: d.month if d is not None else np.nan)
    return month.isin(IPL_SEASON_MONTHS).where(month.notna()).astype(float)


def compute_summer_window_raw(df: pd.DataFrame) -> pd.Series:
    parsed = df["release_date"].map(parse_release_date)
    month = parsed.map(lambda d: d.month if d is not None else np.nan)
    return month.isin(SUMMER_VACATION_MONTHS).where(month.notna()).astype(float)


# Direction note: for Negative-direction factors the raw signal must be flipped
# before banding so that "more severity" maps toward stated_min (the more
# negative bound), not stated_max.
NEGATIVE_DIRECTION_FACTORS = {"star_overexposure", "excessive_runtime", "box_office_clashes",
                              "exam_schedules", "ipl_sporting_events"}


def build_measurable_features(df: pd.DataFrame, actors_by_movie: dict, actors_by_actor: dict) -> pd.DataFrame:
    feats = pd.DataFrame(index=df.index)
    # These four raw signals encode an Indian festival/exam/IPL/summer-vacation
    # calendar; on a pooled global/all corpus, applying them to a Japanese or US
    # release would be wrong, not just imprecise, so they're masked to NaN (which
    # percentile_into_band below resolves to the neutral band midpoint) for any
    # row that isn't an Indian-market release.
    india_mask = df["market_is_india"] == 1

    feats["conflict_balance"] = df["conflict_balance_score"]
    feats["conflict_balance"] = feats["conflict_balance"].fillna((0.25 + 0.35) / 2)

    feats["narrative_novelty"] = df["narrative_novelty_score"]
    feats["narrative_novelty"] = feats["narrative_novelty"].fillna((0.30 + 0.45) / 2)

    overexposure_raw = df["movie_key"].map(lambda k: compute_release_overexposure(k, actors_by_movie, actors_by_actor))
    feats["star_overexposure"] = percentile_into_band(-overexposure_raw, -0.25, -0.15)

    runtime_raw = compute_excessive_runtime_raw(df)
    feats["excessive_runtime"] = percentile_into_band(-runtime_raw, -0.30, -0.15)

    efficiency_raw = compute_budget_scale_efficiency_raw(df)
    feats["budget_scale_efficiency"] = percentile_into_band(efficiency_raw, -0.20, 0.20)

    trailer_raw = compute_trailer_timing_raw(df)
    feats["trailer_teaser_impact"] = percentile_into_band(trailer_raw, 0.35, 0.50)
    feats["_trailer_coverage_n"] = trailer_raw.notna().sum()

    song_raw = compute_song_timing_raw(df)
    feats["first_single_timing"] = percentile_into_band(song_raw, 0.15, 0.25)
    feats["_song_coverage_n"] = song_raw.notna().sum()

    holiday_raw = compute_holiday_window_raw(df).where(india_mask)
    feats["holiday_release_window"] = percentile_into_band(holiday_raw, 0.40, 0.60)

    clash_raw = compute_clash_raw(df)
    feats["box_office_clashes"] = percentile_into_band(-clash_raw, -0.35, -0.20)

    exam_raw = compute_exam_season_raw(df).where(india_mask)
    feats["exam_schedules"] = percentile_into_band(-exam_raw, -0.25, -0.15)

    ipl_raw = compute_ipl_season_raw(df).where(india_mask)
    feats["ipl_sporting_events"] = percentile_into_band(-ipl_raw, -0.20, -0.10)

    summer_raw = compute_summer_window_raw(df).where(india_mask)
    feats["summer_vacation_window"] = percentile_into_band(summer_raw, 0.25, 0.35)

    return feats


# ---------------------------------------------------------------------------
# Full feature assembly
# ---------------------------------------------------------------------------

def assemble_features(df: pd.DataFrame, actors: pd.DataFrame, apply_india_filter: bool = True) -> pd.DataFrame:
    df = df.copy()
    df["ln_revenue"] = np.log(df["revenue"].astype(float))
    df["primary_genre"] = df["genre"].map(primary_genre)
    df["primary_genre"] = df["primary_genre"].where(df["primary_genre"] != "unknown", df["genres"].map(primary_genre))

    # Per-row market tag (not gated by apply_india_filter -- it's needed on both
    # the india-only and pooled corpora): used both as a model feature so a pooled
    # model can learn market-specific baselines, and to gate the India-specific
    # calendar heuristics in build_measurable_features below.
    df["market_is_india"] = df.apply(
        lambda r: 1 if is_india_market_row(r.get("language"), r.get("country")) else 0, axis=1)

    stems = df["movie_name"].map(franchise_stem)
    stem_counts = stems.value_counts()
    df["franchise_flag"] = stems.map(lambda s: 1 if stem_counts.get(s, 0) >= 2 else 0)

    df["budget_effective"] = adjust_budget(df)
    df["ln_budget_effective"] = np.log(df["budget_effective"].clip(lower=1.0))

    actors_by_movie: dict = {}
    for _, row in actors.iterrows():
        key = row["movie_name"].strip().lower() + "|" + str(int(row["release_year"]))
        actors_by_movie.setdefault(key, []).append(row.to_dict())

    actors_by_actor: dict = {}
    for _, row in actors.iterrows():
        actors_by_actor.setdefault(row["actor_key"], []).append({
            "movie_key": row["movie_name"].strip().lower() + "|" + str(int(row["release_year"])),
            "release_year": int(row["release_year"]),
        })

    df["r_star"] = df["movie_key"].map(lambda k: compute_r_star(k, actors_by_movie))
    df["r_star"] = df["r_star"].fillna(df["r_star"].median())

    director_lookup = (
        actors.dropna(subset=["director"])
        .sort_values("release_year")
        .groupby(["movie_name", "release_year"])["director"]
        .first()
    )

    def lookup_director(name, year):
        try:
            return director_lookup.loc[(name, year)]
        except KeyError:
            return None

    df["director_resolved"] = df.apply(lambda r: lookup_director(r["movie_name"], r["release_year"]) or r["directors"], axis=1)
    df["director_key"] = df["director_resolved"].fillna("").str.strip().str.lower().replace("", np.nan)

    if apply_india_filter:
        # Only meaningful (and only run) when this row set was already restricted
        # to the Indian market at the SQL level (--market india) -- it drops rows
        # that matched only via the country='India' fallback but have no
        # Indian-language directing credit. On a pooled global/all corpus this
        # would wrongly discard genuine non-Indian productions, so it's skipped.
        df = filter_non_indian_productions(df, actors)

    df["r_director"] = compute_historical_anchor(df, "director_key")
    df["r_concept"] = compute_historical_anchor(df, "primary_genre")

    measurable = build_measurable_features(df, actors_by_movie, actors_by_actor)
    coverage_n = {
        "trailer_teaser_impact": int(measurable.pop("_trailer_coverage_n").iloc[0]) if "_trailer_coverage_n" in measurable else 0,
        "first_single_timing": int(measurable.pop("_song_coverage_n").iloc[0]) if "_song_coverage_n" in measurable else 0,
    }
    full = pd.concat([df, measurable], axis=1)
    full.attrs["coverage_n"] = coverage_n
    return full


# ---------------------------------------------------------------------------
# Modeling
# ---------------------------------------------------------------------------

BASELINE_ANCHOR_COLS = ["ln_budget_effective", "r_star", "r_director", "r_concept", "franchise_flag"]


def delta_regressor_col(key: str) -> str:
    return f"ln1p_{key}"


def prepare_model_frame(df: pd.DataFrame) -> pd.DataFrame:
    m = df.copy()
    for key in MEASURABLE_KEYS:
        if key in m.columns:
            m[delta_regressor_col(key)] = np.log1p(m[key])
    return m


def feature_columns(df: pd.DataFrame) -> list[str]:
    cols = list(BASELINE_ANCHOR_COLS)
    # Not folded into BASELINE_ANCHOR_COLS: those feed the Y = B0 * prod(1+delta_i)
    # formula's B0 term specifically, and market shouldn't change that formula's
    # meaning. It's still part of every direct predictive model's feature set
    # (compare_models/fit_predictive_model), which is what lets a pooled model
    # learn market-specific baselines.
    if "market_is_india" in df.columns:
        cols.append("market_is_india")
    for key in MEASURABLE_KEYS:
        c = delta_regressor_col(key)
        if c in df.columns:
            cols.append(c)
    return cols


def fit_ridge(train: pd.DataFrame, cols: list[str]) -> tuple[np.ndarray, StandardScaler, Ridge]:
    X = train[cols].fillna(0).values
    y = train["ln_revenue"].values
    scaler = StandardScaler()
    Xs = scaler.fit_transform(X)
    model = Ridge(alpha=RIDGE_ALPHA, random_state=RNG_SEED)
    model.fit(Xs, y)
    unscaled_coefs = model.coef_ / scaler.scale_
    return unscaled_coefs, scaler, model


def bootstrap_calibration(train: pd.DataFrame, cols: list[str], n_iters: int = BOOTSTRAP_ITERS) -> pd.DataFrame:
    rng = np.random.RandomState(RNG_SEED)
    n = len(train)
    draws = {c: [] for c in cols}
    for _ in range(n_iters):
        idx = rng.randint(0, n, size=n)
        sample = train.iloc[idx]
        try:
            coefs, _, _ = fit_ridge(sample, cols)
        except Exception:
            continue
        for c, v in zip(cols, coefs):
            draws[c].append(v)
    rows = []
    for c in cols:
        arr = np.array(draws[c]) if draws[c] else np.array([0.0])
        rows.append({
            "feature": c, "beta_p10": np.percentile(arr, 10),
            "beta_p50": np.percentile(arr, 50), "beta_p90": np.percentile(arr, 90),
            "n_bootstrap": len(arr),
        })
    return pd.DataFrame(rows).set_index("feature")


def time_based_split(df: pd.DataFrame, test_frac: float = 0.18) -> tuple[pd.DataFrame, pd.DataFrame]:
    years = np.sort(df["release_year"].unique())
    cutoff_idx = int(len(years) * (1 - test_frac))
    cutoff_year = years[max(cutoff_idx, 0)]
    train = df[df["release_year"] < cutoff_year]
    test = df[df["release_year"] >= cutoff_year]
    if len(train) < 30 or len(test) < 10:
        # Small corpus fallback: chronological 80/20 split by row order instead of by year.
        df_sorted = df.sort_values("release_year")
        split = int(len(df_sorted) * 0.82)
        train, test = df_sorted.iloc[:split], df_sorted.iloc[split:]
    return train, test


def fit_predictive_model(train: pd.DataFrame, test: pd.DataFrame, cols: list[str]) -> dict:
    Xtr, ytr = train[cols].fillna(0).values, train["ln_revenue"].values
    Xte, yte = test[cols].fillna(0).values, test["ln_revenue"].values

    gbr = GradientBoostingRegressor(n_estimators=300, max_depth=3, learning_rate=0.05, random_state=RNG_SEED)
    gbr.fit(Xtr, ytr)
    pred_log = gbr.predict(Xte)

    ridge_coefs, scaler, ridge_model = fit_ridge(train, cols)
    ridge_pred_log = ridge_model.predict(scaler.transform(Xte))

    def metrics(y_true_log, y_pred_log):
        y_true, y_pred = np.exp(y_true_log), np.exp(y_pred_log)
        return {
            "r2_log_space": r2_score(y_true_log, y_pred_log),
            "mae_log_space": mean_absolute_error(y_true_log, y_pred_log),
            "mape_original_scale_pct": mean_absolute_percentage_error(y_true, y_pred) * 100,
        }

    return {
        "gbr_test": metrics(yte, pred_log),
        "ridge_test": metrics(yte, ridge_pred_log),
        "gbr_feature_importance": dict(zip(cols, gbr.feature_importances_.tolist())),
        "n_train": len(train), "n_test": len(test),
    }


# ---------------------------------------------------------------------------
# Multi-model comparison, including a neural network (MLPRegressor)
# ---------------------------------------------------------------------------
# On ~1,400 rows and ~19 features a neural net is not guaranteed to beat a tree
# ensemble -- MLPs generally want much more data than tabular gradient boosting
# does to earn their extra flexibility. So this fits several candidates with the
# identical out-of-fold protocol and lets the accuracy numbers pick the winner,
# rather than assuming the neural net is "the accurate way" a priori.

MODEL_FACTORIES = {
    "ridge": lambda: Ridge(alpha=RIDGE_ALPHA, random_state=RNG_SEED),
    "gbr": lambda: GradientBoostingRegressor(n_estimators=300, max_depth=3, learning_rate=0.05, random_state=RNG_SEED),
    "hist_gbr": lambda: HistGradientBoostingRegressor(max_iter=300, max_depth=4, learning_rate=0.05, random_state=RNG_SEED),
    "mlp_neural_net": lambda: MLPRegressor(
        hidden_layer_sizes=(64, 32), activation="relu", alpha=1e-2, learning_rate_init=1e-3,
        early_stopping=True, n_iter_no_change=20, max_iter=2000, random_state=RNG_SEED),
}


def cross_validated_predictions_for(df: pd.DataFrame, cols: list[str], model_name: str,
                                     n_splits: int = CV_FOLDS) -> pd.Series:
    """Same out-of-fold protocol as cross_validated_predictions, generalized to any model
    in MODEL_FACTORIES. Standardizing inputs is required for Ridge/MLP and harmless for
    the tree models, so all four candidates run through one uniform pipeline."""
    X = df[cols].fillna(0).values
    y = df["ln_revenue"].values
    pred = np.full(len(df), np.nan)
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=RNG_SEED)
    for train_idx, test_idx in kf.split(X):
        scaler = StandardScaler()
        Xtr = scaler.fit_transform(X[train_idx])
        Xte = scaler.transform(X[test_idx])
        model = MODEL_FACTORIES[model_name]()
        model.fit(Xtr, y[train_idx])
        pred[test_idx] = model.predict(Xte)
    return pd.Series(pred, index=df.index)


def compare_models(df: pd.DataFrame, cols: list[str]) -> dict:
    if len(df) < CV_FOLDS * 2:
        return {"error": f"insufficient rows for {CV_FOLDS}-fold CV: n={len(df)}"}
    results = {}
    for name in MODEL_FACTORIES:
        pred_log = cross_validated_predictions_for(df, cols, name)
        _, summary = evaluate_full_corpus_accuracy(df, pred_log)
        results[name] = summary
    return results


def print_model_comparison(results: dict) -> None:
    print("\n-- Direct revenue-prediction model comparison (5-fold out-of-fold) --")
    if "error" in results:
        print(f"  skipped: {results['error']}")
        return
    print(f"  {'model':16s} {'median |%err|':>14s} {'within 20%':>13s} {'within 30%':>13s} {'within 50%':>13s}")
    for name, s in results.items():
        n = s["n_movies"]
        print(f"  {name:16s} {s['median_abs_pct_error']:>13.1f}% "
              f"{s['within_20pct']['n_correct']:>5d}/{n:<5d} ({s['within_20pct']['pct_correct']:>5.1f}%) "
              f"{s['within_30pct']['n_correct']:>5d}/{n:<5d} "
              f"{s['within_50pct']['n_correct']:>5d}/{n:<5d}")


# ---------------------------------------------------------------------------
# Feature 0 (4): india-only-as-today vs. pooled-global vs. per-market comparison
# ---------------------------------------------------------------------------
# Runs unconditionally on every invocation, independent of --market, so the
# effect of Feature 0's fix (pooling in the ~9,033 non-Indian rows the script
# used to hard-drop) is always visible in output/model_comparison.json's diff --
# not just when a particular --market flag happens to be passed for the rest
# of the pipeline.

def run_market_comparison(movies_raw_all: pd.DataFrame, actors: pd.DataFrame) -> dict:
    print("\nRunning india-only / pooled-global / per-market model comparison (Feature 0)...")

    india_mask_raw = movies_raw_all.apply(
        lambda r: is_india_market_row(r.get("language"), r.get("country")), axis=1)
    india_raw = movies_raw_all[india_mask_raw].reset_index(drop=True)

    def run_one(raw: pd.DataFrame, apply_india_filter: bool) -> dict:
        deduped = dedupe_movies(raw)
        assembled = assemble_features(deduped, actors, apply_india_filter=apply_india_filter)
        model_df = prepare_model_frame(assembled)
        cols = feature_columns(model_df)
        return compare_models(model_df, cols)

    # (a) India-only, exactly today's pipeline (SQL-level India restriction +
    # the director-based filter_non_indian_productions pass) -- the fixed
    # baseline every other number in this comparison is measured against.
    per_market_india = run_one(india_raw, apply_india_filter=True)

    # (b) Every market pooled together, with the market_is_india feature letting
    # the model learn a market-specific baseline instead of assuming India and
    # the US behave the same.
    pooled_deduped = dedupe_movies(movies_raw_all)
    pooled_assembled = assemble_features(pooled_deduped, actors, apply_india_filter=False)
    pooled_model_df = prepare_model_frame(pooled_assembled)
    pooled_cols = feature_columns(pooled_model_df)
    pooled_global = compare_models(pooled_model_df, pooled_cols)

    # (c) Two fully separate per-market models, split from the same pooled data
    # so (b) and (c) are directly comparable on identical rows/features.
    other_split = pooled_model_df[pooled_model_df["market_is_india"] == 0]
    per_market_other = compare_models(other_split, pooled_cols)

    return {
        "pooled_global": pooled_global,
        "per_market_india": per_market_india,
        "per_market_other": per_market_other,
    }


# ---------------------------------------------------------------------------
# Neural-network-calibrated factor min/max + formula-based revenue reconstruction
# ---------------------------------------------------------------------------
# This is the literal Y = B0 * prod(1 + delta_i) formula from the brief: B0 comes
# from a Ridge fit on the budget/quality baseline anchors alone, and each delta_i
# is a measurable factor's per-movie value rescaled from the literature
# [stated_min, stated_max] band into a band the neural net re-derives from data
# (via partial dependence, since an MLP has no single global coefficient the way
# Ridge does). Unmeasurable factors still can't be calibrated -- no model invents
# signal for a column that doesn't exist in the schema -- so they're left out of
# the formula the same way they're absent from the regression features above.

FACTOR_BY_KEY = {f.key: f for f in FACTOR_CATALOG}


def fit_baseline_model(train: pd.DataFrame) -> tuple[StandardScaler, Ridge]:
    X = train[BASELINE_ANCHOR_COLS].fillna(0).values
    y = train["ln_revenue"].values
    scaler = StandardScaler()
    Xs = scaler.fit_transform(X)
    model = Ridge(alpha=RIDGE_ALPHA, random_state=RNG_SEED)
    model.fit(Xs, y)
    return scaler, model


def fit_effect_model(train: pd.DataFrame, cols: list[str], model_name: str = "mlp_neural_net"):
    X = train[cols].fillna(0).values
    y = train["ln_revenue"].values
    scaler = StandardScaler()
    Xs = scaler.fit_transform(X)
    model = MODEL_FACTORIES[model_name]()
    model.fit(Xs, y)
    return scaler, model


def compute_factor_effects(scaler: StandardScaler, model, train: pd.DataFrame, cols: list[str]) -> dict:
    """For each measurable factor, sweep its feature from the literature stated_min to
    stated_max -- holding every other feature at each row's actual value, i.e. a proper
    partial-dependence average -- and read the model's average predicted swing in
    ln(revenue). That swing, per unit of the swept range, is the model's own learned
    effective 'coefficient' for the factor, used exactly the way the Ridge coefficient
    was used elsewhere in this script to rescale the literature band into a calibrated
    one -- just estimated by a model that can capture non-linear interactions instead of
    a linear fit."""
    X = train[cols].fillna(0).values
    effects = {}
    for key in MEASURABLE_KEYS:
        col = delta_regressor_col(key)
        if col not in cols:
            continue
        idx = cols.index(col)
        f = FACTOR_BY_KEY[key]
        lo_val, hi_val = np.log1p(f.stated_min), np.log1p(f.stated_max)
        X_lo, X_hi = X.copy(), X.copy()
        X_lo[:, idx] = lo_val
        X_hi[:, idx] = hi_val
        pred_lo = model.predict(scaler.transform(X_lo))
        pred_hi = model.predict(scaler.transform(X_hi))
        effect_log = float(np.mean(pred_hi - pred_lo))
        span = hi_val - lo_val
        effects[key] = effect_log / span if abs(span) > 1e-9 else 0.0
    return effects


def calibrate_factors_from_effects(effects: dict) -> dict:
    """Same CALIBRATION_CLIP philosophy as the Ridge-bootstrap calibration elsewhere in
    this script: a slope of 1.0 means the stated band looks about right as-is; clip to
    [0.30, 1.60] of the stated band so one noisy partial-dependence read can't flip a
    factor's sign or blow its range up to something implausible."""
    calibrated = {}
    for key, slope in effects.items():
        f = FACTOR_BY_KEY[key]
        mult = float(np.clip(slope, *CALIBRATION_CLIP))
        cal_a, cal_b = f.stated_min * mult, f.stated_max * mult
        calibrated[key] = (min(cal_a, cal_b), max(cal_a, cal_b), mult)
    return calibrated


def rescale_into_band(value: pd.Series, old_lo: float, old_hi: float, new_lo: float, new_hi: float) -> pd.Series:
    if abs(old_hi - old_lo) < 1e-9:
        return pd.Series(new_lo, index=value.index)
    frac = ((value - old_lo) / (old_hi - old_lo)).clip(0, 1)
    return new_lo + frac * (new_hi - new_lo)


def formula_predict_revenue(df: pd.DataFrame, baseline_scaler: StandardScaler, baseline_model: Ridge,
                             calibrated: dict) -> pd.Series:
    Xb = df[BASELINE_ANCHOR_COLS].fillna(0).values
    ln_revenue = baseline_model.predict(baseline_scaler.transform(Xb))
    for key, (cal_lo, cal_hi, _mult) in calibrated.items():
        if key not in df.columns:
            continue
        f = FACTOR_BY_KEY[key]
        delta_calibrated = rescale_into_band(df[key], f.stated_min, f.stated_max, cal_lo, cal_hi)
        ln_revenue = ln_revenue + np.log1p(delta_calibrated).values
    return pd.Series(ln_revenue, index=df.index)


def formula_reconstruction_oof(df: pd.DataFrame, cols: list[str], model_name: str = "mlp_neural_net",
                                n_splits: int = CV_FOLDS) -> pd.Series:
    """Honest out-of-fold version of the formula pipeline: B0 and the factor-effect
    calibration are both fit on the train fold only, then applied to the held-out fold --
    so the reported accuracy isn't inflated by calibrating a factor's band on the same
    rows it's later scored against."""
    pred = np.full(len(df), np.nan)
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=RNG_SEED)
    for train_idx, test_idx in kf.split(df):
        train_fold, test_fold = df.iloc[train_idx], df.iloc[test_idx]
        b_scaler, b_model = fit_baseline_model(train_fold)
        eff_scaler, eff_model = fit_effect_model(train_fold, cols, model_name)
        effects = compute_factor_effects(eff_scaler, eff_model, train_fold, cols)
        calibrated = calibrate_factors_from_effects(effects)
        pred[test_idx] = formula_predict_revenue(test_fold, b_scaler, b_model, calibrated).values
    return pd.Series(pred, index=df.index)


def build_nn_calibrated_factor_table(df: pd.DataFrame, cols: list[str], model_name: str = "mlp_neural_net") -> list[dict]:
    """Final reportable min/max per factor, fit once on the full corpus (as opposed to
    formula_reconstruction_oof's per-fold refits, which exist only to keep the accuracy
    check honest)."""
    eff_scaler, eff_model = fit_effect_model(df, cols, model_name)
    effects = compute_factor_effects(eff_scaler, eff_model, df, cols)
    calibrated = calibrate_factors_from_effects(effects)
    rows = []
    for f in FACTOR_CATALOG:
        if f.key in calibrated:
            cal_lo, cal_hi, mult = calibrated[f.key]
            rows.append({
                "id": f.id, "key": f.key, "name": f.name, "category": f.category,
                "stated_min": f.stated_min, "stated_max": f.stated_max,
                "calibrated_min": round(cal_lo, 4), "calibrated_max": round(cal_hi, 4),
                "calibration_multiplier": round(mult, 3), "source": f"{model_name}_partial_dependence",
            })
        else:
            rows.append({
                "id": f.id, "key": f.key, "name": f.name, "category": f.category,
                "stated_min": f.stated_min, "stated_max": f.stated_max,
                "calibrated_min": f.stated_min, "calibrated_max": f.stated_max,
                "calibration_multiplier": None, "source": "prior_literature",
            })
    return rows


def write_nn_outputs(nn_factor_table: list[dict], model_comparison: dict,
                      formula_predictions: pd.DataFrame, formula_summary: dict, output_dir: str) -> None:
    os.makedirs(output_dir, exist_ok=True)
    pd.DataFrame(nn_factor_table).to_csv(os.path.join(output_dir, "factor_impact_scores_nn_calibrated.csv"), index=False)
    with open(os.path.join(output_dir, "model_comparison.json"), "w") as f:
        json.dump(model_comparison, f, indent=2, default=float)
    formula_predictions.sort_values("abs_pct_error").to_csv(
        os.path.join(output_dir, "formula_revenue_predictions.csv"), index=False)
    with open(os.path.join(output_dir, "formula_accuracy_summary.json"), "w") as f:
        json.dump(formula_summary, f, indent=2, default=float)
    print(f"Wrote {output_dir}/factor_impact_scores_nn_calibrated.csv, model_comparison.json, "
          f"formula_revenue_predictions.csv, formula_accuracy_summary.json")


# ---------------------------------------------------------------------------
# Full-corpus out-of-fold prediction accuracy
# ---------------------------------------------------------------------------

def cross_validated_predictions(df: pd.DataFrame, cols: list[str], n_splits: int = CV_FOLDS) -> pd.Series:
    """Out-of-fold ln(revenue) prediction for every row via K-fold GBR, so each
    movie is scored by a model that never saw its own revenue during training --
    unlike just refitting on the full corpus and predicting back on it, which
    would report in-sample fit and overstate accuracy."""
    X = df[cols].fillna(0).values
    y = df["ln_revenue"].values
    pred = np.full(len(df), np.nan)
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=RNG_SEED)
    for train_idx, test_idx in kf.split(X):
        gbr = GradientBoostingRegressor(n_estimators=300, max_depth=3, learning_rate=0.05, random_state=RNG_SEED)
        gbr.fit(X[train_idx], y[train_idx])
        pred[test_idx] = gbr.predict(X[test_idx])
    return pd.Series(pred, index=df.index)


def evaluate_full_corpus_accuracy(df: pd.DataFrame, pred_ln_revenue: pd.Series,
                                   thresholds: tuple = ACCURACY_THRESHOLDS) -> tuple[pd.DataFrame, dict]:
    actual = df["revenue"].astype(float)
    predicted = np.exp(pred_ln_revenue)
    abs_pct_error = (predicted - actual).abs() / actual

    rows = pd.DataFrame({
        "movie_name": df["movie_name"], "release_year": df["release_year"],
        "actual_revenue": actual, "predicted_revenue": predicted.round(0),
        "abs_pct_error": abs_pct_error.round(4),
    })
    for t in thresholds:
        rows[f"within_{int(t * 100)}pct"] = abs_pct_error <= t

    summary = {"n_movies": len(rows)}
    for t in thresholds:
        n_correct = int((abs_pct_error <= t).sum())
        summary[f"within_{int(t * 100)}pct"] = {
            "n_correct": n_correct,
            "pct_correct": round(100 * n_correct / len(rows), 1),
        }
    summary["median_abs_pct_error"] = round(float(abs_pct_error.median()) * 100, 1)
    return rows, summary


def print_accuracy_report(summary: dict) -> None:
    print("\n-- Full-corpus revenue prediction accuracy (5-fold out-of-fold GBR) --")
    print(f"  Movies scored: {summary['n_movies']}")
    print(f"  Median absolute % error: {summary['median_abs_pct_error']}%")
    for key, val in summary.items():
        if key.startswith("within_"):
            pct = key.replace("within_", "").replace("pct", "")
            print(f"  Predicted within +/-{pct}% of actual revenue: {val['n_correct']}/{summary['n_movies']} "
                  f"({val['pct_correct']}%)")


# ---------------------------------------------------------------------------
# Calibrated factor table
# ---------------------------------------------------------------------------

def calibrate_factor_table(df: pd.DataFrame, bootstrap: pd.DataFrame) -> list[dict]:
    out = []
    coverage_n = df.attrs.get("coverage_n", {})
    for f in FACTOR_CATALOG:
        row = {
            "id": f.id, "key": f.key, "name": f.name, "category": f.category,
            "direction": f.direction, "stated_min": f.stated_min, "stated_max": f.stated_max,
            "proxy_note": f.proxy_note,
        }
        if not f.measurable:
            row.update({
                "calibrated_min": f.stated_min, "calibrated_max": f.stated_max,
                "source": "prior_literature", "n_obs": None, "beta_p50": None,
            })
            out.append(row)
            continue

        col = delta_regressor_col(f.key)
        if col not in bootstrap.index:
            row.update({
                "calibrated_min": f.stated_min, "calibrated_max": f.stated_max,
                "source": "prior_literature_fallback", "n_obs": None, "beta_p50": None,
            })
            out.append(row)
            continue

        b = bootstrap.loc[col]
        lo_mult = float(np.clip(b["beta_p10"], *CALIBRATION_CLIP))
        hi_mult = float(np.clip(b["beta_p90"], *CALIBRATION_CLIP))
        mid_mult = float(np.clip(b["beta_p50"], *CALIBRATION_CLIP))
        cal_a, cal_b = f.stated_min * lo_mult, f.stated_max * hi_mult
        cal_min, cal_max = min(cal_a, cal_b), max(cal_a, cal_b)

        n_obs = len(df)
        if f.key in ("trailer_teaser_impact", "first_single_timing"):
            n_obs = coverage_n.get(f.key, 0)

        row.update({
            "calibrated_min": round(cal_min, 4), "calibrated_max": round(cal_max, 4),
            "calibration_point_multiplier": round(mid_mult, 3),
            "source": "data_fitted", "n_obs": n_obs, "beta_p50": round(float(b["beta_p50"]), 4),
        })
        out.append(row)
    return out


def calibrate_baseline_anchors(bootstrap: pd.DataFrame) -> dict:
    result = {}
    for col in BASELINE_ANCHOR_COLS:
        if col in bootstrap.index:
            b = bootstrap.loc[col]
            result[col] = {
                "beta_p10": round(float(b["beta_p10"]), 4),
                "beta_p50": round(float(b["beta_p50"]), 4),
                "beta_p90": round(float(b["beta_p90"]), 4),
            }
    return result


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def print_report(factor_table: list[dict], baseline: dict, model_metrics: dict, n_train: int, n_test: int) -> None:
    print("\n" + "=" * 100)
    print("INDIAN BOX-OFFICE REVENUE MODEL — FACTOR IMPACT CALIBRATION")
    print("=" * 100)
    print(f"Training rows: {n_train}   Held-out test rows: {n_test}")
    print("\n-- Predictive accuracy (revenue, held-out test set, time-based split) --")
    for name, m in [("GradientBoostingRegressor", model_metrics["gbr_test"]),
                    ("Ridge (interpretable)", model_metrics["ridge_test"])]:
        print(f"  {name:28s} R2(log)={m['r2_log_space']:.3f}  MAE(log)={m['mae_log_space']:.3f}  "
              f"MAPE(original $)={m['mape_original_scale_pct']:.1f}%")

    print("\n-- Baseline formula anchors (B0 = budget * (R_star+R_director+R_concept) * R_IP) --")
    for k, v in baseline.items():
        print(f"  {k:20s} fitted coefficient p10/p50/p90 = {v['beta_p10']:+.3f} / {v['beta_p50']:+.3f} / {v['beta_p90']:+.3f}")

    print("\n-- Compounding factor impact scores (Y = B0 * prod(1 + delta_i)) --")
    by_cat: dict = {}
    for row in factor_table:
        by_cat.setdefault(row["category"], []).append(row)

    for cat, rows in by_cat.items():
        print(f"\n  [{cat}]")
        for r in sorted(rows, key=lambda x: x["id"]):
            src = "DATA-FITTED" if r["source"] == "data_fitted" else "prior"
            n_note = f" n={r['n_obs']}" if r.get("n_obs") else ""
            print(f"    {r['id']:>3}. {r['name']:<48s} "
                  f"[{r['calibrated_min']:+.0%}, {r['calibrated_max']:+.0%}]  "
                  f"(stated [{r['stated_min']:+.0%},{r['stated_max']:+.0%}])  {src}{n_note}")
    print("\n" + "=" * 100)


def write_outputs(factor_table: list[dict], baseline: dict, model_metrics: dict, output_dir: str) -> None:
    os.makedirs(output_dir, exist_ok=True)
    with open(os.path.join(output_dir, "factor_impact_scores.json"), "w") as f:
        json.dump({
            "generated": str(date.today()),
            "baseline_anchors": baseline,
            "model_metrics": model_metrics,
            "factors": factor_table,
        }, f, indent=2, default=float)

    pd.DataFrame(factor_table).to_csv(os.path.join(output_dir, "factor_impact_scores.csv"), index=False)
    print(f"\nWrote {output_dir}/factor_impact_scores.json and .csv")


def write_prediction_outputs(predictions: pd.DataFrame, summary: dict, output_dir: str) -> None:
    os.makedirs(output_dir, exist_ok=True)
    predictions.sort_values("abs_pct_error").to_csv(
        os.path.join(output_dir, "movie_revenue_predictions.csv"), index=False)
    with open(os.path.join(output_dir, "revenue_accuracy_summary.json"), "w") as f:
        json.dump(summary, f, indent=2, default=float)
    print(f"Wrote {output_dir}/movie_revenue_predictions.csv and revenue_accuracy_summary.json")


# ---------------------------------------------------------------------------
# Optional AuraLLM (Claude) sanity pass for prior-only factors
# ---------------------------------------------------------------------------

def aura_llm_prior_commentary(factor_table: list[dict], api_key: Optional[str]) -> Optional[dict]:
    """Best-effort, OFF by default (--use-llm to enable). For the ~68 factors this
    schema cannot fit directly, ask Claude (the same model family AuraMath's
    ClaudeLlmClient/nlq engine uses) for a short qualitative gut-check on whether
    the literature-stated band still looks reasonable for the modern Indian market
    -- explanatory context only, it never overrides a fitted numeric range."""
    if not api_key:
        print("(--use-llm set but no Anthropic API key found; skipping AuraLLM commentary)")
        return None
    try:
        import anthropic
    except ImportError:
        print("(--use-llm set but the 'anthropic' package is not installed; skipping)")
        return None

    client = anthropic.Anthropic(api_key=api_key)
    prior_only = [f for f in factor_table if f["source"] != "data_fitted"][:8]
    listing = "\n".join(f"- {f['name']} ({f['direction']}, stated {f['stated_min']:+.0%} to {f['stated_max']:+.0%})"
                         for f in prior_only)
    prompt = (
        "You are sanity-checking box-office impact-factor ranges for Indian films "
        "that a data pipeline could not measure directly. For each factor below, "
        "in one short sentence say whether the stated range still looks reasonable "
        "for the modern Indian theatrical market, or should be treated cautiously. "
        "Do not propose new numeric ranges — only qualitative commentary.\n\n" + listing
    )
    resp = client.messages.create(
        model="claude-sonnet-5", max_tokens=600, messages=[{"role": "user", "content": prompt}],
    )
    text = resp.content[0].text if resp.content else ""
    return {"prior_factors_reviewed": [f["key"] for f in prior_only], "commentary": text}


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--min-year", type=int, default=MIN_RELEASE_YEAR)
    p.add_argument("--min-budget", type=int, default=MIN_BUDGET_USD)
    p.add_argument("--min-revenue", type=int, default=MIN_REVENUE_USD)
    p.add_argument("--output-dir", default="./output")
    p.add_argument("--market", choices=["india", "global", "all"], default="all",
                    help="Row set for the primary training pipeline (bootstrap calibration, "
                         "factor table, single-model report, movie_revenue_predictions.csv): "
                         "'india' replicates the historical Indian-only-market filtering; "
                         "'global'/'all' pool every market (same behavior for both) with a "
                         "market_is_india feature. The india-only-vs-pooled-global-vs-"
                         "per-market model comparison in model_comparison.json always runs "
                         "regardless of this flag.")
    p.add_argument("--use-llm", action="store_true", help="Ask AuraLLM (Claude) for qualitative commentary on prior-only factors")
    return p.parse_args()


def main() -> None:
    args = parse_args()

    print(f"Connecting to postgresql://{args.db_user}@{args.db_host}:{args.db_port}/{args.db_name} ...")
    conn = get_connection(args)
    try:
        # Always load every market at the SQL level (restrict_to_india=False) --
        # the india-only row set (for --market india, and for the fixed baseline
        # half of the market comparison below) is derived from this in Python via
        # is_india_market_row, rather than round-tripping the DB a second time.
        movies_raw_all = load_movies(conn, args.min_year, args.min_budget, args.min_revenue,
                                      restrict_to_india=False)
        actors_raw = load_actor_credits(conn)
    finally:
        conn.close()
    print(f"Loaded {len(movies_raw_all)} raw movie rows (all markets), {len(actors_raw)} actor-credit rows.")

    actors = build_actor_features(actors_raw)

    if args.market == "india":
        india_mask_raw = movies_raw_all.apply(
            lambda r: is_india_market_row(r.get("language"), r.get("country")), axis=1)
        primary_raw = movies_raw_all[india_mask_raw].reset_index(drop=True)
        apply_india_filter = True
    else:
        primary_raw = movies_raw_all
        apply_india_filter = False

    movies = dedupe_movies(primary_raw)
    print(f"Deduped to {len(movies)} distinct (movie, release year) entities (--market {args.market}).")

    full = assemble_features(movies, actors, apply_india_filter=apply_india_filter)
    model_df = prepare_model_frame(full)

    train, test = time_based_split(model_df)
    cols = feature_columns(model_df)

    bootstrap = bootstrap_calibration(train, cols)
    model_metrics = fit_predictive_model(train, test, cols)

    factor_table = calibrate_factor_table(full, bootstrap)
    baseline = calibrate_baseline_anchors(bootstrap)

    print_report(factor_table, baseline, model_metrics, len(train), len(test))
    write_outputs(factor_table, baseline, model_metrics, args.output_dir)

    oof_pred_log = cross_validated_predictions(model_df, cols)
    predictions, accuracy_summary = evaluate_full_corpus_accuracy(model_df, oof_pred_log)
    print_accuracy_report(accuracy_summary)
    write_prediction_outputs(predictions, accuracy_summary, args.output_dir)

    print("\nComparing direct revenue-prediction models (Ridge / GBR / HistGBR / MLP neural net)...")
    model_comparison = compare_models(model_df, cols)
    print_model_comparison(model_comparison)

    # Feature 0 (4): india-only-as-today vs. pooled-global vs. per-market, always
    # computed and written under new keys so the improvement (or lack of it) from
    # pooling markets is visible in model_comparison.json's diff every run,
    # regardless of what --market was passed for the primary pipeline above.
    market_comparison = run_market_comparison(movies_raw_all, actors)
    model_comparison.update(market_comparison)
    print("\n-- India-only (today) vs. pooled-global vs. per-market comparison --")
    for label in ("per_market_india", "pooled_global", "per_market_other"):
        print(f"\n[{label}]")
        print_model_comparison(market_comparison[label])

    print("\nCalibrating factor min/max via MLP neural-net partial dependence, "
          "then reconstructing revenue via Y = B0 * prod(1 + delta_i)...")
    nn_factor_table = build_nn_calibrated_factor_table(model_df, cols)
    formula_pred_log = formula_reconstruction_oof(model_df, cols)
    formula_predictions, formula_summary = evaluate_full_corpus_accuracy(model_df, formula_pred_log)
    print("\n-- Formula-based revenue reconstruction accuracy "
          "(Y = B0 * prod(1+delta_i), MLP-calibrated deltas, out-of-fold) --")
    print_accuracy_report(formula_summary)
    write_nn_outputs(nn_factor_table, model_comparison, formula_predictions, formula_summary, args.output_dir)

    if args.use_llm:
        api_key = os.environ.get("ANTHROPIC_API_KEY")
        commentary = aura_llm_prior_commentary(factor_table, api_key)
        if commentary:
            with open(os.path.join(args.output_dir, "aura_llm_commentary.json"), "w") as f:
                json.dump(commentary, f, indent=2)
            print(f"Wrote {args.output_dir}/aura_llm_commentary.json")


if __name__ == "__main__":
    main()
