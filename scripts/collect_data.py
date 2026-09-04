#!/usr/bin/env python3
"""
collect_data.py -- drives the `data_sources` registry (Feature 1).

For every `data_sources` row matching --source/--entity-type, dispatches to the
connector named by that row's `connector_type` (`html_scrape` -> HtmlScrapeConnector,
`api` -> ApiConnector, `kaggle_csv` -> KaggleDatasetConnector, `file_download` ->
FileDownloadConnector), upserts the mapped fields onto
`movies_data_collection`/`actors_data_collection` (adding any missing target
column via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` first), and writes back
`last_fetched_at`/`last_status`/`raw_payload` on the `data_sources` row itself.

Every write here is fill-null-only (`COALESCE(existing, new)`), never a blind
overwrite -- these sources exist to backfill gaps (Feature 3: 94.3% of rows
have no `budget`, 96.6% no `revenue`), and a differently-sourced number for a
column this table's own pipeline already populated should never silently
replace it.

`kaggle_csv`/`file_download` rows are bulk (`{"rows": [...], "n_rows": N}` --
one dataset/file covers many movies, not one `entity_key`): each row is
fuzzy-matched to `movies_data_collection` by (title, release_year) via
`connectors.bulk_upsert`/`connectors.entity_resolution` (Feature 3) rather
than requiring an exact `entity_key` match.

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
import sys
from datetime import datetime, timezone
from typing import Optional

import psycopg2
import psycopg2.extras

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.api import ApiConnector  # noqa: E402
from connectors.bulk_upsert import (  # noqa: E402
    ensure_target_columns,
    resolve_and_upsert_bulk_movie_rows,
    safe_identifier as _safe_identifier,
)
from connectors.file_download import FileDownloadConnector  # noqa: E402
from connectors.html_scrape import HtmlScrapeConnector  # noqa: E402
from connectors.kaggle_dataset import KaggleDatasetConnector  # noqa: E402
from connectors.schema import (  # noqa: E402
    TARGET_TABLE_BY_ENTITY_TYPE,
    ensure_data_sources_schema,
    parse_movie_entity_key,
)

_BULK_CONNECTOR_TYPES = {"kaggle_csv", "file_download"}


def build_connector(connector_type: str, field_mapping: dict):
    if connector_type == "html_scrape":
        return HtmlScrapeConnector(field_mapping)
    if connector_type == "api":
        return ApiConnector(field_mapping)
    if connector_type == "kaggle_csv":
        return KaggleDatasetConnector(field_mapping)
    if connector_type == "file_download":
        return FileDownloadConnector(field_mapping)
    raise ValueError(f"Unknown connector_type: {connector_type!r}")


def upsert_movie_fields(conn, values: dict, movie_name: str, release_date: str, language: str) -> int:
    """Fill-null upsert (`COALESCE`-guarded) -- never overwrites an
    already-populated column with a differently-sourced value."""
    if not values:
        return 0
    set_clause = ", ".join(
        f"{_safe_identifier(k)} = COALESCE(movies_data_collection.{_safe_identifier(k)}, %s)"
        for k in values
    )
    sql = (f"UPDATE movies_data_collection SET {set_clause} "
           f"WHERE movie_name = %s AND release_date = %s AND language = %s")
    with conn.cursor() as cur:
        cur.execute(sql, [*values.values(), movie_name, release_date, language])
        return cur.rowcount


def upsert_actor_fields(conn, values: dict, actor_name: str) -> int:
    """Fill-null upsert (`COALESCE`-guarded) -- same rationale as
    `upsert_movie_fields`."""
    if not values:
        return 0
    set_clause = ", ".join(
        f"{_safe_identifier(k)} = COALESCE(actors_data_collection.{_safe_identifier(k)}, %s)"
        for k in values
    )
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

    if row["connector_type"] in _BULK_CONNECTOR_TYPES:
        # Bulk result -- see connectors/base.py's docstring. One dataset/file
        # covers many movies, not this row's single `entity_key`, so each row
        # is fuzzy-matched to movies_data_collection by (title, release_year)
        # via connectors.bulk_upsert (Feature 3) instead of an exact-key join.
        # Only entity_type='movie' bulk sources are supported today -- no
        # named Feature 3 dataset is actor-keyed.
        if row["entity_type"] != "movie":
            print(f"  [FAIL] id={row['id']} {row['source_name']}: bulk connector_type "
                  f"{row['connector_type']!r} only supports entity_type='movie'")
            if not dry_run:
                record_fetch_result(conn, row["id"], "error: bulk connector requires entity_type=movie", None)
            return "failed"

        counts = resolve_and_upsert_bulk_movie_rows(conn, result.get("rows", []), dry_run=dry_run)
        mode_note = "DRY-RUN" if dry_run else "OK"
        print(f"  [{mode_note}] id={row['id']} {row['source_name']}: {counts['attempted']} source row(s), "
              f"matched {counts['matched']}, unmatched {counts['unmatched']}"
              + ("" if dry_run else f", updated {counts['rows_updated']} DB row(s)"))
        if not dry_run:
            record_fetch_result(conn, row["id"], "ok", counts)
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
