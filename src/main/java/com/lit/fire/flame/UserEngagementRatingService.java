package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corpus-relative "Engagement Rating" scorer for resolved users.
 *
 * Each row across x_posts/youtube_comments/reddit_posts/instagram_posts is scored via
 * {@link EngagementScoreCalculator}'s per-platform adapter, summed per global_user_id into
 * engagement_score_raw, then corpus-relative percentile ranked and rescaled into a fixed band,
 * mirroring {@link ConflictBalanceService}/{@link NarrativeNoveltyService}'s convention — an
 * unbounded raw sum isn't comparable across users, but a percentile rank is.
 *
 * Author -> global_user_id resolution uses the same normalize() (lowercase, strip
 * non-alphanumerics) + user_identity_link lookup as {@link GenreLookalikeService}; rows whose
 * author has no matching identity are skipped, same as there.
 */
@Service
public class UserEngagementRatingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // No pre-existing engagement_rating column to match scale with (unlike ConflictBalanceService's
    // legacy-derived [0.25, 0.35] band), so this factor picks a fresh, human-readable 0-100
    // percentile-like scale rather than an unbounded raw sum.
    private static final double BAND_FLOOR = 0.0;
    private static final double BAND_CEIL = 100.0;

    private void ensureSchema() {
        jdbcTemplate.execute("ALTER TABLE marketing_target_profiles ADD COLUMN IF NOT EXISTS engagement_score_raw double precision");
        jdbcTemplate.execute("ALTER TABLE marketing_target_profiles ADD COLUMN IF NOT EXISTS engagement_rating double precision");
    }

    /** Rebuilds engagement_score_raw/engagement_rating for every resolved user with a marketing_target_profiles row. */
    public Map<String, Object> recomputeAndPersist() {
        ensureSchema();

        Map<String, String> identities = loadIdentityIndex();

        Map<String, Double> rawByUser = new HashMap<>();
        if (!identities.isEmpty()) {
            accumulateXPosts(identities, rawByUser);
            accumulateYoutubeComments(identities, rawByUser);
            accumulateRedditPosts(identities, rawByUser);
            accumulateInstagramPosts(identities, rawByUser);
        }

        double[] sortedRaw = rawByUser.values().stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sortedRaw);

        int updated = 0;
        for (Map.Entry<String, Double> entry : rawByUser.entrySet()) {
            String globalUserId = entry.getKey();
            double raw = entry.getValue();
            double percentile = percentileRank(raw, sortedRaw);
            double rating = BAND_FLOOR + percentile * (BAND_CEIL - BAND_FLOOR);
            // No matching marketing_target_profiles row (table is populated separately by
            // MarketingEnrichmentEngine) simply updates 0 rows rather than creating one.
            updated += jdbcTemplate.update(
                    "UPDATE marketing_target_profiles SET engagement_score_raw = ?, engagement_rating = ? " +
                    "WHERE global_user_id = ?",
                    raw, rating, globalUserId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("usersScored", rawByUser.size());
        summary.put("rowsUpdated", updated);
        summary.put("medianRaw", median(sortedRaw));
        return summary;
    }

    private Map<String, String> loadIdentityIndex() {
        Map<String, String> index = new HashMap<>();
        jdbcTemplate.query("SELECT normalized_author, global_user_id FROM user_identity_link", rs -> {
            index.put(rs.getString("normalized_author"), rs.getString("global_user_id"));
        });
        return index;
    }

    private void accumulateXPosts(Map<String, String> identities, Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, comment_count, shares_count, likes_count, views_count FROM x_posts " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    String globalUserId = identities.get(normalize(rs.getString("author")));
                    if (globalUserId == null) {
                        return;
                    }
                    double score = EngagementScoreCalculator.scoreXPost(
                            rs.getObject("comment_count", Integer.class),
                            rs.getObject("shares_count", Integer.class),
                            rs.getObject("likes_count", Integer.class),
                            rs.getObject("views_count", Integer.class));
                    rawByUser.merge(globalUserId, score, Double::sum);
                });
    }

    private void accumulateYoutubeComments(Map<String, String> identities, Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, reply_count, likes_count FROM youtube_comments " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    String globalUserId = identities.get(normalize(rs.getString("author")));
                    if (globalUserId == null) {
                        return;
                    }
                    double score = EngagementScoreCalculator.scoreYoutubeComment(
                            rs.getObject("reply_count", Integer.class),
                            rs.getObject("likes_count", Integer.class));
                    rawByUser.merge(globalUserId, score, Double::sum);
                });
    }

    private void accumulateRedditPosts(Map<String, String> identities, Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, num_comments, score FROM reddit_posts " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    String globalUserId = identities.get(normalize(rs.getString("author")));
                    if (globalUserId == null) {
                        return;
                    }
                    double score = EngagementScoreCalculator.scoreRedditPost(
                            rs.getObject("num_comments", Integer.class),
                            rs.getObject("score", Integer.class));
                    rawByUser.merge(globalUserId, score, Double::sum);
                });
    }

    private void accumulateInstagramPosts(Map<String, String> identities, Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, comments_count, like_count FROM instagram_posts " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    String globalUserId = identities.get(normalize(rs.getString("author")));
                    if (globalUserId == null) {
                        return;
                    }
                    double score = EngagementScoreCalculator.scoreInstagramPost(
                            rs.getObject("comments_count", Integer.class),
                            rs.getObject("like_count", Integer.class));
                    rawByUser.merge(globalUserId, score, Double::sum);
                });
    }

    private static double median(double[] sortedValues) {
        int m = sortedValues.length;
        if (m == 0) return 0.0;
        return (m % 2 == 0) ? (sortedValues[m / 2 - 1] + sortedValues[m / 2]) / 2.0 : sortedValues[m / 2];
    }

    private static double percentileRank(double value, double[] sortedRef) {
        if (sortedRef.length == 0) return 0.5;
        int idx = Arrays.binarySearch(sortedRef, value);
        if (idx < 0) idx = -idx - 1;
        return Math.min(1.0, Math.max(0.0, (double) idx / sortedRef.length));
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
    }
}
