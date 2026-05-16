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
 * Party-scoped marketing endpoints, parallel to {@link GenreMarketingAPI}.
 * Backed by {@link EntityMarketingService}; entities are sourced from
 * {@code entity_keywords} rows with {@code category = 'media.politics'}, and
 * the path parameter {@code {party}} is matched against
 * {@code entity_keywords.keyword} (case-insensitive).
 */
@RestController
@RequestMapping("/api/marketing/party")
public class PoliticalMarketingAPI {

    private static final String CATEGORY      = "media.politics";
    private static final int    SPREADER_LIMIT = 50;

    @Autowired private EntityMarketingService service;

    // GET /api/marketing/party
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> listParties() {
        List<Map<String, Object>> parties = service.listEntities(CATEGORY, "state");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category",     CATEGORY);
        body.put("totalParties", parties.size());
        body.put("parties",      parties);
        return ResponseEntity.ok(body);
    }

    // GET /api/marketing/party/{party}/potential-voters
    @GetMapping("/{party}/potential-voters")
    public ResponseEntity<Map<String, Object>> potentialVoters(@PathVariable String party) {
        List<Map<String, Object>> voters = service.potentialAudience(party);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("party",        party);
        body.put("scoringModel", "p_conv = 1 / (1 + exp(-(affinity_score * influence_rank)))");
        body.put("totalVoters",  voters.size());
        body.put("voters",       voters);
        return ResponseEntity.ok(body);
    }

    // GET /api/marketing/party/{party}/super-spreaders
    @GetMapping("/{party}/super-spreaders")
    public ResponseEntity<Map<String, Object>> superSpreaders(@PathVariable String party) {
        List<Map<String, Object>> spreaders = service.topSpreaders(party, SPREADER_LIMIT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("party",          party);
        body.put("limit",          SPREADER_LIMIT);
        body.put("rankingMetric",  "hawkes_alpha (stored as influence_rank)");
        body.put("totalSpreaders", spreaders.size());
        body.put("spreaders",      spreaders);
        return ResponseEntity.ok(body);
    }

    // GET /api/marketing/party/{party}/channel-strategy
    @GetMapping("/{party}/channel-strategy")
    public ResponseEntity<Map<String, Object>> channelStrategy(@PathVariable String party) {
        Map<String, Object> strategy = service.channelStrategy(party, party + " supporters");
        long audience = service.audienceSize(party);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("party",        party);
        body.put("audienceSize", audience);
        body.putAll(strategy);
        return ResponseEntity.ok(body);
    }
}
