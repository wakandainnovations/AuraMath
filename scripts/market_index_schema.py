"""Schema + read/write helpers for Feature 6's two macro-factor reference
tables:

- `market_index_daily`: BSE Sensex (`^BSESN`) / NSE Nifty (`^NSEI`) daily
  closes, populated by `scripts/backfill_market_index.py` via `yfinance`.
- `ticket_price_index`: a small, manually-curated average-ticket-price
  table sourced from PVR Inox's public quarterly investor-relations decks
  and the FICCI-EY "Media & Entertainment" annual report (per the project
  plan), populated one row at a time via `scripts/register_ticket_price.py`
  as real figures are pulled from those PDFs -- not auto-fetched, and this
  module does not fabricate any rows.

Both are plain reference tables, not per-movie columns and not part of
Feature 2's `factor_definitions`/`movie_factor_values` registry --
`movie_revenue_impact_model.py` joins each movie's `release_date` against
these at runtime (nearest-prior-trading-day for the index, period-covering
row for ticket prices) to derive the `sensex_sentiment`/`ticket_price_level`
factor values, rather than persisting a value per `movie_key`. See
`DERIVED_FACTOR_FNS['sensex_sentiment']`/`['ticket_price_level']` there.

Follows the same `CREATE TABLE IF NOT EXISTS` convention used elsewhere in
this repo's Python-side schema modules (`connectors/schema.py`,
`registry/schema.py`), safe to call on every run.
"""
from __future__ import annotations

from typing import Optional

import pandas as pd
import psycopg2.extras

# `index_name` values `backfill_market_index.py` writes into
# `market_index_daily` -- shared here so it and
# `movie_revenue_impact_model.py`'s `SENSEX_SERIES.get(...)` lookup can't
# drift out of sync on the string literal.
SENSEX_INDEX_NAME = "sensex"
NIFTY_INDEX_NAME = "nifty"

# Canonical `city_tier` vocabulary -- both `infer_city_tier()` in
# movie_revenue_impact_model.py (which buckets a movie's language/country
# into one of these) and `register_ticket_price.py` (which a human uses to
# hand-enter a real row) must agree on these labels, or the join in
# `compute_ticket_price_atp_raw` silently matches nothing. Coarse by design
# -- see `infer_city_tier`'s own docstring, same documented-approximation
# category as `FESTIVE_WINDOWS`.
TICKET_PRICE_CITY_TIERS = ("tier_1", "tier_2_3", "national_average")

_CREATE_MARKET_INDEX_DAILY_SQL = """
    CREATE TABLE IF NOT EXISTS market_index_daily (
        index_name text NOT NULL,
        trade_date date NOT NULL,
        close numeric NOT NULL,
        PRIMARY KEY (index_name, trade_date)
    )
"""

_CREATE_TICKET_PRICE_INDEX_SQL = """
    CREATE TABLE IF NOT EXISTS ticket_price_index (
        id serial PRIMARY KEY,
        period_start date NOT NULL,
        period_end date NOT NULL,
        region text NOT NULL,
        city_tier text NOT NULL,
        atp_usd numeric NOT NULL,
        source_url text,
        UNIQUE (period_start, period_end, region, city_tier)
    )
"""


def ensure_market_data_schema(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(_CREATE_MARKET_INDEX_DAILY_SQL)
        cur.execute(_CREATE_TICKET_PRICE_INDEX_SQL)
    conn.commit()


def upsert_market_index_daily(conn, index_name: str, rows: list[tuple]) -> int:
    """`rows` is a list of (trade_date, close) pairs (e.g. from a `yfinance`
    history dataframe). Upserts on (index_name, trade_date) -- a re-run with
    an overlapping date range just re-writes the same close, harmless."""
    if not rows:
        return 0
    ensure_market_data_schema(conn)
    with conn.cursor() as cur:
        psycopg2.extras.execute_values(
            cur,
            "INSERT INTO market_index_daily (index_name, trade_date, close) VALUES %s "
            "ON CONFLICT (index_name, trade_date) DO UPDATE SET close = EXCLUDED.close",
            [(index_name, trade_date, close) for trade_date, close in rows],
        )
    conn.commit()
    return len(rows)


def fetch_market_index_series(conn) -> dict[str, pd.Series]:
    """{index_name: pd.Series of close, indexed by trade_date (Timestamp),
    sorted ascending} -- one entry per distinct index_name currently in
    `market_index_daily` (empty dict if the table has no rows yet, e.g.
    before `backfill_market_index.py` has ever been run -- callers must
    treat that as "no sensex signal available", not an error)."""
    ensure_market_data_schema(conn)
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(
            "SELECT index_name, trade_date, close FROM market_index_daily "
            "ORDER BY index_name, trade_date"
        )
        rows = cur.fetchall()

    by_index: dict[str, list[tuple]] = {}
    for row in rows:
        by_index.setdefault(row["index_name"], []).append(
            (pd.Timestamp(row["trade_date"]), float(row["close"])))

    series: dict[str, pd.Series] = {}
    for index_name, pairs in by_index.items():
        dates, closes = zip(*pairs)
        series[index_name] = pd.Series(closes, index=pd.DatetimeIndex(dates))
    return series


def nearest_prior_close(series: pd.Series, target_date) -> Optional[float]:
    """Close on `target_date` if it was a trading day, else the closest
    *prior* trading day's close (a Sensex/Nifty series has no entry on
    weekends/market holidays, so an exact-date lookup would miss most
    release dates). Returns None if `target_date` is before every date in
    `series` (nothing to look back to) or `series` is empty."""
    if series is None or series.empty or target_date is None or pd.isna(target_date):
        return None
    pos = series.index.searchsorted(pd.Timestamp(target_date), side="right") - 1
    if pos < 0:
        return None
    return float(series.iloc[pos])


def upsert_ticket_price_row(conn, *, period_start: str, period_end: str, region: str,
                             city_tier: str, atp_usd: float, source_url: Optional[str] = None) -> None:
    ensure_market_data_schema(conn)
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO ticket_price_index (period_start, period_end, region, city_tier, atp_usd, source_url)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (period_start, period_end, region, city_tier) DO UPDATE SET
                atp_usd = EXCLUDED.atp_usd, source_url = EXCLUDED.source_url
            """,
            (period_start, period_end, region, city_tier, atp_usd, source_url),
        )
    conn.commit()


def fetch_ticket_price_index_rows(conn) -> list[dict]:
    """List of `{period_start, period_end, region, city_tier, atp_usd,
    source_url}` dicts, one per hand-curated row (empty list until someone
    runs `register_ticket_price.py` with real PVR Inox/FICCI-EY figures --
    this module ships with zero rows, never a fabricated placeholder)."""
    ensure_market_data_schema(conn)
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(
            "SELECT period_start, period_end, region, city_tier, atp_usd, source_url "
            "FROM ticket_price_index ORDER BY period_start"
        )
        return [dict(row) for row in cur.fetchall()]
