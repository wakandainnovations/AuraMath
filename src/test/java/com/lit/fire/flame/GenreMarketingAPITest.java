package com.lit.fire.flame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link GenreMarketingAPI#channelStrategy(String)} over a mocked {@link JdbcTemplate},
 * same style as {@link EntityMarketingServiceTest}, verifying the read-side switch from the old
 * live unfiltered-scan-plus-classify (now owned by {@link ChannelReachPrecomputer}) reads
 * {@code genre_channel_reach_agg} correctly and leaves the untouched {@code audienceSize} query
 * against {@code marketing_target_profiles} working as before.
 */
public class GenreMarketingAPITest {

    private JdbcTemplate jdbc;
    private GenreMarketingAPI api;

    @BeforeEach
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        api = new GenreMarketingAPI();
        ReflectionTestUtils.setField(api, "jdbc", jdbc);
        ReflectionTestUtils.setField(api, "classifier", new GenreClassifier());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(42L);
    }

    private void stubGenreReachAgg(List<ResultSet> rows) {
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
        stubGenreReachAgg(List.of(mockRow("reddit", 500L, 20L)));

        ResponseEntity<Map<String, Object>> response = api.channelStrategy("horror");
        Map<String, Object> body = response.getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) body.get("channels");
        assertEquals(4, channels.size());
        for (Map<String, Object> ch : channels) {
            if ("Reddit".equals(ch.get("platform"))) {
                assertEquals(500L, ch.get("reach"));
            } else {
                assertEquals(0L, ch.get("reach"), ch.get("platform") + " should default to zero reach");
            }
        }
        assertEquals(42L, body.get("audienceSize"), "audienceSize query is untouched by this change");
    }

    @Test
    public void allZeroReachProducesNoMeasurableReachHeadline() {
        stubGenreReachAgg(List.of());

        ResponseEntity<Map<String, Object>> response = api.channelStrategy("nonexistentgenre");
        Map<String, Object> body = response.getBody();

        assertTrue(((String) body.get("headline")).contains("no measurable reach"),
                "expected the zero-reach headline branch: " + body.get("headline"));
    }
}
