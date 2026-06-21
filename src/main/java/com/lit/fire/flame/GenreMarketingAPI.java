package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lit.fire.flame.GenreClassifier.GenreLabel;
import com.lit.fire.flame.models.UniversalPost;
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

    // top_movie_genres weights are per-post classifier scores summed over the
    // user's posts, and a single matched post contributes at least 1.0 — so
    // requiring > 1.0 means more than one post's worth of genre signal.
    private static final double GENRE_INTEREST_THRESHOLD = 1.0;
    private static final int    SUPER_SPREADER_LIMIT     = 50;

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
    // Users whose GenreInterestScore for {genre} exceeds GENRE_INTEREST_THRESHOLD,
    // sorted by conversion probability:
    //   genre_affinity  = genre_interest_score / Σ(all genre scores)   ∈ [0, 1]
    //   p_conv          = sigmoid(genre_affinity × influence_rank)
    //
    // Normalising by total genre score prevents prolific authors from saturating
    // sigmoid purely through post volume; genre_affinity captures what share of
    // an author's genre-tagged content belongs to this genre.
    // -------------------------------------------------------------------------
    @GetMapping("/{genre}/potential-viewers")
    public ResponseEntity<Map<String, Object>> potentialViewers(@PathVariable String genre) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT global_user_id, platform_handles, tribe_label, influence_rank, " +
                "       top_movie_genres, peak_activity_times, moi_score " +
                "FROM marketing_target_profiles " +
                "WHERE top_movie_genres::text ILIKE ?",
                "%\"" + genre + "\"%");

        List<Map<String, Object>> viewers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Double genreScore = extractGenreScore(row, genre);
            if (genreScore == null || genreScore <= GENRE_INTEREST_THRESHOLD) {
                continue;
            }

            double totalScore    = extractTotalGenreScore(row);
            double genreAffinity = totalScore > 0 ? genreScore / totalScore : 0.0;
            double influenceRank = toDouble(row.get("influence_rank"));
            double pConv         = sigmoid(genreAffinity * influenceRank);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("global_user_id",       row.get("global_user_id"));
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
        body.put("scoringModel", "p_conv = sigmoid(genre_affinity * influence_rank); genre_affinity = genre_interest_score / total_genre_score");
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
    // Genre membership is decided in-memory by GenreClassifier — every post
    // is built into a UniversalPost (with platform-specific metadata so the
    // Reddit-title weighting and Instagram media-type boosts apply) and
    // accepted only if the classifier returns the requested genre.
    //
    // Reach proxy (matches GenreInterestProfiler):
    //   X        → views_count
    //   YouTube  → likes_count
    //   Reddit   → num_comments
    //   Instagram→ like_count
    // -------------------------------------------------------------------------
    @GetMapping("/{genre}/channel-strategy")
    public ResponseEntity<Map<String, Object>> channelStrategy(@PathVariable String genre) {
        long[] xStats         = classifyTable("x_posts",          genre);
        long[] youtubeStats   = classifyTable("youtube_comments", genre);
        long[] redditStats    = classifyTable("reddit_posts",     genre);
        long[] instagramStats = classifyTable("instagram_posts",  genre);

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
     * Streams every row of {@code table} through {@link GenreClassifier} and
     * returns {@code [reach, matchedPostCount]} for posts the classifier
     * tagged with {@code genre}. Reach uses the per-platform proxy.
     */
    private long[] classifyTable(String table, String genre) {
        long[] acc = new long[]{0L, 0L};   // [reach, matchedCount]

        switch (table) {
            case "x_posts" -> jdbc.query(
                    "SELECT id, text, keyword, COALESCE(views_count, 0) AS metric " +
                    "FROM x_posts",
                    rs -> {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("keyword", rs.getString("keyword"));
                        UniversalPost post = new UniversalPost(
                                rs.getString("id"), null, rs.getString("text"),
                                null, "x_posts", meta);
                        if (matchesGenre(post, genre)) {
                            acc[0] += rs.getLong("metric");
                            acc[1] += 1;
                        }
                    });
            case "youtube_comments" -> jdbc.query(
                    "SELECT id, text, keyword, COALESCE(likes_count, 0) AS metric " +
                    "FROM youtube_comments",
                    rs -> {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("keyword", rs.getString("keyword"));
                        UniversalPost post = new UniversalPost(
                                rs.getString("id"), null, rs.getString("text"),
                                null, "youtube_comments", meta);
                        if (matchesGenre(post, genre)) {
                            acc[0] += rs.getLong("metric");
                            acc[1] += 1;
                        }
                    });
            case "reddit_posts" -> jdbc.query(
                    "SELECT id, title, text, keyword, COALESCE(num_comments, 0) AS metric " +
                    "FROM reddit_posts",
                    rs -> {
                        String title = rs.getString("title") == null ? "" : rs.getString("title");
                        String body  = rs.getString("text")  == null ? "" : rs.getString("text");
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("keyword", rs.getString("keyword"));
                        meta.put("title",   title);
                        UniversalPost post = new UniversalPost(
                                rs.getString("id"), null, (title + " " + body).trim(),
                                null, "reddit_posts", meta);
                        if (matchesGenre(post, genre)) {
                            acc[0] += rs.getLong("metric");
                            acc[1] += 1;
                        }
                    });
            case "instagram_posts" -> jdbc.query(
                    "SELECT id, text, keyword, media_type, COALESCE(like_count, 0) AS metric " +
                    "FROM instagram_posts",
                    rs -> {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("keyword",    rs.getString("keyword"));
                        meta.put("media_type", rs.getString("media_type"));
                        UniversalPost post = new UniversalPost(
                                rs.getString("id"), null, rs.getString("text"),
                                null, "instagram_posts", meta);
                        if (matchesGenre(post, genre)) {
                            acc[0] += rs.getLong("metric");
                            acc[1] += 1;
                        }
                    });
            default -> throw new IllegalArgumentException("Unknown table: " + table);
        }
        return acc;
    }

    private boolean matchesGenre(UniversalPost post, String genre) {
        for (GenreLabel label : classifier.classifyPost(post)) {
            if (genre.equalsIgnoreCase(label.genre())) {
                return true;
            }
        }
        return false;
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
