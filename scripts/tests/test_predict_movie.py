"""Unit tests for Feature 9's single-upcoming-movie inference path
(scripts/predict_movie.py). No live DB/network call: `build_inference_features()`
takes already-loaded DataFrames, so a small synthetic "historical corpus" plus
one synthetic upcoming-movie JSON payload (deliberately carrying no `revenue`
key at all) is enough to exercise the whole pre-release-only feature-assembly
path -- the exact bug class this test guards against is a silent -inf/NaN
leaking out of `assemble_features()`'s unconditional `np.log(df["revenue"])`
line when the target row's revenue is missing by design, not by accident."""
from __future__ import annotations

import os
import sys
import unittest

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import movie_revenue_impact_model as model  # noqa: E402
from predict_movie import (  # noqa: E402
    ACTOR_ROW_COLUMNS,
    build_inference_features,
    build_movie_row_from_json,
    synthetic_actor_rows,
)


def _historical_movies() -> pd.DataFrame:
    cols = model.WANTED_MOVIE_COLUMNS
    rows = [
        {"movie_name": "Old Hit One", "release_date": "2018-05-10", "language": "hindi",
         "country": "India", "genre": "Action", "genres": None, "directors": "director_x",
         "budget": 4_000_000, "revenue": 12_000_000, "runtime_mins": 140, "imdb_rating": None,
         "rating_10": 7.5, "gdp_usd_billions": 2700.0, "inflation_rate_pct": 4.0,
         "trailer_release_date": "2018-03-01", "teaser_release_date": None,
         "first_song_release_date": None, "trailer_days_to_release": 70, "teaser_days_to_release": None,
         "song_days_to_release": None, "trailer_views": 5_000_000, "teaser_views": None,
         "trailer_comments": 1000, "teaser_comments": None, "conflict_balance_score": None,
         "narrative_novelty_score": None, "production_companies": "Studio A", "overview": None},
        {"movie_name": "Old Hit Two", "release_date": "2020-08-20", "language": "hindi",
         "country": "India", "genre": "Action", "genres": None, "directors": "director_x",
         "budget": 6_000_000, "revenue": 4_000_000, "runtime_mins": 130, "imdb_rating": None,
         "rating_10": 6.0, "gdp_usd_billions": 2800.0, "inflation_rate_pct": 6.0,
         "trailer_release_date": "2020-06-01", "teaser_release_date": None,
         "first_song_release_date": None, "trailer_days_to_release": 80, "teaser_days_to_release": None,
         "song_days_to_release": None, "trailer_views": 2_000_000, "teaser_views": None,
         "trailer_comments": 500, "teaser_comments": None, "conflict_balance_score": None,
         "narrative_novelty_score": None, "production_companies": "Studio A", "overview": None},
        {"movie_name": "Unrelated Drama", "release_date": "2019-02-14", "language": "english",
         "country": "USA", "genre": "Drama", "genres": None, "directors": "someone_else",
         "budget": 20_000_000, "revenue": 55_000_000, "runtime_mins": 110, "imdb_rating": None,
         "rating_10": 7.0, "gdp_usd_billions": 21000.0, "inflation_rate_pct": 2.0,
         "trailer_release_date": "2018-12-01", "teaser_release_date": None,
         "first_song_release_date": None, "trailer_days_to_release": 75, "teaser_days_to_release": None,
         "song_days_to_release": None, "trailer_views": 8_000_000, "teaser_views": None,
         "trailer_comments": 2000, "teaser_comments": None, "conflict_balance_score": None,
         "narrative_novelty_score": None, "production_companies": "Studio B", "overview": None},
    ]
    return pd.DataFrame(rows, columns=cols)


def _historical_actors() -> pd.DataFrame:
    rows = [
        {"actor_name": "Lead Actor X", "movie_name": "Old Hit One", "release_date": "2018-05-10",
         "language": "hindi", "genre": "Action", "director": "director_x", "role_position": 1},
        {"actor_name": "Lead Actor X", "movie_name": "Old Hit Two", "release_date": "2020-08-20",
         "language": "hindi", "genre": "Action", "director": "director_x", "role_position": 1},
        {"actor_name": "Someone Else", "movie_name": "Unrelated Drama", "release_date": "2019-02-14",
         "language": "english", "genre": "Drama", "director": "someone_else", "role_position": 1},
    ]
    return pd.DataFrame(rows, columns=ACTOR_ROW_COLUMNS)


def _stub_factor_defs() -> list[dict]:
    """A small mix of raw_column/derived_python_fn/active/candidate rows --
    enough to exercise compute_registry_features' real code paths without
    needing the live factor_definitions table."""
    return [
        {"factor_key": "conflict_balance", "name": "Conflict Balance", "category": "Narrative",
         "direction": "Positive", "stated_min": 0.0, "stated_max": 1.0, "data_type": "numeric",
         "status": "active", "source_table": "movies_data_collection",
         "source_column": "conflict_balance_score", "computation_type": "raw_column",
         "derivation_ref": None},
        {"factor_key": "star_overexposure", "name": "Star Overexposure", "category": "Cast",
         "direction": "Negative", "stated_min": -0.5, "stated_max": 0.5, "data_type": "numeric",
         "status": "active", "source_table": None, "source_column": None,
         "computation_type": "derived_python_fn", "derivation_ref": "star_overexposure"},
        {"factor_key": "lead_prior_films_count", "name": "Lead Prior Films", "category": "Cast",
         "direction": "Positive", "stated_min": 0.0, "stated_max": 1.0, "data_type": "numeric",
         "status": "active", "source_table": None, "source_column": None,
         "computation_type": "derived_python_fn", "derivation_ref": "lead_prior_films_count"},
        {"factor_key": "box_office_clashes", "name": "Box Office Clashes", "category": "Timing",
         "direction": "Negative", "stated_min": -0.3, "stated_max": 0.0, "data_type": "numeric",
         "status": "candidate", "source_table": None, "source_column": None,
         "computation_type": "derived_python_fn", "derivation_ref": "box_office_clashes"},
    ]


class PredictMovieTestCase(unittest.TestCase):
    def setUp(self):
        self._orig_factor_defs = model.FACTOR_DEFS
        self._orig_factor_by_key = model.FACTOR_BY_KEY
        model.FACTOR_DEFS = _stub_factor_defs()
        model.FACTOR_BY_KEY = {f["factor_key"]: f for f in model.FACTOR_DEFS}

    def tearDown(self):
        model.FACTOR_DEFS = self._orig_factor_defs
        model.FACTOR_BY_KEY = self._orig_factor_by_key


class BuildMovieRowFromJsonTest(PredictMovieTestCase):
    def test_revenue_always_forced_to_nan_even_if_supplied(self):
        row = build_movie_row_from_json({
            "movie_name": "Sneaky", "release_date": "2027-01-01", "language": "hindi",
            "budget": 1_000_000, "revenue": 999_000_000,
        })
        self.assertTrue(np.isnan(row.loc[0, "revenue"]))

    def test_missing_required_field_raises(self):
        with self.assertRaises(ValueError):
            build_movie_row_from_json({"movie_name": "No Budget", "release_date": "2027-01-01",
                                        "language": "hindi"})

    def test_director_fills_directors_column_when_absent(self):
        row = build_movie_row_from_json({
            "movie_name": "X", "release_date": "2027-01-01", "language": "hindi",
            "budget": 1, "director": "director_x",
        })
        self.assertEqual(row.loc[0, "directors"], "director_x")


class SyntheticActorRowsTest(PredictMovieTestCase):
    def test_cast_list_becomes_rows(self):
        rows = synthetic_actor_rows("X", "2027-01-01", "hindi", "Action", "director_x",
                                     [{"actor_name": "A", "role_position": 1},
                                      {"actor_name": "B", "role_position": 2}])
        self.assertEqual(len(rows), 2)
        self.assertEqual(list(rows["actor_name"]), ["A", "B"])

    def test_no_cast_but_director_still_registers_a_row(self):
        rows = synthetic_actor_rows("X", "2027-01-01", "hindi", "Action", "director_x", None)
        self.assertEqual(len(rows), 1)
        self.assertIsNone(rows.loc[0, "actor_name"])
        self.assertEqual(rows.loc[0, "director"], "director_x")

    def test_no_cast_no_director_produces_no_rows(self):
        rows = synthetic_actor_rows("X", "2027-01-01", "hindi", "Action", None, None)
        self.assertEqual(len(rows), 0)


class BuildInferenceFeaturesTest(PredictMovieTestCase):
    """The plan's explicit test requirement: feed a synthetic upcoming-movie
    JSON (no `revenue` key at all) through build_inference_features() and
    confirm a full feature row comes out with no exception and no accidental
    NaN/inf propagating from the missing revenue column."""

    def _run(self, attrs: dict, cast=None):
        historical_movies = _historical_movies()
        historical_actors = _historical_actors()
        new_row_raw = build_movie_row_from_json(attrs)
        new_actor_rows = synthetic_actor_rows(
            attrs["movie_name"], attrs["release_date"], attrs.get("language"),
            attrs.get("genre"), attrs.get("director"), cast)
        movie_financials = model.build_movie_financials_lookup(historical_movies)
        model_df = build_inference_features(
            historical_movies, historical_actors, new_row_raw, new_actor_rows,
            eav_lookup={}, movie_financials=movie_financials)
        return model_df

    def test_produces_exactly_one_target_row_with_full_features_no_exception(self):
        attrs = {
            "movie_name": "Upcoming Sequel", "release_date": "2027-06-15", "language": "hindi",
            "country": "India", "genre": "Action", "budget": 5_000_000, "director": "director_x",
        }
        model_df = self._run(attrs, cast=[{"actor_name": "Lead Actor X", "role_position": 1}])

        target = model_df[model_df["is_inference_target"]]
        self.assertEqual(len(target), 1)

        row = target.iloc[0]
        # The whole point: revenue is unknown, so ln_revenue must be NaN --
        # never -inf (that would mean log(0) silently ran) and never raise.
        self.assertTrue(np.isnan(row["ln_revenue"]))
        self.assertFalse(np.isinf(row["ln_revenue"]))

        # But every pre-release-available baseline feature must compute
        # cleanly to a finite number -- budget was supplied.
        self.assertTrue(np.isfinite(row["ln_budget_effective"]))
        self.assertIn(row["market_is_india"], (0, 1))
        self.assertEqual(row["market_is_india"], 1)
        self.assertIn(row["franchise_flag"], (0, 1))

        # Lead actor X has exactly one strictly-prior credited film in the
        # synthetic historical corpus (Old Hit One, before Old Hit Two) --
        # director_x has two -- so Feature 5's registry-driven columns should
        # have resolved to *something* real, not be silently dropped.
        coverage = {c["factor_key"]: c for c in model_df.attrs.get("coverage_report", [])}
        self.assertIn("lead_prior_films_count", coverage)
        self.assertGreater(coverage["lead_prior_films_count"]["n_obs"], 0)

        # No infinite value anywhere on the target row's numeric columns.
        numeric = target.select_dtypes(include=[np.number]).to_numpy(dtype=float)
        self.assertFalse(np.isinf(numeric).any())

    def test_no_cast_or_director_still_assembles_without_exception(self):
        attrs = {
            "movie_name": "Total Unknown", "release_date": "2027-09-01", "language": "tamil",
            "country": "India", "genre": "Comedy", "budget": 1_500_000,
        }
        model_df = self._run(attrs, cast=None)
        target = model_df[model_df["is_inference_target"]]
        self.assertEqual(len(target), 1)
        row = target.iloc[0]
        self.assertTrue(np.isnan(row["ln_revenue"]))
        self.assertTrue(np.isfinite(row["ln_budget_effective"]))

    def test_historical_rows_unaffected_keep_real_ln_revenue(self):
        attrs = {
            "movie_name": "Upcoming Sequel", "release_date": "2027-06-15", "language": "hindi",
            "country": "India", "genre": "Action", "budget": 5_000_000, "director": "director_x",
        }
        model_df = self._run(attrs, cast=[{"actor_name": "Lead Actor X", "role_position": 1}])
        historical = model_df[~model_df["is_inference_target"]]
        self.assertEqual(len(historical), 3)
        self.assertTrue(historical["ln_revenue"].notna().all())


if __name__ == "__main__":
    unittest.main()
