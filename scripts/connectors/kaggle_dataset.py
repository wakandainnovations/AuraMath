"""KaggleDatasetConnector (Feature 1).

Requirements
------------
    pip install kaggle pandas

Setup
-----
See README.md in this directory for `~/.kaggle/kaggle.json` credential setup --
required before this connector's default `_kaggle_cli_download` will work.
"""
from __future__ import annotations

import os
import subprocess
import tempfile
import zipfile
from typing import Callable, Optional
from urllib.parse import urlparse

import pandas as pd


class KaggleDatasetConnector:
    """`field_mapping`: {source_csv_column: target_table_column}. `fetch(url)`
    downloads the dataset (`url` may be a bare `owner/dataset-slug` or a full
    kaggle.com dataset URL), unzips it, loads `target_csv` (or the first CSV
    found, if not given) with pandas, and returns
    `{"rows": [{target_column: value, ...}, ...], "n_rows": N}` -- a bulk
    result, unlike the single-entity connectors, since one Kaggle dataset
    covers many movies/actors at once. Matching each row to a
    `movies_data_collection`/`actors_data_collection` record is Feature 3's
    fuzzy-entity-resolution job, not this connector's.

    `download_fn(slug, dest_dir)` defaults to shelling out to the real `kaggle`
    CLI; tests inject a fake one so no live network call is needed.
    """

    def __init__(self, field_mapping: dict, target_csv: Optional[str] = None,
                 download_dir: Optional[str] = None,
                 download_fn: Optional[Callable[[str, str], None]] = None):
        self.field_mapping = field_mapping or {}
        self.target_csv = target_csv
        self.download_dir = download_dir
        self._download_fn = download_fn or self._kaggle_cli_download

    @staticmethod
    def _kaggle_cli_download(slug: str, dest_dir: str) -> None:
        subprocess.run(
            ["kaggle", "datasets", "download", "-d", slug, "-p", dest_dir, "--force"],
            check=True, capture_output=True,
        )

    @staticmethod
    def _slug_from_url(url: str) -> str:
        if url.startswith("http://") or url.startswith("https://"):
            parts = [p for p in urlparse(url).path.split("/") if p]
            return "/".join(parts[-2:])
        return url

    @staticmethod
    def _find_zip(dest_dir: str) -> str:
        zips = [f for f in os.listdir(dest_dir) if f.endswith(".zip")]
        if not zips:
            raise FileNotFoundError(f"No .zip file found in {dest_dir} after Kaggle download")
        return os.path.join(dest_dir, zips[0])

    def _resolve_csv_path(self, dest_dir: str) -> str:
        if self.target_csv:
            candidate = os.path.join(dest_dir, self.target_csv)
            if os.path.exists(candidate):
                return candidate
        csvs = [f for f in os.listdir(dest_dir) if f.endswith(".csv")]
        if not csvs:
            raise FileNotFoundError(f"No CSV found in {dest_dir}")
        return os.path.join(dest_dir, sorted(csvs)[0])

    def fetch(self, url: str) -> dict:
        slug = self._slug_from_url(url)
        with tempfile.TemporaryDirectory() as tmp_dir:
            dest_dir = self.download_dir or tmp_dir
            os.makedirs(dest_dir, exist_ok=True)
            self._download_fn(slug, dest_dir)

            zip_path = self._find_zip(dest_dir)
            with zipfile.ZipFile(zip_path) as zf:
                zf.extractall(dest_dir)

            csv_path = self._resolve_csv_path(dest_dir)
            df = pd.read_csv(csv_path)

            available_source_cols = [c for c in self.field_mapping if c in df.columns]
            mapped = df[available_source_cols].rename(columns=self.field_mapping)
            rows = mapped.to_dict(orient="records")
            return {"rows": rows, "n_rows": len(rows)}
