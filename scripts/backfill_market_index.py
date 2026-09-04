#!/usr/bin/env python3
"""
backfill_market_index.py -- Feature 6's Sensex/Nifty populator for
`market_index_daily`.

Fetches BSE Sensex (`^BSESN`) and NSE Nifty (`^NSEI`) daily closes via
`yfinance` and upserts them into `market_index_daily(index_name, trade_date,
close)` (see `scripts/market_index_schema.py`). `movie_revenue_impact_model.py`
then joins each Indian-market movie's `release_date` against this table
(nearest prior trading day) to derive `sensex_close_at_release` and
`sensex_90d_change_pct` -- the latter feeds the registered `sensex_sentiment`
factor (see `register_feature6_factors.py`).

Why its own script rather than a `data_sources`-driven `collect_data.py` row
(same reasoning `backfill_world_bank_macro.py` documents for GDP/inflation):
`yfinance` returns a whole ticker's *time series* per call, not one value per
URL/entity, which doesn't fit `ApiConnector`'s single-dot-path-per-entity
shape. `scripts/sources.yaml`'s `sensex_nifty` entry is `skip_registration:
true` for exactly this reason -- it's on the record there, but its real
fetch/write logic lives here.

Nifty (`^NSEI`) is pulled too even though only Sensex currently backs a
registered factor -- "add both, they're cheap to try" per the plan, and it's
one extra ticker on the same call shape, so a future `nifty_sentiment` factor
(or a straight Sensex/Nifty cross-check) needs no new backfill script.

Requirements
------------
    pip install psycopg2-binary yfinance pandas

Usage
-----
    python3 backfill_market_index.py [--start 1990-01-01] [--dry-run]
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Callable, Optional

import pandas as pd
import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from market_index_schema import (  # noqa: E402
    NIFTY_INDEX_NAME, SENSEX_INDEX_NAME, upsert_market_index_daily,
)

# yfinance ticker -> the index_name this backfill writes into
# market_index_daily. Both are free, no-key tickers on the same call shape.
TICKERS = {
    "^BSESN": SENSEX_INDEX_NAME,
    "^NSEI": NIFTY_INDEX_NAME,
}

DEFAULT_START = "1990-01-01"


def fetch_index_history(ticker: str, start: str, end: Optional[str] = None) -> list[tuple]:
    """Returns [(trade_date, close), ...] for one ticker via a real
    `yfinance` call; tests inject a fake `fetch_fn` instead so no live
    network call is made in CI."""
    import yfinance as yf  # imported lazily -- only backfill_market_index.py needs this dependency

    history = yf.Ticker(ticker).history(start=start, end=end, auto_adjust=False)
    if history.empty:
        return []
    closes = history["Close"].dropna()
    return [(idx.date(), float(val)) for idx, val in closes.items()]


def backfill(
    conn, *, start: str = DEFAULT_START, end: Optional[str] = None, dry_run: bool = False,
    fetch_fn: Callable[[str, str, Optional[str]], list[tuple]] = fetch_index_history,
) -> dict:
    counts: dict = {}
    for ticker, index_name in TICKERS.items():
        rows = fetch_fn(ticker, start, end)
        counts[index_name] = {"fetched": len(rows), "written": 0}
        if dry_run or not rows:
            continue
        counts[index_name]["written"] = upsert_market_index_daily(conn, index_name, rows)
    return counts


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--start", default=DEFAULT_START,
                    help=f"Earliest trade date to fetch (default {DEFAULT_START} -- covers this "
                         f"corpus's 1918-2028 release-year range about as far back as either index "
                         f"has data; ^BSESN/^NSEI history predates this on yfinance but a movie's "
                         f"nearest-prior-trading-day lookup only needs a date this far back to have "
                         f"*some* prior close, not a complete series).")
    p.add_argument("--end", default=None, help="Latest trade date to fetch (default: today)")
    p.add_argument("--dry-run", action="store_true")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        counts = backfill(conn, start=args.start, end=args.end, dry_run=args.dry_run)
    finally:
        conn.close()

    for index_name, c in counts.items():
        print(f"{index_name}: fetched {c['fetched']} daily close(s)"
              + (f", wrote {c['written']}" if not args.dry_run else " (dry run -- no rows written)"))


if __name__ == "__main__":
    main()
