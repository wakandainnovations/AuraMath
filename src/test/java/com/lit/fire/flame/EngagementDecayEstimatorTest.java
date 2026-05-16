package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;

public class EngagementDecayEstimatorTest {

    @Test
    public void testPredictEngagementShelfLife() {
        // Sample data representing the time (in hours) at which likes were received for a post.
        // We use a pattern that starts slow, accelerates to a peak, and then tapers off,
        // so that the shape parameter (k) is > 1 even after subtracting the first timestamp.
        List<Double> timeToLikeData = Arrays.asList(
            0.5, 1.5, 2.0, 2.3, 2.5, 2.6, 2.7, 2.8, 3.0, 3.3, 3.8, 4.5, 5.5, 7.0, 9.0, 12.0, 16.0, 24.0
        );

        Stream<UniversalPost> postsStream = timeToLikeData.stream().map(hours -> {
            long seconds = (long) (hours * 3600);
            return new UniversalPost("postId", "authorId", "content", LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC), "platform", Collections.emptyMap());
        });

        // Create an estimator and fit the Weibull model to the data.
        EngagementDecayEstimator estimator = new EngagementDecayEstimator(postsStream);

        // Predict the engagement shelf life for a hypothetical post.
        // The postId is a placeholder for future use and doesn't affect the current calculation.
        double shelfLifeInSeconds = estimator.predictEngagementShelfLife();
        double shelfLifeInHours = shelfLifeInSeconds / 3600.0;

        System.out.println("Fitted Weibull Parameters:");
        System.out.printf("  - Shape (k): %.2f%n", estimator.getShape());
        System.out.printf("  - Scale (lambda): %.2f%n", estimator.getScale());
        System.out.println();
        System.out.printf("Predicted Engagement Shelf Life: %.2f hours%n", shelfLifeInHours);
        System.out.println("This is the estimated time for the post's engagement rate to drop to 10% of its peak.");

        assertNotEquals("Shelf life should not be infinity", Double.POSITIVE_INFINITY, shelfLifeInHours, 0.0);
        
        // In a real-world test, you would assert that the value is within an expected range.
        assertTrue("Shelf life should be a positive value", shelfLifeInHours > 0);
        assertTrue("Shelf life should be a realistic value for this dataset", shelfLifeInHours < 50);
    }
}