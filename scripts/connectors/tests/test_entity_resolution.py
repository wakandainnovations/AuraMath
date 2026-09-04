"""Unit tests for connectors.entity_resolution / connectors.bulk_upsert --
a fake psycopg2-shaped connection/cursor stands in for Postgres so these run
with no live database."""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from connectors.bulk_upsert import resolve_and_upsert_bulk_movie_rows  # noqa: E402
from connectors.entity_resolution import coerce_release_year, resolve_movie_entity_key  # noqa: E402


class FakeCursor:
    """Enough of psycopg2's cursor interface for entity_resolution/bulk_upsert:
    tracks every executed statement, and answers `similarity`-ranked movie
    lookups from a small in-memory table instead of a real GiST index."""

    def __init__(self, movies, executed_log, existing_columns):
        self._movies = movies
        self._executed_log = executed_log
        self._existing_columns = existing_columns
        self._result = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    @staticmethod
    def _similarity(a: str, b: str) -> float:
        a, b = a.lower(), b.lower()
        if a == b:
            return 1.0
        a_tokens, b_tokens = set(a.split()), set(b.split())
        if not a_tokens or not b_tokens:
            return 0.0
        overlap = len(a_tokens & b_tokens) / len(a_tokens | b_tokens)
        return overlap

    def execute(self, sql, params=None):
        self._executed_log.append((sql.strip(), params))
        normalized = " ".join(sql.split())

        if normalized.startswith("SELECT movie_name, release_date, language"):
            title = params["title"]
            year = params.get("year")
            candidates = []
            for (movie_name, release_date, language) in self._movies:
                if year is not None and not release_date.startswith(year):
                    continue
                sim = self._similarity(movie_name, title)
                candidates.append((movie_name, release_date, language, sim))
            candidates.sort(key=lambda row: row[3], reverse=True)
            self._result = candidates[0] if candidates else None
            return

        if normalized.startswith("ALTER TABLE"):
            self._result = None
            return

        if normalized.startswith("UPDATE movies_data_collection"):
            # crude but sufficient for these tests: count it as one row affected
            self.rowcount = 1
            self._result = None
            return

        self._result = None

    def fetchone(self):
        return self._result


class FakeConn:
    def __init__(self, movies):
        self.movies = movies
        self.executed_log = []
        self.existing_columns = set()
        self.commits = 0

    def cursor(self, **kwargs):
        return FakeCursor(self.movies, self.executed_log, self.existing_columns)

    def commit(self):
        self.commits += 1


MOVIES = [
    ("The Devil Wears Prada", "2006-06-30", "english"),
    ("Vivah", "2006-10-27", "hindi"),
    ("A Prairie Home Companion", "2006-06-01", "english"),
]


class CoerceReleaseYearTest(unittest.TestCase):
    def test_bare_year_string(self):
        self.assertEqual(coerce_release_year("2010"), 2010)

    def test_full_date_string(self):
        self.assertEqual(coerce_release_year("2010-06-01"), 2010)

    def test_float_year(self):
        self.assertEqual(coerce_release_year(2010.0), 2010)

    def test_none_and_nan(self):
        self.assertIsNone(coerce_release_year(None))
        self.assertIsNone(coerce_release_year(float("nan")))

    def test_garbage_string(self):
        self.assertIsNone(coerce_release_year("unknown"))


class ResolveMovieEntityKeyTest(unittest.TestCase):
    def test_exact_title_and_year_match(self):
        conn = FakeConn(MOVIES)
        resolved = resolve_movie_entity_key(conn, "Vivah", 2006)
        self.assertEqual(resolved, ("Vivah", "2006-10-27", "hindi"))

    def test_fuzzy_title_match(self):
        conn = FakeConn(MOVIES)
        # a punctuation/phrasing variant a source CSV might plausibly carry
        resolved = resolve_movie_entity_key(conn, "Devil Wears Prada The", 2006)
        self.assertEqual(resolved[0], "The Devil Wears Prada")

    def test_no_match_below_threshold(self):
        conn = FakeConn(MOVIES)
        resolved = resolve_movie_entity_key(conn, "Completely Unrelated Film Title", 2006)
        self.assertIsNone(resolved)

    def test_missing_title_returns_none_without_querying(self):
        conn = FakeConn(MOVIES)
        resolved = resolve_movie_entity_key(conn, None, 2006)
        self.assertIsNone(resolved)
        self.assertEqual(conn.executed_log, [])

    def test_wrong_year_excludes_otherwise_good_match(self):
        conn = FakeConn(MOVIES)
        resolved = resolve_movie_entity_key(conn, "Vivah", 1999)
        self.assertIsNone(resolved)


class ResolveAndUpsertBulkMovieRowsTest(unittest.TestCase):
    def test_matched_and_unmatched_rows_counted(self):
        conn = FakeConn(MOVIES)
        rows = [
            {"movie_name": "Vivah", "release_year": 2006, "imdb_rating": 7.4},
            {"movie_name": "Totally Unknown Film", "release_year": 2006, "imdb_rating": 5.0},
        ]
        counts = resolve_and_upsert_bulk_movie_rows(conn, rows)
        self.assertEqual(counts["attempted"], 2)
        self.assertEqual(counts["matched"], 1)
        self.assertEqual(counts["unmatched"], 1)
        self.assertEqual(counts["rows_updated"], 1)

    def test_dry_run_matches_but_writes_nothing(self):
        conn = FakeConn(MOVIES)
        rows = [{"movie_name": "Vivah", "release_year": 2006, "imdb_rating": 7.4}]
        counts = resolve_and_upsert_bulk_movie_rows(conn, rows, dry_run=True)
        self.assertEqual(counts["matched"], 1)
        self.assertEqual(counts["rows_updated"], 0)
        self.assertEqual(conn.commits, 0)
        self.assertFalse(any(sql.startswith("UPDATE") for sql, _ in conn.executed_log))

    def test_empty_rows(self):
        conn = FakeConn(MOVIES)
        counts = resolve_and_upsert_bulk_movie_rows(conn, [])
        self.assertEqual(counts, {"attempted": 0, "matched": 0, "unmatched": 0, "rows_updated": 0})


if __name__ == "__main__":
    unittest.main()
