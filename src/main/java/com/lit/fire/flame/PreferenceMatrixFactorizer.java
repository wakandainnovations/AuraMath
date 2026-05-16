package com.lit.fire.flame;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

import java.util.*;
import java.util.stream.Collectors;

public class PreferenceMatrixFactorizer {

    private final RealMatrix userFactors;
    private final RealMatrix itemFactors;
    private final Map<String, Integer> userMapping;
    private final Map<String, Integer> itemMapping;
    private final RealMatrix interactionMatrix;

    private final double learningRate;
    private final double regularization;
    private final int numEpochs;

    public PreferenceMatrixFactorizer(Map<String, List<String>> interactions, int numFactors, double learningRate, double regularization, int numEpochs) {
        this.learningRate = learningRate;
        this.regularization = regularization;
        this.numEpochs = numEpochs;

        userMapping = new HashMap<>();
        itemMapping = new HashMap<>();
        int userIndex = 0;
        int itemIndex = 0;

        for (String userId : interactions.keySet()) {
            if (!userMapping.containsKey(userId)) {
                userMapping.put(userId, userIndex++);
            }
            for (String itemId : interactions.get(userId)) {
                if (!itemMapping.containsKey(itemId)) {
                    itemMapping.put(itemId, itemIndex++);
                }
            }
        }

        interactionMatrix = new Array2DRowRealMatrix(userMapping.size(), itemMapping.size());
        for (Map.Entry<String, List<String>> entry : interactions.entrySet()) {
            int u = userMapping.get(entry.getKey());
            for (String itemId : entry.getValue()) {
                int i = itemMapping.get(itemId);
                interactionMatrix.setEntry(u, i, 1.0);
            }
        }

        Random random = new Random();
        userFactors = new Array2DRowRealMatrix(userMapping.size(), numFactors);
        itemFactors = new Array2DRowRealMatrix(itemMapping.size(), numFactors);
        userFactors.walkInRowOrder(new org.apache.commons.math3.linear.DefaultRealMatrixChangingVisitor() {
            @Override
            public double visit(int row, int column, double value) {
                return random.nextDouble();
            }
        });
        itemFactors.walkInRowOrder(new org.apache.commons.math3.linear.DefaultRealMatrixChangingVisitor() {
            @Override
            public double visit(int row, int column, double value) {
                return random.nextDouble();
            }
        });
    }

    public void factorize() {
        for (int epoch = 0; epoch < numEpochs; epoch++) {
            for (int u = 0; u < userFactors.getRowDimension(); u++) {
                for (int i = 0; i < itemFactors.getRowDimension(); i++) {
                    if (interactionMatrix.getEntry(u, i) > 0) {
                        double error = interactionMatrix.getEntry(u, i) - userFactors.getRowVector(u).dotProduct(itemFactors.getRowVector(i));
                        RealVector userFactor = userFactors.getRowVector(u);
                        RealVector itemFactor = itemFactors.getRowVector(i);

                        RealVector newUserFactor = userFactor.add(itemFactor.mapMultiply(error).subtract(userFactor.mapMultiply(regularization)).mapMultiply(learningRate));
                        RealVector newItemFactor = itemFactor.add(userFactor.mapMultiply(error).subtract(itemFactor.mapMultiply(regularization)).mapMultiply(learningRate));

                        userFactors.setRowVector(u, newUserFactor);
                        itemFactors.setRowVector(i, newItemFactor);
                    }
                }
            }
        }
    }

    public RealVector getUserFactor(String userId) {
        if (!userMapping.containsKey(userId)) {
            return null; // Or throw an exception
        }
        int userIndex = userMapping.get(userId);
        return userFactors.getRowVector(userIndex);
    }

    public Map<String, RealVector> getAllUserFactors() {
        Map<String, RealVector> allUserFactors = new HashMap<>();
        for (String userId : userMapping.keySet()) {
            allUserFactors.put(userId, getUserFactor(userId));
        }
        return allUserFactors;
    }

    public List<String> getTopPredictedMovies(String authorId, int topN) {
        if (!userMapping.containsKey(authorId)) {
            return Collections.emptyList();
        }

        int userId = userMapping.get(authorId);
        RealVector userVector = userFactors.getRowVector(userId);

        Map<String, Double> predictions = new HashMap<>();
        for (String itemId : itemMapping.keySet()) {
            int i = itemMapping.get(itemId);
            double prediction = userVector.dotProduct(itemFactors.getRowVector(i));
            predictions.put(itemId, prediction);
        }

        return predictions.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
