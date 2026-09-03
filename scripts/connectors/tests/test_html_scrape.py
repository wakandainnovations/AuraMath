"""Unit test for HtmlScrapeConnector -- no live network calls: requests.get and
robots.txt fetching are both mocked, and the "page" fetched is a local fixture."""
from __future__ import annotations

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from connectors.html_scrape import HtmlScrapeConnector  # noqa: E402

FIXTURES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


class FakeResponse:
    def __init__(self, text: str):
        self.text = text

    def raise_for_status(self) -> None:
        return None


class HtmlScrapeConnectorTest(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(FIXTURES_DIR, "sample_actor_page.html")) as f:
            self.fixture_html = f.read()

    def _connector(self, **kwargs) -> HtmlScrapeConnector:
        connector = HtmlScrapeConnector(
            field_mapping={
                "resolved_actor_name": ".actor-name",
                "film_count_text": ".film-count",
                "missing_field": ".does-not-exist",
            },
            delay_seconds=0,
            **kwargs,
        )
        # Bypass a real robots.txt fetch -- allow everything for this test.
        connector._robots_allow = lambda url: True
        return connector

    @mock.patch("connectors.html_scrape.requests.get")
    def test_fetch_extracts_mapped_fields(self, mock_get):
        mock_get.return_value = FakeResponse(self.fixture_html)
        connector = self._connector()

        result = connector.fetch("https://example.com/actor/aamir-khan")

        self.assertEqual(result["resolved_actor_name"], "Aamir Khan")
        self.assertEqual(result["film_count_text"], "55 films")
        self.assertIsNone(result["missing_field"])
        mock_get.assert_called_once()

    @mock.patch("connectors.html_scrape.requests.get")
    def test_robots_disallow_raises_and_skips_fetch(self, mock_get):
        connector = self._connector()
        connector._robots_allow = lambda url: False

        with self.assertRaises(PermissionError):
            connector.fetch("https://example.com/disallowed")
        mock_get.assert_not_called()


if __name__ == "__main__":
    unittest.main()
