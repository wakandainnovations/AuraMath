package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Lookup and bulk-sync endpoints for the persisted {@code author_categories}
 * table.  The table itself is populated each time {@code /user-report/{author}}
 * runs; this controller lets the marketing team list users by category without
 * recomputing reports.
 */
@RestController
@RequestMapping("/api/marketing")
public class AuthorCategoryController {

    @Autowired private AuthorCategoryRepository  repository;
    @Autowired private HawkesAuditService        auditService;
    @Autowired private MarketingUserReportController reportController;
    @Autowired private JdbcTemplate              jdbcTemplate;

    // -------------------------------------------------------------------------
    // GET /api/marketing/users
    //
    // Returns the persisted categorisation for every author matching the
    // (optional) filter set.  Examples:
    //   /api/marketing/users
    //   /api/marketing/users?audienceClassification=Brand%20Evangelist
    //   /api/marketing/users?influenceTier=Viral%20Node&dominantTone=positive
    // -------------------------------------------------------------------------

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(required = false) String audienceClassification,
            @RequestParam(required = false) String influenceTier,
            @RequestParam(required = false) String postingStyle,
            @RequestParam(required = false) String dominantTone,
            @RequestParam(required = false) String primaryPlatform) {

        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("audience_classification", audienceClassification);
        filters.put("influence_tier",          influenceTier);
        filters.put("posting_style",           postingStyle);
        filters.put("dominant_tone",           dominantTone);
        filters.put("primary_platform",        primaryPlatform);

        List<Map<String, Object>> rows = repository.find(filters);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("filtersApplied", stripNulls(filters));
        resp.put("totalUsers",     rows.size());
        resp.put("users",          rows);
        return ResponseEntity.ok(resp);
    }

    // -------------------------------------------------------------------------
    // GET /api/marketing/users/categories
    //
    // Helper: returns the distinct values present in each categorical column
    // so the UI knows what's available to filter by.
    // -------------------------------------------------------------------------

    @GetMapping("/users/categories")
    public ResponseEntity<Map<String, List<String>>> categories() {
        return ResponseEntity.ok(repository.distinctValues());
    }

    // -------------------------------------------------------------------------
    // POST /api/marketing/users/sync
    //
    // Walks every author with at least one valid scored post, runs the same
    // categorisation as the report endpoint, and upserts the result.
    // Without this the table only fills in for authors whose report has been
    // explicitly viewed.
    // -------------------------------------------------------------------------

    @PostMapping("/users/sync")
    public ResponseEntity<Map<String, Object>> sync() {
        List<String> authors = jdbcTemplate.queryForList(
            "SELECT author FROM (" +
            "  SELECT author FROM x_posts          WHERE sentiment_score BETWEEN 1 AND 100 " +
            "  UNION SELECT author FROM youtube_comments WHERE sentiment_score BETWEEN 1 AND 100 " +
            "  UNION SELECT author FROM reddit_posts     WHERE sentiment_score BETWEEN 1 AND 100 " +
            "  UNION SELECT author FROM instagram_posts  WHERE sentiment_score BETWEEN 1 AND 100 " +
            ") t WHERE author IS NOT NULL AND author <> '' GROUP BY author",
            String.class);

        // Drop rows from previous runs that no longer meet the >MIN_POSTS rule.
        int purged = repository.purgeBelowThreshold();

        int upserted = 0, skippedEmpty = 0, skippedFewPosts = 0, failed = 0;
        for (String author : authors) {
            try {
                HawkesAuditService.AuditResult result = auditService.compute(author);
                if (result.isEmpty()) { skippedEmpty++; continue; }
                List<MarketingUserReportController.BurstRegion> bursts =
                    reportController.extractBurstRegions(result.entries);
                int distinctBursts = auditService.countDistinctClusters(result.entries);
                if (repository.upsert(reportController.categorize(result, bursts, distinctBursts)))
                    upserted++;
                else
                    skippedFewPosts++;
            } catch (Exception e) {
                failed++;
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rule",                 "Only authors with total_posts > " + AuthorCategoryRepository.MIN_POSTS + " are categorised");
        resp.put("totalAuthorsScanned",  authors.size());
        resp.put("upserted",             upserted);
        resp.put("skippedFewPosts",      skippedFewPosts);
        resp.put("skippedEmpty",         skippedEmpty);
        resp.put("staleRowsPurged",      purged);
        resp.put("failed",               failed);
        return ResponseEntity.ok(resp);
    }

    // -------------------------------------------------------------------------
    // Scheduled re-categorisation: runs sync() every 24 hours so newly-arrived
    // posts are folded into each author's classification. Initial delay keeps
    // app startup snappy; the lock interval is generous because a full sync
    // can take several minutes on a populated table.
    // -------------------------------------------------------------------------

    @Scheduled(
        fixedRate    = 24L * 60L * 60L * 1000L,
        initialDelay =        5L * 60L * 1000L,
        timeUnit     = TimeUnit.MILLISECONDS)
    public void scheduledResync() {
        try {
            sync();
        } catch (Exception e) {
            // Swallow: next tick will retry. Avoid taking down the scheduler thread.
        }
    }

    // -------------------------------------------------------------------------

    private Map<String, String> stripNulls(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> { if (v != null && !v.isEmpty()) out.put(k, v); });
        return out;
    }
}
