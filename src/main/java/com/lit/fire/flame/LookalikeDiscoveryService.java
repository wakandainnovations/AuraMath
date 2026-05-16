package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.opencsv.CSVWriter;
import org.apache.commons.math3.linear.RealVector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class LookalikeDiscoveryService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private PreferenceMatrixFactorizer preferenceMatrixFactorizer;

    private final Gson gson = new Gson();

    private static final int TOP_ASPECTS_K = 500;

    private static final int MIN_POST_COUNT = 5;

    public void discoverAndReport(String seedAuthorId, String outputCsvPath) throws IOException {
        Map<String, RealVector> allUserFactors = preferenceMatrixFactorizer.getAllUserFactors();

        Map<String, Double> moiScores = new HashMap<>();
        Map<String, String> platformHandlesMap = new HashMap<>();

        jdbcTemplate.query(
                "SELECT global_user_id, moi_score, platform_handles FROM marketing_target_profiles",
                (rs) -> {
                    while (rs.next()) {
                        moiScores.put(rs.getString("global_user_id"), rs.getDouble("moi_score"));
                        platformHandlesMap.put(rs.getString("global_user_id"), rs.getString("platform_handles"));
                    }
                    return null;
                }
        );

        RealVector seedVector = getCombinedFeatureVector(seedAuthorId, allUserFactors, moiScores);
        if (seedVector == null) {
            throw new IllegalArgumentException("Invalid seedAuthorId");
        }

        List<Map.Entry<String, Double>> similarUsers = allUserFactors.keySet().stream()
                .filter(userId -> !userId.equals(seedAuthorId))
                .map(userId -> {
                    RealVector userVector = getCombinedFeatureVector(userId, allUserFactors, moiScores);
                    if (userVector == null) {
                        return null;
                    }
                    return Map.entry(userId, userVector.getDistance(seedVector));
                })
                .filter(Objects::nonNull)
                .sorted(Map.Entry.comparingByValue())
                .limit(50)
                .toList();

        List<String[]> csvData = new ArrayList<>();
        csvData.add(new String[]{"permalink", "platform"});

        Type rootType = new TypeToken<Map<String, Object>>() {}.getType();
        for (Map.Entry<String, Double> user : similarUsers) {
            String userId = user.getKey();
            String platformHandlesJson = platformHandlesMap.get(userId);
            if (platformHandlesJson == null || platformHandlesJson.isEmpty()) continue;

            Map<String, Object> root = gson.fromJson(platformHandlesJson, rootType);
            if (root == null) continue;

            String primary = root.get("primary_platform") instanceof String s ? s : null;
            Object byPlatformObj = root.get("by_platform");
            if (!(byPlatformObj instanceof Map<?, ?> byPlatform) || byPlatform.isEmpty()) continue;

            Object entryObj = primary != null ? byPlatform.get(primary) : byPlatform.values().iterator().next();
            if (!(entryObj instanceof Map<?, ?> entry)) continue;

            Object profileUrl = entry.get("profile_url");
            csvData.add(new String[]{
                    profileUrl == null ? "" : profileUrl.toString(),
                    primary == null ? "" : primary
            });
        }

        try (CSVWriter writer = new CSVWriter(new FileWriter(outputCsvPath))) {
            writer.writeAll(csvData);
        }
    }

    private RealVector getCombinedFeatureVector(String userId, Map<String, RealVector> allUserFactors, Map<String, Double> moiScores) {
        RealVector latentFactors = allUserFactors.get(userId);
        if (latentFactors == null) {
            return null;
        }
        double moiScore = moiScores.getOrDefault(userId, 0.0);
        return latentFactors.append(moiScore);
    }

    /**
     * Finds up to {@code limit} authors who belong to the same Tribe as the seed and have the
     * most similar cross-platform sentiment_category histories across X, Reddit, and Instagram.
     *
     * Feature vector per author:
     *   [ moi_score | aspect sentiment scores... | X sentiment dist... | Reddit sentiment dist... | Instagram sentiment dist... ]
     *
     * Similarity is measured as L2 distance over these vectors; same-tribe filtering is applied
     * before ranking so that only tribal peers are considered.
     */
    public List<Map<String, Object>> findLookalikes(String seedAuthorId, int limit) {
        Type mapType = new TypeToken<Map<String, Double>>() {}.getType();

        List<Map<String, Object>> profiles = jdbcTemplate.queryForList(
                "SELECT global_user_id, tribe_label, moi_score, top_genres, platform_handles " +
                "FROM marketing_target_profiles");

        if (profiles.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Map<String, Object>> profileMap = new HashMap<>();
        for (Map<String, Object> p : profiles) {
            profileMap.put((String) p.get("global_user_id"), p);
        }

        if (!profileMap.containsKey(seedAuthorId)) {
            throw new IllegalArgumentException("Unknown seedAuthorId: " + seedAuthorId);
        }

        String seedTribe = (String) profileMap.get(seedAuthorId).get("tribe_label");

        Map<String, Map<String, Double>> xSentiments = loadSentimentDistributions("x_posts", "author");
        Map<String, Map<String, Double>> redditSentiments = loadSentimentDistributions("reddit_posts", "author");
        Map<String, Map<String, Double>> instaSentiments = loadSentimentDistributions("instagram_posts", "author");

        // Collect stable-ordered dimension keys so every vector has the same layout
        Set<String> allCategories = new LinkedHashSet<>();
        Stream.of(xSentiments, redditSentiments, instaSentiments)
                .flatMap(m -> m.values().stream())
                .flatMap(m -> m.keySet().stream())
                .forEach(allCategories::add);
        List<String> categoryList = new ArrayList<>(allCategories);

        // Feature selection: keep the TOP_ASPECTS_K aspect keys that appear in the
        // most profiles. The raw aspect vocabulary is ~95k nouns extracted from post
        // text, most appearing in only 1-2 authors, which adds noise and dimensionality
        // without signal. Restricting to the most common keys keeps the feature vector
        // tractable and concentrates distance on shared vocabulary.
        Map<String, Integer> aspectCounts = new HashMap<>();
        for (Map<String, Object> p : profiles) {
            Object topGenresRaw = p.get("top_genres");
            String json = topGenresRaw == null ? null : topGenresRaw.toString();
            if (json != null && !json.isEmpty()) {
                Map<String, Double> aspects = gson.fromJson(json, mapType);
                if (aspects != null) {
                    for (String k : aspects.keySet()) {
                        aspectCounts.merge(k, 1, Integer::sum);
                    }
                }
            }
        }
        List<String> aspectList = aspectCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_ASPECTS_K)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Z-score each feature dimension across the full population so MOI (~0-3),
        // sentiment distributions (~0-1), and top_genres aspect scores (~0-100)
        // contribute comparably to L2 distance. Vector dim is ~95k (one per distinct
        // top_genres aspect key), so we accumulate stats incrementally instead of
        // materializing all 40k vectors at once.
        int n = profiles.size();

        // Pass 1: build each vector, accumulate per-dim sum and sum-of-squares,
        // remember only the seed vector, discard the rest.
        double[] seedRaw = null;
        double[] sum = null;
        double[] sumSq = null;
        for (int i = 0; i < n; i++) {
            double[] v = buildFeatureVector(
                    profiles.get(i), xSentiments, redditSentiments, instaSentiments,
                    categoryList, aspectList, mapType);
            if (sum == null) {
                sum = new double[v.length];
                sumSq = new double[v.length];
            }
            for (int j = 0; j < v.length; j++) {
                sum[j] += v[j];
                sumSq[j] += v[j] * v[j];
            }
            if (seedAuthorId.equals(profiles.get(i).get("global_user_id"))) {
                seedRaw = v;
            }
        }

        int dim = sum.length;
        double[] means = new double[dim];
        double[] stds = new double[dim];
        for (int j = 0; j < dim; j++) {
            means[j] = sum[j] / n;
            double variance = sumSq[j] / n - means[j] * means[j];
            stds[j] = variance > 0 ? Math.sqrt(variance) : 0.0;
        }

        double[] seedVector = new double[dim];
        for (int j = 0; j < dim; j++) {
            seedVector[j] = stds[j] > 0 ? (seedRaw[j] - means[j]) / stds[j] : 0.0;
        }

        // Pass 2: rebuild each candidate, standardize in place, compute distance.
        Type rootType = new TypeToken<Map<String, Object>>() {}.getType();
        List<Map.Entry<String, Double>> distances = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = profiles.get(i);
            String userId = (String) p.get("global_user_id");
            if (userId.equals(seedAuthorId)) continue;
            if (!Objects.equals(p.get("tribe_label"), seedTribe)) continue;
            if (totalPostCount(p.get("platform_handles"), rootType) < MIN_POST_COUNT) continue;
            double[] v = buildFeatureVector(
                    p, xSentiments, redditSentiments, instaSentiments,
                    categoryList, aspectList, mapType);
            for (int j = 0; j < dim; j++) {
                v[j] = stds[j] > 0 ? (v[j] - means[j]) / stds[j] : 0.0;
            }
            distances.add(Map.entry(userId, computeL2Distance(seedVector, v)));
        }

        distances.sort(Map.Entry.comparingByValue());

        return distances.stream()
                .limit(limit)
                .map(e -> {
                    Map<String, Object> p = profileMap.get(e.getKey());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("global_user_id", e.getKey());
                    result.put("tribe_label", p.get("tribe_label"));
                    result.put("moi_score", p.get("moi_score"));
                    result.put("platform_handles", JsonbUtil.asTree(p.get("platform_handles"), gson));
                    result.put("similarity_score", 1.0 / (1.0 + e.getValue()));
                    return result;
                })
                .collect(Collectors.toList());
    }

    private Map<String, Map<String, Double>> loadSentimentDistributions(String table, String authorColumn) {
        Map<String, Map<String, Double>> raw = new HashMap<>();
        String sql = "SELECT " + authorColumn + ", sentiment_category, COUNT(*) AS cnt" +
                     " FROM " + table +
                     " WHERE sentiment_category IS NOT NULL" +
                     " GROUP BY " + authorColumn + ", sentiment_category";

        jdbcTemplate.query(sql, rs -> {
            String author = rs.getString(1);
            String category = rs.getString(2);
            double count = rs.getLong(3);
            raw.computeIfAbsent(author, k -> new HashMap<>()).put(category, count);
        });

        // Normalize counts to relative frequencies within each author
        for (Map<String, Double> categoryMap : raw.values()) {
            double total = categoryMap.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total > 0) categoryMap.replaceAll((k, v) -> v / total);
        }
        return raw;
    }

    private double[] buildFeatureVector(Map<String, Object> profile,
                                        Map<String, Map<String, Double>> xSentiments,
                                        Map<String, Map<String, Double>> redditSentiments,
                                        Map<String, Map<String, Double>> instaSentiments,
                                        List<String> categoryList,
                                        List<String> aspectList,
                                        Type mapType) {
        String userId = (String) profile.get("global_user_id");
        double[] vector = new double[1 + aspectList.size() + 3 * categoryList.size()];
        int idx = 0;

        Object moiObj = profile.get("moi_score");
        vector[idx++] = moiObj instanceof Number ? ((Number) moiObj).doubleValue() : 0.0;

        Object topGenresRaw = profile.get("top_genres");
        String topGenresJson = topGenresRaw == null ? null : topGenresRaw.toString();
        Map<String, Double> aspects = Collections.emptyMap();
        if (topGenresJson != null && !topGenresJson.isEmpty()) {
            Map<String, Double> parsed = gson.fromJson(topGenresJson, mapType);
            if (parsed != null) aspects = parsed;
        }
        for (String aspect : aspectList) {
            vector[idx++] = aspects.getOrDefault(aspect, 0.0);
        }

        Map<String, Double> xDist = xSentiments.getOrDefault(userId, Collections.emptyMap());
        for (String cat : categoryList) vector[idx++] = xDist.getOrDefault(cat, 0.0);

        Map<String, Double> redditDist = redditSentiments.getOrDefault(userId, Collections.emptyMap());
        for (String cat : categoryList) vector[idx++] = redditDist.getOrDefault(cat, 0.0);

        Map<String, Double> instaDist = instaSentiments.getOrDefault(userId, Collections.emptyMap());
        for (String cat : categoryList) vector[idx++] = instaDist.getOrDefault(cat, 0.0);

        return vector;
    }

    private int totalPostCount(Object platformHandlesObj, Type rootType) {
        String json = platformHandlesObj == null ? null : platformHandlesObj.toString();
        if (json == null || json.isEmpty()) return 0;
        Map<String, Object> root = gson.fromJson(json, rootType);
        if (root == null) return 0;
        if (!(root.get("by_platform") instanceof Map<?, ?> byPlatform)) return 0;
        int total = 0;
        for (Object entry : byPlatform.values()) {
            if (entry instanceof Map<?, ?> m && m.get("post_count") instanceof Number pc) {
                total += pc.intValue();
            }
        }
        return total;
    }

    private double computeL2Distance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
