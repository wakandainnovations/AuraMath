package com.lit.fire.flame;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GenreLookalikeService {

    private static final int DEFAULT_TOP_N = 50;
    private static final int DEFAULT_NUM_FACTORS = 10;
    private static final double DEFAULT_LEARNING_RATE = 0.05;
    private static final double DEFAULT_REGULARIZATION = 0.02;
    private static final int DEFAULT_NUM_EPOCHS = 50;

    private final JdbcTemplate jdbc;
    private final int numFactors;
    private final double learningRate;
    private final double regularization;
    private final int numEpochs;

    public record UntappedPotential(
            String globalUserId,
            String mostActivePlatform,
            double similarity,
            long totalViews,
            int totalPosts) {}

    @Autowired
    public GenreLookalikeService(
            JdbcTemplate jdbc,
            @Value("${lookalike.numFactors:10}")     int numFactors,
            @Value("${lookalike.learningRate:0.05}") double learningRate,
            @Value("${lookalike.regularization:0.02}") double regularization,
            @Value("${lookalike.numEpochs:50}")      int numEpochs) {
        this.jdbc = jdbc;
        this.numFactors = numFactors;
        this.learningRate = learningRate;
        this.regularization = regularization;
        this.numEpochs = numEpochs;
    }

    public List<UntappedPotential> findUntappedPotentials(List<String> seedGlobalUserIds) {
        return findUntappedPotentials(seedGlobalUserIds, DEFAULT_TOP_N);
    }

    public List<UntappedPotential> findUntappedPotentials(List<String> seedGlobalUserIds, int topN) {
        if (seedGlobalUserIds == null || seedGlobalUserIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> seedSet = new HashSet<>(seedGlobalUserIds);

        Map<String, String> identities = loadIdentityIndex();          // normalized_author -> global_user_id
        if (identities.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<String>> interactions = buildInteractions(identities);
        Map<String, Long> viewsByUser = loadViewsByUser(identities);
        Map<String, Map<String, Integer>> platformCounts = loadPlatformCounts(identities);

        PreferenceMatrixFactorizer factorizer = new PreferenceMatrixFactorizer(
                interactions, numFactors, learningRate, regularization, numEpochs);
        factorizer.factorize();

        Map<String, RealVector> userFactors = factorizer.getAllUserFactors();

        RealVector seedCentroid = computeSeedCentroid(seedSet, userFactors);
        if (seedCentroid == null) {
            return Collections.emptyList();
        }

        long seedReachCutoff = medianReach(seedSet, viewsByUser);

        List<UntappedPotential> candidates = new ArrayList<>();
        for (Map.Entry<String, RealVector> entry : userFactors.entrySet()) {
            String userId = entry.getKey();
            if (seedSet.contains(userId)) {
                continue;
            }
            RealVector vec = entry.getValue();
            if (vec == null || vec.getNorm() == 0.0) {
                continue;
            }

            long userViews = viewsByUser.getOrDefault(userId, 0L);
            // "Untapped": similar tastes but smaller audience than the seed cohort.
            // If the seed cohort itself has zero recorded views (e.g. no X presence),
            // the cutoff is 0 — fall through and keep all candidates so the call
            // still returns a useful list rather than being silently empty.
            if (seedReachCutoff > 0 && userViews >= seedReachCutoff) {
                continue;
            }

            double similarity = cosine(vec, seedCentroid);
            Map<String, Integer> counts = platformCounts.getOrDefault(userId, Collections.emptyMap());
            String mostActive = mostActivePlatform(counts);
            int totalPosts = counts.values().stream().mapToInt(Integer::intValue).sum();

            candidates.add(new UntappedPotential(userId, mostActive, similarity, userViews, totalPosts));
        }

        candidates.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        if (candidates.size() > topN) {
            return new ArrayList<>(candidates.subList(0, topN));
        }
        return candidates;
    }

    private Map<String, String> loadIdentityIndex() {
        Map<String, String> index = new HashMap<>();
        jdbc.query("SELECT normalized_author, global_user_id FROM user_identity_link", rs -> {
            index.put(rs.getString("normalized_author"), rs.getString("global_user_id"));
        });
        return index;
    }

    private Map<String, List<String>> buildInteractions(Map<String, String> identities) {
        Map<String, Set<String>> dedup = new HashMap<>();
        String[] tables = {"x_posts", "youtube_comments", "reddit_posts", "instagram_posts"};
        for (String table : tables) {
            String sql = "SELECT author, keyword FROM " + table +
                         " WHERE author IS NOT NULL AND author <> '' AND keyword IS NOT NULL AND keyword <> ''";
            jdbc.query(sql, rs -> {
                String globalUserId = identities.get(normalize(rs.getString("author")));
                if (globalUserId == null) {
                    return;
                }
                String keyword = rs.getString("keyword").trim().toLowerCase();
                if (keyword.isEmpty()) {
                    return;
                }
                dedup.computeIfAbsent(globalUserId, k -> new HashSet<>()).add(keyword);
            });
        }

        Map<String, List<String>> interactions = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : dedup.entrySet()) {
            interactions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return interactions;
    }

    private Map<String, Long> loadViewsByUser(Map<String, String> identities) {
        // Cross-platform reach proxy, matching GenreInterestProfiler:
        //   x_posts          → views_count   (real impressions)
        //   youtube_comments → like_count    (proxy)
        //   reddit_posts     → num_comments  (proxy)
        //   instagram_posts  → like_count    (proxy)
        // Sum is per-author across all four; then mapped to global_user_id.
        String sql =
                "SELECT author, SUM(metric) AS total FROM (" +
                "  SELECT author, COALESCE(views_count, 0)  AS metric FROM x_posts " +
                "    WHERE author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(like_count, 0)   AS metric FROM youtube_comments " +
                "    WHERE author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(num_comments, 0) AS metric FROM reddit_posts " +
                "    WHERE author IS NOT NULL AND author <> '' " +
                "  UNION ALL " +
                "  SELECT author, COALESCE(like_count, 0)   AS metric FROM instagram_posts " +
                "    WHERE author IS NOT NULL AND author <> '' " +
                ") t GROUP BY author";

        Map<String, Long> totals = new HashMap<>();
        jdbc.query(sql, rs -> {
            String globalUserId = identities.get(normalize(rs.getString("author")));
            if (globalUserId == null) {
                return;
            }
            totals.merge(globalUserId, rs.getLong("total"), Long::sum);
        });
        return totals;
    }

    private Map<String, Map<String, Integer>> loadPlatformCounts(Map<String, String> identities) {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        Map<String, String> tableToPlatformLabel = new LinkedHashMap<>();
        tableToPlatformLabel.put("x_posts",          "X");
        tableToPlatformLabel.put("youtube_comments", "YouTube");
        tableToPlatformLabel.put("reddit_posts",     "Reddit");
        tableToPlatformLabel.put("instagram_posts",  "Instagram");

        for (Map.Entry<String, String> entry : tableToPlatformLabel.entrySet()) {
            String table = entry.getKey();
            String label = entry.getValue();
            String sql = "SELECT author, COUNT(*) AS cnt FROM " + table +
                         " WHERE author IS NOT NULL AND author <> '' GROUP BY author";
            jdbc.query(sql, rs -> {
                String globalUserId = identities.get(normalize(rs.getString("author")));
                if (globalUserId == null) {
                    return;
                }
                int count = rs.getInt("cnt");
                counts.computeIfAbsent(globalUserId, k -> new HashMap<>())
                      .merge(label, count, Integer::sum);
            });
        }
        return counts;
    }

    private RealVector computeSeedCentroid(Set<String> seedSet, Map<String, RealVector> userFactors) {
        RealVector sum = null;
        int found = 0;
        for (String seed : seedSet) {
            RealVector v = userFactors.get(seed);
            if (v == null) {
                continue;
            }
            if (sum == null) {
                sum = new ArrayRealVector(v.toArray());
            } else {
                sum = sum.add(v);
            }
            found++;
        }
        if (found == 0 || sum == null) {
            return null;
        }
        return sum.mapDivide(found);
    }

    private long medianReach(Set<String> seedSet, Map<String, Long> viewsByUser) {
        long[] reach = seedSet.stream()
                .mapToLong(id -> viewsByUser.getOrDefault(id, 0L))
                .toArray();
        if (reach.length == 0) {
            return 0L;
        }
        Arrays.sort(reach);
        int mid = reach.length / 2;
        if (reach.length % 2 == 1) {
            return reach[mid];
        }
        return (reach[mid - 1] + reach[mid]) / 2;
    }

    private String mostActivePlatform(Map<String, Integer> platformCounts) {
        if (platformCounts.isEmpty()) {
            return "Unknown";
        }
        return platformCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");
    }

    private static double cosine(RealVector a, RealVector b) {
        double normA = a.getNorm();
        double normB = b.getNorm();
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return a.dotProduct(b) / (normA * normB);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
    }
}
