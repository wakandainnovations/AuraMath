package com.lit.fire.flame;

import weka.clusterers.SimpleKMeans;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Segments users into tribes using K-Means clustering on their feature vectors.
 */
public class AudienceSegmenter {

    private SimpleKMeans kMeans;
    private Instances structure;
    private final int numClusters;

    /**
     * @param numClusters The number of tribes to create.
     */
    public AudienceSegmenter(int numClusters) {
        this.numClusters = numClusters;
        this.kMeans = new SimpleKMeans();
        try {
            kMeans.setNumClusters(numClusters);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the dataset structure from feature vectors.
     *
     * @param userFeatureVectors A map where keys are user IDs and values are their feature vectors.
     */
    private void createDatasetStructure(Map<String, double[]> userFeatureVectors) {
        if (userFeatureVectors.isEmpty()) {
            throw new IllegalArgumentException("User feature vectors cannot be empty.");
        }

        int numAttributes = userFeatureVectors.values().iterator().next().length;
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < numAttributes; i++) {
            attributes.add(new Attribute("feature_" + (i + 1)));
        }

        structure = new Instances("UserFeatures", attributes, 0);
    }

    /**
     * Clusters users into tribes.
     *
     * @param userFeatureVectors A map where keys are user IDs and values are their feature vectors.
     * @return A map of user IDs to their assigned tribe ID.
     */
    public Map<String, String> segmentUsers(Map<String, double[]> userFeatureVectors) {
        if (userFeatureVectors.isEmpty()) {
            return new HashMap<>();
        }
        if (structure == null) {
            createDatasetStructure(userFeatureVectors);
        }

        Instances data = new Instances(structure);
        for (double[] features : userFeatureVectors.values()) {
            data.add(new DenseInstance(1.0, features));
        }

        try {
            // K-Means requires at least as many instances as clusters. For tiny / niche inputs,
            // degrade k to the number of users instead of letting Weka throw — otherwise the catch
            // below would return an empty map and every user would lose their tribe assignment.
            int effectiveK = Math.max(1, Math.min(numClusters, data.numInstances()));
            kMeans.setNumClusters(effectiveK);
            kMeans.buildClusterer(data);
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }

        Map<String, String> userToTribe = new HashMap<>();
        int i = 0;
        for (String userId : userFeatureVectors.keySet()) {
            try {
                int cluster = kMeans.clusterInstance(data.get(i));
                userToTribe.put(userId, "Tribe_" + (cluster + 1));
            } catch (Exception e) {
                e.printStackTrace();
            }
            i++;
        }
        return userToTribe;
    }

    /**
     * Generates a summary report of the average characteristics of each tribe.
     *
     * @param userFeatureVectors A map where keys are user IDs and values are their feature vectors.
     * @param userToTribe        A map of user IDs to their assigned tribe ID.
     * @return A string report.
     */
    public String generateTribeSummary(Map<String, double[]> userFeatureVectors, Map<String, String> userToTribe) {
        Instances centroids = kMeans.getClusterCentroids();
        StringBuilder report = new StringBuilder();

        // Use the actual centroid count: k may have been degraded for small inputs (see segmentUsers).
        for (int i = 0; i < centroids.numInstances(); i++) {
            report.append("Tribe_").append(i + 1).append(" Characteristics:\n");
            Instance centroid = centroids.get(i);
            for (int j = 0; j < centroid.numAttributes(); j++) {
                report.append(String.format("  - Average for Feature %d: %.4f\n", j + 1, centroid.value(j)));
            }
            report.append("\n");
        }
        return report.toString();
    }
}
