package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class EnrichmentController {

    @Autowired
    private MarketingEnrichmentEngine marketingEnrichmentEngine;

    @Autowired
    private PlatformHandlesRefreshService platformHandlesRefreshService;

    @Autowired
    private CrossPlatformIdentityResolver crossPlatformIdentityResolver;

    @Autowired
    private NarrativeNoveltyService narrativeNoveltyService;

    @Autowired
    private ConflictBalanceService conflictBalanceService;

    @Autowired
    private UserEngagementRatingService userEngagementRatingService;

    @Autowired
    private GraphPopulationService graphPopulationService;

    @Autowired
    private VmiComputationService vmiComputationService;

    @Autowired
    private BehaviorFeatureComputationService behaviorFeatureComputationService;

    @Autowired
    private UserCausalLiftScoreService userCausalLiftScoreService;

    @PostMapping("/run-enrichment")
    public ResponseEntity<String> runEnrichment() {
        marketingEnrichmentEngine.enrichAndSave();
        return ResponseEntity.ok("done");
    }

    /**
     * Refreshes only platform_handles/profile_url — cheap (no NLP, no Hawkes fit), safe to run
     * far more often than the full /run-enrichment to close the attribution gap for authors
     * ingested since the last full run. See {@link PlatformHandlesRefreshService}.
     */
    @PostMapping("/run-platform-handles-refresh")
    public ResponseEntity<Map<String, Object>> runPlatformHandlesRefresh() {
        return ResponseEntity.ok(platformHandlesRefreshService.recomputeAndPersist());
    }

    @PostMapping("/run-engagement-rating")
    public ResponseEntity<Map<String, Object>> runEngagementRating() {
        return ResponseEntity.ok(userEngagementRatingService.recomputeAndPersist());
    }

    @PostMapping("/run-graph-population")
    public ResponseEntity<Map<String, Object>> runGraphPopulation() {
        return ResponseEntity.ok(graphPopulationService.recomputeAndPersist());
    }

    @PostMapping("/resolve-identities")
    public ResponseEntity<String> resolveIdentities() {
        int inserted = crossPlatformIdentityResolver.resolveIdentities();
        return ResponseEntity.ok("inserted=" + inserted);
    }

    @PostMapping("/recompute-narrative-novelty")
    public ResponseEntity<Map<String, Object>> recomputeNarrativeNovelty() {
        return ResponseEntity.ok(narrativeNoveltyService.recomputeAndPersist());
    }

    /** Same scoring algorithm, but persists into the legacy narrative_novelty_score column (no _v2 suffix). */
    @PostMapping("/recompute-narrative-novelty-v1")
    public ResponseEntity<Map<String, Object>> recomputeNarrativeNoveltyV1() {
        return ResponseEntity.ok(narrativeNoveltyService.recomputeAndPersistV1());
    }

    @PostMapping("/recompute-conflict-balance")
    public ResponseEntity<Map<String, Object>> recomputeConflictBalance() {
        return ResponseEntity.ok(conflictBalanceService.recomputeAndPersist());
    }

    @PostMapping("/run-vmi-computation")
    public ResponseEntity<Map<String, Object>> runVmiComputation() {
        return ResponseEntity.ok(vmiComputationService.recomputeAndPersist());
    }

    @PostMapping("/run-behavior-feature-computation")
    public ResponseEntity<Map<String, Object>> runBehaviorFeatureComputation() {
        return ResponseEntity.ok(behaviorFeatureComputationService.recomputeAndPersist());
    }

    @PostMapping("/run-causal-lift-scoring")
    public ResponseEntity<Map<String, Object>> runCausalLiftScoring() {
        return ResponseEntity.ok(userCausalLiftScoreService.recomputeAndPersist());
    }
}
