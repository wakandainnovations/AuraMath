package com.lit.fire.flame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Computes the weights used by /viral-seeds to combine Hawkes α, MOI score, and total
 * reach into a single composite "seedScore". Alpha is the anchor (weight 1.0) and the
 * moi / reach weights are scaled so the typical magnitude of each contribution matches.
 *
 * Why p90 and not the median: across the population, α is bimodal — most non-zero values
 * are either ~0 (no cascade pattern) or saturate at β−ε ≈ 1.0 (optimizer hit the bound).
 * The median of non-saturated α is essentially 0, which would produce useless weights.
 * The 90th percentile captures the "good but not pathological" end of each distribution.
 *
 * Saturated α (≥ 0.99) is excluded from calibration — those values are optimizer artifacts,
 * not real virality signals, and including them would inflate the alpha normalizer.
 *
 * Recalibrates every 24 hours so that as the corpus grows the weights track the
 * shifting distribution. On calibration failure the previous weights are retained.
 */
@Service
public class SeedScoreCalibrator {

    private static final Logger log = LoggerFactory.getLogger(SeedScoreCalibrator.class);

    private static final double ALPHA_SATURATION = 0.99;
    private static final double CALIBRATION_PERCENTILE = 0.9;

    // Sanity bounds — even if the data is degenerate the composite score stays well-behaved.
    private static final double W_MOI_MIN = 0.1,   W_MOI_MAX = 100.0;
    private static final double W_REACH_MIN = 1e-4, W_REACH_MAX = 1.0;

    // Initial defaults derived from the current corpus (43k profiles, romance sample):
    //   p90(α | non-saturated, > 0) ≈ 0.49
    //   p90(moi | > 0)              ≈ 0.23      → W_moi  ≈ 2.13
    //   p90(log1p(reach))           ≈ 7.5       → W_reach ≈ 0.065
    private volatile double wMoi   = 2.0;
    private volatile double wReach = 0.065;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public double getMoiWeight()   { return wMoi; }
    public double getReachWeight() { return wReach; }

    /** Composite score used by /viral-seeds ranking. */
    public double seedScore(double alpha, double moi, long totalReach) {
        return alpha + wMoi * moi + wReach * Math.log1p(totalReach);
    }

    /**
     * Recompute weights from the current corpus. Runs 60s after startup so the DB is
     * warm, then every 24 hours.
     */
    @Scheduled(initialDelay = 60_000L, fixedRate = 24L * 60 * 60 * 1000)
    public void recalibrate() {
        try {
            Double alphaP90 = jdbcTemplate.queryForObject(
                    "SELECT percentile_cont(?) WITHIN GROUP (ORDER BY influence_rank) " +
                    "FROM marketing_target_profiles " +
                    "WHERE influence_rank > 0 AND influence_rank < ?",
                    Double.class, CALIBRATION_PERCENTILE, ALPHA_SATURATION);

            Double moiP90 = jdbcTemplate.queryForObject(
                    "SELECT percentile_cont(?) WITHIN GROUP (ORDER BY moi_score) " +
                    "FROM marketing_target_profiles WHERE moi_score > 0",
                    Double.class, CALIBRATION_PERCENTILE);

            Double logReachP90 = jdbcTemplate.queryForObject(
                    "SELECT percentile_cont(?) WITHIN GROUP (ORDER BY LN(1 + per.total)) " +
                    "FROM (" +
                    "  SELECT author, SUM(reach) AS total FROM (" +
                    "    SELECT author, COALESCE(views_count, 0)::bigint AS reach FROM x_posts          WHERE author IS NOT NULL AND author <> '' " +
                    "    UNION ALL " +
                    "    SELECT author, COALESCE(like_count,  0)::bigint           FROM instagram_posts  WHERE author IS NOT NULL AND author <> '' " +
                    "    UNION ALL " +
                    "    SELECT author, COALESCE(score,       0)::bigint           FROM reddit_posts     WHERE author IS NOT NULL AND author <> '' " +
                    "    UNION ALL " +
                    "    SELECT author, 1::bigint                                  FROM youtube_comments WHERE author IS NOT NULL AND author <> '' " +
                    "  ) u GROUP BY author HAVING SUM(reach) > 0" +
                    ") per",
                    Double.class, CALIBRATION_PERCENTILE);

            if (alphaP90 == null || moiP90 == null || logReachP90 == null
                    || alphaP90 <= 0 || moiP90 <= 0 || logReachP90 <= 0) {
                log.warn("Calibration: insufficient data (alphaP90={}, moiP90={}, logReachP90={}); keeping wMoi={}, wReach={}",
                        alphaP90, moiP90, logReachP90, wMoi, wReach);
                return;
            }

            double newMoi   = clamp(alphaP90 / moiP90,      W_MOI_MIN,   W_MOI_MAX);
            double newReach = clamp(alphaP90 / logReachP90, W_REACH_MIN, W_REACH_MAX);
            log.info("Calibrated seed-score weights: wMoi {} -> {}, wReach {} -> {} (alphaP90={}, moiP90={}, logReachP90={})",
                    wMoi, newMoi, wReach, newReach, alphaP90, moiP90, logReachP90);
            this.wMoi   = newMoi;
            this.wReach = newReach;
        } catch (Exception e) {
            log.warn("Seed-score weight calibration failed; keeping wMoi={}, wReach={}", wMoi, wReach, e);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
