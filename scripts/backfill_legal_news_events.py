#!/usr/bin/env python3
"""
backfill_legal_news_events.py -- Feature 7's shared legal/controversy
news-event feed: ONE connector (`connectors.news_event_feed.NewsEventFeedConnector`,
GDELT DOC 2.0 API), run once per (movie, factor) pair across seven
factor_definitions rows -- state_bans, pre_release_leak,
title_ownership_disputes, copyright_claims, distribution_disputes,
name_similarity_disputes, plagiarism_allegation_events -- since all seven
are the same shape ("did a searchable news event of type X happen around
this movie"); only the keyword set (`LEGAL_EVENT_KEYWORDS`) and factor_key
differ per factor, not the connector logic.

Writes into `movie_factor_values` (Feature 2's EAV overflow table) rather
than `ALTER TABLE`-ing seven new columns onto `movies_data_collection`:
these are sparse, mostly-zero signals (most movies have zero legal drama --
expect a mostly-zero flag with limited standalone lift, per the plan), and
the EAV table is exactly the "trial an experimental signal before it earns
a real column" case Feature 2's own docstring names. A 0.0 is written
explicitly on a no-hit lookup (not skipped) so the coverage report reflects
"checked, found nothing" rather than "never checked".

Why its own script rather than a `data_sources`/`collect_data.py` row (same
reasoning `backfill_market_index.py`/`backfill_world_bank_macro.py` document
for their own shape mismatches): `collect_data.py`'s generic dispatch is one
URL per (entity, source) row; this needs SEVEN queries per movie (one per
keyword set) against a release-date-anchored search window, which doesn't
fit that per-row shape.

Requirements
------------
    pip install requests psycopg2-binary

Usage
-----
    python3 backfill_legal_news_events.py --min-year 2015 --limit 200 \
        [--window-days 45] [--request-delay 1.0] [--dry-run]

Rate-limited to one request every --request-delay seconds (default 1.0) to
stay well under GDELT's free-tier usage guidance. Meant to run occasionally
against a bounded slice of the corpus (--limit / --min-year), not the full
544k-row table in one pass -- seven GDELT queries per movie adds up fast.
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from datetime import date, datetime, timedelta
from typing import Callable, Optional

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.news_event_feed import (  # noqa: E402
    LEGAL_EVENT_KEYWORDS, NewsEventFeedConnector, build_gdelt_query_url,
)
from connectors.schema import movie_entity_key  # noqa: E402
from registry.schema import upsert_factor_value  # noqa: E402

DEFAULT_WINDOW_DAYS = 45
DEFAULT_MAXRECORDS = 25
DEFAULT_REQUEST_DELAY = 1.0


def parse_release_date(s: Optional[str]) -> Optional[date]:
    if not s:
        return None
    try:
        return datetime.strptime(str(s)[:10], "%Y-%m-%d").date()
    except ValueError:
        return None


def fetch_candidate_movies(conn, min_year: int, limit: Optional[int]) -> list[dict]:
    sql = """
        SELECT movie_name, release_date, language
        FROM movies_data_collection
        WHERE left(release_date, 4) ~ '^[0-9]{4}$'
          AND left(release_date, 4)::int >= %(min_year)s
        ORDER BY release_date DESC
    """
    params: dict = {"min_year": min_year}
    if limit:
        sql += " LIMIT %(limit)s"
        params["limit"] = limit
    with conn.cursor() as cur:
        cur.execute(sql, params)
        cols = [d[0] for d in cur.description]
        return [dict(zip(cols, row)) for row in cur.fetchall()]


def backfill(
    conn, *, min_year: int, limit: Optional[int] = None, window_days: int = DEFAULT_WINDOW_DAYS,
    request_delay: float = DEFAULT_REQUEST_DELAY, dry_run: bool = False,
    fetch_fn: Optional[Callable[[str], dict]] = None,
    movies: Optional[list[dict]] = None,
) -> dict:
    """`fetch_fn`/`movies` are test seams: pass a fake fetch_fn (no live GDELT
    call) and/or a hand-built movie list to exercise the orchestration
    without a live DB or network call -- same pattern backfill_market_index.py
    uses for `fetch_fn`."""
    connector = NewsEventFeedConnector()
    fetch = fetch_fn or connector.fetch

    if movies is None:
        movies = fetch_candidate_movies(conn, min_year, limit)

    counts = {key: {"queried": 0, "hits": 0, "written": 0} for key in LEGAL_EVENT_KEYWORDS}

    for movie in movies:
        release = parse_release_date(movie.get("release_date"))
        if release is None:
            continue
        start = release - timedelta(days=window_days)
        end = release + timedelta(days=window_days)
        entity_key = movie_entity_key(movie["movie_name"], movie["release_date"], movie["language"])

        for factor_key, keywords in LEGAL_EVENT_KEYWORDS.items():
            url = build_gdelt_query_url(movie["movie_name"], keywords, start, end, maxrecords=DEFAULT_MAXRECORDS)
            counts[factor_key]["queried"] += 1
            try:
                result = fetch(url)
            except Exception as exc:  # noqa: BLE001 -- a flaky news API shouldn't abort the whole run
                print(f"  [WARN] {factor_key} lookup failed for {movie['movie_name']!r}: {exc}")
                continue

            flag = 1.0 if int(result.get("n_articles", 0)) > 0 else 0.0
            if flag:
                counts[factor_key]["hits"] += 1
            if not dry_run:
                upsert_factor_value(conn, movie_key=entity_key, factor_key=factor_key, value_numeric=flag)
                counts[factor_key]["written"] += 1
            if request_delay:
                time.sleep(request_delay)

    return counts


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--min-year", type=int, default=2015,
                    help="Only movies released this year or later (default 2015 -- news coverage of "
                         "legal/controversy events for older titles is thin regardless, and GDELT's "
                         "own archive is strongest post-2015).")
    p.add_argument("--limit", type=int, default=200,
                    help="Max movies to process this run (default 200 -- keeps a run to a bounded "
                         "~1,400 GDELT queries; re-run with a different --min-year slice to cover more).")
    p.add_argument("--window-days", type=int, default=DEFAULT_WINDOW_DAYS,
                    help="Search window is [release_date - N, release_date + N] (default 45).")
    p.add_argument("--request-delay", type=float, default=DEFAULT_REQUEST_DELAY,
                    help="Seconds to sleep between GDELT requests (default 1.0).")
    p.add_argument("--dry-run", action="store_true", help="Query GDELT but write nothing.")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        counts = backfill(
            conn, min_year=args.min_year, limit=args.limit, window_days=args.window_days,
            request_delay=args.request_delay, dry_run=args.dry_run,
        )
    finally:
        conn.close()

    print(f"\n-- legal/controversy news-event feed (--min-year {args.min_year}, "
          f"--window-days {args.window_days}{', DRY RUN' if args.dry_run else ''}) --")
    for factor_key, c in counts.items():
        print(f"  {factor_key}: queried {c['queried']}, hits {c['hits']}, written {c['written']}")


if __name__ == "__main__":
    main()
