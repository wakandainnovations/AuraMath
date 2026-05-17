package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final Gson gson = new Gson();

    // StanfordCoreNLP initialisation is expensive; hold one instance per application lifecycle.
    private final AspectSentimentAnalyzer aspectAnalyzer = new AspectSentimentAnalyzer();

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
     *   - Stanford CoreNLP extracts every noun from each post's text.
     *   - The post's pre-stored sentiment_score (x_posts) or a numeric conversion of
     *     sentiment_category (youtube / reddit / instagram) is assigned to every noun.
     *   - Scores are averaged across all posts that mention the same noun.
     *   - Aspects mentioned in fewer than MIN_ASPECT_MENTIONS posts are dropped as noise.
     *
     * The byPlatform breakdown lets the team see platform-specific perception gaps —
     * e.g. Reddit criticises pacing while Instagram praises the visuals — and pick
     * the right trailer cut for each channel.
     *
     * sentiment_score sourcing:
     *   x_posts        → real sentiment_score column (continuous [-1, 1])
     *   youtube_comments, reddit_posts, instagram_posts → sentiment_category converted:
     *                    "positive" → +0.6 | "negative" → -0.6 | anything else → 0.0
     */
    @GetMapping("/aspect-drivers/{keyword}")
    public Map<String, Object> getAspectDrivers(@PathVariable String keyword) {
        String kw = "%" + keyword + "%";
        int limit = MAX_POSTS_PER_PLATFORM;

        // Per-platform accumulators: aspect noun → list of sentiment scores across posts
        Map<String, List<Double>> xAspects      = new HashMap<>();
        Map<String, List<Double>> ytAspects     = new HashMap<>();
        Map<String, List<Double>> redditAspects = new HashMap<>();
        Map<String, List<Double>> igAspects     = new HashMap<>();

        // ── x_posts: real sentiment_score ────────────────────────────────────────
        List<Map<String, Object>> xRows = jdbcTemplate.queryForList(
                "SELECT text, sentiment_score FROM x_posts " +
                "WHERE keyword ILIKE ? AND text IS NOT NULL " +
                "ORDER BY created_at DESC LIMIT " + limit, kw);
        for (Map<String, Object> row : xRows) {
            String text = (String) row.get("text");
            if (text == null || text.isBlank()) continue;
            double score = toDouble(row.get("sentiment_score"));
            collectAspects(text, score, xAspects);
        }

        // ── youtube_comments: sentiment_category → numeric ───────────────────────
        List<Map<String, Object>> ytRows = jdbcTemplate.queryForList(
                "SELECT text, sentiment_category FROM youtube_comments " +
                "WHERE keyword ILIKE ? AND text IS NOT NULL " +
                "ORDER BY published_at DESC LIMIT " + limit, kw);
        for (Map<String, Object> row : ytRows) {
            String text = (String) row.get("text");
            if (text == null || text.isBlank()) continue;
            collectAspects(text, categoryToScore((String) row.get("sentiment_category")), ytAspects);
        }

        // ── reddit_posts: title + body, sentiment_category → numeric ─────────────
        // Reddit titles carry as much signal as the body; concatenate both.
        List<Map<String, Object>> redditRows = jdbcTemplate.queryForList(
                "SELECT title, text, sentiment_category FROM reddit_posts " +
                "WHERE keyword ILIKE ? " +
                "ORDER BY created_at DESC LIMIT " + limit, kw);
        for (Map<String, Object> row : redditRows) {
            String combined = combine((String) row.get("title"), (String) row.get("text"));
            if (combined.isBlank()) continue;
            collectAspects(combined, categoryToScore((String) row.get("sentiment_category")), redditAspects);
        }

        // ── instagram_posts: caption, sentiment_category → numeric ────────────────
        List<Map<String, Object>> igRows = jdbcTemplate.queryForList(
                "SELECT text, sentiment_category FROM instagram_posts " +
                "WHERE keyword ILIKE ? AND text IS NOT NULL " +
                "ORDER BY timestamp DESC LIMIT " + limit, kw);
        for (Map<String, Object> row : igRows) {
            String text = (String) row.get("text");
            if (text == null || text.isBlank()) continue;
            collectAspects(text, categoryToScore((String) row.get("sentiment_category")), igAspects);
        }

        // ── Merge all platforms for the overall view ──────────────────────────────
        Map<String, List<Double>> overallAspects = new HashMap<>();
        mergeInto(overallAspects, xAspects);
        mergeInto(overallAspects, ytAspects);
        mergeInto(overallAspects, redditAspects);
        mergeInto(overallAspects, igAspects);

        // ── Assemble response ─────────────────────────────────────────────────────
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("keyword", keyword);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("x",         xRows.size());
        counts.put("youtube",   ytRows.size());
        counts.put("reddit",    redditRows.size());
        counts.put("instagram", igRows.size());
        counts.put("total",     xRows.size() + ytRows.size() + redditRows.size() + igRows.size());
        response.put("totalPostsAnalyzed", counts);

        Map<String, Object> overall = buildStrengthsWeaknesses(overallAspects);
        response.put("strengths",  overall.get("strengths"));
        response.put("weaknesses", overall.get("weaknesses"));

        // Per-platform breakdown — omit platforms with no matching posts.
        Map<String, Object> byPlatform = new LinkedHashMap<>();
        if (!xRows.isEmpty())      byPlatform.put("x",         buildStrengthsWeaknesses(xAspects));
        if (!ytRows.isEmpty())     byPlatform.put("youtube",   buildStrengthsWeaknesses(ytAspects));
        if (!redditRows.isEmpty()) byPlatform.put("reddit",    buildStrengthsWeaknesses(redditAspects));
        if (!igRows.isEmpty())     byPlatform.put("instagram", buildStrengthsWeaknesses(igAspects));
        response.put("byPlatform", byPlatform);

        return response;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Runs the aspect analyzer on one post's text and accumulates the aspect→score
     * pairs into the target map, appending to the existing list for each aspect.
     */
    private void collectAspects(String text, double score, Map<String, List<Double>> target) {
        Map<String, Double> aspects = aspectAnalyzer.analyze(text, score);
        for (Map.Entry<String, Double> e : aspects.entrySet()) {
            target.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }
    }

    /**
     * Aggregates an aspect map into Strengths and Weaknesses lists.
     * Aspects mentioned fewer than MIN_ASPECT_MENTIONS times are dropped.
     * Each list is sorted by absolute sentiment (strongest first).
     */
    private Map<String, Object> buildStrengthsWeaknesses(Map<String, List<Double>> aspectMap) {
        List<Map<String, Object>> strengths  = new ArrayList<>();
        List<Map<String, Object>> weaknesses = new ArrayList<>();

        for (Map.Entry<String, List<Double>> e : aspectMap.entrySet()) {
            List<Double> scores = e.getValue();
            int n = scores.size();
            if (n < MIN_ASPECT_MENTIONS) continue;

            double avg = scores.stream().mapToDouble(d -> d).average().orElse(0.0);
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

    /** Merges all entries from source into target, appending score lists. */
    private void mergeInto(Map<String, List<Double>> target, Map<String, List<Double>> source) {
        for (Map.Entry<String, List<Double>> e : source.entrySet()) {
            target.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
    }

    /**
     * Converts a stored sentiment_category string to a numeric score.
     * Used for platforms that don't store a continuous sentiment_score.
     */
    private double categoryToScore(String category) {
        if (category == null) return 0.0;
        return switch (category.toLowerCase()) {
            case "positive" -> 0.6;
            case "negative" -> -0.6;
            default         -> 0.0;
        };
    }

    private String combine(String a, String b) {
        return ((a != null ? a : "") + " " + (b != null ? b : "")).trim();
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
