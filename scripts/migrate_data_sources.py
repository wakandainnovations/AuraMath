#!/usr/bin/env python3
"""
migrate_data_sources.py -- one-time backfill of `data_sources` (Feature 1) from
the existing `sacnilk_url`/`kulfiy_url`/`fandango_url` columns on
`actors_data_collection`.

Safe to re-run: `data_sources` has a UNIQUE(entity_type, entity_key,
source_name, url) constraint and this uses `ON CONFLICT DO NOTHING`, so running
it twice inserts zero rows the second time. Keeps the three legacy URL columns
in place for backward compatibility -- this only *reads* them into the new
registry, it drops nothing.

Requirements
------------
    pip install psycopg2-binary

Usage
-----
    python3 migrate_data_sources.py \
        --db-host localhost --db-port 5432 --db-name aura --db-user mukundv

Connection defaults mirror movie_revenue_impact_model.py's.
"""
from __future__ import annotations

import argparse
import os
import sys

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.schema import ensure_data_sources_schema  # noqa: E402

# column on actors_data_collection -> data_sources.source_name
LEGACY_URL_COLUMNS = {
    "sacnilk_url": "sacnilk",
    "kulfiy_url": "kulfiy",
    "fandango_url": "fandango",
}


def backfill(conn) -> dict[str, int]:
    ensure_data_sources_schema(conn)
    inserted_by_source: dict[str, int] = {}
    with conn.cursor() as cur:
        for url_column, source_name in LEGACY_URL_COLUMNS.items():
            cur.execute(
                f"""
                INSERT INTO data_sources (entity_type, entity_key, source_name, url, connector_type)
                SELECT DISTINCT 'actor', actor_name, %s, {url_column}, 'html_scrape'
                FROM actors_data_collection
                WHERE {url_column} IS NOT NULL AND btrim({url_column}) <> ''
                ON CONFLICT (entity_type, entity_key, source_name, url) DO NOTHING
                """,
                (source_name,),
            )
            inserted_by_source[source_name] = cur.rowcount
    conn.commit()
    return inserted_by_source


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    return p.parse_args()


def main() -> None:
    args = parse_args()
    print(f"Connecting to postgresql://{args.db_user}@{args.db_host}:{args.db_port}/{args.db_name} ...")
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        inserted_by_source = backfill(conn)
    finally:
        conn.close()

    print("Backfilled data_sources from legacy URL columns (new rows inserted this run):")
    for source_name, n in inserted_by_source.items():
        print(f"  {source_name}: {n}")

    print(
        "\nNote: kulfiy_url's actual purpose (kulfiy.com) is not documented anywhere in this "
        "repo. Its rows are backfilled with connector_type='html_scrape' and an empty "
        "field_mapping so the registry reflects the URLs that already existed -- confirm what "
        "Kulfiy actually is with whoever populated these URLs before writing CSS-selector "
        "field_mapping config for it (see scripts/connectors/README.md)."
    )


if __name__ == "__main__":
    main()
