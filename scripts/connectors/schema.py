"""`data_sources` registry table (Feature 1).

`data_sources` governs *where raw data comes from* -- one row per
(entity, source, url). It is deliberately a separate table from Feature 2's
`factor_definitions`, which governs *which columns the model actually trains
on*: a `data_sources` row can exist with no corresponding model feature yet
(raw metadata pulled in but not yet used), and a `factor_definitions` row can
exist with no single `data_sources` row behind it (a derived or hand-entered
factor). Keep them separate rather than merging into one table.

Follows the same `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` / `CREATE TABLE IF
NOT EXISTS` convention `ConflictBalanceService`/`NarrativeNoveltyService` use
on the Java side (their `ensureSchema()` methods) -- this is that pattern's
Python-side equivalent, safe to call on every run.
"""
from __future__ import annotations

from typing import Optional

import psycopg2.extras

TARGET_TABLE_BY_ENTITY_TYPE = {
    "movie": "movies_data_collection",
    "actor": "actors_data_collection",
}

_CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS data_sources (
        id serial PRIMARY KEY,
        entity_type text NOT NULL CHECK (entity_type IN ('movie', 'actor')),
        entity_key text NOT NULL,
        source_name text NOT NULL,
        url text NOT NULL,
        connector_type text NOT NULL CHECK (connector_type IN ('html_scrape', 'api', 'kaggle_csv')),
        field_mapping jsonb,
        last_fetched_at timestamptz,
        last_status text,
        raw_payload jsonb,
        UNIQUE (entity_type, entity_key, source_name, url)
    )
"""

# entity_type='movie' entity_key is movie_name||'|'||release_date||'|'||language
# (three-part composite matching movies_data_collection's primary key);
# entity_type='actor' entity_key is bare actor_name -- both per the plan's spec.

_CREATE_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_data_sources_source_entity_type
        ON data_sources (source_name, entity_type)
"""


def ensure_data_sources_schema(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(_CREATE_TABLE_SQL)
        cur.execute(_CREATE_INDEX_SQL)
    conn.commit()


def movie_entity_key(movie_name: str, release_date: str, language: str) -> str:
    return f"{movie_name}|{release_date}|{language}"


def parse_movie_entity_key(entity_key: str) -> tuple[str, str, str]:
    parts = entity_key.split("|")
    if len(parts) != 3:
        raise ValueError(
            f"Malformed movie entity_key (expected movie_name|release_date|language): {entity_key!r}")
    return parts[0], parts[1], parts[2]


def register_source(conn, *, entity_type: str, entity_key: str, source_name: str,
                     url: str, connector_type: str, field_mapping: Optional[dict] = None) -> None:
    """Upsert one `data_sources` row -- the "add a URL to the list" operation
    the plan asks for. Safe to call repeatedly with the same
    (entity_type, entity_key, source_name, url): re-registering just refreshes
    `field_mapping`."""
    ensure_data_sources_schema(conn)
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO data_sources (entity_type, entity_key, source_name, url, connector_type, field_mapping)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (entity_type, entity_key, source_name, url)
            DO UPDATE SET connector_type = EXCLUDED.connector_type, field_mapping = EXCLUDED.field_mapping
            """,
            (entity_type, entity_key, source_name, url, connector_type,
             psycopg2.extras.Json(field_mapping) if field_mapping is not None else None),
        )
    conn.commit()
