#!/usr/bin/env python3
"""
register_sources.py -- registers Feature 3's bulk external datasets
(`sources.yaml`) into `data_sources` (Feature 1) and `factor_definitions`
(Feature 2), so a new source is a config-file entry, not a one-off hardcoded
column the way today's 12 measurable factors are wired in.

For every `sources.yaml` entry with `skip_registration: false` (or omitted):
  - upserts one `data_sources` row (`connectors.schema.register_source`),
    `field_mapping` built from that entry's `fields[].source_column ->
    target_column`;
  - for every `fields[]` item carrying a `factor:` block, upserts a
    `candidate` `factor_definitions` row (`registry.schema.register_factor`)
    keyed on `target_column` (or `factor.factor_key` if given), so the new
    column shows up in the coverage report immediately, before anyone
    decides whether to promote it to `active`.

`skip_registration: true` entries (manual-only, not-to-be-scraped, purpose
unconfirmed, or needing a local multi-file join collect_data.py's generic
per-row dispatch doesn't do) are printed as a reminder, never inserted --
see each entry's `notes:` in `sources.yaml` for why.

Requirements
------------
    pip install psycopg2-binary pyyaml

Usage
-----
    python3 register_sources.py [--dry-run]
    python3 register_sources.py --sources-file /path/to/sources.yaml

Connection defaults mirror movie_revenue_impact_model.py's.
"""
from __future__ import annotations

import argparse
import getpass
import os
import sys
from typing import Optional

import psycopg2
import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.schema import register_source  # noqa: E402
from registry.schema import register_factor  # noqa: E402

DEFAULT_SOURCES_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sources.yaml")

# Bulk sources (kaggle_csv/file_download) aren't tied to one entity -- there's
# no single (movie_name, release_date, language)/actor_name an entire
# dataset/file maps to. entity_key is NOT NULL on data_sources, so this
# sentinel documents "this row covers many entities" instead of a real key.
BULK_ENTITY_KEY = "__bulk__"


def build_field_mapping(source: dict) -> dict:
    return {
        f["source_column"]: f["target_column"]
        for f in source.get("fields", [])
        if f.get("source_column")
    }


def register_source_row(conn, source: dict, dry_run: bool) -> None:
    field_mapping = build_field_mapping(source)
    entity_type = source.get("entity_type", "movie")
    entity_key = source.get("entity_key", BULK_ENTITY_KEY)
    if dry_run:
        print(f"  [DRY-RUN] data_sources: {source['source_name']} "
              f"(connector_type={source['connector_type']}, field_mapping={field_mapping})")
        return
    register_source(
        conn,
        entity_type=entity_type,
        entity_key=entity_key,
        source_name=source["source_name"],
        url=source["url"],
        connector_type=source["connector_type"],
        field_mapping=field_mapping or None,
    )
    print(f"  [OK] data_sources: {source['source_name']} registered "
          f"(connector_type={source['connector_type']})")


def register_factors_for_source(conn, source: dict, dry_run: bool, added_by: str) -> int:
    n_registered = 0
    for field in source.get("fields", []):
        factor = field.get("factor")
        if not factor:
            continue
        factor_key = factor.get("factor_key", field["target_column"])
        if dry_run:
            print(f"    [DRY-RUN] factor_definitions: {factor_key} (status=candidate, "
                  f"source_column={field['target_column']})")
            n_registered += 1
            continue
        register_factor(
            conn,
            factor_key=factor_key,
            name=factor.get("name", factor_key),
            category=factor["category"],
            direction=factor["direction"],
            stated_min=factor["stated_min"],
            stated_max=factor["stated_max"],
            data_type=factor.get("data_type", "numeric"),
            status="candidate",
            source_table="movies_data_collection",
            source_column=field["target_column"],
            computation_type="raw_column",
            added_by=added_by,
            notes=factor.get("notes"),
        )
        print(f"    [OK] factor_definitions: {factor_key} registered (status=candidate)")
        n_registered += 1
    return n_registered


def load_sources(path: str) -> list[dict]:
    with open(path) as f:
        doc = yaml.safe_load(f)
    return doc.get("sources", [])


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--sources-file", default=DEFAULT_SOURCES_FILE)
    p.add_argument("--dry-run", action="store_true", help="print what would be registered; write nothing")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    sources = load_sources(args.sources_file)

    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    added_by = getpass.getuser()
    try:
        n_sources_registered = 0
        n_factors_registered = 0
        skipped: list[tuple[str, Optional[str]]] = []

        for source in sources:
            has_factors = any(f.get("factor") for f in source.get("fields", []))
            if source.get("skip_registration", False):
                skipped.append((source["source_name"], source.get("notes")))
                # A skipped source can still have real target columns worth
                # tracking as candidate factors (e.g. imdb_rating from
                # imdb_non_commercial) even though its data_sources row isn't
                # inserted -- its actual fetch logic lives in a dedicated
                # backfill_*.py script instead of collect_data.py's generic
                # dispatch, per that source's notes.
                if has_factors:
                    print(f"{source['source_name']} (documentation-only data_sources row, "
                          f"but registering its factor(s)):")
                    n_factors_registered += register_factors_for_source(conn, source, args.dry_run, added_by)
                continue
            print(f"{source['source_name']}:")
            register_source_row(conn, source, args.dry_run)
            n_factors_registered += register_factors_for_source(conn, source, args.dry_run, added_by)
            n_sources_registered += 1

        print(f"\n-- summary -- data_sources registered: {n_sources_registered}, "
              f"factor_definitions registered: {n_factors_registered}, "
              f"skipped (documentation-only): {len(skipped)}")

        if skipped:
            print("\nSkipped (see sources.yaml for the full reason on each):")
            for name, notes in skipped:
                summary = (notes or "").strip().split(". ")[0].strip() if notes else ""
                if len(summary) > 140:
                    summary = summary[:137] + "..."
                print(f"  - {name}: {summary}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
