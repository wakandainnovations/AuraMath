package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/marketing")
public class UserProfileAPI {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/user-profile/{globalUserId}")
    public ResponseEntity<Map<String, Object>> getUserProfile(@PathVariable String globalUserId) {

        String normalizedId = globalUserId.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");

        List<String> authors = jdbcTemplate.queryForList(
                "SELECT normalized_author FROM user_identity_link WHERE REGEXP_REPLACE(LOWER(global_user_id), '[^a-zA-Z0-9]', '', 'g') = ?",
                String.class, normalizedId);

        if (authors.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String normalizedAuthor = authors.get(0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("globalUserId", globalUserId);
        response.put("normalizedAuthor", normalizedAuthor);
        response.put("xStats", buildXStats(normalizedAuthor));
        response.put("youtubeStats", buildYoutubeStats(normalizedAuthor));
        response.put("redditStats", buildRedditStats(normalizedAuthor));
        response.put("instagramStats", buildInstagramStats(normalizedAuthor));

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildXStats(String normalizedAuthor) {
        String sql = "SELECT " +
                "COALESCE(SUM(likes_count), 0) AS total_likes, " +
                "COALESCE(SUM(views_count), 0) AS total_views, " +
                "COUNT(*) AS post_count " +
                "FROM x_posts " +
                "WHERE REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g') = ?";

        Map<String, Object> row = jdbcTemplate.queryForList(sql, normalizedAuthor).get(0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalLikes", row.get("total_likes"));
        stats.put("totalViews", row.get("total_views"));
        stats.put("postCount", row.get("post_count"));
        return stats;
    }

    private Map<String, Object> buildYoutubeStats(String normalizedAuthor) {
        String sql = "SELECT " +
                "COALESCE(SUM(reply_count), 0) AS total_replies, " +
                "COUNT(*) AS comment_count " +
                "FROM youtube_comments " +
                "WHERE REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g') = ?";

        Map<String, Object> row = jdbcTemplate.queryForList(sql, normalizedAuthor).get(0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalReplies", row.get("total_replies"));
        stats.put("commentCount", row.get("comment_count"));
        return stats;
    }

    private Map<String, Object> buildRedditStats(String normalizedAuthor) {
        String sql = "SELECT " +
                "COALESCE(SUM(score), 0) AS total_score, " +
                "COALESCE(AVG(score), 0.0) AS avg_score, " +
                "COUNT(*) AS post_count " +
                "FROM reddit_posts " +
                "WHERE REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g') = ?";

        Map<String, Object> row = jdbcTemplate.queryForList(sql, normalizedAuthor).get(0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalScore", row.get("total_score"));
        stats.put("averageScore", row.get("avg_score"));
        stats.put("postCount", row.get("post_count"));
        return stats;
    }

    private Map<String, Object> buildInstagramStats(String normalizedAuthor) {
        String sql = "SELECT media_type, COUNT(*) AS count " +
                "FROM instagram_posts " +
                "WHERE REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g') = ? " +
                "GROUP BY media_type " +
                "ORDER BY count DESC";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, normalizedAuthor);

        Map<String, Object> mediaTypeBreakdown = new LinkedHashMap<>();
        String preferredMediaType = null;

        for (Map<String, Object> row : rows) {
            String mediaType = (String) row.get("media_type");
            if (mediaType != null) {
                mediaTypeBreakdown.put(mediaType, row.get("count"));
                if (preferredMediaType == null) {
                    preferredMediaType = mediaType;
                }
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("mediaTypeBreakdown", mediaTypeBreakdown);
        stats.put("preferredMediaType", preferredMediaType);
        return stats;
    }
}
