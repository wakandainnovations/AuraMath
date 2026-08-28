package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Celebrity-scoped marketing endpoints, parallel to {@link GenreMarketingAPI}.
 * Backed by {@link EntityMarketingService}; entities come from
 * {@code entity_keywords} rows with {@code category = 'media.celebrity'},
 * and the path parameter {@code {celebrity}} is matched against
 * {@code entity_keywords.keyword} (case-insensitive). Use {@code GET
 * /api/marketing/celebrity} to discover valid keyword values.
 */
@RestController
@RequestMapping("/api/marketing/celebrity")
public class CelebrityMarketingAPI {

    private static final String CATEGORY  = "media.celebrity";
    private static final int    FAN_LIMIT = 50;

    @Autowired private EntityMarketingService service;

    // GET /api/marketing/celebrity
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> listCelebrities() {
        List<Map<String, Object>> celebrities = service.listEntities(CATEGORY, "industry");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category",          CATEGORY);
        body.put("totalCelebrities",  celebrities.size());
        body.put("celebrities",       celebrities);
        return ResponseEntity.ok(body);
    }

    // GET /api/marketing/celebrity/{celebrity}/potential-fans
    @GetMapping("/{celebrity}/potential-fans")
    public ResponseEntity<Map<String, Object>> potentialFans(@PathVariable String celebrity) {
        List<Map<String, Object>> fans = service.potentialAudience(celebrity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("celebrity",    celebrity);
        body.put("scoringModel", "p_conv = 1 / (1 + exp(-(affinity_score * influence_rank)))");
        body.put("totalFans",    fans.size());
        body.put("fans",         fans);
        return ResponseEntity.ok(body);
    }

    // GET /api/marketing/celebrity/{celebrity}/posts
    @GetMapping("/{celebrity}/posts")
    public ResponseEntity<?> posts(@PathVariable String celebrity,
                                    @RequestParam(required = false) String platform,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 200) {
            return ResponseEntity.badRequest().body("limit must be between 1 and 200");
        }
        Map<String, Object> result = service.postsForKeyword(celebrity, platform, limit, offset);
        if (result == null) {
            return ResponseEntity.badRequest().body(
                    "Unknown platform '" + platform + "'. Must be one of: x, youtube, reddit, instagram");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("celebrity", celebrity);
        body.put("limit",     limit);
        body.put("offset",    offset);
        body.putAll(result);
        return ResponseEntity.ok(body);
    }

    // GET /api/marketing/celebrity/{celebrity}/super-fans
    @GetMapping("/{celebrity}/super-fans")
    public ResponseEntity<Map<String, Object>> superFans(@PathVariable String celebrity) {
        List<Map<String, Object>> superFans = service.topSpreaders(celebrity, FAN_LIMIT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("celebrity",      celebrity);
        body.put("limit",          FAN_LIMIT);
        body.put("rankingMetric",  "hawkes_alpha (stored as influence_rank)");
        body.put("totalSuperFans", superFans.size());
        body.put("superFans",      superFans);
        return ResponseEntity.ok(body);
    }

    // GET /api/marketing/celebrity/{celebrity}/channel-strategy
    @GetMapping("/{celebrity}/channel-strategy")
    public ResponseEntity<Map<String, Object>> channelStrategy(@PathVariable String celebrity) {
        Map<String, Object> strategy = service.channelStrategy(celebrity, celebrity + " fans");
        long audience = service.audienceSize(celebrity);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("celebrity",    celebrity);
        body.put("audienceSize", audience);
        body.putAll(strategy);
        return ResponseEntity.ok(body);
    }
}
