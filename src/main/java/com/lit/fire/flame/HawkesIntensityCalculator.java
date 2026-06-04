package com.lit.fire.flame;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import com.lit.fire.flame.models.UniversalPost;
import java.util.Comparator;
import java.util.stream.Collectors;


// Note: This class requires the Apache Commons Math library (e.g., org.apache.commons:commons-math3)
import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.SimpleBounds;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.BOBYQAOptimizer;

/**
 * Calculates the conditional intensity of a user's posting behavior
 * using a self-exciting Hawkes process.
 *
 * This class estimates the background rate (mu) and infectivity factor (alpha)
 * for a given author based on their post timestamps.
 */
public class HawkesIntensityCalculator {

    private final Connection connection;
    private final double beta;

    /**
     * A simple data class to hold the estimated Hawkes process parameters.
     */
    public static class HawkesParameters {
        public final double mu;
        public final double alpha;

        public HawkesParameters(double mu, double alpha) {
            this.mu = mu;
            this.alpha = alpha;
        }

        @Override
        public String toString() {
            return "HawkesParameters{mu=" + mu + ", alpha=" + alpha + "}";
        }
    }

    /**
     * Constructs a HawkesIntensityCalculator.
     *
     * @param connection A JDBC connection to the database.
     * @param beta The decay rate for the exponential kernel of the Hawkes process.
     */
    public HawkesIntensityCalculator(Connection connection, double beta) {
        this.connection = connection;
        this.beta = beta;
    }

    /**
     * Estimates the Hawkes process parameters (mu and alpha) for a given author.
     *
     * @param author The author for whom to estimate the parameters.
     * @return A {@link HawkesParameters} object containing the estimated mu and alpha.
     * @throws SQLException if a database access error occurs.
     */
    public HawkesParameters estimateParameters(String author) throws SQLException {
        List<Double> eventTimes = getEventTimesForAuthor(author);
        return estimateParameters(eventTimes);
    }

    public HawkesParameters estimateParameters(Stream<UniversalPost> posts) {
        List<Double> eventTimes = posts
                .map(p -> (double) p.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC))
                .sorted()
                .collect(Collectors.toList());

        if (!eventTimes.isEmpty()) {
            long firstTimestamp = eventTimes.get(0).longValue();
            eventTimes = eventTimes.stream()
                    .map(t -> (t - firstTimestamp))
                    .collect(Collectors.toList());
        }

        return estimateParameters(eventTimes);
    }

    private HawkesParameters estimateParameters(List<Double> eventTimes) {
        if (eventTimes.size() < 2) {
            // Not enough data to perform estimation.
            // A simple estimation for mu can be done, but alpha is indeterminable.
            if (eventTimes.isEmpty()) {
                return new HawkesParameters(0.0, 0.0);
            }
            double duration = eventTimes.get(eventTimes.size() - 1);
            if (duration == 0) return new HawkesParameters(0.0, 0.0);
            return new HawkesParameters(eventTimes.size() / duration, 0.0);
        }

        final double T = eventTimes.get(eventTimes.size() - 1);

        // The log-likelihood function for a Hawkes process with an exponential kernel.
        // We use a numerical optimizer to find the parameters that maximize this function.
        // This is equivalent to minimizing the negative log-likelihood.
        MultivariateFunction negLogLikelihood = params -> {
            double mu = params[0];
            double alpha = params[1];

            // The term representing the integral of the intensity function
            double integralTerm = -mu * T;
            for (double ti : eventTimes) {
                integralTerm -= (alpha / beta) * (1 - Math.exp(-beta * (T - ti)));
            }

            // The term representing the sum of the log of the intensity at each event time
            double sumLogIntensity = 0;
            for (int i = 0; i < eventTimes.size(); i++) {
                double ti = eventTimes.get(i);
                double intensity = mu;
                for (int j = 0; j < i; j++) {
                    double tj = eventTimes.get(j);
                    intensity += alpha * Math.exp(-beta * (ti - tj));
                }
                if (intensity <= 0) return 1e15; // Invalid intensity: large finite penalty (Infinity breaks BOBYQA's quadratic model)
                sumLogIntensity += Math.log(intensity);
            }

            return -(integralTerm + sumLogIntensity);
        };

        // Use BOBYQAOptimizer for derivative-free optimization with bound constraints.
        // BOBYQA requires FINITE bounds (it uses them to scale variables and size the
        // trust region), so derive a finite, data-driven upper bound for mu rather than
        // passing Double.POSITIVE_INFINITY.
        final double muUpperBound = 10.0 * eventTimes.size() / Math.max(T, 1.0);
        final double alphaRange = beta - 1e-9;

        // The initial trust-region radius must be no more than half the smallest bound
        // range, otherwise the interpolation geometry starts out inconsistent.
        final double initialRadius = Math.min(muUpperBound, alphaRange) / 4.0;
        final double stoppingRadius = 1e-8;

        // Initial guess for [mu, alpha], clamped strictly inside the bounds so the
        // optimizer does not reject a starting point that lies outside the finite box.
        final double muGuess = Math.min(Math.max(0.1, 1e-9), muUpperBound);
        final double alphaGuess = Math.min(Math.max(0.1, 0.0), alphaRange);

        // Number of interpolation points: 2 * num_params + 1
        BOBYQAOptimizer optimizer = new BOBYQAOptimizer(5, initialRadius, stoppingRadius);
        PointValuePair result = optimizer.optimize(
                new MaxEval(2000),
                new ObjectiveFunction(negLogLikelihood),
                GoalType.MINIMIZE,
                new InitialGuess(new double[]{muGuess, alphaGuess}),
                // Bounds for parameters: mu > 0 and 0 <= alpha < beta for stationarity
                new SimpleBounds(new double[]{1e-9, 0}, new double[]{muUpperBound, alphaRange})
        );

        double[] optimalParams = result.getPoint();
        return new HawkesParameters(optimalParams[0], optimalParams[1]);
    }


    /**
     * Retrieves and processes event timestamps for a given author from the database.
     *
     * @param author The author whose posts are to be fetched.
     * @return A list of event times in seconds, relative to the first event.
     * @throws SQLException if a database access error occurs.
     */
    private List<Double> getEventTimesForAuthor(String author) throws SQLException {
        List<Double> eventTimes = new ArrayList<>();
        String sql = "SELECT created_at as event_time FROM x_posts WHERE author = ? " +
                     "UNION ALL " +
                     "SELECT published_at as event_time FROM youtube_comments WHERE author = ? " +
                     "UNION ALL " +
                     "SELECT created_at as event_time FROM reddit_posts WHERE author = ? " +
                     "UNION ALL " +
                     "SELECT timestamp as event_time FROM instagram_posts WHERE author = ? " +
                     "ORDER BY event_time ASC";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, author);
            stmt.setString(2, author);
            stmt.setString(3, author);
            stmt.setString(4, author);
            try (ResultSet rs = stmt.executeQuery()) {
                long firstTimestamp = -1;
                while (rs.next()) {
                    Timestamp timestamp = rs.getTimestamp("event_time");
                    if (timestamp != null) {
                        if (firstTimestamp == -1) {
                            firstTimestamp = timestamp.getTime();
                        }
                        // Convert timestamps to seconds relative to the first event
                        double eventTimeInSeconds = (timestamp.getTime() - firstTimestamp) / 1000.0;
                        eventTimes.add(eventTimeInSeconds);
                    }
                }
            }
        }
        return eventTimes;
    }

    /**
     * Identifies if an author is a "Super Spreader" based on their infectivity factor (alpha).
     *
     * @param author The author to evaluate.
     * @param alphaThreshold The threshold for the alpha parameter to be considered a Super Spreader.
     * @return true if the author's alpha is above the threshold, false otherwise.
     * @throws SQLException if a database access error occurs during parameter estimation.
     */
    public boolean isSuperSpreader(String author, double alphaThreshold) throws SQLException {
        HawkesParameters params = estimateParameters(author);
        return params.alpha > alphaThreshold;
    }
}
