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
    pip install xgboost lightgbm catboost shap joblib

The second line (Feature 8) is optional-but-recommended: xgboost/lightgbm/
catboost extend the model comparison beyond sklearn's own GBR/HistGBR/MLP,
and shap powers the per-factor/per-movie explanation step. Each of the four
is imported defensively -- a missing one is skipped with a printed warning
rather than crashing the whole run, so `compare_models()` and the SHAP step
still work (with a smaller candidate set / a documented partial-dependence
fallback) on a host that hasn't installed them yet.

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
from datetime import date, datetime, timezone
from typing import Callable, Optional

import joblib
import numpy as np
import pandas as pd
import psycopg2
import psycopg2.extras
from sklearn.ensemble import (
    GradientBoostingClassifier, GradientBoostingRegressor, HistGradientBoostingRegressor,
    StackingRegressor,
)
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.metrics import (
    accuracy_score, mean_absolute_error, mean_absolute_percentage_error, r2_score, roc_auc_score,
)
from sklearn.model_selection import KFold
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler

# Feature 8: xgboost/lightgbm/catboost/shap are new, heavier optional
# dependencies, unlike everything above -- imported defensively so a host
# that hasn't run `pip install xgboost lightgbm catboost shap` yet still gets
# a working (smaller) model comparison and a graceful SHAP fallback instead
# of an import-time crash. `_OPTIONAL_DEPENDENCY_IMPORT_ERRORS` is surfaced
# in main()'s startup log so a missing package is a visible warning, not a
# silently smaller candidate set.
_OPTIONAL_DEPENDENCY_IMPORT_ERRORS: dict[str, str] = {}
try:
    from xgboost import XGBRegressor
except Exception as _exc:  # noqa: BLE001 -- pragma: no cover -- not just ImportError: e.g. on macOS
    # without Homebrew's libomp, `pip install xgboost` succeeds but the import raises
    # xgboost's own XGBoostError (a RuntimeError) trying to dlopen libxgboost.dylib.
    # Catching broadly is what actually delivers the "graceful degradation" this
    # block's intro comment promises -- ImportError alone only covers "not installed".
    XGBRegressor = None
    _OPTIONAL_DEPENDENCY_IMPORT_ERRORS["xgboost"] = str(_exc)
try:
    from lightgbm import LGBMRegressor
except Exception as _exc:  # noqa: BLE001 -- pragma: no cover -- same native-lib-load caveat as xgboost above
    LGBMRegressor = None
    _OPTIONAL_DEPENDENCY_IMPORT_ERRORS["lightgbm"] = str(_exc)
try:
    from catboost import CatBoostRegressor
except Exception as _exc:  # noqa: BLE001 -- pragma: no cover -- same native-lib-load caveat as xgboost above
    CatBoostRegressor = None
    _OPTIONAL_DEPENDENCY_IMPORT_ERRORS["catboost"] = str(_exc)
try:
    import shap
except Exception as _exc:  # noqa: BLE001 -- pragma: no cover -- same native-lib-load caveat as xgboost above
    shap = None
    _OPTIONAL_DEPENDENCY_IMPORT_ERRORS["shap"] = str(_exc)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.schema import movie_entity_key  # noqa: E402
from registry.schema import (  # noqa: E402
    ensure_factor_registry_schema, fetch_factor_definitions, fetch_movie_factor_values,
)
from market_index_schema import (  # noqa: E402
    SENSEX_INDEX_NAME, fetch_market_index_series, fetch_ticket_price_index_rows, nearest_prior_close,
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

# Feature 5: a lead/director/cast member's prior film counts as a "hit" when
# its own revenue/budget ratio clears this multiple. A named constant per the
# plan, not a magic number -- tune later once Feature 8's model comparison
# shows whether a different threshold predicts better.
LEAD_PRIOR_FILM_HIT_THRESHOLD = 1.5

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


# Feature 6: the two macro-factor reference tables (see market_index_schema.py),
# loaded once per run into these module-level globals -- same pattern as
# FACTOR_DEFS above. SENSEX_SERIES/TICKET_PRICE_ROWS default to empty, so a
# database where backfill_market_index.py/register_ticket_price.py haven't
# been run yet degrades to "no signal" (sensex_sentiment/ticket_price_level
# stay all-NaN, reported at 0% coverage) rather than crashing.
SENSEX_SERIES: dict[str, pd.Series] = {}
TICKET_PRICE_ROWS: list[dict] = []


def load_market_index_data(conn) -> None:
    """Populates SENSEX_SERIES/TICKET_PRICE_ROWS. Call once near the start
    of a run, same as load_factor_registry/load_eav_lookup."""
    global SENSEX_SERIES, TICKET_PRICE_ROWS
    SENSEX_SERIES = fetch_market_index_series(conn)
    TICKET_PRICE_ROWS = fetch_ticket_price_index_rows(conn)


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
    # Feature 7: production_companies is a real live column (see
    # compute_joint_production_partnerships_raw); overview is not live on
    # movies_data_collection today but is a target_column Feature 3's
    # the_movies_dataset source (sources.yaml) writes via collect_data.py's
    # ALTER TABLE ADD COLUMN IF NOT EXISTS -- degrades to all-NaN (and
    # remake_rights_detected reports 0% coverage) on a DB where that
    # connector hasn't run yet, same graceful-missing-column handling as
    # genres/imdb_rating above.
    "production_companies", "overview",
    # cbfc_rating: added alongside the data-entry UI (scripts/data_entry_ui.py) --
    # the factor_definitions row for this existed since Feature 3/7 registration
    # but had no backing column ("No certification column" in its notes) until now.
    "cbfc_rating",
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


def build_movie_financials_lookup(full_corpus_raw: pd.DataFrame) -> dict[str, tuple[float, float]]:
    """movie_key (movie_name.lower()|release_year) -> (budget, revenue), built
    from the FULL corpus (every market, disclosed or not) rather than the
    budget/revenue-floored training subset -- Feature 5's lead/director
    prior-film hit-rate lookups need to find an earlier film's financials even
    when that earlier film itself never cleared this run's --min-budget/
    --min-revenue floors. Only rows with real disclosed financials
    (has_financials_mask) are kept; an undisclosed prior film is intentionally
    absent from this lookup so callers can tell "checked, not disclosed" (the
    plan's explicit "leave null rather than guessing") apart from "no prior
    film at all"."""
    d = full_corpus_raw.copy()
    d["release_year"] = d["release_date"].map(parse_release_year)
    d = d[d["release_year"].notna()].copy()
    d["movie_key"] = d["movie_name"].str.strip().str.lower() + "|" + d["release_year"].astype(int).astype(str)
    disclosed = d[has_financials_mask(d)]
    lookup: dict[str, tuple[float, float]] = {}
    for key, grp in disclosed.groupby("movie_key"):
        lookup[key] = (float(grp["budget"].iloc[0]), float(grp["revenue"].iloc[0]))
    return lookup


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

    # Feature 7: dubbing/localization-breadth proxy for the subtitle_dubbing_quality
    # catalogue slot (quality itself isn't measurable, breadth is) -- count of
    # sibling rows sharing (movie_name, release_date) but a different `language`,
    # i.e. how many simultaneous language releases a title had. Computed HERE,
    # before drop_duplicates below collapses those sibling rows to one, since
    # every dedupe_movies() call site feeds straight into assemble_features() and
    # the signal can't be reconstructed after the sibling rows are gone.
    dubbing_group_key = df["movie_name"].astype(str).str.strip().str.lower() + "|" + df["release_date"].astype(str)
    df["dubbing_breadth_count"] = df.groupby(dubbing_group_key)["language"].transform("nunique").astype(float)

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


# ---------------------------------------------------------------------------
# Feature 6: macro factors -- Sensex 90-day pre-release momentum, ticket-price
# level. Both join `movies_data_collection.release_date` against a reference
# table (market_index_daily / ticket_price_index, see market_index_schema.py)
# rather than a per-movie column, and both are restricted to the Indian
# market -- a Sensex-derived "sentiment" number joined onto a Japanese or US
# release, or a PVR Inox-sourced ticket price applied to a non-Indian title,
# would be wrong, not just imprecise (same category of restriction as
# INDIA_ONLY_CALENDAR_FACTOR_KEYS above).
# ---------------------------------------------------------------------------

def compute_sensex_features_raw(df: pd.DataFrame, india_mask: pd.Series) -> pd.DataFrame:
    """Returns a 2-column frame: `sensex_close_at_release` (nearest prior
    trading day's close) and `sensex_90d_change_pct` (percent change over the
    90 days before that close) -- both NaN outside `india_mask` and wherever
    `market_index_daily` has no Sensex data yet (backfill_market_index.py
    hasn't been run). `sensex_90d_change_pct` is the raw signal
    `sensex_sentiment` (DERIVED_FACTOR_FNS) reports; `sensex_close_at_release`
    is carried onto the model frame for context/explainability only -- it has
    no registered factor of its own, per the plan."""
    out = pd.DataFrame(
        {"sensex_close_at_release": np.nan, "sensex_90d_change_pct": np.nan}, index=df.index)
    series = SENSEX_SERIES.get(SENSEX_INDEX_NAME)
    if series is None or series.empty:
        return out

    parsed = df["release_date"].map(parse_release_date)
    for idx in df.index:
        if not india_mask.loc[idx]:
            continue
        release_dt = parsed.loc[idx]
        if release_dt is None:
            continue
        close_now = nearest_prior_close(series, release_dt)
        close_90d_ago = nearest_prior_close(series, release_dt - pd.Timedelta(days=90))
        out.at[idx, "sensex_close_at_release"] = close_now
        if close_now is not None and close_90d_ago:
            out.at[idx, "sensex_90d_change_pct"] = 100.0 * (close_now - close_90d_ago) / close_90d_ago
    return out


def infer_city_tier(language_value, country_value) -> Optional[str]:
    """Coarse city-tier proxy for the ticket_price_index join -- there is no
    real per-movie release-city data anywhere in this schema, so this infers
    a tier bucket from language, the same documented-approximation way
    FESTIVE_WINDOWS stands in for a real per-year festival-date table: wide
    Hindi/English-language releases skew toward Tier-1-heavy multiplex
    distribution; other Indian-regional-language releases skew toward a more
    Tier-2/3-heavy mix; anything outside the Indian market has no matching
    ticket_price_index row at all. Must return one of
    market_index_schema.TICKET_PRICE_CITY_TIERS (or None) -- register_ticket_price.py
    enforces the same vocabulary on the write side so the join below always
    has a chance of matching."""
    if not is_india_market_row(language_value, country_value):
        return None
    lang = str(language_value or "").strip().lower()
    if lang in {"hindi", "english"}:
        return "tier_1"
    if is_indian_language_field(language_value):
        return "tier_2_3"
    return "national_average"


def compute_ticket_price_atp_raw(df: pd.DataFrame) -> pd.Series:
    """Average ticket price (USD) for each row's inferred city_tier bucket
    and release_date, from the hand-curated `ticket_price_index` table (see
    register_ticket_price.py) -- NaN wherever no row's [period_start,
    period_end] window covers the release_date for that tier, including
    every non-Indian row (infer_city_tier returns None there) and, until
    someone hand-enters real rows, every row at all (TICKET_PRICE_ROWS ships
    empty)."""
    out = pd.Series(np.nan, index=df.index)
    if not TICKET_PRICE_ROWS:
        return out

    parsed = df["release_date"].map(parse_release_date)
    tiers = df.apply(lambda r: infer_city_tier(r.get("language"), r.get("country")), axis=1)
    for idx in df.index:
        tier = tiers.loc[idx]
        release_dt = parsed.loc[idx]
        if tier is None or release_dt is None:
            continue
        for row in TICKET_PRICE_ROWS:
            if row["city_tier"] != tier:
                continue
            if pd.Timestamp(row["period_start"]) <= release_dt <= pd.Timestamp(row["period_end"]):
                out.at[idx] = float(row["atp_usd"])
                break
    return out


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


def build_actor_features(actors: pd.DataFrame,
                          movie_financials: Optional[dict[str, tuple[float, float]]] = None) -> pd.DataFrame:
    actors = actors.copy()
    actors["release_year"] = actors["release_date"].map(parse_release_year)
    actors = actors[actors["release_year"].notna()].copy()
    actors["release_year"] = actors["release_year"].astype(int)
    actors["actor_key"] = actors["actor_name"].str.strip().str.lower()
    actors["director_key"] = actors["director"].fillna("").str.strip().str.lower().replace("", np.nan)
    actors["movie_key"] = actors["movie_name"].str.strip().str.lower() + "|" + actors["release_year"].astype(str)
    if movie_financials:
        financials = actors["movie_key"].map(movie_financials)
        actors["own_budget"] = financials.map(lambda t: t[0] if isinstance(t, tuple) else np.nan)
        actors["own_revenue"] = financials.map(lambda t: t[1] if isinstance(t, tuple) else np.nan)
    else:
        actors["own_budget"] = np.nan
        actors["own_revenue"] = np.nan
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


# ---------------------------------------------------------------------------
# Feature 5: cast/crew track-record factors
#
# "Lead" = the actors_data_collection row(s) for this movie with the minimum
# available role_position (role_position=1 when present, else whichever
# position is smallest for that movie -- the plan's own fallback rule).
#
# The plan calls for a strict release_date (day-level) "prior" comparison,
# reusing parse_release_date. Verified directly against the live
# `actors_data_collection` table before wiring this up: 0 of its 62,413 rows
# match a day-level date (`release_date ~ '^\d{4}-\d{2}-\d{2}$'`) -- every
# row is year-only (e.g. "1971"). parse_release_date only accepts full
# YYYY-MM-DD strings, so a literal day-level comparison here would silently
# resolve every "prior" list to empty. Comparisons below therefore use
# release_year (strictly less than), the same granularity build_actor_features's
# `films_before` ranking and compute_release_overexposure's trailing-12-month
# window above already use for this exact table, for the same reason.
# ---------------------------------------------------------------------------

def compute_lead_actor_key(movie_key: str, actors_by_movie: dict) -> Optional[str]:
    rows = actors_by_movie.get(movie_key)
    if not rows:
        return None
    with_position = [r for r in rows if pd.notna(r.get("role_position"))]
    if not with_position:
        return None
    return min(with_position, key=lambda r: r["role_position"]).get("actor_key")


def _strictly_prior(history: list[dict], before_year: Optional[int]) -> list[dict]:
    """History entries with release_year strictly before `before_year` -- the
    shared no-leakage filter every Feature 5 lookup below uses. Year
    granularity, not release_date, per the module note above."""
    if before_year is None or pd.isna(before_year):
        return []
    return [h for h in history
            if h.get("release_year") is not None and pd.notna(h["release_year"])
            and h["release_year"] < before_year]


def _hit_flag_and_ratio(budget, revenue) -> tuple[Optional[float], Optional[float]]:
    """Only computable when the film in question itself has disclosed
    budget>0 and revenue>0 -- per the plan, leave both null rather than
    guessing when a prior film's financials were never disclosed (Feature 4's
    disclosure_likelihood explains why)."""
    if not (pd.notna(budget) and pd.notna(revenue) and budget > 0 and revenue > 0):
        return None, None
    ratio = float(revenue) / float(budget)
    return (1.0 if ratio >= LEAD_PRIOR_FILM_HIT_THRESHOLD else 0.0), ratio


def compute_lead_prior_films_count(movie_key: str, release_year: Optional[int],
                                    actors_by_movie: dict, actors_by_actor: dict) -> float:
    if release_year is None or pd.isna(release_year):
        return np.nan  # unparseable release_year -- "unknown", not "zero prior films"
    lead_key = compute_lead_actor_key(movie_key, actors_by_movie)
    if lead_key is None:
        return np.nan
    prior = _strictly_prior(actors_by_actor.get(lead_key, []), release_year)
    return float(len(prior))


def compute_lead_prior_film_hit(movie_key: str, release_year: Optional[int],
                                 actors_by_movie: dict, actors_by_actor: dict) -> tuple[float, float]:
    """Looks at the lead's most recent prior release_year and returns
    (hit_flag, revenue_ratio) for it -- np.nan/np.nan when the lead is
    unknown, has no prior film, or that prior film's financials were never
    disclosed. Year granularity means "most recent" can be a tie (the lead
    released more than one credited film in that same year); when it is, the
    two are averaged rather than picking one arbitrarily, since nothing in
    this schema orders same-year releases."""
    lead_key = compute_lead_actor_key(movie_key, actors_by_movie)
    if lead_key is None:
        return np.nan, np.nan
    prior = _strictly_prior(actors_by_actor.get(lead_key, []), release_year)
    if not prior:
        return np.nan, np.nan
    most_recent_year = max(h["release_year"] for h in prior)
    tied = [h for h in prior if h["release_year"] == most_recent_year]
    flags_ratios = [_hit_flag_and_ratio(h.get("budget"), h.get("revenue")) for h in tied]
    flags = [f for f, _r in flags_ratios if f is not None]
    ratios = [r for _f, r in flags_ratios if r is not None]
    return ((float(np.mean(flags)) if flags else np.nan),
            (float(np.mean(ratios)) if ratios else np.nan))


def compute_director_prior_films_count(director_key: Optional[str], release_year: Optional[int],
                                        directors_by_director: dict) -> float:
    if not director_key or pd.isna(director_key):
        return np.nan
    if release_year is None or pd.isna(release_year):
        return np.nan  # unparseable release_year -- "unknown", not "zero prior films"
    prior = _strictly_prior(directors_by_director.get(director_key, []), release_year)
    return float(len(prior))


def compute_director_prior_hit_rate(director_key: Optional[str], release_year: Optional[int],
                                     directors_by_director: dict) -> float:
    """Hit rate across every strictly-prior film of this director that itself
    has disclosed financials -- prior films with undisclosed financials are
    excluded from both numerator and denominator rather than counted as
    misses, same "leave null, don't guess" rule as the lead's own factors."""
    if not director_key or pd.isna(director_key):
        return np.nan
    prior = _strictly_prior(directors_by_director.get(director_key, []), release_year)
    flags = [f for f, _r in (_hit_flag_and_ratio(h.get("budget"), h.get("revenue")) for h in prior)
             if f is not None]
    return float(np.mean(flags)) if flags else np.nan


def compute_actor_prior_hit_rate(actor_key: Optional[str], release_year: Optional[int],
                                  actors_by_actor: dict) -> Optional[float]:
    if not actor_key:
        return None
    prior = _strictly_prior(actors_by_actor.get(actor_key, []), release_year)
    flags = [f for f, _r in (_hit_flag_and_ratio(h.get("budget"), h.get("revenue")) for h in prior)
             if f is not None]
    return float(np.mean(flags)) if flags else None


def compute_ensemble_avg_prior_hit_rate(movie_key: str, release_year: Optional[int],
                                         actors_by_movie: dict, actors_by_actor: dict) -> float:
    """role_weight()-weighted average, across the full credited cast, of each
    actor's OWN prior-film hit rate -- same role_wt-weighted num/den pattern
    compute_r_star uses, so an ensemble with a strong lead but an untested
    supporting cast isn't penalized/rewarded the same as one where every
    credited actor has a track record."""
    rows = actors_by_movie.get(movie_key)
    if not rows:
        return np.nan
    num, den = 0.0, 0.0
    for r in rows:
        rate = compute_actor_prior_hit_rate(r.get("actor_key"), release_year, actors_by_actor)
        if rate is None:
            continue
        wt = r.get("role_wt") or 0.0
        num += wt * rate
        den += wt
    return num / den if den > 0 else np.nan


# Columns Feature 5 persists back onto `movies_data_collection` (see
# persist_feature5_columns below) -- the RAW signal (an actual count/ratio/
# hit-rate), not the [stated_min, stated_max]-rescaled value
# compute_registry_features produces for the model's own feature matrix. A
# real count is a more useful thing for another consumer to read back off the
# table than a percentile band calibrated to one particular training run.
FEATURE5_PERSISTED_COLUMNS = [
    "lead_prior_films_count", "lead_prior_film_hit_flag", "lead_prior_film_revenue_ratio",
    "director_prior_films_count", "director_prior_hit_rate", "ensemble_avg_prior_hit_rate",
]


def compute_feature5_raw_columns(df: pd.DataFrame, actors_by_movie: dict, actors_by_actor: dict,
                                  directors_by_director: dict) -> pd.DataFrame:
    """Raw (unbanded) values for FEATURE5_PERSISTED_COLUMNS, computed directly
    from the same functions DERIVED_FACTOR_FNS wires into the registry --
    duplicated here (rather than reusing compute_registry_features' output)
    because that path returns the banded model-feature value, not the raw
    count/ratio/rate this function persists."""
    out = pd.DataFrame(index=df.index)
    out["lead_prior_films_count"] = df.apply(
        lambda r: compute_lead_prior_films_count(r["movie_key"], r["release_year"],
                                                   actors_by_movie, actors_by_actor), axis=1)
    hit = df.apply(
        lambda r: compute_lead_prior_film_hit(r["movie_key"], r["release_year"],
                                               actors_by_movie, actors_by_actor), axis=1)
    out["lead_prior_film_hit_flag"] = hit.map(lambda t: t[0])
    out["lead_prior_film_revenue_ratio"] = hit.map(lambda t: t[1])
    out["director_prior_films_count"] = df.apply(
        lambda r: compute_director_prior_films_count(r["director_key"], r["release_year"],
                                                       directors_by_director), axis=1)
    out["director_prior_hit_rate"] = df.apply(
        lambda r: compute_director_prior_hit_rate(r["director_key"], r["release_year"],
                                                    directors_by_director), axis=1)
    out["ensemble_avg_prior_hit_rate"] = df.apply(
        lambda r: compute_ensemble_avg_prior_hit_rate(r["movie_key"], r["release_year"],
                                                        actors_by_movie, actors_by_actor), axis=1)
    return out


def persist_feature5_columns(conn, df: pd.DataFrame) -> int:
    """Python-side equivalent of the `ensureSchema()`-style
    `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` + batched `UPDATE` convention
    `ConflictBalanceService`/`NarrativeNoveltyService` use on the Java side to
    persist `conflict_balance_score`/`narrative_novelty_score` -- applied here
    so both languages write `movies_data_collection` the same way.

    `df` must carry `movie_name`, `release_year`, and every
    FEATURE5_PERSISTED_COLUMNS column (see compute_feature5_raw_columns), one
    row per deduplicated (movie_name, release_year) entity. The UPDATE keys
    only on (movie_name, left(release_date, 4)), not the full (movie_name,
    release_date, language) triple Feature 1's connectors use -- these are
    per-FILM signals (a lead actor's track record doesn't change per dubbed-
    language edition), so every sibling language release of the same title/
    year intentionally gets the same value, matching how budget/revenue
    themselves are already shared across those sibling rows."""
    with conn.cursor() as cur:
        for col in FEATURE5_PERSISTED_COLUMNS:
            cur.execute(f"ALTER TABLE movies_data_collection ADD COLUMN IF NOT EXISTS {col} numeric")
    conn.commit()

    set_clause = ", ".join(f"{col} = %s" for col in FEATURE5_PERSISTED_COLUMNS)
    sql = (f"UPDATE movies_data_collection SET {set_clause} "
           f"WHERE movie_name = %s AND left(release_date, 4) = %s")
    param_rows = [
        (*(None if pd.isna(row[col]) else float(row[col]) for col in FEATURE5_PERSISTED_COLUMNS),
         row["movie_name"], str(int(row["release_year"])))
        for _, row in df.iterrows()
    ]
    with conn.cursor() as cur:
        # execute_batch's cursor.rowcount only reflects the last statement in
        # the batch, not a running total -- report rows attempted instead of
        # a misleading "rows updated" count.
        psycopg2.extras.execute_batch(cur, sql, param_rows)
    conn.commit()
    return len(param_rows)


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


# ---------------------------------------------------------------------------
# Feature 7: two zero-new-data legal/financial factors, computed directly from
# columns already in movies_data_collection -- no connector required. See
# register_feature7_factors.py for the factor_definitions registration.
# ---------------------------------------------------------------------------

def compute_joint_production_partnerships_raw(df: pd.DataFrame) -> pd.Series:
    """Count of comma-separated entries in movies_data_collection.production_companies
    -- a real column, real signal, available today with zero new data collection
    (catalogue slot 89). NaN (not 0) when production_companies itself is blank,
    so a genuinely-unknown row doesn't get scored as "zero partnerships"."""
    def _count_companies(value) -> float:
        if pd.isna(value) or not str(value).strip():
            return np.nan
        return float(len([c for c in str(value).split(",") if c.strip()]))
    return df["production_companies"].map(_count_companies)


# CBFC certification -> ordinal accessibility score (U = broadest audience/most
# accessible, UA = moderate, A = adults-only/most restrictive). Unrecognized or
# missing certifications map to NaN (no signal) rather than a guessed middle
# value -- percentile_into_band() rescales whatever's left into cbfc_rating's
# stated [-0.3, +0.3] Bidirectional band the usual way.
CBFC_RATING_ORDINAL = {"U": 2.0, "UA": 1.0, "S": 1.0, "A": 0.0}


def compute_cbfc_rating_raw(df: pd.DataFrame) -> pd.Series:
    normalized = (
        df["cbfc_rating"].astype(str).str.strip().str.upper()
        .str.replace(" ", "", regex=False).str.replace("-", "", regex=False)
        .str.replace("/", "", regex=False)
    )
    return normalized.map(CBFC_RATING_ORDINAL)


REMAKE_PHRASE_PATTERN = re.compile(
    r"(?i)\b(official\s+remake\s+of|remake\s+of\s+the|is\s+a\s+remake\s+of|"
    r"remake\s+of\s+the\s+\d{4}|based\s+on\s+the\s+\d{4}\s+film)\b"
)


def compute_remake_rights_detected_raw(df: pd.DataFrame) -> pd.Series:
    """Higher-confidence half of catalogue slot 78 (plagiarism_remake_rights),
    split out per the plan since remake-rights detection is a genuine
    pre-release fact while the plagiarism-allegation half is a rare news event
    (see plagiarism_allegation_events / LEGAL_EVENT_KEYWORDS below). Regex over
    `overview` (Feature 3's the_movies_dataset synopsis column) for explicit
    "official remake of ___" / "is a remake of ___" phrasing -- a real,
    if narrow, synopsis-NLP signal, not TMDB "belongs to collection" metadata
    (that needs a live TMDB API id lookup this repo doesn't have wired yet;
    see sources.yaml's tmdb_api_certification note for the same gap).
    NaN (not 0) when `overview` is null/absent -- movies_data_collection has
    no `overview` column on a DB where Feature 3's the_movies_dataset source
    hasn't been collected yet (WANTED_MOVIE_COLUMNS backfills it as all-NaN),
    so this degrades to 0% coverage rather than a false-negative 0."""
    if "overview" not in df.columns:
        return pd.Series(np.nan, index=df.index)
    overview = df["overview"]
    detected = overview.map(
        lambda v: 1.0 if isinstance(v, str) and REMAKE_PHRASE_PATTERN.search(v) else (np.nan if pd.isna(v) else 0.0)
    )
    return detected


# Feature 7: shared legal/controversy news-event feed (GDELT DOC 2.0 API,
# see connectors/news_event_feed.py + backfill_legal_news_events.py) --
# one connector class, seven factor_keys, keyword set differs per factor,
# connector logic doesn't. All seven are rare, mostly-zero events: most
# movies have zero legal drama, so even a perfectly accurate detector
# produces a mostly-zero flag with limited standalone lift on model
# accuracy -- built because it's cheap once the shared connector exists and
# Feature 8's SHAP output will show empirically whether any of them actually
# move predictions, not because any one is expected to be a top driver.
# Populated via movie_factor_values (computation_type='eav'), not a Python
# fn here -- DERIVED_FACTOR_FNS has no slot for these; they're resolved by
# resolve_factor_raw_and_banded's eav branch instead, same as any other
# EAV-backed factor.
LEGAL_NEWS_EVENT_FACTOR_KEYS = (
    "state_bans", "pre_release_leak", "title_ownership_disputes", "copyright_claims",
    "distribution_disputes", "name_similarity_disputes", "plagiarism_allegation_events",
)


# Feature 2: registry-driven factor resolution. Each entry is a raw-signal
# function keyed by `derivation_ref` (matched against factor_definitions rows
# with computation_type='derived_python_fn') -- this is where the raw compute
# logic above (compute_holiday_window_raw etc.) plugs in "by name instead of
# hardcoded into build_measurable_features", per the plan. Every function
# here has the same (df, actors_by_movie, actors_by_actor, directors_by_director)
# -> raw pd.Series signature so resolve_factor_raw_and_banded can call any of
# them uniformly (the fourth arg, added for Feature 5's director-keyed
# lookups, is unused by the pre-existing entries); the raw signal is
# pre-negation, pre-band -- direction-based sign flip and stated_min/max
# banding happen once, generically, in the resolver below.
DERIVED_FACTOR_FNS: dict[str, Callable[[pd.DataFrame, dict, dict, dict], pd.Series]] = {
    "star_overexposure": lambda df, abm, aba, dbd: df["movie_key"].map(
        lambda k: compute_release_overexposure(k, abm, aba)),
    "excessive_runtime": lambda df, abm, aba, dbd: compute_excessive_runtime_raw(df),
    "budget_scale_efficiency": lambda df, abm, aba, dbd: compute_budget_scale_efficiency_raw(df),
    "trailer_teaser_impact": lambda df, abm, aba, dbd: compute_trailer_timing_raw(df),
    "first_single_timing": lambda df, abm, aba, dbd: compute_song_timing_raw(df),
    "holiday_release_window": lambda df, abm, aba, dbd: compute_holiday_window_raw(df),
    "box_office_clashes": lambda df, abm, aba, dbd: compute_clash_raw(df),
    "exam_schedules": lambda df, abm, aba, dbd: compute_exam_season_raw(df),
    "ipl_sporting_events": lambda df, abm, aba, dbd: compute_ipl_season_raw(df),
    "summer_vacation_window": lambda df, abm, aba, dbd: compute_summer_window_raw(df),
    # Feature 5: cast/crew track-record factors, all keyed on the target row's
    # own release_year -- actors_data_collection has zero day-level release
    # dates (verified live), so "prior" here is a strict release_year
    # comparison; see the module note above compute_lead_actor_key.
    "lead_prior_films_count": lambda df, abm, aba, dbd: df.apply(
        lambda r: compute_lead_prior_films_count(r["movie_key"], r["release_year"], abm, aba), axis=1),
    "lead_prior_film_hit_flag": lambda df, abm, aba, dbd: df.apply(
        lambda r: compute_lead_prior_film_hit(r["movie_key"], r["release_year"], abm, aba)[0], axis=1),
    "lead_prior_film_revenue_ratio": lambda df, abm, aba, dbd: df.apply(
        lambda r: compute_lead_prior_film_hit(r["movie_key"], r["release_year"], abm, aba)[1], axis=1),
    "director_prior_films_count": lambda df, abm, aba, dbd: df.apply(
        lambda r: compute_director_prior_films_count(r["director_key"], r["release_year"], dbd), axis=1),
    "director_prior_hit_rate": lambda df, abm, aba, dbd: df.apply(
        lambda r: compute_director_prior_hit_rate(r["director_key"], r["release_year"], dbd), axis=1),
    "ensemble_avg_prior_hit_rate": lambda df, abm, aba, dbd: df.apply(
        lambda r: compute_ensemble_avg_prior_hit_rate(r["movie_key"], r["release_year"], abm, aba), axis=1),
    # Feature 6: assemble_features() computes these two columns directly onto
    # df before compute_registry_features runs (see the "Feature 6: macro
    # factors" block above df["r_concept"] assembly) -- both are already
    # India-market-masked/join-derived there, so these entries just read the
    # column back rather than recomputing.
    "sensex_sentiment": lambda df, abm, aba, dbd: df["sensex_90d_change_pct"],
    "ticket_price_level": lambda df, abm, aba, dbd: df["ticket_price_atp_usd"],
    # Feature 7: joint_production_partnerships/subtitle_dubbing_quality are
    # direct promotions of catalogue slots 89/86 (not new adjacent keys, unlike
    # the Feature 5/6 entries above) -- both compute from columns already on
    # df with zero new data collection. dubbing_breadth_count is precomputed
    # by dedupe_movies() (see its docstring) since the sibling-language rows
    # it counts are destroyed by that same function's collapse.
    "joint_production_partnerships": lambda df, abm, aba, dbd: compute_joint_production_partnerships_raw(df),
    "subtitle_dubbing_quality": lambda df, abm, aba, dbd: df["dubbing_breadth_count"],
    "remake_rights_detected": lambda df, abm, aba, dbd: compute_remake_rights_detected_raw(df),
    # Registered since Feature 3/7 but never had a backing column ("No
    # certification column" in its factor_definitions.notes) until
    # scripts/data_entry_ui.py's cbfc_rating text column landed.
    "cbfc_rating": lambda df, abm, aba, dbd: compute_cbfc_rating_raw(df),
}


def resolve_factor_raw_and_banded(
    df: pd.DataFrame, factor: dict, actors_by_movie: dict, actors_by_actor: dict,
    india_mask: pd.Series, eav_lookup: dict[str, dict[str, float]],
    directors_by_director: Optional[dict] = None,
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
        raw = fn(df, actors_by_movie, actors_by_actor, directors_by_director or {})
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
    directors_by_director: Optional[dict] = None,
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
            df, factor, actors_by_movie, actors_by_actor, india_mask, eav_lookup,
            directors_by_director)
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
            # Feature 5: this credit's own film's disclosed financials
            # (looked up in build_actor_features via
            # build_movie_financials_lookup), for the prior-film hit-rate
            # lookups below.
            "budget": row.get("own_budget"),
            "revenue": row.get("own_revenue"),
            "role_wt": row.get("role_wt"),
        })

    # Feature 5: director-keyed analog of actors_by_actor -- one entry per
    # distinct (director, movie) pair (actors_data_collection repeats the same
    # director on every cast row of a movie, so dedupe by movie_key first or
    # a multi-cast film would inflate its own director's prior-film count).
    directors_by_director: dict = {}
    director_rows = actors.dropna(subset=["director_key"]).drop_duplicates(subset=["director_key", "movie_key"])
    for _, row in director_rows.iterrows():
        directors_by_director.setdefault(row["director_key"], []).append({
            "movie_key": row["movie_key"],
            "release_year": int(row["release_year"]),
            "budget": row.get("own_budget"),
            "revenue": row.get("own_revenue"),
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

    # Feature 6: macro factors -- computed directly onto df (like
    # market_is_india/budget_effective above) rather than through
    # DERIVED_FACTOR_FNS's fixed 4-arg signature, since both need external
    # reference-table data (SENSEX_SERIES/TICKET_PRICE_ROWS) that signature
    # has no slot for. DERIVED_FACTOR_FNS['sensex_sentiment']/
    # ['ticket_price_level'] below just read these columns back off df.
    india_mask_for_macro = df["market_is_india"] == 1
    sensex_feats = compute_sensex_features_raw(df, india_mask_for_macro)
    df["sensex_close_at_release"] = sensex_feats["sensex_close_at_release"]
    df["sensex_90d_change_pct"] = sensex_feats["sensex_90d_change_pct"]
    df["ticket_price_atp_usd"] = compute_ticket_price_atp_raw(df)

    registry_feats, coverage_report = compute_registry_features(
        df, actors_by_movie, actors_by_actor, FACTOR_DEFS, eav_lookup or {}, directors_by_director)
    # A raw_column factor is free to name its factor_key after its own
    # source_column (e.g. Feature 3 registers factor_key='genres' straight off
    # movies_data_collection.genres) -- when it does, drop df's own copy of
    # that column before concatenating so the result has one "genres" column
    # (the banded model feature) instead of two same-named columns, which
    # pandas can't disambiguate (m[key] then returns a DataFrame, not a
    # Series, breaking every m[key].astype(...) call downstream). Everything
    # in this function that needed the raw pre-band column (primary_genre
    # above) already read it before this point.
    df = df.drop(columns=[c for c in registry_feats.columns if c in df.columns])
    full = pd.concat([df, registry_feats], axis=1)
    full.attrs["coverage_report"] = coverage_report
    # Feature 5: stash the self-join indices so main() can persist the raw
    # (unbanded) track-record columns back onto movies_data_collection
    # (persist_feature5_columns) without rebuilding them a second time.
    full.attrs["actors_by_movie"] = actors_by_movie
    full.attrs["actors_by_actor"] = actors_by_actor
    full.attrs["directors_by_director"] = directors_by_director
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
# On ~1,436 India-only rows and ~19 features, MLPRegressor is the *worst* of
# the four sklearn-only candidates tried (37.7% within 50% of actual revenue,
# median abs % error 66.4%), behind GradientBoostingRegressor (49.4%/51.1%,
# the winner), HistGradientBoostingRegressor (49.2%/50.7%), and Ridge
# (46.1%/54.4%) -- see output/model_comparison.json. That's a normal outcome
# for a small-n, mostly-numeric tabular problem: tree ensembles generally win
# there, and NNs need either much more data or structure trees can't exploit
# (learned entity embeddings, not just more depth on the current
# one-hot/target-encoded architecture) to earn an edge. Feature 8 (this
# block) adds three more tree-based candidates -- xgboost/lightgbm/catboost
# are the de facto standard for exactly this problem shape and routinely beat
# sklearn's own GBR/HistGBR -- rather than tuning the MLP further. Once
# Feature 0 grows the corpus to ~11k pooled rows and Features 5/6/7 add more
# categorical/relational structure (director, lead actor, genre, language),
# revisit with a small Keras/PyTorch MLP that *embeds* those high-cardinality
# categoricals as learned low-dimensional vectors instead of one-hot/target-
# encoding them -- that is the specific place NNs tend to earn an edge over
# trees on this kind of data, not a re-run of today's architecture on more
# rows. So this fits every candidate with the identical out-of-fold protocol
# and lets the accuracy numbers pick the winner, rather than assuming any one
# model (neural net included) is "the accurate way" a priori.

MODEL_FACTORIES: dict[str, Callable[[], object]] = {
    "ridge": lambda: Ridge(alpha=RIDGE_ALPHA, random_state=RNG_SEED),
    "gbr": lambda: GradientBoostingRegressor(n_estimators=300, max_depth=3, learning_rate=0.05, random_state=RNG_SEED),
    "hist_gbr": lambda: HistGradientBoostingRegressor(max_iter=300, max_depth=4, learning_rate=0.05, random_state=RNG_SEED),
    "mlp_neural_net": lambda: MLPRegressor(
        hidden_layer_sizes=(64, 32), activation="relu", alpha=1e-2, learning_rate_init=1e-3,
        early_stopping=True, n_iter_no_change=20, max_iter=2000, random_state=RNG_SEED),
}
# Feature 8: xgboost/lightgbm/catboost, added conditionally on the defensive
# imports above -- a host missing one of these still runs the comparison
# with a smaller candidate set instead of crashing at import time. CatBoost
# in particular handles high-cardinality categoricals (director, lead actor,
# genre, language) natively, worth trying as Features 5/6/7 add more of them.
if XGBRegressor is not None:
    MODEL_FACTORIES["xgboost"] = lambda: XGBRegressor(
        n_estimators=300, max_depth=4, learning_rate=0.05, random_state=RNG_SEED,
        objective="reg:squarederror", verbosity=0)
if LGBMRegressor is not None:
    MODEL_FACTORIES["lightgbm"] = lambda: LGBMRegressor(
        n_estimators=300, max_depth=4, learning_rate=0.05, random_state=RNG_SEED, verbosity=-1)
if CatBoostRegressor is not None:
    MODEL_FACTORIES["catboost"] = lambda: CatBoostRegressor(
        iterations=300, depth=4, learning_rate=0.05, random_seed=RNG_SEED, verbose=False)


def build_stacking_regressor(base_names: list[str]) -> StackingRegressor:
    """A `sklearn.ensemble.StackingRegressor` over `base_names` (expected to be
    the empirically-best 2-3 entries in MODEL_FACTORIES per this run's
    compare_models() results, picked by compare_stacking_ensemble() below),
    with a Ridge final estimator -- a small, interpretable meta-learner over
    a handful of already-decent base predictions, not another black box."""
    estimators = [(name, MODEL_FACTORIES[name]()) for name in base_names]
    return StackingRegressor(estimators=estimators, final_estimator=Ridge(alpha=RIDGE_ALPHA, random_state=RNG_SEED))


def cross_validated_predictions_for_model(df: pd.DataFrame, cols: list[str],
                                           model_factory: Callable[[], object], n_splits: int = CV_FOLDS,
                                           sample_weight: Optional[pd.Series] = None) -> pd.Series:
    """Same out-of-fold protocol as cross_validated_predictions_for, generalized
    to any zero-arg model factory rather than just a MODEL_FACTORIES lookup by
    name -- lets compare_stacking_ensemble() reuse this for a StackingRegressor
    built fresh per outer fold (each fold's stack does its own internal CV to
    build meta-features, per sklearn's own StackingRegressor.fit; this outer
    loop is what keeps the *reported* accuracy honestly out-of-fold on top of
    that, same as every other candidate here)."""
    X = df[cols].fillna(0).values
    y = df["ln_revenue"].values
    w = sample_weight.reindex(df.index).values if sample_weight is not None else None
    pred = np.full(len(df), np.nan)
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=RNG_SEED)
    for train_idx, test_idx in kf.split(X):
        scaler = StandardScaler()
        Xtr = scaler.fit_transform(X[train_idx])
        Xte = scaler.transform(X[test_idx])
        model = model_factory()
        try:
            model.fit(Xtr, y[train_idx], sample_weight=w[train_idx] if w is not None else None)
        except TypeError:
            model.fit(Xtr, y[train_idx])
        pred[test_idx] = model.predict(Xte)
    return pd.Series(pred, index=df.index)


def cross_validated_predictions_for(df: pd.DataFrame, cols: list[str], model_name: str,
                                     n_splits: int = CV_FOLDS,
                                     sample_weight: Optional[pd.Series] = None) -> pd.Series:
    """Same out-of-fold protocol as cross_validated_predictions, generalized to any model
    in MODEL_FACTORIES. Standardizing inputs is required for Ridge/MLP and harmless for
    the tree models, so every candidate runs through one uniform pipeline.
    `sample_weight` (Feature 4's inverse-probability weights) is passed to
    `.fit()` when the model supports it; MLPRegressor doesn't, so it silently
    falls back to an unweighted fit for that one model rather than crashing."""
    return cross_validated_predictions_for_model(df, cols, MODEL_FACTORIES[model_name], n_splits, sample_weight)


def compare_models(df: pd.DataFrame, cols: list[str]) -> dict:
    if len(df) < CV_FOLDS * 2:
        return {"error": f"insufficient rows for {CV_FOLDS}-fold CV: n={len(df)}"}
    results = {}
    for name in MODEL_FACTORIES:
        pred_log = cross_validated_predictions_for(df, cols, name)
        _, summary = evaluate_full_corpus_accuracy(df, pred_log)
        results[name] = summary
    return results


def pick_champion_model(results: dict) -> Optional[str]:
    """Lowest median |% error| among compare_models()'s (or compare_models()
    plus a registered stacking_ensemble entry's) valid candidates -- the same
    metric print_model_comparison() leads with. Returns None if every
    candidate errored (e.g. compare_models()'s own insufficient-rows guard)."""
    valid = {name: s for name, s in results.items()
             if isinstance(s, dict) and "error" not in s and "median_abs_pct_error" in s}
    if not valid:
        return None
    return min(valid, key=lambda n: valid[n]["median_abs_pct_error"])


def compare_stacking_ensemble(df: pd.DataFrame, cols: list[str], base_results: dict,
                               sample_weight: Optional[pd.Series] = None, top_k: int = 3) -> dict:
    """Ranks compare_models()'s base candidates by median |% error|, builds a
    StackingRegressor over the top `top_k` (default 3, per the plan's "best
    2-3 base models"), evaluates it with the identical out-of-fold protocol,
    and reports whether it beats the single best base model on the same
    metric -- "usually a small free win once base models exist," per the
    plan, not assumed to help without checking."""
    ranked = sorted(
        ((name, s) for name, s in base_results.items()
         if isinstance(s, dict) and "error" not in s and "median_abs_pct_error" in s),
        key=lambda kv: kv[1]["median_abs_pct_error"],
    )
    if len(ranked) < 2:
        return {"skipped": f"fewer than 2 valid base models ({len(ranked)})"}
    base_names = [name for name, _ in ranked[:top_k]]
    best_base_name, best_base_summary = ranked[0]

    pred_log = cross_validated_predictions_for_model(
        df, cols, lambda: build_stacking_regressor(base_names), sample_weight=sample_weight)
    _, summary = evaluate_full_corpus_accuracy(df, pred_log)
    beats_best = summary["median_abs_pct_error"] < best_base_summary["median_abs_pct_error"]
    return {
        "base_models_used": base_names, "summary": summary,
        "best_base_model": best_base_name, "best_base_median_abs_pct_error": best_base_summary["median_abs_pct_error"],
        "beats_best_base_model": beats_best,
    }


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


def print_stacking_result(stacking_result: dict) -> None:
    print("\n-- Stacking ensemble over the top base models --")
    if "skipped" in stacking_result:
        print(f"  skipped: {stacking_result['skipped']}")
        return
    s = stacking_result["summary"]
    n = s["n_movies"]
    print(f"  base models: {stacking_result['base_models_used']}")
    print(f"  stacking_ensemble  {s['median_abs_pct_error']:>13.1f}% "
          f"{s['within_20pct']['n_correct']:>5d}/{n:<5d} ({s['within_20pct']['pct_correct']:>5.1f}%) "
          f"{s['within_30pct']['n_correct']:>5d}/{n:<5d} "
          f"{s['within_50pct']['n_correct']:>5d}/{n:<5d}")
    verdict = "beats" if stacking_result["beats_best_base_model"] else "does NOT beat"
    print(f"  stacking {verdict} the best single base model "
          f"({stacking_result['best_base_model']}, {stacking_result['best_base_median_abs_pct_error']:.1f}%)")


# ---------------------------------------------------------------------------
# Feature 8: champion full-corpus fit, SHAP explanations, model persistence
# ---------------------------------------------------------------------------
# Once compare_models() (+ the stacking check above) has picked a winner by
# out-of-fold accuracy, everything below fits that ONE model exactly once on
# every disclosed-revenue row (not a CV fold -- that's what makes this the
# artifact worth persisting/serving, as opposed to the many per-fold models
# fit purely to measure accuracy above) and explains it with SHAP.

def fit_champion_on_full_corpus(model_factory: Callable[[], object], df: pd.DataFrame, cols: list[str],
                                 sample_weight: Optional[pd.Series] = None) -> tuple[StandardScaler, object]:
    """The "do one final fit on all available labeled rows" step the plan asks
    for once a champion is chosen -- every other fit in this script (compare_models,
    bootstrap_calibration, time_based_split's train/test) exists to measure
    accuracy honestly, not to produce the model that actually gets served.
    Takes a zero-arg factory (not a MODEL_FACTORIES name) so a winning
    stacking_ensemble can be fit here without mutating the module-level
    MODEL_FACTORIES registry -- same reasoning as
    cross_validated_predictions_for_model's split from cross_validated_predictions_for."""
    X = df[cols].fillna(0).values
    y = df["ln_revenue"].values
    scaler = StandardScaler()
    Xs = scaler.fit_transform(X)
    w = sample_weight.reindex(df.index).values if sample_weight is not None else None
    model = model_factory()
    try:
        model.fit(Xs, y, sample_weight=w)
    except TypeError:
        model.fit(Xs, y)
    return scaler, model


# Tree-based candidates get shap.TreeExplainer (fast, exact); everything else
# (Ridge, MLPRegressor, StackingRegressor) falls back to shap.Explainer's
# generic model-agnostic path, so any champion -- including one added later --
# gets *some* SHAP explanation rather than requiring a new special case here.
_SHAP_TREE_MODEL_NAMES = {"gbr", "hist_gbr", "xgboost", "lightgbm", "catboost"}
# Generic SHAP explanation is O(background_size) model calls per row; capping
# the background sample keeps a several-thousand-row corpus tractable for the
# non-tree fallback path (TreeExplainer doesn't need a background sample at all).
_SHAP_GENERIC_BACKGROUND_SIZE = 100


def compute_shap_values(model_name: str, model: object, scaler: StandardScaler,
                         df: pd.DataFrame, cols: list[str]) -> np.ndarray:
    """Model-agnostic SHAP explanation for whichever model compare_models()
    (or compare_stacking_ensemble()) picked as champion -- the standard,
    model-agnostic replacement for compute_factor_effects()'s single-model
    (MLP-only) partial-dependence sweep, per the plan. Raises if the `shap`
    package isn't installed or the explainer itself fails; callers wrap this
    in a try/except and fall back to the existing partial-dependence-only
    reporting (compute_factor_effects stays wired for exactly that, as the
    plan asks -- "a documented fallback for estimators SHAP doesn't cleanly
    support")."""
    if shap is None:
        raise RuntimeError(
            f"shap not installed ({_OPTIONAL_DEPENDENCY_IMPORT_ERRORS.get('shap')}); "
            "run `pip install shap` to enable SHAP-based factor explanations.")
    X = df[cols].fillna(0).values
    Xs = scaler.transform(X)
    if model_name in _SHAP_TREE_MODEL_NAMES:
        explainer = shap.TreeExplainer(model)
        shap_values = explainer.shap_values(Xs)
    else:
        n_background = min(_SHAP_GENERIC_BACKGROUND_SIZE, len(Xs))
        background = shap.sample(Xs, n_background, random_state=RNG_SEED) if len(Xs) > n_background else Xs
        explainer = shap.Explainer(model.predict, background)
        shap_values = explainer(Xs).values
    return np.asarray(shap_values)


def _shap_col_label(col: str) -> str:
    """ln1p_<factor_key> -> factor_key (matches delta_regressor_col's own
    convention); every other column (baseline anchors, market_is_india) is
    reported under its own feature-column name -- there's no factor_key for
    those, they're not factor_definitions-registry rows."""
    prefix = "ln1p_"
    return col[len(prefix):] if col.startswith(prefix) else col


def summarize_shap(shap_values: np.ndarray, cols: list[str]) -> dict[str, float]:
    """Corpus-wide mean(|SHAP|) per factor_key/feature -- what
    factor_impact_scores' new `mean_abs_shap` column reports alongside the
    existing calibrated_min/calibrated_max band, per the plan."""
    mean_abs = np.abs(shap_values).mean(axis=0)
    return {_shap_col_label(col): float(val) for col, val in zip(cols, mean_abs)}


def top_shap_drivers_per_row(shap_values: np.ndarray, cols: list[str], k: int = 5) -> list[list[dict]]:
    """Per-movie top-k |SHAP| drivers with sign, e.g. [{"factor": "r_star",
    "shap_value": 0.42}, ...] -- what makes a served prediction able to answer
    "why", not just "how much" (stored alongside movie_revenue_predictions.csv
    for Feature 10 to persist once that table exists)."""
    labels = [_shap_col_label(c) for c in cols]
    k = min(k, len(cols))
    drivers = []
    for row in shap_values:
        order = np.argsort(-np.abs(row))[:k]
        drivers.append([{"factor": labels[i], "shap_value": round(float(row[i]), 5)} for i in order])
    return drivers


def persist_model_artifact(model_name: str, model: object, scaler: StandardScaler, cols: list[str],
                            factor_keys_used: list[str], n_training_rows: int, models_dir: str) -> Optional[str]:
    """joblib.dump the champion model bundle to models/revenue_model_{version}.joblib
    -- until this, every model in compare_models() was fit fresh inside a CV
    loop and discarded, so nothing survived past a single run. This artifact
    is what Feature 9's single-movie prediction and Feature 10's serving are
    both meant to load; training happens once per scheduled run, inference
    happens on demand without retraining."""
    os.makedirs(models_dir, exist_ok=True)
    model_version = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    path = os.path.join(models_dir, f"revenue_model_{model_version}.joblib")
    joblib.dump({
        "model": model, "scaler": scaler, "feature_columns": cols,
        "factor_keys_used": factor_keys_used, "model_name": model_name,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_training_rows": n_training_rows,
    }, path)
    return path


def persist_disclosure_artifact(disclosure_result: dict, models_dir: str) -> Optional[str]:
    """joblib.dump Stage A's (Feature 4) fitted disclosure classifier bundle to
    models/disclosure_classifier_{version}.joblib -- same convention as
    persist_model_artifact above, one version-stamped file per run. Feature 9's
    predict_movie.py loads this to score a brand-new row's disclosure_likelihood
    on demand, instead of re-running fit_disclosure_classifier's full-corpus
    KFold comparison on every single-movie prediction call."""
    os.makedirs(models_dir, exist_ok=True)
    model_version = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    path = os.path.join(models_dir, f"disclosure_classifier_{model_version}.joblib")
    joblib.dump({
        "model": disclosure_result["model"], "scaler": disclosure_result["scaler"],
        "feature_columns": disclosure_result["feature_columns"],
        "model_name": disclosure_result["winner"],
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_rows": disclosure_result["n_rows"],
    }, path)
    return path


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
        # The final full-corpus-fit model/scaler/feature_columns (not just the
        # OOF diagnostics above) -- Feature 9's predict_movie.py needs to score
        # a brand-new, never-before-seen row's disclosure_likelihood on demand
        # without retraining Stage A from scratch on every single-movie
        # prediction call. Non-JSON-serializable -- callers that json.dump()
        # this dict (write_disclosure_outputs, main()'s model_comparison)
        # exclude these three keys the same way they already exclude
        # "disclosure_likelihood".
        "model": final_model, "scaler": scaler, "feature_columns": feat_cols,
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


def bootstrap_prediction_spread_for_row(train_df: pd.DataFrame, cols: list[str], target_X: np.ndarray,
                                         sample_weight: Optional[pd.Series] = None,
                                         n_iters: int = CONFIDENCE_BOOTSTRAP_ITERS) -> dict:
    """Feature 9 variant of bootstrap_prediction_spread: fits each bootstrap
    resample on `train_df` (a corpus of *historical* rows with real disclosed
    ln_revenue) exactly like the original, but reads the spread of predictions
    on a single external `target_X` row instead of scoring the same rows the
    resample was fit on -- what a genuinely upcoming movie (no ln_revenue of
    its own) needs, since it can't be part of `train_df` in the first place."""
    rng = np.random.RandomState(RNG_SEED)
    X = train_df[cols].fillna(0).values
    y = train_df["ln_revenue"].values
    w = sample_weight.reindex(train_df.index).values if sample_weight is not None else None
    n = len(train_df)
    preds = np.full(n_iters, np.nan)
    for i in range(n_iters):
        idx = rng.randint(0, n, size=n)
        model = GradientBoostingRegressor(**CONFIDENCE_GBR_PARAMS)
        try:
            model.fit(X[idx], y[idx], sample_weight=w[idx] if w is not None else None)
        except TypeError:
            model.fit(X[idx], y[idx])
        preds[i] = model.predict(target_X)[0]
    return {"p10_log": float(np.percentile(preds, 10)), "p90_log": float(np.percentile(preds, 90))}


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


_DISCLOSURE_NON_SERIALIZABLE_KEYS = {"disclosure_likelihood", "model", "scaler"}


def write_disclosure_outputs(disclosure_result: dict, full_df: pd.DataFrame, output_dir: str) -> None:
    os.makedirs(output_dir, exist_ok=True)
    report = {k: v for k, v in disclosure_result.items() if k not in _DISCLOSURE_NON_SERIALIZABLE_KEYS}
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
    """Feature 8: this partial-dependence sweep now feeds the formula-reconstruction
    pipeline (build_nn_calibrated_factor_table/formula_reconstruction_oof) only --
    the standalone "how much did each factor move the outcome" question is
    answered by SHAP instead (compute_shap_values/summarize_shap, computed on
    top of compare_models()'s actual champion, not a fixed MLP), per the plan:
    "keep compute_factor_effects's partial-dependence method only as a
    documented fallback for estimators SHAP doesn't cleanly support." It stays
    MLP-only here because the formula pipeline's Y = B0 * prod(1+delta_i)
    reconstruction specifically wants a single consistent nonlinear model's
    swing-from-stated-min-to-max reading across every CV fold, which is a
    different question from "explain the champion's actual predictions."

    For each measurable factor, sweep its feature from the literature stated_min to
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
        "movie_name": df["movie_name"], "release_date": df["release_date"], "language": df["language"],
        "release_year": df["release_year"],
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
# Feature 10: serve predictions/factor scores/accuracy history from Postgres,
# alongside the existing CSV/JSON writers above -- output/* stays the offline-
# analysis destination, these tables are what MovieRevenuePredictionController
# reads from the Java app. Same ensureSchema()-style CREATE TABLE IF NOT EXISTS
# + upsert convention persist_feature5_columns/predict_movie.py's
# persist_prediction already use.
# ---------------------------------------------------------------------------

# Same table predict_movie.py (Feature 9) pre-created for its own is_upcoming=true
# rows -- defined here (the "main" module) and imported by predict_movie.py so
# both writers share one schema definition rather than two copies drifting apart.
MOVIE_REVENUE_PREDICTIONS_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS movie_revenue_predictions (
        movie_name text, release_date text, language text,
        predicted_revenue numeric, confidence_band_low numeric, confidence_band_high numeric,
        actual_revenue numeric, abs_pct_error numeric, is_upcoming boolean default false,
        model_name text, model_version text, factor_keys_used jsonb, generated_at timestamptz,
        primary key (movie_name, release_date, language)
    )
"""

FACTOR_IMPACT_SCORES_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS factor_impact_scores (
        factor_key text PRIMARY KEY, name text, category text, direction text,
        stated_min numeric, stated_max numeric, status text, proxy_note text,
        coverage_pct numeric, corr_with_ln_revenue numeric,
        calibrated_min numeric, calibrated_max numeric, calibration_point_multiplier numeric,
        source text, n_obs integer, beta_p50 numeric, mean_abs_shap numeric,
        generated_at timestamptz
    )
"""

MODEL_COMPARISON_HISTORY_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS model_comparison_history (
        id serial PRIMARY KEY, model_name text, run_at timestamptz default now(),
        n_movies integer, within_20pct numeric, within_30pct numeric, within_50pct numeric,
        median_abs_pct_error numeric, factor_keys_used jsonb
    )
"""


def model_version_from_artifact_path(path: str) -> str:
    """Inverse of persist_model_artifact's `revenue_model_{version}.joblib` naming
    -- avoids changing that function's (tested) return type just to also hand back
    the version string; the version is fully recoverable from the path it returns."""
    base = os.path.basename(path)
    prefix, suffix = "revenue_model_", ".joblib"
    if base.startswith(prefix) and base.endswith(suffix):
        return base[len(prefix):-len(suffix)]
    return base


def persist_movie_revenue_predictions(conn, predictions: pd.DataFrame, model_name: str,
                                       model_version: str, factor_keys_used: list[str]) -> int:
    """Upserts every backtested row (is_upcoming=false, actual_revenue/abs_pct_error
    populated) into the same movie_revenue_predictions table predict_movie.py writes
    is_upcoming=true rows into -- one table serves both cases, per the plan, keyed on
    the (movie_name, release_date, language) primary key Feature 1's data_sources/
    Feature 2's movie_factor_values also use."""
    with conn.cursor() as cur:
        cur.execute(MOVIE_REVENUE_PREDICTIONS_SCHEMA_SQL)
    conn.commit()

    generated_at = datetime.now(timezone.utc)
    factor_keys_json = psycopg2.extras.Json(factor_keys_used)
    param_rows = []
    for _, row in predictions.iterrows():
        release_date, language = row.get("release_date"), row.get("language")
        if pd.isna(release_date) or pd.isna(language):
            # PK needs all three columns -- dedupe_movies always keeps a real
            # release_date/language on the row it picks, so this should be rare;
            # skip rather than write a row that can never match the PK on conflict.
            continue
        param_rows.append((
            row["movie_name"], str(release_date), str(language),
            None if pd.isna(row.get("predicted_revenue")) else float(row["predicted_revenue"]),
            None if pd.isna(row.get("confidence_band_low")) else float(row["confidence_band_low"]),
            None if pd.isna(row.get("confidence_band_high")) else float(row["confidence_band_high"]),
            None if pd.isna(row.get("actual_revenue")) else float(row["actual_revenue"]),
            None if pd.isna(row.get("abs_pct_error")) else float(row["abs_pct_error"]),
            model_name, model_version, factor_keys_json, generated_at,
        ))

    sql = """
        INSERT INTO movie_revenue_predictions
            (movie_name, release_date, language, predicted_revenue,
             confidence_band_low, confidence_band_high, actual_revenue, abs_pct_error,
             is_upcoming, model_name, model_version, factor_keys_used, generated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, false, %s, %s, %s, %s)
        ON CONFLICT (movie_name, release_date, language) DO UPDATE SET
            predicted_revenue = EXCLUDED.predicted_revenue,
            confidence_band_low = EXCLUDED.confidence_band_low,
            confidence_band_high = EXCLUDED.confidence_band_high,
            actual_revenue = EXCLUDED.actual_revenue, abs_pct_error = EXCLUDED.abs_pct_error,
            is_upcoming = false, model_name = EXCLUDED.model_name,
            model_version = EXCLUDED.model_version, factor_keys_used = EXCLUDED.factor_keys_used,
            generated_at = EXCLUDED.generated_at
    """
    with conn.cursor() as cur:
        psycopg2.extras.execute_batch(cur, sql, param_rows)
    conn.commit()
    return len(param_rows)


def persist_factor_impact_scores(conn, factor_table: list[dict]) -> int:
    """Upserts factor_impact_scores.csv's exact row shape into Postgres --
    the served, always-current counterpart to that CSV snapshot."""
    with conn.cursor() as cur:
        cur.execute(FACTOR_IMPACT_SCORES_SCHEMA_SQL)
    conn.commit()

    generated_at = datetime.now(timezone.utc)
    param_rows = [
        {
            "factor_key": r["key"], "name": r["name"], "category": r["category"],
            "direction": r["direction"], "stated_min": r["stated_min"], "stated_max": r["stated_max"],
            "status": r.get("status"), "proxy_note": r.get("proxy_note"),
            "coverage_pct": r.get("coverage_pct"), "corr_with_ln_revenue": r.get("corr_with_ln_revenue"),
            "calibrated_min": r.get("calibrated_min"), "calibrated_max": r.get("calibrated_max"),
            "calibration_point_multiplier": r.get("calibration_point_multiplier"),
            "source": r.get("source"), "n_obs": r.get("n_obs"), "beta_p50": r.get("beta_p50"),
            "mean_abs_shap": r.get("mean_abs_shap"), "generated_at": generated_at,
        }
        for r in factor_table
    ]
    sql = """
        INSERT INTO factor_impact_scores
            (factor_key, name, category, direction, stated_min, stated_max, status, proxy_note,
             coverage_pct, corr_with_ln_revenue, calibrated_min, calibrated_max,
             calibration_point_multiplier, source, n_obs, beta_p50, mean_abs_shap, generated_at)
        VALUES (%(factor_key)s, %(name)s, %(category)s, %(direction)s, %(stated_min)s, %(stated_max)s,
                %(status)s, %(proxy_note)s, %(coverage_pct)s, %(corr_with_ln_revenue)s,
                %(calibrated_min)s, %(calibrated_max)s, %(calibration_point_multiplier)s,
                %(source)s, %(n_obs)s, %(beta_p50)s, %(mean_abs_shap)s, %(generated_at)s)
        ON CONFLICT (factor_key) DO UPDATE SET
            name = EXCLUDED.name, category = EXCLUDED.category, direction = EXCLUDED.direction,
            stated_min = EXCLUDED.stated_min, stated_max = EXCLUDED.stated_max,
            status = EXCLUDED.status, proxy_note = EXCLUDED.proxy_note,
            coverage_pct = EXCLUDED.coverage_pct, corr_with_ln_revenue = EXCLUDED.corr_with_ln_revenue,
            calibrated_min = EXCLUDED.calibrated_min, calibrated_max = EXCLUDED.calibrated_max,
            calibration_point_multiplier = EXCLUDED.calibration_point_multiplier,
            source = EXCLUDED.source, n_obs = EXCLUDED.n_obs, beta_p50 = EXCLUDED.beta_p50,
            mean_abs_shap = EXCLUDED.mean_abs_shap, generated_at = EXCLUDED.generated_at
    """
    with conn.cursor() as cur:
        psycopg2.extras.execute_batch(cur, sql, param_rows)
    conn.commit()
    return len(param_rows)


def persist_model_comparison_history(conn, model_name: str, accuracy_summary: dict,
                                      factor_keys_used: list[str]) -> None:
    """Feature 11: appends (never overwrites) one row per run -- unlike
    output/model_comparison.json (write_nn_outputs, open(..., "w")), which is
    replaced in place every run, this table accumulates so a future accuracy
    regression (bad data pull, upstream schema drift, a stale connector) shows
    up as a visible trend, and so 'did adding factor X actually move accuracy'
    is answerable by diffing two rows before/after that factor was promoted to
    active. Also what Feature 10's /accuracy endpoint reads a 'latest run' row
    from."""
    with conn.cursor() as cur:
        cur.execute(MODEL_COMPARISON_HISTORY_SCHEMA_SQL)
    conn.commit()
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO model_comparison_history
                (model_name, n_movies, within_20pct, within_30pct, within_50pct,
                 median_abs_pct_error, factor_keys_used)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (
                model_name, accuracy_summary.get("n_movies"),
                accuracy_summary.get("within_20pct", {}).get("pct_correct"),
                accuracy_summary.get("within_30pct", {}).get("pct_correct"),
                accuracy_summary.get("within_50pct", {}).get("pct_correct"),
                accuracy_summary.get("median_abs_pct_error"),
                psycopg2.extras.Json(factor_keys_used),
            ),
        )
    conn.commit()


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
    p.add_argument("--models-dir", default="./models",
                    help="Feature 8: where the champion model artifact "
                         "(revenue_model_{version}.joblib) is written after the final "
                         "full-corpus fit (default ./models).")
    p.add_argument("--skip-shap", action="store_true",
                    help="Skip Feature 8's SHAP explanation step (still fits and persists the "
                         "champion model) -- useful for a fast dev-iteration run, since SHAP on "
                         "a non-tree champion can be slow on a large corpus.")
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

    if _OPTIONAL_DEPENDENCY_IMPORT_ERRORS:
        missing = ", ".join(sorted(_OPTIONAL_DEPENDENCY_IMPORT_ERRORS))
        print(f"Warning: optional Feature 8 dependencies not installed ({missing}); "
              f"`pip install {missing}` for the full model comparison / SHAP explanations. "
              f"Continuing with a smaller candidate set / partial-dependence-only fallback.")

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
        # Feature 6: Sensex/Nifty daily closes + the hand-curated ticket-price
        # table, into the SENSEX_SERIES/TICKET_PRICE_ROWS globals -- same
        # load-once-per-run pattern as the factor registry above.
        load_market_index_data(conn)

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

    # Feature 5: budget/revenue lookup keyed off the FULL corpus (not just the
    # rows this run's --min-budget/--min-revenue floors admit) so a lead/
    # director's earlier film's disclosed financials are found even when that
    # earlier film itself never cleared this run's floors.
    movie_financials = build_movie_financials_lookup(full_corpus_raw)
    actors = build_actor_features(actors_raw, movie_financials)

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
    disclosure_artifact_path = persist_disclosure_artifact(disclosure_result, args.models_dir)
    print(f"Persisted Stage A disclosure classifier artifact ({disclosure_result['winner']}) to "
          f"{disclosure_artifact_path}.")
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

    # Feature 5: persist the raw cast/crew track-record columns back onto
    # movies_data_collection (Java's ConflictBalanceService/
    # NarrativeNoveltyService ensureSchema() convention, applied here from
    # Python) -- a short-lived second connection since the one used to load
    # the corpus above was already closed. Best-effort: a persistence hiccup
    # here shouldn't abort the rest of this run's modeling/reporting.
    try:
        feature5_raw = compute_feature5_raw_columns(
            full, full.attrs["actors_by_movie"], full.attrs["actors_by_actor"],
            full.attrs["directors_by_director"])
        persist_df = pd.concat([full[["movie_name", "release_year"]], feature5_raw], axis=1)
        persist_conn = get_connection(args)
        try:
            n_persisted = persist_feature5_columns(persist_conn, persist_df)
        finally:
            persist_conn.close()
        print(f"Feature 5: persisted cast/crew track-record columns for {n_persisted} movie entities "
              f"onto movies_data_collection ({FEATURE5_PERSISTED_COLUMNS}).")
    except Exception as exc:  # noqa: BLE001 -- best-effort side write, never fatal to this run
        print(f"Warning: Feature 5 column persistence failed ({exc}); continuing without it.")

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

    print(f"\nComparing direct revenue-prediction models ({', '.join(MODEL_FACTORIES)})...")
    model_comparison = compare_models(model_df, cols)
    print_model_comparison(model_comparison)
    # Feature 8: snapshot before the non-model keys below get mixed into
    # model_comparison -- compare_stacking_ensemble()/pick_champion_model()
    # both need the pure {model_name: summary} shape compare_models() returns.
    base_model_results = dict(model_comparison)

    print("\nFitting a stacking ensemble over the top base models (Feature 8)...")
    stacking_result = compare_stacking_ensemble(model_df, cols, base_model_results, sample_weight=sample_weight)
    print_stacking_result(stacking_result)
    model_comparison["stacking_ensemble"] = stacking_result

    # Feature 8: champion = whichever candidate -- including the stacking
    # ensemble above, if it actually won -- has the lowest out-of-fold median
    # |% error|. `champion_factories` is a local name->factory lookup (a copy
    # of MODEL_FACTORIES plus, conditionally, stacking_ensemble) rather than
    # mutating the module-level MODEL_FACTORIES registry, so a repeated
    # in-process call to main() (or a test) never leaks a stale
    # stacking_ensemble entry into a later compare_models() run.
    champion_results = dict(base_model_results)
    champion_factories = dict(MODEL_FACTORIES)
    if "summary" in stacking_result:
        champion_factories["stacking_ensemble"] = (
            lambda bn=stacking_result["base_models_used"]: build_stacking_regressor(bn))
        champion_results["stacking_ensemble"] = stacking_result["summary"]
    champion_name = pick_champion_model(champion_results)

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
        k: v for k, v in disclosure_result.items() if k not in _DISCLOSURE_NON_SERIALIZABLE_KEYS
    }
    model_comparison["ipw_comparison"] = ipw_comparison

    # Feature 8: fit the champion exactly once on every disclosed-revenue row
    # (not a CV fold), explain it with SHAP, and persist it as a joblib
    # artifact -- until now every model in this script was fit fresh inside a
    # CV loop and discarded. shap_factor_importances/shap_top_drivers_by_row
    # stay empty (not an error) if SHAP is unavailable/fails or --skip-shap
    # is passed; compute_factor_effects's MLP partial-dependence calibration
    # below is unaffected either way, per the plan's documented-fallback note.
    shap_factor_importances: dict[str, float] = {}
    shap_top_drivers_by_row: Optional[list[list[dict]]] = None
    if champion_name is None:
        print("\nWarning: no valid champion model (compare_models() found insufficient rows); "
              "skipping the full-corpus fit, SHAP explanations, and model-artifact persistence.")
    else:
        print(f"\nChampion model (lowest median |% err| across {len(champion_results)} "
              f"candidates, Feature 8): {champion_name}")
        champion_scaler, champion_model = fit_champion_on_full_corpus(
            champion_factories[champion_name], model_df, cols, sample_weight=sample_weight)

        # Feature 10: the champion's OWN out-of-fold predictions/accuracy --
        # NOT the fixed-GBR accuracy_summary/predictions computed earlier in
        # this function (that pipeline predates compare_models() and stays a
        # GBR-specific baseline in output/movie_revenue_predictions.csv,
        # unchanged, per the plan's "keep cross_validated_predictions_for
        # machinery unchanged" note on Feature 8). Persisting to Postgres
        # under model_name=champion_name with GBR's numbers would silently
        # mislabel a different model's accuracy as the champion's -- this
        # genuinely diverges in practice (e.g. a catboost champion's own
        # 49.4% median |%err| vs. GBR's 51.8% on the same India-only corpus).
        champion_oof_pred_log = cross_validated_predictions_for_model(
            model_df, cols, champion_factories[champion_name], sample_weight=sample_weight)
        champion_predictions, champion_accuracy_summary = evaluate_full_corpus_accuracy(
            model_df, champion_oof_pred_log)
        if args.confidence_bootstrap_iters > 0:
            champion_predictions = attach_confidence_band(
                champion_predictions, model_df, cols, model_df["disclosure_likelihood"],
                sample_weight=sample_weight, n_iters=args.confidence_bootstrap_iters)

        if args.skip_shap:
            print("Skipping SHAP explanation step (--skip-shap).")
        else:
            try:
                shap_values = compute_shap_values(champion_name, champion_model, champion_scaler, model_df, cols)
                shap_factor_importances = summarize_shap(shap_values, cols)
                shap_top_drivers_by_row = top_shap_drivers_per_row(shap_values, cols)
                print(f"Computed SHAP explanations for {len(model_df)} rows via champion model {champion_name}.")
            except Exception as exc:  # noqa: BLE001 -- SHAP is additive reporting, never fatal to this run
                print(f"Warning: SHAP computation failed/unavailable ({exc}); factor_impact_scores "
                      f"will omit mean_abs_shap, falling back to the existing MLP partial-dependence "
                      f"calibration only.")

        model_artifact_path = persist_model_artifact(
            champion_name, champion_model, champion_scaler, cols, factor_keys_used,
            n_training_rows=len(model_df), models_dir=args.models_dir)
        print(f"Persisted champion model artifact ({champion_name}, n={len(model_df)} rows) to "
              f"{model_artifact_path}.")
        model_comparison["champion_model"] = {
            "model_name": champion_name, "model_artifact_path": model_artifact_path,
            "n_training_rows": len(model_df),
        }

    if shap_factor_importances:
        # Merge mean_abs_shap into the already-written factor_impact_scores
        # output alongside the existing calibrated_min/calibrated_max band,
        # per the plan -- re-writing is cheap and keeps write_outputs() as
        # the single place that shapes factor_impact_scores.json/.csv.
        for row in factor_table:
            row["mean_abs_shap"] = shap_factor_importances.get(row["key"])
        write_outputs(factor_table, baseline, model_metrics, args.output_dir)
    if shap_top_drivers_by_row is not None:
        # Store each prediction's top-5 SHAP drivers alongside it in
        # movie_revenue_predictions.csv -- what Feature 10's serving persists
        # to the movie_revenue_predictions table once that exists, so a
        # served prediction can answer "why", not just "how much".
        shap_drivers_series = pd.Series(
            [json.dumps(d) for d in shap_top_drivers_by_row], index=model_df.index, name="top_shap_drivers")
        predictions = predictions.join(shap_drivers_series, how="left")
        write_prediction_outputs(predictions, accuracy_summary, args.output_dir)
        if champion_name is not None:
            champion_predictions = champion_predictions.join(shap_drivers_series, how="left")

    # Feature 10/11: persist backtested predictions, factor scores, and this
    # run's accuracy summary (appended to model_comparison_history, Feature
    # 11's drift-monitoring trend) to Postgres, alongside the CSV/JSON writers
    # above -- output/* stays for offline analysis, these tables are what
    # MovieRevenuePredictionController serves. Best-effort/non-fatal, matching
    # persist_feature5_columns' convention -- a DB hiccup here shouldn't
    # invalidate an otherwise-successful run.
    if champion_name is not None:
        model_version = model_version_from_artifact_path(model_artifact_path)
        try:
            db_conn = get_connection(args)
            try:
                n_persisted = persist_movie_revenue_predictions(
                    db_conn, champion_predictions, champion_name, model_version, factor_keys_used)
                persist_factor_impact_scores(db_conn, factor_table)
                persist_model_comparison_history(
                    db_conn, champion_name, champion_accuracy_summary, factor_keys_used)
            finally:
                db_conn.close()
            print(f"Feature 10: persisted {n_persisted} movie_revenue_predictions row(s), "
                  f"{len(factor_table)} factor_impact_scores row(s), and one model_comparison_history "
                  f"row to Postgres (model {champion_name} {model_version}).")
        except Exception as exc:  # noqa: BLE001 -- best-effort side write, never fatal to this run
            print(f"Warning: Feature 10 Postgres persistence failed ({exc}); continuing "
                  f"(CSV/JSON outputs above are unaffected).")
    else:
        print("\nSkipping Feature 10 Postgres persistence: no champion model this run.")

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
