package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Back-end for the Celebrity Analytics API. Active only for managed entities of
 * type {@code CELEBRITY}: it resolves the celebrity's tracked keyword set,
 * gathers observable signals (Hawkes self-excitation and sentiment via
 * {@link EntityIntelService}/{@link HawkesAuditService}; reach, engagement,
 * fan-base size and advocate strength via {@link EntityMarketingService}),
 * feeds them through the pure {@link CelebrityMetricsModel}, and assembles the
 * JSON payload of predicted brand value, reach value, fan engagement,
 * endorsement score and the four percentage key metrics.
 *
 * Signal gathering reuses the exact aggregations the rest of the marketing stack
 * already uses, so the analytics never drift from the underlying intelligence.
 */
@Service
public class CelebrityAnalyticsService {

    /** Entity type this API serves; matched case-insensitively. */
    public static final String CELEBRITY_TYPE = "celebrity";
    private static final int   ADVOCATE_TOP   = 10;

    private static final ZoneId            IST    = ZoneId.of("Asia/Kolkata");
    private static final String            TZ     = "IST";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired private JdbcTemplate            jdbc;
    @Autowired private EntityIntelService      intel;
    @Autowired private EntityMarketingService  marketing;

    /**
     * Decay rate (β) of the Hawkes kernel used to fit the advocates' stored {@code influence_rank}
     * (raw α, produced by {@link MarketingEnrichmentEngine}'s hours-based calculator). Used to turn
     * raw α into the dimensionless branching ratio n = α/β so advocacy is comparable to {@code
     * branchingRatio}. Raw α is unit-dependent and must never be compared across estimators directly.
     */
    @Value("${hawkes.beta:3.0}") private double advocacyBeta;

    /** True if the entity exists and is of type CELEBRITY. */
    public static boolean isCelebrity(EntityIntelService.EntityProfile entity) {
        return entity != null && entity.type != null
                && entity.type.trim().equalsIgnoreCase(CELEBRITY_TYPE);
    }

    /** Lists managed entities of type CELEBRITY with their tracked keyword sets. */
    public List<Map<String, Object>> listCelebrities() {
        String sql =
                "SELECT me.id, me.name, array_agg(DISTINCT ek.keyword) AS keywords " +
                "FROM managed_entities me " +
                "JOIN entity_keywords ek ON ek.entity_id = me.id " +
                "WHERE LOWER(me.type) = ? " +
                "GROUP BY me.id, me.name " +
                "ORDER BY me.name";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, CELEBRITY_TYPE);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entityId", String.valueOf(row.get("id")));
            entry.put("name",     row.get("name"));
            entry.put("keywords", sqlArrayToList(row.get("keywords")));
            out.add(entry);
        }
        return out;
    }

    /**
     * Full analytics payload for a celebrity, or a {@code message}-only map when
     * the entity is missing, not a celebrity, or has no scored history.
     */
    public Map<String, Object> analytics(String entityId) {
        EntityIntelService.EntityProfile entity = intel.lookup(entityId);
        if (entity == null) {
            return message(entityId, "No managed entity found for this id");
        }
        if (!isCelebrity(entity)) {
            Map<String, Object> body = message(entityId,
                    "Celebrity Analytics is only available for managed entities of type CELEBRITY (this entity is of type '"
                    + entity.type + "')");
            body.put("entityType", entity.type);
            return body;
        }

        HawkesAuditService.AuditResult audit = intel.computeAudit(entity);
        if (audit.isEmpty()) {
            Map<String, Object> body = message(entityId,
                    "No scored post history found for this celebrity — cannot compute analytics");
            body.put("name",            entity.name);
            body.put("trackedKeywords", entity.keywords);
            return body;
        }

        // ---- gather raw signals -------------------------------------------------
        List<HawkesAuditService.AuditEntry> entries = audit.entries;
        int    n              = entries.size();
        double branchingRatio = audit.alpha / HawkesAuditService.BETA;

        long   fanBaseSize    = marketing.audienceSize(entity.keywords);
        Map<String, Object> reach      = marketing.reachByPlatform(entity.keywords);
        Map<String, Object> engagement = marketing.engagementByPlatform(entity.keywords);
        long   reachTotal     = ((Number) reach.get("total")).longValue();
        long   engagementTotal= ((Number) engagement.get("total")).longValue();

        List<Map<String, Object>> advocates = marketing.topSpreaders(entity.keywords, ADVOCATE_TOP);
        // hawkes_alpha (stored influence_rank) is raw α fitted at β = advocacyBeta. Divide by β to get
        // the dimensionless branching ratio n = α/β ∈ [0,1), matching how branchingRatio is formed above
        // and preventing the previous clamp01 from flattening every strong advocate (raw α could reach β).
        double advocacyBranchingRatio = (advocacyBeta > 0 ? advocates.stream()
                .mapToDouble(a -> ((Number) a.getOrDefault("hawkes_alpha", 0.0)).doubleValue())
                .average().orElse(0.0) / advocacyBeta : 0.0);

        SentimentStats sentiment = sentimentStats(entries);

        double spanDays = observationSpanDays(entries);

        CelebrityMetricsModel.Signals signals = new CelebrityMetricsModel.Signals(
                n, fanBaseSize, spanDays, branchingRatio,
                reachTotal, engagementTotal,
                sentiment.positive, sentiment.negative, sentiment.neutral,
                sentiment.stdevNorm, sentiment.negativeBurstShare,
                advocacyBranchingRatio);

        CelebrityMetricsModel.Metrics m = CelebrityMetricsModel.compute(signals);

        // ---- assemble response --------------------------------------------------
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", nowIso());
        body.put("celebrity",   celebrityProfile(entity, entries, n, fanBaseSize, spanDays));

        body.put("headlineMetrics", headlineMetrics(m));
        body.put("keyMetricsPercent", keyMetricsPercent(m));

        body.put("reachBreakdown",      reach);
        body.put("engagementBreakdown", engagement);
        body.put("sentiment",           sentimentBlock(sentiment, m));
        body.put("topAdvocates",        advocates);

        body.put("scoreDrivers",  scoreDrivers(m));
        body.put("model",         modelExplanation(signals, m));
        body.put("interpretation",interpretation(entity, m));
        return body;
    }

    // -------------------------------------------------------------------------
    // Response sections
    // -------------------------------------------------------------------------

    private Map<String, Object> celebrityProfile(
            EntityIntelService.EntityProfile entity,
            List<HawkesAuditService.AuditEntry> entries,
            int totalPosts, long fanBaseSize, double spanDays) {

        java.util.Set<String> platforms = new java.util.LinkedHashSet<>();
        for (HawkesAuditService.AuditEntry e : entries) platforms.add(platformLabel(e.platform));

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("firstSeen",          toIso(entries.get(0).timestamp));
        window.put("lastSeen",           toIso(entries.get(entries.size() - 1).timestamp));
        window.put("observationSpanDays",round1(spanDays));
        window.put("averagePostsPerDay", spanDays > 0 ? round1(totalPosts / spanDays) : (double) totalPosts);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityId",         entity.entityId);
        p.put("name",             entity.name);
        p.put("type",             entity.type);
        p.put("trackedKeywords",  entity.keywords);
        p.put("activePlatforms",  new ArrayList<>(platforms));
        p.put("totalPosts",       totalPosts);
        p.put("fanBaseSize",      fanBaseSize);
        p.put("observationWindow",window);
        return p;
    }

    private Map<String, Object> headlineMetrics(CelebrityMetricsModel.Metrics m) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("predictedBrandValueUsd", Math.round(m.predictedBrandValueUsd()));
        h.put("predictedBrandValueDisplay", usd(m.predictedBrandValueUsd()));
        h.put("socialMediaReachValue",  m.socialMediaReachValue());
        h.put("fanEngagementValue",     m.fanEngagementValue());
        h.put("endorsementScore",       round1(m.endorsementScore()));
        return h;
    }

    private Map<String, Object> keyMetricsPercent(CelebrityMetricsModel.Metrics m) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("socialMediaInfluence", pct(m.socialMediaInfluencePct()));
        k.put("brandPower",           pct(m.brandPowerPct()));
        k.put("fanLoyalty",           pct(m.fanLoyaltyPct()));
        k.put("controversyRisk",      pct(m.controversyRiskPct()));
        return k;
    }

    /** A percentage value with a coarse human-readable band. */
    private Map<String, Object> pct(double value) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("score", round1(value));
        e.put("band",  band(value));
        return e;
    }

    private Map<String, Object> sentimentBlock(SentimentStats s, CelebrityMetricsModel.Metrics m) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("positive",      s.positive);
        b.put("negative",      s.negative);
        b.put("neutral",       s.neutral);
        b.put("netSentiment",  round2(m.netSentiment()));
        b.put("label",         sentimentLabel(m.netSentiment()));
        b.put("volatility",    round3(s.stdevNorm));
        b.put("negativeBurstShare", round3(s.negativeBurstShare));
        return b;
    }

    private Map<String, Object> scoreDrivers(CelebrityMetricsModel.Metrics m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reachScore",            round3(m.reachScore()));
        d.put("audienceScore",         round3(m.audienceScore()));
        d.put("viralityScore",         round3(m.viralityScore()));
        d.put("engagementRateScore",   round3(m.engagementRateScore()));
        d.put("engagementPerFanScore", round3(m.engagementPerFanScore()));
        d.put("repeatPostingScore",    round3(m.repeatPostingScore()));
        d.put("positivityScore",       round3(m.positivityScore()));
        d.put("sentimentVolatilityScore", round3(m.sentimentVolatilityScore()));
        d.put("negativeBurstScore",    round3(m.negativeBurstScore()));
        d.put("advocacyScore",         round3(m.advocacyScore()));
        return d;
    }

    private Map<String, Object> modelExplanation(
            CelebrityMetricsModel.Signals s, CelebrityMetricsModel.Metrics m) {

        Map<String, Object> constants = new LinkedHashMap<>();
        constants.put("reachLogScale",      CelebrityMetricsModel.REACH_LOG_SCALE);
        constants.put("audienceLogScale",   CelebrityMetricsModel.AUDIENCE_LOG_SCALE);
        constants.put("engagementLogScale", CelebrityMetricsModel.ENGAGEMENT_LOG_SCALE);
        constants.put("usdPer1000Reach",    CelebrityMetricsModel.USD_PER_1000_REACH);
        constants.put("maxControversyDiscount", CelebrityMetricsModel.MAX_CONTROVERSY_DISCOUNT);

        Map<String, Object> formulas = new LinkedHashMap<>();
        formulas.put("socialMediaInfluence", "100 * (0.50*virality + 0.30*audience + 0.20*reach)");
        formulas.put("brandPower",           "100 * (0.30*reach + 0.20*audience + 0.20*engagementRate + 0.15*virality + 0.15*positivity)");
        formulas.put("fanLoyalty",           "100 * (0.35*repeatPosting + 0.30*positivity + 0.20*engagementPerFan + 0.15*advocacy)");
        formulas.put("controversyRisk",      "100 * (0.50*negativeShare + 0.30*sentimentVolatility + 0.20*negativeBurstShare)");
        formulas.put("endorsementScore",     "100 * (0.35*reach + 0.25*positivity + 0.20*fanLoyalty + 0.20*influence) * (1 - 0.7*controversyRisk)");
        formulas.put("predictedBrandValueUsd","(annualisedReach/1000) * usdPer1000Reach * qualityMultiplier * (1 - controversyDiscount)");

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("branchingRatio",         round3(s.branchingRatio()));
        derived.put("annualisedReach",        Math.round(m.annualisedReach()));
        derived.put("brandQualityMultiplier", round3(m.brandQualityMultiplier()));

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("description", "Bounded, monotonic scoring model. Raw magnitudes are squashed to [0,1] "
                + "with saturating transforms, then combined with fixed-weight priors. Percentages are 0–100.");
        model.put("constants", constants);
        model.put("formulas",  formulas);
        model.put("derived",   derived);
        return model;
    }

    private List<String> interpretation(EntityIntelService.EntityProfile entity, CelebrityMetricsModel.Metrics m) {
        List<String> out = new ArrayList<>();
        String name = entity.name != null ? entity.name : "This celebrity";
        out.add(String.format(
                "%s has a predicted brand value of %s, driven by a %s social-media reach value of %,d and %,d fan interactions.",
                name, usd(m.predictedBrandValueUsd()), band(m.brandPowerPct()).toLowerCase(),
                m.socialMediaReachValue(), m.fanEngagementValue()));
        out.add(String.format(
                "Brand Power %.0f%% and Social Media Influence %.0f%% — %s",
                m.brandPowerPct(), m.socialMediaInfluencePct(),
                m.socialMediaInfluencePct() >= 60
                        ? "the conversation is far-reaching and self-sustaining."
                        : "reach is real but does not yet self-amplify; pair with paid promotion."));
        out.add(String.format(
                "Fan Loyalty %.0f%% with an endorsement score of %.0f/100.",
                m.fanLoyaltyPct(), m.endorsementScore()));
        if (m.controversyRiskPct() >= 40) {
            out.add(String.format(
                    "⚠ Controversy Risk is elevated at %.0f%% — vet recent sentiment before any brand association.",
                    m.controversyRiskPct()));
        } else {
            out.add(String.format(
                    "Controversy Risk is low at %.0f%% — a safe association for brand partners.",
                    m.controversyRiskPct()));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Sentiment aggregation over the audited post stream
    // -------------------------------------------------------------------------

    private static final class SentimentStats {
        long   positive, negative, neutral;
        double stdevNorm;           // stdev of (sentimentScore/100), in [0,1]
        double negativeBurstShare;  // fraction of in-burst posts that are negative
    }

    private SentimentStats sentimentStats(List<HawkesAuditService.AuditEntry> entries) {
        SentimentStats s = new SentimentStats();

        // tone counts + normalised score moments
        double sum = 0.0, sumSq = 0.0;
        long scored = 0;
        long burstPosts = 0, burstNegative = 0;
        for (HawkesAuditService.AuditEntry e : entries) {
            switch (e.tone()) {
                case "positive" -> s.positive++;
                case "negative" -> s.negative++;
                default          -> s.neutral++;
            }
            if (e.sentimentScore != null) {
                double v = e.sentimentScore / 100.0;
                sum   += v;
                sumSq += v * v;
                scored++;
            }
            if (e.burstSize >= HawkesAuditService.CLUSTER_MIN) {
                burstPosts++;
                if ("negative".equals(e.tone())) burstNegative++;
            }
        }
        if (scored > 0) {
            double mean = sum / scored;
            double var  = Math.max(0.0, sumSq / scored - mean * mean);
            s.stdevNorm = Math.sqrt(var);
        }
        s.negativeBurstShare = burstPosts > 0 ? (double) burstNegative / burstPosts : 0.0;
        return s;
    }

    private double observationSpanDays(List<HawkesAuditService.AuditEntry> entries) {
        long ms = entries.get(entries.size() - 1).timestamp.getTime()
                - entries.get(0).timestamp.getTime();
        return ms / 86_400_000.0;
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> message(String entityId, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entityId", entityId);
        body.put("message",  message);
        return body;
    }

    private static String band(double pct) {
        if (pct >= 80) return "Very High";
        if (pct >= 60) return "High";
        if (pct >= 40) return "Moderate";
        if (pct >= 20) return "Low";
        return "Very Low";
    }

    private static String sentimentLabel(double net) {
        if (net >= 0.3)  return "Predominantly Positive";
        if (net >= 0.05) return "Leaning Positive";
        if (net > -0.05) return "Mixed / Neutral";
        if (net > -0.3)  return "Leaning Negative";
        return "Predominantly Negative";
    }

    private static String usd(double value) {
        double v = Math.round(value);
        if (v >= 1_000_000_000) return String.format("$%.1fB", v / 1_000_000_000.0);
        if (v >= 1_000_000)     return String.format("$%.1fM", v / 1_000_000.0);
        if (v >= 1_000)         return String.format("$%.1fK", v / 1_000.0);
        return String.format("$%,d", (long) v);
    }

    private String platformLabel(String p) {
        if (p == null) return "Unknown";
        return switch (p) {
            case "x"         -> "X (Twitter)";
            case "youtube"   -> "YouTube";
            case "reddit"    -> "Reddit";
            case "instagram" -> "Instagram";
            default          -> p;
        };
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private String toIso(java.util.Date d) {
        return d.toInstant().atZone(IST).toLocalDateTime().format(TS_FMT) + " " + TZ;
    }

    private String nowIso() {
        return java.time.Instant.now().atZone(IST).toLocalDateTime().format(TS_FMT) + " " + TZ;
    }

    @SuppressWarnings("unchecked")
    private static List<String> sqlArrayToList(Object sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            if (sqlArray instanceof java.sql.Array a) {
                Object inner = a.getArray();
                if (inner instanceof Object[] arr) {
                    List<String> list = new ArrayList<>(arr.length);
                    for (Object o : arr) if (o != null) list.add(o.toString());
                    return list;
                }
            }
            if (sqlArray instanceof List<?> l) return (List<String>) l;
        } catch (Exception ignored) { }
        return List.of();
    }
}
