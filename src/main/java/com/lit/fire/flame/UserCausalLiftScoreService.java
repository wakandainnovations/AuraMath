package com.lit.fire.flame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-user "Causal Lift Score": whether a user's engagement during an entity's high-net-sentiment
 * periods is followed by above-baseline viewership growth for that entity - distinct from {@link
 * UserEngagementRatingService}'s engagement_rating (loudness, corpus-relative percentile of raw
 * engagement volume) and {@code moi_score} (efficiency), neither of which look at what happens
 * to viewership *after* a user's engagement.
 *
 * <p>Pipeline, keyed off {@link VmiComputationService}'s {@code entity_daily_vmi} (F1) and
 * {@link BehaviorFeatureComputationService}'s {@code entity_daily_behavior_features} (F3) day_index
 * alignment - this service does nothing for an entity that hasn't been VMI-computed yet, same
 * dependency posture as F3 has on F1:
 * <ol>
 *   <li><b>Per-entity relative sentiment threshold</b>: for every day_index in that entity's own
 *       {@code entity_daily_vmi} history, compute the trailing-7-day net sentiment ratio ending on
 *       that day_index's calendar_date, using {@link BehaviorFeatureComputationService#netSentimentScore}
 *       verbatim (itself {@code com.aura.service.service.DashboardService}'s exact
 *       positiveMentions/negativeMentions formula). The entity's own top-quartile (75th percentile,
 *       linear-interpolated the same way Postgres's {@code percentile_cont} would) across that
 *       full ratio history is its qualifying threshold - deliberately per-entity, not a single
 *       fixed cutoff shared across entities with very different sentiment-volume profiles.</li>
 *   <li><b>Qualifying events</b>: for every (entity, day_index) whose trailing ratio clears that
 *       entity's own threshold, every raw author who mentioned the entity that calendar day is
 *       resolved to a global_user_id via {@code user_identity_link}, normalized the same way
 *       {@link GenreLookalikeService#normalize} does. Each resolved user yields one qualifying
 *       event (user, entity, day_index).</li>
 *   <li><b>Lift</b>: for each qualifying event, an ordinary-least-squares line is fit to {@code
 *       entity_daily_vmi.cumulative_engagement_volume} over whatever day_index points exist in the
 *       7 days before day_index (entity_daily_vmi can have gaps on zero-engagement calendar days,
 *       so this uses whatever is present rather than requiring all 7), projected forward 3 days,
 *       and compared against the actual cumulative_engagement_volume at day_index + 3. This is an
 *       <b>interrupted-trend estimate against the entity's own pre-event trajectory, not a
 *       randomized control</b> - there is no held-out counterfactual entity/user, so "lift" here
 *       means "deviation from where the entity's own prior trend line said it would be", which can
 *       be confounded by anything else happening to the entity at the same time. Events whose
 *       trailing window has fewer than 2 usable points, or whose day_index + 3 row doesn't exist
 *       yet (either genuinely within the last 3 days of the entity's tracked history, or a gap),
 *       are skipped - "no actual to compare against yet" covers both.</li>
 *   <li><b>Aggregation</b>: per global_user_id, with &gt;= 2 qualifying events the per-event lifts
 *       are averaged weighted by inverse variance (variance = the mean squared residual of that
 *       event's own trend fit - the entity's residual volatility around its pre-event trajectory,
 *       clamped to {@link #MIN_VARIANCE} to avoid a divide-by-zero blowup on a near-perfect fit);
 *       with exactly 1 event the lift is used unweighted. confidence is HIGH at &gt;= 3 events,
 *       else LOW. Users with zero qualifying events get no row at all (not a zero score) - a user
 *       who never engaged during a high-sentiment window has no causal claim to make one way or
 *       the other.</li>
 * </ol>
 */
@Service
public class UserCausalLiftScoreService {

    private static final Logger log = LoggerFactory.getLogger(UserCausalLiftScoreService.class);

    private static final int TRAILING_SENTIMENT_WINDOW_DAYS = 7;
    private static final int TRAILING_TREND_WINDOW_DAYS = 7;
    private static final int PROJECTION_HORIZON_DAYS = 3;
    private static final double TOP_QUARTILE = 0.75;

    // Floor applied to a trend fit's residual variance before it's used as an inverse-variance
    // weight - an event whose 7 prior points sit near-perfectly on the fitted line would otherwise
    // produce a near-infinite weight and dominate that user's aggregate off a single lucky fit.
    private static final double MIN_VARIANCE = 1e-6;

    @Autowired
    private JdbcTemplate jdbc;

    private void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_causal_lift_scores (" +
                "global_user_id VARCHAR(255) PRIMARY KEY, " +
                "causal_lift_score DOUBLE PRECISION NOT NULL, " +
                "n_qualifying_events INT NOT NULL, " +
                "confidence VARCHAR(10) NOT NULL, " +
                "last_computed_at TIMESTAMP NOT NULL)");
    }

    record VmiPoint(LocalDate calendarDate, double cumulativeVolume) {}

    /** One computed qualifying event's lift and the variance to weight it by. */
    record EventLift(double lift, double variance) {}

    /** slope/intercept of the OLS fit over the trailing window, plus its mean-squared residual. */
    record TrendFit(double slope, double intercept, double residualVariance) {}

    /** Rebuilds user_causal_lift_scores for every user with >= 1 qualifying event. */
    public Map<String, Object> recomputeAndPersist() {
        ensureSchema();

        Map<Long, NavigableMap<Integer, VmiPoint>> vmiByEntity = loadVmiPoints();
        if (vmiByEntity.isEmpty()) {
            log.info("Causal lift scoring skipped: entity_daily_vmi is empty (F1 hasn't run yet)");
            return Map.of("usersScored", 0, "qualifyingEventsTotal", 0, "rowsUpserted", 0);
        }

        Map<Long, NavigableMap<LocalDate, long[]>> sentimentByEntityDate = new HashMap<>();
        Map<Long, Map<LocalDate, Set<String>>> authorsByEntityDate = new HashMap<>();
        loadMentionSentimentAndAuthors(sentimentByEntityDate, authorsByEntityDate);

        Map<String, String> identityIndex = loadIdentityIndex();

        Map<String, List<EventLift>> eventsByUser = new LinkedHashMap<>();
        int qualifyingEventsTotal = 0;

        for (Map.Entry<Long, NavigableMap<Integer, VmiPoint>> entityEntry : vmiByEntity.entrySet()) {
            long entityId = entityEntry.getKey();
            NavigableMap<Integer, VmiPoint> points = entityEntry.getValue();
            if (points.isEmpty()) continue;

            NavigableMap<LocalDate, long[]> sentimentByDate =
                    sentimentByEntityDate.getOrDefault(entityId, new TreeMap<>());
            Map<LocalDate, Set<String>> authorsByDate = authorsByEntityDate.getOrDefault(entityId, Map.of());

            // Step 1: this entity's own trailing net-sentiment ratio for every day_index it has,
            // and its own top-quartile threshold across that history.
            Map<Integer, Double> ratioByDayIndex = new LinkedHashMap<>();
            List<Double> ratios = new ArrayList<>();
            for (Map.Entry<Integer, VmiPoint> p : points.entrySet()) {
                double ratio = trailingNetSentimentRatio(sentimentByDate, p.getValue().calendarDate());
                ratioByDayIndex.put(p.getKey(), ratio);
                ratios.add(ratio);
            }
            List<Double> sortedRatios = new ArrayList<>(ratios);
            Collections.sort(sortedRatios);
            double threshold = topQuartileThreshold(sortedRatios);

            // Steps 2-3: qualifying (entity, day_index) days -> resolved users -> lift.
            for (Map.Entry<Integer, Double> r : ratioByDayIndex.entrySet()) {
                if (r.getValue() < threshold) continue;
                int dayIndex = r.getKey();

                LocalDate date = points.get(dayIndex).calendarDate();
                Set<String> authors = authorsByDate.getOrDefault(date, Set.of());
                if (authors.isEmpty()) continue;

                Set<String> resolvedUsers = new HashSet<>();
                for (String author : authors) {
                    String globalUserId = identityIndex.get(GenreLookalikeService.normalize(author));
                    if (globalUserId != null) resolvedUsers.add(globalUserId);
                }
                if (resolvedUsers.isEmpty()) continue;

                // day_index + 3's row missing covers both "genuinely within the last 3 days of
                // this entity's tracked history" and an ordinary gap day - either way, no actual
                // to compare against yet.
                VmiPoint actualPoint = points.get(dayIndex + PROJECTION_HORIZON_DAYS);
                if (actualPoint == null) continue;

                NavigableMap<Integer, Double> trailingWindow = new TreeMap<>();
                for (int d = dayIndex - TRAILING_TREND_WINDOW_DAYS; d <= dayIndex - 1; d++) {
                    VmiPoint pt = points.get(d);
                    if (pt != null) trailingWindow.put(d, pt.cumulativeVolume());
                }
                if (trailingWindow.size() < 2) continue;

                TrendFit fit = fitLinearTrend(trailingWindow);
                double projected = fit.intercept() + fit.slope() * (dayIndex + PROJECTION_HORIZON_DAYS);
                double lift = computeLift(actualPoint.cumulativeVolume(), projected);

                qualifyingEventsTotal++;
                for (String globalUserId : resolvedUsers) {
                    eventsByUser.computeIfAbsent(globalUserId, k -> new ArrayList<>())
                            .add(new EventLift(lift, fit.residualVariance()));
                }
            }
        }

        Timestamp computedAt = Timestamp.from(Instant.now());
        List<Object[]> batchArgs = buildUpsertArgs(eventsByUser, computedAt);

        int[] rowsUpserted = batchArgs.isEmpty() ? new int[0] : jdbc.batchUpdate(
                "INSERT INTO user_causal_lift_scores " +
                "(global_user_id, causal_lift_score, n_qualifying_events, confidence, last_computed_at) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (global_user_id) DO UPDATE SET " +
                "causal_lift_score = EXCLUDED.causal_lift_score, " +
                "n_qualifying_events = EXCLUDED.n_qualifying_events, " +
                "confidence = EXCLUDED.confidence, " +
                "last_computed_at = EXCLUDED.last_computed_at",
                batchArgs,
                new int[]{Types.VARCHAR, Types.DOUBLE, Types.INTEGER, Types.VARCHAR, Types.TIMESTAMP});

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("usersScored", eventsByUser.size());
        summary.put("qualifyingEventsTotal", qualifyingEventsTotal);
        summary.put("rowsUpserted", rowsUpserted.length);
        log.info("Causal lift scoring complete: {}", summary);
        return summary;
    }

    /** entity_id -> day_index -> (calendar_date, cumulative_engagement_volume), from entity_daily_vmi. */
    private Map<Long, NavigableMap<Integer, VmiPoint>> loadVmiPoints() {
        Map<Long, NavigableMap<Integer, VmiPoint>> result = new HashMap<>();
        jdbc.query("SELECT entity_id, day_index, calendar_date, cumulative_engagement_volume FROM entity_daily_vmi",
                rs -> {
                    result.computeIfAbsent(rs.getLong("entity_id"), k -> new TreeMap<>())
                            .put(rs.getInt("day_index"),
                                    new VmiPoint(rs.getDate("calendar_date").toLocalDate(),
                                            rs.getDouble("cumulative_engagement_volume")));
                });
        return result;
    }

    /**
     * entity_id -> calendar_date -> [positiveCount, negativeCount] (all mentions, same join as
     * {@link BehaviorFeatureComputationService#loadMentionAggregates} - see its class javadoc on
     * why mention_entities/mentions rather than mentions.managed_entity_id), and entity_id ->
     * calendar_date -> the set of raw authors who mentioned the entity that day (author present
     * mentions only - a mention with no author can't resolve to a global_user_id anyway).
     */
    private void loadMentionSentimentAndAuthors(Map<Long, NavigableMap<LocalDate, long[]>> sentimentByEntityDate,
                                                 Map<Long, Map<LocalDate, Set<String>>> authorsByEntityDate) {
        jdbc.query(
                "SELECT me.managed_entity_id AS entity_id, m.post_date AS post_date, " +
                "       m.sentiment AS sentiment, m.author AS author " +
                "FROM mention_entities me " +
                "JOIN mentions m ON m.id = me.mention_id " +
                "JOIN managed_entities ent ON ent.id = me.managed_entity_id AND ent.type = 'MOVIE' " +
                "WHERE m.post_date IS NOT NULL",
                rs -> {
                    long entityId = rs.getLong("entity_id");
                    LocalDate date = rs.getTimestamp("post_date").toLocalDateTime().toLocalDate();
                    String sentiment = rs.getString("sentiment");
                    String author = rs.getString("author");

                    if ("POSITIVE".equals(sentiment) || "NEGATIVE".equals(sentiment)) {
                        long[] delta = "POSITIVE".equals(sentiment) ? new long[]{1, 0} : new long[]{0, 1};
                        sentimentByEntityDate.computeIfAbsent(entityId, k -> new TreeMap<>())
                                .merge(date, delta, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
                    }

                    if (author != null && !author.isBlank()) {
                        authorsByEntityDate.computeIfAbsent(entityId, k -> new HashMap<>())
                                .computeIfAbsent(date, k -> new HashSet<>())
                                .add(author);
                    }
                });
    }

    private Map<String, String> loadIdentityIndex() {
        Map<String, String> index = new HashMap<>();
        jdbc.query("SELECT normalized_author, global_user_id FROM user_identity_link", rs -> {
            index.put(rs.getString("normalized_author"), rs.getString("global_user_id"));
        });
        return index;
    }

    // -------------------------------------------------------------------------
    // Pure computation helpers - unit-tested directly.
    // -------------------------------------------------------------------------

    /**
     * Trailing-{@value #TRAILING_SENTIMENT_WINDOW_DAYS}-day net sentiment ratio ending on (and
     * including) {@code day}, via {@link BehaviorFeatureComputationService#netSentimentScore} -
     * DashboardService's exact positiveMentions/negativeMentions formula, reused verbatim.
     */
    static double trailingNetSentimentRatio(NavigableMap<LocalDate, long[]> dailySentimentCounts, LocalDate day) {
        LocalDate windowStart = day.minusDays(TRAILING_SENTIMENT_WINDOW_DAYS - 1L);
        long positive = 0, negative = 0;
        for (long[] counts : dailySentimentCounts.subMap(windowStart, true, day, true).values()) {
            positive += counts[0];
            negative += counts[1];
        }
        return BehaviorFeatureComputationService.netSentimentScore(positive, negative);
    }

    /**
     * The entity's own top-quartile cutoff across {@code sortedAscendingRatios} (its full trailing-
     * ratio history to date) - linear-interpolated the same way Postgres's {@code percentile_cont}
     * computes a percentile, so a day qualifies only relative to how sentiment-heavy *this* entity's
     * own history has been, not a threshold shared across entities with very different profiles.
     */
    static double topQuartileThreshold(List<Double> sortedAscendingRatios) {
        int n = sortedAscendingRatios.size();
        if (n == 0) return 0.0;
        if (n == 1) return sortedAscendingRatios.get(0);
        double idx = TOP_QUARTILE * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sortedAscendingRatios.get(lo);
        double frac = idx - lo;
        return sortedAscendingRatios.get(lo) + (sortedAscendingRatios.get(hi) - sortedAscendingRatios.get(lo)) * frac;
    }

    /** Ordinary-least-squares fit of cumulativeVolume on day_index, plus its mean-squared residual. */
    static TrendFit fitLinearTrend(NavigableMap<Integer, Double> pointsByDayIndex) {
        int n = pointsByDayIndex.size();
        double sumX = 0.0, sumY = 0.0;
        for (Map.Entry<Integer, Double> e : pointsByDayIndex.entrySet()) {
            sumX += e.getKey();
            sumY += e.getValue();
        }
        double meanX = sumX / n, meanY = sumY / n;

        double sxx = 0.0, sxy = 0.0;
        for (Map.Entry<Integer, Double> e : pointsByDayIndex.entrySet()) {
            double dx = e.getKey() - meanX;
            sxx += dx * dx;
            sxy += dx * (e.getValue() - meanY);
        }
        double slope = sxx == 0.0 ? 0.0 : sxy / sxx;
        double intercept = meanY - slope * meanX;

        double sse = 0.0;
        for (Map.Entry<Integer, Double> e : pointsByDayIndex.entrySet()) {
            double predicted = intercept + slope * e.getKey();
            double residual = e.getValue() - predicted;
            sse += residual * residual;
        }
        // Population variance (divide by n, not n-2): these windows are 2-7 points, mirroring
        // VmiComputationService's meanAndStdDev population-variance convention rather than a
        // sample-variance correction that would blow up or divide-by-zero at the smallest windows.
        double residualVariance = sse / n;
        return new TrendFit(slope, intercept, residualVariance);
    }

    /**
     * lift = (actual - projected) / max(projected, 1.0) - an interrupted-trend estimate against the
     * entity's own pre-event trajectory (see class javadoc), not a randomized control.
     */
    static double computeLift(double actual, double projected) {
        return (actual - projected) / Math.max(projected, 1.0);
    }

    static String confidenceFor(int nQualifyingEvents) {
        return nQualifyingEvents >= 3 ? "HIGH" : "LOW";
    }

    /** >= 2 events: inverse-variance weighted average. Exactly 1 event: used unweighted. */
    static double aggregateLift(List<EventLift> events) {
        if (events.size() == 1) {
            return events.get(0).lift();
        }
        double weightedSum = 0.0, weightSum = 0.0;
        for (EventLift e : events) {
            double weight = 1.0 / Math.max(e.variance(), MIN_VARIANCE);
            weightedSum += weight * e.lift();
            weightSum += weight;
        }
        return weightedSum / weightSum;
    }

    /** Builds batch upsert args for every user with >= 1 qualifying event; absent users get no row. */
    static List<Object[]> buildUpsertArgs(Map<String, List<EventLift>> eventsByUser, Timestamp computedAt) {
        List<Object[]> batchArgs = new ArrayList<>();
        for (Map.Entry<String, List<EventLift>> entry : eventsByUser.entrySet()) {
            List<EventLift> events = entry.getValue();
            if (events.isEmpty()) continue;
            double causalLiftScore = aggregateLift(events);
            int n = events.size();
            batchArgs.add(new Object[]{entry.getKey(), causalLiftScore, n, confidenceFor(n), computedAt});
        }
        return batchArgs;
    }
}
