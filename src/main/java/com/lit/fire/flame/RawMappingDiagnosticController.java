package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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

            // PostMapper.mapXPost() reads "like_count" instead of "likes_count"
            // and "impression_count" instead of "views_count" — both columns are absent in the DB
            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("likes (mapper reads 'like_count')", row.get("like_count"));
            javaMapped.put("views (mapper reads 'impression_count')", row.get("impression_count"));

            List<String> issues = new ArrayList<>();
            issues.add("DB column 'likes_count' = " + row.get("likes_count") + " but PostMapper reads 'like_count' → gets null/0");
            issues.add("DB column 'views_count' = " + row.get("views_count") + " but PostMapper reads 'impression_count' → gets null/0");

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", issues);
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
            dbRaw.put("reply_count", row.get("reply_count"));

            // PostMapper.mapYouTubeComment() reads "total_reply_count" instead of "reply_count"
            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("comments (mapper reads 'total_reply_count')", row.get("total_reply_count"));

            List<String> issues = new ArrayList<>();
            issues.add("DB column 'reply_count' = " + row.get("reply_count") + " but PostMapper reads 'total_reply_count' → gets null/0");

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", issues);
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
            dbRaw.put("score", row.get("score"));
            dbRaw.put("num_comments", row.get("num_comments"));

            // PostMapper.mapRedditPost() reads "ups" for likes (wrong column) but also reads
            // "score" separately as "platformSpecificScore", and "num_comments" correctly
            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("likes (mapper reads 'ups')", row.get("ups"));
            javaMapped.put("comments (mapper reads 'num_comments')", row.get("num_comments"));
            javaMapped.put("platformSpecificScore (mapper reads 'score')", row.get("score"));

            List<String> issues = new ArrayList<>();
            issues.add("DB column 'score' = " + row.get("score") + " but PostMapper reads 'ups' for 'likes' → gets null/0; 'score' is only preserved as 'platformSpecificScore'");
            issues.add("DB column 'num_comments' = " + row.get("num_comments") + " and PostMapper reads 'num_comments' → correct");

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", issues);
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
            dbRaw.put("media_type", row.get("media_type"));

            // PostMapper.mapInstagramPost() never reads media_type — it is absent from metadata
            Map<String, Object> javaMapped = new LinkedHashMap<>();
            javaMapped.put("media_type (not read by PostMapper)", null);

            List<String> issues = new ArrayList<>();
            issues.add("DB column 'media_type' = " + row.get("media_type") + " but PostMapper.mapInstagramPost() does not read or store it — field is lost after mapping");

            entry.put("db_raw", dbRaw);
            entry.put("java_mapped", javaMapped);
            entry.put("mapping_issues", issues);
            return entry;
        }).collect(Collectors.toList());
    }
}
