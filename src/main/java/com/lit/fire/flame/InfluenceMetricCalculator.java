package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A utility to quantify author influence using the Magnitude of Influence (MOI) metric,
 * focusing on the efficiency of converting views into actions.
 */
public class InfluenceMetricCalculator {

    /**
     * Calculates the Magnitude of Influence (MOI) for each author based on post efficiency.
     *
     * @param posts A stream of universal posts.
     * @return A map where keys are author names and values are their MOI scores.
     */
    public static Map<String, Double> calculateMoi(Stream<UniversalPost> posts) {
        // Group posts by author
        Map<String, List<UniversalPost>> postsByAuthor = posts
                .collect(Collectors.groupingBy(UniversalPost::getAuthorId));

        Map<String, Double> moiScores = new HashMap<>();

        for (Map.Entry<String, List<UniversalPost>> entry : postsByAuthor.entrySet()) {
            String author = entry.getKey();
            List<UniversalPost> authorPosts = entry.getValue();

            // Calculate ROA for each post and then the MOI for the author
            double sumOfSquaredRoas = authorPosts.stream()
                    .mapToDouble(post -> {
                        int viewsCount = 0;
                        int likesCount = 0;
                        int commentCount = 0;
                        
                        Map<String, Object> metadata = post.getMetadata();
                        if (metadata != null) {
                            if (metadata.containsKey("views")) {
                                viewsCount = ((Number) metadata.get("views")).intValue();
                            }
                            if (metadata.containsKey("likes")) {
                                likesCount = ((Number) metadata.get("likes")).intValue();
                            }
                            if (metadata.containsKey("comments")) {
                                commentCount = ((Number) metadata.get("comments")).intValue();
                            }
                        }
                        if (viewsCount == 0) {
                            return 0.0; // If views are zero, ROA is 0
                        }
                        double roa = (double) (likesCount + commentCount) / viewsCount;
                        return roa * roa;
                    })
                    .sum();

            if (authorPosts.isEmpty()) {
                moiScores.put(author, 0.0);
            } else {
                double moi = Math.sqrt(sumOfSquaredRoas / authorPosts.size());
                moiScores.put(author, moi);
            }
        }

        return moiScores;
    }

    /**
     * Ranks authors by their MOI score in descending order.
     *
     * @param moiScores A map of author MOI scores.
     * @return A sorted list of map entries.
     */
    public static List<Map.Entry<String, Double>> getRankedAuthors(Map<String, Double> moiScores) {
        return moiScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toList());
    }
}
