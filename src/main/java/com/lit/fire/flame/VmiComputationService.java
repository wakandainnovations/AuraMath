package com.lit.fire.flame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Viewership Momentum Index (VMI): a social-engagement-based proxy for "maximum viewership",
 * since this system has no real box-office/ticketing data. Every mention of a {@code
 * managed_entities} row of type {@code MOVIE} is scored with {@link EngagementScoreCalculator}'s
 * existing per-platform adapters (verbatim - the weighting itself is not re-derived here) and
 * rolled up into a per-day engagement volume, then compared against the entity's cohort.
 *
 * <p>Pipeline per entity:
 * <ol>
 *   <li><b>dailyEngagementVolume</b>: every mention of the entity (resolved via
 *       {@code mention_entities} -> {@code mentions} -> the platform table {@code mentions.platform}
 *       points at) is scored via the matching {@link EngagementScoreCalculator} adapter and summed
 *       per calendar day of the underlying post's own date column.</li>
 *   <li><b>day_index</b>: days elapsed since the entity's own first tracked mention, not a calendar
 *       date - so two movies tracked over different real-world windows are still comparable
 *       turn-by-turn (day_index 0 = both movies' opening day of tracked chatter).</li>
 *   <li><b>cohort_zscore</b>: entities are grouped by (industry, language) - the same grouping
 *       {@code managed_entities} already carries (confirmed live: e.g. Sandalwood/Kannada,
 *       Kollywood/Tamil, Bollywood/Hindi) - and z-scored against cohort-mates at the same
 *       day_index. See {@link #MIN_COHORT_SIZE_FOR_ZSCORE}.</li>
 * </ol>
 *
 * <p>Mention resolution deliberately goes through {@code mention_entities}/{@code mentions} (the
 * same join {@link LanguageMarketingAPI}/{@link GenreMarketingAPI} use for entity-scoped audience
 * queries) rather than {@code entity_keywords} keyword-matching ({@link EntityIntelService}/
 * {@link EntityMarketingService}'s approach): {@code mentions.managed_entity_id}/
 * {@code mention_entities} is the curated entity link, and {@code mentions.post_id} plus
 * {@code mentions.platform} identify exactly which platform-table row to pull engagement counts
 * from - {@code mentions} itself carries no engagement counts (sentiment/author/date only).
 */
@Service
public class VmiComputationService {

    private static final Logger log = LoggerFactory.getLogger(VmiComputationService.class);

    // Below this many cohort-mates with a data point at a given day_index, a z-score would be
    // computed from a near-empty sample and isn't meaningful - skip it (null) rather than report
    // a spurious outlier/non-outlier verdict off e.g. 2 or 3 data points.
    private static final int MIN_COHORT_SIZE_FOR_ZSCORE = 4;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BehaviorFeatureComputationService behaviorFeatureComputationService;

    @Autowired
    private UserCausalLiftScoreService userCausalLiftScoreService;

    private void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS entity_daily_vmi (" +
                "entity_id BIGINT NOT NULL, " +
                "day_index INT NOT NULL, " +
                "calendar_date DATE NOT NULL, " +
                "daily_engagement_volume DOUBLE PRECISION NOT NULL, " +
                "cohort_zscore DOUBLE PRECISION, " +
                "cumulative_engagement_volume DOUBLE PRECISION NOT NULL, " +
                "computed_at TIMESTAMP NOT NULL, " +
                "PRIMARY KEY (entity_id, day_index))");
        // Idempotent no-op on a freshly-created table; guards a deployment where the table
        // predates this constraint list (mirrors ConflictBalanceService/RetweetResolver's
        // ALTER TABLE ... IF NOT EXISTS idiom for schema evolution).
        jdbc.execute("ALTER TABLE entity_daily_vmi ADD COLUMN IF NOT EXISTS cohort_zscore DOUBLE PRECISION");
    }

    private record CohortKey(String industry, String language) {}

    /** One entity's engagement series, keyed by day_index, built off its own first-mention day. */
    private static final class EntitySeries {
        final CohortKey cohort;
        final NavigableMap<Integer, LocalDate> dateByDayIndex = new TreeMap<>();
        final NavigableMap<Integer, Double> volumeByDayIndex = new TreeMap<>();

        EntitySeries(CohortKey cohort) {
            this.cohort = cohort;
        }
    }

    /**
     * Rebuilds {@code entity_daily_vmi} for every {@code MOVIE} managed_entities row.
     */
    public Map<String, Object> recomputeAndPersist() {
        ensureSchema();

        Map<Long, CohortKey> cohortByEntity = loadMovieCohorts();

        // entity_id -> calendar day -> summed per-post EngagementScoreCalculator score for that day.
        Map<Long, NavigableMap<LocalDate, Double>> volumeByEntityAndDate = new HashMap<>();
        accumulateXPosts(volumeByEntityAndDate);
        accumulateYoutubeComments(volumeByEntityAndDate);
        accumulateRedditPosts(volumeByEntityAndDate);
        accumulateInstagramPosts(volumeByEntityAndDate);

        // Re-index each entity's per-date volumes onto day_index (days since ITS OWN first
        // tracked mention), so cohort-mates tracked over different real-world date ranges still
        // line up turn-by-turn.
        Map<Long, EntitySeries> seriesByEntity = new LinkedHashMap<>();
        for (Map.Entry<Long, NavigableMap<LocalDate, Double>> e : volumeByEntityAndDate.entrySet()) {
            long entityId = e.getKey();
            NavigableMap<LocalDate, Double> byDate = e.getValue();
            if (byDate.isEmpty()) continue;
            LocalDate firstMentionDay = byDate.firstKey();
            EntitySeries series = new EntitySeries(cohortByEntity.get(entityId));
            for (Map.Entry<LocalDate, Double> d : byDate.entrySet()) {
                int dayIndex = (int) ChronoUnit.DAYS.between(firstMentionDay, d.getKey());
                series.dateByDayIndex.put(dayIndex, d.getKey());
                series.volumeByDayIndex.put(dayIndex, d.getValue());
            }
            seriesByEntity.put(entityId, series);
        }

        // (cohort, day_index) -> every cohort-mate's volume at that day_index, used to compute
        // the mean/stddev each entity in the slice is z-scored against.
        Map<CohortKey, Map<Integer, List<Double>>> cohortDayPopulation = new HashMap<>();
        for (EntitySeries series : seriesByEntity.values()) {
            if (series.cohort == null) continue;
            Map<Integer, List<Double>> byDayIndex =
                    cohortDayPopulation.computeIfAbsent(series.cohort, k -> new HashMap<>());
            for (Map.Entry<Integer, Double> d : series.volumeByDayIndex.entrySet()) {
                byDayIndex.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(d.getValue());
            }
        }

        Map<CohortKey, Map<Integer, double[]>> statsByCohortDay = new HashMap<>();
        int eligibleSlices = 0;
        for (Map.Entry<CohortKey, Map<Integer, List<Double>>> c : cohortDayPopulation.entrySet()) {
            Map<Integer, double[]> byDayIndex = new HashMap<>();
            for (Map.Entry<Integer, List<Double>> d : c.getValue().entrySet()) {
                List<Double> values = d.getValue();
                if (values.size() < MIN_COHORT_SIZE_FOR_ZSCORE) continue;
                byDayIndex.put(d.getKey(), meanAndStdDev(values));
                eligibleSlices++;
            }
            statsByCohortDay.put(c.getKey(), byDayIndex);
        }

        Timestamp computedAt = Timestamp.from(Instant.now());
        List<Object[]> batchArgs = new ArrayList<>();
        for (Map.Entry<Long, EntitySeries> e : seriesByEntity.entrySet()) {
            long entityId = e.getKey();
            EntitySeries series = e.getValue();
            Map<Integer, double[]> statsByDayIndex =
                    series.cohort == null ? Map.of() : statsByCohortDay.getOrDefault(series.cohort, Map.of());

            double cumulative = 0.0;
            for (Map.Entry<Integer, Double> d : series.volumeByDayIndex.entrySet()) {
                int dayIndex = d.getKey();
                double volume = d.getValue();
                cumulative += volume;

                Double zscore = null;
                double[] stats = statsByDayIndex.get(dayIndex);
                if (stats != null) {
                    double mean = stats[0], stdDev = stats[1];
                    zscore = stdDev == 0.0 ? 0.0 : (volume - mean) / stdDev;
                }

                batchArgs.add(new Object[]{
                        entityId, dayIndex, java.sql.Date.valueOf(series.dateByDayIndex.get(dayIndex)),
                        volume, zscore, cumulative, computedAt
                });
            }
        }

        int[] rowsUpserted = batchArgs.isEmpty() ? new int[0] : jdbc.batchUpdate(
                "INSERT INTO entity_daily_vmi " +
                "(entity_id, day_index, calendar_date, daily_engagement_volume, cohort_zscore, cumulative_engagement_volume, computed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (entity_id, day_index) DO UPDATE SET " +
                "calendar_date = EXCLUDED.calendar_date, " +
                "daily_engagement_volume = EXCLUDED.daily_engagement_volume, " +
                "cohort_zscore = EXCLUDED.cohort_zscore, " +
                "cumulative_engagement_volume = EXCLUDED.cumulative_engagement_volume, " +
                "computed_at = EXCLUDED.computed_at",
                batchArgs,
                new int[]{Types.BIGINT, Types.INTEGER, Types.DATE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.TIMESTAMP});

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entitiesProcessed", seriesByEntity.size());
        summary.put("dayRowsUpserted", rowsUpserted.length);
        summary.put("cohortDaySlicesEligibleForZscore", eligibleSlices);
        return summary;
    }

    /** id -> (industry, language) for every MOVIE managed_entities row. */
    private Map<Long, CohortKey> loadMovieCohorts() {
        Map<Long, CohortKey> result = new HashMap<>();
        jdbc.query("SELECT id, industry, language FROM managed_entities WHERE type = 'MOVIE'", rs -> {
            result.put(rs.getLong("id"), new CohortKey(rs.getString("industry"), rs.getString("language")));
        });
        return result;
    }

    private static double[] meanAndStdDev(List<Double> values) {
        int n = values.size();
        double sum = 0.0;
        for (double v : values) sum += v;
        double mean = sum / n;
        double variance = 0.0;
        for (double v : values) variance += (v - mean) * (v - mean);
        variance /= n; // population variance - the cohort-at-this-day_index IS the whole reference population
        return new double[]{mean, Math.sqrt(variance)};
    }

    // -------------------------------------------------------------------------
    // Per-platform accumulation. Mirrors UserEngagementRatingService's shape, but resolves the
    // entity via mention_entities/mentions (the curated entity link) instead of author, and keys
    // on (entity_id, calendar day of the post's own date column) instead of author.
    // -------------------------------------------------------------------------

    private void accumulateXPosts(Map<Long, NavigableMap<LocalDate, Double>> volumeByEntityAndDate) {
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, xp.created_at AS event_time, " +
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
                    mergeEvent(volumeByEntityAndDate, rs.getLong("entity_id"),
                            rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(), score);
                });
    }

    private void accumulateYoutubeComments(Map<Long, NavigableMap<LocalDate, Double>> volumeByEntityAndDate) {
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, yc.published_at AS event_time, " +
                "       yc.reply_count, yc.likes_count " +
                "FROM mention_entities je " +
                "JOIN mentions m ON m.id = je.mention_id AND m.platform = 'YOUTUBE' " +
                "JOIN managed_entities ent ON ent.id = je.managed_entity_id AND ent.type = 'MOVIE' " +
                "JOIN youtube_comments yc ON yc.id = m.post_id " +
                "WHERE yc.published_at IS NOT NULL",
                rs -> {
                    double score = EngagementScoreCalculator.scoreYoutubeComment(
                            rs.getObject("reply_count", Integer.class),
                            rs.getObject("likes_count", Integer.class));
                    mergeEvent(volumeByEntityAndDate, rs.getLong("entity_id"),
                            rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(), score);
                });
    }

    private void accumulateRedditPosts(Map<Long, NavigableMap<LocalDate, Double>> volumeByEntityAndDate) {
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, rp.created_at AS event_time, " +
                "       rp.num_comments, rp.score " +
                "FROM mention_entities je " +
                "JOIN mentions m ON m.id = je.mention_id AND m.platform = 'REDDIT' " +
                "JOIN managed_entities ent ON ent.id = je.managed_entity_id AND ent.type = 'MOVIE' " +
                "JOIN reddit_posts rp ON rp.id = m.post_id " +
                "WHERE rp.created_at IS NOT NULL",
                rs -> {
                    double score = EngagementScoreCalculator.scoreRedditPost(
                            rs.getObject("num_comments", Integer.class),
                            rs.getObject("score", Integer.class));
                    mergeEvent(volumeByEntityAndDate, rs.getLong("entity_id"),
                            rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(), score);
                });
    }

    private void accumulateInstagramPosts(Map<Long, NavigableMap<LocalDate, Double>> volumeByEntityAndDate) {
        // scoreInstagramPost hard-codes shares=0 (instagram_posts.reshare_count is currently all
        // zero in this corpus - see EngagementScoreCalculator's Instagram adapter javadoc).
        jdbc.query(
                "SELECT je.managed_entity_id AS entity_id, ip.timestamp AS event_time, " +
                "       ip.comments_count, ip.like_count " +
                "FROM mention_entities je " +
                "JOIN mentions m ON m.id = je.mention_id AND m.platform = 'INSTAGRAM' " +
                "JOIN managed_entities ent ON ent.id = je.managed_entity_id AND ent.type = 'MOVIE' " +
                "JOIN instagram_posts ip ON ip.id = m.post_id " +
                "WHERE ip.timestamp IS NOT NULL",
                rs -> {
                    double score = EngagementScoreCalculator.scoreInstagramPost(
                            rs.getObject("comments_count", Integer.class),
                            rs.getObject("like_count", Integer.class));
                    mergeEvent(volumeByEntityAndDate, rs.getLong("entity_id"),
                            rs.getTimestamp("event_time").toLocalDateTime().toLocalDate(), score);
                });
    }

    private static void mergeEvent(Map<Long, NavigableMap<LocalDate, Double>> volumeByEntityAndDate,
                                    long entityId, LocalDate day, double score) {
        volumeByEntityAndDate.computeIfAbsent(entityId, k -> new TreeMap<>()).merge(day, score, Double::sum);
    }

    // -------------------------------------------------------------------------
    // Read paths used by later features (F4/F5/F7/F10).
    // -------------------------------------------------------------------------

    /** The day_index/calendar_date with the highest daily_engagement_volume - the "moment of maximum viewership". */
    public Map<String, Object> peakDay(long entityId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT day_index, calendar_date, daily_engagement_volume FROM entity_daily_vmi " +
                "WHERE entity_id = ? ORDER BY daily_engagement_volume DESC, day_index ASC LIMIT 1",
                entityId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        result.put("dayIndex", row.get("day_index"));
        result.put("calendarDate", row.get("calendar_date"));
        result.put("dailyEngagementVolume", row.get("daily_engagement_volume"));
        return result;
    }

    /** The latest cumulative_engagement_volume - "campaign viewership so far". */
    public Double cumulativeToDate(long entityId) {
        List<Double> rows = jdbc.queryForList(
                "SELECT cumulative_engagement_volume FROM entity_daily_vmi " +
                "WHERE entity_id = ? ORDER BY day_index DESC LIMIT 1",
                Double.class, entityId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Full day-by-day VMI series for an entity, ordered by day_index - the read path behind
     * {@code GET /api/marketing/entity/{entityId}/vmi}. Empty when the entity has no
     * entity_daily_vmi rows yet (never computed, or no tracked mentions).
     */
    public List<Map<String, Object>> series(long entityId) {
        return jdbc.queryForList(
                "SELECT day_index, daily_engagement_volume, cohort_zscore, cumulative_engagement_volume " +
                "FROM entity_daily_vmi WHERE entity_id = ? ORDER BY day_index",
                entityId);
    }

    /**
     * Runs 15 minutes after {@link MarketingEnrichmentScheduler}'s 03:30 UTC refresh and 30
     * minutes before {@link ChannelReachPrecomputer}'s 04:15 UTC run (staggered the same way
     * that job offsets from MarketingEnrichmentScheduler), so this doesn't stack CPU load with
     * the other daily jobs. Configure via {@code vmi.computation.cron}/{@code
     * vmi.computation.zone}; the {@code /api/admin/run-vmi-computation} endpoint always works
     * regardless of the schedule.
     */
    @Scheduled(cron = "${vmi.computation.cron:0 45 3 * * *}", zone = "${vmi.computation.zone:UTC}")
    public void scheduledRecompute() {
        try {
            long start = System.currentTimeMillis();
            log.info("VMI computation starting");
            Map<String, Object> summary = recomputeAndPersist();
            log.info("VMI computation complete in {} ms: {}", System.currentTimeMillis() - start, summary);
        } catch (Exception e) {
            log.error("VMI computation failed", e);
            return;
        }

        // Runs immediately after VMI in the same cycle (not its own cron): behavior features read
        // entity_daily_vmi's day_index alignment as a prerequisite, so they only make sense once VMI
        // has (re)computed it, same dependency style as GraphPopulationService riding
        // MarketingEnrichmentScheduler's cron after UserEngagementRatingService.
        try {
            long start = System.currentTimeMillis();
            log.info("Behavior feature computation starting");
            Map<String, Object> summary = behaviorFeatureComputationService.recomputeAndPersist();
            log.info("Behavior feature computation complete in {} ms: {}", System.currentTimeMillis() - start, summary);
        } catch (Exception e) {
            log.error("Behavior feature computation failed", e);
            return;
        }

        // Runs last in the same cycle, after both its dependencies (entity_daily_vmi and
        // entity_daily_behavior_features) are fresh: causal lift scoring reads entity_daily_vmi's
        // cumulative_engagement_volume trend directly and needs entity_daily_behavior_features'
        // day_index alignment/prerequisite check to hold, same chaining style as this method's own
        // relationship to MarketingEnrichmentScheduler.refresh().
        try {
            long start = System.currentTimeMillis();
            log.info("Causal lift scoring starting");
            Map<String, Object> summary = userCausalLiftScoreService.recomputeAndPersist();
            log.info("Causal lift scoring complete in {} ms: {}", System.currentTimeMillis() - start, summary);
        } catch (Exception e) {
            log.error("Causal lift scoring failed", e);
        }
    }
}
