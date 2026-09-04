"""NewsEventFeedConnector (Feature 7) -- the shared "did a searchable news
event of type X happen around this movie" connector behind seven Legal
factor_definitions rows: state_bans, pre_release_leak,
title_ownership_disputes, copyright_claims, distribution_disputes,
name_similarity_disputes, plagiarism_allegation_events. One connector class
covers all seven since they're all the same shape -- only the keyword set
(LEGAL_EVENT_KEYWORDS) differs per factor, not the connector logic.

Backed by GDELT's free public DOC 2.0 API (api.gdeltproject.org), no API key
needed. Implements the `Connector` protocol (`fetch(url) -> dict`) like
`ApiConnector`, but isn't `ApiConnector` itself: GDELT's response is a list
of matching articles, not a single JSON object to dot-path into, and this
needs SEVEN queries per movie (one per keyword set) built from a
release-date-anchored window, not one fetch per `data_sources` row --
`backfill_legal_news_events.py` (not `collect_data.py`'s generic per-row
dispatch) drives it, same reasoning `backfill_market_index.py`/
`backfill_world_bank_macro.py` document for their own shape mismatches.

Requirements
------------
    pip install requests
"""
from __future__ import annotations

from datetime import date
from urllib.parse import urlencode

import requests

GDELT_DOC_API_BASE = "https://api.gdeltproject.org/api/v2/doc/doc"
DEFAULT_MAXRECORDS = 25

# factor_key -> keyword phrases ORed together in the GDELT query, alongside
# the movie's title (ANDed). Deliberately conservative/specific phrasing --
# these are rare events (most movies have zero legal drama), so a broad
# keyword set would mostly just add false positives, not real signal.
LEGAL_EVENT_KEYWORDS: dict[str, list[str]] = {
    "state_bans": [
        "banned in", "state ban on release", "screening banned", "ban lifted",
    ],
    "pre_release_leak": [
        "leaked online", "print leaked", "pre-release leak", "leaked before release",
    ],
    "title_ownership_disputes": [
        "title ownership dispute", "title registered by", "rights to the title",
    ],
    "copyright_claims": [
        "copyright infringement", "copyright claim", "copyright violation lawsuit",
    ],
    "distribution_disputes": [
        "distribution rights dispute", "distributor dispute", "distribution deal collapsed",
    ],
    "name_similarity_disputes": [
        "name similarity row", "objected to the title", "similar to real name",
    ],
    "plagiarism_allegation_events": [
        "plagiarism allegations", "accused of copying", "accused of plagiarising",
    ],
}


def build_gdelt_query_url(
    movie_title: str, keywords: list[str], start: date, end: date,
    maxrecords: int = DEFAULT_MAXRECORDS,
) -> str:
    """One GDELT DOC 2.0 API GET URL: movie title ANDed with an ORed keyword
    clause, restricted to [start, end] (a release-date-anchored window, see
    backfill_legal_news_events.py's --window-days)."""
    keyword_clause = " OR ".join(f'"{kw}"' for kw in keywords)
    query = f'"{movie_title}" ({keyword_clause})'
    params = {
        "query": query,
        "mode": "artlist",
        "format": "json",
        "maxrecords": str(maxrecords),
        "startdatetime": start.strftime("%Y%m%d") + "000000",
        "enddatetime": end.strftime("%Y%m%d") + "235959",
    }
    return f"{GDELT_DOC_API_BASE}?{urlencode(params)}"


class NewsEventFeedConnector:
    """`fetch(url)` returns `{"n_articles": int, "article_titles": [str, ...]}`
    for one GDELT DOC 2.0 API query URL (see `build_gdelt_query_url`). The
    caller (backfill_legal_news_events.py) turns `n_articles > 0` into a
    binary flag per (movie, factor_key) -- this class itself is a plain
    fetch, no aggregation/flag logic, so it stays reusable for any future
    news-event factor that needs a different flagging rule."""

    def __init__(self, timeout: float = 15.0):
        self.timeout = timeout

    def fetch(self, url: str) -> dict:
        response = requests.get(url, timeout=self.timeout)
        response.raise_for_status()
        payload = response.json()
        articles = payload.get("articles", []) if isinstance(payload, dict) else []
        titles = [a.get("title") for a in articles if isinstance(a, dict) and a.get("title")]
        return {"n_articles": len(articles), "article_titles": titles}
