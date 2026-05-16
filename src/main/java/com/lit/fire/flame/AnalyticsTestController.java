package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/test")
public class AnalyticsTestController {

    private static final double HAWKES_BETA = 1.0;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /**
     * Queries all four platform tables for the given author, runs Hawkes and MOI calculations,
     * and returns raw DB values alongside computed scores for end-to-end verification.
     *
     * Throws on any infrastructure failure (DB down, optimizer error) → HTTP 500.
     */
    @GetMapping("/process-user/{author}")
    public Map<String, Object> processUser(@PathVariable String author) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("author", author);

        // Per-platform post lists kept separate so MOI can be broken down by source.
        // YouTube is excluded from MOI — the schema stores no per-comment engagement metrics.
        List<UniversalPost> xPostsForMoi     = new ArrayList<>();
        List<UniversalPost> redditPostsForMoi = new ArrayList<>();
        List<UniversalPost> igPostsForMoi     = new ArrayList<>();

        // ── x_posts ──────────────────────────────────────────────────────────────────
        // Only platform in the schema with a real views_count column.
        Map<String, Object> xRaw = new LinkedHashMap<>();
        List<Map<String, Object>> xRows = jdbcTemplate.queryForList(
                "SELECT id, likes_count, comment_count, views_count, created_at FROM x_posts WHERE author = ?",
                author);
        long xTotalLikes = 0, xTotalComments = 0, xTotalViews = 0;
        for (Map<String, Object> row : xRows) {
            int likes    = toInt(row.get("likes_count"));
            int comments = toInt(row.get("comment_count"));
            int views    = toInt(row.get("views_count"));
            xTotalLikes    += likes;
            xTotalComments += comments;
            xTotalViews    += views;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("views_count",   views);   // real impressions from DB
            meta.put("likes_count",   likes);
            meta.put("comment_count", comments);
            xPostsForMoi.add(new UniversalPost(String.valueOf(row.get("id")), author, null,
                    toLocalDateTime(row.get("created_at")), "x_posts", meta));
        }
        xRaw.put("count",         xRows.size());
        xRaw.put("totalLikes",    xTotalLikes);
        xRaw.put("totalComments", xTotalComments);
        xRaw.put("totalViews",    xTotalViews);

        // ── youtube_comments ─────────────────────────────────────────────────────────
        // Schema has no likes/views per comment → excluded from MOI, counted only.
        Map<String, Object> ytRaw = new LinkedHashMap<>();
        int ytCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM youtube_comments WHERE author = ?", Integer.class, author);
        ytRaw.put("count", ytCount);

        // ── reddit_posts ─────────────────────────────────────────────────────────────
        // No impressions column. `score` (net upvotes) is used as the likes proxy.
        // views_count stays 0 → ROA = 0 per post → reddit MOI = 0 (this is correct,
        // not a bug; the schema simply does not record impressions).
        Map<String, Object> redditRaw = new LinkedHashMap<>();
        List<Map<String, Object>> redditRows = jdbcTemplate.queryForList(
                "SELECT id, score, num_comments, created_at FROM reddit_posts WHERE author = ?",
                author);
        long redditTotalScore = 0, redditTotalComments = 0;
        for (Map<String, Object> row : redditRows) {
            int score       = toInt(row.get("score"));
            int numComments = toInt(row.get("num_comments"));
            redditTotalScore    += score;
            redditTotalComments += numComments;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("views_count",   0);          // no impressions column in schema
            meta.put("likes_count",   score);      // score = net upvotes
            meta.put("comment_count", numComments);
            redditPostsForMoi.add(new UniversalPost(String.valueOf(row.get("id")), author, null,
                    toLocalDateTime(row.get("created_at")), "reddit_posts", meta));
        }
        redditRaw.put("count",         redditRows.size());
        redditRaw.put("totalScore",    redditTotalScore);
        redditRaw.put("totalComments", redditTotalComments);

        // ── instagram_posts ──────────────────────────────────────────────────────────
        // No impressions column. views_count stays 0 → ROA = 0 → instagram MOI = 0.
        Map<String, Object> igRaw = new LinkedHashMap<>();
        List<Map<String, Object>> igRows = jdbcTemplate.queryForList(
                "SELECT id, like_count, comments_count, timestamp FROM instagram_posts WHERE author = ?",
                author);
        long igTotalLikes = 0, igTotalComments = 0;
        for (Map<String, Object> row : igRows) {
            int likes    = toInt(row.get("like_count"));
            int comments = toInt(row.get("comments_count"));
            igTotalLikes    += likes;
            igTotalComments += comments;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("views_count",    0);      // no impressions column in schema
            meta.put("likes_count",    likes);
            meta.put("comments_count", comments);
            igPostsForMoi.add(new UniversalPost(String.valueOf(row.get("id")), author, null,
                    toLocalDateTime(row.get("timestamp")), "instagram_posts", meta));
        }
        igRaw.put("count",         igRows.size());
        igRaw.put("totalLikes",    igTotalLikes);
        igRaw.put("totalComments", igTotalComments);

        // rawCounts: one entry per platform + grand total
        Map<String, Object> rawCounts = new LinkedHashMap<>();
        rawCounts.put("xPosts",                      xRaw);
        rawCounts.put("youtubeComments",              ytRaw);
        rawCounts.put("redditPosts",                  redditRaw);
        rawCounts.put("instagramPosts",               igRaw);
        rawCounts.put("totalPostsAcrossAllPlatforms",
                xRows.size() + ytCount + redditRows.size() + igRows.size());
        response.put("rawCounts", rawCounts);

        // ── HawkesIntensityCalculator ─────────────────────────────────────────────────
        // Uses its own DB query internally. Any failure propagates as HTTP 500.
        HawkesIntensityCalculator.HawkesParameters hawkesParams;
        try (Connection conn = dataSource.getConnection()) {
            hawkesParams = new HawkesIntensityCalculator(conn, HAWKES_BETA).estimateParameters(author);
        }
        Map<String, Object> hawkesResult = new LinkedHashMap<>();
        hawkesResult.put("mu",    hawkesParams.mu);
        hawkesResult.put("alpha", hawkesParams.alpha);

        // ── InfluenceMetricCalculator (MOI) ──────────────────────────────────────────
        // Broken down per platform so it's unambiguous which numbers drive the score.
        //
        // x_posts:          real views_count  → ROA is meaningful
        // reddit_posts:     views_count=0     → ROA=0, moiScore=0  (no impressions stored)
        // instagram_posts:  views_count=0     → ROA=0, moiScore=0  (no impressions stored)
        // youtube_comments: excluded entirely  → no per-comment engagement columns at all
        //
        // The overall score combines all three eligible platforms; x_posts dominates
        // because it is the only one contributing non-zero ROA values.
        List<UniversalPost> allForMoi = new ArrayList<>();
        allForMoi.addAll(xPostsForMoi);
        allForMoi.addAll(redditPostsForMoi);
        allForMoi.addAll(igPostsForMoi);

        Map<String, Object> moiBreakdown = new LinkedHashMap<>();
        moiBreakdown.put("overall",          moiFor(allForMoi,        author));
        moiBreakdown.put("xPosts",           moiFor(xPostsForMoi,     author));
        moiBreakdown.put("redditPosts",      moiFor(redditPostsForMoi, author));
        moiBreakdown.put("instagramPosts",   moiFor(igPostsForMoi,    author));
        moiBreakdown.put("youtubeComments",  "excluded — no per-comment engagement data in schema");

        Map<String, Object> calculatedScores = new LinkedHashMap<>();
        calculatedScores.put("hawkes",       hawkesResult);
        calculatedScores.put("influenceMoi", moiBreakdown);
        response.put("calculatedScores", calculatedScores);

        // Clarify what DB column maps to each MOI input for full traceability
        Map<String, String> dataSourceNotes = new LinkedHashMap<>();
        dataSourceNotes.put("xPosts",
                "views_count → views  |  likes_count → likes  |  comment_count → comments  (all real DB values)");
        dataSourceNotes.put("redditPosts",
                "views_count=0 (no impressions column)  |  score → likes  |  num_comments → comments");
        dataSourceNotes.put("instagramPosts",
                "views_count=0 (no impressions column)  |  like_count → likes  |  comments_count → comments");
        dataSourceNotes.put("youtubeComments",
                "excluded from MOI — schema has no likes/views per comment row");
        response.put("dataSourceNotes", dataSourceNotes);

        return response;
    }

    private double moiFor(List<UniversalPost> posts, String author) {
        if (posts.isEmpty()) return 0.0;
        return InfluenceMetricCalculator.calculateMoi(posts.stream()).getOrDefault(author, 0.0);
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        return ((Number) value).intValue();
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        return LocalDateTime.now();
    }
}
