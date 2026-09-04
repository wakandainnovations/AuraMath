"""Unit tests for Feature 6's macro factors (Sensex 90-day pre-release
momentum, ticket-price level) -- hand-built SENSEX_SERIES/TICKET_PRICE_ROWS
module globals stand in for market_index_daily/ticket_price_index (no live
DB, no live yfinance call). Mirrors test_feature5_cast_crew_track_record.py's
"fake the self-join inputs, assert on the pure compute functions" style.
"""
from __future__ import annotations

import os
import sys
import unittest
from datetime import date

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import movie_revenue_impact_model as model  # noqa: E402
from market_index_schema import SENSEX_INDEX_NAME, nearest_prior_close  # noqa: E402


class NearestPriorCloseTest(unittest.TestCase):
    def setUp(self):
        self.series = pd.Series(
            [100.0, 105.0, 110.0],
            index=pd.to_datetime(["2020-01-01", "2020-01-15", "2020-02-01"]),
        )

    def test_exact_trading_day(self):
        self.assertEqual(nearest_prior_close(self.series, pd.Timestamp("2020-01-15")), 105.0)

    def test_falls_back_to_prior_trading_day(self):
        # 2020-01-16 isn't in the series (weekend/holiday-style gap) -- should
        # fall back to 2020-01-15's close, not the next one.
        self.assertEqual(nearest_prior_close(self.series, pd.Timestamp("2020-01-16")), 105.0)

    def test_before_every_date_returns_none(self):
        self.assertIsNone(nearest_prior_close(self.series, pd.Timestamp("2019-01-01")))

    def test_empty_series_returns_none(self):
        self.assertIsNone(nearest_prior_close(pd.Series(dtype=float), pd.Timestamp("2020-01-01")))

    def test_none_date_returns_none(self):
        self.assertIsNone(nearest_prior_close(self.series, None))


class InferCityTierTest(unittest.TestCase):
    def test_hindi_is_tier_1(self):
        self.assertEqual(model.infer_city_tier("hindi", "India"), "tier_1")

    def test_english_india_is_tier_1(self):
        self.assertEqual(model.infer_city_tier("english", "India"), "tier_1")

    def test_regional_indian_language_is_tier_2_3(self):
        self.assertEqual(model.infer_city_tier("tamil", "India"), "tier_2_3")

    def test_non_indian_market_is_none(self):
        self.assertIsNone(model.infer_city_tier("english", "United States of America"))

    def test_japanese_is_none(self):
        self.assertIsNone(model.infer_city_tier("japanese", "Japan"))


class ComputeSensexFeaturesRawTest(unittest.TestCase):
    def setUp(self):
        self._orig_sensex_series = model.SENSEX_SERIES
        model.SENSEX_SERIES = {
            SENSEX_INDEX_NAME: pd.Series(
                [100.0, 105.0, 110.0, 90.0, 95.0, 120.0],
                index=pd.to_datetime(
                    ["2020-01-01", "2020-01-15", "2020-02-01", "2020-03-01", "2020-03-15", "2020-04-09"]
                ),
            )
        }

    def tearDown(self):
        model.SENSEX_SERIES = self._orig_sensex_series

    def test_india_row_gets_close_and_90d_change(self):
        df = pd.DataFrame({"release_date": ["2020-04-10"]})
        india_mask = pd.Series([True], index=df.index)
        out = model.compute_sensex_features_raw(df, india_mask)
        # Nearest prior trading day to 2020-04-10 is 2020-04-09 (close=120.0).
        self.assertEqual(out.loc[0, "sensex_close_at_release"], 120.0)
        # 90 days before 2020-04-10 is 2020-01-11 -> nearest prior close is
        # 2020-01-01 (100.0). (120 - 100) / 100 * 100 = 20.0.
        self.assertAlmostEqual(out.loc[0, "sensex_90d_change_pct"], 20.0)

    def test_non_india_row_is_null(self):
        df = pd.DataFrame({"release_date": ["2020-04-10"]})
        india_mask = pd.Series([False], index=df.index)
        out = model.compute_sensex_features_raw(df, india_mask)
        self.assertTrue(pd.isna(out.loc[0, "sensex_close_at_release"]))
        self.assertTrue(pd.isna(out.loc[0, "sensex_90d_change_pct"]))

    def test_unparseable_release_date_is_null(self):
        df = pd.DataFrame({"release_date": ["not-a-date"]})
        india_mask = pd.Series([True], index=df.index)
        out = model.compute_sensex_features_raw(df, india_mask)
        self.assertTrue(pd.isna(out.loc[0, "sensex_close_at_release"]))

    def test_empty_sensex_series_is_null_not_an_error(self):
        model.SENSEX_SERIES = {}
        df = pd.DataFrame({"release_date": ["2020-04-10"]})
        india_mask = pd.Series([True], index=df.index)
        out = model.compute_sensex_features_raw(df, india_mask)
        self.assertTrue(pd.isna(out.loc[0, "sensex_close_at_release"]))


class ComputeTicketPriceAtpRawTest(unittest.TestCase):
    def setUp(self):
        self._orig_rows = model.TICKET_PRICE_ROWS
        model.TICKET_PRICE_ROWS = [
            {"period_start": date(2020, 1, 1), "period_end": date(2020, 6, 30),
             "region": "India", "city_tier": "tier_1", "atp_usd": 3.20, "source_url": "u1"},
            {"period_start": date(2020, 1, 1), "period_end": date(2020, 6, 30),
             "region": "India", "city_tier": "tier_2_3", "atp_usd": 1.80, "source_url": "u2"},
        ]

    def tearDown(self):
        model.TICKET_PRICE_ROWS = self._orig_rows

    def test_matches_covering_period_and_tier(self):
        df = pd.DataFrame({
            "language": ["hindi", "tamil"],
            "country": ["India", "India"],
            "release_date": ["2020-03-01", "2020-03-01"],
        })
        out = model.compute_ticket_price_atp_raw(df)
        self.assertEqual(out.loc[0], 3.20)
        self.assertEqual(out.loc[1], 1.80)

    def test_non_india_row_is_null(self):
        df = pd.DataFrame({
            "language": ["english"], "country": ["United States of America"],
            "release_date": ["2020-03-01"],
        })
        out = model.compute_ticket_price_atp_raw(df)
        self.assertTrue(pd.isna(out.loc[0]))

    def test_date_outside_every_period_is_null(self):
        df = pd.DataFrame({
            "language": ["hindi"], "country": ["India"], "release_date": ["2021-01-01"],
        })
        out = model.compute_ticket_price_atp_raw(df)
        self.assertTrue(pd.isna(out.loc[0]))

    def test_no_rows_seeded_yet_is_all_null(self):
        model.TICKET_PRICE_ROWS = []
        df = pd.DataFrame({
            "language": ["hindi"], "country": ["India"], "release_date": ["2020-03-01"],
        })
        out = model.compute_ticket_price_atp_raw(df)
        self.assertTrue(pd.isna(out.loc[0]))


class BackfillMarketIndexTest(unittest.TestCase):
    """backfill_market_index.py's fetch/write orchestration, with fetch_fn
    injected (no live yfinance call) and its upsert reference monkeypatched
    (no live DB)."""

    def test_dry_run_fetches_but_does_not_write(self):
        import backfill_market_index as bmi

        written = []
        orig_upsert = bmi.upsert_market_index_daily
        bmi.upsert_market_index_daily = lambda conn, index_name, rows: written.append((index_name, len(rows))) or len(rows)
        try:
            def fake_fetch(ticker, start, end):
                return [(date(2020, 1, 1), 100.0), (date(2020, 1, 2), 101.0)]

            counts = bmi.backfill(conn=object(), dry_run=True, fetch_fn=fake_fetch)
        finally:
            bmi.upsert_market_index_daily = orig_upsert

        self.assertEqual(written, [])
        self.assertEqual(counts["sensex"]["fetched"], 2)
        self.assertEqual(counts["sensex"]["written"], 0)
        self.assertEqual(counts["nifty"]["fetched"], 2)

    def test_writes_when_not_dry_run(self):
        import backfill_market_index as bmi

        written = []
        orig_upsert = bmi.upsert_market_index_daily
        bmi.upsert_market_index_daily = lambda conn, index_name, rows: written.append((index_name, len(rows))) or len(rows)
        try:
            def fake_fetch(ticker, start, end):
                return [(date(2020, 1, 1), 100.0)]

            counts = bmi.backfill(conn=object(), dry_run=False, fetch_fn=fake_fetch)
        finally:
            bmi.upsert_market_index_daily = orig_upsert

        self.assertEqual(sorted(written), [("nifty", 1), ("sensex", 1)])
        self.assertEqual(counts["sensex"]["written"], 1)

    def test_no_rows_fetched_skips_write(self):
        import backfill_market_index as bmi

        written = []
        orig_upsert = bmi.upsert_market_index_daily
        bmi.upsert_market_index_daily = lambda conn, index_name, rows: written.append(index_name)
        try:
            counts = bmi.backfill(conn=object(), dry_run=False, fetch_fn=lambda ticker, start, end: [])
        finally:
            bmi.upsert_market_index_daily = orig_upsert

        self.assertEqual(written, [])
        self.assertEqual(counts["sensex"]["fetched"], 0)


if __name__ == "__main__":
    unittest.main()
