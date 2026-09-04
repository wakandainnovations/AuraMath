package com.lit.fire.flame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link MovieRevenuePredictionController}'s Feature 10 read endpoints
 * ({@code GET /{movieName}}, {@code /factor-impact-scores}, {@code /accuracy})
 * against the real local 'aura' DB, same convention as
 * {@link FactorDefinitionControllerTest}/{@link UserGraphControllerTest}.
 * Fixture rows go directly into movie_revenue_predictions/factor_impact_scores/
 * model_comparison_history (the tables these endpoints only ever read), all
 * prefixed "ugctest-mrp-" so they can't collide with real data written by
 * movie_revenue_impact_model.py/predict_movie.py.
 */
@SpringBootTest
public class MovieRevenuePredictionControllerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MovieRevenuePredictionController controller;

    private static final String MOVIE = "ugctest-mrp-Sample Film";
    private static final String FACTOR_KEY = "ugctest-mrp-sample-factor";

    private void deleteTestRows() {
        jdbcTemplate.update("DELETE FROM movie_revenue_predictions WHERE movie_name = ?", MOVIE);
        jdbcTemplate.update("DELETE FROM factor_impact_scores WHERE factor_key = ?", FACTOR_KEY);
        jdbcTemplate.update("DELETE FROM model_comparison_history WHERE model_name = 'ugctest-mrp-model'");
    }

    @BeforeEach
    public void setUp() {
        deleteTestRows(); // in case a prior run crashed before cleanup
    }

    @AfterEach
    public void tearDown() {
        deleteTestRows();
    }

    @Test
    public void getByMovieName_returnsBothUpcomingAndBacktestedRows() {
        jdbcTemplate.update(
            "INSERT INTO movie_revenue_predictions " +
            "(movie_name, release_date, language, predicted_revenue, actual_revenue, abs_pct_error, " +
            " is_upcoming, model_name, model_version, factor_keys_used, generated_at) " +
            "VALUES (?, '2019-05-01', 'hindi', 1000000, 950000, 0.0526, false, 'gbr', 'v1', " +
            " ?::jsonb, ?)",
            MOVIE, "[\"" + FACTOR_KEY + "\"]", Timestamp.from(java.time.Instant.now()));
        jdbcTemplate.update(
            "INSERT INTO movie_revenue_predictions " +
            "(movie_name, release_date, language, predicted_revenue, is_upcoming, " +
            " model_name, model_version, factor_keys_used, generated_at) " +
            "VALUES (?, '2027-01-15', 'hindi', 2000000, true, 'gbr', 'v1', ?::jsonb, ?)",
            MOVIE, "[\"" + FACTOR_KEY + "\"]", Timestamp.from(java.time.Instant.now()));

        ResponseEntity<?> resp = controller.getByMovieName(MOVIE);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertEquals(2, body.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> predictions = (List<Map<String, Object>>) body.get("predictions");
        assertEquals(2, predictions.size());
        // factor_keys_used should have been decoded from jsonb into a real list, not a PGobject.
        Object decoded = predictions.get(0).get("factor_keys_used");
        assertTrue(decoded instanceof List, "factor_keys_used should decode to a List, got " + decoded);
    }

    @Test
    public void getByMovieName_unknownTitle_returns404() {
        ResponseEntity<?> resp = controller.getByMovieName("ugctest-mrp-Does Not Exist");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    public void factorImpactScores_includesFixtureRow() {
        jdbcTemplate.update(
            "INSERT INTO factor_impact_scores " +
            "(factor_key, name, category, direction, stated_min, stated_max, status, " +
            " coverage_pct, calibrated_min, calibrated_max, source, n_obs, generated_at) " +
            "VALUES (?, 'Sample Factor', 'Financial', 'Positive', 0.10, 0.20, 'active', " +
            " 12.5, 0.11, 0.19, 'data_fitted', 500, ?)",
            FACTOR_KEY, Timestamp.from(java.time.Instant.now()));

        ResponseEntity<Map<String, Object>> resp = controller.factorImpactScores();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> factors = (List<Map<String, Object>>) body.get("factors");
        assertTrue(factors.stream().anyMatch(f -> FACTOR_KEY.equals(f.get("factor_key"))));
    }

    @Test
    public void accuracy_returnsLatestRunAndPagedBacktestedRows() {
        jdbcTemplate.update(
            "INSERT INTO model_comparison_history " +
            "(model_name, n_movies, within_20pct, within_30pct, within_50pct, " +
            " median_abs_pct_error, factor_keys_used) " +
            "VALUES ('ugctest-mrp-model', 1436, 30.0, 46.1, 49.4, 51.1, ?::jsonb)",
            "[\"" + FACTOR_KEY + "\"]");
        jdbcTemplate.update(
            "INSERT INTO movie_revenue_predictions " +
            "(movie_name, release_date, language, predicted_revenue, actual_revenue, abs_pct_error, " +
            " is_upcoming, model_name, model_version, factor_keys_used, generated_at) " +
            "VALUES (?, '2019-05-01', 'hindi', 1000000, 950000, 0.0526, false, 'gbr', 'v1', " +
            " ?::jsonb, ?)",
            MOVIE, "[\"" + FACTOR_KEY + "\"]", Timestamp.from(java.time.Instant.now()));

        ResponseEntity<Map<String, Object>> resp = controller.accuracy(0, 20);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> latestRun = (Map<String, Object>) body.get("latestRun");
        assertNotNull(latestRun);
        assertEquals("ugctest-mrp-model", latestRun.get("model_name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> moviesPage = (Map<String, Object>) body.get("movies");
        assertNotNull(moviesPage);
        assertTrue(((Number) moviesPage.get("totalElements")).longValue() >= 1);
    }

    @Test
    public void accuracy_rejectsOutOfRangePageSize() {
        ResponseEntity<Map<String, Object>> resp = controller.accuracy(0, 500);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }
}
