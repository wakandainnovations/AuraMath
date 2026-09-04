"""Fuzzy (title, release_year) entity resolution for bulk connector results
(Feature 3).

`KaggleDatasetConnector`/`FileDownloadConnector` return `{"rows": [...],
"n_rows": N}` -- one dict per movie in the source dataset, addressed by
whatever title string that source used, not by a `movies_data_collection`
primary key. Titles rarely match byte-for-byte across sources (punctuation,
"The" prefixes, subtitle differences, re-release naming), so a plain
`WHERE movie_name = ...` join would silently drop most rows.

`resolve_movie_entity_key` fuzzy-matches instead, using the same trigram
approach `NarrativeNoveltyService`/`GenreLookalikeService` use on the Java
side: pg_trgm's `%` operator against `idx_movies_data_collection_trgm`
(already created on `movies_data_collection.movie_name`) narrows to
plausible candidates via the GiST index, `similarity()` ranks them, and a
minimum-similarity threshold rejects a weak best-match instead of accepting
whatever happened to rank first.
"""
from __future__ import annotations

import re
from typing import Optional

DEFAULT_SIMILARITY_THRESHOLD = 0.35

# Bulk-row dict keys that exist only to *find* the matching
# movies_data_collection row -- never written back onto it (the matched row's
# own movie_name/release_date are already correct; overwriting them with
# whatever casing/format the source used would just add noise).
RESOLUTION_ONLY_FIELDS = {"movie_name", "release_year"}

_LEADING_YEAR_RE = re.compile(r"(\d{4})")


def coerce_release_year(value) -> Optional[int]:
    """Pulls a 4-digit year out of whatever shape a source CSV gives it --
    a bare year (int, float, or "2010" string) or a full date string
    ("2010-06-01")."""
    if value is None:
        return None
    if isinstance(value, float) and value != value:  # NaN
        return None
    m = _LEADING_YEAR_RE.search(str(value))
    return int(m.group(1)) if m else None


def resolve_movie_entity_key(
    conn, title: Optional[str], release_year: Optional[int],
    threshold: float = DEFAULT_SIMILARITY_THRESHOLD,
) -> Optional[tuple[str, str, str]]:
    """Fuzzy-matches (title, release_year) against `movies_data_collection`.

    Returns the best-matching row's exact `(movie_name, release_date,
    language)` triple -- the same three-part key `data_sources.entity_key`
    uses for movies -- or `None` if `title` is missing or nothing clears
    `threshold`. When several rows share a title/year (e.g. simultaneous
    multi-language releases), the single highest-`similarity()` row is
    returned; a bulk source's per-language split isn't attempted here.
    """
    if not title or not str(title).strip():
        return None

    with conn.cursor() as cur:
        if release_year is not None:
            cur.execute(
                """
                SELECT movie_name, release_date, language, similarity(movie_name, %(title)s) AS sim
                FROM movies_data_collection
                WHERE movie_name %% %(title)s
                  AND left(release_date, 4) = %(year)s
                ORDER BY sim DESC
                LIMIT 1
                """,
                {"title": title, "year": str(release_year)},
            )
        else:
            cur.execute(
                """
                SELECT movie_name, release_date, language, similarity(movie_name, %(title)s) AS sim
                FROM movies_data_collection
                WHERE movie_name %% %(title)s
                ORDER BY sim DESC
                LIMIT 1
                """,
                {"title": title},
            )
        row = cur.fetchone()

    if row is None:
        return None
    movie_name, release_date, language, sim = row
    if sim is None or sim < threshold:
        return None
    return movie_name, release_date, language
