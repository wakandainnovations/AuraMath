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
12 today (`status='active'` rows in the live `factor_definitions` registry --
Feature 2, see scripts/registry/) plus the four baseline anchors. The
remaining ~68 (mostly VFX/BGM/controversy/legal/distribution factors —
categories 3, 6, 7 in particular) have no corresponding column anywhere in
this database; for those the script is explicit that it is reporting the
literature-supplied prior band unmodified, tagged `source="prior_literature"`,
rather than fabricating a fitted number from a signal that does not exist.
That gap is consistent with the brief's own note that unlisted/unmeasured
factors contribute a real 10-25% of variance this model cannot attribute --
and it is closeable over time without touching this script, by registering a
new factor via `scripts/register_factor.py` once real data backs it.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import date
from typing import Callable, Optional

import numpy as np
import pandas as pd
import psycopg2
from sklearn.ensemble import (
    GradientBoostingClassifier, GradientBoostingRegressor, HistGradientBoostingRegressor,
)
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.metrics import (
    accuracy_score, mean_absolute_error, mean_absolute_percentage_error, r2_score, roc_auc_score,
)
from sklearn.model_selection import KFold
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.schema import movie_entity_key  # noqa: E402
from registry.schema import (  # noqa: E402
    ensure_factor_registry_schema, fetch_factor_definitions, fetch_movie_factor_values,
)

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

# Feature 2 coverage guard: a candidate/active factor only feeds the trained
# model once its non-null coverage on the current run's rows clears this
# threshold (percent, not fraction) -- keeps a brand-new sparsely-populated
# factor from being force-fit into every run before enough data exists for it
# to mean anything, while it's still visible in the coverage report from day
# one. Overridable via --min-feature-coverage.
DEFAULT_MIN_FEATURE_COVERAGE_PCT = 5.0

# India-only calendar heuristics (Feature 0): applying an Indian festival/
# exam/IPL/summer-vacation window to a Japanese or US release would be wrong,
# not just imprecise, so these four derived_python_fn factors are masked to
# NaN outside the Indian market regardless of what the registry row says.
INDIA_ONLY_CALENDAR_FACTOR_KEYS = {
    "holiday_release_window", "exam_schedules", "ipl_sporting_events", "summer_vacation_window",
}

# Feature 4: budget/revenue disclosure is MNAR (obscure/low-budget/foreign
# films are both less likely to have tracked financials AND more likely to
# have underperformed), not missing at random -- so instead of just training
# Stage B's revenue regressor on the ~3-6% of rows with usable budget/revenue
# and ignoring the selection effect, Stage A models P(disclosed) on the full
# corpus and that probability feeds an inverse-probability weight into Stage B
# plus a per-row confidence-band width. See fit_disclosure_classifier() below.
DISCLOSURE_TOP_CATEGORIES = 20
# Clip 1/P(disclosed) so a handful of near-zero-probability rows (a genuinely
# obscure film that happens to have leaked financials anyway) don't dominate
# Stage B's loss -- same clipping philosophy as CALIBRATION_CLIP above.
IPW_WEIGHT_CLIP = (1.0, 20.0)
# Bagged-bootstrap prediction-interval settings for the per-row confidence
# band (reduced n_estimators vs. the primary GBR since this refits
# CONFIDENCE_BOOTSTRAP_ITERS times just to read the spread of the estimate,
# not to optimize point-prediction accuracy).
CONFIDENCE_BOOTSTRAP_ITERS = 30
CONFIDENCE_GBR_PARAMS = dict(n_estimators=150, max_depth=3, learning_rate=0.05, random_state=RNG_SEED)
# Band half-width multiplier at disclosure_likelihood = 0 (few/no comparable
# disclosed films back this estimate -- widen) vs. = 1 (well-covered -- narrow).
CONFIDENCE_WIDTH_MULTIPLIER_RANGE = (1.6, 0.7)
# Feature 2 note: the 80-entry business-supplied factor catalogue (categories
# Narrative/Cast/Production/Marketing/Timing/Legal/Financial, each with a
# stated min/max impact range) used to be a hardcoded `FACTOR_CATALOG` list
# right here. It has been replaced by the live `factor_definitions` Postgres
# table (see scripts/registry/schema.py) -- the direct answer to "let me add
# more parameters in the future without touching the code": register a new
# factor via `scripts/register_factor.py` or `POST /api/admin/factor-definitions`
# and the next run picks it up automatically, no script edit required. The
# original catalogue data is preserved verbatim in
# scripts/registry/seed_catalog.py, which `migrate_factor_definitions.py` uses
# to seed the table once. `load_factor_registry()` below loads it into the
# module-level `FACTOR_DEFS`/`FACTOR_BY_KEY` globals at the start of every run.


# ---------------------------------------------------------------------------
# Database access
# ---------------------------------------------------------------------------

def get_connection(args: argparse.Namespace):
    return psycopg2.connect(
        host=args.db_host, port=args.db_port, dbname=args.db_name,
        user=args.db_user, password=args.db_password or None,
    )


# ---------------------------------------------------------------------------
# Factor registry (Feature 2) -- loaded once per run into these module-level
# globals, read by every downstream feature-assembly/reporting function
# instead of the old hardcoded FACTOR_CATALOG list. See registry/schema.py
# and registry/seed_catalog.py.
# ---------------------------------------------------------------------------

FACTOR_DEFS: list[dict] = []
FACTOR_BY_KEY: dict[str, dict] = {}


def load_factor_registry(conn) -> list[dict]:
    """Populates FACTOR_DEFS/FACTOR_BY_KEY from the live factor_definitions
    table. Call once near the start of a run (main() does this right after
    connecting), before assemble_features/feature_columns/prepare_model_frame
    or any of the factor-effect/calibration functions are used -- they all
    read these globals rather than taking the registry as a parameter, to
    avoid threading it through every function signature in a script this
    size."""
    global FACTOR_DEFS, FACTOR_BY_KEY
    ensure_factor_registry_schema(conn)
    FACTOR_DEFS = fetch_factor_definitions(conn)
    FACTOR_BY_KEY = {f["factor_key"]: f for f in FACTOR_DEFS}
    if not FACTOR_DEFS:
        print("Warning: factor_definitions is empty -- run migrate_factor_definitions.py "
              "first to seed the 80-entry catalogue. Proceeding with baseline-anchors-only "
              "features (ln_budget_effective, r_star, r_director, r_concept, franchise_flag).")
    return FACTOR_DEFS


def load_eav_lookup(conn) -> dict[str, dict[str, float]]:
    """{factor_key: {movie_key: value_numeric}} for every candidate/active
    factor whose computation_type is 'eav' -- the movie_factor_values-backed
    factors. Requires load_factor_registry() to have run first."""
    eav_keys = [f["factor_key"] for f in FACTOR_DEFS
                if f.get("computation_type") == "eav" and f["status"] in ("active", "candidate")]
    return fetch_movie_factor_values(conn, eav_keys)


# Columns the model would like to read. Several of these (`genres`, `imdb_rating`,
# `conflict_balance_score`, `narrative_novelty_score`) do not exist on the live
# `movies_data_collection` table today -- load_movies() below queries
# information_schema.columns at startup and only SELECTs whichever of these
# actually exist, then backfills the rest as an all-NaN column so every
# downstream reader (dedupe_movies's _completeness score, compute_registry_features's
# conflict_balance/narrative_novelty raw_column fallbacks, etc.) degrades to
# "no signal" instead of crashing -- and stays that way automatically if the
# schema drifts again later, without another hand-fix here.
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


def build_movies_sql(available_cols: list[str], restrict_to_india: bool,
                      require_financials: bool = True) -> str:
    select_cols = ", ".join(available_cols)
    # `restrict_to_india=False` (the `global`/`all` --market modes) keeps every row
    # passing the budget/revenue/year floors regardless of language/country --
    # this is what stops the script from throwing away the ~9,033 usable
    # non-Indian rows that used to be hard-filtered out here unconditionally.
    where_market = (
        "\n      AND (language = ANY(%(indian_languages)s) OR btrim(country) = 'India')"
        if restrict_to_india else ""
    )
    # `require_financials=False` (Feature 4's disclosure classifier) drops the
    # budget/revenue floors entirely so the query returns the full corpus --
    # every row with a parseable release year, disclosed or not -- instead of
    # just the ~3-6% of rows the rest of the pipeline trains the revenue
    # regressor on. has_financials is then derived in pandas from the raw
    # (possibly null/zero) budget/revenue columns.
    where_financials = (
        "revenue >= %(min_revenue)s\n          AND budget > %(min_budget)s\n          AND "
        if require_financials else ""
    )
    return f"""
        SELECT {select_cols}
        FROM movies_data_collection
        WHERE {where_financials}left(release_date, 4) ~ '^[0-9]{{4}}$'
          AND left(release_date, 4)::int > %(min_year)s{where_market}
    """


def load_movies(conn, min_year: int, min_budget: int, min_revenue: int,
                 restrict_to_india: bool, require_financials: bool = True) -> pd.DataFrame:
    existing = fetch_existing_columns(conn, "movies_data_collection")
    available_cols = [c for c in WANTED_MOVIE_COLUMNS if c in existing]
    missing_cols = [c for c in WANTED_MOVIE_COLUMNS if c not in existing]
    if missing_cols:
        print(f"Note: movies_data_collection is missing column(s) {missing_cols}; "
              f"treating them as always-NaN rather than failing the query.")

    sql = build_movies_sql(available_cols, restrict_to_india, require_financials)
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


def has_financials_mask(df: pd.DataFrame) -> pd.Series:
    """budget/revenue null-or-<=0 counts as 'not disclosed' -- Stage A's
    (Feature 4) classification target and the same predicate the old SQL-level
    floor used to enforce unconditionally."""
    return (df["budget"] > 0) & (df["revenue"] > 0)


def filter_financials(df: pd.DataFrame, min_budget: int, min_revenue: int) -> pd.DataFrame:
    """Applies the same budget/revenue floor build_movies_sql used to apply at
    the SQL level, in pandas -- lets main() load the full corpus once
    (require_financials=False, for Stage A) and derive the disclosed subset
    the rest of the pipeline already expects from it, instead of a second
    round-trip to Postgres with the floor re-applied at the SQL level."""
    return df[(df["budget"] > min_budget) & (df["revenue"] >= min_revenue)].reset_index(drop=True)


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


# Feature 2: registry-driven factor resolution. Each entry is a raw-signal
# function keyed by `derivation_ref` (matched against factor_definitions rows
# with computation_type='derived_python_fn') -- this is where the raw compute
# logic above (compute_holiday_window_raw etc.) plugs in "by name instead of
# hardcoded into build_measurable_features", per the plan. Every function
# here has the same (df, actors_by_movie, actors_by_actor) -> raw pd.Series
# signature so resolve_factor_raw_and_banded can call any of them uniformly;
# the raw signal is pre-negation, pre-band -- direction-based sign flip and
# stated_min/max banding happen once, generically, in the resolver below.
DERIVED_FACTOR_FNS: dict[str, Callable[[pd.DataFrame, dict, dict], pd.Series]] = {
    "star_overexposure": lambda df, abm, aba: df["movie_key"].map(
        lambda k: compute_release_overexposure(k, abm, aba)),
    "excessive_runtime": lambda df, abm, aba: compute_excessive_runtime_raw(df),
    "budget_scale_efficiency": lambda df, abm, aba: compute_budget_scale_efficiency_raw(df),
    "trailer_teaser_impact": lambda df, abm, aba: compute_trailer_timing_raw(df),
    "first_single_timing": lambda df, abm, aba: compute_song_timing_raw(df),
    "holiday_release_window": lambda df, abm, aba: compute_holiday_window_raw(df),
    "box_office_clashes": lambda df, abm, aba: compute_clash_raw(df),
    "exam_schedules": lambda df, abm, aba: compute_exam_season_raw(df),
    "ipl_sporting_events": lambda df, abm, aba: compute_ipl_season_raw(df),
    "summer_vacation_window": lambda df, abm, aba: compute_summer_window_raw(df),
}


def resolve_factor_raw_and_banded(
    df: pd.DataFrame, factor: dict, actors_by_movie: dict, actors_by_actor: dict,
    india_mask: pd.Series, eav_lookup: dict[str, dict[str, float]],
) -> tuple[Optional[pd.Series], Optional[pd.Series]]:
    """Resolves one factor_definitions row's value on this corpus. Returns
    (raw, banded): `raw` is pre-band/pre-sign-flip (used for coverage %% and
    correlation-with-ln(revenue) reporting), `banded` is rescaled into
    [stated_min, stated_max] (used as the model feature). Returns (None,
    None) when the factor has no working computation path on this corpus --
    a pure literature-prior candidate/explanatory_only/deprecated row, an EAV
    factor with zero rows entered yet, or a misconfigured registry row
    pointing at a column/derivation_ref that doesn't exist (schema drift is
    reported, not a crash -- same philosophy as load_movies' missing-column
    handling)."""
    key = factor["factor_key"]
    lo, hi = float(factor["stated_min"]), float(factor["stated_max"])
    ctype = factor.get("computation_type")

    if ctype == "raw_column":
        col = factor.get("source_column")
        if not col or col not in df.columns:
            return None, None
        raw = df[col]
        # conflict_balance/narrative_novelty are written by ConflictBalanceService/
        # NarrativeNoveltyService already rescaled into their own stated band --
        # a raw_column factor's value is used as-is, just fillna'd to the band
        # midpoint when the upstream service hasn't scored this row yet.
        banded = raw.fillna((lo + hi) / 2.0)
        return raw, banded

    if ctype == "derived_python_fn":
        fn = DERIVED_FACTOR_FNS.get(factor.get("derivation_ref"))
        if fn is None:
            return None, None
        raw = fn(df, actors_by_movie, actors_by_actor)
        if key in INDIA_ONLY_CALENDAR_FACTOR_KEYS:
            raw = raw.where(india_mask)
        # Negative-direction factors' raw signal is "amount of severity" (higher
        # = worse); flip sign so higher severity maps toward stated_min (the
        # more negative bound) rather than stated_max, per factor.direction --
        # this generic rule reproduces the old hand-maintained
        # NEGATIVE_DIRECTION_FACTORS set exactly, since that set was always just
        # "the measurable factors whose direction is Negative".
        signed = -raw if factor.get("direction") == "Negative" else raw
        banded = percentile_into_band(signed, lo, hi)
        return raw, banded

    if ctype == "eav":
        values = eav_lookup.get(key)
        if not values:
            return None, None
        # movie_factor_values.movie_key is the canonical per-row
        # movie_name|release_date|language composite (registry.schema.
        # movie_entity_key, same convention Feature 1's data_sources uses) --
        # NOT this script's coarser internal `df["movie_key"]`
        # (lowercased-name|release_year, a post-dedup modeling convenience
        # that collapses every dubbed-language release of a film into one
        # row). Look up by the row's own exact movie_name/release_date/
        # language so a hand-entered or connector-sourced value lands on the
        # right row regardless of which language edition dedupe_movies kept.
        entity_keys = df.apply(
            lambda r: movie_entity_key(r["movie_name"], r["release_date"], r["language"]), axis=1)
        raw = entity_keys.map(values)
        banded = percentile_into_band(raw, lo, hi) if factor.get("data_type") == "numeric" else raw
        return raw, banded

    # computation_type is 'derived_sql', or None (a pure literature-prior row
    # with no computation path registered yet): nothing to compute here.
    return None, None


def compute_registry_features(
    df: pd.DataFrame, actors_by_movie: dict, actors_by_actor: dict,
    factor_defs: list[dict], eav_lookup: dict[str, dict[str, float]],
) -> tuple[pd.DataFrame, list[dict]]:
    """Registry-driven replacement for the old hardcoded build_measurable_features:
    for every candidate/active factor_definitions row, resolve its value on
    this corpus (if it has a computation path) and record a coverage-report
    entry. `status='candidate'` factors are computed and reported (coverage %%,
    correlation with ln(revenue)) exactly like `status='active'` ones here --
    the distinction between "computed" and "trained on" is enforced later, in
    feature_columns()'s coverage guard, not here."""
    feats = pd.DataFrame(index=df.index)
    india_mask = df["market_is_india"] == 1
    n_rows = len(df)
    coverage_report: list[dict] = []

    for factor in factor_defs:
        if factor["status"] not in ("active", "candidate"):
            continue
        raw, banded = resolve_factor_raw_and_banded(
            df, factor, actors_by_movie, actors_by_actor, india_mask, eav_lookup)
        if banded is None:
            continue

        key = factor["factor_key"]
        feats[key] = banded
        n_obs = int(raw.notna().sum()) if raw is not None else n_rows
        coverage_pct = round(100.0 * n_obs / n_rows, 2) if n_rows else 0.0

        corr = None
        if raw is not None and n_obs >= 5 and "ln_revenue" in df.columns:
            try:
                corr_val = pd.to_numeric(raw, errors="coerce").astype(float).corr(df["ln_revenue"])
                corr = round(float(corr_val), 4) if corr_val is not None and not np.isnan(corr_val) else None
            except (TypeError, ValueError):
                corr = None

        coverage_report.append({
            "factor_key": key, "status": factor["status"], "category": factor["category"],
            "n_obs": n_obs, "coverage_pct": coverage_pct, "corr_with_ln_revenue": corr,
        })

    return feats, coverage_report


# ---------------------------------------------------------------------------
# Full feature assembly
# ---------------------------------------------------------------------------

def assemble_features(df: pd.DataFrame, actors: pd.DataFrame, apply_india_filter: bool = True,
                       eav_lookup: Optional[dict[str, dict[str, float]]] = None) -> pd.DataFrame:
    df = df.copy()
    df["ln_revenue"] = np.log(df["revenue"].astype(float))
    df["primary_genre"] = df["genre"].map(primary_genre)
    df["primary_genre"] = df["primary_genre"].where(df["primary_genre"] != "unknown", df["genres"].map(primary_genre))

    # Per-row market tag (not gated by apply_india_filter -- it's needed on both
    # the india-only and pooled corpora): used both as a model feature so a pooled
    # model can learn market-specific baselines, and to gate the India-specific
    # calendar heuristics in compute_registry_features below.
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

    registry_feats, coverage_report = compute_registry_features(
        df, actors_by_movie, actors_by_actor, FACTOR_DEFS, eav_lookup or {})
    full = pd.concat([df, registry_feats], axis=1)
    full.attrs["coverage_report"] = coverage_report
    return full


# ---------------------------------------------------------------------------
# Modeling
# ---------------------------------------------------------------------------

BASELINE_ANCHOR_COLS = ["ln_budget_effective", "r_star", "r_director", "r_concept", "franchise_flag"]


def delta_regressor_col(key: str) -> str:
    return f"ln1p_{key}"


def prepare_model_frame(df: pd.DataFrame) -> pd.DataFrame:
    """ln1p-transforms every registry-computed factor column present on df
    (both candidate and active -- feature_columns() below is what decides
    which of these actually make it into a trained model's `cols`). Reads
    the FACTOR_DEFS global instead of a hardcoded key list, so a newly
    registered factor needs no change here to be picked up."""
    m = df.copy()
    for factor in FACTOR_DEFS:
        key = factor["factor_key"]
        if key in m.columns:
            # clip(lower=-0.999) guards ln1p against a misconfigured/free-text
            # EAV value <= -1 blowing up into -inf; every measurable/banded
            # factor's value is comfortably inside (-1, 1) already.
            m[delta_regressor_col(key)] = np.log1p(m[key].astype(float).clip(lower=-0.999))
    return m


def feature_columns(df: pd.DataFrame, min_coverage_pct: float = DEFAULT_MIN_FEATURE_COVERAGE_PCT) -> list[str]:
    """Registry-driven feature list (Feature 2): every `status='active'`
    factor whose non-null coverage on this df's rows clears
    `min_coverage_pct` is included automatically -- promoting a factor via
    register_factor.py/the admin API is enough to have it picked up here,
    no code change required. `status='candidate'` factors are deliberately
    excluded (they're still computed and reported via
    compute_registry_features's coverage_report, just not trained on yet)."""
    cols = list(BASELINE_ANCHOR_COLS)
    # Not folded into BASELINE_ANCHOR_COLS: those feed the Y = B0 * prod(1+delta_i)
    # formula's B0 term specifically, and market shouldn't change that formula's
    # meaning. It's still part of every direct predictive model's feature set
    # (compare_models/fit_predictive_model), which is what lets a pooled model
    # learn market-specific baselines.
    if "market_is_india" in df.columns:
        cols.append("market_is_india")
    for row in df.attrs.get("coverage_report", []):
        if row["status"] != "active" or row["coverage_pct"] < min_coverage_pct:
            continue
        c = delta_regressor_col(row["factor_key"])
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


def fit_predictive_model(train: pd.DataFrame, test: pd.DataFrame, cols: list[str],
                          sample_weight: Optional[pd.Series] = None) -> dict:
    Xtr, ytr = train[cols].fillna(0).values, train["ln_revenue"].values
    Xte, yte = test[cols].fillna(0).values, test["ln_revenue"].values
    w = sample_weight.reindex(train.index).values if sample_weight is not None else None

    gbr = GradientBoostingRegressor(n_estimators=300, max_depth=3, learning_rate=0.05, random_state=RNG_SEED)
    gbr.fit(Xtr, ytr, sample_weight=w)
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
                                     n_splits: int = CV_FOLDS,
                                     sample_weight: Optional[pd.Series] = None) -> pd.Series:
    """Same out-of-fold protocol as cross_validated_predictions, generalized to any model
    in MODEL_FACTORIES. Standardizing inputs is required for Ridge/MLP and harmless for
    the tree models, so all four candidates run through one uniform pipeline.
    `sample_weight` (Feature 4's inverse-probability weights) is passed to
    `.fit()` when the model supports it; MLPRegressor doesn't, so it silently
    falls back to an unweighted fit for that one model rather than crashing."""
    X = df[cols].fillna(0).values
    y = df["ln_revenue"].values
    w = sample_weight.reindex(df.index).values if sample_weight is not None else None
    pred = np.full(len(df), np.nan)
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=RNG_SEED)
    for train_idx, test_idx in kf.split(X):
        scaler = StandardScaler()
        Xtr = scaler.fit_transform(X[train_idx])
        Xte = scaler.transform(X[test_idx])
        model = MODEL_FACTORIES[model_name]()
        try:
            model.fit(Xtr, y[train_idx], sample_weight=w[train_idx] if w is not None else None)
        except TypeError:
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

def run_market_comparison(movies_raw_all: pd.DataFrame, actors: pd.DataFrame,
                           eav_lookup: dict[str, dict[str, float]],
                           min_coverage_pct: float = DEFAULT_MIN_FEATURE_COVERAGE_PCT) -> dict:
    print("\nRunning india-only / pooled-global / per-market model comparison (Feature 0)...")

    india_mask_raw = movies_raw_all.apply(
        lambda r: is_india_market_row(r.get("language"), r.get("country")), axis=1)
    india_raw = movies_raw_all[india_mask_raw].reset_index(drop=True)

    def run_one(raw: pd.DataFrame, apply_india_filter: bool) -> dict:
        deduped = dedupe_movies(raw)
        assembled = assemble_features(deduped, actors, apply_india_filter=apply_india_filter,
                                       eav_lookup=eav_lookup)
        model_df = prepare_model_frame(assembled)
        cols = feature_columns(model_df, min_coverage_pct)
        return compare_models(model_df, cols)

    # (a) India-only, exactly today's pipeline (SQL-level India restriction +
    # the director-based filter_non_indian_productions pass) -- the fixed
    # baseline every other number in this comparison is measured against.
    per_market_india = run_one(india_raw, apply_india_filter=True)

    # (b) Every market pooled together, with the market_is_india feature letting
    # the model learn a market-specific baseline instead of assuming India and
    # the US behave the same.
    pooled_deduped = dedupe_movies(movies_raw_all)
    pooled_assembled = assemble_features(pooled_deduped, actors, apply_india_filter=False,
                                          eav_lookup=eav_lookup)
    pooled_model_df = prepare_model_frame(pooled_assembled)
    pooled_cols = feature_columns(pooled_model_df, min_coverage_pct)
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
# Feature 4: MNAR missing-financials modeling
# ---------------------------------------------------------------------------
# 513,198/544,424 rows (94.3%) have budget null/0 and 525,637 (96.6%) have
# revenue null/0, almost certainly MNAR -- obscure/low-budget/foreign films
# are both less likely to have tracked financials AND more likely to have
# underperformed. Training Stage B (the revenue regressor) only on the
# disclosed subset, as the rest of this script otherwise does, silently
# assumes the pattern that holds for films whose financials get published
# also holds for the long tail that never gets tracked. Stage A below models
# P(disclosed) on the FULL corpus so that assumption can be corrected for
# (inverse-probability weighting) and surfaced (a per-row confidence band and
# a `disclosure_likelihood` field), instead of just being ignored.

# Deliberately excludes anything derived from budget/revenue/trailer
# telemetry: those are either the target itself or correlated with whether a
# row's financials got tracked in the first place -- exactly the selection
# effect Stage A exists to model, not a legitimate predictor of it. Note:
# the plan's "director prior-film count" pre-release factor is Feature 5's
# job to compute properly (an actors_data_collection self-join); Stage A
# below computes its own lightweight version directly from
# movies_data_collection.directors so Feature 4 doesn't have to wait on
# Feature 5 landing first -- Feature 5, once built, can supersede this with
# its richer version without changing Stage A's shape.
DISCLOSURE_MODEL_FACTORIES = {
    "logistic_regression": lambda: LogisticRegression(max_iter=1000, C=1.0, random_state=RNG_SEED),
    "gbc": lambda: GradientBoostingClassifier(n_estimators=200, max_depth=3, learning_rate=0.05,
                                               random_state=RNG_SEED),
}


def compute_director_prior_release_counts(distinct_movies: pd.DataFrame) -> pd.Series:
    """Count of strictly-earlier-released movies (by release_year) credited to
    the same primary director, computed on a frame already deduplicated to one
    row per (movie_name, release_year) -- Stage A's own pre-release-only proxy
    for director track record (see module note above re: Feature 5). Uses
    np.searchsorted per director group instead of a python loop: sorting each
    director's release years and searching each row's own year into that
    sorted array gives 'count of years strictly less than mine' directly,
    correctly collapsing same-year multi-language duplicates without treating
    them as leaking into each other's prior count."""
    out = pd.Series(0, index=distinct_movies.index, dtype=int)
    has_director = distinct_movies["director_key"].fillna("") != ""
    for _key, grp in distinct_movies[has_director].groupby("director_key"):
        years = grp["release_year"].to_numpy()
        sorted_years = np.sort(years)
        out.loc[grp.index] = np.searchsorted(sorted_years, years, side="left")
    return out


def compute_prerelease_movie_attrs(df: pd.DataFrame) -> pd.DataFrame:
    """Per-row release_year / primary_genre / director_key / franchise_flag /
    director_prior_release_count, computed once on a deduplicated
    (movie_name, release_year) view -- so a title's simultaneous language
    editions don't inflate another film's director-prior-films count or its
    own franchise sequel count -- then broadcast back onto every row
    (including every language edition) of the full, non-deduplicated corpus
    Stage A trains on. Unlike dedupe_movies() elsewhere in this script, this
    dedup doesn't need to pick a single 'best' representative row (nothing
    downstream here reads revenue/budget), so it keeps the first occurrence
    per (movie_name, release_year) rather than sorting by completeness."""
    d = df.copy()
    d["release_year"] = d["release_date"].map(parse_release_year).astype(int)
    d["primary_genre_disclosure"] = d["genre"].map(primary_genre)
    d["primary_genre_disclosure"] = d["primary_genre_disclosure"].where(
        d["primary_genre_disclosure"] != "unknown", d["genres"].map(primary_genre))
    d["director_key"] = d["directors"].fillna("").str.split(",").str[0].str.strip().str.lower()
    d["movie_key"] = d["movie_name"].str.strip().str.lower() + "|" + d["release_year"].astype(str)

    distinct = d.drop_duplicates(subset="movie_key").copy()
    stems = distinct["movie_name"].map(franchise_stem)
    stem_counts = stems.value_counts()
    distinct["franchise_flag"] = stems.map(lambda s: 1 if stem_counts.get(s, 0) >= 2 else 0)
    distinct["director_prior_release_count"] = compute_director_prior_release_counts(distinct)

    lookup = distinct.set_index("movie_key")[["franchise_flag", "director_prior_release_count"]]
    d = d.merge(lookup, on="movie_key", how="left", suffixes=("", ""))
    d["entity_key"] = d.apply(
        lambda r: movie_entity_key(r["movie_name"], r["release_date"], r["language"]), axis=1)
    return d


def _top_k_bucketed(series: pd.Series, k: int = DISCLOSURE_TOP_CATEGORIES) -> pd.Series:
    top = series.value_counts().head(k).index
    return series.where(series.isin(top), "other")


def build_disclosure_features(df: pd.DataFrame) -> tuple[pd.DataFrame, list[str]]:
    """Pre-release-only, always-available design matrix for Stage A: genre,
    language, country, release year, franchise flag, director prior-release
    count -- exactly the plan's named feature list, one-hot encoding the three
    categoricals (bucketing anything outside the top DISCLOSURE_TOP_CATEGORIES
    values as 'other' so a long tail of one-off languages/countries doesn't
    blow up the design matrix)."""
    feats = pd.DataFrame(index=df.index)
    feats["release_year"] = df["release_year"].astype(float)
    feats["franchise_flag"] = df["franchise_flag"].astype(float)
    feats["director_prior_release_count"] = df["director_prior_release_count"].astype(float)

    genre = _top_k_bucketed(df["primary_genre_disclosure"].fillna("unknown"))
    language = _top_k_bucketed(df["language"].fillna("unknown").str.strip().str.lower())
    country = _top_k_bucketed(df["country"].fillna("unknown").str.strip())
    cat = pd.get_dummies(
        pd.DataFrame({"genre": genre, "language": language, "country": country}),
        prefix=["genre", "language", "country"],
    )
    full = pd.concat([feats, cat], axis=1)
    return full, list(full.columns)


def cross_validated_disclosure_proba(X: np.ndarray, y: np.ndarray, model_name: str,
                                      n_splits: int = CV_FOLDS) -> np.ndarray:
    """Out-of-fold predicted P(disclosed), same protocol as
    cross_validated_predictions_for -- needed both to report an honest
    AUC/accuracy (not inflated by scoring a model on rows it trained on) and
    because Stage B's inverse-probability weights must come from a
    disclosure-probability estimate that didn't see that row's own label
    during its own fold."""
    proba = np.full(len(y), np.nan)
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=RNG_SEED)
    for train_idx, test_idx in kf.split(X):
        scaler = StandardScaler()
        Xtr = scaler.fit_transform(X[train_idx])
        Xte = scaler.transform(X[test_idx])
        model = DISCLOSURE_MODEL_FACTORIES[model_name]()
        model.fit(Xtr, y[train_idx])
        proba[test_idx] = model.predict_proba(Xte)[:, 1]
    return proba


def fit_disclosure_classifier(full_df: pd.DataFrame) -> dict:
    """Stage A (Feature 4): binary classifier for has_financials =
    (budget>0 AND revenue>0), trained on the FULL corpus that clears the year
    floor -- not just the ~3-6% of rows that pass the budget/revenue floor --
    using only pre-release-always-available features. Compares
    LogisticRegression against GradientBoostingClassifier via out-of-fold
    AUC and keeps whichever wins; reports both as a first-class output
    (accuracy/AUC/feature importances), not just internal plumbing, since
    'is this movie even in a class of films whose revenue tends to get
    tracked' is a useful signal on its own."""
    feats, feat_cols = build_disclosure_features(full_df)
    X = feats.fillna(0).values
    y = has_financials_mask(full_df).astype(int).values

    model_comparison = {}
    oof_probas = {}
    for name in DISCLOSURE_MODEL_FACTORIES:
        proba = cross_validated_disclosure_proba(X, y, name)
        oof_probas[name] = proba
        model_comparison[name] = {
            "auc": round(float(roc_auc_score(y, proba)), 4),
            "accuracy_at_0.5": round(float(accuracy_score(y, (proba >= 0.5).astype(int))), 4),
        }
    winner = max(model_comparison, key=lambda n: model_comparison[n]["auc"])

    scaler = StandardScaler()
    Xs = scaler.fit_transform(X)
    final_model = DISCLOSURE_MODEL_FACTORIES[winner]()
    final_model.fit(Xs, y)
    if hasattr(final_model, "feature_importances_"):
        importances = dict(zip(feat_cols, final_model.feature_importances_.tolist()))
    else:
        importances = dict(zip(feat_cols, (final_model.coef_[0] / scaler.scale_).tolist()))
    importances = dict(sorted(importances.items(), key=lambda kv: -abs(kv[1])))

    return {
        "winner": winner,
        "model_comparison": model_comparison,
        "feature_importances": importances,
        "n_rows": int(len(full_df)), "n_disclosed": int(y.sum()), "n_undisclosed": int(len(y) - y.sum()),
        # OOF probability for every full-corpus row -- honest (no row scored by
        # a model that trained on its own label), used both for Stage B's IPW
        # weights and for the disclosure_likelihood attached to predictions.
        "disclosure_likelihood": pd.Series(oof_probas[winner], index=full_df.index),
    }


def compute_ipw_weights(disclosure_likelihood: pd.Series, clip_range: tuple = IPW_WEIGHT_CLIP) -> pd.Series:
    """1 / P(disclosed), clipped -- a row from a class of films rarely seen
    with tracked financials counts more in Stage B's loss, so the regressor
    doesn't implicitly learn only the pattern that holds for the
    well-covered, high-disclosure-probability slice of the corpus."""
    p = disclosure_likelihood.fillna(disclosure_likelihood.median()).clip(lower=1e-3, upper=1.0)
    return (1.0 / p).clip(*clip_range)


def compare_ipw_weighting(df: pd.DataFrame, cols: list[str], model_name: str = "gbr") -> dict:
    """Weighted vs. unweighted CV accuracy for Stage B's primary regressor --
    keep whichever wins on within_30pct (ties broken by median_abs_pct_error),
    same comparison convention run_market_comparison uses for Feature 0.
    MLPRegressor doesn't accept sample_weight; this comparison is scoped to
    the tree/linear models (default 'gbr', today's champion) that do."""
    unweighted_pred = cross_validated_predictions_for(df, cols, model_name)
    _, unweighted = evaluate_full_corpus_accuracy(df, unweighted_pred)

    weights = compute_ipw_weights(df["disclosure_likelihood"])
    weighted_pred = cross_validated_predictions_for(df, cols, model_name, sample_weight=weights)
    _, weighted = evaluate_full_corpus_accuracy(df, weighted_pred)

    def score(s: dict) -> tuple:
        return (s["within_30pct"]["pct_correct"], -s["median_abs_pct_error"])

    winner = "weighted" if score(weighted) > score(unweighted) else "unweighted"
    return {"model_name": model_name, "unweighted": unweighted, "weighted": weighted, "winner": winner}


def bootstrap_prediction_spread(df: pd.DataFrame, cols: list[str],
                                 sample_weight: Optional[pd.Series] = None,
                                 n_iters: int = CONFIDENCE_BOOTSTRAP_ITERS) -> pd.DataFrame:
    """Bagged-tree-style bootstrap prediction interval: refit a (reduced)
    GBR on n_iters bootstrap resamples of df and read the spread of predicted
    ln(revenue) per row across resamples -- the same 'refit on resampled
    data, read the spread of the estimate' idea bootstrap_calibration already
    uses for Ridge coefficients, applied here to per-row predictions. This is
    an in-sample bagging spread (each resample is fit on -- and scores -- the
    same df), not a fold-held-out spread: cheap enough to run
    CONFIDENCE_BOOTSTRAP_ITERS times without multiplying by CV_FOLDS, and
    adequate for a *width* estimate (it's rescaled by disclosure_likelihood
    below anyway, not used as the point prediction itself)."""
    rng = np.random.RandomState(RNG_SEED)
    X = df[cols].fillna(0).values
    y = df["ln_revenue"].values
    w = sample_weight.reindex(df.index).values if sample_weight is not None else None
    n = len(df)
    preds = np.full((n_iters, n), np.nan)
    for i in range(n_iters):
        idx = rng.randint(0, n, size=n)
        model = GradientBoostingRegressor(**CONFIDENCE_GBR_PARAMS)
        try:
            model.fit(X[idx], y[idx], sample_weight=w[idx] if w is not None else None)
        except TypeError:
            model.fit(X[idx], y[idx])
        preds[i] = model.predict(X)
    return pd.DataFrame({
        "p10_log": np.percentile(preds, 10, axis=0),
        "p90_log": np.percentile(preds, 90, axis=0),
    }, index=df.index)


def confidence_band_multiplier(disclosure_likelihood: pd.Series) -> pd.Series:
    lo_mult, hi_mult = CONFIDENCE_WIDTH_MULTIPLIER_RANGE
    p = disclosure_likelihood.fillna(disclosure_likelihood.median() if disclosure_likelihood.notna().any() else 0.5)
    return lo_mult + p.clip(0, 1) * (hi_mult - lo_mult)


def attach_confidence_band(predictions: pd.DataFrame, df: pd.DataFrame, cols: list[str],
                            disclosure_likelihood: pd.Series,
                            sample_weight: Optional[pd.Series] = None,
                            n_iters: int = CONFIDENCE_BOOTSTRAP_ITERS) -> pd.DataFrame:
    """Adds disclosure_likelihood/confidence_band_low/confidence_band_high to
    an evaluate_full_corpus_accuracy() predictions frame: a title with a low
    disclosure_likelihood (few comparable disclosed films back its estimate)
    gets a wider band around the same point prediction; a well-covered title
    gets a narrower one -- so a downstream consumer can tell 'well-supported
    estimate' from 'thin-comparables guess' instead of one flat number."""
    dl = disclosure_likelihood.reindex(df.index)
    spread = bootstrap_prediction_spread(df, cols, sample_weight, n_iters=n_iters)
    mult = confidence_band_multiplier(dl)
    center_log = np.log(predictions["predicted_revenue"].clip(lower=1.0)).values
    half_width_log = ((spread["p90_log"] - spread["p10_log"]) / 2.0).values * mult.values

    out = predictions.copy()
    out["disclosure_likelihood"] = dl.round(4).values
    out["confidence_band_low"] = np.exp(center_log - half_width_log).round(0)
    out["confidence_band_high"] = np.exp(center_log + half_width_log).round(0)
    return out


def write_disclosure_outputs(disclosure_result: dict, full_df: pd.DataFrame, output_dir: str) -> None:
    os.makedirs(output_dir, exist_ok=True)
    report = {k: v for k, v in disclosure_result.items() if k != "disclosure_likelihood"}
    with open(os.path.join(output_dir, "disclosure_classifier_report.json"), "w") as f:
        json.dump(report, f, indent=2, default=float)

    likelihood_df = pd.DataFrame({
        "movie_name": full_df["movie_name"], "release_date": full_df["release_date"],
        "language": full_df["language"], "has_financials": has_financials_mask(full_df).values,
        "disclosure_likelihood": disclosure_result["disclosure_likelihood"].round(4).values,
    })
    likelihood_df.to_csv(os.path.join(output_dir, "disclosure_likelihood.csv"), index=False)
    print(f"Wrote {output_dir}/disclosure_classifier_report.json and disclosure_likelihood.csv "
          f"({disclosure_result['winner']} won: AUC="
          f"{disclosure_result['model_comparison'][disclosure_result['winner']]['auc']})")


def print_disclosure_report(disclosure_result: dict) -> None:
    print("\n-- Stage A: budget/revenue disclosure classifier (Feature 4) --")
    print(f"  Full corpus: {disclosure_result['n_rows']} rows "
          f"({disclosure_result['n_disclosed']} disclosed / {disclosure_result['n_undisclosed']} not)")
    for name, m in disclosure_result["model_comparison"].items():
        marker = "  <- winner" if name == disclosure_result["winner"] else ""
        print(f"  {name:20s} AUC={m['auc']:.4f}  accuracy@0.5={m['accuracy_at_0.5']:.4f}{marker}")
    top_feats = list(disclosure_result["feature_importances"].items())[:8]
    print("  Top feature importances:")
    for name, val in top_feats:
        print(f"    {name:<24s} {val:+.4f}")


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
# FACTOR_BY_KEY itself is populated by load_factor_registry() at the start of
# main() -- see the "Factor registry" section above.


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
    for factor in FACTOR_DEFS:
        key = factor["factor_key"]
        col = delta_regressor_col(key)
        if col not in cols:
            continue
        idx = cols.index(col)
        lo_val, hi_val = np.log1p(float(factor["stated_min"])), np.log1p(float(factor["stated_max"]))
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
        cal_a, cal_b = float(f["stated_min"]) * mult, float(f["stated_max"]) * mult
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
        delta_calibrated = rescale_into_band(df[key], float(f["stated_min"]), float(f["stated_max"]), cal_lo, cal_hi)
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
    for f in FACTOR_DEFS:
        key = f["factor_key"]
        stated_min, stated_max = float(f["stated_min"]), float(f["stated_max"])
        if key in calibrated:
            cal_lo, cal_hi, mult = calibrated[key]
            rows.append({
                "key": key, "name": f["name"], "category": f["category"], "status": f["status"],
                "stated_min": stated_min, "stated_max": stated_max,
                "calibrated_min": round(cal_lo, 4), "calibrated_max": round(cal_hi, 4),
                "calibration_multiplier": round(mult, 3), "source": f"{model_name}_partial_dependence",
            })
        else:
            rows.append({
                "key": key, "name": f["name"], "category": f["category"], "status": f["status"],
                "stated_min": stated_min, "stated_max": stated_max,
                "calibrated_min": stated_min, "calibrated_max": stated_max,
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

def cross_validated_predictions(df: pd.DataFrame, cols: list[str], n_splits: int = CV_FOLDS,
                                 sample_weight: Optional[pd.Series] = None) -> pd.Series:
    """Out-of-fold ln(revenue) prediction for every row via K-fold GBR, so each
    movie is scored by a model that never saw its own revenue during training --
    unlike just refitting on the full corpus and predicting back on it, which
    would report in-sample fit and overstate accuracy. `sample_weight` (Feature
    4's inverse-probability weights) is applied when compare_ipw_weighting()
    found weighting wins for this corpus; None reproduces the old unweighted
    behavior exactly."""
    X = df[cols].fillna(0).values
    y = df["ln_revenue"].values
    w = sample_weight.reindex(df.index).values if sample_weight is not None else None
    pred = np.full(len(df), np.nan)
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=RNG_SEED)
    for train_idx, test_idx in kf.split(X):
        gbr = GradientBoostingRegressor(n_estimators=300, max_depth=3, learning_rate=0.05, random_state=RNG_SEED)
        gbr.fit(X[train_idx], y[train_idx], sample_weight=w[train_idx] if w is not None else None)
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
    """Registry-driven replacement for the old FACTOR_CATALOG loop: iterates
    every factor_definitions row (not just 'active' ones, so candidate/
    explanatory_only/deprecated factors still show up in the report) and
    reports either a data-fitted calibrated band (if the factor made it into
    this run's trained `cols`, per feature_columns()'s coverage guard) or the
    literature-stated band unmodified."""
    out = []
    coverage_by_key = {row["factor_key"]: row for row in df.attrs.get("coverage_report", [])}
    for f in FACTOR_DEFS:
        key = f["factor_key"]
        stated_min, stated_max = float(f["stated_min"]), float(f["stated_max"])
        cov = coverage_by_key.get(key)
        row = {
            "key": key, "name": f["name"], "category": f["category"],
            "direction": f["direction"], "stated_min": stated_min, "stated_max": stated_max,
            "status": f["status"], "proxy_note": f.get("notes") or "",
            "coverage_pct": cov["coverage_pct"] if cov else None,
            "corr_with_ln_revenue": cov["corr_with_ln_revenue"] if cov else None,
        }

        if key not in df.columns:
            # No computation path resolved any value for this factor on this
            # corpus -- a pure literature-prior candidate, explanatory_only,
            # or deprecated row.
            row.update({
                "calibrated_min": stated_min, "calibrated_max": stated_max,
                "source": "prior_literature", "n_obs": None, "beta_p50": None,
            })
            out.append(row)
            continue

        col = delta_regressor_col(key)
        if col not in bootstrap.index:
            # Computed (visible in coverage_report) but not part of this run's
            # trained cols -- either status != 'active' or it didn't clear the
            # coverage guard yet.
            row.update({
                "calibrated_min": stated_min, "calibrated_max": stated_max,
                "source": "prior_literature_fallback", "n_obs": cov["n_obs"] if cov else None,
                "beta_p50": None,
            })
            out.append(row)
            continue

        b = bootstrap.loc[col]
        lo_mult = float(np.clip(b["beta_p10"], *CALIBRATION_CLIP))
        hi_mult = float(np.clip(b["beta_p90"], *CALIBRATION_CLIP))
        mid_mult = float(np.clip(b["beta_p50"], *CALIBRATION_CLIP))
        cal_a, cal_b = stated_min * lo_mult, stated_max * hi_mult
        cal_min, cal_max = min(cal_a, cal_b), max(cal_a, cal_b)

        row.update({
            "calibrated_min": round(cal_min, 4), "calibrated_max": round(cal_max, 4),
            "calibration_point_multiplier": round(mid_mult, 3),
            "source": "data_fitted", "n_obs": cov["n_obs"] if cov else len(df),
            "beta_p50": round(float(b["beta_p50"]), 4),
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
        for r in sorted(rows, key=lambda x: x["key"]):
            src = "DATA-FITTED" if r["source"] == "data_fitted" else "prior"
            status_note = f" [{r['status']}]" if r.get("status") not in (None, "active") else ""
            n_note = f" n={r['n_obs']}" if r.get("n_obs") else ""
            print(f"    {r['name']:<48s} "
                  f"[{r['calibrated_min']:+.0%}, {r['calibrated_max']:+.0%}]  "
                  f"(stated [{r['stated_min']:+.0%},{r['stated_max']:+.0%}])  {src}{status_note}{n_note}")
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


def factor_keys_used_from_cols(cols: list[str]) -> list[str]:
    """Extracts the active factor_key list from a feature_columns() result --
    every non-baseline-anchor/non-market column is `ln1p_<factor_key>`. This
    is what makes "did adding factor X actually help" empirically answerable
    per run: Feature 11's model_comparison_history is meant to persist this
    exact list alongside each row's accuracy numbers."""
    prefix = "ln1p_"
    return [c[len(prefix):] for c in cols if c.startswith(prefix)]


def print_factor_coverage_report(coverage_report: list[dict]) -> None:
    print("\n-- Factor registry coverage (Feature 2: candidate + active factors, this run) --")
    for row in sorted(coverage_report, key=lambda r: (-r["coverage_pct"], r["factor_key"])):
        corr_note = f"  corr={row['corr_with_ln_revenue']:+.3f}" if row["corr_with_ln_revenue"] is not None else ""
        print(f"  {row['factor_key']:<28s} [{row['status']:<9s}] "
              f"coverage={row['coverage_pct']:>6.2f}%  n_obs={row['n_obs']:<8d}{corr_note}")


def write_factor_coverage_report(coverage_report: list[dict], output_dir: str) -> None:
    os.makedirs(output_dir, exist_ok=True)
    pd.DataFrame(coverage_report).to_csv(os.path.join(output_dir, "factor_coverage_report.csv"), index=False)
    with open(os.path.join(output_dir, "factor_coverage_report.json"), "w") as f:
        json.dump(coverage_report, f, indent=2, default=float)
    print(f"Wrote {output_dir}/factor_coverage_report.csv and .json")


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
    p.add_argument("--min-feature-coverage", type=float, default=DEFAULT_MIN_FEATURE_COVERAGE_PCT,
                    dest="min_feature_coverage",
                    help="Feature 2 coverage guard: minimum non-null coverage, in percent "
                         f"(default {DEFAULT_MIN_FEATURE_COVERAGE_PCT}), an active factor_definitions "
                         "row must clear on this run's rows to be included in the trained feature "
                         "set. Below-threshold active factors (and every candidate factor, "
                         "regardless of coverage) are still computed and reported in "
                         "factor_coverage_report.csv/.json, just excluded from the model.")
    p.add_argument("--confidence-bootstrap-iters", type=int, default=CONFIDENCE_BOOTSTRAP_ITERS,
                    dest="confidence_bootstrap_iters",
                    help="Feature 4: number of bootstrap refits used to estimate each row's "
                         f"confidence-band width (default {CONFIDENCE_BOOTSTRAP_ITERS}). Set to 0 "
                         "to skip the confidence-band step entirely (movie_revenue_predictions.csv "
                         "still gets disclosure_likelihood, just no confidence_band_low/high) -- "
                         "useful for a fast dev-iteration run against a small corpus.")
    return p.parse_args()


def main() -> None:
    args = parse_args()

    print(f"Connecting to postgresql://{args.db_user}@{args.db_host}:{args.db_port}/{args.db_name} ...")
    conn = get_connection(args)
    try:
        # Feature 2: load the live factor_definitions registry (replacing the
        # old hardcoded FACTOR_CATALOG) and any movie_factor_values EAV rows
        # before closing the connection -- everything downstream reads the
        # FACTOR_DEFS/FACTOR_BY_KEY globals and the eav_lookup dict rather than
        # querying the DB again per-row.
        load_factor_registry(conn)
        eav_lookup = load_eav_lookup(conn)

        # Feature 4: load the FULL corpus (every row with a parseable release
        # year, disclosed or not) rather than the budget/revenue-floored
        # subset -- Stage A's disclosure classifier needs to see the ~94-97%
        # of rows the rest of the pipeline can't train a revenue regressor on.
        # The disclosed subset (movies_raw_all, same rows the old single query
        # returned) is then just a pandas filter of this, avoiding a second
        # round-trip to Postgres with the floor re-applied at the SQL level.
        full_corpus_raw = load_movies(conn, args.min_year, args.min_budget, args.min_revenue,
                                       restrict_to_india=False, require_financials=False)
        movies_raw_all = filter_financials(full_corpus_raw, args.min_budget, args.min_revenue)
        actors_raw = load_actor_credits(conn)
    finally:
        conn.close()
    print(f"Loaded {len(full_corpus_raw)} full-corpus movie rows (all markets, any disclosure status); "
          f"{len(movies_raw_all)} have disclosed budget+revenue. {len(actors_raw)} actor-credit rows.")
    print(f"Factor registry: {len(FACTOR_DEFS)} factor_definitions rows "
          f"({sum(1 for f in FACTOR_DEFS if f['status'] == 'active')} active, "
          f"{sum(1 for f in FACTOR_DEFS if f['status'] == 'candidate')} candidate).")

    actors = build_actor_features(actors_raw)

    # Feature 4, Stage A: disclosure classifier on the full corpus, using only
    # pre-release-always-available features -- run once up front so its
    # per-row disclosure_likelihood is available to both Stage B's
    # inverse-probability weighting and the confidence-band step below.
    print("\nFitting Stage A disclosure classifier "
          "(P(budget/revenue disclosed) on the full corpus, Feature 4)...")
    prerelease_full = compute_prerelease_movie_attrs(full_corpus_raw)
    disclosure_result = fit_disclosure_classifier(prerelease_full)
    print_disclosure_report(disclosure_result)
    write_disclosure_outputs(disclosure_result, prerelease_full, args.output_dir)
    disclosure_by_entity_key = disclosure_result["disclosure_likelihood"].copy()
    disclosure_by_entity_key.index = prerelease_full["entity_key"].values

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

    full = assemble_features(movies, actors, apply_india_filter=apply_india_filter, eav_lookup=eav_lookup)
    model_df = prepare_model_frame(full)

    # Feature 4: attach each row's Stage A disclosure_likelihood by the same
    # exact (movie_name, release_date, language) composite key data_sources/
    # movie_factor_values use -- model_df's own movie_key (lowercased-name|
    # release_year) collapses dubbed-language editions, which would blur
    # rows the disclosure model scored individually.
    model_df["entity_key"] = model_df.apply(
        lambda r: movie_entity_key(r["movie_name"], r["release_date"], r["language"]), axis=1)
    model_df["disclosure_likelihood"] = model_df["entity_key"].map(disclosure_by_entity_key)
    n_unmatched = int(model_df["disclosure_likelihood"].isna().sum())
    if n_unmatched:
        print(f"Note: {n_unmatched}/{len(model_df)} rows didn't match a Stage A entity_key "
              f"(unparseable release_year edge case); backfilling with the corpus median.")
    model_df["disclosure_likelihood"] = model_df["disclosure_likelihood"].fillna(
        model_df["disclosure_likelihood"].median())

    train, test = time_based_split(model_df)
    cols = feature_columns(model_df, args.min_feature_coverage)
    factor_keys_used = factor_keys_used_from_cols(cols)
    print(f"Trained feature set: {len(factor_keys_used)} active factor(s) cleared the "
          f"{args.min_feature_coverage}% coverage guard: {factor_keys_used}")

    print_factor_coverage_report(full.attrs.get("coverage_report", []))
    write_factor_coverage_report(full.attrs.get("coverage_report", []), args.output_dir)

    # Feature 4, Stage B: compare inverse-probability-weighted vs. unweighted
    # CV accuracy for the primary GBR pipeline and keep whichever wins, rather
    # than assuming IPW helps without checking -- same "measure, don't assume"
    # convention run_market_comparison uses for the pooled-vs-per-market call.
    print("\nComparing IPW-weighted vs. unweighted Stage B regressor (Feature 4)...")
    ipw_comparison = compare_ipw_weighting(model_df, cols, model_name="gbr")
    print(f"  unweighted: within_30pct={ipw_comparison['unweighted']['within_30pct']['pct_correct']}%  "
          f"median_abs_pct_error={ipw_comparison['unweighted']['median_abs_pct_error']}%")
    print(f"  weighted:   within_30pct={ipw_comparison['weighted']['within_30pct']['pct_correct']}%  "
          f"median_abs_pct_error={ipw_comparison['weighted']['median_abs_pct_error']}%")
    print(f"  winner: {ipw_comparison['winner']}")
    sample_weight = compute_ipw_weights(model_df["disclosure_likelihood"]) if ipw_comparison["winner"] == "weighted" else None

    bootstrap = bootstrap_calibration(train, cols)
    model_metrics = fit_predictive_model(train, test, cols, sample_weight=sample_weight)

    factor_table = calibrate_factor_table(full, bootstrap)
    baseline = calibrate_baseline_anchors(bootstrap)

    print_report(factor_table, baseline, model_metrics, len(train), len(test))
    write_outputs(factor_table, baseline, model_metrics, args.output_dir)

    oof_pred_log = cross_validated_predictions(model_df, cols, sample_weight=sample_weight)
    predictions, accuracy_summary = evaluate_full_corpus_accuracy(model_df, oof_pred_log)
    print_accuracy_report(accuracy_summary)

    if args.confidence_bootstrap_iters > 0:
        print(f"\nEstimating per-row confidence bands ({args.confidence_bootstrap_iters} bootstrap "
              f"refits, Feature 4)...")
        predictions = attach_confidence_band(
            predictions, model_df, cols, model_df["disclosure_likelihood"], sample_weight=sample_weight,
            n_iters=args.confidence_bootstrap_iters)
    write_prediction_outputs(predictions, accuracy_summary, args.output_dir)

    print("\nComparing direct revenue-prediction models (Ridge / GBR / HistGBR / MLP neural net)...")
    model_comparison = compare_models(model_df, cols)
    print_model_comparison(model_comparison)
    # Feature 2: record the exact factor_key list this run trained on, so a
    # future model_comparison_history row (Feature 11) can answer "did adding
    # factor X actually help" empirically -- added after printing so it
    # doesn't get mistaken for a fifth model's results.
    model_comparison["factor_keys_used"] = factor_keys_used
    # Feature 4: record Stage A's own accuracy plus the weighted-vs-unweighted
    # Stage B comparison so "did IPW actually help" is visible in the same
    # model_comparison.json diff Feature 11 tracks run-over-run, not just
    # printed to the console.
    model_comparison["disclosure_classifier"] = {
        k: v for k, v in disclosure_result.items() if k != "disclosure_likelihood"
    }
    model_comparison["ipw_comparison"] = ipw_comparison

    # Feature 0 (4): india-only-as-today vs. pooled-global vs. per-market, always
    # computed and written under new keys so the improvement (or lack of it) from
    # pooling markets is visible in model_comparison.json's diff every run,
    # regardless of what --market was passed for the primary pipeline above.
    market_comparison = run_market_comparison(movies_raw_all, actors, eav_lookup, args.min_feature_coverage)
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
