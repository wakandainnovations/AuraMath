package com.lit.fire.flame;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code platform} query param on {@link TopSpreadersController}: an unrecognized
 * value is rejected with 400 before touching the DB, omitting it (or passing blank) queries all
 * four platform tables like before, and a recognized value (case-insensitively) restricts the
 * {@code combined_posts} UNION to that platform's table alone.
 *
 * Uses a small {@link JdbcTemplate} subclass rather than Mockito here — the controller passes a
 * pre-built {@code Object[]} of variable length (1 or 4) to the varargs {@code queryForList},
 * which Mockito's varargs matcher support handles awkwardly; overriding the method directly
 * sidesteps that entirely.
 */
public class TopSpreadersControllerTest {

    private static class RecordingJdbcTemplate extends JdbcTemplate {
        String lastSql;
        Object[] lastArgs;
        List<Map<String, Object>> combinedPostsRows = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("marketing_target_profiles")) {
                return List.of();
            }
            lastSql = sql;
            lastArgs = args;
            return combinedPostsRows;
        }
    }

    private TopSpreadersController newController(RecordingJdbcTemplate jdbc) {
        TopSpreadersController controller = new TopSpreadersController();
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(controller, "hawkesIntensityCalculator", new HawkesIntensityCalculator(null, 1.0));
        ReflectionTestUtils.setField(controller, "minPosts", 1);
        return controller;
    }

    private static Map<String, Object> post(String author, String platform, long likes, long comments) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "1");
        row.put("author", author);
        row.put("views_count", 0L);
        row.put("likes_count", likes);
        row.put("comment_count", comments);
        row.put("sentiment_score", 60.0);
        row.put("created_at", Timestamp.valueOf("2026-08-01 00:00:00"));
        row.put("platform", platform);
        return row;
    }

    @Test
    public void unknownPlatformIsRejectedWith400BeforeHittingTheDb() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        TopSpreadersController controller = newController(jdbc);

        ResponseEntity<?> resp = controller.getTopSpreaders("Coolie", "tiktok");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(((String) resp.getBody()).contains("Unknown platform 'tiktok'"));
        assertNull(jdbc.lastSql, "must not hit the DB for an invalid platform");
    }

    @Test
    public void noPlatformParamQueriesAllFourPlatformTables() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.combinedPostsRows = List.of(post("alice", "x_posts", 10, 2));
        TopSpreadersController controller = newController(jdbc);

        ResponseEntity<?> resp = controller.getTopSpreaders("Coolie", null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(jdbc.lastSql.contains("FROM x_posts"));
        assertTrue(jdbc.lastSql.contains("FROM youtube_comments"));
        assertTrue(jdbc.lastSql.contains("FROM reddit_posts"));
        assertTrue(jdbc.lastSql.contains("FROM instagram_posts"));
        assertEquals(4, jdbc.lastArgs.length, "one keyword placeholder per platform");
    }

    @Test
    public void blankPlatformParamBehavesLikeOmitted() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.combinedPostsRows = List.of(post("dave", "reddit_posts", 4, 4));
        TopSpreadersController controller = newController(jdbc);

        ResponseEntity<?> resp = controller.getTopSpreaders("Coolie", "   ");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(4, jdbc.lastArgs.length);
    }

    @Test
    public void platformParamRestrictsQueryToThatPlatformOnly() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.combinedPostsRows = List.of(post("bob", "youtube_comments", 5, 1));
        TopSpreadersController controller = newController(jdbc);

        ResponseEntity<?> resp = controller.getTopSpreaders("Coolie", "youtube");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(jdbc.lastSql.contains("FROM youtube_comments"));
        assertFalse(jdbc.lastSql.contains("FROM x_posts"));
        assertFalse(jdbc.lastSql.contains("FROM reddit_posts"));
        assertFalse(jdbc.lastSql.contains("FROM instagram_posts"));
        assertEquals(1, jdbc.lastArgs.length, "single keyword placeholder for the one selected platform");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) resp.getBody();
        assertEquals(1, body.size());
        assertEquals("bob", body.get(0).get("author"));
    }

    @Test
    public void platformParamIsCaseInsensitive() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.combinedPostsRows = List.of(post("carol", "instagram_posts", 8, 3));
        TopSpreadersController controller = newController(jdbc);

        ResponseEntity<?> resp = controller.getTopSpreaders("Coolie", "INSTAGRAM");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(jdbc.lastSql.contains("FROM instagram_posts"));
        assertEquals(1, jdbc.lastArgs.length);
    }

    @Test
    public void xPlatformParamQueriesOnlyXPosts() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.combinedPostsRows = List.of(post("erin", "x_posts", 20, 5));
        TopSpreadersController controller = newController(jdbc);

        ResponseEntity<?> resp = controller.getTopSpreaders("Coolie", "x");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(jdbc.lastSql.contains("FROM x_posts"));
        assertFalse(jdbc.lastSql.contains("FROM youtube_comments"));
        assertFalse(jdbc.lastSql.contains("FROM reddit_posts"));
        assertFalse(jdbc.lastSql.contains("FROM instagram_posts"));
    }
}
