package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HawkesIntensityCalculator hawkesIntensityCalculator;

    /**
     * Returns the top 50 authors on X for posts matching {keyword} in the last 90 days,
     * ranked by Viral Potential Score:
     *
     *   VPS = (likes + 3 × comments) × (1 + α)
     *
     * The engagement count rewards authors whose audience actively reacts (not just passive
     * viewers), and the (1 + α) factor lets Hawkes infectivity boost bursty cascade-starters
     * without zeroing out high-engagement organic spreaders whose post cadence fits α ≈ 0.
     *
     * Comments are weighted 3× likes because they require materially more user effort and
     * correlate more strongly with downstream sharing. Requires ≥ 2 matching posts per author.
     *
     * average_sentiment_score is included so the team avoids seeding with high-influence detractors.
     */
    @GetMapping("/top-50-spreaders/{keyword}")
    public List<Map<String, Object>> getTopSpreaders(@PathVariable String keyword) {
        String sql = "SELECT id, author, views_count, likes_count, comment_count, sentiment_score, created_at " +
                     "FROM x_posts " +
                     "WHERE keyword ILIKE ? " +
                     "AND created_at >= NOW() - INTERVAL '90 days' " +
                     "AND views_count > 0 " +
                     "AND sentiment_score <> 0";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, "%" + keyword + "%");

        Map<String, List<Map<String, Object>>> postsByAuthor = rows.stream()
                .filter(r -> r.get("author") != null)
                .collect(Collectors.groupingBy(r -> (String) r.get("author")));

        return postsByAuthor.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .map(entry -> scoreAuthorSafely(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> (double) r.get("viral_potential_score")).reversed())
                .limit(TOP_N)
                .collect(Collectors.toList());
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
                .filter(r -> r.get("sentiment_score") != null && ((Number) r.get("sentiment_score")).doubleValue() != 0.0)
                .mapToDouble(r -> ((Number) r.get("sentiment_score")).doubleValue())
                .average()
                .orElse(0.0);

        List<UniversalPost> universalPosts = authorPosts.stream()
                .map(r -> {
                    Timestamp raw = (Timestamp) r.getOrDefault("created_at", new Timestamp(0));
                    LocalDateTime ts = raw.toLocalDateTime();
                    return new UniversalPost(
                            String.valueOf(r.getOrDefault("id", "")),
                            author, "", ts, "x_posts", null
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
