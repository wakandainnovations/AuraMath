package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * Extracts aspect terms from post text and attaches the post's <em>precomputed</em> sentiment to
 * each of them.
 *
 * <p>This is deliberately an aspect <em>extractor</em>, not an independent sentiment model: the
 * pipeline runs only {@code tokenize, ssplit, pos} to pick out noun aspects, and the sentiment value
 * carried for each aspect is the score the caller supplies (the externally computed
 * {@code sentiment_score} stored per post). It does NOT run CoreNLP's own sentiment annotator —
 * doing so would re-derive sentiment from scratch, ignore the stored score, and bypass the
 * "{@code sentiment_score = 0} means invalid" sentinel that callers filter on. Callers are expected
 * to pass a sentiment already on the desired scale (e.g. the 0–100 column centred to signed [-1,1]).
 */
public class AspectSentimentAnalyzer {

    private final StanfordCoreNLP pipeline;

    public AspectSentimentAnalyzer() {
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit, pos");
        this.pipeline = new StanfordCoreNLP(props);
    }

    public Map<String, Double> analyze(String text, double sentimentScore) {
        Map<String, Double> aspectSentiments = new HashMap<>();
        Annotation document = new Annotation(text);
        pipeline.annotate(document);

        for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class)) {
            for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                if (pos != null && pos.startsWith("NN")) { // Noun (guard null POS tag)
                    String aspect = token.originalText().toLowerCase();
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
}
