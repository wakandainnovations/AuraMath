package com.lit.fire.flame.nlq.schema;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.connection.DatasourceRegistry;
import com.lit.fire.flame.nlq.connection.DynamicConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the F13 {@link SchemaRefreshJob}: it introspects every registered database and stores each
 * schema in the {@link SchemaCacheService}, refreshing databases independently so one unreachable
 * database does not stop the others.
 */
class SchemaRefreshJobTest {

    private final List<Path> tempFiles = new ArrayList<>();
    private Path cacheDb;

    @BeforeEach
    void setup() throws IOException {
        cacheDb = newTempFile("ask-refresh-cache-");
    }

    @AfterEach
    void cleanup() throws IOException {
        for (Path p : tempFiles) {
            Files.deleteIfExists(p);
        }
    }

    private Path newTempFile(String prefix) throws IOException {
        Path p = Files.createTempFile(prefix, ".db");
        Files.deleteIfExists(p); // let SQLite create it fresh
        tempFiles.add(p);
        return p;
    }

    private String sqliteUrl(Path db) {
        return "jdbc:sqlite:" + db.toAbsolutePath();
    }

    private void createTable(Path db, String ddl) throws Exception {
        try (Connection c = DriverManager.getConnection(sqliteUrl(db));
             Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        }
    }

    private DatasourceRegistry registryFrom(String content) throws IOException {
        Path secrets = Files.createTempFile("refresh-secrets-", ".properties");
        tempFiles.add(secrets);
        Files.write(secrets, content.getBytes(StandardCharsets.UTF_8));
        return DatasourceRegistry.fromSecretsFile(secrets.toString());
    }

    private SchemaCacheService cache(AskEngineProperties props) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(sqliteUrl(cacheDb));
        return new SchemaCacheService(props, new JdbcTemplate(ds));
    }

    @Test
    void refreshCachesEveryRegisteredDatabase() throws Exception {
        Path orders = newTempFile("ask-refresh-orders-");
        Path billing = newTempFile("ask-refresh-billing-");
        createTable(orders, "CREATE TABLE orders (id INTEGER PRIMARY KEY, amount REAL)");
        createTable(billing, "CREATE TABLE invoices (id INTEGER PRIMARY KEY, total REAL)");

        DatasourceRegistry registry = registryFrom(String.join("\n",
                "ask.db.orders.url=" + sqliteUrl(orders),
                "ask.db.billing.url=" + sqliteUrl(billing)));

        AskEngineProperties props = new AskEngineProperties();
        SchemaCacheService cache = cache(props);
        SchemaRefreshJob job = new SchemaRefreshJob(registry, new DynamicConnectionFactory(props),
                new SchemaIntrospector(), cache, props);

        job.refresh();

        Optional<DatabaseSchema> ordersSchema = cache.get("orders");
        Optional<DatabaseSchema> billingSchema = cache.get("billing");
        assertTrue(ordersSchema.isPresent());
        assertTrue(billingSchema.isPresent());
        assertTrue(ordersSchema.get().getTables().stream().anyMatch(t -> t.getName().equals("orders")));
        assertTrue(billingSchema.get().getTables().stream().anyMatch(t -> t.getName().equals("invoices")));
    }

    @Test
    void oneUnreachableDatabaseDoesNotStopTheRest() throws Exception {
        Path orders = newTempFile("ask-refresh-orders2-");
        createTable(orders, "CREATE TABLE orders (id INTEGER PRIMARY KEY)");

        // 'broken' has a valid scheme but is unreachable; refresh must still cache 'orders'.
        DatasourceRegistry registry = registryFrom(String.join("\n",
                "ask.db.broken.url=jdbc:postgresql://127.0.0.1:1/nope",
                "ask.db.orders.url=" + sqliteUrl(orders)));

        AskEngineProperties props = new AskEngineProperties();
        // Keep the connection attempt to the dead host short.
        props.setConnectionTimeoutSeconds(1);
        SchemaCacheService cache = cache(props);
        SchemaRefreshJob job = new SchemaRefreshJob(registry, new DynamicConnectionFactory(props),
                new SchemaIntrospector(), cache, props);

        job.refresh();

        assertTrue(cache.get("orders").isPresent(), "the reachable database was still cached");
        assertFalse(cache.get("broken").isPresent(), "the unreachable database was skipped");
    }

    @Test
    void disabledCacheRefreshIsANoOp() throws Exception {
        Path orders = newTempFile("ask-refresh-orders3-");
        createTable(orders, "CREATE TABLE orders (id INTEGER PRIMARY KEY)");
        DatasourceRegistry registry = registryFrom("ask.db.orders.url=" + sqliteUrl(orders));

        AskEngineProperties props = new AskEngineProperties();
        SchemaCacheService cache = cache(props);
        SchemaRefreshJob job = new SchemaRefreshJob(registry, new DynamicConnectionFactory(props),
                new SchemaIntrospector(), cache, props);

        props.getSchemaCache().setEnabled(false);
        job.refresh();

        assertEquals(Optional.empty(), cache.get("orders"));
    }
}
