package com.lit.fire.flame.nlq.sql;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SkipList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link QueryExecutionService} (F6) — bounded, timed, read-only execution.
 *
 * <p>A real file-backed SQLite database is written once with a normal connection, then every query
 * runs over a <b>separate read-only connection</b> (so {@link Connection#isReadOnly()} is genuinely
 * true and the data is shared across connections, which {@code :memory:} would not allow). The schema
 * is introspected through F2 so the service's defensive F5 re-validation runs against real metadata.
 */
class QueryExecutionServiceTest {

    private final SchemaIntrospector introspector = new SchemaIntrospector();
    private Path dbFile;

    @BeforeEach
    void createDatabase() throws Exception {
        dbFile = Files.createTempFile("ask-f6-", ".db");
        Files.deleteIfExists(dbFile); // let SQLite create it fresh
        try (Connection write = DriverManager.getConnection(url());
             Statement st = write.createStatement()) {
            st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL, score REAL)");
            for (int i = 1; i <= 50; i++) {
                st.executeUpdate("INSERT INTO users (id, email, score) VALUES ("
                        + i + ", 'user" + i + "@example.com', " + (i + 0.5) + ")");
            }
        }
    }

    @AfterEach
    void deleteDatabase() throws IOException {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private String url() {
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    /** A read-only connection to the shared file, with the read-only open mode baked in (like F1). */
    private Connection readOnlyConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Properties props = new Properties();
        props.putAll(config.toProperties());
        return DriverManager.getConnection(url(), props);
    }

    private DatabaseSchema schema(Connection connection, SkipList skipList) throws SQLException {
        return introspector.introspect(connection, skipList);
    }

    @Test
    void selectReturnsTypedRowsAndColumnMetadata() throws Exception {
        QueryExecutionService service = service();
        try (Connection connection = readOnlyConnection()) {
            DatabaseSchema schema = schema(connection, SkipList.empty());
            QueryResult result = service.execute(connection,
                    "SELECT id, email, score FROM users WHERE id = 1", SkipList.empty(), schema);

            assertEquals(3, result.getColumns().size());
            assertEquals("id", result.getColumns().get(0).getName());
            assertEquals("email", result.getColumns().get(1).getName());
            assertEquals(1, result.getRowCount());
            assertFalse(result.isTruncated());

            Map<String, Object> row = result.getRows().get(0);
            assertEquals(1L, ((Number) row.get("id")).longValue());
            assertEquals("user1@example.com", row.get("email"));
            assertNotNull(row.get("score"));
            assertTrue(result.getExecutionMillis() >= 0);
        }
    }

    @Test
    void nullValuesAreReturnedAsNull() throws Exception {
        // Add a row whose score is NULL and read it back.
        try (Connection write = DriverManager.getConnection(url());
             Statement st = write.createStatement()) {
            st.executeUpdate("INSERT INTO users (id, email, score) VALUES (999, 'null@example.com', NULL)");
        }
        QueryExecutionService service = service();
        try (Connection connection = readOnlyConnection()) {
            DatabaseSchema schema = schema(connection, SkipList.empty());
            QueryResult result = service.execute(connection,
                    "SELECT score FROM users WHERE id = 999", SkipList.empty(), schema);
            assertEquals(1, result.getRowCount());
            assertNull(result.getRows().get(0).get("score"));
        }
    }

    @Test
    void resultExceedingMaxRowsIsTruncated() throws Exception {
        AskEngineProperties props = new AskEngineProperties();
        props.setMaxRows(10); // 50 rows exist, so the result fills the cap
        QueryExecutionService service = new QueryExecutionService(new SqlSafetyGuard(props), props);
        try (Connection connection = readOnlyConnection()) {
            DatabaseSchema schema = schema(connection, SkipList.empty());
            QueryResult result = service.execute(connection,
                    "SELECT id FROM users", SkipList.empty(), schema);
            assertEquals(10, result.getRowCount());
            assertTrue(result.isTruncated());
        }
    }

    @Test
    void resultUnderMaxRowsIsNotTruncated() throws Exception {
        AskEngineProperties props = new AskEngineProperties();
        props.setMaxRows(1000);
        QueryExecutionService service = new QueryExecutionService(new SqlSafetyGuard(props), props);
        try (Connection connection = readOnlyConnection()) {
            DatabaseSchema schema = schema(connection, SkipList.empty());
            QueryResult result = service.execute(connection,
                    "SELECT id FROM users", SkipList.empty(), schema);
            assertEquals(50, result.getRowCount());
            assertFalse(result.isTruncated());
        }
    }

    /**
     * The timeout contract: the service applies {@code queryTimeoutSeconds} via
     * {@link java.sql.Statement#setQueryTimeout(int)} and maps the driver's resulting
     * {@link SQLTimeoutException} to {@link QueryExecutionException.Kind#TIMEOUT}.
     *
     * <p>SQLite's {@code setQueryTimeout} only governs lock contention, so it cannot reliably cut off
     * a CPU-bound query in-process; the cancellation itself is the driver's job (and works on
     * Postgres/MySQL). This test therefore drives the service's timeout <em>handling</em>
     * deterministically through a stub statement that records the applied timeout and throws as a
     * timed-out driver would. The schema is still introspected from a real read-only connection so the
     * F5 re-validation runs normally.
     */
    @Test
    void timeoutIsAppliedAndMappedToTypedException() throws Exception {
        AskEngineProperties props = new AskEngineProperties();
        props.setQueryTimeoutSeconds(7);
        QueryExecutionService service = new QueryExecutionService(new SqlSafetyGuard(props), props);

        DatabaseSchema schema;
        try (Connection real = readOnlyConnection()) {
            schema = schema(real, SkipList.empty());
        }

        int[] appliedTimeout = {-1};
        Connection timingOut = timingOutConnection(appliedTimeout);
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> service.execute(timingOut, "SELECT id FROM users", SkipList.empty(), schema));
        assertEquals(QueryExecutionException.Kind.TIMEOUT, e.getKind());
        assertEquals(7, appliedTimeout[0], "configured query timeout must be applied to the statement");
    }

    /**
     * A read-only {@link Connection} whose statements record the applied query timeout and then throw
     * {@link SQLTimeoutException} from {@code executeQuery}, as a real driver does when a query is cut
     * off by its timeout. Built with {@link java.lang.reflect.Proxy} so only the handful of methods the
     * service touches need behavior.
     */
    private Connection timingOutConnection(int[] appliedTimeout) {
        PreparedStatement statement = (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, methodArgs) -> {
                    switch (method.getName()) {
                        case "setQueryTimeout":
                            appliedTimeout[0] = (int) methodArgs[0];
                            return null;
                        case "executeQuery":
                            throw new SQLTimeoutException("query timeout");
                        case "close":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, methodArgs) -> {
                    switch (method.getName()) {
                        case "isReadOnly":
                            return Boolean.TRUE;
                        case "prepareStatement":
                            return statement;
                        case "close":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    @Test
    void nonReadOnlyConnectionIsRejected() throws Exception {
        QueryExecutionService service = service();
        // A plain (writable) connection: isReadOnly() is false, so execution must refuse.
        try (Connection connection = DriverManager.getConnection(url())) {
            DatabaseSchema schema = schema(connection, SkipList.empty());
            QueryExecutionException e = assertThrows(QueryExecutionException.class,
                    () -> service.execute(connection, "SELECT id FROM users", SkipList.empty(), schema));
            assertEquals(QueryExecutionException.Kind.NOT_READ_ONLY, e.getKind());
        }
    }

    @Test
    void unsafeSqlIsRejectedAtExecution() throws Exception {
        QueryExecutionService service = service();
        try (Connection connection = readOnlyConnection()) {
            DatabaseSchema schema = schema(connection, SkipList.empty());
            QueryExecutionException e = assertThrows(QueryExecutionException.class,
                    () -> service.execute(connection, "SELECT id FROM users; DROP TABLE users",
                            SkipList.empty(), schema));
            assertEquals(QueryExecutionException.Kind.UNSAFE_SQL, e.getKind());
        }
    }

    private QueryExecutionService service() {
        return new QueryExecutionService(new SqlSafetyGuard(new AskEngineProperties()),
                new AskEngineProperties());
    }
}
