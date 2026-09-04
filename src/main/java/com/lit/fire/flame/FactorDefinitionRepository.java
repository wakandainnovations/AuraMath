package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java-side access to the {@code factor_definitions}/{@code movie_factor_values}
 * tables (Feature 2) -- the live, queryable replacement for the hardcoded
 * FACTOR_CATALOG list that used to live in {@code movie_revenue_impact_model.py}.
 * Schema here matches {@code scripts/registry/schema.py} exactly (both sides
 * use {@code CREATE TABLE IF NOT EXISTS}, so whichever runs first wins and
 * the other is a no-op); this is the Python-side {@code ensure_factor_registry_schema}'s
 * Java-side equivalent, following the same convention {@code ConflictBalanceService}/
 * {@code NarrativeNoveltyService} use for their own {@code ensureSchema()}.
 *
 * <p>Division of concerns with Feature 1's {@code data_sources} table:
 * {@code data_sources} governs where raw data comes from; {@code factor_definitions}
 * governs which columns the model actually trains on. Kept separate.
 */
@Repository
public class FactorDefinitionRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public static final Set<String> STATUS_VALUES =
        Set.of("candidate", "active", "deprecated", "explanatory_only");
    public static final Set<String> DIRECTION_VALUES = Set.of("Positive", "Negative", "Bidirectional");
    public static final Set<String> DATA_TYPE_VALUES = Set.of("numeric", "boolean", "categorical");
    public static final Set<String> COMPUTATION_TYPE_VALUES =
        Set.of("raw_column", "derived_sql", "derived_python_fn", "eav");

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS factor_definitions (" +
            "  factor_key text PRIMARY KEY," +
            "  name text NOT NULL," +
            "  category text NOT NULL," +
            "  direction text NOT NULL CHECK (direction IN ('Positive', 'Negative', 'Bidirectional'))," +
            "  stated_min numeric NOT NULL," +
            "  stated_max numeric NOT NULL," +
            "  data_type text NOT NULL CHECK (data_type IN ('numeric', 'boolean', 'categorical'))," +
            "  status text NOT NULL DEFAULT 'candidate'" +
            "    CHECK (status IN ('candidate', 'active', 'deprecated', 'explanatory_only'))," +
            "  source_table text," +
            "  source_column text," +
            "  computation_type text" +
            "    CHECK (computation_type IS NULL OR computation_type IN" +
            "           ('raw_column', 'derived_sql', 'derived_python_fn', 'eav'))," +
            "  derivation_ref text," +
            "  added_at timestamptz NOT NULL DEFAULT now()," +
            "  added_by text," +
            "  notes text" +
            ")");
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS movie_factor_values (" +
            "  movie_key text NOT NULL," +
            "  factor_key text NOT NULL REFERENCES factor_definitions (factor_key)," +
            "  value_numeric numeric," +
            "  value_text text," +
            "  computed_at timestamptz NOT NULL DEFAULT now()," +
            "  PRIMARY KEY (movie_key, factor_key)" +
            ")");
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_factor_definitions_status ON factor_definitions (status)");
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_movie_factor_values_factor_key ON movie_factor_values (factor_key)");
    }

    /** @param status optional filter; null/blank returns every row. */
    public List<Map<String, Object>> list(String status) {
        if (status != null && !status.isBlank()) {
            return jdbcTemplate.queryForList(
                "SELECT * FROM factor_definitions WHERE status = ? ORDER BY factor_key", status);
        }
        return jdbcTemplate.queryForList("SELECT * FROM factor_definitions ORDER BY factor_key");
    }

    public Map<String, Object> get(String factorKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM factor_definitions WHERE factor_key = ?", factorKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Upsert on factor_key -- the "add a parameter" operation Feature 2 exists for. */
    public void upsert(FactorDefinitionRequest r) {
        jdbcTemplate.update(
            "INSERT INTO factor_definitions " +
            "  (factor_key, name, category, direction, stated_min, stated_max, data_type, status," +
            "   source_table, source_column, computation_type, derivation_ref, added_by, notes) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (factor_key) DO UPDATE SET " +
            "  name = EXCLUDED.name, category = EXCLUDED.category, direction = EXCLUDED.direction," +
            "  stated_min = EXCLUDED.stated_min, stated_max = EXCLUDED.stated_max," +
            "  data_type = EXCLUDED.data_type, status = EXCLUDED.status," +
            "  source_table = EXCLUDED.source_table, source_column = EXCLUDED.source_column," +
            "  computation_type = EXCLUDED.computation_type, derivation_ref = EXCLUDED.derivation_ref," +
            "  notes = EXCLUDED.notes",
            r.factorKey(), r.name(), r.category(), r.direction(), r.statedMin(), r.statedMax(),
            r.dataType() == null ? "numeric" : r.dataType(), r.status() == null ? "candidate" : r.status(),
            r.sourceTable(), r.sourceColumn(), r.computationType(), r.derivationRef(),
            r.addedBy(), r.notes());
    }

    /** Promote candidate -&gt; active, or deprecate. @return false if factorKey doesn't exist. */
    public boolean updateStatus(String factorKey, String status) {
        int updated = jdbcTemplate.update(
            "UPDATE factor_definitions SET status = ? WHERE factor_key = ?", status, factorKey);
        return updated > 0;
    }

    /**
     * Bulk upsert into {@code movie_factor_values} -- the "hand the system a
     * spreadsheet of scores" path. {@code movie_key} is the same
     * {@code movie_name|release_date|language} composite Feature 1's
     * {@code data_sources} uses.
     *
     * @return number of rows upserted
     */
    public int bulkUpsertValues(List<FactorValueEntry> entries) {
        String sql =
            "INSERT INTO movie_factor_values (movie_key, factor_key, value_numeric, value_text, computed_at) " +
            "VALUES (?, ?, ?, ?, now()) " +
            "ON CONFLICT (movie_key, factor_key) DO UPDATE SET " +
            "  value_numeric = EXCLUDED.value_numeric, value_text = EXCLUDED.value_text," +
            "  computed_at = EXCLUDED.computed_at";
        int count = 0;
        for (FactorValueEntry e : entries) {
            String movieKey = e.movieKey() != null && !e.movieKey().isBlank()
                ? e.movieKey()
                : e.movieName() + "|" + e.releaseDate() + "|" + e.language();
            jdbcTemplate.update(sql, movieKey, e.factorKey(), e.valueNumeric(), e.valueText());
            count++;
        }
        return count;
    }

    /** Status counts for the coverage report (Feature 11 reads this shape). */
    public Map<String, Integer> statusCounts() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT status, count(*) AS n FROM factor_definitions GROUP BY status");
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            out.put((String) row.get("status"), ((Number) row.get("n")).intValue());
        }
        return out;
    }

    public record FactorDefinitionRequest(
        String factorKey, String name, String category, String direction,
        Double statedMin, Double statedMax, String dataType, String status,
        String sourceTable, String sourceColumn, String computationType,
        String derivationRef, String addedBy, String notes) {}

    public record FactorValueEntry(
        String movieKey, String movieName, String releaseDate, String language,
        String factorKey, Double valueNumeric, String valueText) {}

    /** Validates the free-text fields against the same CHECK-constraint value
     * sets the DB enforces, so a bad request 400s with a clear message instead
     * of surfacing a raw SQL constraint-violation error. */
    public static List<String> validate(FactorDefinitionRequest r, boolean requireCoreFields) {
        List<String> errors = new ArrayList<>();
        if (requireCoreFields) {
            if (isBlank(r.factorKey())) errors.add("factorKey is required");
            if (isBlank(r.name())) errors.add("name is required");
            if (isBlank(r.category())) errors.add("category is required");
            if (r.statedMin() == null) errors.add("statedMin is required");
            if (r.statedMax() == null) errors.add("statedMax is required");
        }
        if (r.direction() != null && !DIRECTION_VALUES.contains(r.direction())) {
            errors.add("direction must be one of " + DIRECTION_VALUES);
        }
        if (r.dataType() != null && !DATA_TYPE_VALUES.contains(r.dataType())) {
            errors.add("dataType must be one of " + DATA_TYPE_VALUES);
        }
        if (r.status() != null && !STATUS_VALUES.contains(r.status())) {
            errors.add("status must be one of " + STATUS_VALUES);
        }
        if (r.computationType() != null && !COMPUTATION_TYPE_VALUES.contains(r.computationType())) {
            errors.add("computationType must be one of " + COMPUTATION_TYPE_VALUES);
        }
        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
