package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Extracts aspect terms (nouns) from post text.
 *
 * <p>{@link #analyze(String, double)} — used by {@code DatabaseManager}'s author aspect-sentiment
 * profiles and {@code MarketingEnrichmentEngine}'s persona/tribe assignment — is deliberately an
 * aspect <em>extractor</em>, not an independent sentiment model: it tags every noun in the post with
 * the caller-supplied document-level sentiment score. Unchanged from its original behavior; those
 * two features are out of scope here.
 *
 * <p>{@link #analyzeAspectSentiment(String)} — used by {@link AspectDriversPrecomputer} — is a real
 * aspect-based sentiment analyzer (ABSA), added to fix a specific problem with the extractor above:
 * copying one document-level score onto <em>every</em> noun in a post is wrong whenever a post
 * discusses more than one topic ("the music was amazing but the runtime killed it" should not score
 * "music" and "runtime" identically), and gives equal weight to nouns that have nothing to do with
 * the post's actual opinion (a stray aside, a hashtag, an @handle, a named person or unrelated film
 * mentioned in passing). Concretely it differs in two ways:
 * <ul>
 *   <li>Each candidate aspect is scored with CoreNLP's neural sentiment classifier run on just the
 *   sentence the aspect appears in, not the whole post.</li>
 *   <li>Candidates are restricted to common-noun (not proper-noun) lemmas with no named-entity tag —
 *   this excludes cast/crew names, other referenced films, hashtags/handles tagged as nouns, and any
 *   other named entity, none of which are genuine "aspects of the movie" in the sense a strengths/
 *   weaknesses list needs.</li>
 * </ul>
 */
public class AspectSentimentAnalyzer {

    // Longest token accepted as an aspect. Real nouns never approach this; longer tokens are
    // spam/garbage text with no whitespace (e.g. a giant hash-like blob) that CoreNLP still tags
    // as a single NN token. aspect_drivers_agg has a composite PRIMARY KEY (keyword, platform,
    // aspect), and Postgres btree index rows are capped at 8191 bytes — an oversized aspect here
    // aborts the whole precompute batch insert.
    private static final int MAX_ASPECT_LENGTH = 100;

    // Shortest token accepted as an aspect for analyzeAspectSentiment — filters degenerate 1-2
    // character noun-tagged tokens ("n", "ok") that aren't meaningful chip labels.
    private static final int MIN_ASPECT_LENGTH = 3;

    // Common (not proper) noun POS tags — excludes NNP/NNPS so proper nouns never qualify as an
    // aspect regardless of what the NER tagger makes of them.
    private static final Set<String> COMMON_NOUN_TAGS = Set.of("NN", "NNS");

    // NER tags that mark a token as a named entity rather than a genuine descriptive aspect. Covers
    // the label set CoreNLP's default "ner" annotator produces (it internally combines the 3-class/
    // 4-class/7-class statistical models plus rule-based recognizers).
    private static final Set<String> NAMED_ENTITY_TAGS = Set.of(
            "PERSON", "ORGANIZATION", "LOCATION", "MISC", "GPE", "FACILITY", "TITLE", "COUNTRY",
            "NATIONALITY", "CITY", "STATE_OR_PROVINCE");

    // Most social posts are a paragraph or two, but some (long-form Reddit posts especially) run
    // to tens of thousands of characters — hundreds of individually well-formed sentences, each
    // well under parse.maxlen, so that guard never triggers. Running full constituency parsing +
    // neural sentiment on every one of those sentences makes a single such post cost hundreds of
    // times what a typical post costs; AspectDriversPrecomputer's parallelStream can have several
    // of these in flight at once, and that was enough to exhaust the JVM heap even though no
    // individual sentence was pathological. The aspects mentioned in the opening of a long post are
    // as representative as any other for this purpose, so truncate rather than analyzing it whole.
    private static final int MAX_INPUT_CHARS = 4000;

    private final StanfordCoreNLP pipeline;

    public AspectSentimentAnalyzer() {
        Properties props = new Properties();
        // Superset of annotators covers both analyze() (only ever reads POS) and
        // analyzeAspectSentiment() (also reads lemma/ner/sentiment) from one shared pipeline.
        props.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner, parse, sentiment");
        // Unpunctuated social-media text (a long run-on post with no sentence-ending punctuation)
        // can make ssplit hand the constituency parser one "sentence" of hundreds of tokens.
        // Parse time/memory blows up non-linearly with sentence length, and — critically — doing
        // that for several such posts at once under AspectDriversPrecomputer's parallelStream can
        // exhaust the heap for the whole JVM even though CoreNLP recovers gracefully per sentence.
        // parse.maxlen skips the parser (and therefore sentiment, which depends on its output) for
        // any sentence longer than this many tokens rather than attempting it.
        props.setProperty("parse.maxlen", "100");
        this.pipeline = new StanfordCoreNLP(props);
    }

    // ------------------------------------------------------------------
    // Original document-sentiment extractor — unchanged.
    // ------------------------------------------------------------------

    public Map<String, Double> analyze(String text, double sentimentScore) {
        Map<String, Double> aspectSentiments = new HashMap<>();
        Annotation document = new Annotation(text);
        pipeline.annotate(document);

        for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class)) {
            for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                if (pos != null && pos.startsWith("NN")) { // Noun (guard null POS tag)
                    String aspect = token.originalText().toLowerCase();
                    if (aspect.length() > MAX_ASPECT_LENGTH) continue; // spam/garbage token, not a real noun
                    aspectSentiments.put(aspect, sentimentScore);
                }
            }
        }
        return aspectSentiments;
    }

    public Map<String, Double> analyze(UniversalPost post) {
        double sentimentScore = 0.0;
        if (post.getMetadata() != null && post.getMetadata().containsKey("sentiment_score")) {
             Object val = post.getMetadata().get("sentiment_score");
             if (val instanceof Number) {
                 sentimentScore = ((Number) val).doubleValue();
             }
        }
        return analyze(post.getContent(), sentimentScore);
    }

    public Map<String, Double> analyze(Stream<UniversalPost> posts) {
        return posts.flatMap(post -> analyze(post).entrySet().stream())
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.averagingDouble(Map.Entry::getValue)));
    }

    // ------------------------------------------------------------------
    // Aspect-based sentiment analysis — AspectDriversPrecomputer.
    // ------------------------------------------------------------------

    /**
     * Aspect-based sentiment for one post. Each candidate aspect (a common-noun lemma with no
     * named-entity tag) is scored with the CoreNLP-predicted sentiment of the single sentence it
     * appears in — 0/25/50/75/100 for very-negative/negative/neutral/positive/very-positive,
     * matching the 25/50/75 category-to-score convention {@code AspectDriversPrecomputer} already
     * uses for platforms that only carry a sentiment label. An aspect mentioned more than once in
     * the same post is averaged across its occurrences into a single value, same as the original
     * extractor collapses repeats via map overwrite — callers still treat one post as contributing
     * exactly one weighted observation per distinct aspect.
     */
    public Map<String, Double> analyzeAspectSentiment(String text) {
        Map<String, double[]> sums = new HashMap<>(); // aspect -> [sentimentSum, occurrences]
        if (text.length() > MAX_INPUT_CHARS) text = text.substring(0, MAX_INPUT_CHARS);
        Annotation document = new Annotation(text);
        pipeline.annotate(document);

        for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class)) {
            Tree sentimentTree = sentence.get(SentimentCoreAnnotations.SentimentAnnotatedTree.class);
            if (sentimentTree == null) continue;
            double sentenceScore = RNNCoreAnnotations.getPredictedClass(sentimentTree) * 25.0;

            for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                if (!isAspectCandidate(token)) continue;
                String lemma = token.get(CoreAnnotations.LemmaAnnotation.class);
                if (lemma == null) continue;
                String aspect = lemma.toLowerCase();
                if (aspect.length() < MIN_ASPECT_LENGTH || aspect.length() > MAX_ASPECT_LENGTH) continue;

                double[] acc = sums.computeIfAbsent(aspect, k -> new double[2]);
                acc[0] += sentenceScore;
                acc[1] += 1;
            }
        }

        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, double[]> e : sums.entrySet()) {
            result.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
        }
        return result;
    }

    private boolean isAspectCandidate(CoreLabel token) {
        String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
        if (pos == null || !COMMON_NOUN_TAGS.contains(pos)) return false; // excludes NNP/NNPS

        String ner = token.get(CoreAnnotations.NamedEntityTagAnnotation.class);
        if (ner != null && NAMED_ENTITY_TAGS.contains(ner)) return false;

        String original = token.originalText();
        return !(original.startsWith("#") || original.startsWith("@"));
    }
}
