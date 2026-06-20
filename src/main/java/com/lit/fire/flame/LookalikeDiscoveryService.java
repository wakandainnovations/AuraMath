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

    // --- Block-wise similarity prototype weights (must sum to 1.0) ---
    // Each block is scored in its own natural geometry and combined as a convex
    // combination. Weights are renormalized over whichever blocks are *defined*
    // for a given seed/candidate pair, so the result always stays in [0, 1].
    private static final double W_TOPIC = 0.50; // TF-IDF cosine over top_genres aspects
    private static final double W_SENT  = 0.30; // Hellinger similarity over sentiment distributions
    private static final double W_MOI   = 0.20; // Gaussian kernel on |Δ moi_score|

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
     * LEGACY similarity (superseded by {@link #findLookalikes}). Concatenates
     * moi_score, aspect scores, and per-platform sentiment distributions into one
     * z-scored vector and ranks by L2 distance, reporting 1/(1+distance).
     *
     * Retained only so {@link #diffLookalikes} can compare against the current
     * method. Its scores concentrate near 0.013 in this ~600-dim space and are not
     * meaningfully displayable; prefer {@link #findLookalikes} for production use.
     */
    public List<Map<String, Object>> findLookalikesL2Legacy(String seedAuthorId, int limit) {
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

        seedAuthorId = resolveSeedAuthorId(profiles, seedAuthorId);

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

    // ------------------------------------------------------------------
    // Block-wise similarity (production path)
    //
    // Instead of concatenating MOI + aspects + sentiment into one z-scored
    // Euclidean vector (which forces three different kinds of objects into a
    // single geometry and lets dimensionality decide each block's weight), we
    // score each block in its own geometry and combine with explicit weights:
    //   - topics    : TF-IDF weighting + cosine  (sparse, no z-scoring)
    //   - sentiment : Hellinger similarity per platform, averaged
    //   - moi       : Gaussian kernel on the gap
    // Every block similarity lands in [0, 1], so the combined score is a
    // genuine, displayable similarity rather than 1/(1+L2) on a ~600-dim vector.
    // ------------------------------------------------------------------
    public List<Map<String, Object>> findLookalikes(String seedAuthorId, int limit) {
        Type mapType = new TypeToken<Map<String, Double>>() {}.getType();
        Type rootType = new TypeToken<Map<String, Object>>() {}.getType();

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
        seedAuthorId = resolveSeedAuthorId(profiles, seedAuthorId);
        Map<String, Object> seedProfile = profileMap.get(seedAuthorId);
        String seedTribe = (String) seedProfile.get("tribe_label");

        Map<String, Map<String, Double>> xSentiments = loadSentimentDistributions("x_posts", "author");
        Map<String, Map<String, Double>> redditSentiments = loadSentimentDistributions("reddit_posts", "author");
        Map<String, Map<String, Double>> instaSentiments = loadSentimentDistributions("instagram_posts", "author");

        // --- IDF over the FULL aspect vocabulary (one pass). Sparse, so no need
        // to cap at TOP_ASPECTS_K: terms everyone shares get down-weighted, rare
        // distinctive terms get up-weighted. Smoothed like sklearn's TfidfTransformer.
        int n = profiles.size();
        Map<String, Integer> docFreq = new HashMap<>();
        for (Map<String, Object> p : profiles) {
            for (String t : parseAspects(p.get("top_genres"), mapType).keySet()) {
                docFreq.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : docFreq.entrySet()) {
            idf.put(e.getKey(), Math.log((double) (n + 1) / (e.getValue() + 1)) + 1.0);
        }

        // MOI spread for the Gaussian kernel bandwidth.
        double moiStd = populationStd(profiles, "moi_score");
        double moiSigma = moiStd > 1e-9 ? moiStd : 1.0;

        // --- Seed block representations ---
        Map<String, Double> seedAspectW = weightedAspects(seedProfile.get("top_genres"), idf, mapType);
        double seedAspectNorm = l2Norm(seedAspectW.values());
        Map<String, Double> seedX = xSentiments.getOrDefault(seedAuthorId, Collections.emptyMap());
        Map<String, Double> seedReddit = redditSentiments.getOrDefault(seedAuthorId, Collections.emptyMap());
        Map<String, Double> seedInsta = instaSentiments.getOrDefault(seedAuthorId, Collections.emptyMap());
        double seedMoi = asDouble(seedProfile.get("moi_score"));

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> p : profiles) {
            String userId = (String) p.get("global_user_id");
            if (userId.equals(seedAuthorId)) continue;
            if (!Objects.equals(p.get("tribe_label"), seedTribe)) continue;
            if (totalPostCount(p.get("platform_handles"), rootType) < MIN_POST_COUNT) continue;

            // Block 1: topics — TF-IDF cosine (defined only if both sides have aspects)
            double topicSim = Double.NaN;
            Map<String, Double> candAspectW = weightedAspects(p.get("top_genres"), idf, mapType);
            double candAspectNorm = l2Norm(candAspectW.values());
            if (seedAspectNorm > 0 && candAspectNorm > 0) {
                double dot = 0.0;
                // iterate the smaller map for the shared-term dot product
                Map<String, Double> small = seedAspectW.size() <= candAspectW.size() ? seedAspectW : candAspectW;
                Map<String, Double> large = small == seedAspectW ? candAspectW : seedAspectW;
                for (Map.Entry<String, Double> e : small.entrySet()) {
                    Double o = large.get(e.getKey());
                    if (o != null) dot += e.getValue() * o;
                }
                topicSim = dot / (seedAspectNorm * candAspectNorm);
            }

            // Block 2: sentiment — Hellinger similarity per platform, averaged over
            // platforms where BOTH authors have a distribution.
            double sentSum = 0.0;
            int sentCount = 0;
            double[][] pairs = {
                hellingerSim(seedX, xSentiments.getOrDefault(userId, Collections.emptyMap())),
                hellingerSim(seedReddit, redditSentiments.getOrDefault(userId, Collections.emptyMap())),
                hellingerSim(seedInsta, instaSentiments.getOrDefault(userId, Collections.emptyMap())),
            };
            for (double[] pair : pairs) {
                if (pair[1] > 0) { sentSum += pair[0]; sentCount++; }
            }
            double sentSim = sentCount > 0 ? sentSum / sentCount : Double.NaN;

            // Block 3: moi — always defined.
            double dMoi = seedMoi - asDouble(p.get("moi_score"));
            double moiSim = Math.exp(-(dMoi * dMoi) / (2.0 * moiSigma * moiSigma));

            // Convex combination over the blocks that are defined for this pair.
            double wSum = 0.0, acc = 0.0;
            if (!Double.isNaN(topicSim)) { acc += W_TOPIC * topicSim; wSum += W_TOPIC; }
            if (!Double.isNaN(sentSim))  { acc += W_SENT  * sentSim;  wSum += W_SENT;  }
            acc += W_MOI * moiSim; wSum += W_MOI;
            double similarity = wSum > 0 ? acc / wSum : 0.0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("global_user_id", userId);
            result.put("tribe_label", p.get("tribe_label"));
            result.put("moi_score", p.get("moi_score"));
            result.put("platform_handles", JsonbUtil.asTree(p.get("platform_handles"), gson));
            result.put("similarity_score", similarity);
            result.put("topic_sim", Double.isNaN(topicSim) ? null : topicSim);
            result.put("sentiment_sim", Double.isNaN(sentSim) ? null : sentSim);
            result.put("moi_sim", moiSim);
            scored.add(result);
        }

        scored.sort((a, b) -> Double.compare(
                (double) b.get("similarity_score"), (double) a.get("similarity_score")));
        return scored.stream().limit(limit).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Seed resolution
    //
    // marketing_target_profiles is keyed by the raw author display string (the
    // same value stored in the source tables' `author` column), and every
    // downstream join in this service correlates on that exact string. Callers,
    // however, rarely have the byte-exact display name — they pass a slightly
    // different casing, stray whitespace, an '@' prefix, or punctuation. We try
    // an exact match first (fast path, preserves the stored key for the joins),
    // then fall back to the same normalization CrossPlatformIdentityResolver uses
    // so trivial variants still resolve to their canonical stored key.
    // ------------------------------------------------------------------
    // Package-private for direct unit testing — depends only on the profiles list, not the DB.
    String resolveSeedAuthorId(List<Map<String, Object>> profiles, String seedAuthorId) {
        // Fast path: the caller already supplied the exact stored key.
        for (Map<String, Object> p : profiles) {
            if (seedAuthorId.equals(p.get("global_user_id"))) {
                return seedAuthorId;
            }
        }

        // Fallback: match on the normalized form against the stored keys.
        String normalizedSeed = normalizeAuthor(seedAuthorId);
        if (!normalizedSeed.isEmpty()) {
            List<String> matches = new ArrayList<>();
            for (Map<String, Object> p : profiles) {
                String key = (String) p.get("global_user_id");
                if (key != null && normalizeAuthor(key).equals(normalizedSeed) && !matches.contains(key)) {
                    matches.add(key);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                // Distinct stored keys collapse to the same normalized form — the
                // cross-platform split-profile case. We can't pick safely; surface
                // the exact ids so the caller can disambiguate.
                throw new IllegalArgumentException(
                        "Ambiguous seedAuthorId '" + seedAuthorId + "': matches multiple profiles "
                        + matches + ". Pass one of these exact ids.");
            }
        }

        List<String> suggestions = suggestSimilarAuthors(profiles, normalizedSeed);
        String hint = suggestions.isEmpty() ? "" : " Did you mean: " + suggestions + "?";
        throw new IllegalArgumentException("Unknown seedAuthorId: " + seedAuthorId + "." + hint);
    }

    /** Lowercase and drop every non-alphanumeric character — matches the
     * normalization in {@link CrossPlatformIdentityResolver}'s COLLECT_AUTHORS_SQL. */
    private static String normalizeAuthor(String author) {
        return author == null ? "" : author.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** A stored key is only offered as a "did you mean" when it clears this
     * similarity bar. A bare substring test is far too loose — a one- or
     * two-character key is a substring of almost every long name — so we score
     * by edit distance and surface only genuinely close matches. */
    private static final double SUGGESTION_MIN_SIMILARITY = 0.5;
    /** Ignore keys whose normalized form is shorter than this when suggesting:
     * a 1-2 char author is never a useful "did you mean", and (as a substring of
     * almost anything) only produces noise like {@code [c, i, C, D, N]}. */
    private static final int SUGGESTION_MIN_KEY_LEN = 3;

    /** Up to five stored keys closest to the seed, ranked by similarity, to make an
     * unresolved seed actionable rather than a bare "unknown". */
    private List<String> suggestSimilarAuthors(List<Map<String, Object>> profiles, String normalizedSeed) {
        if (normalizedSeed.length() < SUGGESTION_MIN_KEY_LEN) {
            // Too short to discriminate — almost every key would look "similar".
            return Collections.emptyList();
        }
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> p : profiles) {
            String key = (String) p.get("global_user_id");
            if (key == null || !seen.add(key)) continue;
            String norm = normalizeAuthor(key);
            if (norm.length() < SUGGESTION_MIN_KEY_LEN) continue;
            double sim = authorSimilarity(normalizedSeed, norm);
            if (sim >= SUGGESTION_MIN_SIMILARITY) {
                scored.add(Map.entry(key, sim));
            }
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : scored) {
            out.add(e.getKey());
            if (out.size() >= 5) break;
        }
        return out;
    }

    /** Similarity in [0,1] between two normalized author strings. Levenshtein-based,
     * but a genuine containment (one is a substring of the other, with the shorter
     * side itself meaningful) is treated as a strong match regardless of the length
     * gap — so an abbreviation like "kvn" still surfaces "kvnproductions". */
    private static double authorSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 0.0;
        int minLen = Math.min(a.length(), b.length());
        if (minLen >= SUGGESTION_MIN_KEY_LEN && (a.contains(b) || b.contains(a))) {
            // Rank closer-length containments ahead of lopsided ones, but keep all
            // of them above the suggestion bar.
            return 0.5 + 0.5 * ((double) minLen / maxLen);
        }
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    /** Standard two-row Levenshtein edit distance. */
    private static int levenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    /**
     * Comparison harness: runs the legacy L2 ranking ("current") and the production
     * block-wise method ("prototype") for the same seed and returns a side-by-side
     * view — each ranked list, the score ranges (showing L2's concentration near 0),
     * and the rank movement for shared candidates. Used for ongoing weight tuning.
     */
    public Map<String, Object> diffLookalikes(String seedAuthorId, int limit) {
        List<Map<String, Object>> current = findLookalikesL2Legacy(seedAuthorId, limit);
        List<Map<String, Object>> proto = findLookalikes(seedAuthorId, limit);

        List<String> currentIds = current.stream().map(r -> (String) r.get("global_user_id")).toList();
        List<String> protoIds = proto.stream().map(r -> (String) r.get("global_user_id")).toList();

        Map<String, Integer> currentRank = new HashMap<>();
        for (int i = 0; i < currentIds.size(); i++) currentRank.put(currentIds.get(i), i + 1);
        Map<String, Integer> protoRank = new HashMap<>();
        for (int i = 0; i < protoIds.size(); i++) protoRank.put(protoIds.get(i), i + 1);

        Set<String> overlap = new LinkedHashSet<>(currentIds);
        overlap.retainAll(protoIds);

        List<Map<String, Object>> movement = new ArrayList<>();
        for (String id : overlap) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("global_user_id", id);
            m.put("rank_current", currentRank.get(id));
            m.put("rank_prototype", protoRank.get(id));
            m.put("rank_delta", currentRank.get(id) - protoRank.get(id));
            movement.add(m);
        }
        movement.sort((a, b) -> Integer.compare((int) a.get("rank_prototype"), (int) b.get("rank_prototype")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seedAuthorId", seedAuthorId);
        out.put("limit", limit);
        out.put("overlap_count", overlap.size());
        out.put("overlap_fraction", currentIds.isEmpty() ? 0.0 : (double) overlap.size() / currentIds.size());
        out.put("current_score_range", scoreRange(current));
        out.put("prototype_score_range", scoreRange(proto));
        out.put("rank_movement_shared", movement);
        out.put("current_top", current);
        out.put("prototype_top", proto);
        return out;
    }

    /** Hellinger similarity (1 - H) between two normalized distributions.
     *  Returns [similarity, definedFlag] where definedFlag>0 iff both distributions are non-empty. */
    private double[] hellingerSim(Map<String, Double> p, Map<String, Double> q) {
        if (p.isEmpty() || q.isEmpty()) return new double[]{0.0, 0.0};
        Set<String> cats = new LinkedHashSet<>(p.keySet());
        cats.addAll(q.keySet());
        double sum = 0.0;
        for (String c : cats) {
            double sp = Math.sqrt(p.getOrDefault(c, 0.0));
            double sq = Math.sqrt(q.getOrDefault(c, 0.0));
            double d = sp - sq;
            sum += d * d;
        }
        double h = Math.sqrt(sum / 2.0); // Hellinger distance in [0,1]
        return new double[]{1.0 - h, 1.0};
    }

    private Map<String, Double> parseAspects(Object topGenresRaw, Type mapType) {
        String json = topGenresRaw == null ? null : topGenresRaw.toString();
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        Map<String, Double> parsed = gson.fromJson(json, mapType);
        if (parsed == null) return Collections.emptyMap();
        // keep only nonzero entries so absence stays sparse (df/cosine ignore zeros)
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : parsed.entrySet()) {
            if (e.getValue() != null && e.getValue() != 0.0) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private Map<String, Double> weightedAspects(Object topGenresRaw, Map<String, Double> idf, Type mapType) {
        Map<String, Double> tf = parseAspects(topGenresRaw, mapType);
        if (tf.isEmpty()) return Collections.emptyMap();
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            out.put(e.getKey(), e.getValue() * idf.getOrDefault(e.getKey(), 1.0));
        }
        return out;
    }

    private double l2Norm(java.util.Collection<Double> values) {
        double s = 0.0;
        for (double v : values) s += v * v;
        return Math.sqrt(s);
    }

    private double populationStd(List<Map<String, Object>> profiles, String key) {
        double sum = 0.0, sumSq = 0.0;
        int n = profiles.size();
        for (Map<String, Object> p : profiles) {
            double v = asDouble(p.get(key));
            sum += v; sumSq += v * v;
        }
        double mean = sum / n;
        double var = sumSq / n - mean * mean;
        return var > 0 ? Math.sqrt(var) : 0.0;
    }

    private double asDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private Map<String, Object> scoreRange(List<Map<String, Object>> results) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (results.isEmpty()) { r.put("count", 0); return r; }
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (Map<String, Object> m : results) {
            double s = (double) m.get("similarity_score");
            min = Math.min(min, s); max = Math.max(max, s); sum += s;
        }
        r.put("count", results.size());
        r.put("min", min);
        r.put("max", max);
        r.put("mean", sum / results.size());
        return r;
    }

    private Map<String, Map<String, Double>> loadSentimentDistributions(String table, String authorColumn) {
        Map<String, Map<String, Double>> raw = new HashMap<>();
        String sql = "SELECT " + authorColumn + ", sentiment_category, COUNT(*) AS cnt" +
                     " FROM " + table +
                     " WHERE sentiment_category IS NOT NULL AND sentiment_score <> 0" +
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
