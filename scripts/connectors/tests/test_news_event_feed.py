"""Unit tests for NewsEventFeedConnector/build_gdelt_query_url (Feature 7) --
no live network calls: requests.get is mocked to return a local JSON
fixture, mirroring test_api.py's style."""
from __future__ import annotations

import json
import os
import sys
import unittest
from datetime import date
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from connectors.news_event_feed import (  # noqa: E402
    LEGAL_EVENT_KEYWORDS, NewsEventFeedConnector, build_gdelt_query_url,
)

FIXTURES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self):
        return self._payload


class BuildGdeltQueryUrlTest(unittest.TestCase):
    def test_url_contains_title_and_keywords_anded_ored(self):
        url = build_gdelt_query_url(
            "Some Movie", ["banned in", "state ban"], date(2024, 1, 1), date(2024, 2, 1))
        self.assertIn("gdeltproject.org", url)
        self.assertIn("mode=artlist", url)
        self.assertIn("format=json", url)
        self.assertIn("startdatetime=20240101000000", url)
        self.assertIn("enddatetime=20240201235959", url)

    def test_every_legal_event_keyword_set_is_non_empty(self):
        for factor_key, keywords in LEGAL_EVENT_KEYWORDS.items():
            self.assertTrue(keywords, f"{factor_key} has no keywords configured")


class NewsEventFeedConnectorTest(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(FIXTURES_DIR, "sample_gdelt_response.json")) as f:
            self.fixture_payload = json.load(f)

    @mock.patch("connectors.news_event_feed.requests.get")
    def test_fetch_counts_articles_and_collects_titles(self, mock_get):
        mock_get.return_value = FakeResponse(self.fixture_payload)
        connector = NewsEventFeedConnector()

        result = connector.fetch("https://api.gdeltproject.org/api/v2/doc/doc?query=test")

        self.assertEqual(result["n_articles"], 2)
        self.assertEqual(
            result["article_titles"],
            ["Film banned in two states after protests", "State ban on release lifted after court order"],
        )

    @mock.patch("connectors.news_event_feed.requests.get")
    def test_fetch_no_articles_returns_zero(self, mock_get):
        mock_get.return_value = FakeResponse({"articles": []})
        connector = NewsEventFeedConnector()

        result = connector.fetch("https://api.gdeltproject.org/api/v2/doc/doc?query=test")

        self.assertEqual(result["n_articles"], 0)
        self.assertEqual(result["article_titles"], [])

    @mock.patch("connectors.news_event_feed.requests.get")
    def test_fetch_malformed_payload_degrades_to_zero(self, mock_get):
        mock_get.return_value = FakeResponse(["not", "a", "dict"])
        connector = NewsEventFeedConnector()

        result = connector.fetch("https://api.gdeltproject.org/api/v2/doc/doc?query=test")

        self.assertEqual(result["n_articles"], 0)


if __name__ == "__main__":
    unittest.main()
