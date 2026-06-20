package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the genre-interest scoring math. No Spring or JDBC required — the scoring
 * helpers are pure functions of (sentiment_score, engagement, reddit-score).
 *
 * Validates the 0–100 → signed [-1,1] sentiment centring (50 = neutral) so that negatively
 * perceived posts subtract from interest, and the Reddit score clamp that prevents a
 * negative-sentiment × negative-score sign flip.
 */
class GenreInterestProfilerTest {

    private static final long ENGAGEMENT = 999L;          // log(1000) ≈ 6.9, a clean positive magnitude
    private static final double LOG_ENG  = Math.log(ENGAGEMENT + 1.0);

    @Test
    void neutralSentimentYieldsZeroInterest() {
        // 50 on the 0–100 scale is neutral → centred to 0 → zero interest regardless of engagement.
        assertEquals(0.0, GenreInterestProfiler.baseInterest(50.0, ENGAGEMENT), 1e-12);
    }

    @Test
    void aboveNeutralSentimentIsPositiveBelowIsNegative() {
        double liked    = GenreInterestProfiler.baseInterest(90.0, ENGAGEMENT);
        double disliked = GenreInterestProfiler.baseInterest(10.0, ENGAGEMENT);

        assertTrue(liked > 0.0,    "above-neutral sentiment should give positive interest, was " + liked);
        assertTrue(disliked < 0.0, "below-neutral sentiment should give negative interest, was " + disliked);
        // Symmetric distance from the neutral midpoint ⇒ equal magnitude, opposite sign.
        assertEquals(liked, -disliked, 1e-9);
    }

    @Test
    void centringMatchesExpectedFormula() {
        // (score-50)/50 * log(eng+1): score 100 → +1 * LOG_ENG ; score 0 would be invalid (filtered upstream).
        assertEquals(1.0 * LOG_ENG, GenreInterestProfiler.baseInterest(100.0, ENGAGEMENT), 1e-9);
        assertEquals(-1.0 * LOG_ENG, GenreInterestProfiler.baseInterest(0.0, ENGAGEMENT), 1e-9);
    }

    @Test
    void negativeViewsAreTreatedAsZeroEngagement() {
        // log(0+1) = 0 ⇒ zero interest, never NaN/negative-log.
        assertEquals(0.0, GenreInterestProfiler.baseInterest(90.0, -5L), 1e-12);
    }

    @Test
    void redditScoreActsAsNonNegativeMultiplierOnly() {
        // A downvoted (negative score) post must not flip the sentiment-driven sign.
        // Disliked post (sentiment < 50) stays non-positive even when the score is negative.
        double dislikedDownvoted = GenreInterestProfiler.redditInterest(10.0, ENGAGEMENT, -40);
        assertEquals(0.0, dislikedDownvoted, 1e-12,
                "clamped negative score ⇒ zero, not a positive double-negative flip");

        // Liked post weighted by a positive score scales up positively.
        double likedUpvoted = GenreInterestProfiler.redditInterest(90.0, ENGAGEMENT, 5);
        assertEquals(GenreInterestProfiler.baseInterest(90.0, ENGAGEMENT) * 5.0, likedUpvoted, 1e-9);
        assertTrue(likedUpvoted > 0.0);
    }

    @Test
    void dislikedUpvotedPostStaysNegative() {
        // Sign comes from sentiment, magnitude from the (clamped) score: disliked but upvoted ⇒ negative.
        double interest = GenreInterestProfiler.redditInterest(20.0, ENGAGEMENT, 8);
        assertTrue(interest < 0.0, "disliked content should register negative interest, was " + interest);
    }
}
