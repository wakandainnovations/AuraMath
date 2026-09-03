"""HtmlScrapeConnector (Feature 1).

Requirements
------------
    pip install requests beautifulsoup4

A new HTML source is a `data_sources` config row, not new code: `field_mapping`
is a `{target_field: css_selector}` map, so pointing this connector at a new
page means inserting a row (see `register_source` in schema.py / Feature 3's
`sources.yaml` entries), not writing a new scraper function.
"""
from __future__ import annotations

import time
from urllib import robotparser
from urllib.parse import urlparse

import requests
from bs4 import BeautifulSoup

DEFAULT_USER_AGENT = "AuraMathDataCollector/1.0 (+internal box-office research bot)"


class HtmlScrapeConnector:
    """`field_mapping`: {target_field_name: css_selector}. `fetch(url)` returns
    `{target_field_name: text_content_or_None}` for the one page at `url`.

    Respects robots.txt (refuses to fetch a disallowed URL) and enforces
    `delay_seconds` between requests to the same host, tracked across calls on
    one connector instance -- so a loop over many `data_sources` rows pointed
    at the same site doesn't hammer it.
    """

    def __init__(self, field_mapping: dict, delay_seconds: float = 2.0,
                 user_agent: str = DEFAULT_USER_AGENT, timeout: float = 15.0):
        self.field_mapping = field_mapping or {}
        self.delay_seconds = delay_seconds
        self.user_agent = user_agent
        self.timeout = timeout
        self._last_fetch_monotonic_by_host: dict[str, float] = {}
        self._robots_cache: dict[str, robotparser.RobotFileParser | None] = {}

    def _robots_allow(self, url: str) -> bool:
        parsed = urlparse(url)
        origin = f"{parsed.scheme}://{parsed.netloc}"
        if origin not in self._robots_cache:
            rp = robotparser.RobotFileParser()
            rp.set_url(origin + "/robots.txt")
            try:
                rp.read()
            except Exception:
                # robots.txt unreachable -- fail open (most sites without a
                # reachable robots.txt intend no restriction) but note it.
                rp = None
            self._robots_cache[origin] = rp
        rp = self._robots_cache[origin]
        return rp.can_fetch(self.user_agent, url) if rp is not None else True

    def _wait_for_host_delay(self, url: str) -> None:
        host = urlparse(url).netloc
        last = self._last_fetch_monotonic_by_host.get(host)
        now = time.monotonic()
        if last is not None:
            remaining = self.delay_seconds - (now - last)
            if remaining > 0:
                time.sleep(remaining)
        self._last_fetch_monotonic_by_host[host] = time.monotonic()

    def fetch(self, url: str) -> dict:
        if not self._robots_allow(url):
            raise PermissionError(f"robots.txt disallows fetching {url} as {self.user_agent!r}")
        self._wait_for_host_delay(url)

        response = requests.get(url, headers={"User-Agent": self.user_agent}, timeout=self.timeout)
        response.raise_for_status()
        soup = BeautifulSoup(response.text, "html.parser")

        result: dict = {}
        for field, selector in self.field_mapping.items():
            element = soup.select_one(selector)
            result[field] = element.get_text(strip=True) if element is not None else None
        return result
