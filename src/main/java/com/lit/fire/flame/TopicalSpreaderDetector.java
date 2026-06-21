package com.lit.fire.flame;

import com.lit.fire.flame.GenreClassifier.GenreLabel;
import com.lit.fire.flame.HawkesIntensityCalculator.HawkesParameters;
import com.lit.fire.flame.models.UniversalPost;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TopicalSpreaderDetector {

    private static final double DEFAULT_TOP_PERCENTILE = 95.0;
    private static final int MIN_POSTS_FOR_HAWKES = 2;

    private final JdbcTemplate jdbc;
    private final GenreClassifier classifier;
    private final double beta;

    public record SpreaderResult(String author, double alpha, double mu, int postCount) {}

    @Autowired
    public TopicalSpreaderDetector(JdbcTemplate jdbc,
                                   GenreClassifier classifier,
                                   @Value("${hawkes.beta:3.0}") double beta) {
        this.jdbc = jdbc;
        this.classifier = classifier;
        this.beta = beta;
    }

    public List<SpreaderResult> findGenreSuperSpreaders(GenreLabel genre) {
        return findGenreSuperSpreaders(genre, DEFAULT_TOP_PERCENTILE);
    }

    public List<SpreaderResult> findGenreSuperSpreaders(GenreLabel genre, double topPercentile) {
        if (genre == null || genre.genre() == null || genre.genre().isBlank()) {
            return Collections.emptyList();
        }

        Map<String, List<UniversalPost>> postsByAuthor = loadAndFilterByGenre(genre.genre());
        if (postsByAuthor.isEmpty()) {
            return Collections.emptyList();
        }

        List<SpreaderResult> alphaResults = computeAlphas(postsByAuthor);
        if (alphaResults.isEmpty()) {
            return Collections.emptyList();
        }

        double cutoff = percentileCutoff(alphaResults, topPercentile);

        List<SpreaderResult> spreaders = new ArrayList<>();
        for (SpreaderResult r : alphaResults) {
            if (r.alpha() >= cutoff) {
                spreaders.add(r);
            }
        }
        spreaders.sort((a, b) -> Double.compare(b.alpha(), a.alpha()));
        return spreaders;
    }

    /**
     * Returns every qualifying author for {@code genre} sorted by genre-scoped Hawkes
     * alpha descending, with no percentile cutoff. Callers apply their own limit.
     * Alpha is estimated only from posts that the GenreClassifier tagged with this genre,
     * so the score reflects spreading influence within the genre, not overall posting activity.
     */
    public List<SpreaderResult> rankBySpreading(GenreLabel genre) {
        if (genre == null || genre.genre() == null || genre.genre().isBlank()) {
            return Collections.emptyList();
        }
        Map<String, List<UniversalPost>> postsByAuthor = loadAndFilterByGenre(genre.genre());
        List<SpreaderResult> results = computeAlphas(postsByAuthor);
        results.sort((a, b) -> Double.compare(b.alpha(), a.alpha()));
        return results;
    }

    public Map<String, Double> computeAlphaByAuthor(GenreLabel genre) {
        Map<String, List<UniversalPost>> postsByAuthor = loadAndFilterByGenre(genre.genre());
        Map<String, Double> result = new HashMap<>();
        for (SpreaderResult r : computeAlphas(postsByAuthor)) {
            result.put(r.author(), r.alpha());
        }
        return result;
    }

    private List<SpreaderResult> computeAlphas(Map<String, List<UniversalPost>> postsByAuthor) {
        // The Stream-based estimator does not touch the JDBC connection.
        HawkesIntensityCalculator hawkes = new HawkesIntensityCalculator(null, beta);

        List<SpreaderResult> results = new ArrayList<>();
        for (Map.Entry<String, List<UniversalPost>> entry : postsByAuthor.entrySet()) {
            List<UniversalPost> posts = entry.getValue();
            if (posts.size() < MIN_POSTS_FOR_HAWKES) {
                continue;
            }
            HawkesParameters params = hawkes.estimateParameters(posts.stream());
            results.add(new SpreaderResult(entry.getKey(), params.alpha, params.mu, posts.size()));
        }
        return results;
    }

    private Map<String, List<UniversalPost>> loadAndFilterByGenre(String genreName) {
        Map<String, List<UniversalPost>> postsByAuthor = new HashMap<>();
        addMatchingPosts(postsByAuthor, genreName, fetchXPosts());
        addMatchingPosts(postsByAuthor, genreName, fetchYoutubeComments());
        addMatchingPosts(postsByAuthor, genreName, fetchRedditPosts());
        addMatchingPosts(postsByAuthor, genreName, fetchInstagramPosts());
        return postsByAuthor;
    }

    private void addMatchingPosts(Map<String, List<UniversalPost>> postsByAuthor,
                                  String genreName,
                                  List<UniversalPost> posts) {
        for (UniversalPost post : posts) {
            if (post.getAuthorId() == null || post.getAuthorId().isBlank()) {
                continue;
            }
            if (post.getTimestamp() == null) {
                continue;
            }
            for (GenreLabel label : classifier.classifyPost(post)) {
                if (genreName.equalsIgnoreCase(label.genre())) {
                    postsByAuthor
                            .computeIfAbsent(post.getAuthorId(), k -> new ArrayList<>())
                            .add(post);
                    break;
                }
            }
        }
    }

    private List<UniversalPost> fetchXPosts() {
        String sql = "SELECT id, author, text, keyword, created_at " +
                     "FROM x_posts WHERE author IS NOT NULL AND author <> '' AND sentiment_score BETWEEN 1 AND 100";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            return new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    rs.getString("text"),
                    toLocalDateTime(rs.getTimestamp("created_at")),
                    "x_posts",
                    metadata);
        });
    }

    private List<UniversalPost> fetchYoutubeComments() {
        String sql = "SELECT id, author, text, keyword, published_at " +
                     "FROM youtube_comments WHERE author IS NOT NULL AND author <> '' AND sentiment_category IS NOT NULL";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            return new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    rs.getString("text"),
                    toLocalDateTime(rs.getTimestamp("published_at")),
                    "youtube_comments",
                    metadata);
        });
    }

    private List<UniversalPost> fetchRedditPosts() {
        String sql = "SELECT id, author, title, text, keyword, created_at " +
                     "FROM reddit_posts WHERE author IS NOT NULL AND author <> '' AND sentiment_category IS NOT NULL";
        return jdbc.query(sql, (rs, rowNum) -> {
            String title = rs.getString("title") == null ? "" : rs.getString("title");
            String body = rs.getString("text") == null ? "" : rs.getString("text");
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            metadata.put("title", title);
            return new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    (title + " " + body).trim(),
                    toLocalDateTime(rs.getTimestamp("created_at")),
                    "reddit_posts",
                    metadata);
        });
    }

    private List<UniversalPost> fetchInstagramPosts() {
        String sql = "SELECT id, author, text, keyword, media_type, timestamp " +
                     "FROM instagram_posts WHERE author IS NOT NULL AND author <> '' AND sentiment_category IS NOT NULL";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyword", rs.getString("keyword"));
            metadata.put("media_type", rs.getString("media_type"));
            return new UniversalPost(
                    rs.getString("id"),
                    rs.getString("author"),
                    rs.getString("text"),
                    toLocalDateTime(rs.getTimestamp("timestamp")),
                    "instagram_posts",
                    metadata);
        });
    }

    private static double percentileCutoff(List<SpreaderResult> results, double percentile) {
        double[] alphas = new double[results.size()];
        for (int i = 0; i < results.size(); i++) {
            alphas[i] = results.get(i).alpha();
        }
        Percentile p = new Percentile().withEstimationType(Percentile.EstimationType.R_7);
        return p.evaluate(alphas, percentile);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
}
