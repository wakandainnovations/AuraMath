package com.lit.fire.flame.nlq.schema;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link SchemaCacheService} round-trip against a real (file-backed SQLite) JdbcTemplate: a
 * structured {@link DatabaseSchema} is stored as JSON and reconstructed with its tables, columns, and
 * masked flags intact. Also verifies it degrades to empty (never throwing) with no JdbcTemplate.
 */
class SchemaCacheServiceTest {

    private Path dbFile;

    @BeforeEach
    void setup() throws IOException {
        dbFile = Files.createTempFile("ask-cache-", ".db");
        Files.deleteIfExists(dbFile);
    }

    @AfterEach
    void cleanup() throws IOException {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return new JdbcTemplate(ds);
    }

    private DatabaseSchema sampleSchema() {
        ColumnInfo id = new ColumnInfo("id", Types.INTEGER, "INTEGER", false);
        ColumnInfo email = new ColumnInfo("email", Types.VARCHAR, "TEXT", false, true); // masked
        TableInfo users = new TableInfo("public", "users", List.of(id, email),
                List.of("id"), List.of());
        return new DatabaseSchema(List.of(users), "PostgreSQL", "postgresql");
    }

    @Test
    void putThenGetReconstructsStructuredSchema() {
        SchemaCacheService cache = new SchemaCacheService(new AskEngineProperties(), jdbcTemplate());
        assertTrue(cache.isReady());

        cache.put("orders", sampleSchema());
        Optional<DatabaseSchema> loaded = cache.get("orders");

        assertTrue(loaded.isPresent());
        DatabaseSchema schema = loaded.get();
        assertEquals("postgresql", schema.getDialect());
        assertEquals("PostgreSQL", schema.getProductName());
        assertEquals(1, schema.getTables().size());
        TableInfo users = schema.getTables().get(0);
        assertEquals("public.users", users.qualifiedName());
        assertEquals(2, users.getColumns().size());
        assertEquals(List.of("id"), users.getPrimaryKeys());
        // The masked flag survives the JSON round-trip.
        ColumnInfo email = users.getColumns().stream()
                .filter(c -> c.getName().equals("email")).findFirst().orElseThrow();
        assertTrue(email.isMasked());
    }

    @Test
    void putOverwritesPreviousSnapshot() {
        SchemaCacheService cache = new SchemaCacheService(new AskEngineProperties(), jdbcTemplate());
        cache.put("orders", sampleSchema());
        // A second put for the same database must replace, not duplicate (primary key is the name).
        DatabaseSchema smaller = new DatabaseSchema(List.of(), "PostgreSQL", "postgresql");
        cache.put("orders", smaller);
        assertEquals(0, cache.get("orders").orElseThrow().getTables().size());
    }

    @Test
    void missingDatabaseReturnsEmpty() {
        SchemaCacheService cache = new SchemaCacheService(new AskEngineProperties(), jdbcTemplate());
        assertTrue(cache.get("nope").isEmpty());
    }

    @Test
    void noJdbcTemplateIsNotReadyAndNeverThrows() {
        SchemaCacheService cache = new SchemaCacheService(new AskEngineProperties(), null);
        assertFalse(cache.isReady());
        cache.put("orders", sampleSchema()); // no-op, no throw
        assertTrue(cache.get("orders").isEmpty());
    }
}
