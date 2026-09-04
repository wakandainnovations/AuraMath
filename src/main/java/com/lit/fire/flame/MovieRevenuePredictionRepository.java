package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * Java-side read access to the three tables {@code movie_revenue_impact_model.py}
 * (Feature 10) and {@code predict_movie.py} (Feature 9) write to:
 * {@code movie_revenue_predictions} (both is_upcoming=true on-demand rows and
 * is_upcoming=false backtested rows -- one table, distinguished by the flag,
 * per the plan), {@code factor_impact_scores} (the served, always-current
 * counterpart to {@code output/factor_impact_scores.csv}), and
 * {@code model_comparison_history} (one appended row per training run).
 *
 * <p>The {@code CREATE TABLE IF NOT EXISTS} calls in {@link #init()} mirror
 * the Python-side schema constants exactly (same convention
 * {@link FactorDefinitionRepository} uses for {@code factor_definitions}) so
 * a GET here 200s with an empty result instead of a raw "relation does not
 * exist" error on a Java-only deployment that hasn't run the Python pipeline
 * yet -- whichever side runs first wins, the other's CREATE is a no-op.
 */
@Repository
public class MovieRevenuePredictionRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS movie_revenue_predictions (" +
            "  movie_name text, release_date text, language text," +
            "  predicted_revenue numeric, confidence_band_low numeric, confidence_band_high numeric," +
            "  actual_revenue numeric, abs_pct_error numeric, is_upcoming boolean default false," +
            "  model_name text, model_version text, factor_keys_used jsonb, generated_at timestamptz," +
            "  primary key (movie_name, release_date, language)" +
            ")");
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS factor_impact_scores (" +
            "  factor_key text PRIMARY KEY, name text, category text, direction text," +
            "  stated_min numeric, stated_max numeric, status text, proxy_note text," +
            "  coverage_pct numeric, corr_with_ln_revenue numeric," +
            "  calibrated_min numeric, calibrated_max numeric, calibration_point_multiplier numeric," +
            "  source text, n_obs integer, beta_p50 numeric, mean_abs_shap numeric," +
            "  generated_at timestamptz" +
            ")");
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS model_comparison_history (" +
            "  id serial PRIMARY KEY, model_name text, run_at timestamptz default now()," +
            "  n_movies integer, within_20pct numeric, within_30pct numeric, within_50pct numeric," +
            "  median_abs_pct_error numeric, factor_keys_used jsonb" +
            ")");
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_movie_revenue_predictions_generated_at " +
            "ON movie_revenue_predictions (generated_at DESC)");
    }

    /** Every row (upcoming or backtested) for a title, most-recent release first. */
    public List<Map<String, Object>> findByMovieName(String movieName) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM movie_revenue_predictions WHERE movie_name = ? " +
            "ORDER BY release_date DESC, language", movieName);
    }

    public List<Map<String, Object>> listFactorImpactScores() {
        return jdbcTemplate.queryForList(
            "SELECT * FROM factor_impact_scores ORDER BY category, factor_key");
    }

    /** Most recent training run's summary metrics, or null if none have been recorded yet. */
    public Map<String, Object> latestModelComparisonHistory() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM model_comparison_history ORDER BY run_at DESC LIMIT 1");
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Backtested rows (predicted vs. actual side by side), newest first. */
    public List<Map<String, Object>> pagedBacktestedPredictions(int page, int size) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM movie_revenue_predictions WHERE is_upcoming = false " +
            "ORDER BY generated_at DESC NULLS LAST, movie_name LIMIT ? OFFSET ?",
            size, (long) page * size);
    }

    public long countBacktestedPredictions() {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM movie_revenue_predictions WHERE is_upcoming = false", Long.class);
        return count == null ? 0L : count;
    }
}
