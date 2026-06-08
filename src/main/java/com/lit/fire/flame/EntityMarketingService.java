package com.lit.fire.flame;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared back-end for keyword-scoped marketing endpoints (politics, celebrity).
 *
 * Genre routes use {@code marketing_target_profiles.top_genres} because genre
 * scores are precomputed at enrichment time. Parties and celebrities have no
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

    /** Per-platform reach for {@code keyword} with relative-strength ratios. */
    public Map<String, Object> channelStrategy(String keyword, String entityLabel) {
        long[] x         = reachForTable("x_posts",          "views_count",  keyword);
        long[] youtube   = reachForTable("youtube_comments", "likes_count",  keyword);
        long[] reddit    = reachForTable("reddit_posts",     "num_comments", keyword);
        long[] instagram = reachForTable("instagram_posts",  "like_count",   keyword);

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
                "  SELECT author FROM x_posts          WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' " +
                "  UNION " +
                "  SELECT author FROM youtube_comments WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' " +
                "  UNION " +
                "  SELECT author FROM reddit_posts     WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' " +
                "  UNION " +
                "  SELECT author FROM instagram_posts  WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' " +
                ") combined";
        Object[] args = repeatLowered(keywords, 4);
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /** Top-N advocates for an entity ranked by Hawkes alpha, across its keyword set. */
    public List<Map<String, Object>> topSpreaders(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        List<Map<String, Object>> rows = aggregateByKeywords(keywords);
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

    /** Per-user activity aggregated across an entity's full keyword set. */
    private List<Map<String, Object>> aggregateByKeywords(List<String> keywords) {
        String in = placeholders(keywords.size());
        String sql =
                "WITH per_post AS (" +
                "  SELECT author AS global_user_id, COALESCE(views_count, 0)::bigint AS engagement " +
                "  FROM x_posts          WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score <> 0 " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(likes_count, 0)::bigint " +
                "  FROM youtube_comments WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score <> 0 " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(num_comments, 0)::bigint " +
                "  FROM reddit_posts     WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score <> 0 " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(like_count, 0)::bigint " +
                "  FROM instagram_posts  WHERE LOWER(keyword) IN (" + in + ") AND author IS NOT NULL AND author <> '' AND sentiment_score <> 0 " +
                "), per_user AS (" +
                "  SELECT global_user_id, COUNT(*) AS post_count, SUM(engagement) AS total_engagement " +
                "  FROM per_post GROUP BY global_user_id " +
                ") " +
                "SELECT m.global_user_id, m.platform_handles, m.tribe_label, m.influence_rank, " +
                "       m.peak_activity_times, m.moi_score, pu.post_count, pu.total_engagement " +
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

    private long[] reachForTable(String table, String reachColumn, String keyword) {
        String sql = "SELECT COALESCE(SUM(" + reachColumn + "), 0)::bigint AS reach, " +
                     "       COUNT(*)::bigint                              AS post_count " +
                     "FROM " + table + " WHERE keyword ILIKE ?";
        Map<String, Object> row = jdbc.queryForMap(sql, keyword);
        return new long[]{ toLong(row.get("reach")), toLong(row.get("post_count")) };
    }

    private static Map<String, Object> channelEntry(String platform, long reach, long postCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform",  platform);
        m.put("reach",     reach);
        m.put("postCount", postCount);
        return m;
    }
}
