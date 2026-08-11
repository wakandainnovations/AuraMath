package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lit.fire.flame.GenreClassifier.GenreLabel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Genre-scoped marketing endpoints backed by {@code marketing_target_profiles}.
 * Outputs are flat JSON shaped for an ad-buying dashboard.
 */
@RestController
@RequestMapping("/api/marketing/genre")
public class GenreMarketingAPI {

    // top_movie_genres stores sentiment-weighted genre engagement: each post's
    // contribution is max(0, signed_sentiment) × log(engagement+1) × classifier_weight.
    // Scores are non-negative; threshold > 1.0 requires meaningful positive engagement
    // signal (e.g. more than one minimally-engaged post with above-neutral sentiment).
    private static final double GENRE_INTEREST_THRESHOLD = 1.0;
    private static final int    SUPER_SPREADER_LIMIT     = 50;

    // Default reach/post_count for a platform with no genre_channel_reach_agg row (genre never
    // matched, or the precomputer hasn't run yet) — matches the old live-scan's implicit zero.
    private static final long[] ZERO_REACH = {0L, 0L};

    private static final Type GENRE_MAP_TYPE = new TypeToken<Map<String, Double>>() {}.getType();

    @Autowired private JdbcTemplate            jdbc;
    @Autowired private GenreClassifier         classifier;
    @Autowired private TopicalSpreaderDetector detector;
    @Value("${hawkes.beta:3.0}") private double hawkesBeta;
    private final Gson gson = new Gson();

    // -------------------------------------------------------------------------
    // GET /api/marketing/genre
    //
    // Lists every movie genre this service can score against. The genre names
    // are the path values accepted by /api/marketing/genre/{genre}/... below.
    // -------------------------------------------------------------------------
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> listGenres() {
        List<Map<String, Object>> genres = new ArrayList<>();
        for (String name : classifier.knownGenres()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("genre", name);
            entry.put("keywordCount", classifier.keywordsFor(name).size());
            genres.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalGenres", genres.size());
        body.put("genres", genres);
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // GET /api/marketing/genre/{genre}/potential-viewers
    //
    // Queries the mentions pipeline live: for each author, sums
    //   max(0, (sentiment_score - 50) / 50)
    // across all their positive-sentiment mentions of {genre} movie/entity posts
    // (via mention_entities → managed_entities/entity_keywords genre mapping).
    // Deduplication: DISTINCT (mention_id, genre) prevents inflating scores when
    // a mention links to multiple entities that share the same genre.
    //
    //   genre_affinity  = genre_interest_score / Σ(all genre scores)   ∈ [0, 1]
    //   p_conv          = sigmoid(genre_affinity × influence_rank)
    //
    // Only authors already in marketing_target_profiles are returned (inner JOIN),
    // ensuring influence_rank is always available for p_conv.
    // -------------------------------------------------------------------------
    @GetMapping("/{genre}/potential-viewers")
    public ResponseEntity<Map<String, Object>> potentialViewers(@PathVariable String genre) {
        String sql =
            "WITH entity_genre AS (" +
            "  SELECT DISTINCT me.id AS entity_id, TRIM(g.g) AS genre" +
            "  FROM managed_entities me" +
            "  CROSS JOIN LATERAL unnest(string_to_array(me.genre, ',')) AS g(g)" +
            "  WHERE me.genre IS NOT NULL AND me.genre <> ''" +
            "  UNION" +
            "  SELECT DISTINCT ek.entity_id, TRIM(g.g) AS genre" +
            "  FROM entity_keywords ek" +
            "  CROSS JOIN LATERAL unnest(string_to_array(ek.genre, ',')) AS g(g)" +
            "  WHERE ek.category = 'media.movie' AND ek.genre IS NOT NULL AND ek.genre <> ''" +
            ")," +
            "mention_genre_pairs AS (" +
            "  SELECT DISTINCT m.id AS mention_id, m.author, m.sentiment_score, eg.genre" +
            "  FROM mentions m" +
            "  JOIN mention_entities me_j ON me_j.mention_id = m.id" +
            "  JOIN entity_genre eg ON eg.entity_id = me_j.managed_entity_id" +
            "  WHERE m.author IS NOT NULL AND m.sentiment_score BETWEEN 1 AND 100" +
            ")," +
            "genre_scores AS (" +
            "  SELECT author, genre," +
            "         SUM(GREATEST(0.0, (sentiment_score::float - 50.0) / 50.0)) AS genre_score" +
            "  FROM mention_genre_pairs" +
            "  GROUP BY author, genre" +
            ")," +
            "author_totals AS (" +
            "  SELECT author, SUM(genre_score) AS total_score" +
            "  FROM genre_scores WHERE genre_score > 0" +
            "  GROUP BY author" +
            ")," +
            "target_viewers AS (" +
            "  SELECT gs.author, gs.genre_score, at.total_score," +
            "         gs.genre_score / at.total_score AS genre_affinity" +
            "  FROM genre_scores gs" +
            "  JOIN author_totals at ON at.author = gs.author" +
            "  WHERE gs.genre ILIKE ?" +
            "    AND gs.genre_score > ?" +
            ")" +
            "SELECT tv.author, tv.genre_score, tv.genre_affinity," +
            "       mtp.tribe_label, mtp.platform_handles, mtp.peak_activity_times," +
            "       mtp.influence_rank, mtp.moi_score" +
            " FROM target_viewers tv" +
            " JOIN marketing_target_profiles mtp ON mtp.global_user_id = tv.author";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, genre, GENRE_INTEREST_THRESHOLD);

        List<Map<String, Object>> viewers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double genreScore    = toDouble(row.get("genre_score"));
            double genreAffinity = toDouble(row.get("genre_affinity"));
            double influenceRank = toDouble(row.get("influence_rank"));
            double pConv         = sigmoid(genreAffinity * influenceRank);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("global_user_id",       row.get("author"));
            entry.put("tribe_label",          row.get("tribe_label"));
            entry.put("platform_handles",     JsonbUtil.asTree(row.get("platform_handles"), gson));
            entry.put("peak_activity_times",  JsonbUtil.asTree(row.get("peak_activity_times"), gson));
            entry.put("genre_interest_score", genreScore);
            entry.put("genre_affinity",       genreAffinity);
            entry.put("influence_rank",       influenceRank);
            entry.put("moi_score",            toDouble(row.get("moi_score")));
            entry.put("p_conv",               pConv);
            viewers.add(entry);
        }

        viewers.sort((a, b) -> Double.compare((Double) b.get("p_conv"), (Double) a.get("p_conv")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("genre",        genre);
        body.put("threshold",    GENRE_INTEREST_THRESHOLD);
        body.put("scoringModel",
                 "p_conv = sigmoid(genre_affinity * influence_rank); " +
                 "genre_affinity = genre_interest_score / total_mention_genre_score; " +
                 "genre_interest_score = SUM(max(0, (sentiment_score - 50) / 50)) " +
                 "from mentions × mention_entities × managed_entities/entity_keywords genre");
        body.put("totalViewers", viewers.size());
        body.put("viewers",      viewers);
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // GET /api/marketing/genre/{genre}/super-spreaders
    //
    // Top 50 users for {genre} by Hawkes alpha estimated from {genre} posts only.
    // TopicalSpreaderDetector filters posts to genre-matching ones before fitting
    // the Hawkes process, so the alpha score reflects spreading influence within
    // the genre rather than overall cross-topic posting infectivity.
    // -------------------------------------------------------------------------
    @GetMapping("/{genre}/super-spreaders")
    public ResponseEntity<Map<String, Object>> superSpreaders(@PathVariable String genre) {
        List<TopicalSpreaderDetector.SpreaderResult> ranked =
                detector.rankBySpreading(new GenreLabel(genre, 1.0));

        int take = Math.min(SUPER_SPREADER_LIMIT, ranked.size());
        List<TopicalSpreaderDetector.SpreaderResult> top = ranked.subList(0, take);

        List<String> authorIds = top.stream()
                .map(TopicalSpreaderDetector.SpreaderResult::author)
                .collect(Collectors.toList());
        Map<String, Map<String, Object>> enrichByAuthor = fetchEnrichment(authorIds);

        List<Map<String, Object>> spreaders = new ArrayList<>();
        for (TopicalSpreaderDetector.SpreaderResult r : top) {
            Map<String, Object> enrich = enrichByAuthor.getOrDefault(r.author(), Map.of());
            Double genreScore = extractGenreScore(enrich, genre);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("global_user_id",       r.author());
            entry.put("tribe_label",          enrich.get("tribe_label"));
            entry.put("platform_handles",     JsonbUtil.asTree(enrich.get("platform_handles"), gson));
            entry.put("peak_activity_times",  JsonbUtil.asTree(enrich.get("peak_activity_times"), gson));
            entry.put("hawkes_alpha",         r.alpha());
            entry.put("branching_ratio",      r.alpha() / hawkesBeta);
            entry.put("genre_post_count",     r.postCount());
            entry.put("genre_interest_score", genreScore == null ? 0.0 : genreScore);
            entry.put("moi_score",            toDouble(enrich.get("moi_score")));
            spreaders.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("genre",          genre);
        body.put("limit",          SUPER_SPREADER_LIMIT);
        body.put("rankingMetric",  "hawkes_alpha (genre-scoped: estimated from " + genre + " posts only)");
        body.put("totalSpreaders", spreaders.size());
        body.put("spreaders",      spreaders);
        return ResponseEntity.ok(body);
    }

    private Map<String, Map<String, Object>> fetchEnrichment(List<String> authorIds) {
        if (authorIds.isEmpty()) return Map.of();
        String placeholders = authorIds.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT global_user_id, tribe_label, platform_handles, " +
                     "       peak_activity_times, top_movie_genres, moi_score " +
                     "FROM marketing_target_profiles WHERE global_user_id IN (" + placeholders + ")";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, authorIds.toArray());
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("global_user_id"), row);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // GET /api/marketing/genre/{genre}/channel-strategy
    //
    // Per-platform reach for {genre}, with relative-strength ratios so the
    // dashboard can render copy like "Horror fans are 3x more active on
    // Reddit than Instagram."
    //
    // Genre membership is decided in-memory by GenreClassifier, same as before, but now run once
    // by ChannelReachPrecomputer on a schedule rather than per request — this endpoint just reads
    // the resulting genre_channel_reach_agg aggregate.
    //
    // Reach proxy (matches GenreInterestProfiler):
    //   X        → views_count
    //   YouTube  → likes_count
    //   Reddit   → num_comments
    //   Instagram→ like_count
    // -------------------------------------------------------------------------
    @GetMapping("/{genre}/channel-strategy")
    public ResponseEntity<Map<String, Object>> channelStrategy(@PathVariable String genre) {
        Map<String, long[]> byPlatform = genreReachByPlatform(genre);
        long[] xStats         = byPlatform.getOrDefault("x",         ZERO_REACH);
        long[] youtubeStats   = byPlatform.getOrDefault("youtube",   ZERO_REACH);
        long[] redditStats    = byPlatform.getOrDefault("reddit",    ZERO_REACH);
        long[] instagramStats = byPlatform.getOrDefault("instagram", ZERO_REACH);

        Long audienceSize = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_target_profiles WHERE top_movie_genres::text ILIKE ?",
                Long.class, "%\"" + genre + "\"%");

        List<Map<String, Object>> channels = new ArrayList<>();
        channels.add(channelEntry("X",         xStats[0],         xStats[1]));
        channels.add(channelEntry("YouTube",   youtubeStats[0],   youtubeStats[1]));
        channels.add(channelEntry("Reddit",    redditStats[0],    redditStats[1]));
        channels.add(channelEntry("Instagram", instagramStats[0], instagramStats[1]));
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
                ? genre + " has no measurable reach in any tracked platform."
                : bottomReach == 0
                    ? genre + " fans are concentrated on " + topChannel
                            + "; no measurable activity on " + bottomChannel + "."
                    : String.format(
                            "%s fans are %.1fx more active on %s than %s.",
                            genre, (double) topReach / bottomReach, topChannel, bottomChannel);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("genre",          genre);
        body.put("audienceSize",   audienceSize == null ? 0L : audienceSize);
        body.put("reachMetric",    Map.of(
                "X",         "views_count",
                "YouTube",   "likes_count",
                "Reddit",    "num_comments",
                "Instagram", "like_count"));
        body.put("topChannel",     topChannel);
        body.put("headline",       headline);
        body.put("channels",       channels);
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private Double extractGenreScore(Map<String, Object> row, String genre) {
        String json = JsonbUtil.asJsonString(row.get("top_movie_genres"));
        if (json == null || json.isEmpty()) {
            return null;
        }
        Map<String, Double> parsed = gson.fromJson(json, GENRE_MAP_TYPE);
        if (parsed == null) {
            return null;
        }
        // Case-insensitive lookup so "/Horror" matches "horror"/"HORROR" alike.
        for (Map.Entry<String, Double> e : parsed.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(genre) && e.getValue() != null) {
                return e.getValue();
            }
        }
        return null;
    }

    private double extractTotalGenreScore(Map<String, Object> row) {
        String json = JsonbUtil.asJsonString(row.get("top_movie_genres"));
        if (json == null || json.isEmpty()) return 0.0;
        Map<String, Double> parsed = gson.fromJson(json, GENRE_MAP_TYPE);
        if (parsed == null) return 0.0;
        double total = 0.0;
        for (Double v : parsed.values()) {
            if (v != null) total += v;
        }
        return total;
    }

    /**
     * Reads precomputed per-platform reach for {@code genre} from {@code genre_channel_reach_agg}
     * (kept fresh by {@link ChannelReachPrecomputer}), instead of scanning and classifying all 4
     * platform tables live on every request.
     */
    private Map<String, long[]> genreReachByPlatform(String genre) {
        Map<String, long[]> byPlatform = new HashMap<>();
        jdbc.query("SELECT platform, reach, post_count FROM genre_channel_reach_agg WHERE genre = LOWER(?)",
                rs -> {
                    byPlatform.put(rs.getString("platform"),
                            new long[]{rs.getLong("reach"), rs.getLong("post_count")});
                },
                genre);
        return byPlatform;
    }

    private static Map<String, Object> channelEntry(String platform, long reach, long postCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform",  platform);
        m.put("reach",     reach);
        m.put("postCount", postCount);
        return m;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private static double sigmoid(double x) {
        if (x >= 0) {
            double e = Math.exp(-x);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(x);
        return e / (1.0 + e);
    }
}
