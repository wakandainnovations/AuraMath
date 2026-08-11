package com.lit.fire.flame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link EntityMarketingService#channelStrategy(String, String)} over a mocked
 * {@link JdbcTemplate} (same style as {@link UserEngagementRatingServiceTest}) reading from
 * {@code channel_reach_agg}, verifying the read-side switch from the old live per-request scan
 * (ChannelReachPrecomputer now owns populating that table) preserves the endpoint's behavior:
 * platforms with no aggregate row default to zero reach, and the three headline branches still
 * compute correctly off whatever reach values come back.
 */
public class EntityMarketingServiceTest {

    private JdbcTemplate jdbc;
    private EntityMarketingService service;

    @BeforeEach
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new EntityMarketingService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
    }

    private void stubChannelReachAgg(List<ResultSet> rows) {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (ResultSet rs : rows) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any());
    }

    private static ResultSet mockRow(String platform, long reach, long postCount) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("platform")).thenReturn(platform);
        when(rs.getLong("reach")).thenReturn(reach);
        when(rs.getLong("post_count")).thenReturn(postCount);
        return rs;
    }

    @Test
    public void missingPlatformsDefaultToZeroReach() throws Exception {
        // Only "x" has an aggregate row — youtube/reddit/instagram were never seen for this keyword.
        stubChannelReachAgg(List.of(mockRow("x", 1000L, 10L)));

        Map<String, Object> result = service.channelStrategy("oppenheimer", "Oppenheimer fans");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) result.get("channels");
        assertEquals(4, channels.size());
        for (Map<String, Object> ch : channels) {
            if ("X".equals(ch.get("platform"))) {
                assertEquals(1000L, ch.get("reach"));
                assertEquals(10L, ch.get("postCount"));
            } else {
                assertEquals(0L, ch.get("reach"), ch.get("platform") + " should default to zero reach");
                assertEquals(0L, ch.get("postCount"), ch.get("platform") + " should default to zero postCount");
            }
        }
        assertEquals("X", result.get("topChannel"));
    }

    @Test
    public void allZeroReachProducesNoMeasurableReachHeadline() {
        stubChannelReachAgg(List.of()); // no rows at all for this keyword

        Map<String, Object> result = service.channelStrategy("unknownkeyword", "Nobody");

        assertTrue(((String) result.get("headline")).contains("no measurable reach"),
                "expected the zero-reach headline branch: " + result.get("headline"));
    }

    @Test
    public void relativeStrengthIsRatioToTopChannel() throws Exception {
        stubChannelReachAgg(List.of(
                mockRow("x", 100L, 5L),
                mockRow("youtube", 50L, 2L),
                mockRow("reddit", 25L, 1L),
                mockRow("instagram", 0L, 0L)));

        Map<String, Object> result = service.channelStrategy("keyword", "Entity");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) result.get("channels");
        Map<String, Object> top = channels.get(0);
        assertEquals("X", top.get("platform"));
        assertEquals(1.0, (double) top.get("relative_strength"), 0.0001);

        Map<String, Object> youtube = channels.stream()
                .filter(c -> "YouTube".equals(c.get("platform"))).findFirst().orElseThrow();
        assertEquals(0.5, (double) youtube.get("relative_strength"), 0.0001);
    }
}
