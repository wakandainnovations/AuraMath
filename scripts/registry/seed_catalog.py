"""One-time seed data for `factor_definitions` (Feature 2).

This is the 80-entry business-supplied factor catalogue that used to live as
a hardcoded `FACTOR_CATALOG` list inside `movie_revenue_impact_model.py`
(categories Narrative/Cast/Production/Marketing/Timing/Legal/Financial, each
with a business-stated min/max impact range). It has been relocated here so
that `migrate_factor_definitions.py` can seed `factor_definitions` from it
*once* -- after that migration runs, `movie_revenue_impact_model.py` reads
the live `factor_definitions` table exclusively and never imports this
module. Do not add new factors here; register them via
`scripts/register_factor.py` or `POST /api/admin/factor-definitions`
instead -- that's the whole point of the registry.

Of the 80 entries:
  - 12 are wired to real data today (`status='active'`): 10 computed via a
    Python function (`computation_type='derived_python_fn'`, `derivation_ref`
    pointing at the function's key in `movie_revenue_impact_model.py`'s
    `DERIVED_FACTOR_FNS` registry) and 2 read directly off a
    `movies_data_collection` column that ConflictBalanceService/
    NarrativeNoveltyService populate (`computation_type='raw_column'`).
  - 3 are `status='explanatory_only'` -- they describe a property of the
    finished film (twist landing, casting fit, subplot integration) that
    can never be known pre-release; see Group D' in the plan doc. This
    status is a permanent classification, not a step on the path to
    'active'.
  - 3 are `status='deprecated'` with a `notes` explanation -- no public
    source discloses them for individual films, ever (distributor/
    financier-internal operations data); see Group D'' in the plan doc.
    `deprecated` here does not mean "was active and got turned off", it
    means "not achievable with more collection effort", which is why they
    are seeded straight into this status rather than as a `candidate`.
  - The remaining 62 are `status='candidate'` with no computation path yet
    (`computation_type=None`) -- literature-prior-only until a future
    feature (3/5/6/7 in the plan, or anything registered later) gives them
    a real `source_table`/`source_column`/`derivation_ref`.
"""
from __future__ import annotations

EXPLANATORY_ONLY_KEYS = {"twist_effectiveness", "miscasting", "romantic_track_integration"}

# Group D'' -- perfectly real, knowable facts, but no public source discloses
# them for individual films (internal distributor/financier/exhibitor
# operations data), so `candidate` (which implies "achievable with more
# collection effort") would be misleading.
NO_PUBLIC_SOURCE_KEYS = {"kdm_lockout", "high_interest_financing", "producer_debt_solvency"}
NO_PUBLIC_SOURCE_NOTE = (
    "No public source discloses this for individual films -- internal "
    "distributor/financier/exhibitor operations data, not something any "
    "news outlet, trade press, or government registry publishes. Not a "
    "movie_factor_values candidate either without a direct industry "
    "partnership; seeded deprecated rather than candidate since more data "
    "collection effort alone can't fix this."
)

# factor_key -> (computation_type, derivation_ref, source_table, source_column)
# for the 12 factors this schema can measure directly today. Absent from this
# dict => computation_type/derivation_ref/source_table/source_column all None
# (a pure literature-prior candidate, or explanatory_only/deprecated).
MEASURABLE_WIRING: dict[str, tuple[str, str | None, str | None, str | None]] = {
    "conflict_balance": ("raw_column", None, "movies_data_collection", "conflict_balance_score"),
    "narrative_novelty": ("raw_column", None, "movies_data_collection", "narrative_novelty_score"),
    "star_overexposure": ("derived_python_fn", "star_overexposure", None, None),
    "excessive_runtime": ("derived_python_fn", "excessive_runtime", "movies_data_collection", "runtime_mins"),
    "budget_scale_efficiency": ("derived_python_fn", "budget_scale_efficiency", "movies_data_collection", "budget"),
    "trailer_teaser_impact": ("derived_python_fn", "trailer_teaser_impact", "movies_data_collection", "trailer_days_to_release"),
    "first_single_timing": ("derived_python_fn", "first_single_timing", "movies_data_collection", "song_days_to_release"),
    "holiday_release_window": ("derived_python_fn", "holiday_release_window", "movies_data_collection", "release_date"),
    "box_office_clashes": ("derived_python_fn", "box_office_clashes", "movies_data_collection", "release_date"),
    "exam_schedules": ("derived_python_fn", "exam_schedules", "movies_data_collection", "release_date"),
    "ipl_sporting_events": ("derived_python_fn", "ipl_sporting_events", "movies_data_collection", "release_date"),
    "summer_vacation_window": ("derived_python_fn", "summer_vacation_window", "movies_data_collection", "release_date"),
}

# (id, key, name, category, direction, stated_min, stated_max, proxy_note)
# Exact 80-row catalogue previously hardcoded as FACTOR_CATALOG.
_RAW_CATALOG: list[tuple] = [
    # --- Category 1: Narrative Architecture & Screenplay Engineering -----
    (1, "conflict_balance", "Protagonist-Antagonist Conflict Balance", "Narrative", "Positive", 0.25, 0.35,
     "movies_data_collection.conflict_balance_score (ConflictBalanceService, corpus-relative synopsis sentiment balance)"),
    (2, "narrative_novelty", "High-Concept Narrative Novelty", "Narrative", "Positive", 0.30, 0.45,
     "movies_data_collection.narrative_novelty_score (NarrativeNoveltyService, embedding-distance percentile)"),
    (4, "genre_template_adherence", "Genre Template Adherence vs. Subversion", "Narrative", "Bidirectional", -0.20, 0.20,
     "No genre-adherence score in schema; would require a subversion-detection model over synopsis text"),
    (11, "romantic_track_integration", "Romantic Track Integration", "Narrative", "Bidirectional", -0.15, 0.15,
     "No subplot-quality signal available"),
    (15, "twist_effectiveness", "Twist Effectiveness and Unpredictability", "Narrative", "Positive", 0.20, 0.30,
     "No third-act / twist annotation in schema"),

    # --- Category 2: Cast Capital, Persona Alignment, Controversies ------
    (16, "persona_fit", "Star-to-Character Persona Fit", "Cast", "Bidirectional", -0.40, 0.40, "No persona/role-alignment signal"),
    (17, "fanbase_mobilization", "Core Fanbase Mobilization Value", "Cast", "Positive", 0.30, 0.50,
     "Related to baseline R_star (role_position + actor popularity) but reported separately as a baseline anchor, not double-counted here"),
    (18, "lead_chemistry", "Lead Actor Screen Chemistry", "Cast", "Positive", 0.20, 0.35, "No pairing/chemistry signal"),
    (19, "support_cast_credibility", "Support Cast Performance Credibility", "Cast", "Positive", 0.15, 0.25, "No supporting-cast quality signal"),
    (20, "director_brand_equity", "Directorial Brand Equity", "Cast", "Positive", 0.25, 0.40,
     "Captured as baseline anchor R_director, reported separately to avoid double-counting"),
    (21, "anti_hero_appeal", "Anti-Hero Appeal and Moral Ambiguity", "Cast", "Positive", 0.20, 0.30, "No character-morality annotation"),
    (22, "actor_controversy", "Off-Screen Actor Controversy", "Cast", "Bidirectional", -0.35, 0.35, "No controversy/news-event feed"),
    (23, "star_overexposure", "Star Satiation and Screen Overexposure", "Cast", "Negative", -0.25, -0.15,
     "actors_data_collection: count of a movie's lead/support actors' OTHER releases in the trailing 12 months"),
    (24, "event_speech_impact", "Off-Script Event Speech Impact", "Cast", "Bidirectional", -0.15, 0.15, "No promotional-event transcript data"),
    (25, "actor_vulnerability", "Lead Actor Vulnerability and Range", "Cast", "Positive", 0.15, 0.25, "No performance-range signal"),
    (26, "multi_generational_appeal", "Multi-Generational Appeal of the Star", "Cast", "Positive", 0.25, 0.35, "No demographic-reach signal"),
    (27, "miscasting", "Miscasting and Role Incongruence", "Cast", "Negative", -0.35, -0.20, "No casting-fit signal"),
    (28, "nostalgic_reunion", "Nostalgic Screen Reunions", "Cast", "Positive", 0.20, 0.30, "No reliable prior-pairing-gap detector at this data quality"),
    (29, "political_dialogue", "Star Political Aspirations / Dialogue Placement", "Cast", "Bidirectional", -0.20, 0.20, "No dialogue-content data"),
    (30, "cameo_appearances", "Cameo Appearances of Iconic Stars", "Cast", "Positive", 0.15, 0.25, "role_position doesn't distinguish cameo billing"),

    # --- Category 3: Production Scale, Visual Assets, Technical Logistics -
    (31, "vfx_quality", "Technical Quality of Visual Effects (VFX)", "Production", "Bidirectional", -0.30, 0.30, "No VFX rating column"),
    (32, "sound_design", "Immersive Sound Design and Mixing", "Production", "Positive", 0.10, 0.20, "No sound-design rating"),
    (33, "action_choreography", "Action Sequence Choreography Innovation", "Production", "Positive", 0.20, 0.35, "No choreography rating"),
    (34, "bgm_impact", "Background Score (BGM) Impact", "Production", "Positive", 0.25, 0.40, "No score/BGM rating"),
    (35, "production_design_scale", "Production Design and Architectural Scale", "Production", "Positive", 0.20, 0.30, "No art-direction rating"),
    (36, "cinematography", "Realistic Color Grading and Cinematography", "Production", "Positive", 0.10, 0.20, "No cinematography rating"),
    (37, "excessive_runtime", "Excessive Runtime and Editing Lag", "Production", "Negative", -0.30, -0.15,
     "movies_data_collection.runtime_mins, penalty scaling in for runtimes above 160 minutes"),
    (38, "editing_pacing", "Dynamic Editing and Transition Pacing", "Production", "Positive", 0.10, 0.15, "No editing-pace rating"),
    (39, "period_authenticity", "Authenticity of Period / Cultural Setting", "Production", "Positive", 0.15, 0.25, "No period-setting flag"),
    (40, "budget_scale_efficiency", "Budget-to-Scale Efficiency", "Production", "Bidirectional", -0.20, 0.20,
     "budget percentile within (primary genre, release year) peer group -- lean-budget films score toward the positive end"),
    (41, "flashback_animation", "Use of Animation for Complex Flashbacks", "Production", "Positive", 0.10, 0.15, "No animation-usage flag"),
    (42, "intrusive_song_placement", "Excessive and Intrusive Song Placements", "Production", "Negative", -0.25, -0.15, "No scene-level song-placement data"),
    (43, "location_novelty", "Location Novelty and Aesthetic Variety", "Production", "Positive", 0.10, 0.15, "No filming-location data"),
    (44, "practical_vs_green_screen", "Live Action over Heavy Green-Screen", "Production", "Positive", 0.15, 0.25, "No production-technique flag"),
    (45, "graphic_violence", "Overuse of Graphic / Gratuitous Violence", "Production", "Bidirectional", -0.15, 0.15, "No content-intensity rating"),

    # --- Category 4: Pre-Release Marketing and Promotional Levers --------
    (46, "trailer_teaser_impact", "Teaser and Trailer Impact", "Marketing", "Positive", 0.35, 0.50,
     "movies_data_collection.trailer_days_to_release timed against the -15%/+25% thresholds in the brief, scaled by trailer_views/comments"),
    (47, "first_single_timing", "Timing of First Single Release", "Marketing", "Positive", 0.15, 0.25,
     "movies_data_collection.song_days_to_release; 6-8 week lead-up window scored as optimal"),
    (48, "brand_extension_naming", "Use of Brand Extensions / Sequel Names", "Marketing", "Bidirectional", -0.30, 0.30,
     "Franchise detection is captured as baseline anchor R_IP; not separately calibrated here to avoid double-counting"),
    (49, "viral_audio_trends", "Viral Music and Social Media Audio Trends", "Marketing", "Positive", 0.20, 0.35, "No social-audio virality feed"),
    (50, "promotional_controversy", "Pre-Release Promotional Controversies", "Marketing", "Bidirectional", -0.15, 0.15, "No controversy feed"),
    (51, "on_ground_events", "Star Attendance at On-Ground Events", "Marketing", "Positive", 0.10, 0.20, "No event-attendance data"),
    (52, "micro_video_campaigns", "Micro-Video Social Media Campaigns", "Marketing", "Positive", 0.15, 0.25, "No campaign-spend/reach data"),
    (53, "influencer_promotion", "Influencer-Driven Promotions", "Marketing", "Positive", 0.10, 0.15, "No influencer-campaign data"),
    (54, "misleading_trailer", "Misleading Trailer Marketing", "Marketing", "Negative", -0.40, -0.25, "No trailer-content-vs-film-tone comparison"),
    (55, "bts_promo_content", "High-Definition Promo / BTS Content", "Marketing", "Positive", 0.10, 0.15, "No BTS content metadata"),
    (56, "countdown_posters", "Strategic Use of Countdown Posters", "Marketing", "Positive", 0.05, 0.10, "No poster-campaign data"),
    (57, "oversaturated_marketing", "Excessive / Over-Saturated Marketing", "Marketing", "Negative", -0.15, -0.10, "No marketing-spend/frequency data"),
    (58, "brand_partnerships", "Cross-Promotion and Brand Partnerships", "Marketing", "Positive", 0.10, 0.20, "No brand-tie-in data"),
    (59, "dynamic_ticket_pricing", "Dynamic Pre-Release Ticket Pricing", "Marketing", "Bidirectional", -0.15, 0.15, "No ticket-pricing data"),
    (60, "global_promo_tours", "Global Promotional Tours", "Marketing", "Positive", 0.15, 0.25, "No tour/appearance schedule data"),

    # --- Category 5: Temporal Scheduling, Holiday Windows, Market Dynamics
    (61, "holiday_release_window", "Holiday Release Windows", "Timing", "Positive", 0.40, 0.60,
     "release_date scored against an approximate Indian festive-calendar window table"),
    (62, "box_office_clashes", "Direct Box Office Clashes", "Timing", "Negative", -0.35, -0.20,
     "count of other same-language releases within +/-3 days in movies_data_collection"),
    (63, "exam_schedules", "Student Examination Schedules", "Timing", "Negative", -0.25, -0.15,
     "release month falls in the Feb-Apr Indian board-exam season"),
    (64, "political_events", "Political Events and Elections", "Timing", "Negative", -0.40, -0.20,
     "No per-state election-calendar table; national-only election-year flag was judged too coarse to calibrate reliably"),
    (65, "ipl_sporting_events", "Major Sporting Events (e.g., IPL)", "Timing", "Negative", -0.20, -0.10,
     "release month falls in the Mar-May IPL season"),
    (66, "summer_vacation_window", "Academic Summer Vacation Windows", "Timing", "Positive", 0.25, 0.35,
     "release month falls in the Apr-Jun summer-vacation window"),
    (67, "extreme_weather", "Extreme Weather Conditions", "Timing", "Negative", -0.15, -0.10, "No weather data joined to release date/region"),
    (68, "ott_window_strategy", "Theatrical Window / OTT Release Strategy", "Timing", "Bidirectional", -0.20, 0.20, "No OTT-release-date column"),
    (69, "post_clash_spillover", "Post-Clash Spillover Audience", "Timing", "Positive", 0.10, 0.15, "No screen-sellout/spillover data"),
    (70, "re_release_nostalgia", "Re-Release Timing and Nostalgia", "Timing", "Positive", 0.10, 0.20, "No re-release flag distinguishable from original release"),

    # --- Category 6: Legal, Administrative, and Censorship Barriers ------
    (71, "cbfc_rating", "CBFC Rating Classifications (U vs. UA/A)", "Legal", "Bidirectional", -0.30, 0.30, "No certification column"),
    (72, "state_bans", "Multi-State Political / Cultural Bans", "Legal", "Negative", -0.50, -0.30, "No ban/restriction data"),
    (73, "pre_release_leak", "High-Definition Pre-Release Leak", "Legal", "Negative", -0.80, -0.60, "No piracy/leak-event data"),
    (74, "title_ownership_disputes", "Legal Disputes over Title Ownership", "Legal", "Negative", -0.25, -0.15, "No litigation data"),
    (75, "copyright_claims", "Copyright Claims on Visuals / Audio", "Legal", "Negative", -0.35, -0.20, "No litigation data"),
    (76, "tax_exemptions", "Regional Entertainment Tax Exemptions", "Legal", "Positive", 0.15, 0.30, "No state-tax-policy data"),
    (77, "distribution_disputes", "Inter-State Distribution Disputes", "Legal", "Negative", -0.30, -0.15, "No distributor-relationship data"),
    (78, "plagiarism_remake_rights", "Plagiarism Allegations and Remake Laws", "Legal", "Bidirectional", -0.15, 0.15, "No plagiarism/remake-rights data"),
    (79, "name_similarity_disputes", "Real-Life Personality Name Similarities", "Legal", "Negative", -0.20, -0.10, "No name-collision data"),
    (80, "certification_delays", "Administrative Delays in Certifications", "Legal", "Negative", -0.40, -0.20, "No certification-timeline data"),

    # --- Category 7: Financial Controls and Distribution Models ----------
    (81, "kdm_lockout", "Digital Key Delivery Message (KDM) Lockout", "Financial", "Negative", -0.60, -0.40, "No KDM/delivery-status data"),
    (82, "minimum_guarantee_deals", "Minimum Guarantee (MG) Distribution", "Financial", "Positive", 0.20, 0.35, "No deal-structure data"),
    (83, "outright_purchase_sales", "Outright Purchase Territorial Sales", "Financial", "Positive", 0.15, 0.25, "No deal-structure data"),
    (84, "high_interest_financing", "High Interest Rates on Film Finance", "Financial", "Negative", -0.30, -0.15, "No financing-terms data"),
    (85, "multiplex_revenue_splits", "Multiplex Revenue Share Splits", "Financial", "Bidirectional", -0.20, 0.20,
     "distributor_share_usd exists but is a post-hoc revenue decomposition, not a pre-release input; using it as a predictor would leak the target"),
    (86, "subtitle_dubbing_quality", "Global Subtitle / Dubbing Quality", "Financial", "Positive", 0.15, 0.25, "No localization-quality rating"),
    (87, "screen_count_allocation", "Screen Count Allocation and Show Pacing", "Financial", "Positive", 0.25, 0.40, "No screen-count data"),
    (88, "pa_commitments", "Print & Advertising (P&A) Commitments", "Financial", "Positive", 0.20, 0.30, "No P&A-budget data"),
    (89, "joint_production_partnerships", "Joint Production Partnerships", "Financial", "Positive", 0.15, 0.25,
     "production_companies exists but co-production vs. sole-production quality signal is unreliable at this data quality"),
    (90, "producer_debt_solvency", "Producer Debt and Studio Solvency", "Financial", "Negative", -0.45, -0.25, "No studio-financials data"),
]


def seed_rows() -> list[dict]:
    """Materializes _RAW_CATALOG into register_factor()-ready dicts, with
    status/computation_type/derivation_ref/source_table/source_column set per
    the wiring rules documented at module level."""
    rows = []
    for (fid, key, name, category, direction, stated_min, stated_max, proxy_note) in _RAW_CATALOG:
        if key in EXPLANATORY_ONLY_KEYS:
            status = "explanatory_only"
        elif key in NO_PUBLIC_SOURCE_KEYS:
            status = "deprecated"
        elif key in MEASURABLE_WIRING:
            status = "active"
        else:
            status = "candidate"

        computation_type, derivation_ref, source_table, source_column = (None, None, None, None)
        if key in MEASURABLE_WIRING:
            computation_type, derivation_ref, source_table, source_column = MEASURABLE_WIRING[key]

        notes = proxy_note
        if key in NO_PUBLIC_SOURCE_KEYS:
            notes = f"{proxy_note}. {NO_PUBLIC_SOURCE_NOTE}"

        rows.append({
            "factor_key": key, "name": name, "category": category, "direction": direction,
            "stated_min": stated_min, "stated_max": stated_max, "data_type": "numeric",
            "status": status, "source_table": source_table, "source_column": source_column,
            "computation_type": computation_type, "derivation_ref": derivation_ref,
            "added_by": "migrate_factor_definitions.py", "notes": notes,
            "catalog_id": fid,
        })
    return rows
