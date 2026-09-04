#!/usr/bin/env python3
"""
register_feature5_factors.py -- one-time registration of the six cast/crew
track-record `factor_definitions` rows Feature 5 adds
(`movie_revenue_impact_model.py`'s `DERIVED_FACTOR_FNS`), via
`scripts/register_factor.py`'s own `register_factor()` upsert rather than a
hand-edited list -- the registry doing its job, per Feature 2.

These are new factor_keys, not a promotion of the original catalogue rows 17
(`fanbase_mobilization`) / 20 (`director_brand_equity`): those two stay
`status='candidate'`/literature-prior on purpose (their own `notes` already
say why -- they'd double-count against the R_star/R_director baseline
anchors). The six rows here are a related-but-distinct real proxy: a
track-record COUNT/HIT-RATE signal (actors_data_collection self-join,
strictly-prior-release-date, no leakage), not a popularity-curve or
historical-revenue-percentile signal. `notes` on each row cross-references
the catalogue slot it's conceptually adjacent to, for anyone auditing the
factor list later.

Seeded `status='candidate'`: promote to `active` (via `register_factor.py
--key <key> --status active --promote-only`) once a live run's factor
coverage report shows reasonable non-null coverage for each -- not done here,
since that's a data-driven call this offline script can't make for you.

Safe to re-run: register_factor() upserts on factor_key.

Usage
-----
    python3 register_feature5_factors.py \
        --db-host localhost --db-port 5432 --db-name aura --db-user mukundv
"""
from __future__ import annotations

import argparse
import getpass
import os
import sys

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from registry.schema import register_factor  # noqa: E402

# (factor_key, name, stated_min, stated_max, notes)
# All: category='Cast', direction='Positive', data_type='numeric',
# computation_type='derived_python_fn', derivation_ref=factor_key (matches
# the DERIVED_FACTOR_FNS key movie_revenue_impact_model.py registers each
# under), status='candidate'.
FEATURE5_FACTORS: list[tuple[str, str, float, float, str]] = [
    (
        "lead_prior_films_count", "Lead Actor Prior Films Count", 0.30, 0.50,
        "How many movies the lead actor (role_position=1, or the minimum "
        "available per movie) had credited with a release_date strictly "
        "before this one, from actors_data_collection. Conceptually "
        "adjacent to catalogue #17 fanbase_mobilization, but a distinct "
        "track-record count, not the R_star popularity-curve baseline "
        "anchor that catalogue slot notes it would otherwise double-count.",
    ),
    (
        "lead_prior_film_hit_flag", "Lead Actor's Last Film Was a Hit", 0.15, 0.30,
        "Whether the lead's single most recent prior film (by release_date) "
        "cleared a revenue/budget ratio of LEAD_PRIOR_FILM_HIT_THRESHOLD "
        "(1.5x). Null when the lead has no prior film, or that prior film's "
        "budget/revenue was never disclosed -- see Feature 4's "
        "disclosure_likelihood for why. Adjacent to catalogue #17.",
    ),
    (
        "lead_prior_film_revenue_ratio", "Lead Actor's Last Film Revenue Ratio", 0.20, 0.40,
        "Raw revenue/budget ratio behind lead_prior_film_hit_flag, for the "
        "lead's single most recent (strictly prior) film. Same null rule as "
        "the hit flag. Adjacent to catalogue #17.",
    ),
    (
        "director_prior_films_count", "Director Prior Films Count", 0.25, 0.40,
        "Count of the director's strictly-earlier-released films, from the "
        "same actors_data_collection self-join keyed on `director` instead "
        "of `actor_name`. Adjacent to catalogue #20 director_brand_equity, "
        "but a track-record count rather than R_director's historical "
        "revenue-percentile anchor (which that catalogue slot's notes say "
        "it would otherwise double-count).",
    ),
    (
        "director_prior_hit_rate", "Director Prior Hit Rate", 0.20, 0.35,
        "Fraction of the director's strictly-prior films (restricted to "
        "those with disclosed budget/revenue) that cleared the "
        "LEAD_PRIOR_FILM_HIT_THRESHOLD ratio. Null when the director has no "
        "prior film with disclosed financials. Adjacent to catalogue #20.",
    ),
    (
        "ensemble_avg_prior_hit_rate", "Ensemble Cast Avg Prior Hit Rate", 0.20, 0.35,
        "role_weight()-weighted average, across the FULL credited cast (not "
        "just the lead), of each actor's own prior-film hit rate. Adjacent "
        "to catalogue #17, at the ensemble level rather than lead-only.",
    ),
]


def register_feature5_factors(conn, added_by: str) -> int:
    for factor_key, name, stated_min, stated_max, notes in FEATURE5_FACTORS:
        register_factor(
            conn, factor_key=factor_key, name=name, category="Cast", direction="Positive",
            stated_min=stated_min, stated_max=stated_max, data_type="numeric", status="candidate",
            computation_type="derived_python_fn", derivation_ref=factor_key,
            added_by=added_by, notes=notes,
        )
    return len(FEATURE5_FACTORS)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    return p.parse_args()


def main() -> None:
    args = parse_args()
    print(f"Connecting to postgresql://{args.db_user}@{args.db_host}:{args.db_port}/{args.db_name} ...")
    conn = psycopg2.connect(host=args.db_host, port=args.db_port, dbname=args.db_name,
                             user=args.db_user, password=args.db_password or None)
    try:
        n = register_feature5_factors(conn, added_by=getpass.getuser())
    finally:
        conn.close()
    print(f"Registered/updated {n} Feature 5 factor_definitions rows "
          f"(status=candidate): {[f[0] for f in FEATURE5_FACTORS]}")


if __name__ == "__main__":
    main()
