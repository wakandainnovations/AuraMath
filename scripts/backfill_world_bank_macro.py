#!/usr/bin/env python3
"""
backfill_world_bank_macro.py -- Feature 3's World Bank Open Data backfill.

`gdp_usd_billions`/`inflation_rate_pct` are already filled on 90.2%/89.7% of
`movies_data_collection` rows -- this closes the remaining ~10% gap, keyed on
(country, release_year), not a fuzzy movie-title match (World Bank's API has
no notion of a movie).

Why this is its own script rather than a `data_sources`-driven
`collect_data.py` row: the World Bank API returns one country's *entire GDP
time series* per call (many years at once), not one value per URL/entity --
that doesn't fit `ApiConnector`'s single dot-path-per-entity shape. This
script fetches each distinct country's series once (cached in-process), then
fill-null-updates every matching row directly, reusing
`connectors.bulk_upsert.safe_identifier`/`ensure_target_columns` for the
write itself.

`movies_data_collection.country` is a free-text, sometimes comma-separated
co-production list (e.g. "France, Mali, Senegal") -- this uses the *first*
listed country as the release's primary market, the same kind of documented
approximation `FESTIVE_WINDOWS`/city-tier inference already make elsewhere in
this plan. Countries with no entry in `COUNTRY_NAME_TO_ISO3` (spot-check
coverage, not exhaustive -- extend as needed) or no World Bank data for a
given year (a defunct country like "Soviet Union", or a too-early year) are
skipped and counted, not guessed at.

Requirements
------------
    pip install psycopg2-binary requests

Usage
-----
    python3 backfill_world_bank_macro.py [--dry-run]
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Callable, Optional

import psycopg2
import psycopg2.extras
import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connectors.bulk_upsert import safe_identifier  # noqa: E402

GDP_INDICATOR = "NY.GDP.MKTP.CD"          # GDP, current US$
INFLATION_INDICATOR = "FP.CPI.TOTL.ZG"    # Inflation, consumer prices (annual %)

WORLD_BANK_API_BASE = "https://api.worldbank.org/v2/country"

# Spot-check coverage of countries actually appearing in movies_data_collection
# (not exhaustive) -- extend this as new countries show up missing from a
# backfill run's "unmapped country" summary.
COUNTRY_NAME_TO_ISO3 = {
    "United States of America": "USA", "United States": "USA",
    "United Kingdom": "GBR", "India": "IND", "France": "FRA", "Germany": "DEU",
    "Japan": "JPN", "China": "CHN", "South Korea": "KOR", "Spain": "ESP",
    "Italy": "ITA", "Canada": "CAN", "Australia": "AUS", "Brazil": "BRA",
    "Mexico": "MEX", "Russia": "RUS", "Netherlands": "NLD", "Sweden": "SWE",
    "Denmark": "DNK", "Norway": "NOR", "Belgium": "BEL", "Austria": "AUT",
    "Switzerland": "CHE", "Poland": "POL", "Czech Republic": "CZE",
    "Hungary": "HUN", "Portugal": "PRT", "Ireland": "IRL", "Greece": "GRC",
    "Turkey": "TUR", "Israel": "ISR", "South Africa": "ZAF", "Egypt": "EGY",
    "Nigeria": "NGA", "Argentina": "ARG", "Chile": "CHL", "Colombia": "COL",
    "Thailand": "THA", "Indonesia": "IDN", "Malaysia": "MYS", "Philippines": "PHL",
    "Vietnam": "VNM", "Hong Kong": "HKG", "Taiwan": "TWN", "New Zealand": "NZL",
    "Finland": "FIN", "Iceland": "ISL", "Romania": "ROU", "Ukraine": "UKR",
}


def primary_country(country_field: Optional[str]) -> Optional[str]:
    """First entry of a possibly comma-separated co-production list, e.g.
    "France, Mali, Senegal" -> "France". Documented approximation -- see
    module docstring."""
    if not country_field or not country_field.strip():
        return None
    return country_field.split(",")[0].strip()


def fetch_worldbank_series(iso3: str, indicator: str, timeout: float = 30.0) -> dict[int, float]:
    """Returns {year: value} for one country/indicator. Real HTTP GET against
    the live World Bank API; tests inject a fake fetch_fn instead."""
    url = f"{WORLD_BANK_API_BASE}/{iso3}/indicator/{indicator}"
    response = requests.get(url, params={"format": "json", "per_page": 20000}, timeout=timeout)
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, list) or len(payload) < 2 or payload[1] is None:
        return {}
    series = {}
    for entry in payload[1]:
        if entry.get("value") is None:
            continue
        try:
            series[int(entry["date"])] = float(entry["value"])
        except (TypeError, ValueError):
            continue
    return series


def backfill(
    conn, *, dry_run: bool = False,
    fetch_fn: Callable[[str, str], dict[int, float]] = fetch_worldbank_series,
) -> dict:
    counts = {"rows_checked": 0, "rows_updated": 0, "unmapped_countries": set(), "no_data_country_years": 0}

    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(
            """
            SELECT movie_name, release_date, language, country
            FROM movies_data_collection
            WHERE (gdp_usd_billions IS NULL OR gdp_usd_billions = 0
                   OR inflation_rate_pct IS NULL OR inflation_rate_pct = 0)
              AND country IS NOT NULL AND btrim(country) <> ''
              AND left(release_date, 4) ~ '^[0-9]{4}$'
            """
        )
        rows = cur.fetchall()

    gdp_cache: dict[str, dict[int, float]] = {}
    inflation_cache: dict[str, dict[int, float]] = {}

    for row in rows:
        counts["rows_checked"] += 1
        country = primary_country(row["country"])
        iso3 = COUNTRY_NAME_TO_ISO3.get(country) if country else None
        if iso3 is None:
            if country:
                counts["unmapped_countries"].add(country)
            continue

        if iso3 not in gdp_cache:
            gdp_cache[iso3] = fetch_fn(iso3, GDP_INDICATOR)
        if iso3 not in inflation_cache:
            inflation_cache[iso3] = fetch_fn(iso3, INFLATION_INDICATOR)

        year = int(row["release_date"][:4])
        gdp_usd = gdp_cache[iso3].get(year)
        inflation_pct = inflation_cache[iso3].get(year)
        if gdp_usd is None and inflation_pct is None:
            counts["no_data_country_years"] += 1
            continue

        values = {}
        if gdp_usd is not None:
            values["gdp_usd_billions"] = gdp_usd / 1e9
        if inflation_pct is not None:
            values["inflation_rate_pct"] = inflation_pct

        if dry_run:
            continue

        set_clause = ", ".join(
            f"{safe_identifier(k)} = COALESCE(movies_data_collection.{safe_identifier(k)}, %s)"
            for k in values
        )
        with conn.cursor() as write_cur:
            write_cur.execute(
                f"UPDATE movies_data_collection SET {set_clause} "
                f"WHERE movie_name = %s AND release_date = %s AND language = %s",
                [*values.values(), row["movie_name"], row["release_date"], row["language"]],
            )
            counts["rows_updated"] += write_cur.rowcount
        conn.commit()

    return counts


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--dry-run", action="store_true")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        counts = backfill(conn, dry_run=args.dry_run)
    finally:
        conn.close()

    print(f"Checked {counts['rows_checked']} row(s) with a gdp/inflation gap.")
    print(f"Updated {counts['rows_updated']} row(s)." if not args.dry_run
          else "(dry run -- no rows written)")
    print(f"No World Bank data for {counts['no_data_country_years']} (country, year) combination(s).")
    if counts["unmapped_countries"]:
        print(f"{len(counts['unmapped_countries'])} country name(s) not in COUNTRY_NAME_TO_ISO3, skipped: "
              f"{sorted(counts['unmapped_countries'])[:20]}"
              f"{' ...' if len(counts['unmapped_countries']) > 20 else ''}")


if __name__ == "__main__":
    main()
