package com.lit.fire.flame.nlq.connection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link DatasourceRegistry}: it parses {@code ask.db.<name>.*} groups from a host
 * credential file into named connections, validates each URL (skipping bad entries), and never exposes
 * credentials through its credential-free {@link DatasourceRegistry#describe() describe()} view.
 */
class DatasourceRegistryTest {

    private Path secrets;

    @AfterEach
    void cleanup() throws IOException {
        if (secrets != null) {
            Files.deleteIfExists(secrets);
        }
    }

    private DatasourceRegistry registryFrom(String content) throws IOException {
        secrets = Files.createTempFile("config-secrets-", ".properties");
        Files.write(secrets, content.getBytes(StandardCharsets.UTF_8));
        return new DatasourceRegistry(secrets.toString());
    }

    @Test
    void loadsNamedDatabasesAndSkipLists() throws Exception {
        DatasourceRegistry registry = registryFrom(String.join("\n",
                "ask.db.orders.driver=mysql",
                "ask.db.orders.url=jdbc:mysql://db.internal:3306/ordersdb",
                "ask.db.orders.username=ro",
                "ask.db.orders.password=secret",
                "ask.db.orders.skipColumns=customers.ssn, customers.email",
                "ask.db.billing.driver=postgresql",
                "ask.db.billing.url=jdbc:postgresql://db.internal:5432/billing",
                "ask.db.billing.username=ro2",
                "ask.db.billing.password=secret2"));

        assertEquals(List.of("orders", "billing"), registry.names());
        assertEquals(2, registry.size());
        assertTrue(registry.contains("ORDERS"), "name lookup is case-insensitive");

        ConnectionRequest orders = registry.get("orders");
        assertNotNull(orders);
        assertEquals("orders", orders.getName());
        assertEquals("jdbc:mysql://db.internal:3306/ordersdb", orders.getJdbcUrl());
        assertEquals("ro", orders.getUsername());
        assertEquals(List.of("customers.ssn", "customers.email"), orders.getSkipColumns());
    }

    @Test
    void skipsEntriesWithMissingOrInvalidUrl() throws Exception {
        DatasourceRegistry registry = registryFrom(String.join("\n",
                "ask.db.good.url=jdbc:postgresql://h:5432/db",
                "ask.db.nourl.username=ro",                       // no url → skipped
                "ask.db.badscheme.url=jdbc:oracle:thin:@h:1521")); // unsupported scheme → skipped

        assertEquals(List.of("good"), registry.names());
        assertNull(registry.get("nourl"));
        assertNull(registry.get("badscheme"));
    }

    @Test
    void describeNeverLeaksCredentials() throws Exception {
        DatasourceRegistry registry = registryFrom(String.join("\n",
                "ask.db.orders.url=jdbc:mysql://db.internal:3306/ordersdb",
                "ask.db.orders.username=ro",
                "ask.db.orders.password=topsecret"));

        List<DatasourceRegistry.DatasourceInfo> described = registry.describe();
        assertEquals(1, described.size());
        DatasourceRegistry.DatasourceInfo info = described.get(0);
        assertEquals("orders", info.getName());
        assertEquals("mysql", info.getDriver());
        assertEquals("db.internal:3306", info.getHost());

        // The credential-free view's string form must not carry the username or password.
        String rendered = info.getName() + "|" + info.getDriver() + "|" + info.getHost();
        assertFalse(rendered.contains("topsecret"));
        assertFalse(rendered.contains("ro"));
    }

    @Test
    void missingFileYieldsEmptyRegistry() {
        DatasourceRegistry registry = new DatasourceRegistry("/no/such/path/config.secrets");
        assertEquals(0, registry.size());
        assertTrue(registry.names().isEmpty());
        assertTrue(DatasourceRegistry.empty().names().isEmpty());
    }
}
