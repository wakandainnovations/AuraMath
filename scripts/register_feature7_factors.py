#!/usr/bin/env python3
"""
register_feature7_factors.py -- one-time registration of Feature 7's
Legal/Financial factor_definitions changes, via `register_factor.py`'s own
`register_factor()` upsert -- the registry doing its job, per Feature 2.

Feature 7 found that, unlike the narrative-quality factors, almost none of
the Legal/Financial catalogue is genuinely unquantifiable -- most are real,
discrete, publicly reported facts or events. This script closes that gap in
four groups:

1. **Direct promotions** (existing catalogue factor_key, computation_type
   goes from None to a real value) -- joint_production_partnerships and
   subtitle_dubbing_quality (zero new data, computed in
   movie_revenue_impact_model.py's DERIVED_FACTOR_FNS), and the six of the
   shared news-event feed's seven factors that ARE a catalogue slot as-is
   (state_bans, pre_release_leak, title_ownership_disputes, copyright_claims,
   distribution_disputes, name_similarity_disputes -- computation_type='eav',
   populated by backfill_legal_news_events.py).
2. **New adjacent factor_keys** (same pattern Feature 5 used for
   fanbase_mobilization/director_brand_equity: the original catalogue slot
   stays literature-prior on purpose, a related-but-distinct real proxy gets
   its own key) -- remake_rights_detected and plagiarism_allegation_events
   both split out of catalogue slot 78 (plagiarism_remake_rights), which
   stays candidate/computation_type=None since it's genuinely bidirectional
   and would conflate two different signals if promoted directly.
3. **Documentation-only note updates** (computation_type stays None, no
   working computation path yet) -- cbfc_rating, screen_count_allocation,
   tax_exemptions (each needs a connector reviewed first -- see sources.yaml)
   and certification_delays (explicitly deprioritized by the plan).
4. **EAV-ready for manual entry** (computation_type -> 'eav', no automated
   connector -- real deal terms, but only occasionally disclosed by trade
   press) -- minimum_guarantee_deals, outright_purchase_sales, pa_commitments.
   Same pattern as ticket_price_index_manual in register_factor.py's own
   docstring: values land via upsert_factor_value / POST
   /api/admin/factor-values once someone decides to hand-curate one.

kdm_lockout/high_interest_financing/producer_debt_solvency are NOT touched
here -- migrate_factor_definitions.py already seeds them status='deprecated'
with the correct "no public source" note (Group D'' in the plan doc); no
public disclosure path exists for them, so there is nothing for Feature 7
to add.

Every row below keeps its original name/category/direction/stated_min/
stated_max from scripts/registry/seed_catalog.py's _RAW_CATALOG unless noted
-- this script only changes computation_type/derivation_ref/notes (or adds a
new factor_key), it never silently redefines an existing catalogue slot's
business-stated range.

Seeded/updated `status='candidate'` throughout: promote to `active` (via
`register_factor.py --key <key> --status active --promote-only`) once a live
run's factor coverage report shows reasonable non-null coverage -- not done
here, that's a data-driven call this offline script can't make for you.

Safe to re-run: register_factor() upserts on factor_key.

Usage
-----
    python3 register_feature7_factors.py \
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

# --- Group 1a: zero-new-data direct promotions --------------------------

ZERO_NEW_DATA_PROMOTIONS: list[dict] = [
    {
        "factor_key": "joint_production_partnerships",
        "name": "Joint Production Partnerships", "category": "Financial", "direction": "Positive",
        "stated_min": 0.15, "stated_max": 0.25, "computation_type": "derived_python_fn",
        "derivation_ref": "joint_production_partnerships",
        "notes": (
            "Count of comma-separated entries in movies_data_collection.production_companies "
            "-- real column, real signal, zero new data collection (Feature 7). Direct promotion "
            "of catalogue slot 89's computation path; the original notes' concern about "
            "co-production vs. sole-production *quality* being unreliable doesn't apply to a "
            "plain partnership count."
        ),
    },
    {
        "factor_key": "subtitle_dubbing_quality",
        "name": "Global Subtitle / Dubbing Quality", "category": "Financial", "direction": "Positive",
        "stated_min": 0.15, "stated_max": 0.25, "computation_type": "derived_python_fn",
        "derivation_ref": "subtitle_dubbing_quality",
        "notes": (
            "Breadth proxy, not a quality rating (quality itself isn't measurable, breadth is): "
            "count of sibling rows sharing (movie_name, release_date) but a different `language` "
            "-- how many simultaneous language releases a title had, computed by dedupe_movies() "
            "before it collapses those sibling rows (Feature 7). Direct promotion of catalogue "
            "slot 86's computation path."
        ),
    },
]

# --- Group 1b + 2: the shared 7-factor legal/controversy news-event feed -

LEGAL_NEWS_EVENT_NOTE_SUFFIX = (
    " Populated by backfill_legal_news_events.py via the shared "
    "NewsEventFeedConnector (GDELT DOC 2.0 API, connectors/news_event_feed.py) "
    "into movie_factor_values (computation_type='eav') -- one connector class "
    "covers all seven news-event factors, only the keyword set differs. "
    "Expect a mostly-zero flag: most movies have zero legal drama, so even a "
    "perfectly accurate detector has limited standalone lift on model "
    "accuracy -- built because it's cheap once the shared connector exists "
    "and Feature 8's SHAP output will show empirically whether any of the "
    "seven actually move predictions."
)

# Existing catalogue slots (72-75, 77, 79) promoted to computation_type='eav'
# as-is -- same factor_key, name, category, direction, band as
# registry/seed_catalog.py's _RAW_CATALOG, only computation_type/notes change.
LEGAL_NEWS_EVENT_CATALOGUE_PROMOTIONS: list[dict] = [
    {"factor_key": "state_bans", "name": "Multi-State Political / Cultural Bans",
     "category": "Legal", "direction": "Negative", "stated_min": -0.50, "stated_max": -0.30},
    {"factor_key": "pre_release_leak", "name": "High-Definition Pre-Release Leak",
     "category": "Legal", "direction": "Negative", "stated_min": -0.80, "stated_max": -0.60},
    {"factor_key": "title_ownership_disputes", "name": "Legal Disputes over Title Ownership",
     "category": "Legal", "direction": "Negative", "stated_min": -0.25, "stated_max": -0.15},
    {"factor_key": "copyright_claims", "name": "Copyright Claims on Visuals / Audio",
     "category": "Legal", "direction": "Negative", "stated_min": -0.35, "stated_max": -0.20},
    {"factor_key": "distribution_disputes", "name": "Inter-State Distribution Disputes",
     "category": "Legal", "direction": "Negative", "stated_min": -0.30, "stated_max": -0.15},
    {"factor_key": "name_similarity_disputes", "name": "Real-Life Personality Name Similarities",
     "category": "Legal", "direction": "Negative", "stated_min": -0.20, "stated_max": -0.10},
]

# New factor_key, the allegation-half split out of catalogue slot 78
# (plagiarism_remake_rights) -- that slot itself stays candidate/
# computation_type=None (see NEW_ADJACENT_FACTORS below).
PLAGIARISM_ALLEGATION_FACTOR: dict = {
    "factor_key": "plagiarism_allegation_events", "name": "Plagiarism Allegation News Events",
    "category": "Legal", "direction": "Negative", "stated_min": -0.15, "stated_max": -0.05,
    "computation_type": "eav",
    "notes": (
        "Allegation half of catalogue slot 78 (plagiarism_remake_rights), split out as its own "
        "key -- the remake-*rights* half is separately detectable pre-release (see "
        "remake_rights_detected below); an allegation is a post-announcement news event, not a "
        "pre-release fact, so it's a distinct signal with its own (narrower, purely negative) "
        "band rather than sharing plagiarism_remake_rights's bidirectional -0.15/0.15."
    ) + LEGAL_NEWS_EVENT_NOTE_SUFFIX,
}

# --- Group 2: new adjacent factor -- remake-rights detection ------------

NEW_ADJACENT_FACTORS: list[dict] = [
    {
        "factor_key": "remake_rights_detected",
        "name": "Remake Rights Disclosed Pre-Release", "category": "Legal", "direction": "Bidirectional",
        "stated_min": -0.10, "stated_max": 0.15, "computation_type": "derived_python_fn",
        "derivation_ref": "remake_rights_detected",
        "notes": (
            "Higher-confidence remake-*rights* half of catalogue slot 78 (plagiarism_remake_rights), "
            "split out per the plan since this is a genuine pre-release fact, not a rare news event "
            "like the allegation half (plagiarism_allegation_events). Regex over `overview` "
            "(Feature 3's the_movies_dataset synopsis column) for explicit remake phrasing -- see "
            "compute_remake_rights_detected_raw's docstring for why this isn't TMDB 'belongs to "
            "collection' metadata (needs a live TMDB API id lookup not wired here yet). "
            "Bidirectional because a disclosed remake can cut either way (built-in "
            "audience/pre-awareness vs. comparison-to-the-original risk), same as catalogue slot "
            "78's own stated direction -- this factor's band is a placeholder subset of that "
            "slot's -0.15/0.15, pending real business input on remake-specific impact."
        ),
    },
]

# --- Group 3: documentation-only note updates (no computation path yet) -

DOCUMENTATION_ONLY_NOTE_UPDATES: list[dict] = [
    {
        "factor_key": "cbfc_rating", "name": "CBFC Rating Classifications (U vs. UA/A)",
        "category": "Legal", "direction": "Bidirectional", "stated_min": -0.30, "stated_max": 0.30,
        "notes": (
            "Real, pre-release-available fact (certification happens before release) -- TMDB's "
            "per-country certification field (needs a live TMDB API movie-id lookup, two-step: "
            "search by title then GET /movie/{id}/release_dates) or CBFC records directly. Not "
            "wired yet: see sources.yaml's tmdb_api_certification entry (skip_registration, "
            "documentation-only) for why. Prioritize screen_count_allocation first per the plan -- "
            "more sources already named for it."
        ),
    },
    {
        "factor_key": "screen_count_allocation", "name": "Screen Count Allocation and Show Pacing",
        "category": "Financial", "direction": "Positive", "stated_min": 0.25, "stated_max": 0.40,
        "notes": (
            "Real, credible signal -- Indian trade sites (Sacnilk, already a named source; Box "
            "Office India, Koimoi) routinely report opening-day/weekend screen counts. Worth "
            "prioritizing over cbfc_rating/tax_exemptions per the plan. Not wired yet: needs a "
            "sample page's CSS-selector field_mapping reviewed before registering a real "
            "html_scrape data_sources row -- see sources.yaml's screen_count_sources entry."
        ),
    },
    {
        "factor_key": "tax_exemptions", "name": "Regional Entertainment Tax Exemptions",
        "category": "Legal", "direction": "Positive", "stated_min": 0.15, "stated_max": 0.30,
        "notes": (
            "Real signal, publicly reported when it happens -- when a state grants a film "
            "tax-free status it's public policy, reported explicitly by entertainment press "
            "('X declared tax-free in [state]'). A targeted keyword news search "
            "('<movie name>' AND 'tax free') gives a real signal, not a guess -- same shape as "
            "the shared legal news-event feed (see LEGAL_EVENT_KEYWORDS), but not folded into it "
            "here since a tax-exemption grant is a positive-direction policy event, not a "
            "negative-direction controversy; a dedicated keyword search is named follow-up work, "
            "not built in this pass. See sources.yaml's tax_exemptions_search entry."
        ),
    },
    {
        "factor_key": "certification_delays", "name": "Administrative Delays in Certifications",
        "category": "Legal", "direction": "Negative", "stated_min": -0.40, "stated_max": -0.20,
        "notes": (
            "Deprioritized per the plan -- weakest item in the Legal group. Needs a data point "
            "this schema doesn't have anywhere: an *originally announced* release date to compare "
            "against the actual one (tracking date-shift announcements from entertainment news), "
            "and even then it's confounded with ordinary clash-avoidance rescheduling, not just "
            "certification issues. Only build this if the seven-factor news feed "
            "(LEGAL_NEWS_EVENT_FACTOR_KEYS) already proves useful."
        ),
    },
]

# --- Group 4: EAV-ready for optional manual entry -----------------------

MANUAL_DEAL_TERM_NOTE_SUFFIX = (
    " Real financial deal terms, but not systematically public -- trade press occasionally "
    "discloses numbers for high-profile releases only. computation_type='eav': optional, "
    "low-priority, hand-curated entry into movie_factor_values (register_factor.py's own "
    "ticket_price_index_manual example, or the Java POST /api/admin/factor-values bulk-upsert "
    "path) for a handful of tentpole titles a year if it's judged worth it -- not worth an "
    "automated connector given how rarely these are disclosed."
)

MANUAL_DEAL_TERM_FACTORS: list[dict] = [
    {"factor_key": "minimum_guarantee_deals", "name": "Minimum Guarantee (MG) Distribution",
     "category": "Financial", "direction": "Positive", "stated_min": 0.20, "stated_max": 0.35},
    {"factor_key": "outright_purchase_sales", "name": "Outright Purchase Territorial Sales",
     "category": "Financial", "direction": "Positive", "stated_min": 0.15, "stated_max": 0.25},
    {"factor_key": "pa_commitments", "name": "Print & Advertising (P&A) Commitments",
     "category": "Financial", "direction": "Positive", "stated_min": 0.20, "stated_max": 0.30},
]


def register_feature7_factors(conn, added_by: str) -> dict:
    counts = {"zero_new_data": 0, "news_event": 0, "new_adjacent": 0, "documentation_only": 0, "manual_eav": 0}

    for f in ZERO_NEW_DATA_PROMOTIONS:
        register_factor(conn, factor_key=f["factor_key"], name=f["name"], category=f["category"],
                         direction=f["direction"], stated_min=f["stated_min"], stated_max=f["stated_max"],
                         data_type="numeric", status="candidate", computation_type=f["computation_type"],
                         derivation_ref=f["derivation_ref"], added_by=added_by, notes=f["notes"])
        counts["zero_new_data"] += 1

    for f in LEGAL_NEWS_EVENT_CATALOGUE_PROMOTIONS:
        register_factor(conn, factor_key=f["factor_key"], name=f["name"], category=f["category"],
                         direction=f["direction"], stated_min=f["stated_min"], stated_max=f["stated_max"],
                         data_type="numeric", status="candidate", computation_type="eav",
                         added_by=added_by, notes=f["name"] + "." + LEGAL_NEWS_EVENT_NOTE_SUFFIX)
        counts["news_event"] += 1
    f = PLAGIARISM_ALLEGATION_FACTOR
    register_factor(conn, factor_key=f["factor_key"], name=f["name"], category=f["category"],
                     direction=f["direction"], stated_min=f["stated_min"], stated_max=f["stated_max"],
                     data_type="numeric", status="candidate", computation_type=f["computation_type"],
                     added_by=added_by, notes=f["notes"])
    counts["news_event"] += 1

    for f in NEW_ADJACENT_FACTORS:
        register_factor(conn, factor_key=f["factor_key"], name=f["name"], category=f["category"],
                         direction=f["direction"], stated_min=f["stated_min"], stated_max=f["stated_max"],
                         data_type="numeric", status="candidate", computation_type=f["computation_type"],
                         derivation_ref=f["derivation_ref"], added_by=added_by, notes=f["notes"])
        counts["new_adjacent"] += 1

    for f in DOCUMENTATION_ONLY_NOTE_UPDATES:
        register_factor(conn, factor_key=f["factor_key"], name=f["name"], category=f["category"],
                         direction=f["direction"], stated_min=f["stated_min"], stated_max=f["stated_max"],
                         data_type="numeric", status="candidate", computation_type=None,
                         added_by=added_by, notes=f["notes"])
        counts["documentation_only"] += 1

    for f in MANUAL_DEAL_TERM_FACTORS:
        register_factor(conn, factor_key=f["factor_key"], name=f["name"], category=f["category"],
                         direction=f["direction"], stated_min=f["stated_min"], stated_max=f["stated_max"],
                         data_type="numeric", status="candidate", computation_type="eav",
                         added_by=added_by, notes=f["name"] + "." + MANUAL_DEAL_TERM_NOTE_SUFFIX)
        counts["manual_eav"] += 1

    return counts


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
        counts = register_feature7_factors(conn, added_by=getpass.getuser())
    finally:
        conn.close()
    total = sum(counts.values())
    print(f"Registered/updated {total} Feature 7 factor_definitions rows (status=candidate): {counts}")


if __name__ == "__main__":
    main()
