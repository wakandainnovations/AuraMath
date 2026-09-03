# Data-source connectors (Feature 1)

Generalizes the per-row URL pattern already on `actors_data_collection`
(`sacnilk_url`/`kulfiy_url`/`fandango_url`) into a queryable registry table,
`data_sources`, plus three connector implementations that know how to actually
fetch from it. See `schema.py`'s module docstring for how this table relates
to Feature 2's `factor_definitions`.

## Model

- **`data_sources`** (see `schema.py`): one row per (entity, source, url).
  `connector_type` picks which of the three connectors below handles it;
  `field_mapping` configures *that* connector without any code change.
- **Connectors** (`base.py`'s `Connector` protocol): `fetch(url) -> dict`.
  - `HtmlScrapeConnector` -- `field_mapping` is `{target_field: css_selector}`.
  - `ApiConnector` -- `field_mapping` is `{target_field: dot.path.into.json}`.
  - `KaggleDatasetConnector` -- `field_mapping` is `{source_csv_col: target_col}`;
    returns bulk `{"rows": [...], "n_rows": N}` since one dataset covers many
    movies/actors (entity resolution against `movies_data_collection` is
    Feature 3's fuzzy-match job, not this connector's).
- **`collect_data.py`** (one directory up): the CLI driver --
  `python3 collect_data.py --source sacnilk --entity-type actor [--dry-run]`.
- **`migrate_data_sources.py`** (one directory up): the one-time backfill from
  the legacy `sacnilk_url`/`kulfiy_url`/`fandango_url` columns.

## Adding a new source

Register one `data_sources` row (via `schema.register_source(conn, ...)`, or
directly with SQL) -- no code change needed unless the source needs a genuinely
new connector type:

```python
from connectors.schema import register_source
register_source(
    conn,
    entity_type="movie",
    entity_key="Dune: Part Two|2024-03-01|english",
    source_name="tmdb",
    url="https://api.themoviedb.org/3/movie/693134?api_key=...",
    connector_type="api",
    field_mapping={"imdb_rating": "vote_average", "genres": "genres.0.name"},
)
```

## Kaggle credential setup (`KaggleDatasetConnector`)

1. `pip install kaggle`
2. Go to https://www.kaggle.com/settings/account -> **API** -> **Create New
   Token**. This downloads `kaggle.json` (your username + API key).
3. Place it at `~/.kaggle/kaggle.json` and lock down permissions:
   ```bash
   mkdir -p ~/.kaggle
   mv ~/Downloads/kaggle.json ~/.kaggle/kaggle.json
   chmod 600 ~/.kaggle/kaggle.json
   ```
4. Verify: `kaggle datasets list -s tmdb` should return results with no auth error.

`KaggleDatasetConnector.fetch(url)` accepts either a bare slug
(`"tmdb/tmdb-movie-metadata"`) or a full `kaggle.com/datasets/...` URL for
`url`.

## `kulfiy_url` -- unresolved

`kulfiy.com` appears to be a per-actor filmography listing page (e.g.
`https://in.kulfiy.com/movies/aamir-khan-movies/`), but its purpose was never
documented in this repo. `migrate_data_sources.py` backfills its 6,492 existing
URLs into `data_sources` as-is (connector_type `html_scrape`, empty
`field_mapping`) so the registry reflects what already existed, but per the
project plan: **confirm what Kulfiy actually is with whoever populated these
URLs before writing CSS-selector `field_mapping` config for it.**

## Robots.txt and rate limiting

`HtmlScrapeConnector` checks `robots.txt` before every fetch and refuses
(raises `PermissionError`) if the target path is disallowed for its user
agent. It also enforces a configurable per-host delay (`delay_seconds`,
default 2s) between requests to the same host, tracked across calls on one
connector instance.

## Tests

`tests/` has one unit test per connector type, using local HTML/JSON fixtures
under `tests/fixtures/` -- no live network calls. Run with:

```bash
python3 -m unittest discover -s scripts/connectors/tests -t scripts -v
```
