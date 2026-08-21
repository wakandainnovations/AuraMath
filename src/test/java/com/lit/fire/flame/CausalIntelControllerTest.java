package com.lit.fire.flame;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link CausalIntelController} over a mocked {@link JdbcTemplate} (same style as
 * {@link EntityMarketingServiceTest} / {@link VmiComputationServiceTest}) rather than a real
 * Postgres instance: F1/F4/F5/F7 are plain SELECTs, nothing Postgres-dialect-specific to exercise.
 */
public class CausalIntelControllerTest {

    private CausalIntelController newController(JdbcTemplate jdbc) {
        VmiComputationService vmi = new VmiComputationService();
        ReflectionTestUtils.setField(vmi, "jdbc", jdbc);

        CausalIntelQueryService causalIntel = new CausalIntelQueryService();
        ReflectionTestUtils.setField(causalIntel, "jdbc", jdbc);

        CausalIntelController controller = new CausalIntelController();
        ReflectionTestUtils.setField(controller, "vmi", vmi);
        ReflectionTestUtils.setField(controller, "causalIntel", causalIntel);
        return controller;
    }

    private static void stubOneArgQuery(JdbcTemplate jdbc, String sqlFragment, List<Map<String, Object>> rows) {
        when(jdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains(sqlFragment)),
                any(Object.class)))
                .thenReturn(rows);
    }

    private static void stubNoArgQuery(JdbcTemplate jdbc, String sqlFragment, List<Map<String, Object>> rows) {
        when(jdbc.queryForList(argThat((String sql) -> sql != null && sql.contains(sqlFragment))))
                .thenReturn(rows);
    }

    // -------------------------------------------------------------------------
    // F1 - VMI
    // -------------------------------------------------------------------------

    @Test
    public void vmi_noRows_returnsInsufficientHistoryNotEmptyList() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubOneArgQuery(jdbc, "FROM entity_daily_vmi", List.of());
        CausalIntelController controller = newController(jdbc);

        ResponseEntity<Map<String, Object>> resp = controller.entityVmi(99L);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("insufficient_history", resp.getBody().get("status"));
        assertTrue(resp.getBody().containsKey("details"));
        assertFalse(resp.getBody().containsKey("series"), "must not also carry an empty series array");
    }

    @Test
    public void vmi_withRows_returnsSeriesAndPeakDay() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubOneArgQuery(jdbc, "FROM entity_daily_vmi WHERE entity_id = ? ORDER BY day_index",
                List.of(
                        Map.of("day_index", 0, "daily_engagement_volume", 10.0,
                                "cohort_zscore", 0.5, "cumulative_engagement_volume", 10.0),
                        Map.of("day_index", 1, "daily_engagement_volume", 20.0,
                                "cohort_zscore", 1.5, "cumulative_engagement_volume", 30.0)));
        // peakDay() issues its own separate SELECT ... ORDER BY daily_engagement_volume DESC ...
        stubOneArgQuery(jdbc, "ORDER BY daily_engagement_volume DESC",
                List.of(Map.of("day_index", 1, "calendar_date", java.sql.Date.valueOf("2024-01-02"),
                        "daily_engagement_volume", 20.0)));

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.entityVmi(1L);

        assertEquals("ok", resp.getBody().get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) resp.getBody().get("series");
        assertEquals(2, series.size());
        Map<String, Object> peak = (Map<String, Object>) resp.getBody().get("peakDay");
        assertEquals(1, peak.get("dayIndex"));
    }

    // -------------------------------------------------------------------------
    // F4 - causal chains
    // -------------------------------------------------------------------------

    @Test
    public void causalChains_cohortSpecificRowsFound_neverHidesNEntitiesSupporting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubOneArgQuery(jdbc, "FROM managed_entities",
                List.of(Map.of("industry", "Kollywood", "language", "Tamil")));

        String chainJson = "[{\"from\":\"comment_velocity\",\"to\":\"daily_engagement_volume\","
                + "\"lag\":2,\"q\":0.04,\"effectSize\":0.31}]";
        stubOneArgQuery(jdbc, "FROM causal_precedence_chains",
                List.of(Map.of("chain_json", chainJson, "path_score", 0.96)));
        stubOneArgQuery(jdbc, "FROM causal_precedence_edges",
                List.of(Map.of("from_series", "comment_velocity", "to_series", "daily_engagement_volume",
                        "lag_days", 2, "fdr_q_value", 0.04, "effect_size_r2", 0.31,
                        "n_entities_supporting", 7)));

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.causalChains(1L);

        assertEquals("ok", resp.getBody().get("status"));
        assertEquals("Kollywood|Tamil", resp.getBody().get("cohort"));
        assertEquals("Kollywood|Tamil", resp.getBody().get("resolvedCohort"));
        assertEquals(Boolean.FALSE, resp.getBody().get("usedPooledFallback"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains = (List<Map<String, Object>>) resp.getBody().get("chains");
        assertEquals(1, chains.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) chains.get(0).get("edges");
        assertEquals(1, edges.size());
        assertEquals(7L, edges.get(0).get("n_entities_supporting"),
                "n_entities_supporting must always be surfaced, joined in from causal_precedence_edges");
    }

    @Test
    public void causalChains_noCohortSpecificRows_fallsBackToAll() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubOneArgQuery(jdbc, "FROM managed_entities",
                List.of(Map.of("industry", "Sandalwood", "language", "Kannada")));

        String chainJson = "[{\"from\":\"net_sentiment_delta\",\"to\":\"daily_engagement_volume\","
                + "\"lag\":1,\"q\":0.02,\"effectSize\":0.4}]";
        // Cohort-specific query returns nothing; the same jdbc mock returns the pooled ALL chain
        // whenever the bound cohort argument is "ALL" - simulate via a full when()/thenReturn chain
        // keyed on the bind arg rather than the SQL text, since both calls use the identical SQL.
        when(jdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM causal_precedence_chains")),
                eq("Sandalwood|Kannada")))
                .thenReturn(List.of());
        when(jdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM causal_precedence_chains")),
                eq("ALL")))
                .thenReturn(List.of(Map.of("chain_json", chainJson, "path_score", 0.88)));
        when(jdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM causal_precedence_edges")),
                eq("ALL")))
                .thenReturn(List.of(Map.of("from_series", "net_sentiment_delta", "to_series", "daily_engagement_volume",
                        "lag_days", 1, "fdr_q_value", 0.02, "effect_size_r2", 0.4,
                        "n_entities_supporting", 12)));

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.causalChains(2L);

        assertEquals("ok", resp.getBody().get("status"));
        assertEquals("Sandalwood|Kannada", resp.getBody().get("cohort"));
        assertEquals("ALL", resp.getBody().get("resolvedCohort"));
        assertEquals(Boolean.TRUE, resp.getBody().get("usedPooledFallback"));
    }

    @Test
    public void causalChains_noRowsAtAll_returnsInsufficientHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubOneArgQuery(jdbc, "FROM managed_entities", List.of());
        stubOneArgQuery(jdbc, "FROM causal_precedence_chains", List.of());

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.causalChains(3L);

        assertEquals("insufficient_history", resp.getBody().get("status"));
        assertFalse(resp.getBody().containsKey("chains"));
    }

    // -------------------------------------------------------------------------
    // F5 - nonobvious levers
    // -------------------------------------------------------------------------

    @Test
    public void nonobviousLevers_noRows_returnsInsufficientHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubNoArgQuery(jdbc, "FROM nonobvious_lever_findings", List.of());

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.nonobviousLevers(5L);

        assertEquals("insufficient_history", resp.getBody().get("status"));
        assertFalse(resp.getBody().containsKey("findings"));
    }

    @Test
    public void nonobviousLevers_pooledRowsReturned() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubNoArgQuery(jdbc, "FROM nonobvious_lever_findings",
                List.of(Map.of("feature_name", "checkpoint_gap_days", "test_statistic", 3.2,
                        "p_value", 0.001, "fdr_q_value", 0.01, "direction", "positive", "n_entities", 40)));

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.nonobviousLevers(5L);

        assertEquals("ok", resp.getBody().get("status"));
        assertEquals("ALL", resp.getBody().get("cohort"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) resp.getBody().get("findings");
        assertEquals(1, findings.size());
    }

    // -------------------------------------------------------------------------
    // F7 - playbook
    // -------------------------------------------------------------------------

    @Test
    public void playbook_cohortSpecificRowsFound() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM playbook_patterns")),
                eq("Tollywood|Telugu")))
                .thenReturn(List.of(Map.of(
                        "pattern_sequence", "[\"teaser\",\"trailer\",\"promo\"]",
                        "support_top_tier", 6, "support_bottom_tier", 1,
                        "p_value", 0.01, "fdr_q_value", 0.03, "n_entities", 18)));

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.playbook("Tollywood", "Telugu");

        assertEquals("ok", resp.getBody().get("status"));
        assertEquals("Tollywood|Telugu", resp.getBody().get("cohort"));
        assertEquals("Tollywood|Telugu", resp.getBody().get("resolvedCohort"));
        assertEquals(Boolean.FALSE, resp.getBody().get("usedPooledFallback"));
    }

    @Test
    public void playbook_noCohortSpecificRows_fallsBackToAll() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM playbook_patterns")),
                eq("Mollywood|Malayalam")))
                .thenReturn(List.of());
        when(jdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM playbook_patterns")),
                eq("ALL")))
                .thenReturn(List.of(Map.of(
                        "pattern_sequence", "[\"teaser\",\"trailer\"]",
                        "support_top_tier", 4, "support_bottom_tier", 0,
                        "p_value", 0.02, "fdr_q_value", 0.05, "n_entities", 22)));

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.playbook("Mollywood", "Malayalam");

        assertEquals("ok", resp.getBody().get("status"));
        assertEquals("Mollywood|Malayalam", resp.getBody().get("cohort"));
        assertEquals("ALL", resp.getBody().get("resolvedCohort"));
        assertEquals(Boolean.TRUE, resp.getBody().get("usedPooledFallback"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) resp.getBody().get("patterns");
        assertEquals(1, patterns.size());
    }

    @Test
    public void playbook_noRowsAtAll_returnsInsufficientHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubOneArgQuery(jdbc, "FROM playbook_patterns", List.of());

        CausalIntelController controller = newController(jdbc);
        ResponseEntity<Map<String, Object>> resp = controller.playbook("Ghostwood", "Klingon");

        assertEquals("insufficient_history", resp.getBody().get("status"));
        assertFalse(resp.getBody().containsKey("patterns"));
    }
}
