"""Unit tests for Feature 8's model-comparison harness additions: champion
selection across compare_models()'s candidates (including a stacking
ensemble), the SHAP summarization helpers (summarize_shap/
top_shap_drivers_per_row -- pure numpy-array logic, no live `shap` fit
required), and the full-corpus champion fit + joblib persistence step.
Mirrors test_feature7_factors.py's "assert on the pure compute functions,
no live DB/network call" style; the heavier real-model paths
(build_stacking_regressor, fit_champion_on_full_corpus, compute_shap_values'
generic fallback) use a tiny synthetic DataFrame so they still fit in
milliseconds.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import movie_revenue_impact_model as model  # noqa: E402


def _tiny_model_df(n: int = 40, seed: int = 0) -> tuple[pd.DataFrame, list[str]]:
    """A small synthetic frame shaped like model_df: a couple of numeric
    feature columns plus ln_revenue, just enough rows for KFold(n_splits=5)
    and a StandardScaler/Ridge/GBR fit to run without erroring."""
    rng = np.random.RandomState(seed)
    cols = ["ln_budget_effective", "r_star"]
    df = pd.DataFrame({
        "ln_budget_effective": rng.normal(15, 1, n),
        "r_star": rng.uniform(0.1, 0.9, n),
    })
    df["ln_revenue"] = df["ln_budget_effective"] * 1.1 + df["r_star"] * 0.5 + rng.normal(0, 0.1, n)
    return df, cols


class PickChampionModelTest(unittest.TestCase):
    def test_picks_lowest_median_abs_pct_error(self):
        results = {
            "ridge": {"median_abs_pct_error": 54.4},
            "gbr": {"median_abs_pct_error": 51.1},
            "hist_gbr": {"median_abs_pct_error": 50.7},
        }
        self.assertEqual(model.pick_champion_model(results), "hist_gbr")

    def test_ignores_error_entries(self):
        results = {
            "ridge": {"median_abs_pct_error": 54.4},
            "broken": "insufficient rows",
        }
        self.assertEqual(model.pick_champion_model(results), "ridge")

    def test_all_errored_returns_none(self):
        self.assertIsNone(model.pick_champion_model({"error": "insufficient rows for 5-fold CV: n=3"}))

    def test_empty_results_returns_none(self):
        self.assertIsNone(model.pick_champion_model({}))


class CompareStackingEnsembleSkipPathTest(unittest.TestCase):
    def test_skips_with_fewer_than_two_valid_base_models(self):
        df, cols = _tiny_model_df()
        result = model.compare_stacking_ensemble(df, cols, {"ridge": {"median_abs_pct_error": 50.0}})
        self.assertIn("skipped", result)

    def test_skips_when_every_base_model_errored(self):
        df, cols = _tiny_model_df()
        result = model.compare_stacking_ensemble(df, cols, {"error": "insufficient rows"})
        self.assertIn("skipped", result)


class BuildStackingRegressorTest(unittest.TestCase):
    def test_uses_the_requested_base_estimators(self):
        stack = model.build_stacking_regressor(["ridge", "gbr"])
        names = [name for name, _ in stack.estimators]
        self.assertEqual(names, ["ridge", "gbr"])
        self.assertIsInstance(stack.final_estimator, model.Ridge)


class FitChampionOnFullCorpusTest(unittest.TestCase):
    def test_fits_on_every_row_not_a_holdout_fold(self):
        df, cols = _tiny_model_df(n=25)
        scaler, fitted = model.fit_champion_on_full_corpus(lambda: model.Ridge(alpha=1.0), df, cols)
        self.assertIsInstance(scaler, model.StandardScaler)
        # A fitted Ridge exposes coef_/n_features_in_; unfit estimators don't.
        self.assertEqual(fitted.n_features_in_, len(cols))
        self.assertEqual(scaler.n_samples_seen_, len(df))


class SummarizeShapTest(unittest.TestCase):
    def test_mean_abs_shap_per_factor_key_strips_ln1p_prefix(self):
        cols = ["ln_budget_effective", "ln1p_star_overexposure", "ln1p_box_office_clashes"]
        shap_values = np.array([
            [1.0, -2.0, 0.5],
            [3.0, 2.0, -0.5],
        ])
        out = model.summarize_shap(shap_values, cols)
        self.assertEqual(
            out, {"ln_budget_effective": 2.0, "star_overexposure": 2.0, "box_office_clashes": 0.5})


class TopShapDriversPerRowTest(unittest.TestCase):
    def test_ranks_by_absolute_value_and_keeps_sign(self):
        cols = ["a", "ln1p_b", "c"]
        shap_values = np.array([[0.1, -5.0, 2.0]])
        out = model.top_shap_drivers_per_row(shap_values, cols, k=2)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][0], {"factor": "b", "shap_value": -5.0})
        self.assertEqual(out[0][1], {"factor": "c", "shap_value": 2.0})

    def test_k_larger_than_column_count_does_not_crash(self):
        out = model.top_shap_drivers_per_row(np.array([[1.0, 2.0]]), ["a", "b"], k=10)
        self.assertEqual(len(out[0]), 2)


class ComputeShapValuesTest(unittest.TestCase):
    def test_raises_clearly_when_shap_not_installed(self):
        original = model.shap
        model.shap = None
        try:
            df, cols = _tiny_model_df(n=10)
            scaler, fitted = model.fit_champion_on_full_corpus(lambda: model.Ridge(alpha=1.0), df, cols)
            with self.assertRaises(RuntimeError):
                model.compute_shap_values("ridge", fitted, scaler, df, cols)
        finally:
            model.shap = original

    @unittest.skipIf(model.shap is None, "shap not installed in this environment")
    def test_tree_explainer_path_matches_row_and_column_count(self):
        df, cols = _tiny_model_df(n=20)
        scaler, fitted = model.fit_champion_on_full_corpus(
            lambda: model.GradientBoostingRegressor(n_estimators=10, max_depth=2, random_state=0), df, cols)
        shap_values = model.compute_shap_values("gbr", fitted, scaler, df, cols)
        self.assertEqual(shap_values.shape, (len(df), len(cols)))


class PersistModelArtifactTest(unittest.TestCase):
    def test_writes_a_loadable_joblib_bundle_with_expected_keys(self):
        df, cols = _tiny_model_df(n=15)
        scaler, fitted = model.fit_champion_on_full_corpus(lambda: model.Ridge(alpha=1.0), df, cols)
        with tempfile.TemporaryDirectory() as tmp:
            path = model.persist_model_artifact(
                "ridge", fitted, scaler, cols, factor_keys_used=["star_overexposure"],
                n_training_rows=len(df), models_dir=tmp)
            self.assertTrue(os.path.exists(path))
            self.assertTrue(path.startswith(tmp))
            bundle = model.joblib.load(path)
            self.assertEqual(bundle["model_name"], "ridge")
            self.assertEqual(bundle["feature_columns"], cols)
            self.assertEqual(bundle["factor_keys_used"], ["star_overexposure"])
            self.assertEqual(bundle["n_training_rows"], len(df))
            self.assertIn("trained_at", bundle)
            # Round-trips through the same StandardScaler/Ridge classes, not
            # just picklable-in-general objects.
            self.assertIsInstance(bundle["scaler"], model.StandardScaler)
            self.assertIsInstance(bundle["model"], model.Ridge)

    def test_creates_models_dir_if_missing(self):
        df, cols = _tiny_model_df(n=15)
        scaler, fitted = model.fit_champion_on_full_corpus(lambda: model.Ridge(alpha=1.0), df, cols)
        with tempfile.TemporaryDirectory() as tmp:
            nested = os.path.join(tmp, "nested", "models")
            path = model.persist_model_artifact("ridge", fitted, scaler, cols, [], len(df), nested)
            self.assertTrue(os.path.exists(path))


if __name__ == "__main__":
    unittest.main()
