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
                if (pos.startsWith("NN")) { // Noun
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
