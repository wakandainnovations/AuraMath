package com.lit.fire.flame;

import com.lit.fire.flame.GenreClassifier.GenreLabel;
import com.lit.fire.flame.models.UniversalPost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GenreInterestProfiler {

    private static final int DEFAULT_TOP_N = 3;

    private final JdbcTemplate jdbc;
    private final GenreClassifier classifier;

    @Autowired
    public GenreInterestProfiler(JdbcTemplate jdbc, GenreClassifier classifier) {
        this.jdbc = jdbc;
        this.classifier = classifier;
    }

    public Map<String, Map<String, Double>> profileAllUsers() {
        Map<String, String> identities = loadIdentityIndex();
        if (identities.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, Double>> userGenreScores = new HashMap<>();

        accumulate(userGenreScores, identities, fetchXPosts());
        accumulate(userGenreScores, identities, fetchYoutubeComments());
        accumulate(userGenreScores, identities, fetchRedditPosts());
        accumulate(userGenreScores, identities, fetchInstagramPosts());

        return userGenreScores;
    }

    public Map<String, List<Map.Entry<String, Double>>> topFavoriteGenres() {
        return topFavoriteGenres(DEFAULT_TOP_N);
    }

    public Map<String, List<Map.Entry<String, Double>>> topFavoriteGenres(int n) {
        Map<String, Map<String, Double>> all = profileAllUsers();
        Map<String, List<Map.Entry<String, Double>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : all.entrySet()) {
            result.put(entry.getKey(), topN(entry.getValue(), n));
        }
        return result;
    }

    public Map<String, Double> profileUser(String globalUserId) {
        Map<String, Map<String, Double>> all = profileAllUsers();
        return all.getOrDefault(globalUserId, Collections.emptyMap());
    }

    private void accumulate(Map<String, Map<String, Double>> userGenreScores,
                            Map<String, String> identities,
                            List<ScoredPost> posts) {
        for (ScoredPost sp : posts) {
            String normalized = normalizeAuthor(sp.post.getAuthorId());
            String globalUserId = identities.get(normalized);
            if (globalUserId == null) {
                continue;
            }

            List<GenreLabel> labels = classifier.classifyPost(sp.post);
            if (labels.isEmpty()) {
                continue;
            }

            Map<String, Double> bucket = userGenreScores
                    .computeIfAbsent(globalUserId, k -> new HashMap<>());

            for (GenreLabel label : labels) {
                double contribution = sp.interestScore * label.weight();
                bucket.merge(label.genre(), contribution, Double::sum);
            }
        }
    }

    private Map<String, String> loadIdentityIndex() {
        Map<String, String> index = new HashMap<>();
        jdbc.query("SELECT normalized_author, global_user_id FROM user_identity_link",
                (RowMapper<Void>) (rs, rowNum) -> {
                    index.put(rs.getString("normalized_author"), rs.getString("global_user_id"));
                    return null;
                });
        return index;
    }

    private List<ScoredPost> fetchXPosts() {
        String sql = "SELECT id, author, text, keyword, sentiment_score, views_count, created_at " +
                     "FROM x_posts WHERE author IS NOT NULL AND author <> '' AND sentiment_score <> 0";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            UniversalPost post = new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    rs.getString("text"),
                    null,
                    "x_posts",
                    metadata);
            double interest = baseInterest(rs.getDouble("sentiment_score"), rs.getLong("views_count"));
            return new ScoredPost(post, interest);
        });
    }

    private List<ScoredPost> fetchYoutubeComments() {
        String sql = "SELECT id, author, text, keyword, sentiment_score, likes_count " +
                     "FROM youtube_comments WHERE author IS NOT NULL AND author <> '' AND sentiment_score <> 0";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            UniversalPost post = new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    rs.getString("text"),
                    null,
                    "youtube_comments",
                    metadata);
            // No views column → use likes_count as the engagement proxy.
            double interest = baseInterest(rs.getDouble("sentiment_score"), rs.getLong("likes_count"));
            return new ScoredPost(post, interest);
        });
    }

    private List<ScoredPost> fetchRedditPosts() {
        String sql = "SELECT id, author, title, text, keyword, sentiment_score, score, num_comments " +
                     "FROM reddit_posts WHERE author IS NOT NULL AND author <> '' AND sentiment_score <> 0";
        return jdbc.query(sql, (rs, rowNum) -> {
            String title = nullSafe(rs.getString("title"));
            String body = nullSafe(rs.getString("text"));
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            metadata.put("title", title);
            UniversalPost post = new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    (title + " " + body).trim(),
                    null,
                    "reddit_posts",
                    metadata);
            // No views column → use num_comments as the engagement proxy, weighted by the post score.
            double interest = redditInterest(rs.getDouble("sentiment_score"),
                                             rs.getLong("num_comments"), rs.getInt("score"));
            return new ScoredPost(post, interest);
        });
    }

    private List<ScoredPost> fetchInstagramPosts() {
        String sql = "SELECT id, author, text, keyword, sentiment_score, media_type, like_count " +
                     "FROM instagram_posts WHERE author IS NOT NULL AND author <> '' AND sentiment_score <> 0";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            metadata.put("media_type", rs.getString("media_type"));
            UniversalPost post = new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    rs.getString("text"),
                    null,
                    "instagram_posts",
                    metadata);
            // No views column → use like_count as the engagement proxy.
            double interest = baseInterest(rs.getDouble("sentiment_score"), rs.getLong("like_count"));
            return new ScoredPost(post, interest);
        });
    }

    /**
     * Interest contribution of a post: centred sentiment × log-engagement.
     * sentiment_score is on a 0–100 scale (50 = neutral, 0 = invalid); it is centred to signed
     * [-1, 1] via (score-50)/50 so a negatively-perceived post subtracts interest instead of always
     * adding. Package-private for unit testing.
     */
    static double baseInterest(double sentimentScore, long views) {
        if (views < 0) {
            views = 0;
        }
        double signedSentiment = (sentimentScore - 50.0) / 50.0;
        return signedSentiment * Math.log(views + 1.0);
    }

    /**
     * Reddit interest: {@link #baseInterest} weighted by the post score. The raw Reddit score
     * (ups − downs) is clamped to ≥0 so it is a non-negative engagement multiplier only; the sign of
     * the interest comes solely from the centred sentiment, keeping Reddit consistent with the other
     * platforms and avoiding a negative-sentiment × negative-score sign flip. Package-private for testing.
     */
    static double redditInterest(double sentimentScore, long numComments, int redditScore) {
        return baseInterest(sentimentScore, numComments) * Math.max(0, redditScore);
    }

    private static List<Map.Entry<String, Double>> topN(Map<String, Double> scores, int n) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (sorted.size() > n) {
            return new ArrayList<>(sorted.subList(0, n));
        }
        return sorted;
    }

    private static String normalizeAuthor(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private record ScoredPost(UniversalPost post, double interestScore) {}
}
