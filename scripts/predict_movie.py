#!/usr/bin/env python3
"""
Feature 9: predict a single upcoming (unreleased) movie
=========================================================

`movie_revenue_impact_model.py`'s whole pipeline -- assemble_features(),
compare_models(), the CV/backtest accuracy report -- exists to score already-
released movies against their own known revenue. `assemble_features()`
unconditionally computes `df["ln_revenue"] = np.log(df["revenue"].astype(float))`,
so every existing code path needs a real revenue up front. A movie that hasn't
come out yet has none by definition, so this is a genuinely separate inference
path, not a rerun of the training pipeline: it loads the champion model
artifact Feature 8 persists (models/revenue_model_{version}.joblib) and
Feature 4's disclosure-classifier artifact (models/disclosure_classifier_{version}.joblib),
builds the same pre-release-only features for one appended row, and predicts
-- no fitting happens here.

Requirements
------------
Same as movie_revenue_impact_model.py (this script imports it directly).

Usage
-----
    # An announced title already catalogued in movies_data_collection:
    python3 predict_movie.py --movie-name "Some Upcoming Film" \\
        --release-date 2027-01-15 --db-host localhost --db-name aura

    # A title that isn't in the database at all yet:
    python3 predict_movie.py --from-json upcoming.json

    # Or piped, e.g. from Java's ProcessBuilder:
    echo '{"movie_name": "...", ...}' | python3 predict_movie.py --from-json -

`--from-json` payload shape (required keys: movie_name, release_date,
language, budget; everything else optional):
    {
      "movie_name": "Some Upcoming Film", "release_date": "2027-01-15",
      "language": "hindi", "country": "India", "genre": "Action, Drama",
      "budget": 15000000, "runtime_mins": 145,
      "production_companies": "Studio A, Studio B",
      "director": "some director",
      "cast": [{"actor_name": "lead actor", "role_position": 1},
               {"actor_name": "second lead", "role_position": 2}],
      "trailer_release_date": "2026-11-01", "trailer_days_to_release": 75,
      "gdp_usd_billions": 3.9, "inflation_rate_pct": 5.1
    }
`revenue` is never read from this payload even if present -- it is always
forced to NaN before any feature is computed (see build_movie_row_from_json).

Progress/diagnostic messages go to stderr; the single JSON result line goes
to stdout, so a caller (a human, or Java's ProcessBuilder) can capture just
the result even when other messages were printed along the way.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys
from datetime import datetime, timezone
from typing import Optional

import numpy as np
import pandas as pd
import psycopg2.extras

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import movie_revenue_impact_model as m  # noqa: E402

REQUIRED_JSON_FIELDS = ("movie_name", "release_date", "language", "budget")

ACTOR_ROW_COLUMNS = ["actor_name", "movie_name", "release_date", "language", "genre", "director", "role_position"]

_PREDICTIONS_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS movie_revenue_predictions (
        movie_name text, release_date text, language text,
        predicted_revenue numeric, confidence_band_low numeric, confidence_band_high numeric,
        actual_revenue numeric, abs_pct_error numeric, is_upcoming boolean default false,
        model_name text, model_version text, factor_keys_used jsonb, generated_at timestamptz,
        primary key (movie_name, release_date, language)
    )
"""
# Feature 10 (not yet built) defines this exact table; Feature 9 needs
# somewhere to write an upcoming-movie prediction now, so it pre-creates it
# with the same schema (idempotent CREATE TABLE IF NOT EXISTS) rather than
# inventing a parallel one -- the same table serves both cases per the plan,
# distinguished by is_upcoming.


def _log(msg: str) -> None:
    print(msg, file=sys.stderr)


# ---------------------------------------------------------------------------
# Model artifact loading (Feature 8's revenue_model_*.joblib, Feature 4's
# disclosure_classifier_*.joblib)
# ---------------------------------------------------------------------------

def _latest_artifact_path(models_dir: str, prefix: str, version: Optional[str]) -> str:
    if version:
        path = os.path.join(models_dir, f"{prefix}_{version}.joblib")
        if not os.path.exists(path):
            raise FileNotFoundError(f"No {prefix} artifact at {path}")
        return path
    # Filenames are UTC-timestamp-stamped (persist_model_artifact/
    # persist_disclosure_artifact), so lexicographic order == chronological.
    candidates = sorted(glob.glob(os.path.join(models_dir, f"{prefix}_*.joblib")))
    if not candidates:
        raise FileNotFoundError(
            f"No {prefix}_*.joblib artifact found in {models_dir} -- run "
            f"movie_revenue_impact_model.py first so it can be persisted.")
    return candidates[-1]


def load_revenue_model(models_dir: str, version: Optional[str] = None) -> dict:
    path = _latest_artifact_path(models_dir, "revenue_model", version)
    bundle = m.joblib.load(path)
    bundle["model_version"] = os.path.basename(path)[len("revenue_model_"):-len(".joblib")]
    return bundle


def load_disclosure_model(models_dir: str, version: Optional[str] = None) -> Optional[dict]:
    try:
        return m.joblib.load(_latest_artifact_path(models_dir, "disclosure_classifier", version))
    except FileNotFoundError as exc:
        _log(f"Warning: {exc}; proceeding without a disclosure_likelihood/confidence band.")
        return None


# ---------------------------------------------------------------------------
# Building the one new row (two input modes)
# ---------------------------------------------------------------------------

def build_movie_row_from_json(attrs: dict) -> pd.DataFrame:
    """One-row DataFrame shaped like `movie_revenue_impact_model.WANTED_MOVIE_COLUMNS`,
    for a title that may not be in `movies_data_collection` at all yet.
    `revenue` is always forced to NaN regardless of what (if anything) the
    caller passes -- this is what makes this a genuinely separate inference
    path rather than a rerun of the training pipeline on a row that happens
    to carry a real label."""
    missing = [f for f in REQUIRED_JSON_FIELDS if attrs.get(f) in (None, "")]
    if missing:
        raise ValueError(f"--from-json payload missing required field(s): {missing}")
    row = {col: np.nan for col in m.WANTED_MOVIE_COLUMNS}
    for key, value in attrs.items():
        if key in row:
            row[key] = value
    if "directors" not in attrs and attrs.get("director"):
        row["directors"] = attrs["director"]
    row["revenue"] = np.nan
    return pd.DataFrame([row])


def fetch_movie_row_from_db(conn, movie_name: str, release_date: str,
                             language: Optional[str] = None) -> pd.DataFrame:
    """Looks up an already-catalogued announced title directly by primary key
    (or movie_name+release_date when language is ambiguous), bypassing
    load_movies()'s budget/revenue/year floors -- those would drop exactly the
    common upcoming-movie case (a title with no disclosed financials yet)."""
    existing = m.fetch_existing_columns(conn, "movies_data_collection")
    available_cols = [c for c in m.WANTED_MOVIE_COLUMNS if c in existing]
    missing_cols = [c for c in m.WANTED_MOVIE_COLUMNS if c not in existing]
    where = "movie_name = %(movie_name)s AND release_date = %(release_date)s"
    params = {"movie_name": movie_name, "release_date": release_date}
    if language:
        where += " AND language = %(language)s"
        params["language"] = language
    sql = f"SELECT {', '.join(available_cols)} FROM movies_data_collection WHERE {where} LIMIT 1"
    df = pd.read_sql(sql, conn, params=params)
    if df.empty:
        raise ValueError(
            f"No row found in movies_data_collection for movie_name={movie_name!r}, "
            f"release_date={release_date!r}"
            f"{f', language={language!r}' if language else ''}. Use --from-json for a title "
            f"that isn't catalogued yet.")
    for col in missing_cols:
        df[col] = np.nan
    # Feature 9: never trust a stored revenue for a prediction target -- if
    # this row somehow already has one, treating it as "upcoming" anyway
    # would be a training-pipeline backtest, not genuine inference.
    df["revenue"] = np.nan
    return df


def synthetic_actor_rows(movie_name: str, release_date: str, language: Optional[str],
                          genre: Optional[str], director: Optional[str],
                          cast: Optional[list[dict]]) -> pd.DataFrame:
    """actors_data_collection-shaped rows for a title that has no cast rows of
    its own yet, from the --from-json payload's `director`/`cast` fields --
    what lets Feature 5's actors_by_movie/directors_by_director self-joins
    (r_star, lead_prior_films_count, director_prior_hit_rate, ...) resolve for
    a movie that was never entered into actors_data_collection. Not needed
    for the DB-lookup mode: an already-catalogued title (via Feature 1's
    connectors) is expected to already have its own actors_data_collection
    rows, found the normal way once concatenated with the historical corpus."""
    cast = cast or []
    rows = [
        {"actor_name": entry["actor_name"], "movie_name": movie_name, "release_date": release_date,
         "language": language, "genre": genre, "director": director,
         "role_position": entry.get("role_position")}
        for entry in cast
    ]
    if not rows and director:
        # No cast supplied at all -- still register the director (actor_name
        # left null) so director_prior_films_count/director_prior_hit_rate,
        # which are keyed on directors_by_director rather than
        # actors_by_movie, can resolve even with zero known cast.
        rows.append({"actor_name": None, "movie_name": movie_name, "release_date": release_date,
                      "language": language, "genre": genre, "director": director, "role_position": None})
    return pd.DataFrame(rows, columns=ACTOR_ROW_COLUMNS)


# ---------------------------------------------------------------------------
# The DB-independent feature-assembly step (unit-testable without a live
# database -- see scripts/tests/test_predict_movie.py)
# ---------------------------------------------------------------------------

def build_inference_features(historical_movies_raw: pd.DataFrame, historical_actors_raw: pd.DataFrame,
                              new_row_raw: pd.DataFrame, new_actor_rows: pd.DataFrame,
                              eav_lookup: dict, movie_financials: dict) -> pd.DataFrame:
    """Mirrors assemble_features()'s feature computation for one appended
    "upcoming" row, except the `ln_revenue` line and anything downstream of
    it: every factor that depends only on pre-release-available data (budget,
    cast/director track record via the actors_data_collection self-join,
    macro/Sensex snapshot at the row's own release date, etc.) computes the
    same way as training, off the same corpus context, since the new row is
    concatenated onto the real historical corpus (already-deduped, real
    revenue) before assemble_features()/prepare_model_frame() run -- exactly
    what lets percentile-rank/historical-anchor factors (r_director, r_concept,
    budget_scale_efficiency, box_office_clashes, ...) be computed against the
    real corpus distribution instead of being reimplemented a second time
    here. `new_row_raw["revenue"]` must already be NaN (build_movie_row_from_json/
    fetch_movie_row_from_db both guarantee this) -- nothing here reads it.

    Returns prepare_model_frame()'s output over the FULL combined corpus;
    callers slice out the `is_inference_target` row(s)."""
    historical_deduped = m.dedupe_movies(historical_movies_raw)
    historical_deduped["is_inference_target"] = False

    new_row = new_row_raw.copy()
    new_row["release_year"] = new_row["release_date"].map(m.parse_release_year)
    if new_row["release_year"].isna().any():
        raise ValueError("release_date must start with a 4-digit year (YYYY-MM-DD).")
    new_row["release_year"] = new_row["release_year"].astype(int)
    new_row["movie_key"] = (new_row["movie_name"].astype(str).str.strip().str.lower() + "|"
                             + new_row["release_year"].astype(str))
    # A genuinely new/unreleased entry has exactly one language edition by
    # definition (this row is it) -- same "1" a real single-language release
    # gets from dedupe_movies' own dubbing_breadth_count computation.
    new_row["dubbing_breadth_count"] = 1.0
    new_row["is_inference_target"] = True

    combined_movies = pd.concat([historical_deduped, new_row], ignore_index=True, sort=False)
    combined_actors_raw = pd.concat([historical_actors_raw, new_actor_rows], ignore_index=True, sort=False)
    actors = m.build_actor_features(combined_actors_raw, movie_financials)

    assembled = m.assemble_features(combined_movies, actors, apply_india_filter=False, eav_lookup=eav_lookup)
    model_df = m.prepare_model_frame(assembled)

    # np.log(NaN) is NaN, never -inf/an exception (only np.log(0) would be
    # -inf) -- assert that explicitly here since a silent -inf/NaN leaking
    # from the forced-missing revenue column into a *different* column is
    # exactly the bug class this inference path exists to avoid.
    target_mask = model_df["is_inference_target"]
    if np.isinf(model_df.loc[target_mask].select_dtypes(include=[np.number]).to_numpy(dtype=float)).any():
        raise RuntimeError(
            "build_inference_features produced an infinite value on the inference row -- "
            "likely a missing-revenue edge case leaking into a derived column.")
    return model_df


# ---------------------------------------------------------------------------
# Disclosure likelihood for the new row (Feature 4's Stage A, scored on
# demand via the persisted artifact -- no retraining)
# ---------------------------------------------------------------------------

def score_disclosure_likelihood(full_corpus_raw: pd.DataFrame, new_row_raw: pd.DataFrame,
                                 disclosure_bundle: dict) -> float:
    combined_raw = pd.concat([full_corpus_raw, new_row_raw], ignore_index=True, sort=False)
    prerelease = m.compute_prerelease_movie_attrs(combined_raw)
    feats, _ = m.build_disclosure_features(prerelease)
    # Reindex to the exact one-hot columns the persisted classifier was
    # trained on: a category outside this run's top-K bucketing (or simply
    # absent from the training corpus) just contributes an all-zero dummy
    # row rather than a shape mismatch.
    target_feats = feats.iloc[[-1]].reindex(columns=disclosure_bundle["feature_columns"], fill_value=0)
    X = target_feats.fillna(0).values
    Xs = disclosure_bundle["scaler"].transform(X)
    return float(disclosure_bundle["model"].predict_proba(Xs)[0, 1])


# ---------------------------------------------------------------------------
# Persisting the result (best-effort, mirrors Feature 5's persist_feature5_columns
# non-fatal-on-failure convention -- this script's contract is to print a
# JSON result to stdout regardless of whether the DB write succeeds)
# ---------------------------------------------------------------------------

def persist_prediction(conn, result: dict) -> None:
    with conn.cursor() as cur:
        cur.execute(_PREDICTIONS_SCHEMA_SQL)
    conn.commit()
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO movie_revenue_predictions
                (movie_name, release_date, language, predicted_revenue,
                 confidence_band_low, confidence_band_high, actual_revenue, abs_pct_error,
                 is_upcoming, model_name, model_version, factor_keys_used, generated_at)
            VALUES (%(movie_name)s, %(release_date)s, %(language)s, %(predicted_revenue)s,
                    %(confidence_band_low)s, %(confidence_band_high)s, NULL, NULL,
                    true, %(model_name)s, %(model_version)s, %(factor_keys_used)s, %(generated_at)s)
            ON CONFLICT (movie_name, release_date, language) DO UPDATE SET
                predicted_revenue = EXCLUDED.predicted_revenue,
                confidence_band_low = EXCLUDED.confidence_band_low,
                confidence_band_high = EXCLUDED.confidence_band_high,
                is_upcoming = true, model_name = EXCLUDED.model_name,
                model_version = EXCLUDED.model_version, factor_keys_used = EXCLUDED.factor_keys_used,
                generated_at = EXCLUDED.generated_at
            """,
            {
                "movie_name": result["movie_name"], "release_date": result["release_date"],
                "language": result["language"], "predicted_revenue": result["predicted_revenue"],
                "confidence_band_low": result["confidence_band_low"],
                "confidence_band_high": result["confidence_band_high"],
                "model_name": result["model_name"], "model_version": result["model_version"],
                "factor_keys_used": psycopg2.extras.Json(result["factor_keys_used"]),
                "generated_at": result["generated_at"],
            },
        )
    conn.commit()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--min-year", type=int, default=m.MIN_RELEASE_YEAR)
    p.add_argument("--min-budget", type=int, default=m.MIN_BUDGET_USD)
    p.add_argument("--min-revenue", type=int, default=m.MIN_REVENUE_USD)
    p.add_argument("--models-dir", default="./models")
    p.add_argument("--model-version", default=None,
                    help="Which revenue_model_{version}.joblib to load (default: most recent).")
    p.add_argument("--disclosure-model-version", default=None,
                    help="Which disclosure_classifier_{version}.joblib to load (default: most recent).")

    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--from-json", metavar="PATH",
                       help="Path to a JSON attribute payload, or '-' to read it from stdin.")
    mode.add_argument("--movie-name", help="Look up an announced title already in movies_data_collection.")
    p.add_argument("--release-date", help="Required with --movie-name (YYYY-MM-DD).")
    p.add_argument("--language", default=None, help="Optional with --movie-name, for disambiguation.")

    p.add_argument("--confidence-bootstrap-iters", type=int, default=20, dest="confidence_bootstrap_iters",
                    help="Bootstrap refits for the confidence band (default 20; 0 skips it). Lower than "
                         "the training run's default since this refits synchronously per prediction call.")
    p.add_argument("--skip-shap", action="store_true", help="Skip the top-5 SHAP-drivers explanation step.")
    p.add_argument("--skip-db-write", action="store_true",
                    help="Don't persist to movie_revenue_predictions -- still prints the JSON result.")
    return p.parse_args()


def run(args: argparse.Namespace) -> dict:
    revenue_bundle = load_revenue_model(args.models_dir, args.model_version)
    disclosure_bundle = load_disclosure_model(args.models_dir, args.disclosure_model_version)

    conn = m.get_connection(args)
    try:
        m.load_factor_registry(conn)
        eav_lookup = m.load_eav_lookup(conn)
        m.load_market_index_data(conn)

        _log("Loading historical corpus context (movies + actor credits)...")
        full_corpus_raw = m.load_movies(conn, args.min_year, args.min_budget, args.min_revenue,
                                         restrict_to_india=False, require_financials=False)
        movies_raw_all = m.filter_financials(full_corpus_raw, args.min_budget, args.min_revenue)
        actors_raw = m.load_actor_credits(conn)
        movie_financials = m.build_movie_financials_lookup(full_corpus_raw)

        if args.from_json:
            raw_text = sys.stdin.read() if args.from_json == "-" else open(args.from_json).read()
            attrs = json.loads(raw_text)
            new_row_raw = build_movie_row_from_json(attrs)
            new_actor_rows = synthetic_actor_rows(
                attrs["movie_name"], attrs["release_date"], attrs.get("language"),
                attrs.get("genre"), attrs.get("director"), attrs.get("cast"))
        else:
            if not args.release_date:
                raise ValueError("--release-date is required with --movie-name.")
            new_row_raw = fetch_movie_row_from_db(conn, args.movie_name, args.release_date, args.language)
            new_actor_rows = pd.DataFrame(columns=ACTOR_ROW_COLUMNS)
    finally:
        conn.close()

    _log("Assembling pre-release-only features for the target row...")
    model_df = build_inference_features(movies_raw_all, actors_raw, new_row_raw, new_actor_rows,
                                         eav_lookup, movie_financials)
    target_row = model_df[model_df["is_inference_target"]]
    if len(target_row) != 1:
        raise RuntimeError(f"Expected exactly one inference target row, found {len(target_row)}.")

    cols = revenue_bundle["feature_columns"]
    missing_cols = [c for c in cols if c not in target_row.columns]
    if missing_cols:
        raise RuntimeError(
            f"The persisted model expects feature column(s) not present on this run: {missing_cols} "
            f"-- the factor registry may have changed since this artifact was trained.")

    X = target_row[cols].fillna(0).values
    Xs = revenue_bundle["scaler"].transform(X)
    pred_log = float(revenue_bundle["model"].predict(Xs)[0])
    predicted_revenue = float(np.exp(pred_log))

    disclosure_likelihood = None
    if disclosure_bundle is not None:
        disclosure_likelihood = score_disclosure_likelihood(full_corpus_raw, new_row_raw, disclosure_bundle)

    confidence_band_low: Optional[float] = None
    confidence_band_high: Optional[float] = None
    train_df = model_df[~model_df["is_inference_target"]]
    if args.confidence_bootstrap_iters > 0 and len(train_df) > 0:
        _log(f"Estimating confidence band ({args.confidence_bootstrap_iters} bootstrap refits)...")
        spread = m.bootstrap_prediction_spread_for_row(train_df, cols, X, n_iters=args.confidence_bootstrap_iters)
        dl_for_band = pd.Series([disclosure_likelihood if disclosure_likelihood is not None else np.nan])
        mult = float(m.confidence_band_multiplier(dl_for_band).iloc[0])
        half_width = ((spread["p90_log"] - spread["p10_log"]) / 2.0) * mult
        confidence_band_low = float(np.exp(pred_log - half_width))
        confidence_band_high = float(np.exp(pred_log + half_width))

    top_shap_drivers = None
    if not args.skip_shap:
        try:
            if revenue_bundle["model_name"] in m._SHAP_TREE_MODEL_NAMES:
                shap_input = target_row
            else:
                background = train_df.sample(min(len(train_df), 100), random_state=m.RNG_SEED)
                shap_input = pd.concat([background, target_row], ignore_index=True)
            shap_values = m.compute_shap_values(
                revenue_bundle["model_name"], revenue_bundle["model"], revenue_bundle["scaler"], shap_input, cols)
            top_shap_drivers = m.top_shap_drivers_per_row(shap_values[-1:], cols, k=5)[0]
        except Exception as exc:  # noqa: BLE001 -- SHAP is additive explanation, never fatal to a prediction
            _log(f"Warning: SHAP explanation failed/unavailable ({exc}); omitting top_shap_drivers.")

    row = new_row_raw.iloc[0]
    result = {
        "movie_name": row["movie_name"], "release_date": row["release_date"],
        "language": row.get("language"), "is_upcoming": True,
        "predicted_revenue": round(predicted_revenue, 0),
        "confidence_band_low": round(confidence_band_low, 0) if confidence_band_low is not None else None,
        "confidence_band_high": round(confidence_band_high, 0) if confidence_band_high is not None else None,
        "disclosure_likelihood": round(disclosure_likelihood, 4) if disclosure_likelihood is not None else None,
        "top_shap_drivers": top_shap_drivers,
        "model_name": revenue_bundle["model_name"], "model_version": revenue_bundle["model_version"],
        "factor_keys_used": revenue_bundle["factor_keys_used"],
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }

    if not args.skip_db_write:
        try:
            write_conn = m.get_connection(args)
            try:
                persist_prediction(write_conn, result)
            finally:
                write_conn.close()
            _log(f"Persisted prediction to movie_revenue_predictions "
                 f"({result['movie_name']}, {result['release_date']}, {result['language']}).")
        except Exception as exc:  # noqa: BLE001 -- best-effort side write, never fatal to this run
            _log(f"Warning: could not persist prediction to movie_revenue_predictions ({exc}).")

    return result


def main() -> None:
    args = parse_args()
    result = run(args)
    print(json.dumps(result, default=str))


if __name__ == "__main__":
    main()
