package com.lit.fire.flame;

import com.google.gson.Gson;
import com.lit.fire.flame.models.UniversalPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/marketing")
public class TopSpreadersController {

    private static final Logger log = LoggerFactory.getLogger(TopSpreadersController.class);

    private static final int TOP_N = 50;
    private static final double COMMENT_WEIGHT = 3.0;

    private final Gson gson = new Gson();

    /**
     * Minimum matching posts an author needs to enter the ranking. Authors below this are
     * dropped before scoring. Defaults to 1 (no gating); raise it to require repeat activity.
     * Single-post authors get a well-defined alpha = 0 from the Hawkes estimator, so a low
     * threshold is safe — it just admits sparse keywords (e.g. niche/new titles) that would
     * otherwise return empty.
     */
    @Value("${top-spreaders.min-posts:1}")
    private int minPosts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HawkesIntensityCalculator hawkesIntensityCalculator;

    /**
     * Returns the top 50 authors across X, YouTube, Reddit, and Instagram for posts matching
     * {keyword} in the last 90 days, ranked by Viral Potential Score:
     *
     *   VPS = (likes + 3 × comments) × (1 + α)
     *
     * The engagement count rewards authors whose audience actively reacts (not just passive
     * viewers), and the (1 + α) factor lets Hawkes infectivity boost bursty cascade-starters
     * without zeroing out high-engagement organic spreaders whose post cadence fits α ≈ 0.
     *
     * Comments are weighted 3× likes because they require materially more user effort and
     * correlate more strongly with downstream sharing. Authors need at least
     * {@code top-spreaders.min-posts} matching posts (default 1) to be ranked.
     *
     * average_sentiment_score is included so the team avoids seeding with high-influence detractors.
     *
     * Only x_posts tracks impressions, so views_count is 0 for the other three platforms
     * (COALESCE'd to 0) and the views_count > 0 floor is scoped to x_posts only — otherwise it
     * would silently exclude every YouTube/Reddit/Instagram author. likes_count/comment_count
     * are mapped from each platform's nearest equivalent column, matching the proxy convention
     * used elsewhere (e.g. TopicalSpreaderDetector, EntityMarketingService):
     *   youtube_comments → likes_count / reply_count
     *   reddit_posts      → score (net upvotes) / num_comments
     *   instagram_posts   → like_count / comments_count
     *
     * Each result includes profile_url — a link to the author's profile (their primary
     * platform's page, e.g. https://twitter.com/handle) sourced from
     * marketing_target_profiles.platform_handles, so a consumer can identify who the
     * author actually is — plus the full platform_handles breakdown per platform. Both are
     * null when the author hasn't been through MarketingEnrichmentEngine yet.
     */
    @GetMapping("/top-50-spreaders/{keyword}")
    public List<Map<String, Object>> getTopSpreaders(@PathVariable String keyword) {
        String sql =
                "WITH combined_posts AS (" +
                "  SELECT id, author, COALESCE(views_count, 0) AS views_count, COALESCE(likes_count, 0) AS likes_count, " +
                "         COALESCE(comment_count, 0) AS comment_count, sentiment_score, created_at, 'x_posts' AS platform " +
                "  FROM x_posts WHERE keyword ILIKE ? " +
                "  UNION ALL " +
                "  SELECT id, author, 0 AS views_count, COALESCE(likes_count, 0) AS likes_count, " +
                "         COALESCE(reply_count, 0) AS comment_count, sentiment_score, published_at AS created_at, 'youtube_comments' AS platform " +
                "  FROM youtube_comments WHERE keyword ILIKE ? " +
                "  UNION ALL " +
                "  SELECT id, author, 0 AS views_count, COALESCE(score, 0) AS likes_count, " +
                "         COALESCE(num_comments, 0) AS comment_count, sentiment_score, created_at, 'reddit_posts' AS platform " +
                "  FROM reddit_posts WHERE keyword ILIKE ? " +
                "  UNION ALL " +
                "  SELECT id, author, 0 AS views_count, COALESCE(like_count, 0) AS likes_count, " +
                "         COALESCE(comments_count, 0) AS comment_count, sentiment_score, timestamp AS created_at, 'instagram_posts' AS platform " +
                "  FROM instagram_posts WHERE keyword ILIKE ? " +
                ") " +
                "SELECT id, author, views_count, likes_count, comment_count, sentiment_score, created_at, platform " +
                "FROM combined_posts " +
                "WHERE created_at >= NOW() - INTERVAL '90 days' " +
                "AND (platform <> 'x_posts' OR views_count > 0) " +
                "AND sentiment_score BETWEEN 1 AND 100";

        String likeKeyword = "%" + keyword + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, likeKeyword, likeKeyword, likeKeyword, likeKeyword);

        Map<String, List<Map<String, Object>>> postsByAuthor = rows.stream()
                .filter(r -> r.get("author") != null)
                .collect(Collectors.groupingBy(r -> (String) r.get("author")));

        List<Map<String, Object>> ranked = postsByAuthor.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= minPosts)
                .map(entry -> scoreAuthorSafely(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> (double) r.get("viral_potential_score")).reversed())
                .limit(TOP_N)
                .collect(Collectors.toList());

        attachProfileLinks(ranked);
        return ranked;
    }

    /**
     * Adds profile_url and platform_handles to each ranked entry, looked up in one batched
     * query against marketing_target_profiles (keyed by author, matching the join contract
     * MarketingEnrichmentEngine writes against — see EntityMarketingService's note on this).
     */
    private void attachProfileLinks(List<Map<String, Object>> spreaders) {
        if (spreaders.isEmpty()) {
            return;
        }
        List<String> authors = spreaders.stream()
                .map(r -> (String) r.get("author"))
                .collect(Collectors.toList());
        String placeholders = authors.stream().map(a -> "?").collect(Collectors.joining(","));
        String sql = "SELECT global_user_id, platform_handles FROM marketing_target_profiles " +
                     "WHERE global_user_id IN (" + placeholders + ")";
        Map<String, Object> platformHandlesByAuthor = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, authors.toArray())) {
            platformHandlesByAuthor.put((String) row.get("global_user_id"), row.get("platform_handles"));
        }

        for (Map<String, Object> record : spreaders) {
            Object tree = JsonbUtil.asTree(platformHandlesByAuthor.get(record.get("author")), gson);
            record.put("platform_handles", tree);
            record.put("profile_url", extractProfileUrl(tree));
        }
    }

    /** Picks the primary platform's profile_url out of a parsed platform_handles tree. */
    private static String extractProfileUrl(Object platformHandlesTree) {
        if (!(platformHandlesTree instanceof Map<?, ?> root)) {
            return null;
        }
        if (!(root.get("by_platform") instanceof Map<?, ?> byPlatform) || byPlatform.isEmpty()) {
            return null;
        }
        Object primary = root.get("primary_platform");
        Object entryObj = primary instanceof String s ? byPlatform.get(s) : byPlatform.values().iterator().next();
        if (!(entryObj instanceof Map<?, ?> entry)) {
            return null;
        }
        Object url = entry.get("profile_url");
        return (url instanceof String s && !s.isBlank()) ? s : null;
    }

    /**
     * Scores a single author, isolating any failure so one bad author cannot abort the
     * whole ranking. Returns null (skipping the author) if scoring throws.
     */
    private Map<String, Object> scoreAuthorSafely(String author, List<Map<String, Object>> authorPosts) {
        try {
            return scoreAuthor(author, authorPosts);
        } catch (RuntimeException e) {
            log.warn("Skipping author '{}' in top-spreaders ranking: {}", author, e.toString());
            return null;
        }
    }

    private Map<String, Object> scoreAuthor(String author, List<Map<String, Object>> authorPosts) {
        long totalViews = authorPosts.stream()
                .mapToLong(r -> ((Number) r.getOrDefault("views_count", 0)).longValue())
                .sum();
        long totalLikes = authorPosts.stream()
                .mapToLong(r -> ((Number) r.getOrDefault("likes_count", 0)).longValue())
                .sum();
        long totalComments = authorPosts.stream()
                .mapToLong(r -> ((Number) r.getOrDefault("comment_count", 0)).longValue())
                .sum();

        double engagementCount = totalLikes + COMMENT_WEIGHT * totalComments;

        double avgSentiment = authorPosts.stream()
                .filter(r -> {
                    if (r.get("sentiment_score") == null) return false;
                    double s = ((Number) r.get("sentiment_score")).doubleValue();
                    return s >= 1.0 && s <= 100.0;
                })
                .mapToDouble(r -> ((Number) r.get("sentiment_score")).doubleValue())
                .average()
                .orElse(0.0);

        List<UniversalPost> universalPosts = authorPosts.stream()
                .map(r -> {
                    Timestamp raw = (Timestamp) r.getOrDefault("created_at", new Timestamp(0));
                    LocalDateTime ts = raw.toLocalDateTime();
                    String platform = String.valueOf(r.getOrDefault("platform", "x_posts"));
                    return new UniversalPost(
                            String.valueOf(r.getOrDefault("id", "")),
                            author, "", ts, platform, null
                    );
                })
                .collect(Collectors.toList());

        double alpha = hawkesIntensityCalculator.estimateParameters(universalPosts.stream()).alpha;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("author", author);
        record.put("viral_potential_score", engagementCount * (1.0 + alpha));
        record.put("alpha", alpha);
        record.put("engagement_count", engagementCount);
        record.put("total_likes", totalLikes);
        record.put("total_comments", totalComments);
        record.put("total_views", totalViews);
        record.put("engagement_rate", totalViews > 0 ? engagementCount / (double) totalViews : 0.0);
        record.put("average_sentiment_score", avgSentiment);
        return record;
    }
}
