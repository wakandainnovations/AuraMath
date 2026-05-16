package com.lit.fire.flame;

public class UserActivityProfiler {

    private static final int HIGH_VIEWS_THRESHOLD = 10000;
    private static final int LOW_VIEWS_THRESHOLD = 1000;
    private static final int HIGH_COMMENTS_THRESHOLD = 100;

    public enum UserCategory {
        LURKER,
        ENGAGER,
        SUPER_FAN,
        NOISE_MAKER,
        LOW_ACTIVITY,
        UNKNOWN
    }

    /**
     * Categorizes a user into one of the predefined categories based on their view and comment counts.
     *
     * @param viewsCount   The total number of views for the user.
     * @param commentCount The total number of comments for the user.
     * @return The UserCategory for the user.
     */
    public UserCategory categorize(long viewsCount, long commentCount) {
        boolean isHighViews = viewsCount > HIGH_VIEWS_THRESHOLD;
        boolean isLowViews = viewsCount < LOW_VIEWS_THRESHOLD;
        boolean isMediumViews = !isHighViews && !isLowViews;

        boolean isHighComments = commentCount > HIGH_COMMENTS_THRESHOLD;
        boolean hasZeroComments = commentCount == 0;

        if (isHighViews && hasZeroComments) {
            return UserCategory.LURKER;
        } else if (isMediumViews && isHighComments) {
            return UserCategory.ENGAGER;
        } else if (isHighViews && isHighComments) {
            return UserCategory.SUPER_FAN;
        } else if (isLowViews && isHighComments) {
            return UserCategory.NOISE_MAKER;
        } else if (viewsCount < LOW_VIEWS_THRESHOLD && commentCount < HIGH_COMMENTS_THRESHOLD) {
            return UserCategory.LOW_ACTIVITY;
        }

        return UserCategory.UNKNOWN;
    }

    /**
     * Calculates the ratio of total views to total comments.
     *
     * @param totalViews    The total number of views for the user.
     * @param totalComments The total number of comments for the user.
     * @return The ratio of views to comments. Returns -1 if comments are zero to avoid division by zero.
     */
    public double calculateViewToCommentRatio(long totalViews, long totalComments) {
        if (totalComments == 0) {
            return -1.0; // Or throw an exception, depending on desired behavior.
        }
        return (double) totalViews / totalComments;
    }
}
