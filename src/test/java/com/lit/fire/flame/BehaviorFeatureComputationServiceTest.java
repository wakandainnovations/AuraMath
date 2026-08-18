package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises {@link BehaviorFeatureComputationService}'s pure calculation helpers directly (same
 * style as {@link EngagementScoreCalculatorTest}), rather than driving recomputeAndPersist() over a
 * mocked JdbcTemplate: the three behaviors this task calls out (netSentimentDelta's formula,
 * spreaderTierShare's unresolved-author exclusion, spilloverEvent's threshold) are all decided by
 * these helpers, independent of the surrounding SQL orchestration.
 */
public class BehaviorFeatureComputationServiceTest {

    private static final double DELTA = 1e-9;

    // -------------------------------------------------------------------------
    // netSentimentDelta - must match DashboardService's exact formula (AuraService repo,
    // com.aura.service.service.DashboardService: negativeMentions > 0 ? positive / negative : 0.0).
    // -------------------------------------------------------------------------

    @Test
    public void netSentimentScoreMatchesDashboardServiceRatioFormula() {
        // 8 positive : 4 negative -> ratio 2.0, exactly DashboardService's positive/negative division.
        assertEquals(2.0, BehaviorFeatureComputationService.netSentimentScore(8, 4), DELTA);
        // DashboardService's own zero-negative fallback: 0.0, not +Infinity/undefined.
        assertEquals(0.0, BehaviorFeatureComputationService.netSentimentScore(5, 0), DELTA);
        assertEquals(0.0, BehaviorFeatureComputationService.netSentimentScore(0, 0), DELTA);
    }

    @Test
    public void netSentimentDeltaOnAFixedFixtureMatchesManualWindowMath() {
        // Fixed fixture: one entity's per-day (positive, negative) mention counts across two
        // consecutive trailing-7-day windows.
        NavigableMap<LocalDate, long[]> daily = new TreeMap<>();
        LocalDate day0 = LocalDate.of(2024, 6, 1);
        // Window ending day0 + 6 (days 0..6): 10 positive, 5 negative -> ratio 2.0
        daily.put(day0.plusDays(0), new long[]{2, 1});
        daily.put(day0.plusDays(1), new long[]{2, 1});
        daily.put(day0.plusDays(2), new long[]{2, 1});
        daily.put(day0.plusDays(3), new long[]{2, 1});
        daily.put(day0.plusDays(4), new long[]{2, 1});
        daily.put(day0.plusDays(5), new long[]{0, 0});
        daily.put(day0.plusDays(6), new long[]{0, 0});
        // Window ending day0 + 5 (days -1..5, i.e. day0-1..day0+5): only days 0..5 have data here
        // (day0 - 1 absent from the map, contributes 0/0) -> 10 positive, 5 negative -> ratio 2.0
        // So both windows land on the same ratio and the delta should be exactly 0.
        LocalDate evalDay = day0.plusDays(6);
        double delta = BehaviorFeatureComputationService.netSentimentDelta(daily, evalDay);
        assertEquals(0.0, delta, DELTA, "both trailing windows sum to the same 10:5 ratio here");

        // Now shift day0+6 to add negatives only, moving just the later window's ratio down.
        daily.put(day0.plusDays(6), new long[]{0, 5});
        double shiftedDelta = BehaviorFeatureComputationService.netSentimentDelta(daily, evalDay);
        // Window ending day0+6 (days 0..6): 10 positive, 10 negative -> ratio 1.0
        // Window ending day0+5 (days -1..5): 10 positive, 5 negative -> ratio 2.0
        // delta = 1.0 - 2.0 = -1.0
        assertEquals(-1.0, shiftedDelta, DELTA);
    }

    // -------------------------------------------------------------------------
    // spreaderTierShare - unresolved authors (no marketing_target_profiles row) must never
    // contribute to the numerator, even though their volume still counts toward the denominator.
    // -------------------------------------------------------------------------

    @Test
    public void spreaderTierShareExcludesUnresolvedAuthors() {
        Map<String, Double> volumeByAuthor = Map.of(
                "@topSpreader", 60.0,   // resolved, rating >= p90 -> counts
                "@midTierAuthor", 30.0, // resolved, rating < p90 -> does not count
                "@ghostAuthor", 10.0    // unresolved (absent from marketing_target_profiles) -> must not count
        );
        double totalVolume = 100.0;
        Map<String, Double> engagementRatingByAuthor = Map.of(
                "@topSpreader", 95.0,
                "@midTierAuthor", 40.0
                // "@ghostAuthor" deliberately absent - simulates no marketing_target_profiles row.
        );
        double p90 = 90.0;

        double share = BehaviorFeatureComputationService.spreaderTierShare(
                volumeByAuthor, totalVolume, engagementRatingByAuthor, p90);

        // Only @topSpreader's 60 counts toward the numerator; @ghostAuthor's 10 stays in the
        // denominator (totalVolume=100) but is excluded from the spreader share entirely.
        assertEquals(0.60, share, DELTA);
    }

    @Test
    public void spreaderTierShareIsZeroWhenNoAuthorResolves() {
        Map<String, Double> volumeByAuthor = Map.of("@ghostA", 20.0, "@ghostB", 30.0);
        double share = BehaviorFeatureComputationService.spreaderTierShare(
                volumeByAuthor, 50.0, Map.of(), 90.0);
        assertEquals(0.0, share, DELTA);
    }

    // -------------------------------------------------------------------------
    // spilloverEvent - fires strictly past SPIKE_MULTIPLIER (1.5x), never at or below it.
    // -------------------------------------------------------------------------

    @Test
    public void spilloverEventDoesNotFireExactlyAtOrBelowThreshold() {
        // X: exactly 1.5x its trailing average -> must NOT fire (strictly greater than required,
        // matching AuraService's SentimentAlertService: currentRatio > baselineRatio * SPIKE_MULTIPLIER).
        Map<String, Double> today = Map.of("X", 150.0, "YOUTUBE", 90.0);
        Map<String, Double> trailingAvg = Map.of("X", 100.0, "YOUTUBE", 100.0);

        String result = BehaviorFeatureComputationService.spilloverEvent(today, trailingAvg);
        assertNull(result, "1.5x exactly and below 1.5x must not trigger a spillover event");
    }

    @Test
    public void spilloverEventFiresJustPastTheThreshold() {
        Map<String, Double> today = Map.of("X", 150.01, "YOUTUBE", 100.0);
        Map<String, Double> trailingAvg = Map.of("X", 100.0, "YOUTUBE", 100.0);

        String result = BehaviorFeatureComputationService.spilloverEvent(today, trailingAvg);
        assertEquals("X", result);
    }

    @Test
    public void spilloverEventPicksTheHighestRatioWhenMultiplePlatformsCross() {
        Map<String, Double> today = Map.of("X", 200.0, "REDDIT", 400.0);
        Map<String, Double> trailingAvg = Map.of("X", 100.0, "REDDIT", 100.0);

        // X ratio = 2.0x, REDDIT ratio = 4.0x - both cross 1.5x, REDDIT is the larger spike.
        String result = BehaviorFeatureComputationService.spilloverEvent(today, trailingAvg);
        assertEquals("REDDIT", result);
    }

    @Test
    public void spilloverEventSkipsPlatformsWithNoBaseline() {
        Map<String, Double> today = Map.of("INSTAGRAM", 500.0);
        Map<String, Double> trailingAvg = Map.of("INSTAGRAM", 0.0);

        assertNull(BehaviorFeatureComputationService.spilloverEvent(today, trailingAvg));
    }

    // -------------------------------------------------------------------------
    // cascadeDepth - sanity check on the per-user approximation's resolution chain.
    // -------------------------------------------------------------------------

    @Test
    public void cascadeDepthAveragesOnlyOverResolvedAuthors() {
        Set<String> postingAuthors = Set.of("@amuthabharathi", "@unresolvedGhost");
        Map<String, String> identityIndex = Map.of("amuthabharathi", "user-amuthabharathi-001");
        Map<String, Long> nodeIdByGlobalUserId = Map.of("user-amuthabharathi-001", 42L);
        Map<Long, Long> retweetedInCountByNodeId = Map.of(42L, 6L);

        double depth = BehaviorFeatureComputationService.cascadeDepth(
                postingAuthors, identityIndex, nodeIdByGlobalUserId, retweetedInCountByNodeId);

        // Only @amuthabharathi resolves (via GenreLookalikeService.normalize -> user_identity_link ->
        // graph_nodes); @unresolvedGhost contributes to neither the sum nor the average's denominator.
        assertEquals(6.0, depth, DELTA);
    }
}
