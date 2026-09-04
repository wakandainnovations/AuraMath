package com.lit.fire.flame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link FactorDefinitionController} (Feature 2's Java admin surface
 * for the factor registry) against the real local 'aura' DB, same convention
 * as {@link UserGraphControllerTest}. Every fixture factor_key is prefixed
 * with "ugctest-fd-" so it can't collide with the real 80-entry catalogue
 * seeded by migrate_factor_definitions.py.
 */
@SpringBootTest
public class FactorDefinitionControllerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FactorDefinitionController controller;

    private static final String KEY = "ugctest-fd-sample";
    private static final String KEY_2 = "ugctest-fd-sample-two";

    private void deleteTestRows() {
        jdbcTemplate.update("DELETE FROM movie_factor_values WHERE factor_key LIKE 'ugctest-fd-%'");
        jdbcTemplate.update("DELETE FROM factor_definitions WHERE factor_key LIKE 'ugctest-fd-%'");
    }

    @BeforeEach
    public void setUp() {
        deleteTestRows(); // in case a prior run crashed before cleanup
    }

    @AfterEach
    public void tearDown() {
        deleteTestRows();
    }

    private FactorDefinitionRepository.FactorDefinitionRequest sampleRequest(String key, String status) {
        return new FactorDefinitionRepository.FactorDefinitionRequest(
            key, "Sample Factor", "Financial", "Positive", 0.10, 0.20, "numeric", status,
            "ticket_price_index", "atp_usd", "raw_column", null, "test-suite", "a note");
    }

    @Test
    public void upsert_thenGet_roundTrips() {
        ResponseEntity<?> postResp = controller.upsert(sampleRequest(KEY, "candidate"));
        assertEquals(HttpStatus.OK, postResp.getStatusCode());

        ResponseEntity<?> getResp = controller.getOne(KEY);
        assertEquals(HttpStatus.OK, getResp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) getResp.getBody();
        assertEquals(KEY, row.get("factor_key"));
        assertEquals("Sample Factor", row.get("name"));
        assertEquals("candidate", row.get("status"));
        assertEquals("raw_column", row.get("computation_type"));
    }

    @Test
    public void upsert_secondCallUpdatesInPlace() {
        controller.upsert(sampleRequest(KEY, "candidate"));

        var updated = new FactorDefinitionRepository.FactorDefinitionRequest(
            KEY, "Renamed Factor", "Financial", "Positive", 0.10, 0.20, "numeric", "candidate",
            "ticket_price_index", "atp_usd", "raw_column", null, "test-suite", "updated note");
        controller.upsert(updated);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM factor_definitions WHERE factor_key = ?", KEY);
        assertEquals(1, rows.size(), "upsert must not create a duplicate row");
        assertEquals("Renamed Factor", rows.get(0).get("name"));
    }

    @Test
    public void upsert_missingRequiredField_returns400() {
        var bad = new FactorDefinitionRepository.FactorDefinitionRequest(
            KEY, null, "Financial", "Positive", 0.1, 0.2, "numeric", "candidate",
            null, null, null, null, null, null);
        ResponseEntity<?> resp = controller.upsert(bad);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void upsert_invalidDirection_returns400() {
        var bad = new FactorDefinitionRepository.FactorDefinitionRequest(
            KEY, "Sample", "Financial", "Sideways", 0.1, 0.2, "numeric", "candidate",
            null, null, null, null, null, null);
        ResponseEntity<?> resp = controller.upsert(bad);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void updateStatus_promotesCandidateToActive() {
        controller.upsert(sampleRequest(KEY, "candidate"));

        ResponseEntity<?> resp = controller.updateStatus(KEY, new FactorDefinitionController.StatusUpdateRequest("active"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT status FROM factor_definitions WHERE factor_key = ?", KEY);
        assertEquals("active", rows.get(0).get("status"));
    }

    @Test
    public void updateStatus_unknownKey_returns404() {
        ResponseEntity<?> resp = controller.updateStatus(
            "ugctest-fd-does-not-exist", new FactorDefinitionController.StatusUpdateRequest("active"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    public void updateStatus_invalidStatus_returns400() {
        controller.upsert(sampleRequest(KEY, "candidate"));
        ResponseEntity<?> resp = controller.updateStatus(KEY, new FactorDefinitionController.StatusUpdateRequest("bogus"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void list_filtersByStatus() {
        controller.upsert(sampleRequest(KEY, "active"));
        controller.upsert(sampleRequest(KEY_2, "candidate"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activeOnly =
            (List<Map<String, Object>>) controller.list("active").getBody().get("factors");
        assertTrue(activeOnly.stream().anyMatch(r -> KEY.equals(r.get("factor_key"))));
        assertTrue(activeOnly.stream().noneMatch(r -> KEY_2.equals(r.get("factor_key"))));
    }

    @Test
    public void list_invalidStatusFilter_returns400() {
        ResponseEntity<?> resp = controller.list("not-a-real-status");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void bulkUpsertValues_viaMovieKeyDirectly() {
        controller.upsert(sampleRequest(KEY, "active"));
        var entry = new FactorDefinitionRepository.FactorValueEntry(
            "Some Movie|2025-01-01|hindi", null, null, null, KEY, 0.42, null);
        var req = new FactorDefinitionController.FactorValuesBulkRequest(List.of(entry));

        ResponseEntity<?> resp = controller.bulkUpsertValues(req);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM movie_factor_values WHERE factor_key = ?", KEY);
        assertEquals(1, rows.size());
        assertEquals("Some Movie|2025-01-01|hindi", rows.get(0).get("movie_key"));
        assertEquals(0.42, ((Number) rows.get(0).get("value_numeric")).doubleValue(), 1e-9);
    }

    @Test
    public void bulkUpsertValues_viaCompositeFields_buildsMovieKey() {
        controller.upsert(sampleRequest(KEY, "active"));
        var entry = new FactorDefinitionRepository.FactorValueEntry(
            null, "Another Movie", "2024-06-15", "tamil", KEY, 1.5, null);
        var req = new FactorDefinitionController.FactorValuesBulkRequest(List.of(entry));

        controller.bulkUpsertValues(req);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT movie_key FROM movie_factor_values WHERE factor_key = ?", KEY);
        assertEquals("Another Movie|2024-06-15|tamil", rows.get(0).get("movie_key"));
    }

    @Test
    public void bulkUpsertValues_reupsertUpdatesInPlace() {
        controller.upsert(sampleRequest(KEY, "active"));
        var entry1 = new FactorDefinitionRepository.FactorValueEntry(
            "Some Movie|2025-01-01|hindi", null, null, null, KEY, 0.10, null);
        controller.bulkUpsertValues(new FactorDefinitionController.FactorValuesBulkRequest(List.of(entry1)));

        var entry2 = new FactorDefinitionRepository.FactorValueEntry(
            "Some Movie|2025-01-01|hindi", null, null, null, KEY, 0.99, null);
        controller.bulkUpsertValues(new FactorDefinitionController.FactorValuesBulkRequest(List.of(entry2)));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM movie_factor_values WHERE factor_key = ?", KEY);
        assertEquals(1, rows.size(), "re-upserting the same (movie_key, factor_key) must not duplicate");
        assertEquals(0.99, ((Number) rows.get(0).get("value_numeric")).doubleValue(), 1e-9);
    }

    @Test
    public void bulkUpsertValues_missingIdentifier_returns400() {
        var entry = new FactorDefinitionRepository.FactorValueEntry(
            null, null, null, null, KEY, 1.0, null);
        ResponseEntity<?> resp = controller.bulkUpsertValues(
            new FactorDefinitionController.FactorValuesBulkRequest(List.of(entry)));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void bulkUpsertValues_emptyList_returns400() {
        ResponseEntity<?> resp = controller.bulkUpsertValues(
            new FactorDefinitionController.FactorValuesBulkRequest(List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void statusCounts_reflectsSeededRows() {
        controller.upsert(sampleRequest(KEY, "active"));
        controller.upsert(sampleRequest(KEY_2, "candidate"));

        ResponseEntity<Map<String, Integer>> resp = controller.statusCounts();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getOrDefault("active", 0) >= 1);
        assertTrue(resp.getBody().getOrDefault("candidate", 0) >= 1);
    }
}
