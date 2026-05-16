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

    private static final String[] PLATFORM_TABLES = {"x_posts", "youtube_comments", "reddit_posts", "instagram_posts"};
    private static final int NUM_TRIBES = 12;
    private static final int CLUSTERING_TOP_ASPECTS_K = 100;

    private final Gson gson = new Gson();

    private record PersonaData(double moi, double alpha, Map<String, Double> aspects) {}

    public void enrichAndSave() {
        // Resolve cross-platform identities first so downstream lookups against
        // user_identity_link see every author present in the source tables.
        crossPlatformIdentityResolver.resolveIdentities();

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

            marketingInsightsRepository.upsertUserPersonaProfile(profile, platformHandles, peakActivityTimes, d.moi());
        }
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
        String sql = "SELECT * FROM " + tableName;
        return jdbcTemplate.queryForStream(sql, (rs, rowNum) -> postMapper.map(rs, tableName));
    }
}
