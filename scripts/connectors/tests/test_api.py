"""Unit test for ApiConnector -- no live network calls: requests.get is mocked
to return a local JSON fixture."""
from __future__ import annotations

import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from connectors.api import ApiConnector  # noqa: E402

FIXTURES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self):
        return self._payload


class ApiConnectorTest(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(FIXTURES_DIR, "sample_api_response.json")) as f:
            self.fixture_payload = json.load(f)

    @mock.patch("connectors.api.requests.get")
    def test_fetch_resolves_dot_paths(self, mock_get):
        mock_get.return_value = FakeResponse(self.fixture_payload)
        connector = ApiConnector(field_mapping={
            "imdb_rating": "vote_average",
            "primary_genre": "genres.0.name",
            "missing_field": "not.a.real.path",
        })

        result = connector.fetch("https://api.example.com/movie/693134")

        self.assertEqual(result["imdb_rating"], 8.2)
        self.assertEqual(result["primary_genre"], "Science Fiction")
        self.assertIsNone(result["missing_field"])

    @mock.patch("connectors.api.requests.get")
    def test_out_of_range_list_index_returns_none(self, mock_get):
        mock_get.return_value = FakeResponse(self.fixture_payload)
        connector = ApiConnector(field_mapping={"tenth_genre": "genres.9.name"})

        result = connector.fetch("https://api.example.com/movie/693134")

        self.assertIsNone(result["tenth_genre"])


if __name__ == "__main__":
    unittest.main()
