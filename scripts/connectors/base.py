"""Connector protocol (Feature 1).

Every data source this repo pulls from -- an HTML page, a JSON REST API, or a
Kaggle dataset -- implements one method, `fetch(url) -> dict`, so
`collect_data.py` can drive all three uniformly by dispatching on
`data_sources.connector_type` instead of hand-writing a fetch path per source.

Single-entity connectors (`HtmlScrapeConnector`, `ApiConnector`) return a flat
`{field_name: value}` dict for the one `entity_key` their `data_sources` row
names -- that dict is upserted directly onto the matching
`movies_data_collection`/`actors_data_collection` row.

`KaggleDatasetConnector` is bulk by nature: one `data_sources` row names a
whole dataset, not one movie/actor. It returns `{"rows": [...], "n_rows": N}`
instead of a flat dict -- per-row entity resolution against
`movies_data_collection` (fuzzy title/year matching via the existing
`idx_movies_data_collection_trgm` index) is Feature 3's job, not this one's.
"""
from __future__ import annotations

from typing import Protocol


class Connector(Protocol):
    def fetch(self, url: str) -> dict:
        ...
