package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AudienceSegmenter}. Pure in-memory Weka clustering — no Spring or JDBC.
 *
 * Focus: the small-/niche-cohort robustness guard. K-Means requires at least as many instances
 * as clusters; without the guard, niche keyword cohorts (fewer users than requested tribes) make
 * Weka throw, and every user loses their persisted tribe_label.
 */
class AudienceSegmenterTest {

    private static Map<String, double[]> vectors(int n) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            // Two well-separated blobs so clustering is meaningful when n is large enough.
            double base = (i % 2 == 0) ? 0.0 : 100.0;
            m.put("user_" + i, new double[]{base + i * 0.01, base - i * 0.01});
        }
        return m;
    }

    @Test
    void emptyInputReturnsEmptyMapWithoutThrowing() {
        AudienceSegmenter seg = new AudienceSegmenter(4);
        Map<String, String> result = assertDoesNotThrow(() -> seg.segmentUsers(new LinkedHashMap<>()));
        assertTrue(result.isEmpty());
    }

    @Test
    void fewerUsersThanClustersDegradesKInsteadOfDroppingEveryone() {
        // 2 users but 5 requested tribes — must NOT return an empty map.
        AudienceSegmenter seg = new AudienceSegmenter(5);
        Map<String, String> result = assertDoesNotThrow(() -> seg.segmentUsers(vectors(2)));

        assertEquals(2, result.size(), "every user must still receive a tribe assignment");
        assertTrue(result.keySet().containsAll(vectors(2).keySet()));
        result.values().forEach(t -> assertNotNull(t));
    }

    @Test
    void singleUserStillGetsAssigned() {
        AudienceSegmenter seg = new AudienceSegmenter(3);
        Map<String, String> result = seg.segmentUsers(vectors(1));
        assertEquals(1, result.size());
    }

    @Test
    void normalCohortAssignsAllUsers() {
        AudienceSegmenter seg = new AudienceSegmenter(2);
        Map<String, double[]> users = vectors(20);
        Map<String, String> result = seg.segmentUsers(users);

        assertEquals(users.size(), result.size());
        assertTrue(result.values().stream().allMatch(t -> t != null && t.startsWith("Tribe_")));
    }

    @Test
    void tribeSummaryDoesNotOverrunWhenKWasDegraded() {
        AudienceSegmenter seg = new AudienceSegmenter(5);   // requested 5 tribes
        Map<String, double[]> users = vectors(2);           // but only 2 users → k degraded to 2
        Map<String, String> assignments = seg.segmentUsers(users);

        // Summary must iterate the actual centroid count, not the requested numClusters (would IOOBE).
        String summary = assertDoesNotThrow(() -> seg.generateTribeSummary(users, assignments));
        assertNotNull(summary);
        assertTrue(summary.contains("Tribe_1"));
    }
}
