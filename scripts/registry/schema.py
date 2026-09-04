"""Factor registry (Feature 2) -- the live, queryable replacement for the
hardcoded `FACTOR_CATALOG` list that used to live in
`movie_revenue_impact_model.py`.

Two tables:

- `factor_definitions`: one row per factor the model *could* train on --
  what it is, where its value comes from, and whether it's currently
  trusted enough to feed the model (`status`). This is what makes "add a
  new predictive parameter later" a data-registration step instead of a
  script edit: insert one row here (`register_factor.py`, or
  `POST /api/admin/factor-definitions` on the Java side), supply the
  values, and the next scheduled training run picks it up automatically.
- `movie_factor_values`: a generic EAV overflow table for factors that
  don't warrant a dedicated column -- a one-off business-supplied score, a
  CSV someone hands you next quarter, an experimental signal being
  trialed before it earns a real column.

Division of concerns with Feature 1's `data_sources` table: `data_sources`
governs *where raw data comes from*; `factor_definitions` governs *which
columns the model actually trains on*. A `data_sources` row can exist with
no corresponding model feature yet, and a `factor_definitions` row can
exist with no single `data_sources` row behind it (a derived or
hand-entered factor). Kept as two separate tables, not merged.

Follows the same `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ... ADD
COLUMN IF NOT EXISTS` convention `ConflictBalanceService`/
`NarrativeNoveltyService` use on the Java side (their `ensureSchema()`
methods) and `scripts/connectors/schema.py` uses on the Python side for
`data_sources` -- this is that same pattern applied to the factor
registry, safe to call on every run.
"""
from __future__ import annotations

from typing import Optional

import psycopg2.extras

STATUS_VALUES = ("candidate", "active", "deprecated", "explanatory_only")
DIRECTION_VALUES = ("Positive", "Negative", "Bidirectional")
DATA_TYPE_VALUES = ("numeric", "boolean", "categorical")
COMPUTATION_TYPE_VALUES = ("raw_column", "derived_sql", "derived_python_fn", "eav")

_CREATE_FACTOR_DEFINITIONS_SQL = """
    CREATE TABLE IF NOT EXISTS factor_definitions (
        factor_key text PRIMARY KEY,
        name text NOT NULL,
        category text NOT NULL,
        direction text NOT NULL CHECK (direction IN ('Positive', 'Negative', 'Bidirectional')),
        stated_min numeric NOT NULL,
        stated_max numeric NOT NULL,
        data_type text NOT NULL CHECK (data_type IN ('numeric', 'boolean', 'categorical')),
        status text NOT NULL DEFAULT 'candidate'
            CHECK (status IN ('candidate', 'active', 'deprecated', 'explanatory_only')),
        source_table text,
        source_column text,
        computation_type text
            CHECK (computation_type IS NULL OR computation_type IN
                   ('raw_column', 'derived_sql', 'derived_python_fn', 'eav')),
        derivation_ref text,
        added_at timestamptz NOT NULL DEFAULT now(),
        added_by text,
        notes text
    )
"""

_CREATE_MOVIE_FACTOR_VALUES_SQL = """
    CREATE TABLE IF NOT EXISTS movie_factor_values (
        movie_key text NOT NULL,
        factor_key text NOT NULL REFERENCES factor_definitions (factor_key),
        value_numeric numeric,
        value_text text,
        computed_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (movie_key, factor_key)
    )
"""

_CREATE_STATUS_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_factor_definitions_status ON factor_definitions (status)
"""

_CREATE_MFV_FACTOR_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_movie_factor_values_factor_key ON movie_factor_values (factor_key)
"""


def ensure_factor_registry_schema(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(_CREATE_FACTOR_DEFINITIONS_SQL)
        cur.execute(_CREATE_MOVIE_FACTOR_VALUES_SQL)
        cur.execute(_CREATE_STATUS_INDEX_SQL)
        cur.execute(_CREATE_MFV_FACTOR_INDEX_SQL)
    conn.commit()


def register_factor(
    conn, *, factor_key: str, name: str, category: str, direction: str,
    stated_min: float, stated_max: float, data_type: str = "numeric",
    status: str = "candidate", source_table: Optional[str] = None,
    source_column: Optional[str] = None, computation_type: Optional[str] = None,
    derivation_ref: Optional[str] = None, added_by: Optional[str] = None,
    notes: Optional[str] = None,
) -> None:
    """Upsert one `factor_definitions` row -- the "add a parameter" operation
    the plan asks for. Safe to call repeatedly with the same `factor_key`:
    re-registering refreshes every field except `added_at`/`added_by`
    (first-write-wins on those two, so re-running a registration script
    doesn't churn provenance)."""
    ensure_factor_registry_schema(conn)
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO factor_definitions
                (factor_key, name, category, direction, stated_min, stated_max,
                 data_type, status, source_table, source_column, computation_type,
                 derivation_ref, added_by, notes)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (factor_key) DO UPDATE SET
                name = EXCLUDED.name,
                category = EXCLUDED.category,
                direction = EXCLUDED.direction,
                stated_min = EXCLUDED.stated_min,
                stated_max = EXCLUDED.stated_max,
                data_type = EXCLUDED.data_type,
                status = EXCLUDED.status,
                source_table = EXCLUDED.source_table,
                source_column = EXCLUDED.source_column,
                computation_type = EXCLUDED.computation_type,
                derivation_ref = EXCLUDED.derivation_ref,
                notes = EXCLUDED.notes
            """,
            (factor_key, name, category, direction, stated_min, stated_max,
             data_type, status, source_table, source_column, computation_type,
             derivation_ref, added_by, notes),
        )
    conn.commit()


def set_factor_status(conn, factor_key: str, status: str) -> bool:
    """Promote candidate -> active, or deprecate. Returns False if the
    factor_key doesn't exist."""
    if status not in STATUS_VALUES:
        raise ValueError(f"status must be one of {STATUS_VALUES}, got {status!r}")
    ensure_factor_registry_schema(conn)
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE factor_definitions SET status = %s WHERE factor_key = %s",
            (status, factor_key),
        )
        updated = cur.rowcount > 0
    conn.commit()
    return updated


def fetch_factor_definitions(conn, statuses: Optional[tuple[str, ...]] = None) -> list[dict]:
    """Returns every factor_definitions row (optionally filtered to a set of
    statuses) as a list of plain dicts, ordered by factor_key for stable
    output. `feature_columns()`/`assemble_features()` in
    movie_revenue_impact_model.py read this instead of the old hardcoded
    FACTOR_CATALOG list."""
    ensure_factor_registry_schema(conn)
    sql = "SELECT * FROM factor_definitions"
    params: tuple = ()
    if statuses:
        sql += " WHERE status = ANY(%s)"
        params = (list(statuses),)
    sql += " ORDER BY factor_key"
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql, params)
        return [dict(row) for row in cur.fetchall()]


def upsert_factor_value(conn, *, movie_key: str, factor_key: str,
                         value_numeric: Optional[float] = None,
                         value_text: Optional[str] = None) -> None:
    ensure_factor_registry_schema(conn)
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO movie_factor_values (movie_key, factor_key, value_numeric, value_text, computed_at)
            VALUES (%s, %s, %s, %s, now())
            ON CONFLICT (movie_key, factor_key) DO UPDATE SET
                value_numeric = EXCLUDED.value_numeric,
                value_text = EXCLUDED.value_text,
                computed_at = EXCLUDED.computed_at
            """,
            (movie_key, factor_key, value_numeric, value_text),
        )
    conn.commit()


def fetch_movie_factor_values(conn, factor_keys: list[str]) -> dict[str, dict[str, float]]:
    """Returns {factor_key: {movie_key: value_numeric}} for every requested
    EAV-backed factor. Text-valued rows (value_text set, value_numeric null)
    are omitted here since the model only consumes numeric features -- a
    categorical EAV factor would need its own encoding step before it could
    feed a regression, out of scope for this generic loader."""
    if not factor_keys:
        return {}
    ensure_factor_registry_schema(conn)
    out: dict[str, dict[str, float]] = {k: {} for k in factor_keys}
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT factor_key, movie_key, value_numeric FROM movie_factor_values
            WHERE factor_key = ANY(%s) AND value_numeric IS NOT NULL
            """,
            (factor_keys,),
        )
        for factor_key, movie_key, value_numeric in cur.fetchall():
            out.setdefault(factor_key, {})[movie_key] = float(value_numeric)
    return out
