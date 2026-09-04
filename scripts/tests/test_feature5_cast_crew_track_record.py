"""Unit tests for Feature 5's cast/crew track-record factors in
movie_revenue_impact_model.py -- hand-built actors_by_movie/actors_by_actor/
directors_by_director indices stand in for the actors_data_collection
self-join (no live DB), focused on the plan's explicit no-leakage rule:
every "prior" comparison must use release_year strictly less than the
target's own release_year.

Year granularity, not day-level release_date, is deliberate: a live query
against actors_data_collection found 0 of its 62,413 rows have a day-level
release_date (every row is year-only, e.g. "1971") -- see the module
docstring above compute_lead_actor_key in movie_revenue_impact_model.py."""
from __future__ import annotations

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from movie_revenue_impact_model import (  # noqa: E402
    LEAD_PRIOR_FILM_HIT_THRESHOLD,
    compute_actor_prior_hit_rate,
    compute_director_prior_films_count,
    compute_director_prior_hit_rate,
    compute_ensemble_avg_prior_hit_rate,
    compute_lead_actor_key,
    compute_lead_prior_film_hit,
    compute_lead_prior_films_count,
)


def _credit(movie_key, year, budget=None, revenue=None, role_wt=1.0):
    return {
        "movie_key": movie_key,
        "release_year": year,
        "budget": budget,
        "revenue": revenue,
        "role_wt": role_wt,
    }


class LeadActorKeyTest(unittest.TestCase):
    def test_picks_minimum_role_position(self):
        abm = {
            "movie_x|2020": [
                {"actor_key": "star_a", "role_position": 2},
                {"actor_key": "star_b", "role_position": 1},
                {"actor_key": "star_c", "role_position": 3},
            ]
        }
        self.assertEqual(compute_lead_actor_key("movie_x|2020", abm), "star_b")

    def test_no_role_position_anywhere_returns_none(self):
        abm = {"movie_x|2020": [{"actor_key": "star_a", "role_position": None}]}
        self.assertIsNone(compute_lead_actor_key("movie_x|2020", abm))

    def test_unknown_movie_returns_none(self):
        self.assertIsNone(compute_lead_actor_key("nope|1999", {}))


class LeadPriorFilmsCountTests(unittest.TestCase):
    def setUp(self):
        self.abm = {
            "new_release|2020": [{"actor_key": "star_a", "role_position": 1}],
        }
        # star_a's full credited history, including films in/after the
        # target release year, which must NOT be counted.
        self.aba = {
            "star_a": [
                _credit("old_1|2015", 2015),
                _credit("old_2|2018", 2018),
                _credit("new_release|2020", 2020),
                _credit("same_year|2020", 2020),   # not strictly before -> excluded
                _credit("future|2021", 2021),      # after -> excluded
            ]
        }

    def test_counts_only_strictly_prior_films(self):
        n = compute_lead_prior_films_count("new_release|2020", 2020, self.abm, self.aba)
        self.assertEqual(n, 2.0)

    def test_unknown_lead_is_nan(self):
        n = compute_lead_prior_films_count("mystery|2020", 2020, {}, self.aba)
        self.assertTrue(math.isnan(n))

    def test_missing_release_year_is_nan(self):
        n = compute_lead_prior_films_count("new_release|2020", float("nan"), self.abm, self.aba)
        self.assertTrue(math.isnan(n))


class LeadPriorFilmHitTests(unittest.TestCase):
    def test_uses_most_recent_prior_year_and_flags_hit(self):
        abm = {"m|2020": [{"actor_key": "star_a", "role_position": 1}]}
        aba = {
            "star_a": [
                _credit("flop|2016", 2016, budget=100, revenue=80),   # older, worse ratio
                _credit("hit|2019", 2019, budget=100, revenue=300),   # most recent prior year
                _credit("m|2020", 2020),
            ]
        }
        flag, ratio = compute_lead_prior_film_hit("m|2020", 2020, abm, aba)
        self.assertEqual(flag, 1.0)
        self.assertAlmostEqual(ratio, 3.0)

    def test_same_year_tie_is_averaged_not_picked_arbitrarily(self):
        # Two credited films in the same most-recent prior year -- nothing in
        # this schema orders same-year releases, so both are averaged.
        abm = {"m|2020": [{"actor_key": "star_a", "role_position": 1}]}
        aba = {
            "star_a": [
                _credit("a|2019", 2019, budget=100, revenue=300),   # ratio 3.0, hit
                _credit("b|2019", 2019, budget=100, revenue=100),   # ratio 1.0, not a hit
            ]
        }
        flag, ratio = compute_lead_prior_film_hit("m|2020", 2020, abm, aba)
        self.assertAlmostEqual(flag, 0.5)
        self.assertAlmostEqual(ratio, 2.0)

    def test_below_threshold_is_not_a_hit(self):
        abm = {"m|2020": [{"actor_key": "star_a", "role_position": 1}]}
        aba = {"star_a": [_credit("meh|2019", 2019, budget=100, revenue=120)]}
        flag, ratio = compute_lead_prior_film_hit("m|2020", 2020, abm, aba)
        self.assertEqual(flag, 0.0)
        self.assertLess(ratio, LEAD_PRIOR_FILM_HIT_THRESHOLD)

    def test_undisclosed_prior_financials_leave_both_null(self):
        abm = {"m|2020": [{"actor_key": "star_a", "role_position": 1}]}
        aba = {"star_a": [_credit("unknown_money|2019", 2019, budget=None, revenue=None)]}
        flag, ratio = compute_lead_prior_film_hit("m|2020", 2020, abm, aba)
        self.assertTrue(math.isnan(flag))
        self.assertTrue(math.isnan(ratio))

    def test_zero_budget_prior_film_leaves_both_null_not_divide_by_zero(self):
        abm = {"m|2020": [{"actor_key": "star_a", "role_position": 1}]}
        aba = {"star_a": [_credit("bad_data|2019", 2019, budget=0, revenue=500)]}
        flag, ratio = compute_lead_prior_film_hit("m|2020", 2020, abm, aba)
        self.assertTrue(math.isnan(flag))
        self.assertTrue(math.isnan(ratio))

    def test_no_prior_film_is_null(self):
        abm = {"m|2020": [{"actor_key": "debutant", "role_position": 1}]}
        flag, ratio = compute_lead_prior_film_hit("m|2020", 2020, abm, {})
        self.assertTrue(math.isnan(flag))
        self.assertTrue(math.isnan(ratio))


class DirectorPriorFactorsTests(unittest.TestCase):
    def setUp(self):
        self.dbd = {
            "dir_a": [
                _credit("d_old|2015", 2015, budget=10, revenue=5),    # flop
                _credit("d_mid|2018", 2018, budget=10, revenue=30),   # hit
                _credit("d_future|2022", 2022, budget=10, revenue=50),  # after target, excluded
            ]
        }

    def test_prior_films_count_excludes_future_films(self):
        n = compute_director_prior_films_count("dir_a", 2020, self.dbd)
        self.assertEqual(n, 2.0)

    def test_hit_rate_averages_only_disclosed_prior_films(self):
        rate = compute_director_prior_hit_rate("dir_a", 2020, self.dbd)
        self.assertAlmostEqual(rate, 0.5)  # one flop, one hit, out of two disclosed prior films

    def test_unknown_director_is_nan(self):
        self.assertTrue(math.isnan(compute_director_prior_films_count(None, 2020, self.dbd)))
        self.assertTrue(math.isnan(compute_director_prior_hit_rate(float("nan"), 2020, self.dbd)))


class EnsembleAvgPriorHitRateTests(unittest.TestCase):
    def test_weights_by_role_weight(self):
        abm = {
            "m|2020": [
                {"actor_key": "lead", "role_wt": 1.0},
                {"actor_key": "support", "role_wt": 0.4},
            ]
        }
        aba = {
            "lead": [_credit("lead_prior|2019", 2019, budget=10, revenue=100)],   # hit -> rate 1.0
            "support": [_credit("support_prior|2019", 2019, budget=10, revenue=10)],  # flop -> rate 0.0
        }
        rate = compute_ensemble_avg_prior_hit_rate("m|2020", 2020, abm, aba)
        expected = (1.0 * 1.0 + 0.4 * 0.0) / (1.0 + 0.4)
        self.assertAlmostEqual(rate, expected)

    def test_cast_members_with_no_track_record_are_excluded_not_zeroed(self):
        abm = {"m|2020": [{"actor_key": "debutant", "role_wt": 1.0}, {"actor_key": "vet", "role_wt": 1.0}]}
        aba = {"vet": [_credit("vet_prior|2019", 2019, budget=10, revenue=100)]}
        rate = compute_ensemble_avg_prior_hit_rate("m|2020", 2020, abm, aba)
        # Only "vet" contributes; a naive average-with-zero-fill would give 0.5 instead of 1.0.
        self.assertAlmostEqual(rate, 1.0)


class ActorPriorHitRateTests(unittest.TestCase):
    def test_no_history_returns_none_not_nan(self):
        self.assertIsNone(compute_actor_prior_hit_rate("nobody", 2020, {}))


if __name__ == "__main__":
    unittest.main()
