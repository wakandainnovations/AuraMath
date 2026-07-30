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
    private CrossPlatformIdentityResolver crossPlatformIdentityResolver;

    @Autowired
    private NarrativeNoveltyService narrativeNoveltyService;

    @Autowired
    private ConflictBalanceService conflictBalanceService;

    @Autowired
    private UserEngagementRatingService userEngagementRatingService;

    @PostMapping("/run-enrichment")
    public ResponseEntity<String> runEnrichment() {
        marketingEnrichmentEngine.enrichAndSave();
        return ResponseEntity.ok("done");
    }

    @PostMapping("/run-engagement-rating")
    public ResponseEntity<Map<String, Object>> runEngagementRating() {
        return ResponseEntity.ok(userEngagementRatingService.recomputeAndPersist());
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
}
