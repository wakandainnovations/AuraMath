#!/usr/bin/env python3
"""
collect_data.py -- drives the `data_sources` registry (Feature 1).

For every `data_sources` row matching --source/--entity-type, dispatches to the
connector named by that row's `connector_type` (`html_scrape` -> HtmlScrapeConnector,
`api` -> ApiConnector, `kaggle_csv` -> KaggleDatasetConnector), upserts the
mapped fields onto `movies_data_collection`/`actors_data_collection` (adding
any missing target column via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
first), and writes back `last_fetched_at`/`last_status`/`raw_payload` on the
`data_sources` row itself.

Requirements
------------
    pip install psycopg2-binary requests beautifulsoup4 pandas kaggle

Usage
-----
    python3 collect_data.py --source sacnilk --entity-type actor [--dry-run]
    python3 collect_data.py --source tmdb --entity-type movie

Connection defaults mirror movie_revenue_impact_model.py's.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import datetime, timezone
from typing import Optional

import psycopg2
import psycopg2.extras

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.api import ApiConnector  # noqa: E402
from connectors.html_scrape import HtmlScrapeConnector  # noqa: E402
from connectors.kaggle_dataset import KaggleDatasetConnector  # noqa: E402
from connectors.schema import (  # noqa: E402
    TARGET_TABLE_BY_ENTITY_TYPE,
    ensure_data_sources_schema,
    parse_movie_entity_key,
)

# field_mapping keys land directly in ALTER TABLE / UPDATE SQL below -- only
# allow identifier-shaped names to rule out injection via a maliciously
# configured field_mapping.
_SAFE_IDENTIFIER = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")


def _safe_identifier(name: str) -> str:
    if not _SAFE_IDENTIFIER.match(name):
        raise ValueError(f"Refusing to use non-identifier column name from field_mapping: {name!r}")
    return name


def build_connector(connector_type: str, field_mapping: dict):
    if connector_type == "html_scrape":
        return HtmlScrapeConnector(field_mapping)
    if connector_type == "api":
        return ApiConnector(field_mapping)
    if connector_type == "kaggle_csv":
        return KaggleDatasetConnector(field_mapping)
    raise ValueError(f"Unknown connector_type: {connector_type!r}")


def ensure_target_columns(conn, table: str, columns: list[str]) -> None:
    if not columns:
        return
    with conn.cursor() as cur:
        for col in columns:
            cur.execute(f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS {_safe_identifier(col)} text")
    conn.commit()


def upsert_movie_fields(conn, values: dict, movie_name: str, release_date: str, language: str) -> int:
    if not values:
        return 0
    set_clause = ", ".join(f"{_safe_identifier(k)} = %s" for k in values)
    sql = (f"UPDATE movies_data_collection SET {set_clause} "
           f"WHERE movie_name = %s AND release_date = %s AND language = %s")
    with conn.cursor() as cur:
        cur.execute(sql, [*values.values(), movie_name, release_date, language])
        return cur.rowcount


def upsert_actor_fields(conn, values: dict, actor_name: str) -> int:
    if not values:
        return 0
    set_clause = ", ".join(f"{_safe_identifier(k)} = %s" for k in values)
    sql = f"UPDATE actors_data_collection SET {set_clause} WHERE actor_name = %s"
    with conn.cursor() as cur:
        cur.execute(sql, [*values.values(), actor_name])
        return cur.rowcount


def record_fetch_result(conn, row_id: int, status: str, raw_payload: Optional[dict]) -> None:
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE data_sources SET last_fetched_at = %s, last_status = %s, raw_payload = %s WHERE id = %s",
            (datetime.now(timezone.utc), status,
             psycopg2.extras.Json(raw_payload) if raw_payload is not None else None, row_id),
        )
    conn.commit()


def process_row(conn, row: dict, dry_run: bool) -> str:
    """Returns "succeeded" or "failed"."""
    connector = build_connector(row["connector_type"], row["field_mapping"] or {})
    try:
        result = connector.fetch(row["url"])
    except Exception as exc:
        print(f"  [FAIL] id={row['id']} {row['source_name']} {row['entity_key']}: {exc}")
        if not dry_run:
            record_fetch_result(conn, row["id"], f"error: {exc}", None)
        return "failed"

    if row["connector_type"] == "kaggle_csv":
        # Bulk result -- see connectors/base.py's docstring. Per-row entity
        # resolution against movies_data_collection/actors_data_collection is
        # Feature 3's fuzzy-match job, not run here.
        n_rows = result.get("n_rows", 0)
        print(f"  [OK] id={row['id']} {row['source_name']}: downloaded {n_rows} row(s) "
              f"(entity resolution deferred to Feature 3)")
        if not dry_run:
            record_fetch_result(conn, row["id"], "ok", {"n_rows": n_rows})
        return "succeeded"

    values = {k: v for k, v in result.items() if v is not None}
    if dry_run:
        print(f"  [DRY-RUN] id={row['id']} {row['source_name']} {row['entity_key']}: would write {values}")
        return "succeeded"

    table = TARGET_TABLE_BY_ENTITY_TYPE[row["entity_type"]]
    ensure_target_columns(conn, table, list(values.keys()))
    if row["entity_type"] == "movie":
        movie_name, release_date, language = parse_movie_entity_key(row["entity_key"])
        n_updated = upsert_movie_fields(conn, values, movie_name, release_date, language)
    else:
        n_updated = upsert_actor_fields(conn, values, row["entity_key"])
    conn.commit()
    record_fetch_result(conn, row["id"], "ok", values)
    print(f"  [OK] id={row['id']} {row['source_name']} {row['entity_key']}: updated {n_updated} row(s)")
    return "succeeded"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--source", required=True, help="data_sources.source_name to run (e.g. sacnilk, tmdb)")
    p.add_argument("--entity-type", required=True, choices=["movie", "actor"])
    p.add_argument("--dry-run", action="store_true", help="fetch and print what would be written; write nothing")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        ensure_data_sources_schema(conn)
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(
                "SELECT id, entity_type, entity_key, source_name, url, connector_type, field_mapping "
                "FROM data_sources WHERE source_name = %s AND entity_type = %s",
                (args.source, args.entity_type),
            )
            rows = [dict(r) for r in cur.fetchall()]

        mode_note = " (dry run)" if args.dry_run else ""
        print(f"Found {len(rows)} data_sources row(s) for source={args.source!r} "
              f"entity_type={args.entity_type!r}{mode_note}")

        counts = {"attempted": len(rows), "succeeded": 0, "failed": 0}
        for row in rows:
            counts[process_row(conn, row, args.dry_run)] += 1

        print(f"\n-- {args.source} summary -- attempted={counts['attempted']} "
              f"succeeded={counts['succeeded']} failed={counts['failed']}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
