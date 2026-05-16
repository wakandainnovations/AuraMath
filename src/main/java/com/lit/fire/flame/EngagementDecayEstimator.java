package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.apache.commons.math3.exception.NumberIsTooSmallException;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Estimates the engagement decay for posts using a Weibull distribution.
 * This class analyzes the temporal distribution of likes to predict how long a post
 * will remain relevant.
 */
public class EngagementDecayEstimator {

    private final WeibullDistribution weibull;
    private final double shape; // k
    private final double scale; // lambda

    /**
     * Constructs an EngagementDecayEstimator and fits a Weibull distribution to the provided data.
     *
     * @param posts A stream of posts to model the engagement pattern.
     */
    public EngagementDecayEstimator(Stream<UniversalPost> posts) {
        List<Double> timeToLikeData = posts
                .map(p -> (double) p.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC))
                .sorted()
                .collect(Collectors.toList());

        if (!timeToLikeData.isEmpty()) {
            long firstTimestamp = timeToLikeData.get(0).longValue();
            timeToLikeData = timeToLikeData.stream()
                    .map(t -> (t - firstTimestamp))
                    .collect(Collectors.toList());
        }

        if (timeToLikeData.size() < 2) {
            throw new IllegalArgumentException("Insufficient data to fit the model. At least two data points are required.");
        }

        // The fitter will find the best parameters for the Weibull PDF.
        // f(t) = (k/lambda) * (t/lambda)^(k-1) * exp(-(t/lambda)^k)
        WeibullFitter fitter = WeibullFitter.create();
        double[] parameters;
        try {
            // Convert List<Double> to double[] for the custom fitter
            double[] primitiveData = new double[timeToLikeData.size()];
            for (int i = 0; i < timeToLikeData.size(); i++) {
                primitiveData[i] = timeToLikeData.get(i);
            }
            // The fit method takes a primitive array of data points.
            parameters = fitter.fit(primitiveData);
        } catch (NumberIsTooSmallException e) {
            // This can happen if the data is not suitable for fitting (e.g., all values are the same).
            throw new IllegalArgumentException("The provided data is not suitable for fitting a Weibull distribution.", e);
        }

        this.shape = parameters[0];
        this.scale = parameters[1];
        this.weibull = new WeibullDistribution(this.shape, this.scale);
    }

    /**
     * Predicts the "engagement shelf life" of a post.
     * Shelf life is defined as the time it takes for the engagement rate (density of likes)
     * to drop to 10% of its peak value.
     *
     * @return The estimated time (in the same units as the input data) until engagement drops below 10% of its peak.
     *         Returns Double.POSITIVE_INFINITY if the engagement model does not show a decay pattern (k <= 1).
     */
    public double predictEngagementShelfLife() {
        if (shape <= 1) {
            // For k <= 1, the Weibull PDF is monotonically decreasing.
            // The concept of a peak and subsequent decay as defined here doesn't apply.
            // This indicates that engagement is highest at the very beginning and then continuously declines.
            return Double.POSITIVE_INFINITY;
        }

        // The mode of the Weibull distribution represents the time of peak engagement.
        double t_peak = scale * Math.pow((shape - 1) / shape, 1 / shape);

        // The value of the PDF at this peak time.
        double peakPdfValue = weibull.density(t_peak);

        // The target is 10% of this peak value.
        double targetPdfValue = 0.1 * peakPdfValue;

        // We need to find t > t_peak such that weibull.density(t) = targetPdfValue.
        // This is a transcendental equation, so we use a numerical search method.
        // We'll perform a search starting from t_peak and moving forward in time.

        double currentTime = t_peak;
        double step = Math.max(0.1, scale / 1000.0); // The step size relative to the scale

        // Search for the time when the density drops below the target.
        // We assume the function is decreasing after the peak.
        while (weibull.density(currentTime) > targetPdfValue) {
            currentTime += step;
            // As a safeguard against an infinite loop, we can add a max iteration or time limit.
            if (currentTime > scale * 100) { // Heuristic limit
                return Double.POSITIVE_INFINITY;
            }
        }

        // We can optionally interpolate for a more precise value.
        // For now, returning the upper bound of the interval is sufficient.
        return currentTime;
    }

    /**
     * Returns the shape parameter (k) of the fitted Weibull distribution.
     */
    public double getShape() {
        return shape;
    }

    /**
     * Returns the scale parameter (lambda) of the fitted Weibull distribution.
     */
    public double getScale() {
        return scale;
    }
}