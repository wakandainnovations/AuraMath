package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/marketing")
public class ViralSeedController {

    // Minimum number of posts that must mention an aspect before it appears in the response.
    // Filters out nouns that appear too rarely to be statistically meaningful.
    private static final int MIN_ASPECT_MENTIONS = 3;

    // Per-platform post cap fed into the NLP pipeline. Keeps response times bounded
    // on large datasets without skewing results (most-recent posts are most relevant).
    private static final int MAX_POSTS_PER_PLATFORM = 1000;

    // Top-N kept in the response after composite-score ranking.
    private static final int RESPONSE_LIMIT = 50;

    // Wider candidate pool fed into composite ranking — pre-filtering by α alone would hide
    // strong moi/reach candidates whose α happens to fall outside the top RESPONSE_LIMIT.
    private static final int CANDIDATE_POOL = 200;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SeedScoreCalibrator seedScoreCalibrator;

    @Autowired
    private EntityIntelService entityIntel;

    private final Gson gson = new Gson();

    // ─── /viral-seeds ────────────────────────────────────────────────────────────

    /**
     * Returns the top 50 viral seed candidates for a given keyword, ranked by Hawkes infectivity (α).
     *
     * Keyword matching is two-tier:
     *   1. top_genres (aspect labels like "action", "thriller") — handles genre-level keywords.
     *   2. keyword column in all four source tables — handles specific titles like "Avengers".
     *
     * primary_platform is determined by comparing per-platform reach signals pulled live from the DB:
     *   x_posts        → real views_count
     *   instagram_posts → like_count  (schema has no impressions column)
     *   reddit_posts   → score (net upvotes; schema has no impressions column)
     *   youtube_comments → comment count (schema has no per-comment engagement columns)
     */
    @GetMapping("/viral-seeds")
    public List<Map<String, Object>> getViralSeeds(@RequestParam String keyword) {
        String kw = "%" + keyword + "%";

        String profileSql =
                "SELECT mtp.global_user_id, mtp.influence_rank, mtp.moi_score, " +
                "       mtp.tribe_label, mtp.top_genres, mtp.platform_handles, mtp.peak_activity_times " +
                "FROM marketing_target_profiles mtp " +
                "WHERE mtp.top_genres::text ILIKE ? " +
                "   OR mtp.global_user_id IN (" +
                "       SELECT DISTINCT author FROM x_posts            WHERE keyword ILIKE ? " +
                "       UNION " +
                "       SELECT DISTINCT author FROM youtube_comments    WHERE keyword ILIKE ? " +
                "       UNION " +
                "       SELECT DISTINCT author FROM reddit_posts         WHERE keyword ILIKE ? " +
                "       UNION " +
                "       SELECT DISTINCT author FROM instagram_posts      WHERE keyword ILIKE ?" +
                "   ) " +
                "ORDER BY mtp.influence_rank DESC " +
                "LIMIT " + CANDIDATE_POOL;

        List<Map<String, Object>> profiles = jdbcTemplate.queryForList(profileSql, kw, kw, kw, kw, kw);
        if (profiles.isEmpty()) return Collections.emptyList();

        List<String> authorIds = profiles.stream()
                .map(p -> (String) p.get("global_user_id"))
                .collect(Collectors.toList());

        String inClause  = authorIds.stream().map(a -> "?").collect(Collectors.joining(", "));
        Object[] idArray = authorIds.toArray();

        Map<String, Long> xViews = aggregateByAuthor(
                "SELECT author, SUM(views_count) AS total FROM x_posts " +
                "WHERE author IN (" + inClause + ") GROUP BY author", idArray);

        Map<String, Long> igLikes = aggregateByAuthor(
                "SELECT author, SUM(like_count) AS total FROM instagram_posts " +
                "WHERE author IN (" + inClause + ") GROUP BY author", idArray);

        Map<String, Long> redditScore = aggregateByAuthor(
                "SELECT author, SUM(score) AS total FROM reddit_posts " +
                "WHERE author IN (" + inClause + ") GROUP BY author", idArray);

        Map<String, Long> ytCount = aggregateByAuthor(
                "SELECT author, COUNT(*) AS total FROM youtube_comments " +
                "WHERE author IN (" + inClause + ") GROUP BY author", idArray);

        double wMoi   = seedScoreCalibrator.getMoiWeight();
        double wReach = seedScoreCalibrator.getReachWeight();

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> profile : profiles) {
            String authorId = (String) profile.get("global_user_id");

            long xV  = xViews.getOrDefault(authorId, 0L);
            long igL = igLikes.getOrDefault(authorId, 0L);
            long rdS = redditScore.getOrDefault(authorId, 0L);
            long ytC = ytCount.getOrDefault(authorId, 0L);
            long totalReach = xV + igL + rdS + ytC;

            // Hawkes α saturates at the optimizer's upper bound (β − ε ≈ 1.0) for
            // authors with clustered low-engagement timestamps, producing α = 1.0
            // even when the user has no measurable reach. Drop these — a viral
            // seed with zero reach is not a viral seed.
            if (totalReach == 0) continue;

            double alpha = toDouble(profile.get("influence_rank"));
            double moi   = toDouble(profile.get("moi_score"));
            double score = seedScoreCalibrator.seedScore(alpha, moi, totalReach);

            String primaryPlatform = resolvePrimaryPlatform(xV, igL, rdS, ytC);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("author",          authorId);
            entry.put("seedScore",       round4(score));
            entry.put("hawkesAlpha",     profile.get("influence_rank"));
            entry.put("moiScore",        profile.get("moi_score"));
            entry.put("tribe",           profile.get("tribe_label"));
            entry.put("primaryPlatform", primaryPlatform);
            entry.put("outreachHandle",  parseOutreachHandle(profile));

            Map<String, Object> signals = new LinkedHashMap<>();
            signals.put("x_views_count",        xV);
            signals.put("instagram_like_count",  igL);
            signals.put("reddit_score",          rdS);
            signals.put("youtube_comment_count", ytC);
            entry.put("reachSignals", signals);

            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("alphaTerm", round4(alpha));
            breakdown.put("moiTerm",   round4(wMoi * moi));
            breakdown.put("reachTerm", round4(wReach * Math.log1p(totalReach)));
            breakdown.put("weights",   Map.of("wMoi", wMoi, "wReach", wReach));
            entry.put("scoreBreakdown", breakdown);

            scored.add(entry);
        }

        scored.sort((a, b) -> Double.compare(
                ((Number) b.get("seedScore")).doubleValue(),
                ((Number) a.get("seedScore")).doubleValue()));

        List<Map<String, Object>> result = scored.size() > RESPONSE_LIMIT
                ? new ArrayList<>(scored.subList(0, RESPONSE_LIMIT))
                : scored;

        int rank = 1;
        for (Map<String, Object> entry : result) {
            LinkedHashMap<String, Object> ranked = new LinkedHashMap<>();
            ranked.put("rank", rank++);
            ranked.putAll(entry);
            entry.clear();
            entry.putAll(ranked);
        }

        return result;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ─── /aspect-drivers/{keyword} ───────────────────────────────────────────────

    /**
     * Aggregates aspect-level sentiment across all four platforms for posts matching {keyword}
     * and splits the results into Strengths (avg sentiment > 0) and Weaknesses (avg < 0).
     *
     * How it works:
     *   - The expensive part — running Stanford CoreNLP over every post to extract aspect
     *     nouns — is done offline by {@link AspectDriversPrecomputer}, which rebuilds the
     *     aspect_drivers_agg / aspect_drivers_post_counts tables once every 24 hours.
     *   - This endpoint simply re-aggregates those pre-summed rows for keywords matching the
     *     requested substring (preserving the original ILIKE semantics), so it returns in
     *     milliseconds instead of running thousands of NLP pipelines on the request thread.
     *   - Each aspect's averageSentiment is sentiment_sum / mention_count across all matched
     *     rows; aspects mentioned in fewer than MIN_ASPECT_MENTIONS posts are dropped as noise.
     *
     * The byPlatform breakdown lets the team see platform-specific perception gaps —
     * e.g. Reddit criticises pacing while Instagram praises the visuals — and pick
     * the right trailer cut for each channel.
     *
     * sentiment_score sourcing (applied during precompute):
     *   x_posts        → real sentiment_score column (continuous [-1, 1])
     *   youtube_comments, reddit_posts, instagram_posts → sentiment_category converted:
     *                    "positive" → +0.6 | "negative" → -0.6 | anything else → 0.0
     */
    @GetMapping("/aspect-drivers/{keyword}")
    public Map<String, Object> getAspectDrivers(@PathVariable String keyword) {
        // Substring match — one user-supplied keyword fans out to every precomputed
        // keyword whose text contains it.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("keyword", keyword);
        response.putAll(aspectDrivers("keyword ILIKE ?", new Object[]{ "%" + keyword + "%" }));
        return response;
    }

    /**
     * Entity-scoped variant of {@code /aspect-drivers}. Resolves the entity's tracked
     * keyword set (managed_entities → entity_keywords) and aggregates aspect drivers
     * across every one of them, so callers can ask "how is entity 29 perceived?"
     * without first having to know which keywords it tracks.
     *
     * Unlike the keyword path variant — which substring-matches a single term — this
     * matches the precomputed rows against the entity's exact keywords (case-insensitively),
     * mirroring how {@link EntityIntelService} scopes the F11 entity report.
     *
     * Example: GET /api/marketing/aspect-drivers?entityId=29
     * Returns 404 if no such entity exists; an existing entity with no matching
     * precomputed posts returns zero counts and empty strengths/weaknesses.
     */
    @GetMapping("/aspect-drivers")
    public ResponseEntity<Map<String, Object>> getAspectDriversForEntity(@RequestParam("entityId") String entityId) {
        EntityIntelService.EntityProfile entity = entityIntel.lookup(entityId);
        if (entity == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("entityId", entityId);
            notFound.put("message", "No entity found for this id");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entityId",        entityId);
        response.put("name",            entity.name);
        response.put("type",            entity.type);
        response.put("trackedKeywords", entity.keywords);

        if (entity.keywords == null || entity.keywords.isEmpty()) {
            // Entity exists but tracks no keywords — return the zero-valued shape rather than
            // building an invalid `IN ()` predicate.
            response.putAll(aspectDrivers("FALSE", new Object[]{}));
            return ResponseEntity.ok(response);
        }

        String in = String.join(", ", Collections.nCopies(entity.keywords.size(), "?"));
        Object[] args = entity.keywords.stream()
                .map(k -> k == null ? null : k.toLowerCase())
                .toArray();
        response.putAll(aspectDrivers("LOWER(keyword) IN (" + in + ")", args));
        return ResponseEntity.ok(response);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Core aspect-drivers aggregation shared by the keyword and entity variants.
     *
     * {@code keywordPredicate} is a SQL boolean over the precomputed tables' {@code keyword}
     * column (e.g. {@code "keyword ILIKE ?"} or {@code "LOWER(keyword) IN (?, ?)"}); the same
     * predicate and {@code args} are applied to both precomputed tables. Returns the
     * {@code totalPostsAnalyzed} counts plus the overall and per-platform strengths/weaknesses;
     * callers prepend their own identity fields (keyword, or entity id/name/keywords).
     */
    private Map<String, Object> aspectDrivers(String keywordPredicate, Object[] args) {
        // Post counts per platform — summed across every precomputed keyword the predicate matches.
        Map<String, Integer> postCounts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT platform, SUM(post_count) AS c FROM aspect_drivers_post_counts " +
                "WHERE " + keywordPredicate + " GROUP BY platform",
                rs -> { postCounts.put(rs.getString("platform"), rs.getInt("c")); }, args);

        // Aspect aggregates per platform: aspect → [sentiment_sum, mention_count].
        // SUM() merges the per-keyword rows for every keyword the predicate matches.
        Map<String, Map<String, double[]>> byPlatformAgg = new HashMap<>();
        jdbcTemplate.query(
                "SELECT platform, aspect, SUM(sentiment_sum) AS s, SUM(mention_count) AS n " +
                "FROM aspect_drivers_agg WHERE " + keywordPredicate + " GROUP BY platform, aspect",
                rs -> {
                    double[] acc = byPlatformAgg
                            .computeIfAbsent(rs.getString("platform"), k -> new HashMap<>())
                            .computeIfAbsent(rs.getString("aspect"), k -> new double[2]);
                    acc[0] += rs.getDouble("s");
                    acc[1] += rs.getInt("n");
                }, args);

        // Overall view: merge every platform's aspect aggregates by summing sums and counts.
        Map<String, double[]> overallAspects = new HashMap<>();
        for (Map<String, double[]> platformAspects : byPlatformAgg.values()) {
            for (Map.Entry<String, double[]> e : platformAspects.entrySet()) {
                double[] acc = overallAspects.computeIfAbsent(e.getKey(), k -> new double[2]);
                acc[0] += e.getValue()[0];
                acc[1] += e.getValue()[1];
            }
        }

        // ── Assemble response ─────────────────────────────────────────────────────
        Map<String, Object> response = new LinkedHashMap<>();

        int xCount  = postCounts.getOrDefault("x", 0);
        int ytCount = postCounts.getOrDefault("youtube", 0);
        int rdCount = postCounts.getOrDefault("reddit", 0);
        int igCount = postCounts.getOrDefault("instagram", 0);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("x",         xCount);
        counts.put("youtube",   ytCount);
        counts.put("reddit",    rdCount);
        counts.put("instagram", igCount);
        counts.put("total",     xCount + ytCount + rdCount + igCount);
        response.put("totalPostsAnalyzed", counts);

        Map<String, Object> overall = buildStrengthsWeaknesses(overallAspects);
        response.put("strengths",  overall.get("strengths"));
        response.put("weaknesses", overall.get("weaknesses"));

        // Per-platform breakdown — omit platforms with no matching posts.
        Map<String, Object> byPlatform = new LinkedHashMap<>();
        if (xCount  > 0) byPlatform.put("x",         buildStrengthsWeaknesses(byPlatformAgg.getOrDefault("x",         Map.of())));
        if (ytCount > 0) byPlatform.put("youtube",   buildStrengthsWeaknesses(byPlatformAgg.getOrDefault("youtube",   Map.of())));
        if (rdCount > 0) byPlatform.put("reddit",    buildStrengthsWeaknesses(byPlatformAgg.getOrDefault("reddit",    Map.of())));
        if (igCount > 0) byPlatform.put("instagram", buildStrengthsWeaknesses(byPlatformAgg.getOrDefault("instagram", Map.of())));
        response.put("byPlatform", byPlatform);

        return response;
    }

    /**
     * Aggregates a precomputed aspect map into Strengths and Weaknesses lists.
     * Each entry maps an aspect to [sentiment_sum, mention_count]; averageSentiment is
     * sentiment_sum / mention_count. Aspects mentioned fewer than MIN_ASPECT_MENTIONS times
     * are dropped. Each list is sorted by absolute sentiment (strongest first).
     */
    private Map<String, Object> buildStrengthsWeaknesses(Map<String, double[]> aspectMap) {
        List<Map<String, Object>> strengths  = new ArrayList<>();
        List<Map<String, Object>> weaknesses = new ArrayList<>();

        for (Map.Entry<String, double[]> e : aspectMap.entrySet()) {
            double sum = e.getValue()[0];
            int n = (int) e.getValue()[1];
            if (n < MIN_ASPECT_MENTIONS) continue;

            double avg = sum / n;
            if (avg == 0.0) continue;

            // Shrinkage toward 0 so a 3-post outlier can't outrank a high-volume consensus.
            double impact = avg * n / (n + MIN_ASPECT_MENTIONS);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("aspect",           e.getKey());
            item.put("averageSentiment", round3(avg));
            item.put("postsMentioning",  n);
            item.put("impactScore",      round3(impact));

            if (avg > 0) strengths.add(item);
            else         weaknesses.add(item);
        }

        // Strengths: highest impactScore first. Weaknesses: most negative impactScore first.
        strengths.sort((a, b)  -> Double.compare((double) b.get("impactScore"), (double) a.get("impactScore")));
        weaknesses.sort((a, b) -> Double.compare((double) a.get("impactScore"), (double) b.get("impactScore")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strengths",  strengths);
        result.put("weaknesses", weaknesses);
        return result;
    }

    private double toDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private double round3(double value) {
        return (double) Math.round(value * 1000.0) / 1000.0;
    }

    // ─── Shared helpers for /viral-seeds ─────────────────────────────────────────

    private Map<String, Long> aggregateByAuthor(String sql, Object[] params) {
        return jdbcTemplate.query(sql, rs -> {
            Map<String, Long> map = new HashMap<>();
            while (rs.next()) {
                map.put(rs.getString("author"), rs.getLong("total"));
            }
            return map;
        }, params);
    }

    private String resolvePrimaryPlatform(long xViews, long igLikes, long redditScore, long ytCount) {
        if (xViews == 0 && igLikes == 0 && redditScore == 0 && ytCount == 0) return "unknown";
        if (xViews >= igLikes && xViews >= redditScore && xViews >= ytCount) return "x";
        if (igLikes >= redditScore && igLikes >= ytCount)                     return "instagram";
        if (redditScore >= ytCount)                                            return "reddit";
        return "youtube";
    }

    private Map<String, String> parseOutreachHandle(Map<String, Object> profile) {
        String json = JsonbUtil.asJsonString(profile.get("platform_handles"));
        if (json == null || json.isBlank()) return null;
        Type rootType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> root = gson.fromJson(json, rootType);
        if (root == null) return null;

        String primary = root.get("primary_platform") instanceof String s ? s : null;
        Object byPlatformObj = root.get("by_platform");
        if (!(byPlatformObj instanceof Map<?, ?> byPlatform) || byPlatform.isEmpty()) return null;

        Object entryObj = primary != null ? byPlatform.get(primary) : byPlatform.values().iterator().next();
        if (!(entryObj instanceof Map<?, ?> entry)) return null;

        Map<String, String> out = new LinkedHashMap<>();
        if (primary != null) out.put("platform", primary);
        Object url = entry.get("profile_url");
        if (url != null) out.put("profile_url", url.toString());
        Object samplePost = entry.get("sample_post_url");
        if (samplePost != null) out.put("permalink", samplePost.toString());
        return out;
    }
}
