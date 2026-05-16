package com.lit.fire.flame;

public class App {
    public static void main(String[] args) {
        // Initialize the DatabaseManager
        DatabaseManager databaseManager = new DatabaseManager();

        // Initialize the MultiLayerInterestAggregator
        MultiLayerInterestAggregator aggregator = new MultiLayerInterestAggregator(databaseManager);

        // Process a sample user
        String sampleUserId = "user123";
        aggregator.processUser(sampleUserId);
    }
}
