#!/usr/bin/env python3
"""
migrate_factor_definitions.py -- one-time seed of `factor_definitions`
(Feature 2) from the 80-entry catalogue that used to be a hardcoded
`FACTOR_CATALOG` list in `movie_revenue_impact_model.py`, now relocated to
`scripts/registry/seed_catalog.py`.

Safe to re-run: `register_factor()` upserts on `factor_key`, so running this
twice just re-writes the same 80 rows (unless someone else already promoted
one of them out of the seed status via `register_factor.py` or the Java
admin API -- see --no-overwrite-status below).

Verification: this migration changes *how* the 12 currently-measurable
factors are wired up (registry-driven instead of a hardcoded Python list),
not what they compute. Run `compare_models()` before and after via
movie_revenue_impact_model.py and confirm identical model_comparison.json
numbers -- see the plan doc (Feature 2) for why this check matters.

Usage
-----
    python3 migrate_factor_definitions.py \
        --db-host localhost --db-port 5432 --db-name aura --db-user mukundv
"""
from __future__ import annotations

import argparse
import os
import sys

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from registry.schema import ensure_factor_registry_schema, fetch_factor_definitions, register_factor  # noqa: E402
from registry.seed_catalog import seed_rows  # noqa: E402


_WIRING_FIELDS = ("computation_type", "derivation_ref", "source_table", "source_column", "notes")


def migrate(conn, overwrite_status: bool = True) -> dict:
    ensure_factor_registry_schema(conn)
    existing = {row["factor_key"]: row for row in fetch_factor_definitions(conn)}

    inserted, updated, skipped_status, wiring_preserved = 0, 0, 0, 0
    for row in seed_rows():
        row = dict(row)
        row.pop("catalog_id", None)
        was_present = row["factor_key"] in existing
        if was_present and not overwrite_status and existing[row["factor_key"]]["status"] != row["status"]:
            # Someone already promoted/demoted this factor via register_factor.py
            # or the Java admin API -- don't stomp on that with the seed default.
            row["status"] = existing[row["factor_key"]]["status"]
            skipped_status += 1
        # This seed only knows the 12 factors MEASURABLE_WIRING wired up as of
        # Feature 2's original migration -- every other catalog row here still
        # has computation_type=None. A later feature (Feature 7 in particular:
        # unlike Feature 5/6's brand-new adjacent keys, it directly promotes
        # existing catalog slots like joint_production_partnerships/state_bans
        # via register_feature7_factors.py) can wire one of those same
        # factor_keys up for real after this migration has already run once.
        # Re-running this "one-time seed" later must not silently un-wire that
        # -- only overwrite computation_type/derivation_ref/source_table/
        # source_column/notes when the seed itself defines a real computation
        # path (MEASURABLE_WIRING), or the existing row was never wired up to
        # begin with.
        if was_present and row.get("computation_type") is None and existing[row["factor_key"]].get("computation_type"):
            for field in _WIRING_FIELDS:
                row[field] = existing[row["factor_key"]][field]
            wiring_preserved += 1
        register_factor(conn, **row)
        if was_present:
            updated += 1
        else:
            inserted += 1

    return {
        "inserted": inserted, "updated": updated, "status_preserved": skipped_status,
        "wiring_preserved": wiring_preserved,
    }


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--no-overwrite-status", action="store_true",
                    help="Preserve a factor's current status if it already exists and differs "
                         "from the seed default (use on a re-run after factors have been "
                         "promoted/deprecated by hand, to avoid resetting them).")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    print(f"Connecting to postgresql://{args.db_user}@{args.db_host}:{args.db_port}/{args.db_name} ...")
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        result = migrate(conn, overwrite_status=not args.no_overwrite_status)
    finally:
        conn.close()

    print(f"factor_definitions seeded: {result['inserted']} inserted, {result['updated']} updated"
          + (f", {result['status_preserved']} kept their existing (already-changed) status"
             if result["status_preserved"] else "")
          + (f", {result['wiring_preserved']} kept their existing (already-wired-up-since) "
             f"computation_type/derivation_ref/source_table/source_column/notes"
             if result["wiring_preserved"] else "") + ".")


if __name__ == "__main__":
    main()
