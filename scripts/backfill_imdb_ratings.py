#!/usr/bin/env python3
"""
backfill_imdb_ratings.py -- Feature 3's IMDb non-commercial export backfill.

Fills `imdb_rating` (currently always-NaN on the live table -- see
`WANTED_MOVIE_COLUMNS`'s note in movie_revenue_impact_model.py) from IMDb's
official non-commercial data export (`datasets.imdbws.com`, not Kaggle, free
for personal/research use, updated daily).

Why this is its own script rather than a `data_sources`-driven
`collect_data.py`/`FileDownloadConnector` row: `title.ratings.tsv.gz` alone
has no title text (only `tconst`, `averageRating`, `numVotes`) -- entity
resolution needs a local join against `title.basics.tsv.gz` (`tconst` ->
`primaryTitle`/`startYear`) *first*, and `FileDownloadConnector.fetch()`
only ever downloads one URL per call. This script does that join in pandas,
then hands the joined rows to `connectors.bulk_upsert.resolve_and_upsert_bulk_movie_rows`
(the same fuzzy-match + fill-null-upsert Feature 3 uses for Kaggle sources) --
reusing FileDownloadConnector's own download machinery for each of the two
files rather than duplicating it.

Requirements
------------
    pip install psycopg2-binary requests pandas

Usage
-----
    python3 backfill_imdb_ratings.py [--dry-run] [--min-votes 100]
"""
from __future__ import annotations

import argparse
import os
import sys

import pandas as pd
import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.bulk_upsert import resolve_and_upsert_bulk_movie_rows  # noqa: E402
from connectors.file_download import FileDownloadConnector  # noqa: E402

BASICS_URL = "https://datasets.imdbws.com/title.basics.tsv.gz"
RATINGS_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"


def _load_tsv_gz(url: str, field_mapping: dict) -> pd.DataFrame:
    connector = FileDownloadConnector(field_mapping)
    result = connector.fetch(url)
    return pd.DataFrame(result["rows"])


def build_rows(basics: pd.DataFrame, ratings: pd.DataFrame, min_votes: int) -> list[dict]:
    """Joins basics (tconst -> title/year) with ratings (tconst -> rating) on
    `tconst`, keeping only feature films (`titleType == "movie"`) with at
    least `min_votes` -- IMDb's export includes shorts/TV episodes/etc. that
    would otherwise pollute the fuzzy-match pool with irrelevant titles."""
    movies = basics[basics["title_type"] == "movie"]
    joined = movies.merge(ratings, on="tconst", how="inner")
    joined = joined[joined["num_votes"].fillna(0).astype(float) >= min_votes]
    joined = joined.rename(columns={"primary_title": "movie_name", "start_year": "release_year"})
    return joined[["movie_name", "release_year", "imdb_rating"]].to_dict(orient="records")


def backfill(conn, *, dry_run: bool = False, min_votes: int = 100,
             load_basics=None, load_ratings=None) -> dict:
    basics = (load_basics or (lambda: _load_tsv_gz(
        BASICS_URL,
        {"tconst": "tconst", "titleType": "title_type", "primaryTitle": "primary_title",
         "startYear": "start_year"},
    )))()
    ratings = (load_ratings or (lambda: _load_tsv_gz(
        RATINGS_URL, {"tconst": "tconst", "averageRating": "imdb_rating", "numVotes": "num_votes"},
    )))()

    rows = build_rows(basics, ratings, min_votes)
    return resolve_and_upsert_bulk_movie_rows(conn, rows, dry_run=dry_run)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--min-votes", type=int, default=100,
                    help="Drop IMDb titles with fewer than this many votes before fuzzy-matching "
                         "(cuts obscure/duplicate-title noise; default 100)")
    p.add_argument("--dry-run", action="store_true")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        counts = backfill(conn, dry_run=args.dry_run, min_votes=args.min_votes)
    finally:
        conn.close()

    print(f"IMDb ratings backfill: {counts['attempted']} candidate title(s) "
          f"(>= {args.min_votes} votes), matched {counts['matched']}, unmatched {counts['unmatched']}"
          + ("" if args.dry_run else f", updated {counts['rows_updated']} DB row(s)"))


if __name__ == "__main__":
    main()
