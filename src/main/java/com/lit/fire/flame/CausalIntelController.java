package com.lit.fire.flame;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only endpoints over the F1 ({@code entity_daily_vmi}), F4 ({@code causal_precedence_edges}/
 * {@code causal_precedence_chains}), F5 ({@code nonobvious_lever_findings}) and F7
 * ({@code playbook_patterns}) tables - plain JdbcTemplate reads via {@link VmiComputationService}
 * and {@link CausalIntelQueryService}, same style as {@link EntityMarketingService}.
 *
 * <p>Every endpoint answers 200 even when its backing table has no rows yet for this entity/cohort:
 * an analysis that ran and found nothing, versus one that hasn't run at all (or this entity doesn't
 * clear the qualifying-history bar), are different facts. The latter is surfaced explicitly as
 * {@code {"status": "insufficient_history", "details": "..."}} rather than an empty 200 that would
 * look identical to "we looked and found nothing".
 */
@RestController
@RequestMapping("/api/marketing")
public class CausalIntelController {

    @Autowired private VmiComputationService   vmi;
    @Autowired private CausalIntelQueryService causalIntel;

    private final Gson gson = new Gson();

    // -------------------------------------------------------------------------
    // F1 - VMI series
    // -------------------------------------------------------------------------

    @GetMapping("/entity/{entityId}/vmi")
    public ResponseEntity<Map<String, Object>> entityVmi(@PathVariable long entityId) {
        List<Map<String, Object>> series = vmi.series(entityId);
        if (series.isEmpty()) {
            return insufficientHistory("No entity_daily_vmi rows for entity " + entityId +
                    " - VMI computation hasn't run for it yet, or it has no tracked mentions.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",   "ok");
        body.put("entityId", entityId);
        body.put("series",   series);
        body.put("peakDay",  vmi.peakDay(entityId));
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // F4 - causal precedence chains
    // -------------------------------------------------------------------------

    @GetMapping("/entity/{entityId}/causal-chains")
    public ResponseEntity<Map<String, Object>> causalChains(@PathVariable long entityId) {
        String cohort = causalIntel.resolveCohort(entityId);

        List<Map<String, Object>> chainRows = cohort != null ? causalIntel.chainsForCohort(cohort) : List.of();
        String resolvedCohort = cohort;
        boolean usedPooledFallback = false;
        if (chainRows.isEmpty()) {
            chainRows = causalIntel.chainsForCohort("ALL");
            resolvedCohort = "ALL";
            usedPooledFallback = true;
        }
        if (chainRows.isEmpty()) {
            return insufficientHistory("No causal_precedence_chains rows for cohort '" +
                    (cohort != null ? cohort : "unknown") + "' or the pooled 'ALL' fallback - " +
                    "the precedence batch hasn't run yet, or this entity's cohort doesn't have " +
                    "enough qualifying (21+ day history) entities.");
        }

        Map<EdgeKey, Long> supportByEdge = new HashMap<>();
        for (Map<String, Object> e : causalIntel.edgesForCohort(resolvedCohort)) {
            int lagDays = ((Number) e.get("lag_days")).intValue();
            supportByEdge.put(
                    new EdgeKey((String) e.get("from_series"), (String) e.get("to_series"), lagDays),
                    ((Number) e.get("n_entities_supporting")).longValue());
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        for (Map<String, Object> row : chainRows) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps =
                    (List<Map<String, Object>>) JsonbUtil.asTree(row.get("chain_json"), gson);

            List<Map<String, Object>> edges = new ArrayList<>();
            if (steps != null) {
                for (Map<String, Object> step : steps) {
                    String from = (String) step.get("from");
                    String to   = (String) step.get("to");
                    int    lag  = ((Number) step.get("lag")).intValue();
                    Long   nEntitiesSupporting = supportByEdge.get(new EdgeKey(from, to, lag));

                    Map<String, Object> edgeOut = new LinkedHashMap<>();
                    edgeOut.put("from_series",   from);
                    edgeOut.put("to_series",     to);
                    edgeOut.put("lag",           lag);
                    edgeOut.put("fdr_q_value",   step.get("q"));
                    edgeOut.put("effect_size_r2", step.get("effectSize"));
                    // Always surfaced (even null, for the rare case an edge fell out of
                    // causal_precedence_edges for this cohort) - never hidden, so a caller can
                    // always see how much evidence backs the chain.
                    edgeOut.put("n_entities_supporting", nEntitiesSupporting);
                    edges.add(edgeOut);
                }
            }

            Map<String, Object> chainOut = new LinkedHashMap<>();
            chainOut.put("pathScore", row.get("path_score"));
            chainOut.put("edges",     edges);
            chains.add(chainOut);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",             "ok");
        body.put("entityId",           entityId);
        body.put("cohort",             cohort);
        body.put("resolvedCohort",     resolvedCohort);
        body.put("usedPooledFallback", usedPooledFallback);
        body.put("chains",             chains);
        return ResponseEntity.ok(body);
    }

    private record EdgeKey(String from, String to, int lag) {}

    // -------------------------------------------------------------------------
    // F5 - nonobvious levers (always pooled, cohort='ALL')
    // -------------------------------------------------------------------------

    @GetMapping("/entity/{entityId}/nonobvious-levers")
    public ResponseEntity<Map<String, Object>> nonobviousLevers(@PathVariable long entityId) {
        List<Map<String, Object>> findings = causalIntel.nonobviousLeversPooled();
        if (findings.isEmpty()) {
            return insufficientHistory("No pooled ('ALL' cohort) nonobvious_lever_findings rows yet - " +
                    "the lever-miner batch hasn't run, or too few entities qualify for pooled analysis.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",   "ok");
        body.put("entityId", entityId);
        body.put("cohort",   "ALL");
        body.put("findings", findings);
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // F7 - playbook patterns
    // -------------------------------------------------------------------------

    @GetMapping("/playbook")
    public ResponseEntity<Map<String, Object>> playbook(
            @RequestParam String industry, @RequestParam String language) {
        String cohort = industry + "|" + language;

        List<Map<String, Object>> patternRows = causalIntel.playbookForCohort(cohort);
        String resolvedCohort = cohort;
        boolean usedPooledFallback = false;
        if (patternRows.isEmpty()) {
            patternRows = causalIntel.playbookForCohort("ALL");
            resolvedCohort = "ALL";
            usedPooledFallback = true;
        }
        if (patternRows.isEmpty()) {
            return insufficientHistory("No playbook_patterns rows for cohort '" + cohort +
                    "' or the pooled 'ALL' fallback - the playbook miner hasn't run yet, or too few " +
                    "entities qualify for cohort or pooled tiering.");
        }

        List<Map<String, Object>> patterns = new ArrayList<>();
        for (Map<String, Object> row : patternRows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("patternSequence",   JsonbUtil.asTree(row.get("pattern_sequence"), gson));
            p.put("supportTopTier",    row.get("support_top_tier"));
            p.put("supportBottomTier", row.get("support_bottom_tier"));
            p.put("pValue",            row.get("p_value"));
            p.put("fdrQValue",         row.get("fdr_q_value"));
            p.put("nEntities",         row.get("n_entities"));
            patterns.add(p);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",             "ok");
        body.put("industry",           industry);
        body.put("language",           language);
        body.put("cohort",             cohort);
        body.put("resolvedCohort",     resolvedCohort);
        body.put("usedPooledFallback", usedPooledFallback);
        body.put("patterns",           patterns);
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // Shared insufficient-history envelope
    // -------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> insufficientHistory(String details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",  "insufficient_history");
        body.put("details", details);
        return ResponseEntity.ok(body);
    }
}
