#!/usr/bin/env python3
"""
register_ticket_price.py -- hand-enter one `ticket_price_index` row
(Feature 6) sourced from PVR Inox's public quarterly investor-relations
presentations or the FICCI-EY "Media & Entertainment" annual report.

No clean scrape-ready per-city average-ticket-price dataset exists (per the
project plan), so this table is deliberately a manually-updated quarterly
reference, not an automated connector -- this script ships zero pre-seeded
rows and fabricates no figures; whoever runs it supplies real numbers read
off one of those two public PDFs, plus the PDF's own URL for auditability.

`--city-tier` must be one of TICKET_PRICE_CITY_TIERS
(scripts/market_index_schema.py) -- `movie_revenue_impact_model.py`'s
`infer_city_tier()` buckets every movie into one of these same three labels,
so a row entered under a different label will never match any movie.

Usage
-----
    python3 register_ticket_price.py \
        --period-start 2024-01-01 --period-end 2024-03-31 \
        --region India --city-tier tier_1 --atp-usd 3.10 \
        --source-url "https://www.pvrinox.in/investors/quarterly-presentation-q4fy24.pdf"

Connection defaults mirror movie_revenue_impact_model.py's.
"""
from __future__ import annotations

import argparse
import os
import sys

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from market_index_schema import TICKET_PRICE_CITY_TIERS, upsert_ticket_price_row  # noqa: E402


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))

    p.add_argument("--period-start", required=True, help="YYYY-MM-DD, start of the quarter/period this ATP covers")
    p.add_argument("--period-end", required=True, help="YYYY-MM-DD, end of the quarter/period this ATP covers")
    p.add_argument("--region", default="India", help="Free text, e.g. 'India' or a specific state/circuit")
    p.add_argument("--city-tier", required=True, choices=TICKET_PRICE_CITY_TIERS)
    p.add_argument("--atp-usd", type=float, required=True, dest="atp_usd",
                    help="Average ticket price in USD for this period/region/tier")
    p.add_argument("--source-url", required=True, dest="source_url",
                    help="URL of the PVR Inox IR deck or FICCI-EY report page this figure was read from")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        upsert_ticket_price_row(
            conn, period_start=args.period_start, period_end=args.period_end, region=args.region,
            city_tier=args.city_tier, atp_usd=args.atp_usd, source_url=args.source_url,
        )
    finally:
        conn.close()
    print(f"Registered ticket_price_index row: {args.period_start}..{args.period_end} "
          f"{args.region}/{args.city_tier} = ${args.atp_usd} (source: {args.source_url})")


if __name__ == "__main__":
    main()
