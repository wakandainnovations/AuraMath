package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corpus-relative "Engagement Rating" scorer for authors already present in
 * {@code marketing_target_profiles}.
 *
 * Each row across x_posts/youtube_comments/reddit_posts/instagram_posts is scored via
 * {@link EngagementScoreCalculator}'s per-platform adapter, summed per author into
 * engagement_score_raw, then corpus-relative percentile ranked and rescaled into a fixed band,
 * mirroring {@link ConflictBalanceService}/{@link NarrativeNoveltyService}'s convention — an
 * unbounded raw sum isn't comparable across users, but a percentile rank is.
 *
 * <p>Aggregation key: the raw, unmodified {@code author} column value from each platform table —
 * confirmed against {@link com.lit.fire.flame.mappers.PostMapper}, which is the same
 * {@code authorId} {@link MarketingEnrichmentEngine} groups by and writes verbatim as
 * {@code marketing_target_profiles.global_user_id} ({@code MarketingInsightsRepository
 * .upsertUserPersonaProfile}). That column is <b>not</b> the cross-platform-resolved
 * {@code user-<uuid>} scheme {@code user_identity_link}/{@link GenreLookalikeService} use — it was
 * previously (incorrectly) treated as if it were, which made every recompute match ~1 row out of
 * ~90k. Do not route this through user_identity_link/normalize(); it would resolve into the wrong
 * ID space for this table.
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

    /** Rebuilds engagement_score_raw/engagement_rating for every author with a marketing_target_profiles row. */
    public Map<String, Object> recomputeAndPersist() {
        ensureSchema();

        Map<String, Double> rawByUser = new HashMap<>();
        accumulateXPosts(rawByUser);
        accumulateYoutubeComments(rawByUser);
        accumulateRedditPosts(rawByUser);
        accumulateInstagramPosts(rawByUser);

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

    private void accumulateXPosts(Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, comment_count, shares_count, likes_count, views_count FROM x_posts " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    double score = EngagementScoreCalculator.scoreXPost(
                            rs.getObject("comment_count", Integer.class),
                            rs.getObject("shares_count", Integer.class),
                            rs.getObject("likes_count", Integer.class),
                            rs.getObject("views_count", Integer.class));
                    rawByUser.merge(rs.getString("author"), score, Double::sum);
                });
    }

    private void accumulateYoutubeComments(Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, reply_count, likes_count FROM youtube_comments " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    double score = EngagementScoreCalculator.scoreYoutubeComment(
                            rs.getObject("reply_count", Integer.class),
                            rs.getObject("likes_count", Integer.class));
                    rawByUser.merge(rs.getString("author"), score, Double::sum);
                });
    }

    private void accumulateRedditPosts(Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, num_comments, score FROM reddit_posts " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    double score = EngagementScoreCalculator.scoreRedditPost(
                            rs.getObject("num_comments", Integer.class),
                            rs.getObject("score", Integer.class));
                    rawByUser.merge(rs.getString("author"), score, Double::sum);
                });
    }

    private void accumulateInstagramPosts(Map<String, Double> rawByUser) {
        jdbcTemplate.query(
                "SELECT author, comments_count, like_count FROM instagram_posts " +
                "WHERE author IS NOT NULL AND author <> ''",
                rs -> {
                    double score = EngagementScoreCalculator.scoreInstagramPost(
                            rs.getObject("comments_count", Integer.class),
                            rs.getObject("like_count", Integer.class));
                    rawByUser.merge(rs.getString("author"), score, Double::sum);
                });
    }

    private static double median(double[] sortedValues) {
        int m = sortedValues.length;
        if (m == 0) return 0.0;
        return (m % 2 == 0) ? (sortedValues[m / 2 - 1] + sortedValues[m / 2]) / 2.0 : sortedValues[m / 2];
    }

    /**
     * Mid-rank percentile: for a value tied with others, its rank is the midpoint of the rank
     * range its whole tied group occupies — (count strictly less + count less-or-equal) / 2n,
     * the standard tie-handling convention (matches scipy's rankdata 'average' method).
     *
     * <p>{@code Arrays.binarySearch} previously stood in for this and was wrong for large tied
     * blocks: it returns whichever index its bisection happens to land on among ties, not a
     * position representative of where the tied block sits. With ~55k of ~98k authors tied at
     * raw score 0 (occupying indices 0..55374 of a 98506-length sorted array), the search's very
     * first probe — the true array midpoint, ~49252 — already fell inside that block, so every
     * zero-score author got percentile ~0.5 (rating 50) instead of the ~0.28 their actual
     * standing warrants.
     */
    private static double percentileRank(double value, double[] sortedRef) {
        int n = sortedRef.length;
        if (n == 0) return 0.5;
        int countLess = lowerBound(sortedRef, value);
        int countLessOrEqual = upperBound(sortedRef, value);
        return Math.min(1.0, Math.max(0.0, (countLess + countLessOrEqual) / (2.0 * n)));
    }

    /** Index of the first element >= value, i.e. the count of elements strictly less than value. */
    private static int lowerBound(double[] sortedRef, double value) {
        int lo = 0, hi = sortedRef.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedRef[mid] < value) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Index of the first element > value, i.e. the count of elements less than or equal to value. */
    private static int upperBound(double[] sortedRef, double value) {
        int lo = 0, hi = sortedRef.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedRef[mid] <= value) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}
