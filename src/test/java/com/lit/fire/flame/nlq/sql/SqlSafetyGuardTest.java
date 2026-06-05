package com.lit.fire.flame.nlq.sql;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SkipList;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SqlSafetyGuard} (F5) — the read-only / skip-list / single-statement trust
 * boundary between the LLM and the database.
 *
 * <p>JUnit Jupiter (see {@code SqlGenerationServiceTest} for why JUnit 4 tests are not discovered).
 * A real {@link SchemaIntrospector} over an in-memory SQLite database supplies the schema, so the
 * table-reference checks run against genuine introspected metadata.
 */
class SqlSafetyGuardTest {

    private final SchemaIntrospector introspector = new SchemaIntrospector();
    private final SqlSafetyGuard guard = new SqlSafetyGuard(new AskEngineProperties());

    /** A {@code users} + {@code secrets} schema; {@code secrets} can be skip-listed per test. */
    private DatabaseSchema schema(SkipList skipList) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL, created_at TEXT)");
                st.executeUpdate("CREATE TABLE secrets (id INTEGER PRIMARY KEY, token TEXT)");
            }
            return introspector.introspect(connection, skipList);
        }
    }

    @Test
    void benignSelectPasses() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        String safe = guard.validate("SELECT id, email FROM users WHERE id = 1 LIMIT 10",
                SkipList.empty(), schema);
        assertTrue(safe.toLowerCase(Locale.ROOT).startsWith("select"));
        assertTrue(safe.toLowerCase(Locale.ROOT).contains("from users"));
        // An explicit limit smaller than the cap is preserved.
        assertTrue(safe.toLowerCase(Locale.ROOT).contains("limit 10"), safe);
    }

    @Test
    void chainedDropIsRejected() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("SELECT * FROM users; DROP TABLE users", SkipList.empty(), schema));
        assertEquals(UnsafeSqlException.Reason.MULTIPLE_STATEMENTS, e.getReason());
    }

    @Test
    void queryAgainstSkippedTableIsRejected() throws Exception {
        // 'secrets' is skipped, so it is absent from the introspected schema entirely.
        SkipList skip = SkipList.from(null, Collections.singletonList("secrets"), null);
        DatabaseSchema schema = schema(skip);
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("SELECT token FROM secrets LIMIT 5", skip, schema));
        assertEquals(UnsafeSqlException.Reason.UNKNOWN_TABLE, e.getReason());
    }

    @Test
    void pgReadFileIsRejected() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("SELECT pg_read_file('/etc/passwd')", SkipList.empty(), schema));
        assertEquals(UnsafeSqlException.Reason.FORBIDDEN_FUNCTION, e.getReason());
    }

    @Test
    void commentedOutChainedStatementIsRejected() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("SELECT id FROM users -- ; DROP TABLE users\n", SkipList.empty(), schema));
        assertEquals(UnsafeSqlException.Reason.COMMENT, e.getReason());
    }

    @Test
    void noLimitQueryGetsBoundedLimit() throws Exception {
        AskEngineProperties props = new AskEngineProperties();
        props.setMaxRows(250);
        SqlSafetyGuard cappedGuard = new SqlSafetyGuard(props);
        DatabaseSchema schema = schema(SkipList.empty());

        String safe = cappedGuard.validate("SELECT id FROM users", SkipList.empty(), schema);
        assertTrue(safe.toLowerCase(Locale.ROOT).contains("limit 250"), safe);
    }

    @Test
    void existingLimitLargerThanCapIsLowered() throws Exception {
        AskEngineProperties props = new AskEngineProperties();
        props.setMaxRows(100);
        SqlSafetyGuard cappedGuard = new SqlSafetyGuard(props);
        DatabaseSchema schema = schema(SkipList.empty());

        String safe = cappedGuard.validate("SELECT id FROM users LIMIT 5000", SkipList.empty(), schema);
        assertTrue(safe.toLowerCase(Locale.ROOT).contains("limit 100"), safe);
    }

    @Test
    void unknownTableIsRejected() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("SELECT id FROM orders LIMIT 5", SkipList.empty(), schema));
        assertEquals(UnsafeSqlException.Reason.UNKNOWN_TABLE, e.getReason());
    }

    @Test
    void nonSelectStatementIsRejected() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("UPDATE users SET email = 'x' WHERE id = 1", SkipList.empty(), schema));
        // Caught by the read-only-start rule before any parse.
        assertEquals(UnsafeSqlException.Reason.NOT_READ_ONLY, e.getReason());
    }

    @Test
    void intoOutfileIsRejected() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("SELECT id FROM users INTO OUTFILE '/tmp/x'", SkipList.empty(), schema));
        // The INTO keyword fires before the outfile function screen.
        assertEquals(UnsafeSqlException.Reason.FORBIDDEN_KEYWORD, e.getReason());
    }

    @Test
    void skippedColumnReferenceIsRejected() throws Exception {
        SkipList skip = SkipList.from(null, null, Collections.singletonList("users.email"));
        DatabaseSchema schema = schema(skip);
        UnsafeSqlException e = assertThrows(UnsafeSqlException.class,
                () -> guard.validate("SELECT email FROM users LIMIT 5", skip, schema));
        assertEquals(UnsafeSqlException.Reason.SKIPPED_COLUMN, e.getReason());
    }

    @Test
    void cteQueryOverKnownTablesPasses() throws Exception {
        DatabaseSchema schema = schema(SkipList.empty());
        String safe = guard.validate(
                "WITH recent AS (SELECT id FROM users) SELECT id FROM recent",
                SkipList.empty(), schema);
        assertTrue(safe.toLowerCase(Locale.ROOT).startsWith("with"), safe);
    }
}
