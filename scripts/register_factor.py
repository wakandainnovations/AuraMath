#!/usr/bin/env python3
"""
register_factor.py -- add or update one `factor_definitions` row (Feature 2)
without touching any code. This is the direct answer to "let me add more
parameters in the future": insert a row describing the new factor here, and
the next run of movie_revenue_impact_model.py picks it up automatically
(as a reported `candidate`, or as a trained `active` feature once its
coverage clears --min-feature-coverage).

Usage
-----
    # A factor sourced straight from an existing column:
    python3 register_factor.py --key ticket_price_atp --name "Average Ticket Price" \
        --category Financial --direction Positive --stated-min 0.15 --stated-max 0.25 \
        --data-type numeric --status candidate \
        --source-table ticket_price_index --source-column atp_usd \
        --computation-type raw_column

    # A factor computed by a Python function already registered in
    # movie_revenue_impact_model.py's DERIVED_FACTOR_FNS:
    python3 register_factor.py --key lead_prior_films_count --name "Lead Prior Films Count" \
        --category Cast --direction Positive --stated-min 0.30 --stated-max 0.50 \
        --computation-type derived_python_fn --derivation-ref lead_prior_films_count \
        --status candidate

    # A factor with no computation path yet -- values will be hand-entered into
    # movie_factor_values (via this script's sibling bulk-upsert path or the
    # Java POST /api/admin/factor-values endpoint) before it's promoted:
    python3 register_factor.py --key ticket_price_index_manual --name "Hand-Curated ATP" \
        --category Financial --direction Positive --stated-min 0.1 --stated-max 0.2 \
        --computation-type eav --status candidate

    # Promote a candidate to active once its coverage/correlation look good:
    python3 register_factor.py --key ticket_price_atp --status active --promote-only

Connection defaults mirror movie_revenue_impact_model.py's.
"""
from __future__ import annotations

import argparse
import getpass
import os
import sys

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from registry.schema import (  # noqa: E402
    COMPUTATION_TYPE_VALUES, DATA_TYPE_VALUES, DIRECTION_VALUES, STATUS_VALUES,
    fetch_factor_definitions, register_factor, set_factor_status,
)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))

    p.add_argument("--key", required=True, dest="factor_key", help="factor_key (primary key, e.g. ticket_price_atp)")
    p.add_argument("--promote-only", action="store_true",
                    help="Only change --status on an existing factor_key; leaves every other field untouched. "
                         "Use this for the candidate->active / active->deprecated promotion path.")

    p.add_argument("--name", help="Human-readable factor name")
    p.add_argument("--category", help="Narrative | Cast | Production | Marketing | Timing | Legal | Financial")
    p.add_argument("--direction", choices=DIRECTION_VALUES, help="Sign of the factor's effect on revenue")
    p.add_argument("--stated-min", type=float, dest="stated_min", help="Business-stated lower bound of the impact band")
    p.add_argument("--stated-max", type=float, dest="stated_max", help="Business-stated upper bound of the impact band")
    p.add_argument("--data-type", choices=DATA_TYPE_VALUES, default="numeric", dest="data_type")
    p.add_argument("--status", choices=STATUS_VALUES, default="candidate")
    p.add_argument("--source-table", dest="source_table")
    p.add_argument("--source-column", dest="source_column")
    p.add_argument("--computation-type", choices=COMPUTATION_TYPE_VALUES, dest="computation_type",
                    help="raw_column | derived_sql | derived_python_fn | eav")
    p.add_argument("--derivation-ref", dest="derivation_ref",
                    help="Key into DERIVED_FACTOR_FNS (for computation_type=derived_python_fn)")
    p.add_argument("--added-by", dest="added_by", default=None,
                    help="Defaults to the current OS user, for provenance")
    p.add_argument("--notes", default=None)
    return p.parse_args()


def main() -> None:
    args = parse_args()
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        if args.promote_only:
            existing = {row["factor_key"]: row for row in fetch_factor_definitions(conn)}
            if args.factor_key not in existing:
                print(f"No existing factor_definitions row for {args.factor_key!r}; "
                      f"can't --promote-only a factor that hasn't been registered yet.", file=sys.stderr)
                sys.exit(1)
            ok = set_factor_status(conn, args.factor_key, args.status)
            print(f"{args.factor_key}: status -> {args.status}" if ok else f"{args.factor_key}: not found")
            return

        missing = [f for f in ("name", "category", "direction", "stated_min", "stated_max")
                   if getattr(args, f) is None]
        if missing:
            print(f"Missing required field(s) for a new/full registration: {missing} "
                  f"(or pass --promote-only to just change --status on an existing factor).",
                  file=sys.stderr)
            sys.exit(1)

        register_factor(
            conn, factor_key=args.factor_key, name=args.name, category=args.category,
            direction=args.direction, stated_min=args.stated_min, stated_max=args.stated_max,
            data_type=args.data_type, status=args.status, source_table=args.source_table,
            source_column=args.source_column, computation_type=args.computation_type,
            derivation_ref=args.derivation_ref, added_by=args.added_by or getpass.getuser(),
            notes=args.notes,
        )
        print(f"Registered factor_definitions row for {args.factor_key!r} (status={args.status}).")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
