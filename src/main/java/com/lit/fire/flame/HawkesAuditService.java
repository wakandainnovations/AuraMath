package com.lit.fire.flame;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.SimpleBounds;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.BOBYQAOptimizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Shared service that fetches a user's cross-platform post history and
 * computes Hawkes process parameters plus per-event excitation spikes.
 *
 * Both the technical audit endpoint and the marketing report endpoint
 * consume this service so the MLE computation is never duplicated.
 */
@Service
public class HawkesAuditService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public static final double BETA        = 0.1;   // /min — ~6.9-min half-life
    public static final double CLUSTER_WIN = 10.0;  // minutes
    public static final int    CLUSTER_MIN = 3;

    // -------------------------------------------------------------------------
    // Public data model
    // -------------------------------------------------------------------------

    public static class AuditEntry {
        public final String         id;
        public final String         platform;
        public final String         content;
        public final java.util.Date timestamp;
        public final String         keyword;
        public final String         sentimentCategory;  // content domain (e.g. media.movie)
        public final Double         sentimentScore;     // 0-100; lower = more negative

        // populated by compute()
        public double tMin;
        public double excitationSpike;
        public int    burstSize;   // 0 = not in a burst; >0 = size of enclosing burst window

        AuditEntry(String id, String platform, String content,
                   java.util.Date timestamp, String keyword,
                   String sentimentCategory, Double sentimentScore) {
            this.id                = id;
            this.platform          = platform;
            this.content           = content;
            this.timestamp         = timestamp;
            this.keyword           = keyword;
            this.sentimentCategory = sentimentCategory;
            this.sentimentScore    = sentimentScore;
        }

        /** Derives tone from the 0-100 sentiment score. */
        public String tone() {
            if (sentimentScore == null) return "neutral";
            if (sentimentScore < 50)   return "negative";
            if (sentimentScore > 75)   return "positive";
            return "neutral";
        }
    }

    public static class AuditResult {
        public final String          author;
        public final List<AuditEntry> entries;
        public final double          mu;
        public final double          alpha;

        AuditResult(String author, List<AuditEntry> entries, double mu, double alpha) {
            this.author  = author;
            this.entries = entries;
            this.mu      = mu;
            this.alpha   = alpha;
        }

        public boolean isEmpty() { return entries.isEmpty(); }
    }

    // -------------------------------------------------------------------------
    // Core computation
    // -------------------------------------------------------------------------

    public AuditResult compute(String author) {
        List<Map<String, Object>> rows = fetchRows(author);
        if (rows.isEmpty()) return new AuditResult(author, Collections.emptyList(), 0.0, 0.0);

        List<AuditEntry> entries = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Object scoreObj = r.get("sentiment_score");
            Double score = null;
            if (scoreObj instanceof Number) score = ((Number) scoreObj).doubleValue();

            entries.add(new AuditEntry(
                String.valueOf(r.get("id")),
                (String) r.get("platform"),
                r.get("content") != null ? r.get("content").toString() : null,
                (java.util.Date) r.get("event_time"),
                r.get("keyword") != null ? r.get("keyword").toString().trim() : null,
                r.get("sentiment_category") != null ? r.get("sentiment_category").toString() : null,
                score
            ));
        }

        int n = entries.size();
        long baseMs = entries.get(0).timestamp.getTime();
        double[] tMin = new double[n];
        for (int i = 0; i < n; i++)
            tMin[i] = (entries.get(i).timestamp.getTime() - baseMs) / 60_000.0;

        double[] hp     = estimateHawkes(tMin);
        double[] spikes = computeSpikes(tMin, hp[1]);
        int[]    bursts = detectBursts(entries, tMin);

        for (int i = 0; i < n; i++) {
            entries.get(i).tMin            = tMin[i];
            entries.get(i).excitationSpike = spikes[i];
            entries.get(i).burstSize       = bursts[i];
        }

        return new AuditResult(author, entries, hp[0], hp[1]);
    }

    // -------------------------------------------------------------------------
    // Shared helpers used by both controllers
    // -------------------------------------------------------------------------

    public String excitationLevel(double spike, double alpha) {
        if (alpha <= 0 || spike <= 0) return "NONE";
        double r = spike / alpha;
        return r >= 0.7 ? "HIGH" : r >= 0.3 ? "MEDIUM" : "LOW";
    }

    /** Counts contiguous burst regions (burstSize ≥ CLUSTER_MIN) as distinct clusters. */
    public int countDistinctClusters(List<AuditEntry> entries) {
        int count = 0;
        boolean inside = false;
        for (AuditEntry e : entries) {
            if (e.burstSize >= CLUSTER_MIN && !inside) { count++; inside = true; }
            else if (e.burstSize < CLUSTER_MIN)        { inside = false; }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Internal computation
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> fetchRows(String author) {
        // sentiment_score = 0 is an invalid/unscored row; only accept 1–100.
        String sql =
            "SELECT id, text AS content, created_at  AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'x' AS platform " +
            "  FROM x_posts          WHERE author = ? AND sentiment_score BETWEEN 1 AND 100 " +
            "UNION ALL " +
            "SELECT id, text AS content, published_at AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'youtube' AS platform " +
            "  FROM youtube_comments WHERE author = ? AND sentiment_score BETWEEN 1 AND 100 " +
            "UNION ALL " +
            "SELECT id, text AS content, created_at   AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'reddit' AS platform " +
            "  FROM reddit_posts     WHERE author = ? AND sentiment_score BETWEEN 1 AND 100 " +
            "UNION ALL " +
            "SELECT id, text AS content, timestamp    AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'instagram' AS platform " +
            "  FROM instagram_posts  WHERE author = ? AND sentiment_score BETWEEN 1 AND 100 " +
            "ORDER BY event_time ASC";
        return jdbcTemplate.queryForList(sql, author, author, author, author);
    }

    double[] estimateHawkes(double[] t) {
        int n = t.length;
        if (n < 2) return new double[]{n > 0 ? 1.0 / Math.max(t[0] + 1, 1) : 0.0, 0.0};
        final double T = t[n - 1];
        MultivariateFunction negLL = params -> {
            double mu = params[0], a = params[1];
            double integral = mu * T;
            for (double ti : t) integral += (a / BETA) * (1.0 - Math.exp(-BETA * (T - ti)));
            double sumLog = 0.0;
            for (int i = 0; i < n; i++) {
                double lam = mu;
                for (int j = 0; j < i; j++) lam += a * Math.exp(-BETA * (t[i] - t[j]));
                if (lam <= 0) return Double.POSITIVE_INFINITY;
                sumLog += Math.log(lam);
            }
            return integral - sumLog;
        };
        try {
            return new BOBYQAOptimizer(5).optimize(
                new MaxEval(2000), new ObjectiveFunction(negLL), GoalType.MINIMIZE,
                new InitialGuess(new double[]{0.1, 0.05}),
                new SimpleBounds(new double[]{1e-9, 0.0}, new double[]{Double.MAX_VALUE, BETA - 1e-9})
            ).getPoint();
        } catch (Exception e) {
            return new double[]{(double) n / T, 0.0};
        }
    }

    double[] computeSpikes(double[] t, double alpha) {
        int n = t.length;
        double[] spikes = new double[n];
        for (int i = 1; i < n; i++)
            for (int j = 0; j < i; j++)
                spikes[i] += alpha * Math.exp(-BETA * (t[i] - t[j]));
        return spikes;
    }

    int[] detectBursts(List<AuditEntry> entries, double[] t) {
        int n = entries.size();
        int[] bursts = new int[n];
        for (int i = 0; i < n; i++) {
            String kw = entries.get(i).keyword;
            if (kw == null || kw.isEmpty()) continue;
            int count = 0;
            for (int j = i; j < n && t[j] - t[i] <= CLUSTER_WIN; j++) count++;
            if (count >= CLUSTER_MIN)
                for (int j = i; j < n && t[j] - t[i] <= CLUSTER_WIN; j++)
                    bursts[j] = Math.max(bursts[j], count);
        }
        return bursts;
    }
}
