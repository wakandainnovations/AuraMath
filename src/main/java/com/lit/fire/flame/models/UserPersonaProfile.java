package com.lit.fire.flame.models;

import java.util.Map;

public class UserPersonaProfile {

    private String userId;
    private String tribe;
    private double infectivityScore;
    private Map<String, Double> averageAspectSentiments;

    public UserPersonaProfile(String userId, String tribe, double infectivityScore, Map<String, Double> averageAspectSentiments) {
        this.userId = userId;
        this.tribe = tribe;
        this.infectivityScore = infectivityScore;
        this.averageAspectSentiments = averageAspectSentiments;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTribe() {
        return tribe;
    }

    public void setTribe(String tribe) {
        this.tribe = tribe;
    }

    public double getInfectivityScore() {
        return infectivityScore;
    }

    public void setInfectivityScore(double infectivityScore) {
        this.infectivityScore = infectivityScore;
    }

    public Map<String, Double> getAverageAspectSentiments() {
        return averageAspectSentiments;
    }

    public void setAverageAspectSentiments(Map<String, Double> averageAspectSentiments) {
        this.averageAspectSentiments = averageAspectSentiments;
    }
}
