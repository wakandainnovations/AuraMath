package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
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

    private static final int TOP_N = 50;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HawkesIntensityCalculator hawkesIntensityCalculator;

    /**
     * Returns the top 50 authors ranked by Viral Potential Score (VPS = total_views × α),
     * where α is the Hawkes-process infectivity fitted to that author's posting timestamps
     * over the last 90 days matching the given keyword.
     *
     * High α identifies authors whose activity self-excites into bursty engagement cascades.
     * average_sentiment_score is included so the team avoids seeding with high-influence detractors.
     */
    @GetMapping("/top-50-spreaders/{keyword}")
    public List<Map<String, Object>> getTopSpreaders(@PathVariable String keyword) {
        String sql = "SELECT id, author, views_count, sentiment_score, created_at " +
                     "FROM x_posts " +
                     "WHERE keyword ILIKE ? " +
                     "AND created_at >= NOW() - INTERVAL '90 days' " +
                     "AND views_count > 0";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, "%" + keyword + "%");

        Map<String, List<Map<String, Object>>> postsByAuthor = rows.stream()
                .filter(r -> r.get("author") != null)
                .collect(Collectors.groupingBy(r -> (String) r.get("author")));

        return postsByAuthor.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .map(entry -> scoreAuthor(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(r -> -((double) r.get("viral_potential_score"))))
                .limit(TOP_N)
                .collect(Collectors.toList());
    }

    private Map<String, Object> scoreAuthor(String author, List<Map<String, Object>> authorPosts) {
        long totalViews = authorPosts.stream()
                .mapToLong(r -> ((Number) r.getOrDefault("views_count", 0)).longValue())
                .sum();

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
        record.put("viral_potential_score", totalViews * alpha);
        record.put("alpha", alpha);
        record.put("total_views", totalViews);
        record.put("average_sentiment_score", avgSentiment);
        return record;
    }
}
