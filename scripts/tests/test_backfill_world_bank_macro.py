"""Unit tests for backfill_world_bank_macro.py -- a fake psycopg2-shaped
connection stands in for Postgres, and `fetch_fn` is injected so no live
World Bank API call is made."""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backfill_world_bank_macro import backfill, primary_country  # noqa: E402


class PrimaryCountryTest(unittest.TestCase):
    def test_single_country(self):
        self.assertEqual(primary_country("India"), "India")

    def test_comma_separated_takes_first(self):
        self.assertEqual(primary_country("France, Mali, Senegal"), "France")

    def test_none_and_blank(self):
        self.assertIsNone(primary_country(None))
        self.assertIsNone(primary_country("   "))


class FakeReadCursor:
    def __init__(self, rows):
        self._rows = rows

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        pass

    def fetchall(self):
        return self._rows


class FakeWriteCursor:
    def __init__(self, log):
        self._log = log
        self.rowcount = 1

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self._log.append((" ".join(sql.split()), params))


class FakeConn:
    def __init__(self, rows):
        self._rows = rows
        self.write_log = []
        self.commits = 0

    def cursor(self, cursor_factory=None):
        if cursor_factory is not None:
            return FakeReadCursor(self._rows)
        return FakeWriteCursor(self.write_log)

    def commit(self):
        self.commits += 1


ROWS = [
    {"movie_name": "Vivah", "release_date": "2006-10-27", "language": "hindi", "country": "India"},
    {"movie_name": "A Prairie Home Companion", "release_date": "2006-06-01",
     "language": "english", "country": "United States of America"},
    {"movie_name": "Some Old Soviet Film", "release_date": "1975-01-01",
     "language": "russian", "country": "Soviet Union"},
]


class BackfillTest(unittest.TestCase):
    def test_fills_gdp_and_inflation_for_mapped_countries(self):
        conn = FakeConn(ROWS)

        def fake_fetch(iso3, indicator):
            if indicator.startswith("NY.GDP"):
                return {2006: 2.5e12} if iso3 in ("IND", "USA") else {}
            return {2006: 4.2} if iso3 in ("IND", "USA") else {}

        counts = backfill(conn, fetch_fn=fake_fetch)

        self.assertEqual(counts["rows_checked"], 3)
        self.assertEqual(counts["rows_updated"], 2)  # Vivah + Prairie Home Companion
        self.assertIn("Soviet Union", counts["unmapped_countries"])
        self.assertEqual(conn.commits, 2)

    def test_dry_run_writes_nothing(self):
        conn = FakeConn(ROWS[:1])

        def fake_fetch(iso3, indicator):
            return {2006: 2.5e12} if indicator.startswith("NY.GDP") else {2006: 4.2}

        counts = backfill(conn, dry_run=True, fetch_fn=fake_fetch)
        self.assertEqual(counts["rows_updated"], 0)
        self.assertEqual(conn.commits, 0)
        self.assertEqual(conn.write_log, [])

    def test_no_data_for_country_year_is_counted_not_written(self):
        conn = FakeConn(ROWS[:1])
        counts = backfill(conn, fetch_fn=lambda iso3, indicator: {})
        self.assertEqual(counts["no_data_country_years"], 1)
        self.assertEqual(counts["rows_updated"], 0)

    def test_series_cached_per_country_not_refetched(self):
        rows = [
            {"movie_name": "A", "release_date": "2006-01-01", "language": "hindi", "country": "India"},
            {"movie_name": "B", "release_date": "2007-01-01", "language": "hindi", "country": "India"},
        ]
        conn = FakeConn(rows)
        call_count = {"n": 0}

        def fake_fetch(iso3, indicator):
            call_count["n"] += 1
            return {2006: 1e12, 2007: 1.1e12} if indicator.startswith("NY.GDP") else {2006: 4.0, 2007: 4.1}

        backfill(conn, fetch_fn=fake_fetch)
        # one GDP + one inflation fetch for India, reused across both rows
        self.assertEqual(call_count["n"], 2)


if __name__ == "__main__":
    unittest.main()
