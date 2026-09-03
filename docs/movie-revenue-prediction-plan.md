# Feature plan: movie revenue prediction — data collection, missing-value handling, model comparison

Verified against the live `aura` Postgres database and `scripts/movie_revenue_impact_model.py`
on 2026-09-03 (every table/column/number below was confirmed with `\d`, `information_schema`,
and sample queries — not just read from code). Feed the prompts to Claude Code roughly in the
order at the bottom; several are independent of each other.

Extensibility is a first-class goal here, not an afterthought: Feature 2 turns "add a new
predictive parameter later" into a data-registration step (a table row plus data), not a code
change, so future factors — whatever they turn out to be — plug into the same pipeline without
touching `feature_columns()`/`assemble_features()` by hand each time.

## Ground truth this plan is built on

- **Two disconnected subsystems today.** The Java/Spring Boot app (`com.lit.fire.flame`) is a
  social-media marketing intelligence service; it only touches movies indirectly, via
  `ConflictBalanceService`/`NarrativeNoveltyService` writing two enrichment scores onto
  `movies_data_collection`. All the actual revenue-prediction work lives in one standalone
  script, `scripts/movie_revenue_impact_model.py` (1,372 lines) — nothing in the Java app calls
  it, schedules it, or serves its output. `output/*.csv`/`.json` are static files nothing reads.
- **`movies_data_collection` has 544,424 rows**, PK `(movie_name, release_date, language)`, live
  columns: `movie_name, release_date, id, rating_10, genre, language, release_day,
  gdp_usd_billions, inflation_rate_pct, release_event_type, release_event_name,
  release_event_detail, country, revenue, runtime, budget, production_companies, runtime_mins,
  directors, trailer_release_date, teaser_release_date, first_song_release_date,
  trailer_days_to_release, teaser_days_to_release, song_days_to_release, trailer_views,
  teaser_views, song_views, trailer_comments, teaser_comments, song_comments,
  youtube_last_checked` (32 columns total).
- **Live bug: the script is currently broken.** `MOVIES_SQL` selects `genres`, `imdb_rating`,
  `conflict_balance_score`, `narrative_novelty_score` — **none of these four columns exist** on
  the live table (confirmed via `information_schema.columns`). Running the script today throws
  `psycopg2.errors.UndefinedColumn`. This is Feature 0, and it blocks everything else.
- **This is a global table, not India-only.** Release years span 1918–2028; language breakdown:
  english 247,059, japanese 36,118, french 28,778, german 27,285, spanish 27,000, chinese 17,695,
  portuguese 15,841, italian 11,286, **hindi 10,621** (9th), then korean/russian/etc. `genre`
  exists as-is (not `genres`) as a single delimited text field.
- **GDP and inflation are already solved.** `gdp_usd_billions` is filled on 490,987/544,424 rows
  (90.2%), `inflation_rate_pct` on 488,519 (89.7%) — the user's ask for "GDP/inflation rate at
  time of release" is already joined per-row in the schema. No new sourcing needed there; only
  backfilling the remaining ~10% gap (Feature 3) is left.
- **Budget/revenue disclosure is the dominant data problem, and it's severe.** 513,198/544,424
  rows (94.3%) have `budget` null or 0; 525,637 (96.6%) have `revenue` null or 0. This is exactly
  the concern the user raised ("some movies may not have mentioned their budget/revenue,
  possibly because it did not do well") — but at a much larger scale than "some": it's the
  overwhelming majority of the corpus, and it is almost certainly **MNAR** (missing not at
  random: obscure/low-budget/foreign-language films are both less likely to have tracked
  financials *and* more likely to have underperformed) rather than random. Feature 4 addresses
  this directly.
- **Currency is already normalized to USD**, verified by sampling both a US bucket (*The Devil
  Wears Prada*: budget $35,000,000 / revenue $326,706,115) and a Hindi/Indian bucket (*Vivah*:
  budget $2,237,446 / revenue $11,097,733) — both are plausible USD-equivalent figures, not raw
  INR-crore numbers. Safe to treat `budget`/`revenue` as one consistent USD scale across markets.
- **The script currently throws away 9,033 usable non-Indian rows for free.** Of rows with
  `budget > $10k AND revenue > $10k`: 2,083 are Indian-market, but **9,033 are non-Indian**
  (mostly US/UK), and `movie_revenue_impact_model.py`'s `MOVIES_SQL`/`filter_non_indian_productions`
  hard-filters to Indian language/country only, so the current model trains on n=1,436 (after
  further dedup) when ~11,000+ clean rows already sit in the same table unused. This is the
  single highest-leverage change available, and it requires zero new data collection — Feature 0.
- **`actors_data_collection` has 62,413 rows, 9,896 distinct actors**, columns: `actor_name,
  movie_name, release_date, language, genre, director, rating, votes, runtime, role_position,
  character_name, awards, streaming_platform, status, sacnilk_url, kulfiy_url, fandango_url`.
  It already carries a **partial per-row source-URL pattern**: `sacnilk_url` filled on 729 rows,
  `kulfiy_url` on 6,492, `fandango_url` on 16,800 (of 62,413) — clear evidence of prior intent to
  scrape per-movie/actor pages from named sources. Sacnilk (sacnilk.com) is a known Indian
  day-wise box-office tracker; Fandango is a known US ticketing/box-office site. **What "Kulfiy"
  actually is isn't documented anywhere in this repo — confirm its purpose with whoever populated
  these URLs before building a connector for it (Feature 1).** The user's ask for "maintain a
  list of URLs to auto-collect from" should generalize this existing three-column pattern into a
  proper registry, not invent a parallel one.
- **Current model comparison** (`output/model_comparison.json`, n=1,436, India-only, 5-fold CV,
  time-respecting split): **GradientBoostingRegressor wins** — 49.4% of predictions within 50% of
  actual revenue, median absolute % error 51.1%. Ridge is close second (46.1% / 54.4%),
  HistGradientBoostingRegressor close third (49.2% / 50.7%). **`MLPRegressor` — the neural net —
  is currently the worst of the four**: 37.7% within 50%, median error 66.4%. This directly
  bears on the user's assumption that a neural net is the way to go: on today's data/features, it
  isn't, and Feature 8 explains the likely reason (small n, mostly-numeric features, no entity
  embeddings, default hyperparameters) and how to give it a fairer shot later. The formula
  baseline (hand-built B0 × Π(1+δᵢ)) trails everything at 15.5% within 50%.
- **The 80-factor business catalogue already exists** (`FACTOR_CATALOG` in the script, categories
  Narrative/Cast/Production/Marketing/Timing/Legal/Financial) with a `measurable: bool` flag per
  factor — but it's a **hardcoded Python list**: adding a new factor today means editing constants
  deep in the script and, separately, hand-adding it to `feature_columns()`. Only **12 of 80** are
  currently computed from real data; the other 68 report an unmodified literature-prior band
  tagged `source="prior_literature"`. This catalogue is the master checklist for "what other
  factors would move the needle" — new work should close items on this list. `output/factor_impact_scores.csv`
  is the current state of that list. Feature 2 turns this hardcoded list into a live, appendable
  registry — the direct answer to "let me add more parameters later without touching the code."
- No Kaggle ingestion, no scheduled retraining, and no Java-side serving of predictions exist yet.

---

## Feature 0 — Fix the broken query, then stop discarding 9,033 usable non-Indian rows

**Why first:** it's a live bug blocking any use of the script today, and fixing the market filter
multiplies usable training data ~6–8x before any new data-collection work is justified.

**Prompt:**
> In `scripts/movie_revenue_impact_model.py`:
> 1. `MOVIES_SQL` selects `genres, imdb_rating, conflict_balance_score, narrative_novelty_score`,
>    none of which exist on the live `movies_data_collection` table (confirm with
>    `information_schema.columns` against your own DB first). Make the column list dynamic:
>    query `information_schema.columns` for `movies_data_collection` at startup, build `SELECT`
>    from the intersection of a wanted-columns list and what actually exists, and have
>    `dedupe_movies`'s `_completeness` scoring and any factor computation that reads
>    `conflict_balance_score`/`narrative_novelty_score` degrade gracefully (treat as always-NaN)
>    when the column is absent, rather than crashing. This also protects the script against
>    future schema drift instead of hard-failing again.
> 2. Add a `--market {india,global,all}` CLI flag, default `all`. Today `filter_non_indian_productions`
>    unconditionally drops every non-Indian row; gate that call behind `args.market == "india"`.
>    For `global`/`all`, keep every row passing the existing budget/revenue/year floors regardless
>    of language/country.
> 3. Add a `market` categorical feature (Indian vs. non-Indian, or a coarser
>    `country`-derived bucket if cardinality is too high) to `feature_columns()` so a pooled model
>    can learn market-specific baselines instead of assuming India and the US behave the same.
>    Gate the India-specific calendar heuristics — `compute_holiday_window_raw`,
>    `compute_exam_season_raw`, `compute_ipl_season_raw`, `compute_summer_window_raw` (factors
>    61/63/65/66, all keyed to the Indian festive/exam/IPL calendar) — to only apply when
>    `market == "india"`; leave them at the neutral/unscored value otherwise rather than wrongly
>    applying an Indian calendar heuristic to a US or Japanese release.
> 4. Re-run `compare_models()` three ways and record the results in the report: (a) India-only as
>    today (n≈1,436, baseline for comparison), (b) global pooled with the new `market` feature,
>    (c) two fully separate per-market models. Keep whichever of (b)/(c) wins on
>    `within_30pct`/median-abs-%-error — don't assume pooling helps without checking; report both
>    to `output/model_comparison.json` under new keys (`pooled_global`, `per_market_india`,
>    `per_market_other`) instead of overwriting the existing India-only numbers, so the
>    improvement (or lack of it) is visible in the diff.

---

## Feature 1 — Generalize the per-row URL pattern into a data-source registry + connector framework

**Why:** this is the "give it a URL and it auto-collects, maintain a list of URLs" part of the
ask. `actors_data_collection.sacnilk_url/kulfiy_url/fandango_url` already show the intent but
require a new hardcoded column for every new source — the registry removes that.

**Prompt:**
> Add a new Postgres table `data_sources`: `id serial primary key, entity_type text check
> (entity_type in ('movie','actor')), entity_key text` (movie: `movie_name||'|'||release_date||
> '|'||language`; actor: `actor_name`), `source_name text, url text, connector_type text check
> (connector_type in ('html_scrape','api','kaggle_csv')), field_mapping jsonb, last_fetched_at
> timestamptz, last_status text, raw_payload jsonb`. Backfill it once from the existing
> `sacnilk_url`/`kulfiy_url`/`fandango_url` columns on `actors_data_collection` (one `data_sources`
> row per non-null URL, `source_name` = the column's prefix) — keep those three columns in place
> for backward compatibility rather than dropping them.
>
> Add `scripts/connectors/` with a `Connector` protocol (`fetch(url: str) -> dict`) and three
> implementations:
> - `HtmlScrapeConnector`: `requests` + `BeautifulSoup`, driven by a per-source CSS-selector map
>   read from `field_mapping` (so a new HTML source is a config row, not new code). Respect
>   `robots.txt` and add a configurable delay between requests to the same host.
> - `ApiConnector`: generic REST GET, JSON path extraction via `field_mapping` (dot-path per
>   target field), for sources with a real API (TMDB, World Bank, yfinance-style feeds).
> - `KaggleDatasetConnector`: wraps the `kaggle` CLI (`kaggle datasets download -d <slug>`,
>   requires `~/.kaggle/kaggle.json` credentials — document that setup step in a README under
>   `scripts/connectors/`), unzips, loads the target CSV with pandas, and applies `field_mapping`
>   (source CSV column → `movies_data_collection`/`actors_data_collection` column) before upsert.
>
> Add `scripts/collect_data.py`: `python3 collect_data.py --source sacnilk --entity-type movie
> [--dry-run]` — loads matching `data_sources` rows, calls the right connector, upserts the
> mapped fields via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (for any target column that
> doesn't exist yet) + `UPDATE ... WHERE (movie_name, release_date, language) = (...)`, and writes
> back `last_fetched_at`/`last_status`/`raw_payload`. Log a per-source summary (rows attempted,
> succeeded, failed) at the end.
>
> Add a unit test per connector type using a small local HTML/JSON fixture (no live network
> calls in tests).
>
> Note the division of concerns with Feature 2: `data_sources` governs *where raw data comes
> from*; Feature 2's `factor_definitions` governs *which columns the model actually trains on*.
> A `data_sources` row can exist with no corresponding model feature yet (raw metadata pulled in
> but not yet used), and a `factor_definitions` row can exist with no single `data_sources` row
> behind it (a derived or hand-entered factor). Keep them as two separate tables, not one.

---

## Feature 2 — Factor registry: make new parameters pluggable without touching code

**Why:** this is the direct answer to "I want to add more parameters in the future and have the
code use them." Today a new factor means editing the hardcoded `FACTOR_CATALOG` list *and*
`feature_columns()`/`assemble_features()` by hand, in two places, every time. This feature makes
"add a parameter" a data-entry operation: insert one row describing it, supply the values, and
the next scheduled training run picks it up automatically.

**Prompt:**
> Add `factor_definitions` (the live, queryable replacement for the hardcoded `FACTOR_CATALOG`
> list): `factor_key text primary key, name text, category text, direction text check (direction
> in ('Positive','Negative','Bidirectional')), stated_min numeric, stated_max numeric, data_type
> text check (data_type in ('numeric','boolean','categorical')), status text check (status in
> ('candidate','active','deprecated','explanatory_only')) default 'candidate', source_table text,
> source_column text, computation_type text check (computation_type in ('raw_column','derived_sql',
> 'derived_python_fn','eav')), derivation_ref text, added_at timestamptz default now(), added_by
> text, notes text`. `explanatory_only` marks a factor that describes a property of the finished
> film (e.g. `twist_effectiveness`) that can never be known pre-release, however well-measured —
> useful for a future retrospective/explainer report, never for the prediction model; treat it as
> a fourth, permanent status alongside the promotable `candidate → active` path, not a synonym for
> `deprecated`. Write a one-time migration that inserts today's 80 `FACTOR_CATALOG` entries
> into this table, with the 12 currently-measurable ones set `computation_type='derived_python_fn'`
> and `derivation_ref` pointing at their existing function name (e.g. `compute_holiday_window_raw`)
> so behavior doesn't change — verify by re-running `compare_models()` before/after this migration
> and confirming identical output.
>
> Add `movie_factor_values(movie_key text, factor_key text references factor_definitions,
> value_numeric numeric, value_text text, computed_at timestamptz default now(), primary key
> (movie_key, factor_key))` — a generic overflow table (`movie_key` = the same
> `movie_name||'|'||release_date||'|'||language` composite used elsewhere) for factors that don't
> warrant a dedicated column: a one-off business-supplied score, a CSV someone hands you next
> quarter, an experimental signal being trialed before it earns a real column. This is what makes
> "collect data in the future and provide it to the code" literal — a value can land here with
> zero schema migration.
>
> Rework `feature_columns()`/`assemble_features()` to be registry-driven: for every
> `factor_definitions` row with `status='active'`, resolve its value from (a) `source_table.source_column`
> directly if `computation_type='raw_column'`, (b) a join against `movie_factor_values` if
> `computation_type='eav'`, or (c) a call into a small `DERIVED_FACTOR_FNS: dict[str, Callable]`
> registry keyed by `derivation_ref` if `computation_type='derived_python_fn'` (this is where
> today's hardcoded calls like `compute_holiday_window_raw` move to, unchanged in logic, just
> invoked by name instead of hardcoded into `build_measurable_features`). `status='candidate'`
> factors are still computed and reported (coverage %, correlation with `ln(revenue)`) in the
> output report, but excluded from the trained feature set — a safe staging area to see whether a
> new factor looks useful before trusting it in the live model.
>
> Add a coverage guard: a candidate/active factor is only included in a given training run once
> its non-null coverage on that run's rows clears `--min-feature-coverage` (default 5%), so a
> brand-new sparsely-populated factor doesn't get force-fit into every run before enough data
> exists for it to mean anything, but is still visible in the coverage report from day one.
>
> Add two registration paths, so adding a factor never requires a script edit:
> - `scripts/register_factor.py --key ticket_price_atp --name "Average Ticket Price" --category
>   Financial --data-type numeric --status candidate --source-table ticket_price_index
>   --source-column atp_usd` — upserts one `factor_definitions` row.
> - Java: `POST /api/admin/factor-definitions` (create/update), `GET /api/admin/factor-definitions`
>   (list with status), `PATCH /api/admin/factor-definitions/{key}/status` (promote
>   candidate→active or deprecate), in a new `FactorDefinitionController`, so a non-Python
>   teammate can register or promote a factor from the API. Add
>   `POST /api/admin/factor-values` (bulk upsert into `movie_factor_values`) as the equivalent
>   "hand the system a spreadsheet of scores" path.
>
> Finally, have every row written to `model_comparison_history` (Feature 11) record the exact
> `factor_key` list used for that run (as a `jsonb` array) — this is what makes "did adding
> parameter X actually help" answerable empirically per addition, not just visible in aggregate
> accuracy drift.

---

## Feature 3 — Register bulk external datasets through Features 1/2 (where to actually get the data)

**Why:** closes the cast/crew, rating, and budget/revenue-coverage gaps at scale, and is the
direct answer to "collect from Kaggle and other sources."

**Prompt:**
> Register the following as `data_sources`/`sources.yaml` entries, each with its own
> `field_mapping`, and run `collect_data.py` per source once Feature 1 lands. For every new column
> a source fills, add a matching `factor_definitions` row via Feature 2 (`status='candidate'`
> until coverage/correlation justifies promoting it to `active`) — don't just append the column to
> the SQL `SELECT` by hand the way the current 12 measurable factors are wired in:
> - **TMDB 5000 Movie Dataset** (Kaggle `tmdb-5000-movie-dataset`) — `budget, revenue, genres`
>   (JSON), `cast`/`crew` (JSON), `popularity`, `vote_average`. Best clean fill for the
>   `genres` column this script already expects but the live table lacks, mostly Hollywood
>   coverage.
> - **The Movies Dataset** (Kaggle `rounakbanik/the-movies-dataset`) — `movies_metadata.csv`
>   (45k movies) + `credits.csv` (full cast/crew per movie) + `keywords.csv` + `ratings.csv`
>   (26M user ratings). Largest single fill for cast job history (feeds Feature 5) and a
>   keyword signal usable as a rough proxy for factor 4 (genre-template adherence).
> - **IMDb non-commercial datasets** (`datasets.imdbws.com` — official IMDb export, free for
>   personal/research use, updated daily, *not* Kaggle) — `title.basics.tsv.gz`,
>   `title.ratings.tsv.gz` (fills `imdb_rating`), `title.principals.tsv.gz` (accurate cast
>   ordering — better `role_position` source than what's currently in `actors_data_collection`),
>   `name.basics.tsv.gz`.
> - **World Bank Open Data API** (`api.worldbank.org`, no key needed) — use directly (not
>   Kaggle) to backfill the remaining ~10% gap in `gdp_usd_billions`/`inflation_rate_pct`, and to
>   build a `year → country → USD FX rate` table if any residual non-USD entries turn up during
>   Feature 0's currency spot-check.
> - **BSE Sensex / NSE Nifty daily close** — via `yfinance` (`^BSESN`, `^NSEI` tickers, no key,
>   free) rather than a Kaggle mirror, since it needs to stay current. New: this is the user's
>   Sensex ask; nothing in the schema covers it today.
> - **Sacnilk** (sacnilk.com) — already partially wired via the 729 existing `sacnilk_url` rows;
>   day-wise Indian box-office tracker, useful for corroborating/backfilling `revenue` on Indian
>   titles at finer granularity than TMDB-style totals. Scrape via `HtmlScrapeConnector`.
> - **The Numbers** (the-numbers.com) — budget/domestic/international gross pages; more complete
>   for older/smaller titles than TMDB. Check their terms before bulk scraping; keep request
>   volume low and cached (`raw_payload` in `data_sources` avoids re-fetching).
> - **Box Office Mojo** (boxofficemojo.com) — do **not** scrape live (their terms discourage
>   bulk scraping); use one of the periodic community Kaggle mirrors instead if daily/weekend
>   market-level breakdowns are needed later.
> - **Ticket price index** — no clean scrape-ready per-city dataset exists. Hand-curate a small
>   `ticket_price_index(period_start, period_end, region, city_tier, atp_usd, source_url)` table
>   from PVR Inox's public quarterly investor-relations presentations and the FICCI-EY "Media &
>   Entertainment" annual report (both public PDFs with average-ticket-price tables) — treat as
>   a manually-updated quarterly reference, not an automated connector, and note the source PDF
>   URL per row for auditability.
> - Confirm what **`kulfiy_url`** actually points to (ask the team/check a sample URL manually)
>   before writing a connector for it — don't guess at its schema.
> For each dataset, entity-resolve to `movies_data_collection` by `(title, release_year)`
> fuzzy match, reusing the existing `pg_trgm` index (`idx_movies_data_collection_trgm`) the same
> way `NarrativeNoveltyService`/search already do, rather than requiring exact string matches.

---

## Feature 4 — Model missing budget/revenue as MNAR, not by dropping 94–97% of rows

**Prompt:**
> Add a two-stage model to `movie_revenue_impact_model.py`:
> - **Stage A — disclosure classifier.** Train a binary classifier (start with
>   `sklearn.linear_model.LogisticRegression`, compare against `GradientBoostingClassifier`)
>   predicting `has_financials = (budget > 0 AND revenue > 0)` from **pre-release-only, always-
>   available** features (genre, language, country, release year, director prior-film count from
>   Feature 5, franchise flag) on the **full** ~544k-row corpus, not just the disclosed subset.
>   Report its own accuracy/AUC and feature importances as a first-class output — "is this movie
>   even in a class of films whose revenue tends to get tracked" is a useful signal on its own
>   (e.g. exposed to API consumers as a `disclosure_likelihood` field), not just internal plumbing.
> - **Stage B — revenue regressor**, same as today, trained on the disclosed subset, but add an
>   **inverse-probability weighting** option: weight each training row by `1 / P(disclosed)` from
>   Stage A (clipped to avoid extreme weights) so the regressor doesn't implicitly assume "the
>   pattern that holds for films whose financials get published also holds for the long tail that
>   never gets tracked." Compare weighted vs. unweighted CV accuracy and keep whichever wins.
> - Add a `confidence_band` to every row of `output/movie_revenue_predictions.csv`: widen the
>   existing bootstrap interval when Stage A's `disclosure_likelihood` for that title is low
>   (few comparable disclosed films) and narrow it when high, so downstream consumers can tell
>   "well-supported estimate" from "thin-comparables guess" instead of getting one flat number.

---

## Feature 5 — Cast/crew track-record factors (the two the user named specifically)

**Prompt:**
> `actors_data_collection` already supports a self-join over `(actor_name, release_date)`
> (this is how `star_overexposure`/`R_star` are computed today) — extend it with:
> - `lead_prior_films_count`: count of the lead actor's (`role_position` = 1, or the minimum
>   available per movie) credited films with `release_date` strictly before this movie's, from
>   `actors_data_collection`. Directly answers "how many movies has the cast acted in prior to
>   this release."
> - `lead_prior_film_hit_flag` / `lead_prior_film_revenue_ratio`: for the lead's most recent
>   prior film (by `release_date`), whether `revenue / budget` exceeds a hit threshold (start at
>   1.5x, make it a named constant) and the raw ratio. Directly answers "was the lead's previous
>   movie a success." Only computable for prior films that themselves have `budget > 0 AND
>   revenue > 0` — for the rest, leave null rather than guessing, and let Feature 4's
>   `disclosure_likelihood` explain why it's null.
> - `director_prior_films_count` / `director_prior_hit_rate`: same pattern keyed on `directors`.
> - `ensemble_avg_prior_hit_rate`: same computed across the full credited cast, weighted by the
>   existing `role_weight()` function.
> - No leakage: every "prior" comparison must use `release_date` strictly less than the target
>   row's `release_date` — reuse the existing date-parsing helpers (`parse_release_date`) rather
>   than re-deriving them.
> - Register each as a `factor_definitions` row via Feature 2 (`computation_type='derived_python_fn'`,
>   `status='candidate'` → promote to `active` once coverage looks reasonable) mapped onto the
>   existing catalogue entries 17 (`fanbase_mobilization`) and 20 (`director_brand_equity`) —
>   this is the registry doing its job instead of hand-editing a hardcoded list, moving the
>   catalogue from 12/80 to 14/80 measurable.
> - Persist via the same `ensureSchema()`-style `ALTER TABLE ADD COLUMN IF NOT EXISTS` +
>   batched `UPDATE` pattern `ConflictBalanceService`/`NarrativeNoveltyService` use in Java —
>   apply it here from Python so both languages follow one write convention against the same
>   table, and document in a comment that this is the Python-side equivalent of that pattern.

---

## Feature 6 — New macro factors: Sensex + ticket-price index

**Prompt:**
> Add a small reference table `market_index_daily(index_name text, trade_date date, close
> numeric, primary key (index_name, trade_date))`, populated via `yfinance` (`^BSESN` for
> Sensex; add `^NSEI` too since it's free from the same call) for at least the 1990–present range
> this corpus needs. Compute two new per-movie features by joining on `release_date` (nearest
> prior trading day): `sensex_close_at_release` and `sensex_90d_change_pct` (percent change over
> the 90 days before release — a proxy for consumer discretionary sentiment at launch). Only
> populate these for rows where a market's release date plausibly correlates with Indian investor
> sentiment (start with the Indian-market subset from Feature 0's `market` flag; leave null
> elsewhere rather than joining Sensex to a Japanese release).
>
> Separately, hand-curate `ticket_price_index(period_start date, period_end date, region text,
> city_tier text, atp_usd numeric, source_url text)` from the PVR Inox / FICCI-EY sources named
> in Feature 3 (a handful of rows updated quarterly, not a live connector). Join each movie to
> the row covering its `release_date`, inferring `city_tier` coarsely from `language`/`country`
> the same documented-approximation way `FESTIVE_WINDOWS` already handles Indian festival dates
> — note in a comment that this is intentionally coarse.
>
> Both factors are genuinely new — neither has a slot in the existing 80-factor catalogue (the
> closest entries are marketing/timing factors, none of which cover broad market-sentiment or
> ticket-price level). Register them as new `factor_definitions` rows via Feature 2
> (`factor_key`s like `sensex_sentiment`, `ticket_price_level`) rather than overloading an
> existing catalogue entry, and flag to whoever owns the business catalogue that this is an
> addition outside the original 90 slots — exactly the kind of future-parameter addition Feature
> 2 exists to make painless.

---

## Feature 7 — Legal & financial signal collection: certification, screen counts, tax status, a shared legal-event feed

**Why:** a closer look at the Legal/Financial catalogue found that, unlike the narrative-quality
factors, almost none of these are truly unquantifiable — most are real, discrete, publicly
reported facts or events. A few genuinely aren't available anywhere (handled separately below).

**Prompt:**
> Two sub-items need **zero new data** — compute them directly from columns already in the schema
> and register via Feature 2, no connector required:
> - `joint_production_partnerships`: count of comma-separated entries in
>   `movies_data_collection.production_companies`. Real column, real signal, today.
> - A dubbing/localization-**breadth** proxy for `subtitle_dubbing_quality` (quality itself isn't
>   measurable, breadth is): count of sibling rows sharing the same `(movie_name, release_date)`
>   but different `language` — i.e. how many simultaneous language releases a title had. Note
>   this signal is currently destroyed by `dedupe_movies()`, which collapses those rows to one
>   before training; compute the sibling count *before* dedup and carry it through as a column,
>   don't try to reconstruct it after.
>
> Three sub-items are quantifiable via sources already named elsewhere in this plan — register as
> `data_sources` (Feature 1) + `factor_definitions` (Feature 2):
> - `cbfc_rating`: TMDB's per-country certification field (same API named for `graphic_violence`
>   in Feature 3) or CBFC records directly. Certification happens before release, so this is
>   genuinely pre-release-available, not retrospective.
> - `screen_count_allocation`: Indian trade sites (Sacnilk — already a named source; Box Office
>   India, Koimoi) routinely report opening-day/weekend screen counts. Real, credible, worth
>   prioritizing over the items below.
> - `tax_exemptions`: when a state grants a film tax-free status it's public policy, reported
>   explicitly by entertainment press ("X declared tax-free in [state]") — a targeted keyword news
>   search (`"<movie name>" AND "tax free"`) gives a real signal, not a guess.
>
> One new shared connector covers seven more factors at once — build a single "legal/controversy
> news-event feed" (GDELT's free public API, or a targeted news search, keyed on movie title +
> release window) rather than seven one-off detectors, since they're all the same shape ("did a
> searchable news event of type X happen around this movie"): `state_bans`, `pre_release_leak`,
> `title_ownership_disputes`, `copyright_claims`, `distribution_disputes`,
> `name_similarity_disputes`, and the allegation-half of `plagiarism_remake_rights` (keyword sets
> differ per factor, connector logic doesn't). **Set expectations accordingly in the script's
> docstring**: these are rare events — most movies have zero legal drama — so even a perfectly
> accurate detector produces a mostly-zero flag with limited standalone lift on model accuracy.
> Build it because it's cheap once the shared connector exists and Feature 8's SHAP output will
> show empirically whether any of the seven actually move predictions, not because it's expected
> to be a top driver.
>
> The remake-*rights* half of `plagiarism_remake_rights` is separate and higher-confidence: detect
> via TMDB's "belongs to collection"/keyword metadata or synopsis NLP for "official remake of
> ___" phrasing — a real pre-release fact, not a rare event.
>
> `certification_delays` is the weakest item here — deprioritize it. It needs a data point this
> schema doesn't have anywhere: an *originally announced* release date to compare against the
> actual one (tracking date-shift announcements from entertainment news), and even then it's
> confounded with ordinary clash-avoidance rescheduling, not just certification issues. Only build
> this if the seven-factor news feed above already proves useful.
>
> `minimum_guarantee_deals`, `outright_purchase_sales`, `pa_commitments` are real financial deal
> terms but **not systematically public** — trade press occasionally discloses numbers for
> high-profile releases only. Treat these as optional, low-priority, hand-curated entries into
> `movie_factor_values` (same pattern as the ticket-price index) for a handful of tentpole titles
> a year if you decide it's worth it — not worth an automated connector given how rarely they're
> disclosed.
>
> `kdm_lockout`, `high_interest_financing`, and `producer_debt_solvency` are **not included here**
> — see the Factor Inventory's Group D″ below for why they're a different case entirely.

---

## Feature 8 — Model comparison harness: add gradient-boosted-tree libraries, give the NN a fair shot, add ensembling

**Prompt:**
> `compare_models()` currently only tries `Ridge`, `GradientBoostingRegressor`,
> `HistGradientBoostingRegressor`, `MLPRegressor` (all sklearn). Add to the model registry:
> - `XGBRegressor` (xgboost) and `LGBMRegressor` (lightgbm) — both are the de facto standard for
>   this exact problem shape (tabular, mixed numeric/categorical, thousands of rows) and
>   routinely beat sklearn's GBR/HistGBR; add both, they're cheap to try.
> - `CatBoostRegressor` (catboost) — handles high-cardinality categoricals (director, lead actor,
>   genre, language) natively without one-hot/target-encoding, which is worth trying given how
>   categorical-heavy this feature set is becoming as Features 5/6/7 add more.
> - Add `pip install xgboost lightgbm catboost` to the script's module docstring alongside the
>   existing `pip install` line.
> Keep the existing time-respecting `time_based_split`/`cross_validated_predictions_for`
> machinery unchanged — it already avoids leaking future films into training, which matters more
> than which estimator is plugged in. Since `feature_columns()` is now registry-driven (Feature
> 2), a newly-promoted `active` factor should require no changes here to be picked up by every
> model in the comparison — that's the whole point of making the registry the source of truth.
>
> On the neural net specifically: set the user's expectation directly in the script's own
> docstring — on today's ~1,436-row India-only, mostly-numeric feature set, `MLPRegressor` is
> the *worst* of the four tried (37.7% within 50%, vs. 49.4% for the winning GBR), which is a
> normal outcome for small-n, mostly-numeric tabular problems (trees generally win there; NNs
> need either much more data or structure trees can't exploit, like learned entity embeddings).
> After Feature 0 grows the corpus to ~11k rows and Features 5/6/7 add more categorical/relational
> structure, revisit with a small Keras/PyTorch MLP that **embeds** `director`, `lead_actor`,
> `genre`, `language` as learned low-dimensional vectors instead of one-hot/target-encoding —
> that's the specific place NNs tend to earn an edge over trees on this kind of data, not just
> adding depth to the current architecture.
>
> Once the best 2–3 base models are chosen empirically, add a
> `sklearn.ensemble.StackingRegressor` over them and report whether it beats the single best base
> model on the same CV harness — usually a small free win once base models exist.
>
> **Replace the single-model partial-dependence effect measurement with SHAP.** Today,
> `fit_effect_model`/`compute_factor_effects` default to `model_name="mlp_neural_net"` — i.e. the
> script already uses a neural net to derive each factor's calibrated impact, via a
> partial-dependence sweep (hold every other feature at its real value, swing just this one from
> `stated_min` to `stated_max`, read the average change in predicted `ln(revenue)`). That's a
> reasonable sensitivity measure but it's tied to one specific model and one specific sweep
> method. Add `pip install shap` and compute SHAP values (`shap.TreeExplainer` for the tree
> models, `shap.Explainer` generically for a fair side-by-side against the MLP/Ridge) on top of
> whichever model wins this feature's comparison — SHAP is the standard, model-agnostic way to
> answer "how much did each parameter move this outcome," both in aggregate (mean |SHAP| per
> `factor_key`, corpus-wide) and per movie (which specific factors pushed *this* title's estimate
> up or down). Report `mean_abs_shap` per `factor_key` alongside `calibrated_min`/`calibrated_max`
> in `factor_impact_scores`, and keep `compute_factor_effects`'s partial-dependence method only as
> a documented fallback for estimators SHAP doesn't cleanly support. Store each prediction's
> top-5 SHAP drivers alongside it in `movie_revenue_predictions` (Feature 10) so a served
> prediction can answer "why," not just "how much."
>
> Re-run and diff `output/model_comparison.json` after Features 0/3/5/6/7 land so the accuracy
> trend across changes is visible in git history, not just the current snapshot.
>
> **Persist the winning model as an artifact, not just an in-process object.** Every model in
> `compare_models()` today is fit fresh inside a CV loop and discarded — there is no `joblib`
> or `pickle` call anywhere in this script, so nothing survives past a single run. Once the
> champion model is chosen, do one final fit on **all** available labeled (disclosed-revenue)
> rows — not a CV fold — and save `joblib.dump({"model": model, "scaler": scaler,
> "feature_columns": cols, "factor_keys_used": [...], "model_name": ..., "trained_at": ...,
> "n_training_rows": ...}, f"models/revenue_model_{model_version}.joblib")`, where
> `model_version` is a timestamp or incrementing integer. This artifact is what Feature 9's
> single-movie prediction and Feature 10's serving both load — training happens once per
> scheduled run (Feature 10's weekly cron), inference happens on demand without retraining.

---

## Feature 9 — Predict a single upcoming (unreleased) movie

**Why:** this is the direct answer to "I want to see how it predicts an upcoming movie release."
Nothing built so far can do this yet. `assemble_features()` unconditionally computes
`df["ln_revenue"] = np.log(df["revenue"].astype(float))` — every existing code path, including
the "predictions" written by Feature 10, requires a **known** revenue, because they're all
out-of-fold backtests over already-released movies. A movie that hasn't come out yet has no
revenue by definition, so it needs a genuinely separate inference path, not a rerun of the
training pipeline.

**Prompt:**
> Add `scripts/predict_movie.py`, loading the artifact Feature 8 persists
> (`models/revenue_model_{version}.joblib`, default to the most recent by filename/timestamp
> unless `--model-version` is given). Two input modes:
> - `--movie-name "X" --release-date 2027-01-15` — looks the row up in `movies_data_collection`
>   (for an announced title already catalogued, e.g. via Feature 1's connectors) and pulls its
>   known attributes.
> - `--from-json path.json` — accepts a raw attribute dict for a title that isn't in the database
>   at all yet (budget, director, lead cast names, genre, language, planned release date, etc.) —
>   this is the common real case: an upcoming release you want a prediction for likely isn't fully
>   catalogued yet.
>
> Build a `build_inference_features()` function that mirrors `assemble_features()`'s feature
> computation **except** the `ln_revenue` line and anything downstream of it — every factor that
> depends only on pre-release-available data (budget, cast history via `actors_data_collection`
> self-joins per Feature 5, director track record, macro/Sensex snapshot at the *predicted*
> release date, etc.) computes the same way as training; nothing reads `revenue`. For features
> that depend on data only available closer to release (`trailer_views`, `song_views` — the
> movie may not have a trailer yet if release is far out), treat as missing and let the existing
> coverage-guard/imputation handling (Feature 2) do what it already does for any sparsely-covered
> factor — don't special-case "upcoming" beyond that.
>
> Output: `predicted_revenue` (from the loaded model), a `confidence_band` (reuse Feature 4's
> `disclosure_likelihood`-scaled bootstrap interval — an upcoming movie with a well-covered feature
> set gets a tighter band, a thin/early one gets a wider band, same logic as a historical movie
> with sparse comparables), and the top-5 SHAP drivers (Feature 8) explaining *why* — e.g. "high
> lead-actor prior hit rate (+), unusually long runtime (−)". Write the result to
> `movie_revenue_predictions` with `is_upcoming = true`, `actual_revenue = NULL`.
>
> Add `POST /api/marketing/movie-revenue-prediction/predict` (same controller as Feature 10)
> accepting the same attribute payload as `--from-json` in the request body, shelling out to
> `predict_movie.py --from-json -` (stdin) via `ProcessBuilder` and returning its JSON output
> synchronously — this is the on-demand "what would this movie make" query the product needs,
> as opposed to the batch weekly re-scoring Feature 10's scheduler does for the whole corpus.
>
> Add a test that feeds a synthetic upcoming-movie JSON (no `revenue` key at all) through
> `build_inference_features()` and confirms it produces a full feature row with no exception and
> no accidental `NaN`/`inf` propagating from a missing `revenue` column — this is exactly the
> bug class that would otherwise surface as a silent crash the first time someone tries this on a
> real unreleased title.

---

## Feature 10 — Serve predictions from the Java app, with a visible accuracy report

**Prompt:**
> Add `movie_revenue_predictions(movie_name text, release_date text, language text,
> predicted_revenue numeric, confidence_band_low numeric, confidence_band_high numeric,
> actual_revenue numeric, abs_pct_error numeric, is_upcoming boolean default false, model_name
> text, model_version text, factor_keys_used jsonb, generated_at timestamptz, primary key
> (movie_name, release_date, language))` and a `factor_impact_scores` table mirroring
> `output/factor_impact_scores.csv`'s columns. `actual_revenue`/`abs_pct_error` are null for a
> genuinely upcoming release (Feature 9) and populated for a backtested historical movie — the
> same table serves both cases, distinguished by `is_upcoming`, rather than needing two tables.
> Have `movie_revenue_impact_model.py` write to both (via `psycopg2`, alongside the existing CSV
> writers — keep the CSVs for offline analysis, add the tables for serving) instead of `output/*`
> being the only destination. `factor_keys_used` (from Feature 2's registry) makes every served
> prediction traceable to exactly which factors fed it — useful once the factor set keeps growing
> and a prediction from six months ago used a different feature set than one made today.
>
> Add `MovieRevenuePredictionController` (package `com.lit.fire.flame`, same thin-JDBC-read shape
> as `NarrativeNoveltyController`):
> - `GET /api/marketing/movie-revenue-prediction/{movieName}` — existing behavior, returns
>   whatever row(s) match, upcoming or historical.
> - `GET /api/marketing/movie-revenue-prediction/factor-impact-scores` — existing behavior.
> - **New — the direct answer to "let me see how accurate this is":**
>   `GET /api/marketing/movie-revenue-prediction/accuracy` returns the latest
>   `model_comparison_history` row (Feature 11: `within_20pct`, `within_30pct`, `within_50pct`,
>   `median_abs_pct_error`, `n_movies`, `run_at`) plus a paginated list of backtested rows
>   (`is_upcoming = false`) sorted by `generated_at desc`, each showing `predicted_revenue` next to
>   `actual_revenue` and `abs_pct_error` side by side — so accuracy is something you can look at
>   directly through the API/a future dashboard, not something buried in a JSON file in `output/`.
>
> Since the model itself is a Python job, not a Java service, add a
> `MovieRevenuePredictionScheduler` that shells out via `ProcessBuilder` (`python3
> scripts/movie_revenue_impact_model.py --db-host ... `, reusing the same connection details
> `DataSourceConfig` already loads from `secrets.txt`), on a weekly cron (box-office factors
> don't move day to day, unlike the existing daily `MarketingEnrichmentScheduler`), following the
> same `@EnableScheduling` + advisory-lock convention. Add `POST
> /api/admin/run-movie-revenue-model` (matching the existing `/api/admin/run-enrichment` trigger
> shape) and `GET /api/admin/movie-revenue-model-status` reporting last-run timestamp/exit
> code/log tail, capturing the subprocess's stdout/stderr to a log file.

---

## Feature 11 — Coverage & drift monitoring

**Prompt:**
> Add `GET /api/admin/factor-coverage` reading Feature 2's `factor_definitions` table's status
> counts (`candidate`/`active`/`deprecated`) and returning `{active_count, total_count, pct}`
> (today's equivalent: 12/80, 15%) so catalogue coverage is a live number that grows automatically
> as Features 3/5/6/7 (and anything registered afterward) promote new factors to `active`, instead
> of something only visible by reading the script's docstring.
> Change `write_outputs`/the Java write path to **append** a timestamped row to a
> `model_comparison_history` table (model_name, run_at, n_movies, within_20pct, within_30pct,
> within_50pct, median_abs_pct_error, factor_keys_used jsonb — from Feature 2/10) instead of
> overwriting `output/model_comparison.json` in place, so a future accuracy regression (bad data
> pull, upstream schema drift, a stale connector) shows up as a visible trend, and so you can
> answer "did adding factor X actually move accuracy" by diffing two `model_comparison_history`
> rows before/after that factor was promoted to `active`.

---

## Factor inventory: what's real today, what this plan makes real, and what's a placeholder

Verified directly against `feature_columns()`/`assemble_features()`/`FACTOR_CATALOG` in
`scripts/movie_revenue_impact_model.py` — this is not a re-description, it's the exact list. The
model trains on **17 numeric inputs today** (5 baseline anchors + 12 measurable factors). No
other row in the 80-factor catalogue reaches the model — the other 68 print a literature-supplied
number in the report and stop there.

### Group A — the 17 inputs feeding `compare_models()` right now

| # | Feature | What it actually is | Honesty note |
|---|---|---|---|
| 1 | `ln_budget_effective` | log of budget, present-valued via real `inflation_rate_pct`/`gdp_usd_billions` | Applies a fixed 77.5% haircut (business-supplied 75–80% range, not fitted) when a row has no trailer/teaser telemetry. Real budget, assumed haircut. |
| 2 | `r_star` | role-weighted average cast popularity | The *films-before* count per actor is real; the popularity curve mapping that count to a 0.10–0.99 score is a fixed business-supplied bucket table, not fitted from this data. |
| 3 | `r_director` | percentile rank of director's real prior-year mean revenue | Fully data-derived; neutral 0.5 fallback only when a director has <5 prior corpus films. |
| 4 | `r_concept` | same, keyed on primary genre | Same as #3. |
| 5 | `franchise_flag` | binary, from title-string sequel/prequel pattern matching | Real titles, heuristic string match — will miss inconsistently-named sequels. |
| 6 | `conflict_balance` | `movies_data_collection.conflict_balance_score` | **Not real on your live DB today** — that column doesn't exist (confirmed), so every row silently gets the literature band's midpoint (0.30) until `ConflictBalanceService` is actually run against this database. |
| 7 | `narrative_novelty` | `movies_data_collection.narrative_novelty_score` | **Same issue as #6** — currently a placeholder midpoint (0.375), not real signal, until `NarrativeNoveltyService` runs here. |
| 8 | `star_overexposure` | trailing-12-month count of cast's *other* releases | Real, from `actors_data_collection` self-join. |
| 9 | `excessive_runtime` | `runtime_mins` penalized above a threshold | Real column; the 160-minute cutoff is business-supplied, not fitted. |
| 10 | `budget_scale_efficiency` | budget's percentile rank within (genre, release-year) peers | Fully real/data-derived. |
| 11 | `trailer_teaser_impact` | `trailer_days_to_release` scored against day-window thresholds, boosted by real `trailer_views` | Real columns; the 30–45-day "optimal window" is business-supplied. |
| 12 | `first_single_timing` | `song_days_to_release` scored against a day-window | Real column; the 42–56-day "optimal window" is business-supplied. |
| 13 | `holiday_release_window` | real release date checked against a festival-date table | The festival dates themselves (`FESTIVE_WINDOWS`) are a fixed, hand-coded approximation — documented in-script as such, since lunisolar holidays move every year. |
| 14 | `box_office_clashes` | count of same-language releases within ±3 days | Fully real, computed from the corpus itself. |
| 15 | `exam_schedules` | real release month vs. a fixed Feb–Apr assumption | Business-supplied window. |
| 16 | `ipl_sporting_events` | real release month vs. a fixed Mar–May assumption | Business-supplied window. |
| 17 | `summer_vacation_window` | real release month vs. a fixed Apr–Jun assumption | Business-supplied window. |

Rows 1, 2, 9, 11, 12, 13, 15, 16, 17 pair a **real** underlying column with a **fixed
threshold/curve/window** the business team supplied rather than one this data has fitted — per
your note, those defaults are fine to keep for now since the model-comparison harness (Feature 8)
will tell you whether tightening them actually improves accuracy. Rows 6 and 7 are the one case
where the *entire value*, not just a threshold, is currently fabricated (a constant, because the
source column doesn't exist on your DB yet) — worth prioritizing `ConflictBalanceService`/
`NarrativeNoveltyService` actually running here, or the model is training on a constant for those
two "features."

### Group B — new real inputs this plan adds from data you already have (self-joins, no new collection)

- `market` (India vs. non-India) — Feature 0.
- `lead_prior_films_count`, `lead_prior_film_hit_flag`, `lead_prior_film_revenue_ratio`,
  `director_prior_films_count`, `director_prior_hit_rate`, `ensemble_avg_prior_hit_rate` — Feature
  5, all computed from real `actors_data_collection` self-joins. The 1.5x "hit" threshold is a
  starting default, same category as the rows above — fine per your note, tunable later.
- `disclosure_likelihood` — Feature 4's Stage A classifier output. This is a **model output**
  learned from your full 544k-row corpus, not a business-supplied prior.
- `joint_production_partnerships` (count of `production_companies` entries) and a
  dubbing/localization-**breadth** proxy for `subtitle_dubbing_quality` (count of sibling
  same-`(movie_name, release_date)` rows across `language`) — Feature 7, both computed from
  columns already on `movies_data_collection` today.

### Group C — new real inputs this plan adds, requiring new data (automated or manual, per Features 1/3/6/7)

- `genres` (properly filled), `imdb_rating`, more accurate cast ordering — via TMDB 5000 / The
  Movies Dataset / IMDb non-commercial exports (Feature 3, automated once built).
- `sensex_close_at_release`, `sensex_90d_change_pct` — via `yfinance` (Feature 6, automated).
- Ticket price / average-ticket-price-by-city-tier — via PVR Inox / FICCI-EY reports (Feature 6,
  **manual** — this is the one piece of the earlier plan that's manual by necessity, no
  scrape-ready source exists).
- `cbfc_rating`, `screen_count_allocation`, `tax_exemptions` — Feature 7, automated via sources
  already named for other factors (TMDB certification field, Sacnilk, entertainment-press keyword
  search).
- `state_bans`, `pre_release_leak`, `title_ownership_disputes`, `copyright_claims`,
  `distribution_disputes`, `name_similarity_disputes`, plagiarism *allegations* — Feature 7's
  shared legal/controversy news-event feed. Real and automated, but expect a mostly-zero, sparse
  signal — these are rare events, not steadily-available numbers like GDP.
- Remake-*rights* (split out of `plagiarism_remake_rights`) — Feature 7, via TMDB collection
  metadata/synopsis NLP, higher confidence than the allegation half above.
- `certification_delays` — Feature 7, lowest-confidence/deprioritized: needs a new
  announced-vs-actual release date data point this schema doesn't have yet.
- `minimum_guarantee_deals`, `outright_purchase_sales`, `pa_commitments` — Feature 7, **manual and
  optional**: real deal terms, but only occasionally disclosed by trade press for high-profile
  titles, so treat as a low-priority hand-curated addition (same pattern as ticket pricing), not
  worth an automated connector.
- Anything else you collect going forward, via Feature 2's `movie_factor_values` table /
  `register_factor.py` / `POST /api/admin/factor-values` — open-ended by design.

### Group D — NOT used to predict anything, today or anywhere in this plan (46 factors, literature-prior only)

These exist only in the qualitative report (`factor_impact_scores.csv`, tagged
`source="prior_literature"`). They never touch a prediction and won't until real data backs them
and Feature 2 promotes them to `status='active'`. Listed in full so nothing is silently implied:

**Narrative (1):** genre_template_adherence

**Cast (13):** persona_fit, fanbase_mobilization *(Feature 5 adds a related-but-distinct real
proxy — this exact catalogue slot stays prior-only)*, lead_chemistry, support_cast_credibility,
director_brand_equity *(same note as fanbase_mobilization)*, anti_hero_appeal, actor_controversy,
event_speech_impact, actor_vulnerability, multi_generational_appeal,
nostalgic_reunion, political_dialogue, cameo_appearances

**Production (13):** vfx_quality, sound_design, action_choreography, bgm_impact,
production_design_scale, cinematography, editing_pacing, period_authenticity,
flashback_animation, intrusive_song_placement, location_novelty, practical_vs_green_screen,
graphic_violence

**Marketing (13):** brand_extension_naming, viral_audio_trends, promotional_controversy,
on_ground_events, micro_video_campaigns, influencer_promotion, misleading_trailer,
bts_promo_content, countdown_posters, oversaturated_marketing, brand_partnerships,
dynamic_ticket_pricing, global_promo_tours

**Timing (5):** political_events, extreme_weather, ott_window_strategy, post_clash_spillover,
re_release_nostalgia

**Legal (0):** none — see Group C above. Closer review found every Legal factor has a real,
if sometimes sparse, quantification path; none belong in a pure literature-prior bucket.

**Financial (1):** multiplex_revenue_splits *(catalogue itself notes: even if data existed,
this one would leak the target — a post-hoc revenue decomposition, not a pre-release input; the
other 9 original Financial entries have moved to Group B, Group C, or Group D″ below)*

If you want any of these 46 to become real, the path is Feature 2: register the `factor_key` with
a real `source_column`/`source_table` (or drop values into `movie_factor_values` if you're
collecting them by hand), and it becomes eligible the same way as every factor in Groups B/C —
no separate mechanism needed.

### Group D′ — explanatory-only, structurally excluded from prediction (3 factors)

`twist_effectiveness`, `miscasting`, and `romantic_track_integration` are different from the 65
above: they don't lack data because no one's collected it yet — they describe a property of the
*finished film as experienced* (whether a twist landed, whether casting worked, whether a subplot
felt integrated). That can't be known before the movie exists to be watched, so no amount of
future data collection turns them into a genuine pre-release predictor; even a perfect measurement
of them is only useful **after the fact**, to help explain why a past movie under/over-performed
— a different product surface (a retrospective "why did this movie do what it did" report,
sourced from post-release critic/audience review-mining) than revenue prediction.

Rather than delete them and lose that reasoning, seed them in Feature 2's `factor_definitions`
with a fourth status value, `explanatory_only` (extend the `status` check constraint to
`('candidate','active','deprecated','explanatory_only')`). `feature_columns()`/`assemble_features()`
must only ever read `status='active'` rows — so this isn't a promise to leave them alone, it's a
status a factor can never be promoted out of into the trained feature set. They stay visible in
`GET /api/admin/factor-definitions` and the coverage report (Feature 11) for whoever eventually
builds a post-release explainer feature, without ever being a candidate for the prediction model.

### Group D″ — no public data source exists (3 factors)

`kdm_lockout`, `high_interest_financing`, `producer_debt_solvency` are a third, different kind of
exclusion — not conceptually circular like Group D′, and not merely uncollected like Group D.
They describe perfectly real, knowable facts (a distributor's KDM delivery status, a production's
loan terms, a studio's debt load), but **no public source discloses them for individual films,
ever** — this is internal distributor/financier/exhibitor operations data, not something any news
outlet, trade press, or government registry publishes. `producer_debt_solvency` has a narrow
exception: for the handful of *publicly-listed* production companies, company-level financial
disclosures exist — but reliably mapping company solvency down to one specific film, across a
mostly-privately-held Indian production landscape, isn't realistic at scale.

These are not candidates for `movie_factor_values` either — that table is for data *you* (or
someone) can actually obtain and enter, and there's no legitimate path to this data, manual or
automated, without direct industry-insider access (an actual distribution/financing partnership).
Seed them in `factor_definitions` with `status='deprecated'` and a `notes` field explaining why,
rather than `candidate` — `candidate` implies "achievable with more collection effort," which
isn't true here.

---

## Suggested order to hand these to Claude Code

0 → (1, 2 in parallel) → 3 → (4, 5, 6, 7 in parallel) → 8 → 9 → 10 → 11.

Do **0 first no matter what** — it's a live bug blocking any run of the script today, and it
unlocks roughly 6–8x more usable training rows before any new data-collection effort is worth the
time. **1** (raw-data URL/connector registry) and **2** (model-feature registry, replacing the
hardcoded `FACTOR_CATALOG`) are independent of each other — 2 only needs 0's fixed query to exist
— and can be built in parallel; both should land before **3**, since 3's new datasets should
register through 1 (source) and 2 (feature) from day one instead of being wired in as one-off
hardcoded columns the way today's 12 measurable factors are. **4/5/6/7** are four independent
new-factor efforts (missing-data modeling, cast track record, macro factors, legal/financial
signals) that can proceed in parallel once 2 exists, since each should go through 2's registration
path rather than editing `feature_columns()` by hand — note 7's two zero-new-data sub-items
(`joint_production_partnerships`, dubbing breadth) are the cheapest thing in this whole group and
worth doing first within it. **8** benefits from 5/6/7's new columns and, because
`feature_columns()` is now registry-driven, needs no code change to pick them up; **8** also now
ends with persisting the trained model artifact, which **9** (predict a single upcoming movie —
the "how accurate is it for a movie that hasn't released yet" capability) depends on directly.
**10** (serving + the accuracy report) depends on both 8 (backtested accuracy numbers) and 9
(the upcoming-movie prediction path) existing. **11** depends on 10's tables and 2's registry for
the live coverage count.

**If "can I see accuracy end-to-end" is the priority over full factor coverage**, a minimal path
through this plan is 0 → 2 → 8 → 9 → 10: fix the bug, stand up the registry (even seeded with
just today's 12 real factors), get the model comparison + persistence working, add single-movie
inference, and serve both the backtest accuracy report and upcoming-movie predictions — the
richer data-collection features (1, 3, 4, 5, 6, 7) then layer on top and improve accuracy over
time without changing this shape.
