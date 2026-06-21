package com.lit.fire.flame;

import com.google.gson.Gson;
import com.lit.fire.flame.mappers.PostMapper;
import com.lit.fire.flame.models.UniversalPost;
import com.lit.fire.flame.models.UserPersonaProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MarketingEnrichmentEngine {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private HawkesIntensityCalculator hawkesIntensityCalculator;

    @Autowired
    private AspectSentimentAnalyzer aspectSentimentAnalyzer;

    @Autowired
    private MarketingInsightsRepository marketingInsightsRepository;

    @Autowired
    private CrossPlatformIdentityResolver crossPlatformIdentityResolver;

    @Autowired
    private GenreClassifier genreClassifier;

    private static final String[] PLATFORM_TABLES = {"x_posts", "youtube_comments", "reddit_posts", "instagram_posts"};
    private static final int NUM_TRIBES = 12;
    private static final int CLUSTERING_TOP_ASPECTS_K = 100;

    // Weight applied when a post's keyword field matches a movie in entity_keywords.
    // Mirrors GenreClassifier.KEYWORD_FIELD_WEIGHT (2.0): the post's keyword column IS the
    // same controlled field the classifier already boosts at 2×, so a curated DB genre hit
    // on that field receives the same weight for consistency.
    private static final double ENTITY_KEYWORD_GENRE_WEIGHT = 2.0;

    private final Gson gson = new Gson();

    private record PersonaData(double moi, double alpha, Map<String, Double> aspects) {}

    public void enrichAndSave() {
        // Resolve cross-platform identities first so downstream lookups against
        // user_identity_link see every author present in the source tables.
        crossPlatformIdentityResolver.resolveIdentities();

        // Load once: keyword → genre from entity_keywords (media.movie) so every
        // per-author buildTopMovieGenresJson call can do O(1) lookups without DB round-trips.
        Map<String, String> movieKeywordGenres = loadMovieKeywordGenres();

        // Load once: author → {genre → score} from the mentions pipeline.
        // Gives definitive genre attribution for every tracked-entity post.
        Map<String, Map<String, Double>> mentionGenreScores = loadMentionGenreScores();

        Stream<UniversalPost> allPosts = Stream.of(PLATFORM_TABLES)
                .flatMap(this::streamTable);

        Map<String, List<UniversalPost>> postsByAuthor = allPosts
                .filter(p -> p.getAuthorId() != null)
                .collect(Collectors.groupingBy(UniversalPost::getAuthorId));

        // Pass 1: compute per-author metrics, accumulate aspect occurrence counts for feature selection.
        Map<String, PersonaData> personaByAuthor = new LinkedHashMap<>();
        Map<String, Integer> aspectCounts = new HashMap<>();

        for (Map.Entry<String, List<UniversalPost>> entry : postsByAuthor.entrySet()) {
            String authorId = entry.getKey();
            List<UniversalPost> posts = entry.getValue();

            double alpha;
            try {
                alpha = hawkesIntensityCalculator.estimateParameters(posts.stream()).alpha;
            } catch (RuntimeException e) {
                alpha = 0.0;
            }
            Map<String, Double> aspects = aspectSentimentAnalyzer.analyze(posts.stream());
            double moi = InfluenceMetricCalculator.calculateMoi(posts.stream()).getOrDefault(authorId, 0.0);

            personaByAuthor.put(authorId, new PersonaData(moi, alpha, aspects));
            for (String k : aspects.keySet()) {
                aspectCounts.merge(k, 1, Integer::sum);
            }
        }

        Map<String, String> tribeByAuthor = assignTribes(personaByAuthor, aspectCounts);

        // Pass 2: upsert each profile with its real tribe label.
        for (Map.Entry<String, PersonaData> entry : personaByAuthor.entrySet()) {
            String authorId = entry.getKey();
            PersonaData d = entry.getValue();

            String tribe = tribeByAuthor.getOrDefault(authorId, "Tribe_unassigned");
            UserPersonaProfile profile = new UserPersonaProfile(authorId, tribe, d.alpha(), d.aspects());

            List<UniversalPost> authorPosts = postsByAuthor.get(authorId);
            String platformHandles = buildPlatformHandlesJson(authorId, authorPosts);
            String peakActivityTimes = buildPeakActivityTimesJson(authorPosts);
            Map<String, Double> authorMentionScores = mentionGenreScores.getOrDefault(authorId, Map.of());
            String topMovieGenres = buildTopMovieGenresJson(authorPosts, movieKeywordGenres, authorMentionScores);

            marketingInsightsRepository.upsertUserPersonaProfile(profile, platformHandles, peakActivityTimes, topMovieGenres, d.moi());
        }
    }

    /**
     * Aggregates sentiment-weighted genre engagement across a user's posts into
     * {@code {"Action": 4.5, "Drama": 2.0, ...}} using three complementary signals:
     *
     * 1. Text classifier (GenreClassifier): scans post content and keyword field for
     *    genre-specific vocabulary (e.g. "emotional", "heartbreak" → Drama).
     *
     * 2. Entity-keyword lookup: the post's {@code keyword} column is a controlled
     *    search term. When it matches a movie in {@code entity_keywords} (category =
     *    'media.movie'), the stored genre is a definitive label — catching movie-title
     *    posts (e.g. "Interstellar" → Sci-Fi) that contain no classifier vocabulary.
     *
     * 3. Mentions-based genre scores: pre-loaded from the {@code mentions} table joined
     *    with {@code mention_entities} and {@code managed_entities/entity_keywords}.
     *    Each mention contributes {@code max(0, (sentiment_score - 50) / 50)} to the
     *    genre of the entity it was collected for. This is the most authoritative signal
     *    for posts about tracked entities because genre attribution comes directly from
     *    the entity database rather than text classification.
     *
     * Signals 1 and 2 are multiplied by a positive-clamped sentiment-engagement weight;
     * Signal 3 uses the raw sentiment contribution (no engagement multiplier) since
     * the {@code mentions} table does not store platform-specific engagement counts.
     * All scores are non-negative, preserving genre_affinity ∈ [0,1] downstream.
     *
     * @param movieKeywordGenres  lowercase keyword → canonical genre, from entity_keywords
     * @param mentionScores       genre → score pre-aggregated from the mentions pipeline
     */
    private String buildTopMovieGenresJson(List<UniversalPost> posts,
                                           Map<String, String> movieKeywordGenres,
                                           Map<String, Double> mentionScores) {
        Map<String, Double> totals = new LinkedHashMap<>();
        if (posts != null) {
            for (UniversalPost p : posts) {
                double sentimentWeight = postSentimentWeight(p);
                if (sentimentWeight <= 0.0) continue;

                // Signal 1: text-content genre classification
                for (GenreClassifier.GenreLabel label : genreClassifier.classifyPost(p)) {
                    totals.merge(label.genre(), sentimentWeight * label.weight(), Double::sum);
                }

                // Signal 2: entity-keyword genre lookup (movie title → genre from DB)
                String postKeyword = stringFromMeta(p, "keyword");
                if (!postKeyword.isBlank()) {
                    String entityGenre = movieKeywordGenres.get(postKeyword.toLowerCase().trim());
                    if (entityGenre != null) {
                        totals.merge(canonicalizeGenre(entityGenre),
                                     sentimentWeight * ENTITY_KEYWORD_GENRE_WEIGHT,
                                     Double::sum);
                    }
                }
            }
        }

        // Signal 3: mentions-based genre scores (pre-aggregated, genre keys already canonicalized)
        for (Map.Entry<String, Double> e : mentionScores.entrySet()) {
            if (e.getValue() > 0.0) {
                totals.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }

        return gson.toJson(totals);
    }

    /**
     * Loads the keyword → genre mapping for all movie entities from {@code entity_keywords}.
     * Keys are lowercased for case-insensitive matching against post keyword columns.
     * Called once per {@link #enrichAndSave()} run to avoid per-author DB round-trips.
     */
    private Map<String, String> loadMovieKeywordGenres() {
        Map<String, String> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT LOWER(keyword) AS kw, genre " +
                "FROM entity_keywords " +
                "WHERE category = 'media.movie' AND keyword IS NOT NULL AND genre IS NOT NULL",
                rs -> {
                    String genre = rs.getString("genre");
                    if (genre != null && !genre.isBlank()) {
                        result.put(rs.getString("kw"), genre.trim());
                    }
                });
        return result;
    }

    /**
     * Loads per-author genre scores from the mentions pipeline for Signal 3 in
     * {@link #buildTopMovieGenresJson}.
     *
     * The CTE pipeline:
     *   1. entity_genre — explodes comma-separated genre strings from managed_entities
     *      and entity_keywords into individual (entity_id, genre) pairs.
     *   2. mention_genre_pairs — DISTINCT (mention_id, genre) prevents double-counting
     *      when a mention links to multiple entities sharing the same genre.
     *   3. Aggregates SUM(max(0, (sentiment_score - 50) / 50)) per (author, genre),
     *      keeping only positive contributions.
     *
     * Result map: author → {canonicalizedGenre → score}.
     */
    private Map<String, Map<String, Double>> loadMentionGenreScores() {
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
            ")" +
            "SELECT author, genre," +
            "       SUM(GREATEST(0.0, (sentiment_score::float - 50.0) / 50.0)) AS genre_score" +
            " FROM mention_genre_pairs" +
            " GROUP BY author, genre" +
            " HAVING SUM(GREATEST(0.0, (sentiment_score::float - 50.0) / 50.0)) > 0";

        Map<String, Map<String, Double>> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String author = rs.getString("author");
            String genre  = canonicalizeGenre(rs.getString("genre"));
            double score  = rs.getDouble("genre_score");
            result.computeIfAbsent(author, k -> new HashMap<>()).merge(genre, score, Double::sum);
        });
        return result;
    }

    /**
     * Normalises a genre string from {@code entity_keywords} to the canonical casing used
     * by {@link GenreClassifier} (e.g. "sci-fi" → "Sci-Fi"). When a match is found the
     * entity contribution merges into the same JSON key as the classifier contribution,
     * avoiding duplicate keys that would silently split the score in {@code extractGenreScore}.
     * Unrecognised genres (not in the classifier vocabulary) are kept as-is so new genres
     * from the DB are not silently dropped.
     */
    private String canonicalizeGenre(String genre) {
        for (String known : genreClassifier.knownGenres()) {
            if (known.equalsIgnoreCase(genre)) return known;
        }
        return genre;
    }

    /**
     * Positive-clamped sentiment-weighted engagement for one post.
     * Returns 0.0 for invalid sentiment (DB sentinel 0), neutral/negative sentiment (≤ 50),
     * or zero platform engagement — so only posts that are both positively perceived and
     * genuinely engaged-with contribute genre scores.
     *
     * Platform engagement proxies (matching GenreInterestProfiler):
     *   X         → views_count  (metadata key "views")
     *   YouTube   → likes_count  (metadata key "likes")
     *   Reddit    → log(num_comments + 1) × max(0, reddit_score)
     *   Instagram → like_count   (metadata key "likes")
     */
    private static double postSentimentWeight(UniversalPost post) {
        if (post.getMetadata() == null) return 0.0;
        Object sentObj = post.getMetadata().get("sentiment_score");
        if (!(sentObj instanceof Number)) return 0.0;
        double sentiment = ((Number) sentObj).doubleValue();
        if (sentiment == 0.0) return 0.0;  // DB sentinel for invalid/missing sentiment
        double signedSentiment = (sentiment - 50.0) / 50.0;
        if (signedSentiment <= 0.0) return 0.0;

        String platform = post.getPlatform() == null ? "" : post.getPlatform();
        if ("reddit_posts".equals(platform)) {
            long comments    = longFromMeta(post, "comments");
            long redditScore = Math.max(0L, longFromMeta(post, "platformSpecificScore"));
            return signedSentiment * Math.log(comments + 1.0) * redditScore;
        }
        long engagement = "x_posts".equals(platform)
                ? longFromMeta(post, "views")
                : longFromMeta(post, "likes");
        return signedSentiment * Math.log(engagement + 1.0);
    }

    private String buildPlatformHandlesJson(String authorId, List<UniversalPost> posts) {
        Map<String, List<UniversalPost>> byPlatform = posts.stream()
                .collect(Collectors.groupingBy(UniversalPost::getPlatform));

        Map<String, Object> byPlatformOut = new LinkedHashMap<>();
        String primaryPlatform = null;
        int primaryCount = -1;

        for (Map.Entry<String, List<UniversalPost>> e : byPlatform.entrySet()) {
            String platformTable = e.getKey();
            List<UniversalPost> platformPosts = e.getValue();

            String platformShort = shortPlatformName(platformTable);
            UniversalPost latest = platformPosts.stream()
                    .filter(p -> p.getTimestamp() != null)
                    .max(Comparator.comparing(UniversalPost::getTimestamp))
                    .orElse(platformPosts.get(0));

            long likes = platformPosts.stream().mapToLong(p -> longFromMeta(p, "likes")).sum();
            long comments = platformPosts.stream().mapToLong(p -> longFromMeta(p, "comments")).sum();
            long views = platformPosts.stream().mapToLong(p -> longFromMeta(p, "views")).sum();
            int n = platformPosts.size();
            double avgEngagement = n > 0 ? (double) (likes + comments) / n : 0.0;

            String samplePermalink = stringFromMeta(latest, "permalink");

            Map<String, Object> platformEntry = new LinkedHashMap<>();
            platformEntry.put("profile_url", profileUrl(platformShort, authorId, samplePermalink));
            platformEntry.put("sample_post_url", samplePermalink);
            platformEntry.put("post_count", n);
            platformEntry.put("total_likes", likes);
            platformEntry.put("total_comments", comments);
            if (views > 0) {
                platformEntry.put("total_views", views);
            }
            platformEntry.put("avg_engagement_per_post", Math.round(avgEngagement * 100.0) / 100.0);
            byPlatformOut.put(platformShort, platformEntry);

            if (n > primaryCount) {
                primaryCount = n;
                primaryPlatform = platformShort;
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("primary_platform", primaryPlatform == null ? "unknown" : primaryPlatform);
        root.put("by_platform", byPlatformOut);
        return gson.toJson(root);
    }

    private String buildPeakActivityTimesJson(List<UniversalPost> posts) {
        long morning = 0, afternoon = 0, evening = 0, night = 0;
        long total = 0;
        for (UniversalPost p : posts) {
            if (p.getTimestamp() == null) continue;
            int h = p.getTimestamp().getHour();
            if (h >= 6 && h < 12)        morning++;
            else if (h >= 12 && h < 18)  afternoon++;
            else if (h >= 18 && h < 22)  evening++;
            else                          night++;
            total++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (total == 0) {
            out.put("morning", 0.0);
            out.put("afternoon", 0.0);
            out.put("evening", 0.0);
            out.put("night", 0.0);
            out.put("post_count", 0);
        } else {
            out.put("morning",   round3((double) morning   / total));
            out.put("afternoon", round3((double) afternoon / total));
            out.put("evening",   round3((double) evening   / total));
            out.put("night",     round3((double) night     / total));
            out.put("post_count", total);
        }
        return gson.toJson(out);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String shortPlatformName(String table) {
        return switch (table) {
            case "x_posts"           -> "x";
            case "youtube_comments"  -> "youtube";
            case "reddit_posts"      -> "reddit";
            case "instagram_posts"   -> "instagram";
            default                  -> table;
        };
    }

    // X permalinks have shape "https://twitter.com/{handle}/status/{id}" (or x.com).
    // The 'author' column is a display name, so the handle must come from the URL.
    private static final Pattern X_HANDLE = Pattern.compile(
            "(?:twitter\\.com|x\\.com)/([^/?#]+)/status/", Pattern.CASE_INSENSITIVE);

    private static String profileUrl(String platformShort, String author, String samplePermalink) {
        String stripped = author == null ? "" : (author.startsWith("@") ? author.substring(1) : author);
        return switch (platformShort) {
            case "x"         -> twitterProfileUrl(samplePermalink, stripped);
            case "youtube"   -> stripped.isBlank() ? "" : "https://www.youtube.com/@" + stripped;
            case "reddit"    -> stripped.isBlank() ? "" : "https://reddit.com/user/" + stripped;
            case "instagram" -> stripped.isBlank() ? "" : "https://www.instagram.com/" + stripped + "/";
            default          -> "";
        };
    }

    private static String twitterProfileUrl(String permalink, String fallbackAuthor) {
        if (permalink != null && !permalink.isBlank()) {
            Matcher m = X_HANDLE.matcher(permalink);
            if (m.find()) {
                return "https://twitter.com/" + m.group(1);
            }
        }
        // Fall back to author only if it looks like a real handle (no whitespace, ASCII).
        if (!fallbackAuthor.isBlank() && fallbackAuthor.chars().allMatch(c -> c < 128 && !Character.isWhitespace(c))) {
            return "https://twitter.com/" + fallbackAuthor;
        }
        return "";
    }

    private static long longFromMeta(UniversalPost p, String key) {
        if (p.getMetadata() == null) return 0L;
        Object v = p.getMetadata().get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static String stringFromMeta(UniversalPost p, String key) {
        if (p.getMetadata() == null) return "";
        Object v = p.getMetadata().get(key);
        return v == null ? "" : v.toString();
    }

    private Map<String, String> assignTribes(Map<String, PersonaData> personaByAuthor,
                                             Map<String, Integer> aspectCounts) {
        // Feature selection: top-K most common aspect keys across the population.
        // Full aspect vocabulary is ~95k nouns; most appear in 1-2 authors and add noise.
        List<String> topAspects = aspectCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(CLUSTERING_TOP_ASPECTS_K)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        int dim = 2 + topAspects.size();
        Map<String, double[]> rawVectors = new LinkedHashMap<>();
        for (Map.Entry<String, PersonaData> e : personaByAuthor.entrySet()) {
            PersonaData d = e.getValue();
            double[] v = new double[dim];
            v[0] = d.moi();
            v[1] = d.alpha();
            for (int i = 0; i < topAspects.size(); i++) {
                v[2 + i] = d.aspects().getOrDefault(topAspects.get(i), 0.0);
            }
            rawVectors.put(e.getKey(), v);
        }

        // Z-score normalize per dimension so MOI/alpha aren't drowned by aspect scales.
        int n = rawVectors.size();
        double[] sum = new double[dim];
        double[] sumSq = new double[dim];
        for (double[] v : rawVectors.values()) {
            for (int i = 0; i < dim; i++) {
                sum[i] += v[i];
                sumSq[i] += v[i] * v[i];
            }
        }
        double[] mean = new double[dim];
        double[] std = new double[dim];
        for (int i = 0; i < dim; i++) {
            mean[i] = sum[i] / n;
            double variance = sumSq[i] / n - mean[i] * mean[i];
            std[i] = variance > 0 ? Math.sqrt(variance) : 0.0;
        }
        Map<String, double[]> standardized = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : rawVectors.entrySet()) {
            double[] v = e.getValue();
            double[] z = new double[dim];
            for (int i = 0; i < dim; i++) {
                z[i] = std[i] > 0 ? (v[i] - mean[i]) / std[i] : 0.0;
            }
            standardized.put(e.getKey(), z);
        }

        AudienceSegmenter segmenter = new AudienceSegmenter(NUM_TRIBES);
        return segmenter.segmentUsers(standardized);
    }

    private Stream<UniversalPost> streamTable(String tableName) {
        // sentiment_score = 0 is the DB sentinel for "invalid / not usable for the use-case";
        // such rows must be excluded from every downstream metric (MOI, Hawkes alpha, persona features).
        String sql = "SELECT * FROM " + tableName + " WHERE sentiment_score <> 0";
        return jdbcTemplate.queryForStream(sql, (rs, rowNum) -> postMapper.map(rs, tableName));
    }
}
