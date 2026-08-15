package com.lit.fire.flame;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared back-end for keyword-scoped marketing endpoints (politics, celebrity).
 *
 * Genre routes use {@code marketing_target_profiles.top_movie_genres} because
 * genre scores are precomputed at enrichment time. Parties and celebrities have no
 * equivalent column, so we identify their audience by matching the post's
 * {@code keyword} field against the entity's {@code entity_keywords} rows
 * (case-insensitive), aggregating engagement per author, and joining back to
 * {@code marketing_target_profiles}.
 *
 * Reach metric per platform:
 *   X         -> views_count
 *   YouTube   -> likes_count
 *   Reddit    -> num_comments
 *   Instagram -> like_count
 */
@Service
public class EntityMarketingService {

    @Autowired private JdbcTemplate jdbc;
    private final Gson gson = new Gson();

    // Default reach/post_count for a platform with no channel_reach_agg row (keyword never
    // seen, or the precomputer hasn't run yet) — matches the old live-scan's implicit zero.
    private static final long[] ZERO_REACH = {0L, 0L};

    /** Sigmoid; safe against overflow on large negative inputs. */
    private static double sigmoid(double x) {
        if (x >= 0) {
            double e = Math.exp(-x);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(x);
        return e / (1.0 + e);
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Soft-saturated audience-affinity score in [0, 1) from raw activity.
     *  raw   = post_count + log10(1 + engagement)
     *  score = raw / (raw + 5.0)
     * Monotonic, so ordering by p_conv is preserved.
     */
    private static double affinityScore(long postCount, long totalEngagement) {
        double raw = postCount + Math.log10(1.0 + Math.max(0, totalEngagement));
        return raw / (raw + 5.0);
    }

    // -------------------------------------------------------------------------
    // List entities in a category (e.g. media.celebrity, media.politics).
    // -------------------------------------------------------------------------
    public List<Map<String, Object>> listEntities(String category, String groupingColumn) {
        String sql =
                "SELECT me.id, me.name, me.type, " +
                "       COALESCE(MAX(ek." + groupingColumn + "), '') AS grouping_value, " +
                "       array_agg(DISTINCT ek.keyword) AS keywords " +
                "FROM entity_keywords ek " +
                "JOIN managed_entities me ON me.id = ek.entity_id " +
                "WHERE ek.category = ? " +
                "GROUP BY me.id, me.name, me.type " +
                "ORDER BY me.name";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, category);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entityId", row.get("id"));
            entry.put("name",     row.get("name"));
            entry.put("type",     row.get("type"));
            entry.put(groupingColumn, row.get("grouping_value"));
            entry.put("keywords",     sqlArrayToList(row.get("keywords")));
            out.add(entry);
        }
        return out;
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
            if (sqlArray instanceof List<?> l) {
                return (List<String>) l;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    // -------------------------------------------------------------------------
    // Per-user activity for a keyword across all 4 platforms. Joins directly
    // on author = marketing_target_profiles.global_user_id — this is the
    // contract MarketingEnrichmentEngine writes against (raw author string),
    // not the normalised author / UUID used by user_identity_link.
    // -------------------------------------------------------------------------
    private List<Map<String, Object>> aggregateByUser(String keyword) {
        String sql =
                "WITH per_post AS (" +
                "  SELECT author AS global_user_id, " +
                "         COALESCE(views_count, 0)::bigint AS engagement " +
                "  FROM x_posts          WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(likes_count, 0)::bigint " +
                "  FROM youtube_comments WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(num_comments, 0)::bigint " +
                "  FROM reddit_posts     WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(like_count, 0)::bigint " +
                "  FROM instagram_posts  WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "), per_user AS (" +
                "  SELECT global_user_id, " +
                "         COUNT(*)        AS post_count, " +
                "         SUM(engagement) AS total_engagement " +
                "  FROM per_post " +
                "  GROUP BY global_user_id " +
                ") " +
                "SELECT m.global_user_id, m.platform_handles, m.tribe_label, m.influence_rank, " +
                "       m.peak_activity_times, m.moi_score, " +
                "       pu.post_count, pu.total_engagement " +
                "FROM per_user pu " +
                "JOIN marketing_target_profiles m ON m.global_user_id = pu.global_user_id";

        return jdbc.queryForList(sql, keyword, keyword, keyword, keyword);
    }

    /** Users who have posted about {@code keyword}, sorted by predicted conversion. */
    public List<Map<String, Object>> potentialAudience(String keyword) {
        List<Map<String, Object>> rows = aggregateByUser(keyword);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long postCount       = toLong(row.get("post_count"));
            long totalEngagement = toLong(row.get("total_engagement"));
            double influence     = toDouble(row.get("influence_rank"));
            double affinity      = affinityScore(postCount, totalEngagement);
            double pConv         = sigmoid(affinity * influence);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("global_user_id",      row.get("global_user_id"));
            entry.put("tribe_label",         row.get("tribe_label"));
            entry.put("platform_handles",    JsonbUtil.asTree(row.get("platform_handles"), gson));
            entry.put("peak_activity_times", JsonbUtil.asTree(row.get("peak_activity_times"), gson));
            entry.put("post_count",          postCount);
            entry.put("total_engagement",    totalEngagement);
            entry.put("affinity_score",      affinity);
            entry.put("influence_rank",      influence);
            entry.put("moi_score",           toDouble(row.get("moi_score")));
            entry.put("p_conv",              pConv);
            out.add(entry);
        }
        out.sort((a, b) -> Double.compare((Double) b.get("p_conv"), (Double) a.get("p_conv")));
        return out;
    }

    /** Top-N users for {@code keyword} ranked by Hawkes alpha (influence_rank). */
    public List<Map<String, Object>> topSpreaders(String keyword, int limit) {
        List<Map<String, Object>> rows = aggregateByUser(keyword);
        rows.sort((a, b) -> Double.compare(toDouble(b.get("influence_rank")),
                                           toDouble(a.get("influence_rank"))));

        int take = Math.min(limit, rows.size());
        List<Map<String, Object>> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("global_user_id",      row.get("global_user_id"));
            entry.put("tribe_label",         row.get("tribe_label"));
            entry.put("platform_handles",    JsonbUtil.asTree(row.get("platform_handles"), gson));
            entry.put("peak_activity_times", JsonbUtil.asTree(row.get("peak_activity_times"), gson));
            entry.put("hawkes_alpha",        toDouble(row.get("influence_rank")));
            entry.put("post_count",          toLong(row.get("post_count")));
            entry.put("total_engagement",    toLong(row.get("total_engagement")));
            entry.put("moi_score",           toDouble(row.get("moi_score")));
            out.add(entry);
        }
        return out;
    }

    /**
     * Authors classified as "Movie Buff" in {@code author_categories} who have
     * also posted about {@code keyword}. The classification is global (per-author,
     * not keyword-scoped), so we intersect it with the per-keyword post activity to
     * surface the movie buffs relevant to this campaign, ranked by branching ratio
     * (amplification potential) and then by their engagement on the keyword.
     */
    public List<Map<String, Object>> movieBuffs(String keyword) {
        String sql =
                "WITH per_post AS (" +
                "  SELECT author AS global_user_id, COALESCE(views_count, 0)::bigint AS engagement " +
                "  FROM x_posts          WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(likes_count, 0)::bigint " +
                "  FROM youtube_comments WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(num_comments, 0)::bigint " +
                "  FROM reddit_posts     WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(like_count, 0)::bigint " +
                "  FROM instagram_posts  WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "), per_user AS (" +
                "  SELECT global_user_id, COUNT(*) AS post_count, SUM(engagement) AS total_engagement " +
                "  FROM per_post GROUP BY global_user_id " +
                ") " +
                "SELECT ac.author, ac.audience_classification, ac.influence_tier, ac.posting_style, " +
                "       ac.dominant_tone, ac.primary_platform, ac.branching_ratio, ac.total_posts, " +
                "       pu.post_count, pu.total_engagement, m.platform_handles " +
                "FROM per_user pu " +
                "JOIN author_categories ac ON ac.author = pu.global_user_id " +
                "LEFT JOIN marketing_target_profiles m ON m.global_user_id = ac.author " +
                "WHERE ac.audience_classification = 'Movie Buff' " +
                "ORDER BY ac.branching_ratio DESC NULLS LAST, pu.total_engagement DESC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, keyword, keyword, keyword, keyword);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object platformHandles = JsonbUtil.asTree(row.get("platform_handles"), gson);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("author",                  row.get("author"));
            entry.put("audienceClassification",  row.get("audience_classification"));
            entry.put("influenceTier",           row.get("influence_tier"));
            entry.put("postingStyle",            row.get("posting_style"));
            entry.put("dominantTone",            row.get("dominant_tone"));
            entry.put("primaryPlatform",         row.get("primary_platform"));
            entry.put("branchingRatio",          toDouble(row.get("branching_ratio")));
            entry.put("totalPosts",              toLong(row.get("total_posts")));
            entry.put("keywordPostCount",        toLong(row.get("post_count")));
            entry.put("keywordEngagement",       toLong(row.get("total_engagement")));
            entry.put("platformHandles",         platformHandles);
            entry.put("profileUrl",              extractProfileUrl(platformHandles));
            out.add(entry);
        }
        return out;
    }

    /**
     * Picks the primary platform's profile_url out of a parsed platform_handles tree
     * (mirrors TopSpreadersController#extractProfileUrl). Null when the author hasn't
     * been through MarketingEnrichmentEngine yet (no marketing_target_profiles row) or
     * has no recorded profile_url.
     */
    private static String extractProfileUrl(Object platformHandlesTree) {
        if (!(platformHandlesTree instanceof Map<?, ?> root)) {
            return null;
        }
        if (!(root.get("by_platform") instanceof Map<?, ?> byPlatform) || byPlatform.isEmpty()) {
            return null;
        }
        Object primary = root.get("primary_platform");
        Object entryObj = primary instanceof String s ? byPlatform.get(s) : byPlatform.values().iterator().next();
        if (!(entryObj instanceof Map<?, ?> entry)) {
            return null;
        }
        Object url = entry.get("profile_url");
        return (url instanceof String s && !s.isBlank()) ? s : null;
    }

    /** Per-platform reach for {@code keyword} with relative-strength ratios. */
    public Map<String, Object> channelStrategy(String keyword, String entityLabel) {
        Map<String, long[]> byPlatform = reachByPlatformFromAgg(keyword);
        long[] x         = byPlatform.getOrDefault("x",         ZERO_REACH);
        long[] youtube   = byPlatform.getOrDefault("youtube",   ZERO_REACH);
        long[] reddit    = byPlatform.getOrDefault("reddit",    ZERO_REACH);
        long[] instagram = byPlatform.getOrDefault("instagram", ZERO_REACH);

        List<Map<String, Object>> channels = new ArrayList<>();
        channels.add(channelEntry("X",         x[0],         x[1]));
        channels.add(channelEntry("YouTube",   youtube[0],   youtube[1]));
        channels.add(channelEntry("Reddit",    reddit[0],    reddit[1]));
        channels.add(channelEntry("Instagram", instagram[0], instagram[1]));
        channels.sort((a, b) -> Long.compare((Long) b.get("reach"), (Long) a.get("reach")));

        long topReach = (Long) channels.get(0).get("reach");
        for (Map<String, Object> ch : channels) {
            long reach = (Long) ch.get("reach");
            ch.put("relative_strength", topReach == 0 ? 0.0 : (double) reach / topReach);
        }

        String topChannel    = (String) channels.get(0).get("platform");
        String bottomChannel = (String) channels.get(channels.size() - 1).get("platform");
        long   bottomReach   = (Long)   channels.get(channels.size() - 1).get("reach");
        String headline = topReach == 0
                ? entityLabel + " has no measurable reach in any tracked platform."
                : bottomReach == 0
                    ? entityLabel + " is concentrated on " + topChannel
                            + "; no measurable activity on " + bottomChannel + "."
                    : String.format(
                            "%s is %.1fx more active on %s than %s.",
                            entityLabel, (double) topReach / bottomReach, topChannel, bottomChannel);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reachMetric", Map.of(
                "X",         "views_count",
                "YouTube",   "likes_count",
                "Reddit",    "num_comments",
                "Instagram", "like_count"));
        body.put("topChannel", topChannel);
        body.put("headline",   headline);
        body.put("channels",   channels);
        return body;
    }

    // -------------------------------------------------------------------------
    // Multi-keyword variants — an entity spans several keywords, so the entity
    // intelligence report aggregates across its full keyword set rather than a
    // single keyword string. These mirror the single-keyword methods above but
    // match the post's keyword against any value in the supplied list.
    // -------------------------------------------------------------------------

    /** Audience size for an entity: distinct authors across all platforms and keywords. */
    public long audienceSize(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return 0L;
        String in = placeholders(keywords.size());
        String sql =
                "SELECT COUNT(DISTINCT author) FROM (" +
                "  SELECT author FROM x_posts          WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                "  UNION " +
                "  SELECT author FROM youtube_comments WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                "  UNION " +
                "  SELECT author FROM reddit_posts     WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                "  UNION " +
                "  SELECT author FROM instagram_posts  WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                ") combined";
        Object[] args = repeatLowered(keywords, 4);
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /**
     * Top-N advocates for an entity ranked by total engagement aggregated across
     * its keyword set, with Hawkes alpha breaking ties.
     */
    public List<Map<String, Object>> topSpreaders(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        List<Map<String, Object>> rows = aggregateByKeywords(keywords);
        rows.sort((a, b) -> {
            int byEngagement = Long.compare(toLong(b.get("total_engagement")),
                                            toLong(a.get("total_engagement")));
            if (byEngagement != 0) return byEngagement;
            return Double.compare(toDouble(b.get("influence_rank")),
                                  toDouble(a.get("influence_rank")));
        });

        int take = Math.min(limit, rows.size());
        List<Map<String, Object>> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            long postCount       = toLong(row.get("post_count"));
            long totalEngagement = toLong(row.get("total_engagement"));
            entry.put("global_user_id",      row.get("global_user_id"));
            entry.put("tribe_label",         row.get("tribe_label"));
            entry.put("platform_handles",    JsonbUtil.asTree(row.get("platform_handles"), gson));
            entry.put("peak_activity_times", JsonbUtil.asTree(row.get("peak_activity_times"), gson));
            entry.put("hawkes_alpha",        toDouble(row.get("influence_rank")));
            entry.put("post_count",          postCount);
            entry.put("total_likes",         toLong(row.get("total_likes")));
            entry.put("total_comments",      toLong(row.get("total_comments")));
            entry.put("total_views",         toLong(row.get("total_views")));
            entry.put("total_engagement",    totalEngagement);
            entry.put("engagement_per_post", postCount == 0 ? 0L
                                                            : Math.round((double) totalEngagement / postCount));
            entry.put("moi_score",           toDouble(row.get("moi_score")));
            out.add(entry);
        }
        return out;
    }

    /**
     * Per-user activity aggregated across an entity's full keyword set.
     * Engagement sums the active-interaction columns per platform (likes +
     * comments), matching {@link #engagementByPlatform} — not raw exposure
     * like X views, which would let one viral view-count drown out genuinely
     * interactive advocates. Likes and comments are also carried separately
     * so callers can show what an author's engagement is made of (Reddit's
     * "likes" component is its post score). Views are carried as a separate
     * informational column — kept out of total_engagement for the reason
     * above — and only X exposes a view count, so the other platforms
     * contribute zero.
     */
    private List<Map<String, Object>> aggregateByKeywords(List<String> keywords) {
        String in = placeholders(keywords.size());
        String sql =
                "WITH per_post AS (" +
                "  SELECT author AS global_user_id, COALESCE(likes_count, 0)::bigint AS likes, COALESCE(comment_count, 0)::bigint AS comments, COALESCE(views_count, 0)::bigint AS views " +
                "  FROM x_posts          WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(likes_count, 0)::bigint, COALESCE(reply_count, 0)::bigint, 0::bigint " +
                "  FROM youtube_comments WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(score, 0)::bigint, COALESCE(num_comments, 0)::bigint, 0::bigint " +
                "  FROM reddit_posts     WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(like_count, 0)::bigint, COALESCE(comments_count, 0)::bigint, 0::bigint " +
                "  FROM instagram_posts  WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100 " +
                "), per_user AS (" +
                "  SELECT global_user_id, COUNT(*) AS post_count, SUM(likes) AS total_likes, " +
                "         SUM(comments) AS total_comments, SUM(views) AS total_views, " +
                "         SUM(likes + comments) AS total_engagement " +
                "  FROM per_post GROUP BY global_user_id " +
                ") " +
                "SELECT m.global_user_id, m.platform_handles, m.tribe_label, m.influence_rank, " +
                "       m.peak_activity_times, m.moi_score, pu.post_count, " +
                "       pu.total_likes, pu.total_comments, pu.total_views, pu.total_engagement " +
                "FROM per_user pu " +
                "JOIN marketing_target_profiles m ON m.global_user_id = pu.global_user_id";
        return jdbc.queryForList(sql, repeatLowered(keywords, 4));
    }

    /** Comma-separated list of {@code n} bind placeholders, e.g. "?, ?, ?". */
    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    /**
     * Lower-cases each keyword and repeats the whole list {@code times} over —
     * one repetition per UNION branch — so the flattened array lines up with
     * the {@code LOWER(keyword) IN (...)} placeholders in source order.
     */
    private static Object[] repeatLowered(List<String> keywords, int times) {
        Object[] args = new Object[keywords.size() * times];
        int idx = 0;
        for (int t = 0; t < times; t++)
            for (String k : keywords)
                args[idx++] = k == null ? null : k.toLowerCase();
        return args;
    }

    /** Audience size for a keyword: distinct authors across all platforms. */
    public long audienceSize(String keyword) {
        String sql =
                "SELECT COUNT(DISTINCT author) FROM (" +
                "  SELECT author FROM x_posts          WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION " +
                "  SELECT author FROM youtube_comments WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION " +
                "  SELECT author FROM reddit_posts     WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                "  UNION " +
                "  SELECT author FROM instagram_posts  WHERE keyword ILIKE ? AND author IS NOT NULL AND author <> '' " +
                ") combined";
        Long n = jdbc.queryForObject(sql, Long.class, keyword, keyword, keyword, keyword);
        return n == null ? 0L : n;
    }

    // -------------------------------------------------------------------------
    // Entity-scoped reach / engagement aggregates across the full keyword set.
    // "Reach" reuses the platform exposure columns already used by
    // channelStrategy (X→views_count, YouTube→likes_count, Reddit→num_comments,
    // Instagram→like_count). "Engagement" sums the active-interaction columns
    // (likes + comments) so it is a genuinely distinct signal from raw exposure
    // — on X especially, reach (views) dwarfs engagement (likes+replies).
    // Both scope by LOWER(keyword) IN (...), matching audienceSize(List).
    // -------------------------------------------------------------------------

    /** Per-platform exposure reach plus the cross-platform total, as a JSON-ready map. */
    public Map<String, Object> reachByPlatform(List<String> keywords) {
        long x  = reachSum("x_posts",          "COALESCE(views_count, 0)",   keywords);
        long yt = reachSum("youtube_comments",  "COALESCE(likes_count, 0)",   keywords);
        long rd = reachSum("reddit_posts",      "COALESCE(num_comments, 0)",  keywords);
        long ig = reachSum("instagram_posts",   "COALESCE(like_count, 0)",    keywords);
        return platformBreakdown(x, yt, rd, ig,
                Map.of("X", "views_count", "YouTube", "likes_count",
                       "Reddit", "num_comments", "Instagram", "like_count"));
    }

    /** Per-platform interaction engagement (likes + comments) plus the total. */
    public Map<String, Object> engagementByPlatform(List<String> keywords) {
        long x  = reachSum("x_posts",          "COALESCE(likes_count, 0) + COALESCE(comment_count, 0)",  keywords);
        long yt = reachSum("youtube_comments",  "COALESCE(likes_count, 0) + COALESCE(reply_count, 0)",   keywords);
        long rd = reachSum("reddit_posts",      "COALESCE(score, 0) + COALESCE(num_comments, 0)",        keywords);
        long ig = reachSum("instagram_posts",   "COALESCE(like_count, 0) + COALESCE(comments_count, 0)", keywords);
        return platformBreakdown(x, yt, rd, ig,
                Map.of("X", "likes_count + comment_count", "YouTube", "likes_count + reply_count",
                       "Reddit", "score + num_comments", "Instagram", "like_count + comments_count"));
    }

    /** SUM of {@code valueExpr} over rows whose keyword is in the entity's set. */
    private long reachSum(String table, String valueExpr, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return 0L;
        String in  = placeholders(keywords.size());
        String sql = "SELECT COALESCE(SUM(" + valueExpr + "), 0)::bigint FROM " + table +
                     " WHERE LOWER(keyword) IN (" + in + ")";
        Long n = jdbc.queryForObject(sql, Long.class, repeatLowered(keywords, 1));
        return n == null ? 0L : n;
    }

    private static Map<String, Object> platformBreakdown(
            long x, long yt, long rd, long ig, Map<String, String> metricColumns) {
        List<Map<String, Object>> channels = new ArrayList<>();
        channels.add(channelEntry("X",         x,  0));
        channels.add(channelEntry("YouTube",   yt, 0));
        channels.add(channelEntry("Reddit",    rd, 0));
        channels.add(channelEntry("Instagram", ig, 0));
        channels.forEach(c -> c.remove("postCount"));   // postCount is not meaningful here
        channels.sort((a, b) -> Long.compare((Long) b.get("reach"), (Long) a.get("reach")));

        long total = x + yt + rd + ig;
        for (Map<String, Object> c : channels) {
            long v = (Long) c.get("reach");
            c.put("share", total == 0 ? 0.0 : (double) v / total);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total",        total);
        body.put("metricColumns", metricColumns);
        body.put("byPlatform",   channels);
        return body;
    }

    /**
     * Reads precomputed per-platform reach for {@code keyword} from {@code channel_reach_agg}
     * (kept fresh by {@link ChannelReachPrecomputer}), instead of scanning all 4 platform tables
     * live on every request. {@code keyword = LOWER(?)} matches how the precomputer stores the
     * column (LOWER(keyword)) — reproducing the old {@code keyword ILIKE ?}'s case-insensitive
     * equality on an unwildcarded pattern.
     */
    private Map<String, long[]> reachByPlatformFromAgg(String keyword) {
        Map<String, long[]> byPlatform = new HashMap<>();
        jdbc.query("SELECT platform, reach, post_count FROM channel_reach_agg WHERE keyword = LOWER(?)",
                rs -> {
                    byPlatform.put(rs.getString("platform"),
                            new long[]{rs.getLong("reach"), rs.getLong("post_count")});
                },
                keyword);
        return byPlatform;
    }

    private static Map<String, Object> channelEntry(String platform, long reach, long postCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform",  platform);
        m.put("reach",     reach);
        m.put("postCount", postCount);
        return m;
    }
}
