"""Unit test for FileDownloadConnector -- no live network calls: `download_fn`
is injected to return a local fixture TSV, gzipped in memory, instead of
hitting a real URL."""
from __future__ import annotations

import gzip
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from connectors.file_download import FileDownloadConnector  # noqa: E402

FIXTURES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")
FIXTURE_TSV = os.path.join(FIXTURES_DIR, "sample_imdb_ratings.tsv")


class FileDownloadConnectorTest(unittest.TestCase):
    def _fake_download(self, url: str) -> bytes:
        with open(FIXTURE_TSV, "rb") as f:
            raw = f.read()
        return gzip.compress(raw) if url.endswith(".gz") else raw

    def test_fetch_downloads_gunzips_and_maps_columns(self):
        connector = FileDownloadConnector(
            field_mapping={"primaryTitle": "movie_name", "averageRating": "imdb_rating"},
            download_fn=self._fake_download,
        )

        result = connector.fetch("https://datasets.imdbws.com/title.ratings.tsv.gz")

        self.assertEqual(result["n_rows"], 3)
        self.assertEqual(
            result["rows"][0],
            {"movie_name": "The Shawshank Redemption", "imdb_rating": 9.3},
        )
        # Unmapped source columns (tconst, numVotes) are dropped.
        self.assertNotIn("tconst", result["rows"][0])

    def _fake_download_plain(self, url: str) -> bytes:
        with open(FIXTURE_TSV, "rb") as f:
            return f.read()

    def test_non_gz_url_is_not_decompressed(self):
        connector = FileDownloadConnector(
            field_mapping={"primaryTitle": "movie_name"},
            download_fn=self._fake_download_plain,
        )
        result = connector.fetch("https://example.com/sample_imdb_ratings.tsv")
        self.assertEqual(result["n_rows"], 3)


if __name__ == "__main__":
    unittest.main()
