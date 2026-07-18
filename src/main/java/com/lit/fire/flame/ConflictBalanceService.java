package com.lit.fire.flame;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;
import org.ejml.simple.SimpleMatrix;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Corpus-relative "Conflict Balance" scorer.
 *
 * A synopsis reads as a genuinely contested conflict when its positive beats (goals reached,
 * triumphs) and negative beats (setbacks, threats) carry roughly equal emotional weight — as
 * opposed to a one-sided synopsis (pure triumph or pure tragedy) or an emotionally flat one (a dry
 * plot description with no real conflict at all). Both failure modes should score low; only a
 * genuine push-and-pull should score high.
 *
 * Per sentence, {@link StanfordCoreNLP}'s sentiment RNN (trained on the Stanford Sentiment
 * Treebank, itself built from movie reviews) gives a 5-class probability distribution. That full
 * distribution — not just the argmax label — is collapsed to a continuous signed polarity
 * x = sum(p_c * (c-2)) in [-2,2], which keeps resolution the discrete class would throw away.
 *
 * Two terms combine into the raw signal:
 *  - balance:    1 - |P-N|/M   (0 when M=0) — 1 means positive/negative mass are equal, ->0 one-sided
 *  - engagement: M / (M+k), k = corpus median M — saturating gate so a synopsis with two tiny,
 *                near-equal-and-opposite sentences doesn't score as "balanced" purely because the
 *                ratio is 1:1; it must actually carry emotional weight
 * raw = balance * engagement, then corpus-relative percentile ranked and rescaled into a fixed
 * band, mirroring {@link NarrativeNoveltyService}'s convention. A third "oscillation" term (sign
 * reversals across consecutive sentences, rewarding back-and-forth swings) was considered and
 * dropped: synopses run 3-6 sentences, too few for a sign-change count to be a stable signal.
 */
@Service
public class ConflictBalanceService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Matches the range already present in the legacy conflict_balance_score column (1,314
    // pre-existing rows span [0.25, 0.35]) so newly computed and legacy values sit on one scale.
    private static final double BAND_FLOOR = 0.25;
    private static final double BAND_CEIL = 0.35;

    private final StanfordCoreNLP pipeline;

    private record CorpusDoc(String movieName, String releaseDate, String language, double magnitude) {}

    public ConflictBalanceService() {
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit, pos, parse, sentiment");
        this.pipeline = new StanfordCoreNLP(props);
    }

    /** Positive mass, negative mass, and per-sentence signed polarity for one synopsis. */
    private double[] sentencePolarities(String synopsis) {
        Annotation document = new Annotation(synopsis);
        pipeline.annotate(document);
        List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
        double[] polarities = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            Tree tree = sentences.get(i).get(SentimentCoreAnnotations.SentimentAnnotatedTree.class);
            SimpleMatrix probs = RNNCoreAnnotations.getPredictions(tree);
            double x = 0.0;
            for (int c = 0; c < probs.getNumElements(); c++) {
                x += probs.get(c) * (c - 2);
            }
            polarities[i] = x;
        }
        return polarities;
    }

    private static double positiveMass(double[] polarities) {
        double p = 0.0;
        for (double x : polarities) p += Math.max(x, 0.0);
        return p;
    }

    private static double negativeMass(double[] polarities) {
        double n = 0.0;
        for (double x : polarities) n += Math.max(-x, 0.0);
        return n;
    }

    private static double balance(double positive, double negative) {
        double magnitude = positive + negative;
        return magnitude > 0.0 ? 1.0 - Math.abs(positive - negative) / magnitude : 0.0;
    }

    private void ensureSchema() {
        jdbcTemplate.execute("ALTER TABLE movies_data_collection ADD COLUMN IF NOT EXISTS conflict_balance_score numeric");
    }

    /** Rebuilds the corpus-relative reference distribution and persists conflict_balance_score for every synopsis row. */
    public Map<String, Object> recomputeAndPersist() {
        ensureSchema();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT movie_name, release_date, language, synopsis " +
                "FROM movies_data_collection WHERE synopsis IS NOT NULL AND btrim(synopsis) <> ''");

        int n = rows.size();
        List<CorpusDoc> docs = new ArrayList<>(n);
        double[] balancePerDoc = new double[n];
        double[] magnitudePerDoc = new double[n];

        for (int i = 0; i < n; i++) {
            Map<String, Object> row = rows.get(i);
            double[] polarities = sentencePolarities((String) row.get("synopsis"));
            double positive = positiveMass(polarities);
            double negative = negativeMass(polarities);
            balancePerDoc[i] = balance(positive, negative);
            magnitudePerDoc[i] = positive + negative;
            docs.add(new CorpusDoc((String) row.get("movie_name"), (String) row.get("release_date"),
                    (String) row.get("language"), magnitudePerDoc[i]));
        }

        double k = median(magnitudePerDoc);

        double[] rawPerDoc = new double[n];
        for (int i = 0; i < n; i++) {
            double engagement = k > 0.0 ? magnitudePerDoc[i] / (magnitudePerDoc[i] + k) : 0.0;
            rawPerDoc[i] = balancePerDoc[i] * engagement;
        }

        double[] sortedRaw = rawPerDoc.clone();
        Arrays.sort(sortedRaw);

        int updated = 0;
        for (int i = 0; i < n; i++) {
            CorpusDoc d = docs.get(i);
            double percentile = percentileRank(rawPerDoc[i], sortedRaw);
            double score = BAND_FLOOR + percentile * (BAND_CEIL - BAND_FLOOR);
            updated += jdbcTemplate.update(
                    "UPDATE movies_data_collection SET conflict_balance_score = ? " +
                    "WHERE movie_name = ? AND release_date = ? AND language = ?",
                    score, d.movieName(), d.releaseDate(), d.language());
        }

        Map<String, Object> validation = jdbcTemplate.queryForMap(
                "SELECT corr(conflict_balance_score, revenue) AS corr_revenue, " +
                "       corr(conflict_balance_score, imdb_rating) AS corr_imdb, " +
                "       count(DISTINCT conflict_balance_score) AS distinct_values " +
                "FROM movies_data_collection WHERE conflict_balance_score IS NOT NULL AND revenue > 0");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("corpusSize", n);
        summary.put("rowsUpdated", updated);
        summary.put("medianMagnitude", k);
        summary.put("validation", validation);
        return summary;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int m = sorted.length;
        if (m == 0) return 0.0;
        return (m % 2 == 0) ? (sorted[m / 2 - 1] + sorted[m / 2]) / 2.0 : sorted[m / 2];
    }

    private static double percentileRank(double value, double[] sortedRef) {
        if (sortedRef.length == 0) return 0.5;
        int idx = Arrays.binarySearch(sortedRef, value);
        if (idx < 0) idx = -idx - 1;
        return Math.min(1.0, Math.max(0.0, (double) idx / sortedRef.length));
    }
}
