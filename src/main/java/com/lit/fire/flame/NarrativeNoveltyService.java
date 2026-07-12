package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Corpus-relative "High-Concept Narrative Novelty" scorer.
 *
 * A movie's novelty is measured as its embedding cosine distance to its nearest neighbors among
 * historical synopses in the same primary genre (so genre alone can't drive the score), rank-
 * normalized against a leave-one-out reference distribution built from movies_data_collection,
 * then linearly rescaled into the fixed [0.30, 0.45] impact band assigned to this factor.
 *
 * Embeddings come from a local Ollama model ({@link OllamaEmbeddingClient}) — dense, semantic
 * sentence vectors rather than TF-IDF's lexical (exact-word-overlap) vectors, so paraphrased or
 * differently-worded synopses about a conceptually similar premise land close together instead
 * of looking artificially "novel" for sharing no vocabulary.
 */
@Service
public class NarrativeNoveltyService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OllamaEmbeddingClient embeddingClient;

    private static final int K_NEIGHBORS = 10;
    private static final int MIN_GENRE_GROUP = 5;    // below this, fall back to the whole corpus for neighbors
    // Below this, a genre's own raw-novelty distribution is too small (~<20 points) to percentile-rank
    // against reliably, AND sparse-genre raw values run systematically higher than dense-genre ones for
    // no reason but corpus density (thinner neighborhoods -> weaker matches -> higher "distance"). Movies
    // in small genres are ranked against a pooled cross-genre reference of other small-genre movies instead,
    // so "novel" never just means "rare genre in this corpus".
    private static final int MIN_GENRE_FOR_OWN_PERCENTILE_POOL = 20;
    private static final double FRANCHISE_PENALTY = 0.7; // sequels/remakes are recognizable regardless of text distance
    private static final double BAND_FLOOR = 0.30;
    private static final double BAND_CEIL = 0.45;
    private static final String SMALL_GENRE_POOL_KEY = "__small_genre_pool__";

    private static final Pattern TRAILING_PART = Pattern.compile(
            "(?i)\\s*[:\\-–]?\\s*(part|chapter|vol\\.?|volume|book)\\s+[ivxlcdm0-9]+\\s*$");
    private static final Pattern TRAILING_ROMAN = Pattern.compile("(?i)\\s+[ivxlcdm]{1,6}\\s*$");
    private static final Pattern TRAILING_NUMERAL = Pattern.compile("\\s+[0-9]{1,2}\\s*$");

    private record CorpusDoc(String movieName, String releaseDate, String language,
                              String primaryGenre, float[] vector) {}

    public record Neighbor(String movieName, double similarity) {}

    public record NoveltyResult(
            String movieName, String primaryGenre, boolean genreFallback, int genreGroupSize,
            int neighborsUsed, boolean franchiseDetected,
            double rawNovelty, double percentile, double score,
            List<Neighbor> nearestNeighbors) {}

    private List<CorpusDoc> corpus = new ArrayList<>();
    private double[] rawNoveltyPerDoc = new double[0];
    private boolean[] franchiseFlagPerDoc = new boolean[0];
    // Reference distribution for percentile ranking, keyed by primary genre for genres large enough to
    // have their own (>= MIN_GENRE_FOR_OWN_PERCENTILE_POOL corpus rows); everything else shares the
    // SMALL_GENRE_POOL_KEY pool so small genres are only ever compared against other small genres.
    private Map<String, double[]> referenceDistributionByGenre = new HashMap<>();
    private Map<String, Integer> genreCorpusSizes = new HashMap<>();
    private Map<String, Integer> franchiseStemCounts = new HashMap<>();
    private Map<String, Integer> franchisePrefixCounts = new HashMap<>();
    private boolean built = false;

    public synchronized void ensureBuilt() {
        if (!built) {
            rebuildCorpus();
        }
    }

    public synchronized void rebuildCorpus() {
        ensureSchema();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT movie_name, release_date, language, " +
                "       COALESCE(NULLIF(btrim(genre),''), NULLIF(btrim(genres),''), 'unknown') AS genre_val, " +
                "       synopsis " +
                "FROM movies_data_collection WHERE synopsis IS NOT NULL AND btrim(synopsis) <> ''");

        int n = rows.size();
        List<String> synopses = new ArrayList<>(n);
        for (Map<String, Object> row : rows) {
            synopses.add((String) row.get("synopsis"));
        }
        List<float[]> embeddings = embeddingClient.embedBatch(synopses);

        List<CorpusDoc> newCorpus = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = rows.get(i);
            String primaryGenre = extractPrimaryGenre((String) row.get("genre_val"));
            newCorpus.add(new CorpusDoc(
                    (String) row.get("movie_name"),
                    (String) row.get("release_date"),
                    (String) row.get("language"),
                    primaryGenre,
                    normalize(embeddings.get(i))));
        }

        Map<String, Integer> stemCounts = new HashMap<>();
        Map<String, Integer> prefixCounts = new HashMap<>();
        List<String> allNames = jdbcTemplate.queryForList("SELECT DISTINCT movie_name FROM movies_data_collection", String.class);
        for (String nm : allNames) {
            stemCounts.merge(stripSequelSuffix(nm), 1, Integer::sum);
            prefixCounts.merge(prefixBeforeSeparator(nm), 1, Integer::sum);
        }

        Map<String, List<Integer>> byGenre = new HashMap<>();
        for (int i = 0; i < newCorpus.size(); i++) {
            byGenre.computeIfAbsent(newCorpus.get(i).primaryGenre(), k -> new ArrayList<>()).add(i);
        }

        double[] rawPerDoc = new double[n];
        boolean[] franchisePerDoc = new boolean[n];
        for (int i = 0; i < n; i++) {
            CorpusDoc doc = newCorpus.get(i);
            List<Integer> group = byGenre.get(doc.primaryGenre());
            List<Integer> candidates = (group.size() - 1 >= MIN_GENRE_GROUP)
                    ? group
                    : IntStream.range(0, n).boxed().toList();
            double raw = rawNoveltyAgainst(doc.vector(), i, candidates, newCorpus);
            boolean franchise = isFranchise(doc.movieName(), stemCounts, prefixCounts);
            if (franchise) {
                raw *= FRANCHISE_PENALTY;
            }
            rawPerDoc[i] = raw;
            franchisePerDoc[i] = franchise;
        }

        Map<String, Integer> genreSizes = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : byGenre.entrySet()) {
            genreSizes.put(e.getKey(), e.getValue().size());
        }

        Map<String, List<Double>> pools = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String genre = newCorpus.get(i).primaryGenre();
            String poolKey = (genreSizes.get(genre) >= MIN_GENRE_FOR_OWN_PERCENTILE_POOL)
                    ? genre : SMALL_GENRE_POOL_KEY;
            pools.computeIfAbsent(poolKey, k -> new ArrayList<>()).add(rawPerDoc[i]);
        }
        Map<String, double[]> refDistByGenre = new HashMap<>();
        for (Map.Entry<String, List<Double>> e : pools.entrySet()) {
            double[] arr = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(arr);
            refDistByGenre.put(e.getKey(), arr);
        }

        this.corpus = newCorpus;
        this.rawNoveltyPerDoc = rawPerDoc;
        this.franchiseFlagPerDoc = franchisePerDoc;
        this.referenceDistributionByGenre = refDistByGenre;
        this.genreCorpusSizes = genreSizes;
        this.franchiseStemCounts = stemCounts;
        this.franchisePrefixCounts = prefixCounts;
        this.built = true;
    }

    /** The reference pool a genre's percentile should be ranked against — its own pool if large enough, else the shared small-genre pool. */
    private double[] referenceDistributionFor(String primaryGenre) {
        int size = genreCorpusSizes.getOrDefault(primaryGenre, 0);
        String poolKey = (size >= MIN_GENRE_FOR_OWN_PERCENTILE_POOL) ? primaryGenre : SMALL_GENRE_POOL_KEY;
        return referenceDistributionByGenre.getOrDefault(poolKey, referenceDistributionByGenre.get(SMALL_GENRE_POOL_KEY));
    }

    /** Score an arbitrary (not-yet-in-DB) movie — the "any upcoming movie" use case. */
    public NoveltyResult computeNovelty(String movieName, String genre, String synopsis) {
        ensureBuilt();

        float[] vector = normalize(embeddingClient.embed(synopsis));
        String primaryGenre = extractPrimaryGenre(genre);

        List<Integer> group = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            if (corpus.get(i).primaryGenre().equals(primaryGenre)) {
                group.add(i);
            }
        }
        boolean fallback = group.size() < MIN_GENRE_GROUP;
        List<Integer> candidates = fallback ? IntStream.range(0, corpus.size()).boxed().toList() : group;

        List<Neighbor> allSims = new ArrayList<>(candidates.size());
        for (int idx : candidates) {
            CorpusDoc d = corpus.get(idx);
            allSims.add(new Neighbor(d.movieName(), cosine(vector, d.vector())));
        }
        allSims.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        int k = Math.min(K_NEIGHBORS, allSims.size());
        double avgSim = allSims.stream().limit(k).mapToDouble(Neighbor::similarity).average().orElse(0.0);
        double raw = (k == 0) ? 1.0 : 1.0 - avgSim;

        boolean franchise = isFranchise(movieName, franchiseStemCounts, franchisePrefixCounts);
        if (franchise) {
            raw *= FRANCHISE_PENALTY;
        }

        double percentile = percentileRank(raw, referenceDistributionFor(primaryGenre));
        double score = BAND_FLOOR + percentile * (BAND_CEIL - BAND_FLOOR);

        return new NoveltyResult(movieName, primaryGenre, fallback, candidates.size(), k, franchise,
                raw, percentile, score, allSims.subList(0, Math.min(5, allSims.size())));
    }

    /** Convenience: score a title already present in movies_data_collection by its stored genre/synopsis. */
    public NoveltyResult computeNoveltyForExisting(String movieName) {
        ensureBuilt();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT movie_name, COALESCE(NULLIF(btrim(genre),''), NULLIF(btrim(genres),''), 'unknown') AS genre_val, synopsis " +
                "FROM movies_data_collection WHERE movie_name = ? AND synopsis IS NOT NULL AND btrim(synopsis) <> '' LIMIT 1",
                movieName);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return computeNovelty((String) row.get("movie_name"), (String) row.get("genre_val"), (String) row.get("synopsis"));
    }

    /** Rebuilds the corpus and persists v2 scores for every synopsis row; returns a summary + validation stats. */
    public Map<String, Object> recomputeAndPersist() {
        rebuildCorpus();

        int updated = 0;
        for (int i = 0; i < corpus.size(); i++) {
            CorpusDoc d = corpus.get(i);
            double raw = rawNoveltyPerDoc[i];
            double pct = percentileRank(raw, referenceDistributionFor(d.primaryGenre()));
            double score = BAND_FLOOR + pct * (BAND_CEIL - BAND_FLOOR);
            updated += jdbcTemplate.update(
                    "UPDATE movies_data_collection SET narrative_novelty_score_v2 = ?, narrative_novelty_raw_v2 = ?, " +
                    "narrative_novelty_franchise_flag = ? WHERE movie_name = ? AND release_date = ? AND language = ?",
                    score, raw, franchiseFlagPerDoc[i], d.movieName(), d.releaseDate(), d.language());
        }

        Map<String, Object> validation = jdbcTemplate.queryForMap(
                "SELECT corr(narrative_novelty_score_v2, revenue) AS corr_revenue_v2, " +
                "       corr(narrative_novelty_score_v2, imdb_rating) AS corr_imdb_v2, " +
                "       corr(narrative_novelty_score, narrative_novelty_score_v2) AS corr_v1_v2, " +
                "       count(DISTINCT narrative_novelty_score_v2) AS distinct_values_v2 " +
                "FROM movies_data_collection WHERE narrative_novelty_score_v2 IS NOT NULL AND revenue > 0");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("corpusSize", corpus.size());
        summary.put("rowsUpdated", updated);
        summary.put("validation", validation);
        return summary;
    }

    private void ensureSchema() {
        jdbcTemplate.execute("ALTER TABLE movies_data_collection ADD COLUMN IF NOT EXISTS narrative_novelty_score_v2 numeric");
        jdbcTemplate.execute("ALTER TABLE movies_data_collection ADD COLUMN IF NOT EXISTS narrative_novelty_raw_v2 numeric");
        jdbcTemplate.execute("ALTER TABLE movies_data_collection ADD COLUMN IF NOT EXISTS narrative_novelty_franchise_flag boolean");
    }

    private double rawNoveltyAgainst(float[] vector, int selfIndex, List<Integer> candidateIdx, List<CorpusDoc> corpusList) {
        List<Double> sims = new ArrayList<>(candidateIdx.size());
        for (int idx : candidateIdx) {
            if (idx == selfIndex) {
                continue;
            }
            sims.add(cosine(vector, corpusList.get(idx).vector()));
        }
        sims.sort(Collections.reverseOrder());
        int k = Math.min(K_NEIGHBORS, sims.size());
        if (k == 0) {
            return 1.0;
        }
        double avgSim = sims.stream().limit(k).mapToDouble(Double::doubleValue).average().orElse(0.0);
        return 1.0 - avgSim;
    }

    private double percentileRank(double value, double[] sortedRef) {
        if (sortedRef.length == 0) {
            return 0.5;
        }
        int idx = Arrays.binarySearch(sortedRef, value);
        if (idx < 0) {
            idx = -idx - 1;
        }
        return Math.min(1.0, Math.max(0.0, (double) idx / sortedRef.length));
    }

    private boolean isFranchise(String movieName, Map<String, Integer> stemCounts, Map<String, Integer> prefixCounts) {
        if (movieName == null || movieName.isBlank()) {
            return false;
        }
        return stemCounts.getOrDefault(stripSequelSuffix(movieName), 0) >= 2
                || prefixCounts.getOrDefault(prefixBeforeSeparator(movieName), 0) >= 2;
    }

    private static String stripSequelSuffix(String name) {
        String s = name.trim();
        s = TRAILING_PART.matcher(s).replaceAll("");
        s = TRAILING_ROMAN.matcher(s).replaceAll("");
        s = TRAILING_NUMERAL.matcher(s).replaceAll("");
        return s.trim().toLowerCase();
    }

    private static String prefixBeforeSeparator(String name) {
        String s = name.trim();
        int idx = -1;
        for (String sep : new String[]{":", " - ", " – "}) {
            int i = s.indexOf(sep);
            if (i > 3 && (idx == -1 || i < idx)) {
                idx = i;
            }
        }
        return idx > 0 ? s.substring(0, idx).trim().toLowerCase() : s.trim().toLowerCase();
    }

    private static String extractPrimaryGenre(String genreValue) {
        if (genreValue == null || genreValue.isBlank()) {
            return "unknown";
        }
        String first = genreValue.split(",")[0];
        return first.trim().toLowerCase();
    }

    private static float[] normalize(float[] v) {
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    private double cosine(float[] a, float[] b) {
        double sum = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
