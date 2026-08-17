package com.lit.fire.flame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link UserEngagementRatingService#recomputeAndPersist()} over a mocked
 * {@link JdbcTemplate} (same style as {@link LookalikeDiscoveryServiceTest}) rather than a real
 * Postgres instance: the SQL here is plain SELECT/UPDATE with no Postgres-specific features to
 * exercise, and mocking keeps the test fast, deterministic, and independent of whatever happens
 * to be in the real corpus (~98k raw authors as of this writing).
 *
 * <p>Aggregation key is the raw {@code author} column value verbatim — the same key
 * {@code marketing_target_profiles.global_user_id} is populated with (see the service's class
 * javadoc) — so two rows only merge into one score here if their {@code author} strings are
 * byte-identical; there is no cross-platform identity resolution in this service.
 */
public class UserEngagementRatingServiceTest {

    private static final double DELTA = 0.0001;

    private JdbcTemplate jdbc;
    private UserEngagementRatingService service;
    private final Map<String, Object[]> capturedUpdates = new HashMap<>();

    private static final String AUTHOR_1 = "AuraEngageTestHero"; // x_posts + youtube_comments, identical string
    private static final String AUTHOR_2 = "uertest-sidekick";   // instagram_posts only

    @BeforeEach
    public void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        service = new UserEngagementRatingService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);

        stubXPosts();
        stubYoutubeComments();
        stubInstagramPosts();
        // reddit_posts is left unstubbed: JdbcTemplate.query(String, RowCallbackHandler) is void,
        // so the mock's default no-op answer is equivalent to "table has no rows".
        captureUpdates();
    }

    private void stubXPosts() throws Exception {
        // scoreXPost = 3*comments + 2*shares + 1.5*likes + 1*views = 3*4 + 2*5 + 1.5*10 + 1*100 = 137
        ResultSet row = mockRow(new HashMap<>() {{
            put("author", AUTHOR_1);
            put("comment_count", 4);
            put("shares_count", 5);
            put("likes_count", 10);
            put("views_count", 100);
        }});
        stubTableQuery("FROM x_posts", List.of(row));
    }

    private void stubYoutubeComments() throws Exception {
        // scoreYoutubeComment = 3*replies + 1.5*likes = 3*6 + 1.5*20 = 48
        ResultSet row = mockRow(new HashMap<>() {{
            put("author", AUTHOR_1); // same literal string as the x_posts row -> merges into one score
            put("reply_count", 6);
            put("likes_count", 20);
        }});
        stubTableQuery("FROM youtube_comments", List.of(row));
    }

    private void stubInstagramPosts() throws Exception {
        // scoreInstagramPost = 3*comments + 1.5*likes = 3*1 + 1.5*1 = 4.5
        ResultSet row = mockRow(new HashMap<>() {{
            put("author", AUTHOR_2);
            put("comments_count", 1);
            put("like_count", 1);
        }});
        stubTableQuery("FROM instagram_posts", List.of(row));
    }

    private void stubTableQuery(String sqlFragment, List<ResultSet> rows) {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (ResultSet rs : rows) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(argThat((String sql) -> sql != null && sql.contains(sqlFragment)), any(RowCallbackHandler.class));
    }

    private void captureUpdates() {
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments(); // [sql, raw, rating, globalUserId]
            String globalUserId = (String) args[3];
            capturedUpdates.put(globalUserId, new Object[]{args[1], args[2]});
            return 1;
        }).when(jdbc).update(anyString(), any(), any(), any());
    }

    private static ResultSet mockRow(Map<String, Object> columns) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        for (Map.Entry<String, Object> e : columns.entrySet()) {
            if (e.getValue() instanceof String s) {
                when(rs.getString(e.getKey())).thenReturn(s);
            } else {
                when(rs.getObject(e.getKey(), Integer.class)).thenReturn((Integer) e.getValue());
            }
        }
        return rs;
    }

    @Test
    public void recomputeAndPersistSumsAcrossPlatformsAndBandsWithinRange() {
        Map<String, Object> summary = service.recomputeAndPersist();
        assertEquals(2, summary.get("usersScored"));

        Object[] author1Update = capturedUpdates.get(AUTHOR_1);
        Object[] author2Update = capturedUpdates.get(AUTHOR_2);
        assertTrue(author1Update != null && author2Update != null, "both authors should be persisted");

        double author1Raw = (Double) author1Update[0];
        double author1Rating = (Double) author1Update[1];
        double author2Raw = (Double) author2Update[0];
        double author2Rating = (Double) author2Update[1];

        // 137 (x_posts) + 48 (youtube_comments) summed for the same literal author string across platforms.
        assertEquals(185.0, author1Raw, DELTA, "raw score should sum contributions from both platforms");
        assertEquals(4.5, author2Raw, DELTA, "second author's raw score is instagram-only");

        // Mid-rank formula, n=2 distinct values: (countLess + countLessOrEqual) / 2n.
        // Higher value: (1+2)/4 = 0.75 -> 75. Lower value: (0+1)/4 = 0.25 -> 25.
        assertEquals(75.0, author1Rating, DELTA);
        assertEquals(25.0, author2Rating, DELTA);

        for (double rating : new double[]{author1Rating, author2Rating}) {
            assertTrue(rating >= 0.0 && rating <= 100.0, "engagement_rating should be banded into [0, 100], got " + rating);
        }
    }

    /**
     * Directly covers the tie-handling fix: a naive {@code Arrays.binarySearch}-based rank
     * previously returned an unspecified index among tied values, which for a large tied block
     * could land far from that block's true position (observed in production: ~55k of ~98k
     * authors tied at raw score 0 all landed at rating ~50, the array's midpoint, instead of
     * their actual standing near the bottom). The mid-rank formula gives every member of a tied
     * group the same rating, equal to the midpoint of the rank range that group occupies.
     */
    @Test
    public void tiedRawScoresReceiveTheSameMidRankRating() throws Exception {
        JdbcTemplate tieJdbc = mock(JdbcTemplate.class);
        UserEngagementRatingService tieService = new UserEngagementRatingService();
        ReflectionTestUtils.setField(tieService, "jdbcTemplate", tieJdbc);

        // Two authors tied at score 4.5 (comments=1, likes=1), one ahead at score 45 (comments=10, likes=10).
        ResultSet tieA = mockRow(Map.of("author", "tie-a", "comments_count", 1, "like_count", 1));
        ResultSet tieB = mockRow(Map.of("author", "tie-b", "comments_count", 1, "like_count", 1));
        ResultSet leader = mockRow(Map.of("author", "leader", "comments_count", 10, "like_count", 10));

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (ResultSet rs : List.of(tieA, tieB, leader)) {
                handler.processRow(rs);
            }
            return null;
        }).when(tieJdbc).query(argThat((String sql) -> sql != null && sql.contains("FROM instagram_posts")), any(RowCallbackHandler.class));
        // x_posts/youtube_comments/reddit_posts left unstubbed -> no-op (no rows).

        Map<String, Object[]> tieCaptured = new HashMap<>();
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            tieCaptured.put((String) args[3], new Object[]{args[1], args[2]});
            return 1;
        }).when(tieJdbc).update(anyString(), any(), any(), any());

        tieService.recomputeAndPersist();

        double tieARating = (Double) tieCaptured.get("tie-a")[1];
        double tieBRating = (Double) tieCaptured.get("tie-b")[1];
        double leaderRating = (Double) tieCaptured.get("leader")[1];

        assertEquals(tieARating, tieBRating, DELTA, "tied raw scores must receive identical ratings");
        // n=3, sorted raw = [4.5, 4.5, 45]. Tied value: (countLess=0 + countLessOrEqual=2)/6 = 33.33.
        assertEquals(33.3333, tieARating, 0.01);
        // Leader: (countLess=2 + countLessOrEqual=3)/6 = 83.33.
        assertEquals(83.3333, leaderRating, 0.01);
        assertTrue(leaderRating > tieARating, "untied leader should still outrank the tied pair");
    }
}
