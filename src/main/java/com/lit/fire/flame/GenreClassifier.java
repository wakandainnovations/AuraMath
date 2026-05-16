package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class GenreClassifier {

    public record GenreLabel(String genre, double weight) {}

    private static final double MIN_SCORE_THRESHOLD = 1.0;
    private static final double KEYWORD_FIELD_WEIGHT = 2.0;
    private static final double REDDIT_TITLE_WEIGHT = 1.5;
    private static final double INSTAGRAM_VIDEO_ACTION_BOOST = 1.5;
    private static final double INSTAGRAM_CAROUSEL_BOOST = 1.1;

    private final Map<String, List<String>> genreKeywords;
    private final Map<String, List<Pattern>> compiledPatterns;

    public GenreClassifier() {
        this.genreKeywords = buildDefaultKeywordMap();
        this.compiledPatterns = compilePatterns(this.genreKeywords);
    }

    public GenreClassifier(Map<String, List<String>> genreKeywords) {
        this.genreKeywords = genreKeywords;
        this.compiledPatterns = compilePatterns(genreKeywords);
    }

    public List<GenreLabel> classifyPost(UniversalPost post) {
        if (post == null) {
            return List.of();
        }

        Map<String, Object> metadata = post.getMetadata() != null ? post.getMetadata() : Map.of();
        String platform = post.getPlatform() == null ? "" : post.getPlatform();

        String content = nullSafe(post.getContent());
        String keywordField = nullSafe(stringFromMetadata(metadata, "keyword"));
        String redditTitle = nullSafe(stringFromMetadata(metadata, "title"));
        String instagramMediaType = nullSafe(stringFromMetadata(metadata, "media_type")).toUpperCase();

        Map<String, Double> genreScores = new HashMap<>();

        scoreText(content, 1.0, genreScores);
        scoreText(keywordField, KEYWORD_FIELD_WEIGHT, genreScores);

        if ("reddit_posts".equals(platform) && !redditTitle.isEmpty()) {
            scoreText(redditTitle, REDDIT_TITLE_WEIGHT, genreScores);
        }

        if ("instagram_posts".equals(platform)) {
            applyInstagramMediaTypeBoost(instagramMediaType, genreScores);
        }

        return genreScores.entrySet().stream()
                .filter(entry -> entry.getValue() >= MIN_SCORE_THRESHOLD)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> new GenreLabel(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void scoreText(String text, double weight, Map<String, Double> genreScores) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (Map.Entry<String, List<Pattern>> entry : compiledPatterns.entrySet()) {
            String genre = entry.getKey();
            int hits = 0;
            for (Pattern pattern : entry.getValue()) {
                var matcher = pattern.matcher(text);
                while (matcher.find()) {
                    hits++;
                }
            }
            if (hits > 0) {
                genreScores.merge(genre, hits * weight, Double::sum);
            }
        }
    }

    private void applyInstagramMediaTypeBoost(String mediaType, Map<String, Double> genreScores) {
        if (mediaType.isEmpty()) {
            return;
        }
        if ("VIDEO".equals(mediaType) || "REEL".equals(mediaType) || "REELS".equals(mediaType)) {
            genreScores.computeIfPresent("Action",
                    (genre, score) -> score * INSTAGRAM_VIDEO_ACTION_BOOST);
            genreScores.computeIfPresent("Sports",
                    (genre, score) -> score * INSTAGRAM_VIDEO_ACTION_BOOST);
            genreScores.computeIfPresent("Music",
                    (genre, score) -> score * INSTAGRAM_VIDEO_ACTION_BOOST);
        } else if ("CAROUSEL_ALBUM".equals(mediaType) || "CAROUSEL".equals(mediaType)) {
            genreScores.replaceAll((genre, score) -> score * INSTAGRAM_CAROUSEL_BOOST);
        }
    }

    private static Map<String, List<String>> buildDefaultKeywordMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Horror", Arrays.asList(
                "scary", "ghost", "haunted", "zombie", "vampire", "demon",
                "creepy", "nightmare", "terror", "evil", "possession", "exorcist"));
        map.put("Sci-Fi", Arrays.asList(
                "spaceship", "alien", "robot", "galaxy", "cyborg",
                "dystopia", "time travel", "interstellar", "wormhole", "android",
                "starship", "extraterrestrial"));
        map.put("Action", Arrays.asList(
                "fight", "explosion", "chase", "battle", "war", "gun",
                "shootout", "stunt", "combat", "missile", "ambush", "warrior"));
        map.put("Comedy", Arrays.asList(
                "funny", "joke", "hilarious", "laugh", "lol", "comedy",
                "humor", "satire", "sitcom", "rofl", "lmao"));
        map.put("Drama", Arrays.asList(
                "emotional", "cry", "tragedy", "heartbreak", "betrayal",
                "tearjerker", "intense", "family drama"));
        map.put("Romance", Arrays.asList(
                "love", "kiss", "date", "romance", "wedding", "crush",
                "valentine", "boyfriend", "girlfriend", "soulmate"));
        map.put("Fantasy", Arrays.asList(
                "magic", "dragon", "wizard", "elf", "kingdom", "sorcerer",
                "spell", "enchanted", "mythical", "quest", "fairy"));
        map.put("Sports", Arrays.asList(
                "goal", "score", "team", "championship", "player", "match",
                "tournament", "league", "coach", "stadium", "olympics"));
        map.put("Music", Arrays.asList(
                "song", "album", "concert", "band", "artist", "tour",
                "lyrics", "playlist", "remix", "single", "guitar", "vocals"));
        map.put("Tech", Arrays.asList(
                "coding", "programming", "software", "gadget", "app",
                "developer", "ai", "machine learning", "startup", "framework",
                "javascript", "python", "api"));
        map.put("Thriller", Arrays.asList(
                "suspense", "mystery", "killer", "detective", "crime",
                "investigation", "conspiracy", "kidnap", "thriller"));
        map.put("Documentary", Arrays.asList(
                "documentary", "interview", "behind the scenes", "real story",
                "true events", "expose"));
        return map;
    }

    private static Map<String, List<Pattern>> compilePatterns(Map<String, List<String>> source) {
        Map<String, List<Pattern>> compiled = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            List<Pattern> patterns = new ArrayList<>(entry.getValue().size());
            for (String keyword : entry.getValue()) {
                patterns.add(Pattern.compile(
                        "\\b" + Pattern.quote(keyword.toLowerCase()) + "\\b",
                        Pattern.CASE_INSENSITIVE));
            }
            compiled.put(entry.getKey(), patterns);
        }
        return compiled;
    }

    private static String stringFromMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
