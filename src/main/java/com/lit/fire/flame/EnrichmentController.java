package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class EnrichmentController {

    @Autowired
    private MarketingEnrichmentEngine marketingEnrichmentEngine;

    @Autowired
    private CrossPlatformIdentityResolver crossPlatformIdentityResolver;

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
}
