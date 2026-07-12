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

    @PostMapping("/run-enrichment")
    public ResponseEntity<String> runEnrichment() {
        marketingEnrichmentEngine.enrichAndSave();
        return ResponseEntity.ok("done");
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
}
