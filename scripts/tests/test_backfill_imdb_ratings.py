"""Unit tests for backfill_imdb_ratings.py -- pandas DataFrames stand in for
the two downloaded IMDb TSVs (no live network call), and a fake psycopg2-shaped
connection (reused from test_backfill_world_bank_macro's pattern) stands in
for Postgres."""
from __future__ import annotations

import os
import sys
import unittest

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backfill_imdb_ratings import backfill, build_rows  # noqa: E402


BASICS = pd.DataFrame([
    {"tconst": "tt0111161", "title_type": "movie", "primary_title": "The Shawshank Redemption", "start_year": "1994"},
    {"tconst": "tt0468569", "title_type": "movie", "primary_title": "The Dark Knight", "start_year": "2008"},
    {"tconst": "tt9999998", "title_type": "tvEpisode", "primary_title": "Some TV Episode", "start_year": "2015"},
    {"tconst": "tt9999999", "title_type": "movie", "primary_title": "Obscure Short Film", "start_year": "2015"},
])

RATINGS = pd.DataFrame([
    {"tconst": "tt0111161", "imdb_rating": 9.3, "num_votes": 2900000},
    {"tconst": "tt0468569", "imdb_rating": 9.0, "num_votes": 2800000},
    {"tconst": "tt9999998", "imdb_rating": 7.0, "num_votes": 500000},
    {"tconst": "tt9999999", "imdb_rating": 5.1, "num_votes": 12},
])


class BuildRowsTest(unittest.TestCase):
    def test_joins_on_tconst_and_filters_non_movies(self):
        rows = build_rows(BASICS, RATINGS, min_votes=100)
        titles = {r["movie_name"] for r in rows}
        self.assertIn("The Shawshank Redemption", titles)
        self.assertIn("The Dark Knight", titles)
        # tvEpisode row excluded even though it clears the vote threshold
        self.assertNotIn("Some TV Episode", titles)

    def test_min_votes_filters_obscure_titles(self):
        rows = build_rows(BASICS, RATINGS, min_votes=100)
        titles = {r["movie_name"] for r in rows}
        self.assertNotIn("Obscure Short Film", titles)  # only 12 votes

        rows_low_threshold = build_rows(BASICS, RATINGS, min_votes=1)
        titles_low = {r["movie_name"] for r in rows_low_threshold}
        self.assertIn("Obscure Short Film", titles_low)

    def test_output_shape(self):
        rows = build_rows(BASICS, RATINGS, min_votes=100)
        row = next(r for r in rows if r["movie_name"] == "The Dark Knight")
        self.assertEqual(row["imdb_rating"], 9.0)
        self.assertEqual(row["release_year"], "2008")


class FakeCursor:
    def __init__(self, movies, log):
        self._movies = movies
        self._log = log
        self._result = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self._log.append((" ".join(sql.split()), params))
        normalized = " ".join(sql.split())
        if normalized.startswith("SELECT movie_name, release_date, language"):
            title, year = params["title"], params.get("year")
            for (movie_name, release_date, language) in self._movies:
                if movie_name.lower() == str(title).lower() and (year is None or release_date.startswith(year)):
                    self._result = (movie_name, release_date, language, 1.0)
                    return
            self._result = None
        elif normalized.startswith("UPDATE"):
            self.rowcount = 1
            self._result = None
        else:
            self._result = None

    def fetchone(self):
        return self._result


class FakeConn:
    def __init__(self, movies):
        self.movies = movies
        self.log = []
        self.commits = 0

    def cursor(self, **kwargs):
        return FakeCursor(self.movies, self.log)

    def commit(self):
        self.commits += 1


class BackfillEndToEndTest(unittest.TestCase):
    def test_backfill_matches_and_counts(self):
        movies = [
            ("The Shawshank Redemption", "1994-09-23", "english"),
            ("The Dark Knight", "2008-07-18", "english"),
        ]
        conn = FakeConn(movies)
        counts = backfill(
            conn, load_basics=lambda: BASICS, load_ratings=lambda: RATINGS, min_votes=100,
        )
        self.assertEqual(counts["matched"], 2)
        self.assertEqual(counts["unmatched"], 0)

    def test_dry_run_does_not_write(self):
        movies = [("The Dark Knight", "2008-07-18", "english")]
        conn = FakeConn(movies)
        counts = backfill(
            conn, load_basics=lambda: BASICS, load_ratings=lambda: RATINGS,
            min_votes=100, dry_run=True,
        )
        self.assertEqual(counts["matched"], 1)
        self.assertEqual(conn.commits, 0)


if __name__ == "__main__":
    unittest.main()
