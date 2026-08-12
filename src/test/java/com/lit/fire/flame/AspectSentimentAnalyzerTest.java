package com.lit.fire.flame;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies analyzeAspectSentiment fixes the specific failure mode that motivated it: a
 * document-level sentiment score being copied onto every noun regardless of what that noun's own
 * sentence actually says, and named entities (cast, other films, hashtags/handles) being tagged as
 * aspects the same as genuine descriptive nouns. Pipeline construction loads several CoreNLP models
 * (parser + sentiment + NER), so this is deliberately one shared instance across all cases.
 */
class AspectSentimentAnalyzerTest {

    private static AspectSentimentAnalyzer analyzer;

    @BeforeAll
    static void setUp() {
        analyzer = new AspectSentimentAnalyzer();
    }

    @Test
    void scoresEachAspectFromItsOwnSentenceNotTheWholePost() {
        Map<String, Double> aspects = analyzer.analyzeAspectSentiment(
                "The music was absolutely amazing and unforgettable. "
                        + "The runtime was way too long and the pacing was boring.");

        assertTrue(aspects.containsKey("music"), "expected 'music' as a candidate aspect: " + aspects);
        assertTrue(aspects.containsKey("runtime"), "expected 'runtime' as a candidate aspect: " + aspects);
        // The whole point of sentence-level scoring: two topics in the same post with opposite
        // sentences must not collapse to the same score, the way document-level attribution would.
        assertTrue(aspects.get("music") > aspects.get("runtime"),
                "music (positive sentence) should score higher than runtime (negative sentence): " + aspects);
    }

    @Test
    void excludesNamedEntitiesLikeCastAndOtherFilms() {
        Map<String, Double> aspects = analyzer.analyzeAspectSentiment(
                "This reminded me so much of a DC movie starring Tom Hanks, incredible film.");

        assertFalse(aspects.containsKey("dc"), "DC is an organization/named entity, not a movie aspect: " + aspects);
        assertFalse(aspects.containsKey("tom"), "a cast member's name is not a movie aspect: " + aspects);
        assertFalse(aspects.containsKey("hanks"), "a cast member's name is not a movie aspect: " + aspects);
        // "film"/"movie" are still genuine common nouns and may legitimately appear.
    }

    @Test
    void excludesHashtagsAndHandles() {
        Map<String, Double> aspects = analyzer.analyzeAspectSentiment(
                "#AmazingFilm was so good! @someuser you have to watch it, the story was great.");

        for (String aspect : aspects.keySet()) {
            assertFalse(aspect.startsWith("#") || aspect.startsWith("@"),
                    "hashtag/handle leaked through as an aspect: " + aspect);
        }
    }

    @Test
    void boundsRuntimeOnVeryLongWellFormedPosts() {
        // Long-form posts (tens of thousands of characters, hundreds of individually normal
        // sentences) previously drove analyzeAspectSentiment to full-parse+sentiment-score every
        // sentence, which under AspectDriversPrecomputer's parallel batches was enough to exhaust
        // the JVM heap even though no single sentence exceeded parse.maxlen. This just needs to
        // return, not hang/OOM, on an input far past the truncation threshold.
        StringBuilder longPost = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            longPost.append("The story was great and the acting was wonderful today. ");
        }

        Map<String, Double> aspects = analyzer.analyzeAspectSentiment(longPost.toString());

        assertTrue(aspects.containsKey("story"), "expected aspects from the (truncated) input: " + aspects);
    }
}
