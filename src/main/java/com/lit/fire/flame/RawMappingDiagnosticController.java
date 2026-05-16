package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/test")
public class RawMappingDiagnosticController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/raw-mapping/{author}")
    public Map<String, Object> getRawMapping(@PathVariable String author) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("author", author);
        result.put("x_posts", buildXPostsComparison(author));
        result.put("youtube_comments", buildYoutubeCommentsComparison(author));
        result.put("reddit_posts", buildRedditPostsComparison(author));
        result.put("instagram_posts", buildInstagramPostsComparison(author));
        return result;
    }

    private List<Map<String, Object>> buildXPostsComparison(String author) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM x_posts WHERE author = ?", author);

        return rows.stream().map(row -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("post_id", row.get("id"));

            Map<String, Object> dbRaw = new LinkedHashMap<>();
            dbRaw.put("likes_count", row.get("likes_count"));
            dbRaw.put("views_count", row.get("views_count"));

            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("likes (mapper reads 'likes_count')", row.get("likes_count"));
            javaMapped.put("views (mapper reads 'views_count')", row.get("views_count"));

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", List.of());
            return entry;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildYoutubeCommentsComparison(String author) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM youtube_comments WHERE author = ?", author);

        return rows.stream().map(row -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("post_id", row.get("id"));

            Map<String, Object> dbRaw = new LinkedHashMap<>();
            dbRaw.put("likes_count", row.get("likes_count"));
            dbRaw.put("reply_count", row.get("reply_count"));

            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("likes (mapper reads 'likes_count')",    row.get("likes_count"));
            javaMapped.put("comments (mapper reads 'reply_count')", row.get("reply_count"));

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", List.of());
            return entry;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildRedditPostsComparison(String author) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM reddit_posts WHERE author = ?", author);

        return rows.stream().map(row -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("post_id", row.get("id"));

            Map<String, Object> dbRaw = new LinkedHashMap<>();
            dbRaw.put("score",        row.get("score"));
            dbRaw.put("num_comments", row.get("num_comments"));

            // 'score' is net upvotes, used as the likes-proxy on Reddit. It's
            // also preserved verbatim as platformSpecificScore.
            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("likes (mapper reads 'score')",              row.get("score"));
            javaMapped.put("comments (mapper reads 'num_comments')",    row.get("num_comments"));
            javaMapped.put("platformSpecificScore (mapper reads 'score')", row.get("score"));

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", List.of());
            return entry;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildInstagramPostsComparison(String author) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM instagram_posts WHERE author = ?", author);

        return rows.stream().map(row -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("post_id", row.get("id"));

            Map<String, Object> dbRaw = new LinkedHashMap<>();
            dbRaw.put("like_count",     row.get("like_count"));
            dbRaw.put("comments_count", row.get("comments_count"));
            dbRaw.put("media_type",     row.get("media_type"));

            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("likes (mapper reads 'like_count')",          row.get("like_count"));
            javaMapped.put("comments (mapper reads 'comments_count')",   row.get("comments_count"));
            javaMapped.put("media_type (mapper reads 'media_type')",     row.get("media_type"));

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", List.of());
            return entry;
        }).collect(Collectors.toList());
    }
}
