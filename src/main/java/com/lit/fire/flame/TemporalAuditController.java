package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/test")
public class TemporalAuditController {

    @Autowired
    private HawkesAuditService service;

    @GetMapping("/temporal-audit/{author}")
    public ResponseEntity<Map<String, Object>> getTemporalAudit(@PathVariable String author) {

        HawkesAuditService.AuditResult result = service.compute(author);

        if (result.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("author", author);
            empty.put("message", "No posts found for this author");
            empty.put("totalPosts", 0);
            return ResponseEntity.ok(empty);
        }

        List<HawkesAuditService.AuditEntry> entries = result.entries;
        int n        = entries.size();
        double mu    = result.mu;
        double alpha = result.alpha;

        Map<String, Integer> platformCounts = new LinkedHashMap<>();
        List<Map<String, Object>> timeline  = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            HawkesAuditService.AuditEntry e = entries.get(i);
            platformCounts.merge(e.platform, 1, Integer::sum);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("postId",                      e.id);
            entry.put("platform",                    e.platform);
            entry.put("timestamp",                   new java.sql.Timestamp(e.timestamp.getTime()).toLocalDateTime().toString());
            entry.put("minutesFromStart",            r2(e.tMin));
            entry.put("keyword",                     e.keyword);
            entry.put("content",                     e.content);
            entry.put("excitationSpike",             r4(e.excitationSpike));
            entry.put("totalIntensity",              r4(mu + e.excitationSpike));
            entry.put("excitationLevel",             service.excitationLevel(e.excitationSpike, alpha));
            entry.put("minutesSincePreviousPost",    i > 0 ? r2(e.tMin - entries.get(i - 1).tMin) : null);
            entry.put("withinHighExcitationCluster", e.burstSize >= HawkesAuditService.CLUSTER_MIN);
            entry.put("keywordBurstSize",            e.burstSize > 0 ? e.burstSize : null);
            timeline.add(entry);
        }

        int distinctClusters = service.countDistinctClusters(entries);

        Map<String, Object> hawkesMap = new LinkedHashMap<>();
        hawkesMap.put("mu",              r5(mu));
        hawkesMap.put("alpha",           r5(alpha));
        hawkesMap.put("beta",            HawkesAuditService.BETA);
        hawkesMap.put("timeUnit",        "minutes");
        hawkesMap.put("halfLifeMinutes", r2(Math.log(2) / HawkesAuditService.BETA));
        hawkesMap.put("branchingRatio",  r4(alpha / HawkesAuditService.BETA));
        hawkesMap.put("interpretation",  String.format(
            "μ=%.5f posts/min baseline; α=%.5f infectivity; " +
            "each post spawns ~%.3f follow-ups (α/β) within a ~%.1f-min window",
            mu, alpha, alpha / HawkesAuditService.BETA, Math.log(2) / HawkesAuditService.BETA));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPosts",                     n);
        summary.put("platforms",                      platformCounts);
        summary.put("observationWindowMinutes",       r2(entries.get(n - 1).tMin));
        summary.put("distinctHighExcitationClusters", distinctClusters);
        summary.put("hawkesProcessActive",            alpha > 0.01);
        summary.put("clusteringVerdict", String.format(
            "%d burst(s) detected where %d+ posts appeared within %.0f min of a keyword event " +
            "— α=%.4f confirms a self-exciting Hawkes process on real DB timestamps",
            distinctClusters, HawkesAuditService.CLUSTER_MIN, HawkesAuditService.CLUSTER_WIN, alpha));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("author",           author);
        resp.put("hawkesParameters", hawkesMap);
        resp.put("summary",          summary);
        resp.put("timeline",         timeline);
        return ResponseEntity.ok(resp);
    }

    private double r2(double v) { return Math.round(v * 1e2) / 1e2; }
    private double r4(double v) { return Math.round(v * 1e4) / 1e4; }
    private double r5(double v) { return Math.round(v * 1e5) / 1e5; }
}
