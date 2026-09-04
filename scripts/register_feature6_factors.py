#!/usr/bin/env python3
"""
register_feature6_factors.py -- one-time registration of Feature 6's two
new `factor_definitions` rows (`movie_revenue_impact_model.py`'s
`DERIVED_FACTOR_FNS['sensex_sentiment']`/`['ticket_price_level']`), via
`scripts/register_factor.py`'s own `register_factor()` upsert -- the
registry doing its job, per Feature 2.

Both are genuinely new factors, not a promotion of an existing catalogue
slot -- neither Sensex-level market sentiment nor a ticket-price level has
a home anywhere in the original 80-factor business catalogue (the closest
entries are marketing/timing factors, none of which cover broad
market-sentiment or ticket-price level). **Flag this to whoever owns the
business catalogue**: these two rows sit outside the original 90 stated-min/
max slots, so their bands below are a placeholder pending real business
input, not a supplied range -- same caveat `stated_min`/`stated_max` carry
on Feature 5's new rows.

Seeded `status='candidate'`: promote to `active` (via `register_factor.py
--key <key> --status active --promote-only`) once a live run's factor
coverage report shows reasonable non-null coverage -- `sensex_sentiment`
and `ticket_price_level` are both India-market-only (see
`infer_city_tier`/`INDIA_ONLY_CALENDAR_FACTOR_KEYS`-style gating in
`movie_revenue_impact_model.py`), so global-pooled coverage will start low
until `--market india` runs or `market_index_daily`/`ticket_price_index`
are populated (`backfill_market_index.py` / `register_ticket_price.py`).

Safe to re-run: register_factor() upserts on factor_key.

Usage
-----
    python3 register_feature6_factors.py \
        --db-host localhost --db-port 5432 --db-name aura --db-user mukundv
"""
from __future__ import annotations

import argparse
import getpass
import os
import sys

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from registry.schema import register_factor  # noqa: E402

# (factor_key, name, stated_min, stated_max, notes)
# All: category='Financial', direction='Positive', data_type='numeric',
# computation_type='derived_python_fn', derivation_ref=factor_key (matches
# the DERIVED_FACTOR_FNS key movie_revenue_impact_model.py registers each
# under), status='candidate'.
FEATURE6_FACTORS: list[tuple[str, str, float, float, str]] = [
    (
        "sensex_sentiment", "BSE Sensex 90-Day Pre-Release Momentum", 0.05, 0.15,
        "Percent change in the BSE Sensex (^BSESN, via market_index_daily / "
        "backfill_market_index.py) over the 90 days before release, joined "
        "on release_date via the nearest prior trading day -- a proxy for "
        "consumer discretionary sentiment at launch. Only populated for "
        "Indian-market rows (Feature 0's market flag); null elsewhere "
        "rather than joining Sensex to a non-Indian release. New factor, "
        "no slot in the original 80-factor catalogue -- flag to the "
        "catalogue owner as an addition outside the original 90 stated "
        "ranges; stated_min/max here are a placeholder pending real "
        "business input.",
    ),
    (
        "ticket_price_level", "Average Ticket Price Level", 0.05, 0.15,
        "Average ticket price (USD) for the movie's release period/city-tier "
        "bucket, from the hand-curated ticket_price_index table (PVR Inox "
        "quarterly investor-relations decks + the FICCI-EY 'Media & "
        "Entertainment' annual report -- see register_ticket_price.py; ships "
        "with zero pre-seeded rows, so coverage is null until someone enters "
        "real figures). city_tier is inferred coarsely from language/country "
        "(infer_city_tier(), a documented approximation, same category as "
        "FESTIVE_WINDOWS). Only populated for Indian-market rows. New "
        "factor, no slot in the original 80-factor catalogue -- same "
        "placeholder-band caveat as sensex_sentiment above.",
    ),
]


def register_feature6_factors(conn, added_by: str) -> int:
    for factor_key, name, stated_min, stated_max, notes in FEATURE6_FACTORS:
        register_factor(
            conn, factor_key=factor_key, name=name, category="Financial", direction="Positive",
            stated_min=stated_min, stated_max=stated_max, data_type="numeric", status="candidate",
            computation_type="derived_python_fn", derivation_ref=factor_key,
            added_by=added_by, notes=notes,
        )
    return len(FEATURE6_FACTORS)


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
        n = register_feature6_factors(conn, added_by=getpass.getuser())
    finally:
        conn.close()
    print(f"Registered/updated {n} Feature 6 factor_definitions rows "
          f"(status=candidate): {[f[0] for f in FEATURE6_FACTORS]}")


if __name__ == "__main__":
    main()
