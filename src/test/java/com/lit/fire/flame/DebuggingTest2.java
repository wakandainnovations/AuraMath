package com.lit.fire.flame;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import java.util.Arrays;
import java.util.List;

public class DebuggingTest2 {
    public static void main(String[] args) {
        List<Double> hours = Arrays.asList(
            0.5, 1.0, 1.2, 1.5, 2.0, 2.3, 2.8, 3.5, 4.0, 5.0, 6.0, 7.5, 9.0, 11.0, 14.0, 18.0, 24.0
        );
        double[] sortedData = new double[hours.size()];
        for(int i=0; i<hours.size(); i++) {
            sortedData[i] = hours.get(i) * 3600;
        }
        
        // Simulating with first timestamp subtracted
        long firstTimestamp = (long)sortedData[0];
        double[] adjustedData = new double[sortedData.length];
        for(int i=0; i<sortedData.length; i++) {
            adjustedData[i] = sortedData[i] - firstTimestamp;
        }
        
        SimpleRegression regression = new SimpleRegression();
        int n = adjustedData.length;
        for (int i = 0; i < n; i++) {
            double t = adjustedData[i];
            if (t <= 0) continue; 
            double fT = (i + 1 - 0.3) / (n + 0.4); 
            double x = Math.log(t);
            double y = Math.log(-Math.log(1 - fT));
            regression.addData(x, y);
        }
        
        double k = regression.getSlope(); 
        System.out.println("k (shape) with subtraction: " + k);
    }
}