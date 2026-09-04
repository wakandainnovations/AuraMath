# Factor registry (Feature 2)

The live, queryable replacement for the hardcoded `FACTOR_CATALOG` list that
used to live in `movie_revenue_impact_model.py`. Turns "add a new predictive
parameter later" into a data-registration step (insert one row, supply the
values) instead of a code change.

## Model

- **`factor_definitions`** (see `schema.py`): one row per factor the model
  could train on -- name/category/direction/stated range, `status`
  (`candidate` / `active` / `deprecated` / `explanatory_only`), and how to
  compute it (`computation_type` + `source_table`/`source_column`/
  `derivation_ref`). Only `status='active'` rows that clear
  `--min-feature-coverage` feed the trained model; `candidate` rows are still
  computed and reported (coverage %, correlation with ln(revenue)) so you can
  see whether a new factor looks useful before promoting it.
- **`movie_factor_values`**: a generic EAV overflow table for factors that
  don't warrant a dedicated column. Keyed by `movie_key` = the same
  `movie_name|release_date|language` composite Feature 1's `data_sources`
  uses for a movie's `entity_key` (see `connectors/schema.py`'s
  `movie_entity_key`) -- **not** `movie_revenue_impact_model.py`'s internal,
  coarser `movie_key` (lowercased-name|release_year), which only exists
  post-dedup as a modeling convenience once every dubbed-language release of
  a film has been collapsed to one row.
- **`DERIVED_FACTOR_FNS`** (in `movie_revenue_impact_model.py`): the raw
  compute functions for `computation_type='derived_python_fn'` factors,
  keyed by `derivation_ref`. This is where the plan's original 10
  hand-wired factor computations (holiday window, box-office clashes, etc.)
  now live -- invoked by name via the registry instead of hardcoded into a
  single `build_measurable_features` function.

Division of concerns with Feature 1's `data_sources`: `data_sources` governs
*where raw data comes from*; `factor_definitions` governs *which columns the
model actually trains on*. Kept as two separate tables.

## Adding a new factor

No code change needed unless the factor needs a genuinely new
`computation_type`:

```bash
# Sourced from an existing column:
python3 register_factor.py --key ticket_price_atp --name "Average Ticket Price" \
    --category Financial --direction Positive --stated-min 0.15 --stated-max 0.25 \
    --source-table ticket_price_index --source-column atp_usd --computation-type raw_column

# Hand-entered per movie, no dedicated column:
python3 register_factor.py --key some_manual_score --name "Manual Score" \
    --category Financial --direction Positive --stated-min 0.1 --stated-max 0.2 \
    --computation-type eav --status candidate
```

Then, for an EAV factor, drop values into `movie_factor_values` (via
`registry.schema.upsert_factor_value`, or the Java
`POST /api/admin/factor-values` bulk-upsert endpoint) using the movie's exact
`movie_name|release_date|language` as it appears in `movies_data_collection`.

Promote once coverage/correlation look good:

```bash
python3 register_factor.py --key ticket_price_atp --status active --promote-only
```

## One-time migration

`migrate_factor_definitions.py` seeds `factor_definitions` from the original
80-entry catalogue (preserved verbatim in `seed_catalog.py`). Safe to re-run;
pass `--no-overwrite-status` after factors have already been promoted/
deprecated by hand so the migration doesn't reset them back to the seed
default.

## Coverage guard

`movie_revenue_impact_model.py --min-feature-coverage 5` (default 5%): an
`active` factor only enters the trained feature set once its non-null
coverage on that run's rows clears this threshold. Below-threshold active
factors, and every `candidate` factor regardless of coverage, are still
computed and written to `factor_coverage_report.csv`/`.json` every run.
