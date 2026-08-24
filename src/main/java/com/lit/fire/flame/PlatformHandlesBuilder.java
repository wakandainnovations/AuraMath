package com.lit.fire.flame;

import com.google.gson.Gson;
import com.lit.fire.flame.models.UniversalPost;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the {@code platform_handles} JSON blob (profile URLs, per-platform post counts) for one
 * author from their posts. Pulled out of {@link MarketingEnrichmentEngine} so it can also be
 * driven by {@link PlatformHandlesRefreshService} without paying for that engine's NLP/Hawkes/
 * tribe-clustering passes — those are unrelated to identity/profile attribution and are what make
 * a full enrichment run slow.
 */
final class PlatformHandlesBuilder {

    private static final Gson gson = new Gson();

    private PlatformHandlesBuilder() {}

    static String build(String authorId, List<UniversalPost> posts) {
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
}
