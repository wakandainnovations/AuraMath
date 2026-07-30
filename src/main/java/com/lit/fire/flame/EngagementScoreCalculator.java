package com.lit.fire.flame;

/**
 * A utility to quantify raw engagement using a fixed linear weighting of
 * comments, shares, likes, and views (weights 3 : 2 : 1.5 : 1). The weighting
 * is intentionally not normalized or log-scaled here so that callers can
 * decide separately whether to rank on raw totals or a percentile band.
 */
public class EngagementScoreCalculator {

    static double score(double comments, double shares, double likes, double views) {
        return 3.0 * comments + 2.0 * shares + 1.5 * likes + 1.0 * views;
    }

    /**
     * Adapter for x_posts. shares_count was added by the retweet-resolution
     * migration (see RetweetResolver), so rows/columns that predate it may be
     * null; treat those as 0 rather than throwing.
     */
    public static double scoreXPost(Integer commentCount, Integer sharesCount, Integer likesCount, Integer viewsCount) {
        return score(orZero(commentCount), orZero(sharesCount), orZero(likesCount), orZero(viewsCount));
    }

    /**
     * Adapter for youtube_comments. A row here is a comment on a video, not
     * the video itself, so there is no view count or share count to attach to
     * it - both are fixed at 0 rather than accepted as parameters.
     */
    public static double scoreYoutubeComment(Integer replyCount, Integer likesCount) {
        return score(orZero(replyCount), 0.0, orZero(likesCount), 0.0);
    }

    /**
     * Adapter for reddit_posts. Reddit's 'score' column is net upvotes, used
     * as the likes-proxy elsewhere in this codebase (see the comment in
     * RawMappingDiagnosticController). Reddit posts don't track shares or
     * views here, so both are fixed at 0.
     */
    public static double scoreRedditPost(Integer numComments, Integer redditScore) {
        return score(orZero(numComments), 0.0, orZero(redditScore), 0.0);
    }

    /**
     * Adapter for instagram_posts. Shares and views aren't tracked here, so
     * both are fixed at 0.
     */
    public static double scoreInstagramPost(Integer commentsCount, Integer likeCount) {
        return score(orZero(commentsCount), 0.0, orZero(likeCount), 0.0);
    }

    private static double orZero(Integer value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
