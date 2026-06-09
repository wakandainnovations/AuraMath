package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Celebrity Analytics API — active only for managed entities of type
 * {@code CELEBRITY}. It serves a single, mathematically-derived analytics
 * payload per celebrity, predicting commercial metrics from the cross-platform
 * conversation already tracked in the database:
 *
 *   • predictedBrandValueUsd – modelled annualised brand/endorsement value (USD)
 *   • socialMediaReachValue  – total cross-platform exposure
 *   • fanEngagementValue     – total cross-platform interactions
 *   • endorsementScore       – 0–100 paid-endorsement suitability
 *
 *   key percentage metrics (0–100): socialMediaInfluence, brandPower,
 *   fanLoyalty, controversyRisk
 *
 * The scoring is implemented by the pure {@link CelebrityMetricsModel} and wired
 * to the data by {@link CelebrityAnalyticsService}. Endpoints:
 *
 *   GET /api/analytics/celebrity              – list celebrities (type = CELEBRITY)
 *   GET /api/analytics/celebrity/{entityId}   – full analytics for one celebrity
 */
@RestController
@RequestMapping("/api/analytics/celebrity")
public class CelebrityAnalyticsAPI {

    @Autowired private CelebrityAnalyticsService service;

    /** List managed entities of type CELEBRITY available for analytics. */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> listCelebrities() {
        List<Map<String, Object>> celebrities = service.listCelebrities();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entityType",        CelebrityAnalyticsService.CELEBRITY_TYPE.toUpperCase());
        body.put("totalCelebrities",  celebrities.size());
        body.put("celebrities",       celebrities);
        return ResponseEntity.ok(body);
    }

    /**
     * Full celebrity analytics. Returns 404 when the id is unknown or the entity
     * is not a CELEBRITY, otherwise 200 with the analytics payload.
     */
    @GetMapping("/{entityId}")
    public ResponseEntity<Map<String, Object>> analytics(@PathVariable String entityId) {
        Map<String, Object> body = service.analytics(entityId);
        if (body.containsKey("message") && !body.containsKey("headlineMetrics")) {
            return ResponseEntity.status(404).body(body);
        }
        return ResponseEntity.ok(body);
    }
}
