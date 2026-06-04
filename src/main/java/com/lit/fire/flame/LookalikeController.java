package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/marketing")
public class LookalikeController {

    @Autowired
    private LookalikeDiscoveryService lookalikeDiscoveryService;

    @PostMapping("/find-lookalikes")
    public ResponseEntity<?> findLookalikes(@RequestBody Map<String, String> body) {
        String seedAuthorId = body.get("seedAuthorId");
        if (seedAuthorId == null || seedAuthorId.isBlank()) {
            return ResponseEntity.badRequest().body("seedAuthorId is required");
        }
        try {
            List<Map<String, Object>> lookalikes = lookalikeDiscoveryService.findLookalikes(seedAuthorId, 100);
            return ResponseEntity.ok(lookalikes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** Side-by-side comparison of the legacy L2 ranking vs. the current block-wise method. */
    @GetMapping("/find-lookalikes/diff")
    public ResponseEntity<?> diffLookalikes(@RequestParam String seedAuthorId,
                                            @RequestParam(defaultValue = "25") int limit) {
        if (seedAuthorId == null || seedAuthorId.isBlank()) {
            return ResponseEntity.badRequest().body("seedAuthorId is required");
        }
        try {
            return ResponseEntity.ok(lookalikeDiscoveryService.diffLookalikes(seedAuthorId, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
