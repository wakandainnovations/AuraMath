package com.lit.fire.flame;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link VmiComputationService#recomputeAndPersist()} over a mocked {@link JdbcTemplate}
 * (same style as {@link UserEngagementRatingServiceTest}) rather than a real Postgres instance:
 * the SQL here is plain SELECT/JOIN/UPSERT with no Postgres-specific dialect features (no
 * regexp_match, no similarity()) to exercise, so mocking keeps the test fast and deterministic.
 */
public class VmiComputationServiceTest {

    private static final double DELTA = 1e-6;

    private VmiComputationService newService(JdbcTemplate jdbc) {
        VmiComputationService service = new VmiComputationService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        return service;
    }

    private static void stubTableQuery(JdbcTemplate jdbc, String sqlFragment, List<ResultSet> rows) {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (ResultSet rs : rows) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(argThat((String sql) -> sql != null && sql.contains(sqlFragment)), any(RowCallbackHandler.class));
    }

    private static void stubCohorts(JdbcTemplate jdbc, Object[]... idIndustryLanguage) throws Exception {
        List<ResultSet> rows = new ArrayList<>();
        for (Object[] row : idIndustryLanguage) {
            rows.add(mockCohortRow((Long) row[0], (String) row[1], (String) row[2]));
        }
        stubTableQuery(jdbc, "SELECT id, industry, language FROM managed_entities", rows);
    }

    private static ResultSet mockCohortRow(long id, String industry, String language) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("industry")).thenReturn(industry);
        when(rs.getString("language")).thenReturn(language);
        return rs;
    }

    /** entity_id + event_time + a single integer engagement column, for the simple test fixtures below. */
    private static ResultSet mockXPostRow(long entityId, String timestamp, int commentCount, int viewsCount) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("entity_id")).thenReturn(entityId);
        when(rs.getTimestamp("event_time")).thenReturn(Timestamp.valueOf(timestamp));
        when(rs.getObject("comment_count", Integer.class)).thenReturn(commentCount);
        when(rs.getObject("shares_count", Integer.class)).thenReturn(0);
        when(rs.getObject("likes_count", Integer.class)).thenReturn(0);
        when(rs.getObject("views_count", Integer.class)).thenReturn(viewsCount);
        return rs;
    }

    /** Records the args of every jdbc.batchUpdate(...) call (in order) into {@code sink} and returns a fake per-row update count. */
    private static void captureBatchUpdates(JdbcTemplate jdbc, List<List<Object[]>> sink) {
        doAnswer(invocation -> {
            List<Object[]> args = invocation.getArgument(1);
            sink.add(new ArrayList<>(args));
            int[] result = new int[args.size()];
            java.util.Arrays.fill(result, 1);
            return result;
        }).when(jdbc).batchUpdate(anyString(), anyList(), any(int[].class));
    }

    @Test
    public void dailyEngagementVolumeAndDayIndexAlignForAFixedFixture() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VmiComputationService service = newService(jdbc);

        stubCohorts(jdbc, new Object[]{1L, "Kollywood", "Tamil"});

        // Entity 1's first tracked mention is 2024-01-01 (day_index 0): two posts that day
        // (scores 6 and 4, EngagementScoreCalculator.score = 3*comments + views), then a gap day
        // with no posts, then one more post on 2024-01-03 (day_index 2, score 4).
        stubTableQuery(jdbc, "JOIN x_posts", List.of(
                mockXPostRow(1L, "2024-01-01 10:00:00", 2, 0),  // score = 3*2 = 6
                mockXPostRow(1L, "2024-01-01 15:00:00", 0, 4),  // score = 1*4 = 4
                mockXPostRow(1L, "2024-01-03 09:00:00", 0, 4))); // score = 1*4 = 4, day_index = 2

        List<List<Object[]>> batches = new ArrayList<>();
        captureBatchUpdates(jdbc, batches);

        service.recomputeAndPersist();

        assertEquals(1, batches.size());
        List<Object[]> rows = batches.get(0);
        assertEquals(2, rows.size(), "one row for day_index 0, one for day_index 2 - the gap day is not materialized");

        Map<Integer, Object[]> byDayIndex = new HashMap<>();
        for (Object[] row : rows) {
            byDayIndex.put((Integer) row[1], row);
        }

        Object[] day0 = byDayIndex.get(0);
        assertEquals(1L, day0[0]);
        assertEquals(Date.valueOf("2024-01-01"), day0[2]);
        assertEquals(10.0, (Double) day0[3], DELTA, "day 0 volume sums both same-day posts: 6 + 4");
        assertNull(day0[4], "singleton cohort never reaches the 4-entity z-score threshold");
        assertEquals(10.0, (Double) day0[5], DELTA, "cumulative at day 0 equals day 0's volume");

        Object[] day2 = byDayIndex.get(2);
        assertEquals(Date.valueOf("2024-01-03"), day2[2]);
        assertEquals(4.0, (Double) day2[3], DELTA);
        assertEquals(14.0, (Double) day2[5], DELTA, "cumulative at day 2 is 10 (day 0) + 4 (day 2)");
    }

    @Test
    public void cohortZscoreIsNullBelowTheFourEntityThreshold() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VmiComputationService service = newService(jdbc);

        // Three entities, same cohort, each with exactly one post on the same calendar day (so
        // each entity's own day_index 0 lines up on the same day_index) - below MIN_COHORT_SIZE_FOR_ZSCORE.
        stubCohorts(jdbc,
                new Object[]{1L, "Sandalwood", "Kannada"},
                new Object[]{2L, "Sandalwood", "Kannada"},
                new Object[]{3L, "Sandalwood", "Kannada"});

        stubTableQuery(jdbc, "JOIN x_posts", List.of(
                mockXPostRow(1L, "2024-03-01 10:00:00", 0, 4),
                mockXPostRow(2L, "2024-03-01 10:00:00", 0, 6),
                mockXPostRow(3L, "2024-03-01 10:00:00", 0, 8)));

        List<List<Object[]>> batches = new ArrayList<>();
        captureBatchUpdates(jdbc, batches);

        service.recomputeAndPersist();

        List<Object[]> rows = batches.get(0);
        assertEquals(3, rows.size());
        for (Object[] row : rows) {
            assertNull(row[4], "cohort has only 3 entities at day_index 0, below the 4-entity floor");
        }
    }

    @Test
    public void cohortZscoreIsComputedAtTheFourEntityThreshold() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VmiComputationService service = newService(jdbc);

        stubCohorts(jdbc,
                new Object[]{1L, "Sandalwood", "Kannada"},
                new Object[]{2L, "Sandalwood", "Kannada"},
                new Object[]{3L, "Sandalwood", "Kannada"},
                new Object[]{4L, "Sandalwood", "Kannada"});

        // Scores 4, 6, 8, 10 (views_count only, weight 1.0) all at day_index 0.
        // mean = 7, population stddev = sqrt(5) = 2.2360679...
        stubTableQuery(jdbc, "JOIN x_posts", List.of(
                mockXPostRow(1L, "2024-03-01 10:00:00", 0, 4),
                mockXPostRow(2L, "2024-03-01 10:00:00", 0, 6),
                mockXPostRow(3L, "2024-03-01 10:00:00", 0, 8),
                mockXPostRow(4L, "2024-03-01 10:00:00", 0, 10)));

        List<List<Object[]>> batches = new ArrayList<>();
        captureBatchUpdates(jdbc, batches);

        service.recomputeAndPersist();

        Map<Long, Object[]> byEntity = new HashMap<>();
        for (Object[] row : batches.get(0)) {
            byEntity.put((Long) row[0], row);
        }

        assertEquals(-1.3416407864998738, (Double) byEntity.get(1L)[4], DELTA);
        assertEquals(-0.4472135954999579, (Double) byEntity.get(2L)[4], DELTA);
        assertEquals(0.4472135954999579, (Double) byEntity.get(3L)[4], DELTA);
        assertEquals(1.3416407864998738, (Double) byEntity.get(4L)[4], DELTA);
    }

    @Test
    public void reRunningRecomputeIsIdempotent() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VmiComputationService service = newService(jdbc);

        stubCohorts(jdbc, new Object[]{1L, "Kollywood", "Tamil"});
        stubTableQuery(jdbc, "JOIN x_posts", List.of(
                mockXPostRow(1L, "2024-01-01 10:00:00", 2, 0),
                mockXPostRow(1L, "2024-01-03 09:00:00", 0, 4)));

        List<List<Object[]>> batches = new ArrayList<>();
        captureBatchUpdates(jdbc, batches);

        service.recomputeAndPersist();
        service.recomputeAndPersist();

        assertEquals(2, batches.size());
        List<Object[]> first = batches.get(0);
        List<Object[]> second = batches.get(1);
        assertEquals(first.size(), second.size(), "re-running must not duplicate or drop rows");

        Map<Integer, Object[]> firstByDayIndex = new HashMap<>();
        for (Object[] row : first) firstByDayIndex.put((Integer) row[1], row);
        Map<Integer, Object[]> secondByDayIndex = new HashMap<>();
        for (Object[] row : second) secondByDayIndex.put((Integer) row[1], row);

        for (Integer dayIndex : firstByDayIndex.keySet()) {
            Object[] a = firstByDayIndex.get(dayIndex);
            Object[] b = secondByDayIndex.get(dayIndex);
            assertEquals(a[0], b[0], "entity_id");
            assertEquals(a[2], b[2], "calendar_date");
            assertEquals((Double) a[3], (Double) b[3], DELTA, "daily_engagement_volume");
            assertEquals((Double) a[5], (Double) b[5], DELTA, "cumulative_engagement_volume");
        }
    }
}
