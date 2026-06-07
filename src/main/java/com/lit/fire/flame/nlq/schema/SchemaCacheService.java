package com.lit.fire.flame.nlq.schema;

import com.google.gson.Gson;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stores each registered database's introspected {@link DatabaseSchema} in a table in AuraMath's
 * <b>own</b> database, so the Ask engine can answer from a cached schema instead of introspecting every
 * target live on each request (F13). The {@link SchemaRefreshJob} populates it hourly.
 *
 * <p><b>Structured, not just text.</b> Downstream validation/execution need the structured schema
 * (tables, columns, masked flags), so the full {@link DatabaseSchema} is serialized to JSON with
 * {@link Gson} and reconstructed on read — not merely the rendered prompt text.
 *
 * <p><b>Fail-soft.</b> The cache lives in AuraMath's own Postgres via the autoconfigured
 * {@link JdbcTemplate} (never a per-request target connection). The table is auto-created (it is an
 * internal table). Any SQL failure is swallowed with a warning: {@link #get} returns empty and callers
 * fall back to live introspection, so the cache can never break a request. It stores structure only —
 * no row data, no credentials.
 */
@Service
public class SchemaCacheService {

    private static final Logger log = LoggerFactory.getLogger(SchemaCacheService.class);

    private final AskEngineProperties properties;
    private final JdbcTemplate appJdbcTemplate;
    private final Gson gson = new Gson();
    private final boolean ready;

    @Autowired
    public SchemaCacheService(AskEngineProperties properties, @Nullable JdbcTemplate appJdbcTemplate) {
        this.properties = properties;
        this.appJdbcTemplate = appJdbcTemplate;
        this.ready = ensureTable();
    }

    /** Whether the cache is usable (enabled, a JdbcTemplate is wired, and the table exists). */
    public boolean isReady() {
        return ready;
    }

    /**
     * Upsert the cached schema for {@code database}. Best-effort: a failure is logged (class name only)
     * and otherwise ignored. The schema is stored as JSON; structure only, never row data.
     */
    public void put(String database, DatabaseSchema schema) {
        if (!ready || database == null || schema == null) {
            return;
        }
        String table = table();
        try {
            // Portable upsert (Postgres/SQLite/H2): delete-then-insert by primary key.
            appJdbcTemplate.update("DELETE FROM " + table + " WHERE database_name = ?", database);
            appJdbcTemplate.update(
                    "INSERT INTO " + table
                            + " (database_name, dialect, product_name, schema_json, refreshed_at)"
                            + " VALUES (?,?,?,?,?)",
                    database,
                    schema.getDialect(),
                    schema.getProductName(),
                    gson.toJson(schema),
                    Timestamp.from(Instant.now()));
        } catch (RuntimeException e) {
            log.warn("failed to cache schema for database '{}' into table {}: {}",
                    database, table, e.getClass().getSimpleName());
        }
    }

    /**
     * The cached {@link DatabaseSchema} for {@code database}, or empty if it is not cached or the cache
     * is unavailable. Never throws — on any error it returns empty so the caller introspects live.
     */
    public Optional<DatabaseSchema> get(String database) {
        if (!ready || database == null) {
            return Optional.empty();
        }
        try {
            List<String> rows = appJdbcTemplate.query(
                    "SELECT schema_json FROM " + table() + " WHERE database_name = ?",
                    (rs, n) -> rs.getString(1),
                    database);
            if (rows.isEmpty() || rows.get(0) == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(gson.fromJson(rows.get(0), DatabaseSchema.class));
        } catch (RuntimeException e) {
            log.warn("failed to read cached schema for database '{}': {}",
                    database, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean ensureTable() {
        if (!properties.getSchemaCache().isEnabled()) {
            return false;
        }
        if (appJdbcTemplate == null) {
            log.info("Ask schema cache: no application JdbcTemplate available; the engine will "
                    + "introspect targets live on each request");
            return false;
        }
        String table = table();
        try {
            appJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "database_name VARCHAR(255) PRIMARY KEY, "
                    + "dialect VARCHAR(64), "
                    + "product_name VARCHAR(128), "
                    + "schema_json TEXT, "
                    + "refreshed_at TIMESTAMP)");
            return true;
        } catch (RuntimeException e) {
            log.warn("Ask schema cache: could not ensure table {} exists ({}); falling back to live "
                    + "introspection", table, e.getClass().getSimpleName());
            return false;
        }
    }

    private String table() {
        return properties.getSchemaCache().getTable();
    }
}
