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
 * exercise, and the real local 'aura' DB has ~75k resolved users, so looping the per-user UPDATE
 * (mirroring ConflictBalanceService's one-row-at-a-time persist pattern) against it exhausts this
 * sandbox's connection budget - a scale problem with the unpooled DriverManagerDataSource, not
 * with this service's logic. Mocking keeps the test fast, deterministic, and independent of
 * whatever happens to be in the real corpus.
 */
public class UserEngagementRatingServiceTest {

    private static final double DELTA = 0.0001;

    private JdbcTemplate jdbc;
    private UserEngagementRatingService service;
    private final Map<String, Object[]> capturedUpdates = new HashMap<>();

    private static final String USER_1 = "uertest-user-1"; // x_posts + youtube_comments
    private static final String USER_2 = "uertest-user-2"; // instagram_posts only

    @BeforeEach
    public void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        service = new UserEngagementRatingService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);

        stubIdentityIndex();
        stubXPosts();
        stubYoutubeComments();
        stubInstagramPosts();
        // reddit_posts is left unstubbed: JdbcTemplate.query(String, RowCallbackHandler) is void,
        // so the mock's default no-op answer is equivalent to "table has no rows".
        captureUpdates();
    }

    private void stubIdentityIndex() throws Exception {
        ResultSet row1 = mockRow(Map.of("normalized_author", "auraengagetesthero", "global_user_id", USER_1));
        ResultSet row2 = mockRow(Map.of("normalized_author", "uertestsidekick", "global_user_id", USER_2));
        stubTableQuery("user_identity_link", List.of(row1, row2));
    }

    private void stubXPosts() throws Exception {
        // scoreXPost = 3*comments + 2*shares + 1.5*likes + 1*views = 3*4 + 2*5 + 1.5*10 + 1*100 = 137
        ResultSet row = mockRow(new HashMap<>() {{
            put("author", "Aura_Engage-TestHero");
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
            put("author", "AURA ENGAGE TESTHERO"); // different casing/spacing, same normalized identity
            put("reply_count", 6);
            put("likes_count", 20);
        }});
        stubTableQuery("FROM youtube_comments", List.of(row));
    }

    private void stubInstagramPosts() throws Exception {
        // scoreInstagramPost = 3*comments + 1.5*likes = 3*1 + 1.5*1 = 4.5
        ResultSet row = mockRow(new HashMap<>() {{
            put("author", "uertest-sidekick");
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

        Object[] user1Update = capturedUpdates.get(USER_1);
        Object[] user2Update = capturedUpdates.get(USER_2);
        assertTrue(user1Update != null && user2Update != null, "both resolved users should be persisted");

        double user1Raw = (Double) user1Update[0];
        double user1Rating = (Double) user1Update[1];
        double user2Raw = (Double) user2Update[0];
        double user2Rating = (Double) user2Update[1];

        // 137 (x_posts) + 48 (youtube_comments) summed for the same resolved user across platforms.
        assertEquals(185.0, user1Raw, DELTA, "raw score should sum contributions from both platforms");
        assertEquals(4.5, user2Raw, DELTA, "second user's raw score is instagram-only");

        // Two-user corpus: the higher-raw user ranks at the 1/2 percentile, the lower at 0/2.
        assertEquals(50.0, user1Rating, DELTA);
        assertEquals(0.0, user2Rating, DELTA);

        for (double rating : new double[]{user1Rating, user2Rating}) {
            assertTrue(rating >= 0.0 && rating <= 100.0, "engagement_rating should be banded into [0, 100], got " + rating);
        }
    }
}
