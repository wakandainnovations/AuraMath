"""Shared fuzzy-match + fill-null upsert for bulk connector results (Feature 3).

Used by `collect_data.py` (for `data_sources` rows whose `connector_type` is
`kaggle_csv`/`file_download`) and by the dedicated `backfill_world_bank_macro.py`
/ `backfill_imdb_ratings.py` scripts (sources whose real fetch shape doesn't
fit the generic per-row connector dispatch -- see `sources.yaml`'s notes on
each) -- one implementation of "fuzzy-match a bulk row to
`movies_data_collection`, then fill only the columns that are currently null"
instead of three copies of the same loop.

Deliberately **fill-null-only** (`COALESCE(existing, new)`), not a blind
overwrite: these sources exist to backfill *gaps* (94.3% of rows have no
`budget`, 96.6% no `revenue`, ~10% missing `gdp_usd_billions`/
`inflation_rate_pct`) -- a differently-sourced number for a column this
table's own pipeline already populated should never silently replace it.
"""
from __future__ import annotations

import re
from typing import Optional

from .entity_resolution import (
    RESOLUTION_ONLY_FIELDS,
    coerce_release_year,
    resolve_movie_entity_key,
)

_SAFE_IDENTIFIER = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")


def safe_identifier(name: str) -> str:
    """`values` dict keys land directly in generated `ALTER TABLE`/`UPDATE`
    SQL below -- only identifier-shaped names are allowed, to rule out
    injection via a maliciously configured `field_mapping`."""
    if not _SAFE_IDENTIFIER.match(name):
        raise ValueError(f"Refusing to use non-identifier column name from field_mapping: {name!r}")
    return name


def ensure_target_columns(conn, table: str, columns: list[str]) -> None:
    if not columns:
        return
    with conn.cursor() as cur:
        for col in columns:
            cur.execute(f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS {safe_identifier(col)} text")
    conn.commit()


def fill_null_update_movie(conn, values: dict, movie_name: str, release_date: str, language: str) -> int:
    """UPDATEs only the columns in `values` that are currently NULL on the
    matched `movies_data_collection` row (`COALESCE`-guarded) -- never
    clobbers an already-populated value with a differently-sourced one."""
    if not values:
        return 0
    set_clause = ", ".join(
        f"{safe_identifier(k)} = COALESCE(movies_data_collection.{safe_identifier(k)}, %s)"
        for k in values
    )
    sql = (f"UPDATE movies_data_collection SET {set_clause} "
           f"WHERE movie_name = %s AND release_date = %s AND language = %s")
    with conn.cursor() as cur:
        cur.execute(sql, [*values.values(), movie_name, release_date, language])
        return cur.rowcount


def resolve_and_upsert_bulk_movie_rows(
    conn, rows: list[dict], *, dry_run: bool = False,
    similarity_threshold: float = 0.35,
) -> dict:
    """Fuzzy-matches every row (each a flat `{target_column: value}` dict
    carrying `movie_name`/`release_year` resolution keys alongside its data
    fields -- the shape `KaggleDatasetConnector`/`FileDownloadConnector`
    already produce via `field_mapping`) to `movies_data_collection` and
    fill-null-upserts the rest.

    Returns `{"attempted", "matched", "unmatched", "rows_updated"}`. In
    `dry_run` mode, resolution still runs (so match-rate is visible) but
    nothing is written.
    """
    counts = {"attempted": len(rows), "matched": 0, "unmatched": 0, "rows_updated": 0}
    if not rows:
        return counts

    data_columns = sorted({k for row in rows for k in row if k not in RESOLUTION_ONLY_FIELDS})
    if not dry_run:
        ensure_target_columns(conn, "movies_data_collection", data_columns)

    for row in rows:
        title = row.get("movie_name")
        year = coerce_release_year(row.get("release_year"))
        resolved = resolve_movie_entity_key(conn, title, year, threshold=similarity_threshold)
        if resolved is None:
            counts["unmatched"] += 1
            continue
        counts["matched"] += 1

        values = {k: v for k, v in row.items() if k not in RESOLUTION_ONLY_FIELDS and v is not None}
        if dry_run or not values:
            continue
        movie_name, release_date, language = resolved
        counts["rows_updated"] += fill_null_update_movie(conn, values, movie_name, release_date, language)
        conn.commit()

    return counts
