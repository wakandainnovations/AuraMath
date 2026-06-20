package com.lit.fire.flame;

/**
 * Pure, dependency-free scoring model behind the Celebrity Analytics API.
 *
 * It turns raw, observable signals about a celebrity's tracked conversation
 * (volume, fan-base size, cross-platform reach, engagement, Hawkes self-excitation,
 * sentiment distribution and advocate strength) into the headline business metrics
 * the API exposes:
 *
 *   • predictedBrandValueUsd  – modelled annualised endorsement/brand value in USD
 *   • socialMediaReachValue   – total cross-platform exposure (passed through)
 *   • fanEngagementValue      – total cross-platform interactions (passed through)
 *   • endorsementScore        – 0–100 suitability for paid endorsement
 *
 * plus four interpretable percentage scores (0–100):
 *
 *   • socialMediaInfluence    – how far and how self-sustaining the reach is
 *   • brandPower              – overall commercial strength of the brand
 *   • fanLoyalty              – how devoted / repeat-engaged the fan base is
 *   • controversyRisk         – exposure to negative / volatile sentiment
 *
 * The model is deliberately a set of bounded, monotonic transforms so the output
 * is stable and explainable. Every magnitude is squashed into [0,1) with a
 * saturating transform before being combined with fixed weights — the same
 * {@code raw/(raw+k)} and {@code log10}-compression idioms already used by
 * {@link EntityMarketingService#affinityScore} and {@link SeedScoreCalibrator}.
 * The weights and scale constants are documented priors; they can later be
 * cohort-calibrated against the full celebrity roster the way
 * {@link SeedScoreCalibrator} recalibrates its reach/MOI weights from percentiles.
 *
 * The class is intentionally free of Spring and JDBC so the math can be unit
 * tested in isolation ({@code CelebrityMetricsModelTest}).
 */
public final class CelebrityMetricsModel {

    private CelebrityMetricsModel() { }

    // -------------------------------------------------------------------------
    // Documented model constants (priors). See class doc for calibration notes.
    // -------------------------------------------------------------------------

    /** log10 half-saturation scale for reach: reach with log10≈this scores ~0.5. */
    public static final double REACH_LOG_SCALE      = 4.0;
    /** log10 half-saturation scale for fan-base size (distinct authors). */
    public static final double AUDIENCE_LOG_SCALE   = 2.0;
    /** log10 half-saturation scale for engagement-rate signals. */
    public static final double ENGAGEMENT_LOG_SCALE = 2.0;
    /** eCPM-style prior: USD of brand value per 1,000 units of annualised reach. */
    public static final double USD_PER_1000_REACH   = 22.0;
    /** Maximum fraction of brand value erased by maximal controversy risk. */
    public static final double MAX_CONTROVERSY_DISCOUNT = 0.5;

    // -------------------------------------------------------------------------
    // Inputs / outputs
    // -------------------------------------------------------------------------

    /**
     * Raw observable signals for one celebrity over the observation window.
     *
     * @param totalPosts          number of scored posts mentioning the celebrity
     * @param fanBaseSize         distinct authors posting about the celebrity
     * @param observationSpanDays length of the observed window, in days
     * @param branchingRatio      Hawkes branching ratio (alpha/beta); ≥1 is supercritical
     * @param reachTotal          total cross-platform exposure (impressions proxy)
     * @param engagementTotal     total cross-platform interactions (likes+comments)
     * @param positiveCount       posts with positive tone
     * @param negativeCount       posts with negative tone
     * @param neutralCount        posts with neutral tone
     * @param sentimentStdev      stdev of normalised sentiment in [0,1] (score/100)
     * @param negativeBurstShare  fraction of in-burst posts that are negative, [0,1]
     * @param advocacyBranchingRatio mean Hawkes branching ratio (alpha/beta) of the top advocates, [0,1)
     */
    public record Signals(
            long   totalPosts,
            long   fanBaseSize,
            double observationSpanDays,
            double branchingRatio,
            long   reachTotal,
            long   engagementTotal,
            long   positiveCount,
            long   negativeCount,
            long   neutralCount,
            double sentimentStdev,
            double negativeBurstShare,
            double advocacyBranchingRatio) { }

    /** Computed metrics. Percentages are 0–100; values are absolute. */
    public record Metrics(
            // headline business metrics
            double predictedBrandValueUsd,
            long   socialMediaReachValue,
            long   fanEngagementValue,
            double endorsementScore,
            // percentage key metrics (0–100)
            double socialMediaInfluencePct,
            double brandPowerPct,
            double fanLoyaltyPct,
            double controversyRiskPct,
            // intermediate sub-scores in [0,1], surfaced for transparency
            double reachScore,
            double audienceScore,
            double viralityScore,
            double engagementRateScore,
            double engagementPerFanScore,
            double repeatPostingScore,
            double positivityScore,
            double sentimentVolatilityScore,
            double negativeBurstScore,
            double advocacyScore,
            // derived intermediates exposed for explainability
            double netSentiment,
            double annualisedReach,
            double brandQualityMultiplier) { }

    // -------------------------------------------------------------------------
    // Bounded transforms
    // -------------------------------------------------------------------------

    static double clamp01(double x) {
        if (Double.isNaN(x)) return 0.0;
        return Math.max(0.0, Math.min(1.0, x));
    }

    /** Soft-saturating ratio {@code x/(x+k)} on [0,∞) → [0,1). */
    static double saturate(double x, double k) {
        double v = Math.max(0.0, x);
        return v / (v + k);
    }

    /** log10-compressed saturation: {@code L/(L+k)} where {@code L = log10(1+x)}. */
    static double saturateLog10(double x, double k) {
        double l = Math.log10(1.0 + Math.max(0.0, x));
        return l / (l + k);
    }

    // -------------------------------------------------------------------------
    // Model
    // -------------------------------------------------------------------------

    public static Metrics compute(Signals s) {
        long total = Math.max(1L, s.positiveCount() + s.negativeCount() + s.neutralCount());
        double negShare      = (double) s.negativeCount() / total;
        double netSentiment  = (double) (s.positiveCount() - s.negativeCount()) / total; // [-1,1]
        double positivity    = (netSentiment + 1.0) / 2.0;                               // [0,1]

        // --- normalised sub-scores -------------------------------------------
        double reachScore        = saturateLog10(s.reachTotal(),  REACH_LOG_SCALE);
        double audienceScore     = saturateLog10(s.fanBaseSize(), AUDIENCE_LOG_SCALE);
        double viralityScore     = clamp01(s.branchingRatio());

        double engPerPost        = s.totalPosts()  > 0 ? (double) s.engagementTotal() / s.totalPosts()  : 0.0;
        double engPerFan         = s.fanBaseSize() > 0 ? (double) s.engagementTotal() / s.fanBaseSize() : 0.0;
        double engagementRate    = saturateLog10(engPerPost, ENGAGEMENT_LOG_SCALE);
        double engagementPerFan  = saturateLog10(engPerFan,  ENGAGEMENT_LOG_SCALE);

        double postsPerFan       = s.fanBaseSize() > 0 ? (double) s.totalPosts() / s.fanBaseSize() : 0.0;
        double repeatPosting     = saturate(postsPerFan - 1.0, 1.0);   // >1 post/fan ⇒ loyalty
        double advocacy          = clamp01(s.advocacyBranchingRatio());
        double volatility        = clamp01(2.0 * s.sentimentStdev());  // 0.5 stdev ⇒ saturated
        double negativeBurst     = clamp01(s.negativeBurstShare());

        // --- percentage key metrics ------------------------------------------
        double influencePct = 100.0 * (0.50 * viralityScore
                                     + 0.30 * audienceScore
                                     + 0.20 * reachScore);

        double brandPowerPct = 100.0 * (0.30 * reachScore
                                      + 0.20 * audienceScore
                                      + 0.20 * engagementRate
                                      + 0.15 * viralityScore
                                      + 0.15 * positivity);

        double fanLoyaltyPct = 100.0 * (0.35 * repeatPosting
                                      + 0.30 * positivity
                                      + 0.20 * engagementPerFan
                                      + 0.15 * advocacy);

        double controversyRiskPct = 100.0 * (0.50 * negShare
                                            + 0.30 * volatility
                                            + 0.20 * negativeBurst);

        // --- endorsement score: brand strength penalised by controversy ------
        double endorsementBase = 0.35 * reachScore
                               + 0.25 * positivity
                               + 0.20 * (fanLoyaltyPct / 100.0)
                               + 0.20 * (influencePct / 100.0);
        double controversyPenalty = 1.0 - 0.7 * (controversyRiskPct / 100.0);
        double endorsementScore   = 100.0 * endorsementBase * controversyPenalty;

        // --- predicted brand value (USD) -------------------------------------
        // Annualise the observed reach, value it at an eCPM-style rate, then scale
        // by a brand-quality multiplier (0.5×–2.0×) and discount for controversy.
        double annualReach = s.observationSpanDays() > 0
                ? s.reachTotal() / s.observationSpanDays() * 365.0
                : s.reachTotal();
        double qualityMultiplier   = 0.5 + 1.5 * (brandPowerPct / 100.0);            // [0.5, 2.0]
        double controversyDiscount = MAX_CONTROVERSY_DISCOUNT * (controversyRiskPct / 100.0);
        double brandValueUsd = (annualReach / 1000.0)
                             * USD_PER_1000_REACH
                             * qualityMultiplier
                             * (1.0 - controversyDiscount);

        return new Metrics(
                brandValueUsd,
                s.reachTotal(),
                s.engagementTotal(),
                endorsementScore,
                influencePct,
                brandPowerPct,
                fanLoyaltyPct,
                controversyRiskPct,
                reachScore,
                audienceScore,
                viralityScore,
                engagementRate,
                engagementPerFan,
                repeatPosting,
                positivity,
                volatility,
                negativeBurst,
                advocacy,
                netSentiment,
                annualReach,
                qualityMultiplier);
    }
}
