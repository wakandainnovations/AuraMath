package com.lit.fire.flame;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-entity, per-day audience-behavior features, computed for every {@code (entity_id, day_index)}
 * already present in {@link VmiComputationService}'s {@code entity_daily_vmi} (F1) - this service
 * reuses that day_index alignment rather than deriving its own, and does nothing for an entity that
 * hasn't been VMI-computed yet.
 *
 * <p>Entity resolution mirrors {@link VmiComputationService}/{@link GraphPopulationService}: joins go
 * through {@code mention_entities} -> {@code mentions} (optionally on to the platform table {@code
 * mentions.platform} points at), not the narrower {@code mentions.managed_entity_id} direct column -
 * confirmed live that column only covers ~9.9k of the ~33.8k mention_entities links, so it under-counts
 * relative to the join this codebase already standardizes on elsewhere.
 *
 * <p><b>netSentimentDelta</b> reuses {@code com.aura.service.service.DashboardService}'s (AuraService
 * repo) exact netSentimentScore formula verbatim: {@code negativeMentions > 0 ? positiveMentions /
 * (double) negativeMentions : 0.0} - a raw positive:negative mention-count ratio, not a normalized
 * score. The counts themselves are sourced via mention_entities/mentions (see above) rather than
 * DashboardService's own {@code mentions.managed_entity_id} query, since that's the linkage this
 * AuraMath codebase already treats as authoritative; the formula is what's being reused, not
 * DashboardService's narrower repository query.
 *
 * <p><b>spreaderTierShare</b> resolves authors by the raw, unmodified {@code author} column value
 * directly against {@code marketing_target_profiles.global_user_id} - confirmed live those two are
 * the same ID space (82k-row join hit). It deliberately does NOT route authors through {@code
 * user_identity_link}/{@link GenreLookalikeService#normalize}: {@link UserEngagementRatingService}'s
 * javadoc documents that marketing_target_profiles is keyed by the raw author string, and that routing
 * it through user_identity_link's resolved {@code user-<uuid>} scheme was a previously-fixed bug that
 * made lookups match ~1 row out of ~90k (confirmed still true live: 1 accidental overlap out of 78k
 * user_identity_link rows).
 *
 * <p><b>cascadeDepth</b> resolves authors the opposite way - via {@code user_identity_link}/{@link
 * GenreLookalikeService#normalize} - because that IS the ID space {@link GraphPopulationService} keys
 * its {@code graph_nodes.attributes->>'global_user_id'} with. graph_edges as populated has no per-post
 * or per-day granularity though: POSTED_ABOUT is one edge per (user, movie) pair aggregated across all
 * of that user's posts, and RETWEETED edges are pure user-to-user pairs with no entity/date attached at
 * all. So this approximates "RETWEETED edges per originating POSTED_ABOUT post for that day" as: among
 * authors who mentioned the entity that day, the average (graph-wide, all-time) count of RETWEETED
 * edges landing on that author's USER node - i.e. how often each of that day's posters gets retweeted,
 * rather than a same-day cascade count the current graph_edges schema cannot express.
 *
 * <p><b>spilloverEvent</b>'s 1.5x multiplier reuses {@code
 * com.aura.service.service.SentimentAlertService.SPIKE_MULTIPLIER}'s value (a different repo/language -
 * AuraService, Java/Spring like this one, but a separate deployable) purely for cross-codebase threshold
 * consistency; the signal it's applied to here (a platform's engagement volume vs. its own trailing
 * 7-day average) is unrelated to SentimentAlertService's own negative-sentiment-ratio spike detector, so
 * only the constant is shared, not the surrounding logic.
 */
@Service
public class BehaviorFeatureComputationService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorFeatureComputationService.class);

    private static final int TRAILING_WINDOW_DAYS = 7;

    // Same value as AuraService's com.aura.service.service.SentimentAlertService.SPIKE_MULTIPLIER
    // (a different repo/language), reused here only for the threshold constant - see class javadoc.
    static final double SPIKE_MULTIPLIER = 1.5;

    private static final String[] PLATFORMS = {"X", "YOUTUBE", "REDDIT", "INSTAGRAM"};

    @Autowired
    private JdbcTemplate jdbc;

    private final Gson gson = new Gson();

    private void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS entity_daily_behavior_features (" +
                "entity_id BIGINT NOT NULL, " +
                "day_index INT NOT NULL, " +
                "comment_velocity BIGINT NOT NULL, " +
                "content_intent_mix JSONB NOT NULL, " +
                "net_sentiment_delta DOUBLE PRECISION NOT NULL, " +
                "spreader_tier_share DOUBLE PRECISION NOT NULL, " +
                "cascade_depth DOUBLE PRECISION NOT NULL, " +
                "spillover_event VARCHAR(20), " +
                "computed_at TIMESTAMP NOT NULL, " +
                "PRIMARY KEY (entity_id, day_index))");
    }

    private record VmiDay(long entityId, int dayIndex, LocalDate calendarDate) {}

    /** Rebuilds entity_daily_behavior_features for every (entity_id, day_index) in entity_daily_vmi. */
    public Map<String, Object> recomputeAndPersist() {
        ensureSchema();

        List<VmiDay> vmiDays = loadVmiDays();
        if (vmiDays.isEmpty()) {
            log.info("Behavior feature computation skipped: entity_daily_vmi is empty (F1 hasn't run yet)");
            return Map.of("entitiesProcessed", 0, "dayRowsUpserted", 0);
        }

        // entity_id -> calendar day -> mention count / [positiveCount, negativeCount], via
        // mention_entities/mentions (see class javadoc on why not mentions.managed_entity_id).
        Map<Long, NavigableMap<LocalDate, Long>> commentVelocityByEntityDate = new HashMap<>();
        Map<Long, NavigableMap<LocalDate, long[]>> sentimentCountsByEntityDate = new HashMap<>();
        loadMentionAggregates(commentVelocityByEntityDate, sentimentCountsByEntityDate);

        // entity_id -> calendar day -> content_intent -> count.
        Map<Long, Map<LocalDate, Map<String, Integer>>> contentIntentMixByEntityDate = new HashMap<>();
        // entity_id -> calendar day -> platform -> summed EngagementScoreCalculator volume.
        Map<Long, Map<LocalDate, Map<String, Double>>> platformVolumeByEntityDate = new HashMap<>();
        // entity_id -> calendar day -> total summed volume (all platforms/authors, known or not).
        Map<Long, Map<LocalDate, Double>> totalVolumeByEntityDate = new HashMap<>();
        // entity_id -> calendar day -> raw author -> summed volume.
        Map<Long, Map<LocalDate, Map<String, Double>>> authorVolumeByEntityDate = new HashMap<>();
        // entity_id -> calendar day -> raw authors who mentioned the entity that day.
        Map<Long, Map<LocalDate, Set<String>>> postingAuthorsByEntityDate = new HashMap<>();

        accumulateXPosts(contentIntentMixByEntityDate, platformVolumeByEntityDate, totalVolumeByEntityDate,
                authorVolumeByEntityDate, postingAuthorsByEntityDate);
        accumulateYoutubeComments(contentIntentMixByEntityDate, platformVolumeByEntityDate, totalVolumeByEntityDate,
                authorVolumeByEntityDate, postingAuthorsByEntityDate);
        accumulateRedditPosts(contentIntentMixByEntityDate, platformVolumeByEntityDate, totalVolumeByEntityDate,
                authorVolumeByEntityDate, postingAuthorsByEntityDate);
        accumulateInstagramPosts(contentIntentMixByEntityDate, platformVolumeByEntityDate, totalVolumeByEntityDate,
                authorVolumeByEntityDate, postingAuthorsByEntityDate);

        Double engagementRatingP90 = jdbc.queryForObject(
                "SELECT percentile_cont(0.9) WITHIN GROUP (ORDER BY engagement_rating) " +
                "FROM marketing_target_profiles WHERE engagement_rating IS NOT NULL", Double.class);

        Map<String, Double> engagementRatingByAuthor = new HashMap<>();
        jdbc.query("SELECT global_user_id, engagement_rating FROM marketing_target_profiles " +
                "WHERE engagement_rating IS NOT NULL",
                rs -> { engagementRatingByAuthor.put(rs.getString("global_user_id"), rs.getDouble("engagement_rating")); });

        Map<String, String> identityIndex = new HashMap<>();
        jdbc.query("SELECT normalized_author, global_user_id FROM user_identity_link",
                rs -> { identityIndex.put(rs.getString("normalized_author"), rs.getString("global_user_id")); });

        Map<String, Long> userNodeIdByGlobalUserId = new HashMap<>();
        jdbc.query("SELECT id, attributes->>'global_user_id' AS gid FROM graph_nodes WHERE type = 'USER'",
                rs -> { userNodeIdByGlobalUserId.put(rs.getString("gid"), rs.getLong("id")); });

        Map<Long, Long> retweetedInCountByNodeId = new HashMap<>();
        jdbc.query("SELECT to_node_id, count(*) AS cnt FROM graph_edges WHERE relation_type = 'RETWEETED' " +
                "GROUP BY to_node_id",
                rs -> { retweetedInCountByNodeId.put(rs.getLong("to_node_id"), rs.getLong("cnt")); });

        Timestamp computedAt = Timestamp.from(Instant.now());
        Set<Long> entitiesProcessed = new HashSet<>();
        int rowsUpserted = 0;

        for (VmiDay day : vmiDays) {
            long entityId = day.entityId();
            LocalDate date = day.calendarDate();
            entitiesProcessed.add(entityId);

            long commentVelocity = commentVelocityByEntityDate
                    .getOrDefault(entityId, new TreeMap<>()).getOrDefault(date, 0L);

            Map<String, Integer> intentMix = contentIntentMixByEntityDate
                    .getOrDefault(entityId, Map.of()).getOrDefault(date, Map.of());
            String intentMixJson = gson.toJson(intentMix);

            NavigableMap<LocalDate, long[]> sentimentByDate =
                    sentimentCountsByEntityDate.getOrDefault(entityId, new TreeMap<>());
            double netSentimentDelta = netSentimentDelta(sentimentByDate, date);

            Map<String, Double> authorVolume = authorVolumeByEntityDate
                    .getOrDefault(entityId, Map.of()).getOrDefault(date, Map.of());
            double totalVolume = totalVolumeByEntityDate
                    .getOrDefault(entityId, Map.of()).getOrDefault(date, 0.0);
            double spreaderShare = spreaderTierShare(authorVolume, totalVolume, engagementRatingByAuthor, engagementRatingP90);

            Set<String> postingAuthors = postingAuthorsByEntityDate
                    .getOrDefault(entityId, Map.of()).getOrDefault(date, Set.of());
            double cascade = cascadeDepth(postingAuthors, identityIndex, userNodeIdByGlobalUserId, retweetedInCountByNodeId);

            Map<LocalDate, Map<String, Double>> platformVolumeByDate =
                    platformVolumeByEntityDate.getOrDefault(entityId, Map.of());
            Map<String, Double> todayByPlatform = new LinkedHashMap<>();
            Map<String, Double> trailingAvgByPlatform = new LinkedHashMap<>();
            for (String platform : PLATFORMS) {
                todayByPlatform.put(platform,
                        platformVolumeByDate.getOrDefault(date, Map.of()).getOrDefault(platform, 0.0));
                trailingAvgByPlatform.put(platform, trailingAveragePerPlatform(platformVolumeByDate, date, platform));
            }
            String spilloverEvent = spilloverEvent(todayByPlatform, trailingAvgByPlatform);

            jdbc.update(
                    "INSERT INTO entity_daily_behavior_features " +
                    "(entity_id, day_index, comment_velocity, content_intent_mix, net_sentiment_delta, " +
                    " spreader_tier_share, cascade_depth, spillover_event, computed_at) " +
                    "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (entity_id, day_index) DO UPDATE SET " +
                    "comment_velocity = EXCLUDED.comment_velocity, " +
                    "content_intent_mix = EXCLUDED.content_intent_mix, " +
                    "net_sentiment_delta = EXCLUDED.net_sentiment_delta, " +
                    "spreader_tier_share = EXCLUDED.spreader_tier_share, " +
                    "cascade_depth = EXCLUDED.cascade_depth, " +
                    "spillover_event = EXCLUDED.spillover_event, " +
                    "computed_at = EXCLUDED.computed_at",
                    entityId, day.dayIndex(), commentVelocity, intentMixJson, netSentimentDelta,
                    spreaderShare, cascade, spilloverEvent, computedAt);
            rowsUpserted++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entitiesProcessed", entitiesProcessed.size());
        summary.put("dayRowsUpserted", rowsUpserted);
        log.info("Behavior feature computation complete: {}", summary);
        return summary;
    }

    private List<VmiDay> loadVmiDays() {
        return jdbc.query("SELECT entity_id, day_index, calendar_date FROM entity_daily_vmi",
                (rs, rowNum) -> new VmiDay(rs.getLong("entity_id"), rs.getInt("day_index"),
                        rs.getDate("calendar_date").toLocalDate()));
    }

    // -------------------------------------------------------------------------
    // mention_entities/mentions aggregation (commentVelocity, sentiment counts) - deliberately not
    // the raw platform tables, since sentiment/date/entity linkage all already live on `mentions`
    // itself (see class javadoc on mention_entities vs mentions.managed_entity_id).
    // -------------------------------------------------------------------------

    private void loadMentionAggregates(Map<Long, NavigableMap<LocalDate, Long>> commentVelocityByEntityDate,
                                        Map<Long, NavigableMap<LocalDate, long[]>> sentimentCountsByEntityDate) {
        jdbc.query(
                "SELECT me.managed_entity_id AS entity_id, m.post_date AS post_date, m.sentiment AS sentiment " +
                "FROM mention_entities me " +
                "JOIN mentions m ON m.id = me.mention_id " +
                "JOIN managed_entities ent ON ent.id = me.managed_entity_id AND ent.type = 'MOVIE' " +
                "WHERE m.post_date IS NOT NULL",
                rs -> {
                    long entityId = rs.getLong("entity_id");
                    LocalDate date = rs.getTimestamp("post_date").toLocalDateTime().toLocalDate();
                    String sentiment = rs.getString("sentiment");

                    commentVelocityByEntityDate.computeIfAbsent(entityId, k -> new TreeMap<>())
                            .merge(date, 1L, Long::sum);

                    if ("POSITIVE".equals(sentiment) || "NEGATIVE".equals(sentiment)) {
                        long[] delta = "POSITIVE".equals(sentiment) ? new long[]{1, 0} : new long[]{0, 1};
                        sentimentCountsByEntityDate.computeIfAbsent(entityId, k -> new TreeMap<>())
                                .merge(date, delta, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Per-platform accumulation (content_intent mix, volumes, posting authors). Mirrors
    // VmiComputationService's accumulateXPosts/etc. shape and joins.
    // -------------------------------------------------------------------------

    private void accumulateXPosts(Map<Long, Map<LocalDate, Map<String, Integer>>> intentMix,
                                   Map<Long, Map<LocalDate, Map<String, Double>>> platformVolume,
                                   Map<Long, Map<LocalDate, Double>> totalVolume,
                                   Map<Long, Map<LocalDate, Map<String, Double>>> authorVolume,
                                   Map<Long, Map<LocalDate, Set<String>>> postingAuthors) {
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, xp.created_at AS event_time, xp.author AS author, " +
                "       xp.content_intent AS content_intent, " +
                "       xp.comment_count, xp.shares_count, xp.likes_count, xp.views_count " +
                "FROM mention_entities je " +
                "JOIN mentions m ON m.id = je.mention_id AND m.platform = 'X' " +
                "JOIN managed_entities ent ON ent.id = je.managed_entity_id AND ent.type = 'MOVIE' " +
                "JOIN x_posts xp ON xp.id = m.post_id " +
                "WHERE xp.created_at IS NOT NULL",
                rs -> {
                    double score = EngagementScoreCalculator.scoreXPost(
                            rs.getObject("comment_count", Integer.class),
                            rs.getObject("shares_count", Integer.class),
                            rs.getObject("likes_count", Integer.class),
                            rs.getObject("views_count", Integer.class));
                    accumulateRow(intentMix, platformVolume, totalVolume, authorVolume, postingAuthors,
                            rs.getLong("entity_id"), rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(),
                            "X", rs.getString("author"), rs.getString("content_intent"), score);
                });
    }

    private void accumulateYoutubeComments(Map<Long, Map<LocalDate, Map<String, Integer>>> intentMix,
                                            Map<Long, Map<LocalDate, Map<String, Double>>> platformVolume,
                                            Map<Long, Map<LocalDate, Double>> totalVolume,
                                            Map<Long, Map<LocalDate, Map<String, Double>>> authorVolume,
                                            Map<Long, Map<LocalDate, Set<String>>> postingAuthors) {
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, yc.published_at AS event_time, yc.author AS author, " +
                "       yc.content_intent AS content_intent, yc.reply_count, yc.likes_count " +
                "FROM mention_entities je " +
                "JOIN mentions m ON m.id = je.mention_id AND m.platform = 'YOUTUBE' " +
                "JOIN managed_entities ent ON ent.id = je.managed_entity_id AND ent.type = 'MOVIE' " +
                "JOIN youtube_comments yc ON yc.id = m.post_id " +
                "WHERE yc.published_at IS NOT NULL",
                rs -> {
                    double score = EngagementScoreCalculator.scoreYoutubeComment(
                            rs.getObject("reply_count", Integer.class),
                            rs.getObject("likes_count", Integer.class));
                    accumulateRow(intentMix, platformVolume, totalVolume, authorVolume, postingAuthors,
                            rs.getLong("entity_id"), rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(),
                            "YOUTUBE", rs.getString("author"), rs.getString("content_intent"), score);
                });
    }

    private void accumulateRedditPosts(Map<Long, Map<LocalDate, Map<String, Integer>>> intentMix,
                                        Map<Long, Map<LocalDate, Map<String, Double>>> platformVolume,
                                        Map<Long, Map<LocalDate, Double>> totalVolume,
                                        Map<Long, Map<LocalDate, Map<String, Double>>> authorVolume,
                                        Map<Long, Map<LocalDate, Set<String>>> postingAuthors) {
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, rp.created_at AS event_time, rp.author AS author, " +
                "       rp.content_intent AS content_intent, rp.num_comments, rp.score " +
                "FROM mention_entities je " +
                "JOIN mentions m ON m.id = je.mention_id AND m.platform = 'REDDIT' " +
                "JOIN managed_entities ent ON ent.id = je.managed_entity_id AND ent.type = 'MOVIE' " +
                "JOIN reddit_posts rp ON rp.id = m.post_id " +
                "WHERE rp.created_at IS NOT NULL",
                rs -> {
                    double score = EngagementScoreCalculator.scoreRedditPost(
                            rs.getObject("num_comments", Integer.class),
                            rs.getObject("score", Integer.class));
                    accumulateRow(intentMix, platformVolume, totalVolume, authorVolume, postingAuthors,
                            rs.getLong("entity_id"), rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(),
                            "REDDIT", rs.getString("author"), rs.getString("content_intent"), score);
                });
    }

    private void accumulateInstagramPosts(Map<Long, Map<LocalDate, Map<String, Integer>>> intentMix,
                                           Map<Long, Map<LocalDate, Map<String, Double>>> platformVolume,
                                           Map<Long, Map<LocalDate, Double>> totalVolume,
                                           Map<Long, Map<LocalDate, Map<String, Double>>> authorVolume,
                                           Map<Long, Map<LocalDate, Set<String>>> postingAuthors) {
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, ip.timestamp AS event_time, ip.author AS author, " +
                "       ip.content_intent AS content_intent, ip.comments_count, ip.like_count " +
                "FROM mention_entities je " +
                "JOIN mentions m ON m.id = je.mention_id AND m.platform = 'INSTAGRAM' " +
                "JOIN managed_entities ent ON ent.id = je.managed_entity_id AND ent.type = 'MOVIE' " +
                "JOIN instagram_posts ip ON ip.id = m.post_id " +
                "WHERE ip.timestamp IS NOT NULL",
                rs -> {
                    double score = EngagementScoreCalculator.scoreInstagramPost(
                            rs.getObject("comments_count", Integer.class),
                            rs.getObject("like_count", Integer.class));
                    accumulateRow(intentMix, platformVolume, totalVolume, authorVolume, postingAuthors,
                            rs.getLong("entity_id"), rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(),
                            "INSTAGRAM", rs.getString("author"), rs.getString("content_intent"), score);
                });
    }

    private static void accumulateRow(Map<Long, Map<LocalDate, Map<String, Integer>>> intentMix,
                                       Map<Long, Map<LocalDate, Map<String, Double>>> platformVolume,
                                       Map<Long, Map<LocalDate, Double>> totalVolume,
                                       Map<Long, Map<LocalDate, Map<String, Double>>> authorVolume,
                                       Map<Long, Map<LocalDate, Set<String>>> postingAuthors,
                                       long entityId, LocalDate date, String platform,
                                       String author, String contentIntent, double score) {
        if (contentIntent != null && !contentIntent.isBlank()) {
            intentMix.computeIfAbsent(entityId, k -> new HashMap<>())
                    .computeIfAbsent(date, k -> new LinkedHashMap<>())
                    .merge(contentIntent, 1, Integer::sum);
        }

        platformVolume.computeIfAbsent(entityId, k -> new HashMap<>())
                .computeIfAbsent(date, k -> new LinkedHashMap<>())
                .merge(platform, score, Double::sum);

        totalVolume.computeIfAbsent(entityId, k -> new HashMap<>())
                .merge(date, score, Double::sum);

        if (author != null && !author.isBlank()) {
            authorVolume.computeIfAbsent(entityId, k -> new HashMap<>())
                    .computeIfAbsent(date, k -> new HashMap<>())
                    .merge(author, score, Double::sum);
            postingAuthors.computeIfAbsent(entityId, k -> new HashMap<>())
                    .computeIfAbsent(date, k -> new HashSet<>())
                    .add(author);
        }
    }

    // -------------------------------------------------------------------------
    // Pure computation helpers - unit-tested directly (see BehaviorFeatureComputationServiceTest).
    // -------------------------------------------------------------------------

    /**
     * com.aura.service.service.DashboardService's (AuraService repo) exact netSentimentScore
     * formula, reused verbatim: a raw positive:negative mention-count ratio, 0.0 when there are no
     * negative mentions in the window (DashboardService's own zero-negative fallback).
     */
    static double netSentimentScore(long positiveMentions, long negativeMentions) {
        return negativeMentions > 0 ? (double) positiveMentions / negativeMentions : 0.0;
    }

    /**
     * netSentimentScore for the trailing-{@value #TRAILING_WINDOW_DAYS}-day window ending {@code day},
     * minus the same computed for the window ending {@code day - 1}. {@code dailySentimentCounts} maps
     * calendar day -> {@code [positiveCount, negativeCount]}; days absent from the map contribute 0/0.
     */
    static double netSentimentDelta(NavigableMap<LocalDate, long[]> dailySentimentCounts, LocalDate day) {
        long[] current = sumSentimentWindow(dailySentimentCounts, day);
        long[] prior = sumSentimentWindow(dailySentimentCounts, day.minusDays(1));
        return netSentimentScore(current[0], current[1]) - netSentimentScore(prior[0], prior[1]);
    }

    private static long[] sumSentimentWindow(NavigableMap<LocalDate, long[]> dailySentimentCounts, LocalDate windowEnd) {
        LocalDate windowStart = windowEnd.minusDays(TRAILING_WINDOW_DAYS - 1L);
        long positive = 0, negative = 0;
        for (long[] counts : dailySentimentCounts.subMap(windowStart, true, windowEnd, true).values()) {
            positive += counts[0];
            negative += counts[1];
        }
        return new long[]{positive, negative};
    }

    /**
     * Fraction of {@code totalVolume} contributed by authors at/above {@code p90Threshold}. Authors
     * absent from {@code engagementRatingByAuthor} (unresolved - no marketing_target_profiles row)
     * never contribute to the numerator, though their volume still counts in totalVolume.
     */
    static double spreaderTierShare(Map<String, Double> volumeByAuthor, double totalVolume,
                                     Map<String, Double> engagementRatingByAuthor, Double p90Threshold) {
        if (totalVolume <= 0.0 || p90Threshold == null || volumeByAuthor == null || volumeByAuthor.isEmpty()) {
            return 0.0;
        }
        double spreaderVolume = 0.0;
        for (Map.Entry<String, Double> entry : volumeByAuthor.entrySet()) {
            Double rating = engagementRatingByAuthor.get(entry.getKey());
            if (rating != null && rating >= p90Threshold) {
                spreaderVolume += entry.getValue();
            }
        }
        return spreaderVolume / totalVolume;
    }

    /** Average (graph-wide, all-time) RETWEETED-edge in-count across authors who posted that day and resolve to a USER node. */
    static double cascadeDepth(Set<String> postingAuthorsForDay, Map<String, String> identityIndexByNormalizedAuthor,
                                Map<String, Long> userNodeIdByGlobalUserId, Map<Long, Long> retweetedInCountByNodeId) {
        if (postingAuthorsForDay == null || postingAuthorsForDay.isEmpty()) {
            return 0.0;
        }
        long sum = 0;
        int resolved = 0;
        for (String author : postingAuthorsForDay) {
            String globalUserId = identityIndexByNormalizedAuthor.get(GenreLookalikeService.normalize(author));
            if (globalUserId == null) continue;
            Long nodeId = userNodeIdByGlobalUserId.get(globalUserId);
            if (nodeId == null) continue;
            sum += retweetedInCountByNodeId.getOrDefault(nodeId, 0L);
            resolved++;
        }
        return resolved == 0 ? 0.0 : (double) sum / resolved;
    }

    private static double trailingAveragePerPlatform(Map<LocalDate, Map<String, Double>> platformVolumeByDate,
                                                       LocalDate day, String platform) {
        double sum = 0.0;
        for (int i = 1; i <= TRAILING_WINDOW_DAYS; i++) {
            LocalDate d = day.minusDays(i);
            sum += platformVolumeByDate.getOrDefault(d, Map.of()).getOrDefault(platform, 0.0);
        }
        return sum / TRAILING_WINDOW_DAYS;
    }

    /**
     * The platform whose {@code todayVolumeByPlatform} strictly exceeds {@link #SPIKE_MULTIPLIER}x its
     * {@code trailingAvgByPlatform} by the widest margin (ratio), or null if none crossed the threshold
     * that day. A platform with a zero/negative trailing average is skipped (no baseline to compare
     * against), mirroring how {@link VmiComputationService#MIN_COHORT_SIZE_FOR_ZSCORE} avoids a spurious
     * verdict off an empty reference population.
     */
    static String spilloverEvent(Map<String, Double> todayVolumeByPlatform, Map<String, Double> trailingAvgByPlatform) {
        String best = null;
        double bestRatio = 0.0;
        for (Map.Entry<String, Double> entry : todayVolumeByPlatform.entrySet()) {
            String platform = entry.getKey();
            double avg = trailingAvgByPlatform.getOrDefault(platform, 0.0);
            if (avg <= 0.0) continue;
            double ratio = entry.getValue() / avg;
            if (ratio > SPIKE_MULTIPLIER && ratio > bestRatio) {
                bestRatio = ratio;
                best = platform;
            }
        }
        return best;
    }
}
