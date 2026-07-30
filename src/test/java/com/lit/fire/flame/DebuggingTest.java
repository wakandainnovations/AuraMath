package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.stream.Stream;

public class DebuggingTest {

    @Test
    public void runTest() {
        List<Double> timeToLikeData = Arrays.asList(
            0.5, 1.0, 1.2, 1.5, 2.0, 2.3, 2.8, 3.5, 4.0, 5.0, 6.0, 7.5, 9.0, 11.0, 14.0, 18.0, 24.0
        );

        Stream<UniversalPost> postsStream = timeToLikeData.stream().map(hours -> {
            long seconds = (long) (hours * 3600);
            return new UniversalPost("postId", "authorId", "content", LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC), "platform", Collections.emptyMap());
        });

        EngagementDecayEstimator estimator = new EngagementDecayEstimator(postsStream);
        double shelfLifeInSeconds = estimator.predictEngagementShelfLife();
        double shelfLifeInHours = shelfLifeInSeconds / 3600.0;
        
        System.out.println("Shape (k): " + estimator.getShape());
        System.out.println("Scale (lambda): " + estimator.getScale());
        System.out.println("Shelf Life (seconds): " + shelfLifeInSeconds);
        System.out.println("Shelf Life (hours): " + shelfLifeInHours);
    }
}