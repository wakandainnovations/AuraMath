"""FileDownloadConnector (Feature 3).

For sources that ship a plain downloadable file at a fixed URL -- not a
Kaggle dataset (no `kaggle` CLI / zip involved) and not a single-entity REST
call. Named case: IMDb's non-commercial exports (`datasets.imdbws.com`),
gzipped TSVs updated daily, free for personal/research use, explicitly *not*
on Kaggle.

Bulk by nature, same as `KaggleDatasetConnector`: one `data_sources` row
names a whole file, not one movie/actor, so `fetch()` returns `{"rows":
[...], "n_rows": N}` and per-row entity resolution against
`movies_data_collection` is `connectors.entity_resolution`'s job, not this
connector's.

Requirements
------------
    pip install requests pandas
"""
from __future__ import annotations

import gzip
import io
from typing import Callable, Optional

import pandas as pd
import requests


class FileDownloadConnector:
    """`field_mapping`: `{source_column: target_column}` -- same convention
    as `KaggleDatasetConnector`. Gzip is auto-detected from a `.gz` URL
    suffix; delimiter defaults to tab for a `.tsv`/`.tsv.gz` URL, comma
    otherwise. `download_fn(url) -> bytes` defaults to a real HTTP GET; tests
    inject a fake one so no live network call is needed.
    """

    def __init__(self, field_mapping: dict, download_fn: Optional[Callable[[str], bytes]] = None,
                 timeout: float = 60.0):
        self.field_mapping = field_mapping or {}
        self.timeout = timeout
        self._download_fn = download_fn or self._http_download

    def _http_download(self, url: str) -> bytes:
        response = requests.get(url, timeout=self.timeout)
        response.raise_for_status()
        return response.content

    def fetch(self, url: str) -> dict:
        raw = self._download_fn(url)
        if url.endswith(".gz"):
            raw = gzip.decompress(raw)
        sep = "\t" if ".tsv" in url else ","
        df = pd.read_csv(io.BytesIO(raw), sep=sep, low_memory=False)

        available_source_cols = [c for c in self.field_mapping if c in df.columns]
        mapped = df[available_source_cols].rename(columns=self.field_mapping)
        rows = mapped.to_dict(orient="records")
        return {"rows": rows, "n_rows": len(rows)}
