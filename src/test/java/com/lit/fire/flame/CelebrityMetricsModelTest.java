package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CelebrityMetricsModel}, the pure scoring math behind the
 * Celebrity Analytics API. No Spring or JDBC required — the model is a function
 * of raw signals, so it is exercised directly.
 */
public class CelebrityMetricsModelTest {

    /** A strong, clean A-list celebrity: huge reach, loyal positive fan base, no controversy. */
    private static CelebrityMetricsModel.Signals aListClean() {
        return new CelebrityMetricsModel.Signals(
                /* totalPosts          */ 50_000,
                /* fanBaseSize         */ 8_000,
                /* observationSpanDays */ 30.0,
                /* branchingRatio      */ 0.85,
                /* reachTotal          */ 500_000_000L,
                /* engagementTotal     */ 40_000_000L,
                /* positiveCount       */ 38_000,
                /* negativeCount       */ 2_000,
                /* neutralCount        */ 10_000,
                /* sentimentStdev      */ 0.12,
                /* negativeBurstShare  */ 0.05,
                /* advocacyAlphaMean   */ 0.8);
    }

    @Test
    void allPercentagesAreWithinZeroToHundred() {
        CelebrityMetricsModel.Metrics m = CelebrityMetricsModel.compute(aListClean());
        for (double pct : new double[]{
                m.socialMediaInfluencePct(), m.brandPowerPct(),
                m.fanLoyaltyPct(), m.controversyRiskPct(), m.endorsementScore()}) {
            assertTrue(pct >= 0.0 && pct <= 100.0, "percentage out of range: " + pct);
        }
    }

    @Test
    void subScoresAreBounded() {
        CelebrityMetricsModel.Metrics m = CelebrityMetricsModel.compute(aListClean());
        for (double v : new double[]{
                m.reachScore(), m.audienceScore(), m.viralityScore(),
                m.engagementRateScore(), m.engagementPerFanScore(),
                m.repeatPostingScore(), m.positivityScore(),
                m.sentimentVolatilityScore(), m.negativeBurstScore(), m.advocacyScore()}) {
            assertTrue(v >= 0.0 && v <= 1.0, "sub-score out of [0,1]: " + v);
        }
    }

    @Test
    void brandValueAndHeadlinePassThroughsAreSane() {
        CelebrityMetricsModel.Metrics m = CelebrityMetricsModel.compute(aListClean());
        assertEquals(500_000_000L, m.socialMediaReachValue());
        assertEquals(40_000_000L,  m.fanEngagementValue());
        assertTrue(m.predictedBrandValueUsd() > 0.0, "brand value should be positive");
        assertTrue(Double.isFinite(m.predictedBrandValueUsd()));
        // 0.5–2.0× quality band
        assertTrue(m.brandQualityMultiplier() >= 0.5 && m.brandQualityMultiplier() <= 2.0);
    }

    @Test
    void controversyDiscountsBrandValueAndEndorsement() {
        CelebrityMetricsModel.Signals clean = aListClean();
        // Same celebrity but with a hostile, volatile, bursty negative conversation.
        CelebrityMetricsModel.Signals toxic = new CelebrityMetricsModel.Signals(
                clean.totalPosts(), clean.fanBaseSize(), clean.observationSpanDays(),
                clean.branchingRatio(), clean.reachTotal(), clean.engagementTotal(),
                /* positive */ 4_000, /* negative */ 40_000, /* neutral */ 6_000,
                /* stdev */ 0.45, /* negBurstShare */ 0.8, clean.advocacyAlphaMean());

        CelebrityMetricsModel.Metrics cleanM = CelebrityMetricsModel.compute(clean);
        CelebrityMetricsModel.Metrics toxicM = CelebrityMetricsModel.compute(toxic);

        assertTrue(toxicM.controversyRiskPct() > cleanM.controversyRiskPct(),
                "controversy risk should rise with negative/volatile sentiment");
        assertTrue(toxicM.predictedBrandValueUsd() < cleanM.predictedBrandValueUsd(),
                "controversy should discount brand value");
        assertTrue(toxicM.endorsementScore() < cleanM.endorsementScore(),
                "controversy should lower endorsement suitability");
    }

    @Test
    void higherReachYieldsHigherBrandValue() {
        CelebrityMetricsModel.Signals small = new CelebrityMetricsModel.Signals(
                1_000, 200, 30.0, 0.4, 1_000_000L, 50_000L,
                600, 100, 300, 0.15, 0.1, 0.3);
        CelebrityMetricsModel.Signals big = new CelebrityMetricsModel.Signals(
                1_000, 200, 30.0, 0.4, 100_000_000L, 50_000L,
                600, 100, 300, 0.15, 0.1, 0.3);
        assertTrue(CelebrityMetricsModel.compute(big).predictedBrandValueUsd()
                 > CelebrityMetricsModel.compute(small).predictedBrandValueUsd());
    }

    @Test
    void emptyAndZeroSignalsDoNotBlowUp() {
        CelebrityMetricsModel.Signals zero = new CelebrityMetricsModel.Signals(
                0, 0, 0.0, 0.0, 0L, 0L, 0, 0, 0, 0.0, 0.0, 0.0);
        CelebrityMetricsModel.Metrics m = CelebrityMetricsModel.compute(zero);
        assertTrue(Double.isFinite(m.predictedBrandValueUsd()));
        assertEquals(0.0, m.predictedBrandValueUsd(), 1e-9);
        assertEquals(0.0, m.controversyRiskPct(), 1e-9);
        // neutral-only conversation ⇒ net sentiment 0 ⇒ positivity 0.5
        assertEquals(0.5, m.positivityScore(), 1e-9);
    }
}
