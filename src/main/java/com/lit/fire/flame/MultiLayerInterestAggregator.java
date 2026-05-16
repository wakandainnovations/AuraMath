package com.lit.fire.flame;

import java.util.List;
import java.util.Map;

public class MultiLayerInterestAggregator {

    private final DatabaseManager databaseManager;

    public MultiLayerInterestAggregator(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void processUser(String globalUserId) {
        // Aggregate data from all platforms
        List<PlatformData> allPlatformData = aggregateData(globalUserId);

        // Calculate sentiment variance
        double variance = calculateSentimentVariance(allPlatformData);

        // Categorize user
        String userCategory = categorizeUser(variance);

        // Output the result
        System.out.println("User " + globalUserId + " is a " + userCategory);
    }

    private List<PlatformData> aggregateData(String globalUserId) {
        // In a real application, this would query the database for all platform data
        // For this example, we'll use dummy data
        return List.of(
                new PlatformData("Reddit", "Action", 0.8),
                new PlatformData("Instagram", "Action", -0.2),
                new PlatformData("X", "Action", 0.7),
                new PlatformData("Youtube", "Action", 0.9)
        );
    }

    private double calculateSentimentVariance(List<PlatformData> platformData) {
        if (platformData.isEmpty()) {
            return 0.0;
        }

        double mean = platformData.stream()
                .mapToDouble(data -> data.sentimentScore)
                .average()
                .orElse(0.0);

        double variance = platformData.stream()
                .mapToDouble(data -> data.sentimentScore)
                .map(score -> Math.pow(score - mean, 2))
                .average()
                .orElse(0.0);

        return variance;
    }

    private String categorizeUser(double variance) {
        // This threshold is arbitrary and would be tuned based on data
        if (variance < 0.1) {
            return "Consistent Evangelist";
        } else {
            return "Context-Dependent Critic";
        }
    }

    private static class PlatformData {
        private final String platform;
        private final String keyword;
        private final double sentimentScore;

        public PlatformData(String platform, String keyword, double sentimentScore) {
            this.platform = platform;
            this.keyword = keyword;
            this.sentimentScore = sentimentScore;
        }
    }
}
