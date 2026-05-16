package com.lit.fire.flame;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import java.util.Arrays;

public class WeibullFitter {
    public static WeibullFitter create() {
        return new WeibullFitter();
    }

    public double[] fit(double[] data) {
        if (data == null || data.length < 2) {
            throw new IllegalArgumentException("Insufficient data for Weibull fitting");
        }

        // 1. Sort the data
        double[] sortedData = data.clone();
        Arrays.sort(sortedData);

        // 2. Rank Regression (Linearization of the Weibull CDF)
        // F(t) = 1 - exp(-(t/lambda)^k)
        // ln(-ln(1 - F(t))) = k * ln(t) - k * ln(lambda)
        // This is a linear equation y = m*x + c
        // where y = ln(-ln(1 - F(t))), x = ln(t), m = k, c = -k * ln(lambda)

        SimpleRegression regression = new SimpleRegression();
        int n = sortedData.length;

        for (int i = 0; i < n; i++) {
            double t = sortedData[i];
            if (t <= 0) continue; // Weibull requires t > 0

            // Median Rank for F(t) to estimate the CDF
            // F(t) = (i + 1 - 0.3) / (n + 0.4)
            double fT = (i + 1 - 0.3) / (n + 0.4); 

            double x = Math.log(t);
            double y = Math.log(-Math.log(1 - fT));

            regression.addData(x, y);
        }

        if (regression.getN() < 2) {
             throw new IllegalArgumentException("Insufficient valid data (t>0) for Weibull fitting");
        }

        double k = regression.getSlope(); // shape parameter
        double c = regression.getIntercept();
        double lambda = Math.exp(-c / k); // scale parameter

        return new double[] { k, lambda };
    }
}
