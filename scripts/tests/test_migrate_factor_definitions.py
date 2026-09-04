"""Unit test for migrate_factor_definitions.py's re-run idempotency --
specifically the Feature 7 fix: re-running this "one-time seed" must not
un-wire a catalog factor_key that a later feature (e.g.
register_feature7_factors.py) has since wired up to a real computation_type,
since seed_catalog.py's MEASURABLE_WIRING only knows about the original 12
Feature-2-era factors. fetch_factor_definitions/register_factor/
ensure_factor_registry_schema are monkeypatched -- no live DB call, same
fetch_fn-injection style test_feature6_macro_factors.py's
BackfillMarketIndexTest uses."""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import migrate_factor_definitions as mfd  # noqa: E402


class MigrateWiringPreservationTest(unittest.TestCase):
    def setUp(self):
        self._orig_ensure = mfd.ensure_factor_registry_schema
        self._orig_fetch = mfd.fetch_factor_definitions
        self._orig_register = mfd.register_factor
        mfd.ensure_factor_registry_schema = lambda conn: None
        self.registered: list[dict] = []
        mfd.register_factor = lambda conn, **kw: self.registered.append(kw)

    def tearDown(self):
        mfd.ensure_factor_registry_schema = self._orig_ensure
        mfd.fetch_factor_definitions = self._orig_fetch
        mfd.register_factor = self._orig_register

    def test_reruns_preserve_a_factor_wired_up_after_the_original_seed(self):
        # Simulate the live registry state AFTER register_feature7_factors.py
        # has already wired up joint_production_partnerships (catalog slot
        # 89), which seed_catalog.py's MEASURABLE_WIRING knows nothing about
        # (it only lists the 12 original Feature-2-era factors).
        mfd.fetch_factor_definitions = lambda conn: [
            {
                "factor_key": "joint_production_partnerships", "status": "candidate",
                "computation_type": "derived_python_fn", "derivation_ref": "joint_production_partnerships",
                "source_table": None, "source_column": None,
                "notes": "Feature 7 wired this up for real.",
            },
        ]

        mfd.migrate(conn=object(), overwrite_status=True)

        written = next(r for r in self.registered if r["factor_key"] == "joint_production_partnerships")
        self.assertEqual(written["computation_type"], "derived_python_fn")
        self.assertEqual(written["derivation_ref"], "joint_production_partnerships")
        self.assertEqual(written["notes"], "Feature 7 wired this up for real.")

    def test_a_never_wired_factor_stays_unwired_on_rerun(self):
        mfd.fetch_factor_definitions = lambda conn: [
            {
                "factor_key": "vfx_quality", "status": "candidate",
                "computation_type": None, "derivation_ref": None,
                "source_table": None, "source_column": None,
                "notes": "No VFX rating column",
            },
        ]

        mfd.migrate(conn=object(), overwrite_status=True)

        written = next(r for r in self.registered if r["factor_key"] == "vfx_quality")
        self.assertIsNone(written["computation_type"])

    def test_original_measurable_wiring_factor_is_reasserted_from_seed(self):
        # star_overexposure is one of the original 12 (MEASURABLE_WIRING) --
        # the seed itself defines a computation_type, so it should always be
        # (re-)written from the seed regardless of what's currently live.
        mfd.fetch_factor_definitions = lambda conn: [
            {
                "factor_key": "star_overexposure", "status": "active",
                "computation_type": None, "derivation_ref": None,  # e.g. corrupted/reset live row
                "source_table": None, "source_column": None, "notes": "stale",
            },
        ]

        mfd.migrate(conn=object(), overwrite_status=True)

        written = next(r for r in self.registered if r["factor_key"] == "star_overexposure")
        self.assertEqual(written["computation_type"], "derived_python_fn")
        self.assertEqual(written["derivation_ref"], "star_overexposure")


if __name__ == "__main__":
    unittest.main()
