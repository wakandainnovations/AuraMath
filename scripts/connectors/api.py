"""ApiConnector (Feature 1) -- a generic REST GET + JSON dot-path extraction,
for sources with a real API (TMDB, World Bank, yfinance-style feeds).

Requirements
------------
    pip install requests
"""
from __future__ import annotations

from typing import Optional

import requests


class ApiConnector:
    """`field_mapping`: {target_field_name: dot_path_into_json_response}, e.g.
    {"imdb_rating": "vote_average", "genres": "genres.0.name"}. A numeric path
    segment indexes into a JSON array. `fetch(url)` returns
    `{target_field_name: value_or_None}` for the one GET at `url`.
    """

    def __init__(self, field_mapping: dict, headers: Optional[dict] = None, timeout: float = 15.0):
        self.field_mapping = field_mapping or {}
        self.headers = headers or {}
        self.timeout = timeout

    @staticmethod
    def _resolve_dot_path(payload, dot_path: str):
        current = payload
        for part in dot_path.split("."):
            if isinstance(current, list):
                if not part.lstrip("-").isdigit():
                    return None
                index = int(part)
                if index < -len(current) or index >= len(current):
                    return None
                current = current[index]
            elif isinstance(current, dict):
                if part not in current:
                    return None
                current = current[part]
            else:
                return None
        return current

    def fetch(self, url: str) -> dict:
        response = requests.get(url, headers=self.headers, timeout=self.timeout)
        response.raise_for_status()
        payload = response.json()
        return {field: self._resolve_dot_path(payload, dot_path) for field, dot_path in self.field_mapping.items()}
