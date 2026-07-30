package com.lit.fire.flame.nlq.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SchemaIntrospector} and {@link SchemaRenderer} against an in-memory SQLite
 * database (the Xerial driver is on the classpath). The setup connection writes the fixture schema;
 * the introspector itself only reads metadata — never row data.
 *
 * <p>The core guarantee under test: a skip-listed table and a skip-listed column are structurally
 * absent from both the {@link DatabaseSchema} and the rendered prompt text.
 */
public class SchemaIntrospectorTest {

    private final SchemaIntrospector introspector = new SchemaIntrospector();
    private final SchemaRenderer renderer = new SchemaRenderer();

    private Connection openFixture() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE orgs (id INTEGER PRIMARY KEY, name TEXT)");
            st.executeUpdate("CREATE TABLE users (" +
                    "id INTEGER PRIMARY KEY, " +
                    "email TEXT NOT NULL, " +
                    "org_id INTEGER, " +
                    "secret_token TEXT, " +
                    "FOREIGN KEY (org_id) REFERENCES orgs(id))");
            st.executeUpdate("CREATE TABLE secrets (id INTEGER PRIMARY KEY, value TEXT)");
        }
        return connection;
    }

    @Test
    public void skippedTableAndColumnAreAbsentFromSchemaAndRender() throws SQLException {
        SkipList skipList = SkipList.from(
                Collections.emptyList(),
                // Per-request: skip the whole "secrets" table (case-insensitive) ...
                Collections.singletonList("SECRETS"),
                // ... and just the users.secret_token column.
                Collections.singletonList("users.secret_token"));

        DatabaseSchema schema;
        try (Connection connection = openFixture()) {
            schema = introspector.introspect(connection, skipList);
        }

        // The skipped table must not be a model-visible table.
        boolean hasSecrets = schema.getTables().stream()
                .anyMatch(t -> t.getName().equalsIgnoreCase("secrets"));
        assertFalse(hasSecrets, "skipped table 'secrets' must not appear in DatabaseSchema");

        // The skipped column must not appear on its table.
        TableInfo users = schema.getTables().stream()
                .filter(t -> t.getName().equalsIgnoreCase("users"))
                .findFirst().orElse(null);
        assertNotNull(users, "users table should be present");
        boolean hasSecretToken = users.getColumns().stream()
                .anyMatch(c -> c.getName().equalsIgnoreCase("secret_token"));
        assertFalse(hasSecretToken, "skipped column 'secret_token' must not appear on users");

        String rendered = renderer.render(schema);

        // The explicit requirement: a skipped table name is absent from the rendered output.
        assertFalse(rendered.contains("secrets"),
                "rendered schema must not contain the skipped table name 'secrets'");
        assertFalse(rendered.contains("secret_token"),
                "rendered schema must not contain the skipped column 'secret_token'");

        // Sanity: the surviving structure is rendered.
        assertTrue(rendered.contains("users"));
        assertTrue(rendered.contains("orgs"));
        assertTrue(rendered.contains("email"));
    }

    @Test
    public void systemAndInternalTablesAreExcluded() throws SQLException {
        DatabaseSchema schema;
        try (Connection connection = openFixture()) {
            // SQLite auto-creates the internal sqlite_sequence table once an AUTOINCREMENT/rowid
            // table exists; in any case sqlite_* must never be surfaced.
            schema = introspector.introspect(connection, SkipList.empty());
        }
        boolean anyInternal = schema.getTables().stream()
                .anyMatch(t -> t.getName().toLowerCase().startsWith("sqlite_"));
        assertFalse(anyInternal, "sqlite_* internal tables must be excluded");
    }

    @Test
    public void skipListMatchingIsCaseInsensitiveAndSchemaAware() {
        SkipList skipList = SkipList.from(
                Arrays.asList("Audit_Log"),
                Arrays.asList("public.Sessions"),
                Arrays.asList("Users.Email", "public.accounts.balance"));

        // Bare-name table entry matches any schema, case-insensitively.
        assertTrue(skipList.isTableSkipped("public", "audit_log"));
        assertTrue(skipList.isTableSkipped(null, "AUDIT_LOG"));
        // Schema-qualified entry matches only that schema.
        assertTrue(skipList.isTableSkipped("public", "sessions"));
        assertFalse(skipList.isTableSkipped("other", "sessions"));
        // Column entries, both two- and three-part forms.
        assertTrue(skipList.isColumnSkipped(null, "users", "email"));
        assertTrue(skipList.isColumnSkipped("public", "accounts", "BALANCE"));
        assertFalse(skipList.isColumnSkipped("public", "accounts", "name"));
    }

    @Test
    public void unionOfDefaultAndRequestTablesIsApplied() {
        List<String> serverDefault = Collections.singletonList("server_secrets");
        List<String> requestTables = Collections.singletonList("request_secrets");
        SkipList skipList = SkipList.from(serverDefault, requestTables, Collections.emptyList());
        assertTrue(skipList.isTableSkipped(null, "server_secrets"));
        assertTrue(skipList.isTableSkipped(null, "request_secrets"));
    }
}
