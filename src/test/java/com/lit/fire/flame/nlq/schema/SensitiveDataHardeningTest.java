package com.lit.fire.flame.nlq.schema;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F9 sensitive-data introspection tests (JUnit Jupiter, so they are actually discovered by surefire —
 * unlike the legacy JUnit 4 {@link SchemaIntrospectorTest}). They cover the F9 acceptance at the
 * schema layer: a {@code password_hash} column is auto-skipped by the default name patterns even with
 * no explicit request, and a {@code email} column configured as masked stays visible (so it can be
 * aggregated) but is flagged and rendered with a {@code MASKED} marker.
 */
class SensitiveDataHardeningTest {

    private final SchemaIntrospector introspector = new SchemaIntrospector();
    private final SchemaRenderer renderer = new SchemaRenderer();

    private DatabaseSchema introspect(SkipList skipList) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE accounts (" +
                        "id INTEGER PRIMARY KEY, email TEXT NOT NULL, password_hash TEXT, api_key TEXT)");
            }
            return introspector.introspect(connection, skipList);
        }
    }

    @Test
    void passwordHashIsAutoSkippedAndEmailIsMasked() throws SQLException {
        List<String> defaultPatterns = new AskEngineProperties.AutoSkip().getPatterns();
        SkipList skipList = SkipList.builder()
                .autoSkipPatterns(defaultPatterns, true)
                .addMaskedColumns(Collections.singletonList("accounts.email"))
                .build();

        DatabaseSchema schema = introspect(skipList);
        TableInfo accounts = schema.getTables().stream()
                .filter(t -> t.getName().equalsIgnoreCase("accounts"))
                .findFirst().orElseThrow();

        // password_hash and api_key are auto-skipped by pattern — structurally absent, no request needed.
        assertFalse(accounts.getColumns().stream()
                        .anyMatch(c -> c.getName().equalsIgnoreCase("password_hash")),
                "password_hash must be auto-skipped");
        assertFalse(accounts.getColumns().stream()
                        .anyMatch(c -> c.getName().equalsIgnoreCase("api_key")),
                "api_key must be auto-skipped");

        // email is masked: still present (so it can be aggregated) and flagged.
        ColumnInfo email = accounts.getColumns().stream()
                .filter(c -> c.getName().equalsIgnoreCase("email"))
                .findFirst().orElseThrow();
        assertTrue(email.isMasked(), "email should be flagged masked");

        // The rendered schema never shows the auto-skipped names and marks the masked column.
        String rendered = renderer.render(schema);
        assertFalse(rendered.contains("password_hash"), rendered);
        assertFalse(rendered.contains("api_key"), rendered);
        assertTrue(rendered.contains("MASKED"), rendered);
    }

    @Test
    void autoSkipCanBeDisabled() throws SQLException {
        List<String> defaultPatterns = new AskEngineProperties.AutoSkip().getPatterns();
        // enabled=false → patterns dropped → password_hash is NOT auto-skipped.
        SkipList skipList = SkipList.builder()
                .autoSkipPatterns(defaultPatterns, false)
                .build();

        DatabaseSchema schema = introspect(skipList);
        TableInfo accounts = schema.getTables().stream()
                .filter(t -> t.getName().equalsIgnoreCase("accounts"))
                .findFirst().orElseThrow();
        assertTrue(accounts.getColumns().stream()
                        .anyMatch(c -> c.getName().equalsIgnoreCase("password_hash")),
                "with auto-skip disabled, password_hash should remain visible");
    }
}
