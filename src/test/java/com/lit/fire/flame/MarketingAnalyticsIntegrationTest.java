package com.lit.fire.flame;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class MarketingAnalyticsIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MarketingEnrichmentEngine marketingEnrichmentEngine;

    @Test
    @Disabled("Manual integration test: requires a populated local 'aura' DB and mutates "
            + "marketing_target_profiles via enrichAndSave(). Run explicitly, not in the default suite.")
    public void executeMarketingAnalyticsPipeline() {
        // 1. Establish a real JDBC connection to the database
        // Verify database has data
        Integer xPostsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM x_posts", Integer.class);
        if (xPostsCount == null || xPostsCount == 0) {
            throw new IllegalStateException("Database returned 0 rows for x_posts");
        }
        
        // 2. Initialize and Execute the MarketingEnrichmentEngine
        marketingEnrichmentEngine.enrichAndSave();

        // 3. Find a Seed Author from the database (dynamically)
        // Select an author that exists in marketing_target_profiles to use as a seed
        List<String> userIds = jdbcTemplate.queryForList("SELECT global_user_id FROM marketing_target_profiles LIMIT 1", String.class);
        if (userIds.isEmpty()) {
             throw new IllegalStateException("No users found in marketing_target_profiles after enrichment");
        }
        String seedAuthorId = userIds.get(0);

        // Fetch user data for reporting
        Map<String, Object> seedUserProfile = jdbcTemplate.queryForMap("SELECT * FROM marketing_target_profiles WHERE global_user_id = ?", seedAuthorId);
        
        Double hawkesInfectivity = (Double) seedUserProfile.get("influence_rank");
        Double moiScore = (Double) seedUserProfile.get("moi_score");
        String tribe = (String) seedUserProfile.get("tribe_label");

        // Input Audit: count rows read from each platform for this user
        Integer seedXPostsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM x_posts WHERE author = ?", Integer.class, seedAuthorId);
        Integer seedYoutubeCommentsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM youtube_comments WHERE author = ?", Integer.class, seedAuthorId);
        Integer seedRedditPostsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reddit_posts WHERE author = ?", Integer.class, seedAuthorId);
        Integer seedInstagramPostsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM instagram_posts WHERE author = ?", Integer.class, seedAuthorId);

        // Fetch lookalikes based on Tribe
        List<String> lookalikes = jdbcTemplate.queryForList("SELECT global_user_id FROM marketing_target_profiles WHERE tribe_label = ? AND global_user_id != ? LIMIT 5", String.class, tribe, seedAuthorId);

        // Print Verification Audit Report
        System.out.println("====== Verification Audit Report ======");
        System.out.println("Seed Author: " + seedAuthorId);
        
        System.out.println("\n--- Input Audit (Rows Read) ---");
        System.out.println("X Posts: " + seedXPostsCount);
        System.out.println("YouTube Comments: " + seedYoutubeCommentsCount);
        System.out.println("Reddit Posts: " + seedRedditPostsCount);
        System.out.println("Instagram Posts: " + seedInstagramPostsCount);
        
        System.out.println("\n--- Mathematical Proof ---");
        System.out.println("Calculated Hawkes Infectivity (α): " + hawkesInfectivity);
        System.out.println("Magnitude of Influence (MOI): " + moiScore);
        
        System.out.println("\n--- Lookalike Evidence (Tribe: " + tribe + ") ---");
        for (String lookalike : lookalikes) {
            System.out.println("- " + lookalike);
        }
        System.out.println("=======================================");
        
        // Assertions to ensure it doesn't use hardcoded data (we fetched from DB)
        assertNotNull(seedAuthorId, "Seed author should not be null");
        assertFalse(lookalikes.isEmpty(), "Lookalikes list should not be empty");
    }
}