package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EngagementScoreCalculatorTest {

    private static final double DELTA = 0.0001;

    @Test
    public void testScoreWeighting() {
        // comments:shares:likes:views = 3:2:1.5:1
        double expected = 3.0 * 4 + 2.0 * 5 + 1.5 * 10 + 1.0 * 100;
        assertEquals(expected, EngagementScoreCalculator.score(4, 5, 10, 100), DELTA);
    }

    @Test
    public void testScoreXPost() {
        double expected = 3.0 * 4 + 2.0 * 5 + 1.5 * 10 + 1.0 * 100;
        assertEquals(expected, EngagementScoreCalculator.scoreXPost(4, 5, 10, 100), DELTA);
    }

    @Test
    public void testScoreXPostDefaultsMissingSharesToZero() {
        // Rows/columns predating the shares_count migration (see RetweetResolver)
        // should default to 0 rather than throwing.
        double expected = 3.0 * 4 + 2.0 * 0 + 1.5 * 10 + 1.0 * 100;
        assertEquals(expected, EngagementScoreCalculator.scoreXPost(4, null, 10, 100), DELTA);
    }

    @Test
    public void testScoreXPostDefaultsAllNullFieldsToZero() {
        assertEquals(0.0, EngagementScoreCalculator.scoreXPost(null, null, null, null), DELTA);
    }

    @Test
    public void testScoreYoutubeComment() {
        // A youtube_comments row is a comment on a video, not the video itself,
        // so shares and views are fixed at 0.
        double expected = 3.0 * 6 + 2.0 * 0 + 1.5 * 20 + 1.0 * 0;
        assertEquals(expected, EngagementScoreCalculator.scoreYoutubeComment(6, 20), DELTA);
    }

    @Test
    public void testScoreYoutubeCommentDefaultsMissingFieldsToZero() {
        assertEquals(0.0, EngagementScoreCalculator.scoreYoutubeComment(null, null), DELTA);
    }

    @Test
    public void testScoreRedditPost() {
        // Reddit's 'score' column is net upvotes, used as the likes-proxy
        // (see RawMappingDiagnosticController). Shares and views are fixed at 0.
        double expected = 3.0 * 8 + 2.0 * 0 + 1.5 * 50 + 1.0 * 0;
        assertEquals(expected, EngagementScoreCalculator.scoreRedditPost(8, 50), DELTA);
    }

    @Test
    public void testScoreRedditPostDefaultsMissingFieldsToZero() {
        assertEquals(0.0, EngagementScoreCalculator.scoreRedditPost(null, null), DELTA);
    }

    @Test
    public void testScoreInstagramPost() {
        // Shares and views aren't tracked for instagram_posts, so both are fixed at 0.
        double expected = 3.0 * 3 + 2.0 * 0 + 1.5 * 40 + 1.0 * 0;
        assertEquals(expected, EngagementScoreCalculator.scoreInstagramPost(3, 40), DELTA);
    }

    @Test
    public void testScoreInstagramPostDefaultsMissingFieldsToZero() {
        assertEquals(0.0, EngagementScoreCalculator.scoreInstagramPost(null, null), DELTA);
    }
}
