"""Unit test for KaggleDatasetConnector -- no live network calls: `download_fn`
is injected to copy a local fixture CSV (zipped on the fly) instead of shelling
out to the real `kaggle` CLI."""
from __future__ import annotations

import os
import sys
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from connectors.kaggle_dataset import KaggleDatasetConnector  # noqa: E402

FIXTURES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")
FIXTURE_CSV = os.path.join(FIXTURES_DIR, "sample_kaggle_dataset.csv")


class KaggleDatasetConnectorTest(unittest.TestCase):
    def _fake_download(self, slug: str, dest_dir: str) -> None:
        zip_path = os.path.join(dest_dir, "sample_kaggle_dataset.zip")
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.write(FIXTURE_CSV, arcname="sample_kaggle_dataset.csv")

    def test_fetch_downloads_unzips_and_maps_columns(self):
        connector = KaggleDatasetConnector(
            field_mapping={"movie_title": "movie_name", "budget_usd": "budget"},
            download_fn=self._fake_download,
        )

        result = connector.fetch("someowner/sample-kaggle-dataset")

        self.assertEqual(result["n_rows"], 3)
        self.assertEqual(result["rows"][0], {"movie_name": "Vivah", "budget": 2237446})
        # Unmapped source columns (release_year, revenue_usd) are dropped.
        self.assertNotIn("release_year", result["rows"][0])

    def test_slug_from_full_kaggle_url(self):
        connector = KaggleDatasetConnector(field_mapping={}, download_fn=self._fake_download)
        slug = connector._slug_from_url("https://www.kaggle.com/datasets/someowner/sample-kaggle-dataset")
        self.assertEqual(slug, "someowner/sample-kaggle-dataset")

    def test_bare_slug_passthrough(self):
        connector = KaggleDatasetConnector(field_mapping={}, download_fn=self._fake_download)
        slug = connector._slug_from_url("someowner/sample-kaggle-dataset")
        self.assertEqual(slug, "someowner/sample-kaggle-dataset")


if __name__ == "__main__":
    unittest.main()
