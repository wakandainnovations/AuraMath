package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link UserCausalLiftScoreService}'s pure calculation helpers directly (same style as
 * {@link BehaviorFeatureComputationServiceTest}), rather than driving recomputeAndPersist() over a
 * mocked JdbcTemplate: the four behaviors this task calls out (per-entity relative threshold,
 * inverse-variance weighting gated at n>=2, the exact n>=3 HIGH-confidence gate, and zero-event
 * users getting no row) are all decided by these helpers, independent of the surrounding SQL
 * orchestration.
 */
public class UserCausalLiftScoreServiceTest {

    private static final double DELTA = 1e-9;

    // -------------------------------------------------------------------------
    // topQuartileThreshold - must be relative to the entity's OWN ratio history, not a fixed
    // global cutoff: two entities with very different sentiment-ratio distributions get different
    // absolute thresholds even though the same 75th-percentile logic is applied to both.
    // -------------------------------------------------------------------------

    @Test
    public void topQuartileThresholdIsRelativeToEachEntitysOwnHistoryNotAFixedCutoff() {
        // Entity A: a "quiet" entity whose ratios never exceed 1.0.
        List<Double> entityARatios = new ArrayList<>(List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8));
        Collections.sort(entityARatios);
        double thresholdA = UserCausalLiftScoreService.topQuartileThreshold(entityARatios);

        // Entity B: a "loud" entity whose ratios are all far higher.
        List<Double> entityBRatios = new ArrayList<>(List.of(5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0));
        Collections.sort(entityBRatios);
        double thresholdB = UserCausalLiftScoreService.topQuartileThreshold(entityBRatios);

        // A fixed global cutoff (e.g. "2.0") would call every one of entity B's days qualifying and
        // none of entity A's high days qualifying; the per-entity threshold instead sits inside each
        // entity's own distribution.
        assertTrue(thresholdA < 1.0, "entity A's own top-quartile threshold must stay within its own low range");
        assertTrue(thresholdB > 4.0, "entity B's own top-quartile threshold must stay within its own high range");
        assertTrue(thresholdA < thresholdB, "the two entities' thresholds must differ - neither is a shared fixed cutoff");

        // A day at entity A's own high end (0.8) qualifies against ITS threshold...
        assertTrue(0.8 >= thresholdA);
        // ...but the same absolute ratio value would not qualify against entity B's threshold -
        // proof that qualification is decided per-entity, not against one global number.
        assertFalse(0.8 >= thresholdB);
    }

    @Test
    public void topQuartileThresholdMatchesPercentileContLinearInterpolation() {
        // 5 ascending values -> p75 index = 0.75 * 4 = 3.0 exactly -> the 4th value, no interpolation needed.
        List<Double> ratios = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        assertEquals(4.0, UserCausalLiftScoreService.topQuartileThreshold(ratios), DELTA);

        // 4 ascending values -> p75 index = 0.75 * 3 = 2.25 -> interpolate 25% of the way from
        // index 2 (30.0) to index 3 (40.0) = 30.0 + 0.25*(40.0-30.0) = 32.5.
        List<Double> ratios2 = List.of(10.0, 20.0, 30.0, 40.0);
        assertEquals(32.5, UserCausalLiftScoreService.topQuartileThreshold(ratios2), DELTA);
    }

    // -------------------------------------------------------------------------
    // trailingNetSentimentRatio - must reuse BehaviorFeatureComputationService.netSentimentScore
    // (DashboardService's exact formula) verbatim over a 7-day inclusive window.
    // -------------------------------------------------------------------------

    @Test
    public void trailingNetSentimentRatioSumsA7DayInclusiveWindowThroughDashboardServiceFormula() {
        TreeMap<java.time.LocalDate, long[]> daily = new TreeMap<>();
        java.time.LocalDate day0 = java.time.LocalDate.of(2024, 6, 1);
        daily.put(day0, new long[]{2, 1});
        daily.put(day0.plusDays(1), new long[]{2, 1});
        daily.put(day0.plusDays(2), new long[]{2, 1});
        daily.put(day0.plusDays(3), new long[]{2, 1});
        daily.put(day0.plusDays(4), new long[]{2, 1});
        daily.put(day0.plusDays(5), new long[]{0, 0});
        daily.put(day0.plusDays(6), new long[]{0, 0});
        // Window day0..day0+6 (7 days inclusive): 10 positive, 5 negative -> DashboardService ratio 2.0.
        double ratio = UserCausalLiftScoreService.trailingNetSentimentRatio(daily, day0.plusDays(6));
        assertEquals(2.0, ratio, DELTA);
    }

    // -------------------------------------------------------------------------
    // fitLinearTrend / computeLift - interrupted-trend estimate sanity checks.
    // -------------------------------------------------------------------------

    @Test
    public void fitLinearTrendRecoversAPerfectLineWithZeroResidualVariance() {
        TreeMap<Integer, Double> points = new TreeMap<>();
        // Perfectly linear: cumulative = 10 * dayIndex + 100.
        for (int d = 1; d <= 7; d++) {
            points.put(d, 10.0 * d + 100.0);
        }
        UserCausalLiftScoreService.TrendFit fit = UserCausalLiftScoreService.fitLinearTrend(points);
        assertEquals(10.0, fit.slope(), DELTA);
        assertEquals(100.0, fit.intercept(), DELTA);
        assertEquals(0.0, fit.residualVariance(), DELTA);
    }

    @Test
    public void computeLiftIsPositiveWhenActualExceedsProjectedTrend() {
        // Trend projected 200, actual came in at 260 -> lift = (260-200)/max(200,1) = 0.3.
        assertEquals(0.3, UserCausalLiftScoreService.computeLift(260.0, 200.0), DELTA);
        // Projected floor of 1.0 guards against a near-zero/negative projection blowing up the ratio:
        // (5.0-0.2)/max(0.2,1.0) = 4.8/1.0 = 4.8, not (5.0-0.2)/0.2 = 24.0.
        assertEquals(4.8, UserCausalLiftScoreService.computeLift(5.0, 0.2), DELTA);
    }

    // -------------------------------------------------------------------------
    // aggregateLift - inverse-variance weighting applies only at n >= 2; n == 1 is unweighted.
    // -------------------------------------------------------------------------

    @Test
    public void aggregateLiftWithExactlyOneEventIsUnweighted() {
        List<UserCausalLiftScoreService.EventLift> events =
                List.of(new UserCausalLiftScoreService.EventLift(0.42, 0.0001));
        // With a single event the raw lift is used directly - its variance must have zero influence.
        assertEquals(0.42, UserCausalLiftScoreService.aggregateLift(events), DELTA);
    }

    @Test
    public void aggregateLiftWithTwoEventsWeightsByInverseVarianceNotASimpleMean() {
        // Event A: lift 1.0, low variance (0.01) -> high weight (100).
        // Event B: lift -1.0, high variance (1.0) -> low weight (1).
        List<UserCausalLiftScoreService.EventLift> events = List.of(
                new UserCausalLiftScoreService.EventLift(1.0, 0.01),
                new UserCausalLiftScoreService.EventLift(-1.0, 1.0));

        double aggregated = UserCausalLiftScoreService.aggregateLift(events);
        double simpleMean = 0.0; // (1.0 + -1.0) / 2

        // A simple mean would land exactly at 0.0; inverse-variance weighting must pull the result
        // toward the low-variance event's lift (1.0) instead.
        assertEquals(0.0, simpleMean, DELTA);
        assertTrue(aggregated > 0.9, "the low-variance event must dominate the weighted average, unlike a simple mean");

        // weight_A = 1/0.01 = 100, weight_B = 1/1.0 = 1 -> (100*1.0 + 1*-1.0) / 101 = 99/101.
        assertEquals(99.0 / 101.0, aggregated, DELTA);
    }

    @Test
    public void aggregateLiftWithThreeEventsWeightsEachByItsOwnVariance() {
        List<UserCausalLiftScoreService.EventLift> events = List.of(
                new UserCausalLiftScoreService.EventLift(2.0, 0.1),
                new UserCausalLiftScoreService.EventLift(0.5, 0.1),
                new UserCausalLiftScoreService.EventLift(-1.0, 10.0));

        double aggregated = UserCausalLiftScoreService.aggregateLift(events);
        // weight = 10, 10, 0.1 -> (10*2.0 + 10*0.5 + 0.1*-1.0) / 20.1
        double expected = (10 * 2.0 + 10 * 0.5 + 0.1 * -1.0) / 20.1;
        assertEquals(expected, aggregated, DELTA);
    }

    // -------------------------------------------------------------------------
    // confidenceFor - the gate is exactly n >= 3 for HIGH, nothing looser or stricter.
    // -------------------------------------------------------------------------

    @Test
    public void confidenceGateIsExactlyThreeEventsForHigh() {
        assertEquals("LOW", UserCausalLiftScoreService.confidenceFor(1));
        assertEquals("LOW", UserCausalLiftScoreService.confidenceFor(2));
        assertEquals("HIGH", UserCausalLiftScoreService.confidenceFor(3));
        assertEquals("HIGH", UserCausalLiftScoreService.confidenceFor(4));
    }

    // -------------------------------------------------------------------------
    // buildUpsertArgs - a user with zero qualifying events must get no row, not a zero score.
    // -------------------------------------------------------------------------

    @Test
    public void userWithZeroQualifyingEventsGetsNoRow() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Map<String, List<UserCausalLiftScoreService.EventLift>> eventsByUser = new TreeMap<>();
        eventsByUser.put("user-with-events", List.of(new UserCausalLiftScoreService.EventLift(0.5, 0.2)));
        // Simulates a user key that ended up with an empty event list (defensive case) rather than
        // never being added to the map at all - either way, no row should be emitted for them.
        eventsByUser.put("user-with-zero-events", List.of());

        List<Object[]> rows = UserCausalLiftScoreService.buildUpsertArgs(eventsByUser, now);

        assertEquals(1, rows.size(), "only the user with >= 1 qualifying event gets a persisted row");
        assertEquals("user-with-events", rows.get(0)[0]);
    }

    @Test
    public void anEmptyEventMapProducesNoRowsAtAll() {
        List<Object[]> rows = UserCausalLiftScoreService.buildUpsertArgs(Map.of(), new Timestamp(System.currentTimeMillis()));
        assertTrue(rows.isEmpty());
    }
}
