"""Unit tests for Feature 7's legal/financial factors -- the two
zero-new-data compute functions (joint_production_partnerships,
subtitle_dubbing_quality's dubbing_breadth_count), the remake-rights
synopsis-NLP detector, and backfill_legal_news_events.py's fetch/write
orchestration (fetch_fn injected, no live GDELT call -- same pattern
test_feature6_macro_factors.py's BackfillMarketIndexTest uses).
"""
from __future__ import annotations

import os
import sys
import unittest

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import movie_revenue_impact_model as model  # noqa: E402


class ComputeJointProductionPartnershipsRawTest(unittest.TestCase):
    def test_counts_comma_separated_entries(self):
        df = pd.DataFrame({
            "production_companies": ["Studio A, Studio B, Studio C", "Solo Studio", None, "  "],
        })
        out = model.compute_joint_production_partnerships_raw(df)
        self.assertEqual(out.iloc[0], 3.0)
        self.assertEqual(out.iloc[1], 1.0)
        self.assertTrue(pd.isna(out.iloc[2]))
        self.assertTrue(pd.isna(out.iloc[3]))

    def test_ignores_stray_commas_and_whitespace(self):
        df = pd.DataFrame({"production_companies": ["Studio A, , Studio B,"]})
        out = model.compute_joint_production_partnerships_raw(df)
        self.assertEqual(out.iloc[0], 2.0)


class DubbingBreadthCountTest(unittest.TestCase):
    def test_sibling_language_rows_are_counted_before_dedup(self):
        df = pd.DataFrame({
            "movie_name": ["Big Movie", "Big Movie", "Big Movie", "Other Movie"],
            "release_date": ["2020-01-10", "2020-01-10", "2020-01-10", "2020-02-01"],
            "language": ["hindi", "tamil", "telugu", "english"],
            "conflict_balance_score": [np.nan] * 4,
            "narrative_novelty_score": [np.nan] * 4,
            "directors": ["Dir A"] * 4,
            "revenue": [100.0, 100.0, 100.0, 50.0],
        })
        deduped = model.dedupe_movies(df)
        big_movie_row = deduped[deduped["movie_name"] == "Big Movie"].iloc[0]
        other_row = deduped[deduped["movie_name"] == "Other Movie"].iloc[0]
        # Three simultaneous-language siblings collapse to one row, but the
        # breadth count (3) must survive the collapse.
        self.assertEqual(len(deduped), 2)
        self.assertEqual(big_movie_row["dubbing_breadth_count"], 3.0)
        self.assertEqual(other_row["dubbing_breadth_count"], 1.0)

    def test_same_language_duplicate_rows_count_once(self):
        df = pd.DataFrame({
            "movie_name": ["Dup Movie", "Dup Movie"],
            "release_date": ["2021-05-01", "2021-05-01"],
            "language": ["hindi", "hindi"],
            "conflict_balance_score": [np.nan, np.nan],
            "narrative_novelty_score": [np.nan, np.nan],
            "directors": ["Dir A", "Dir A"],
            "revenue": [100.0, 100.0],
        })
        deduped = model.dedupe_movies(df)
        self.assertEqual(deduped.iloc[0]["dubbing_breadth_count"], 1.0)


class ComputeRemakeRightsDetectedRawTest(unittest.TestCase):
    def test_detects_official_remake_phrasing(self):
        df = pd.DataFrame({"overview": [
            "This is an official remake of the 1998 classic.",
            "A love story set in the mountains.",
            None,
        ]})
        out = model.compute_remake_rights_detected_raw(df)
        self.assertEqual(out.iloc[0], 1.0)
        self.assertEqual(out.iloc[1], 0.0)
        self.assertTrue(pd.isna(out.iloc[2]))

    def test_missing_overview_column_is_all_null(self):
        df = pd.DataFrame({"movie_name": ["X", "Y"]})
        out = model.compute_remake_rights_detected_raw(df)
        self.assertTrue(out.isna().all())


class BackfillLegalNewsEventsTest(unittest.TestCase):
    """backfill_legal_news_events.py's fetch/write orchestration, with
    fetch_fn injected (no live GDELT call) and upsert_factor_value
    monkeypatched (no live DB)."""

    def test_dry_run_queries_but_does_not_write(self):
        import backfill_legal_news_events as blne

        written = []
        orig_upsert = blne.upsert_factor_value
        blne.upsert_factor_value = lambda conn, **kw: written.append(kw)
        try:
            movies = [{"movie_name": "Test Movie", "release_date": "2022-06-01", "language": "hindi"}]
            counts = blne.backfill(
                conn=object(), min_year=2020, dry_run=True, request_delay=0,
                fetch_fn=lambda url: {"n_articles": 1, "article_titles": ["x"]},
                movies=movies,
            )
        finally:
            blne.upsert_factor_value = orig_upsert

        self.assertEqual(written, [])
        for factor_key in blne.LEGAL_EVENT_KEYWORDS:
            self.assertEqual(counts[factor_key]["queried"], 1)
            self.assertEqual(counts[factor_key]["hits"], 1)
            self.assertEqual(counts[factor_key]["written"], 0)

    def test_writes_zero_flag_explicitly_on_no_hit(self):
        import backfill_legal_news_events as blne

        written = []
        orig_upsert = blne.upsert_factor_value
        blne.upsert_factor_value = lambda conn, **kw: written.append(kw)
        try:
            movies = [{"movie_name": "Quiet Movie", "release_date": "2022-06-01", "language": "hindi"}]
            counts = blne.backfill(
                conn=object(), min_year=2020, dry_run=False, request_delay=0,
                fetch_fn=lambda url: {"n_articles": 0, "article_titles": []},
                movies=movies,
            )
        finally:
            blne.upsert_factor_value = orig_upsert

        self.assertEqual(len(written), len(blne.LEGAL_EVENT_KEYWORDS))
        self.assertTrue(all(w["value_numeric"] == 0.0 for w in written))
        for factor_key in blne.LEGAL_EVENT_KEYWORDS:
            self.assertEqual(counts[factor_key]["hits"], 0)
            self.assertEqual(counts[factor_key]["written"], 1)

    def test_unparseable_release_date_is_skipped(self):
        import backfill_legal_news_events as blne

        movies = [{"movie_name": "No Date", "release_date": "not-a-date", "language": "hindi"}]
        counts = blne.backfill(
            conn=object(), min_year=2020, dry_run=True, request_delay=0,
            fetch_fn=lambda url: {"n_articles": 0, "article_titles": []},
            movies=movies,
        )
        for factor_key in blne.LEGAL_EVENT_KEYWORDS:
            self.assertEqual(counts[factor_key]["queried"], 0)

    def test_fetch_failure_does_not_abort_the_run(self):
        import backfill_legal_news_events as blne

        def flaky_fetch(url):
            raise RuntimeError("GDELT is down")

        movies = [{"movie_name": "Test Movie", "release_date": "2022-06-01", "language": "hindi"}]
        counts = blne.backfill(
            conn=object(), min_year=2020, dry_run=True, request_delay=0,
            fetch_fn=flaky_fetch, movies=movies,
        )
        for factor_key in blne.LEGAL_EVENT_KEYWORDS:
            self.assertEqual(counts[factor_key]["hits"], 0)


if __name__ == "__main__":
    unittest.main()
