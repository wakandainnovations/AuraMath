package com.lit.fire.flame.nlq.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.audit.AskAuditLogger;
import com.lit.fire.flame.nlq.audit.AskMetrics;
import com.lit.fire.flame.nlq.audit.LlmUsageRecorder;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.connection.ConnectionRequest;
import com.lit.fire.flame.nlq.connection.DatasourceRegistry;
import com.lit.fire.flame.nlq.connection.DynamicConnectionFactory;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import com.lit.fire.flame.nlq.math.AnswerSynthesisService;
import com.lit.fire.flame.nlq.schema.SchemaCacheService;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SchemaRenderer;
import com.lit.fire.flame.nlq.sql.QueryExecutionService;
import com.lit.fire.flame.nlq.sql.ResultRedactor;
import com.lit.fire.flame.nlq.sql.SqlGenerationService;
import com.lit.fire.flame.nlq.sql.SqlSafetyGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the federated (multi-database) path of the {@link AskOrchestrator}: two real
 * file-backed SQLite databases are queried with one safe sub-query each, and the results are collated
 * into a single answer. The LLM is a capturing stub that routes by structured-tool name and returns a
 * {@code emit_federated_sql} plan with one sub-query per database. No network or API key is needed.
 */
class AskOrchestratorFederatedTest {

    private Path ordersDb;
    private Path billingDb;

    @BeforeEach
    void createDatabases() throws Exception {
        ordersDb = freshDb("ask-fed-orders-");
        try (Connection c = DriverManager.getConnection(url(ordersDb));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount REAL)");
            st.executeUpdate("INSERT INTO orders (id, amount) VALUES (1, 100), (2, 200), (3, 300)");
        }
        billingDb = freshDb("ask-fed-billing-");
        try (Connection c = DriverManager.getConnection(url(billingDb));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE invoices (id INTEGER PRIMARY KEY, total REAL)");
            st.executeUpdate("INSERT INTO invoices (id, total) VALUES (1, 50), (2, 75)");
        }
    }

    @AfterEach
    void deleteDatabases() throws IOException {
        if (ordersDb != null) {
            Files.deleteIfExists(ordersDb);
        }
        if (billingDb != null) {
            Files.deleteIfExists(billingDb);
        }
    }

    private static Path freshDb(String prefix) throws IOException {
        Path p = Files.createTempFile(prefix, ".db");
        Files.deleteIfExists(p); // let SQLite create it fresh
        return p;
    }

    private static String url(Path db) {
        return "jdbc:sqlite:" + db.toAbsolutePath();
    }

    @Test
    void federatedAskQueriesEachDatabaseAndCollates() throws Exception {
        RoutingLlmClient llm = new RoutingLlmClient(federatedPlan(), twoSumPlan(),
                answerText("Orders total 600; billed 125."));

        AskRequest req = new AskRequest();
        req.setQuestion("compare total order amount with total billed");
        req.setConnections(List.of(
                named("orders", url(ordersDb)),
                named("billing", url(billingDb))));

        AskResponse response = orchestrator(llm).ask(req);

        assertFalse(response.isClarificationNeeded());
        // No single top-level SQL for a federated answer — the per-database queries are on subQueries.
        assertNull(response.getSql());
        assertEquals(2, response.getSubQueries().size());
        assertEquals("orders", response.getSubQueries().get(0).getDatabase());
        assertEquals("billing", response.getSubQueries().get(1).getDatabase());
        // Each executed sub-query was row-capped by the safety guard.
        assertTrue(response.getSubQueries().get(0).getSql().toLowerCase(Locale.ROOT).contains("limit"));

        // The mathematician layer computed each database's sum deterministically over the labeled view.
        assertEquals(600.0, response.getComputedValues().get("orders_total"), 1e-9);
        assertEquals(125.0, response.getComputedValues().get("billing_total"), 1e-9);

        // Tables are reported namespaced by database; total row count spans both databases (3 + 2).
        assertEquals(List.of("orders.orders", "billing.invoices"), response.getTablesUsed());
        assertEquals(5, response.getRowCount());

        assertNotNull(response.getAnswer());
        assertTrue(response.getTimingMillis().containsKey("totalMillis"));
        assertNotNull(response.getRequestId());

        // The federated planning prompt showed BOTH databases, each under its own header.
        String planPrompt = llm.requests.get(0).getUserPrompt();
        assertTrue(planPrompt.contains("=== database: orders"), planPrompt);
        assertTrue(planPrompt.contains("=== database: billing"), planPrompt);
    }

    // --- helpers --------------------------------------------------------------------------------

    private AskOrchestrator orchestrator(LlmClient llm) {
        AskEngineProperties properties = new AskEngineProperties();
        SchemaRenderer renderer = new SchemaRenderer();
        SqlSafetyGuard guard = new SqlSafetyGuard(properties);
        return new AskOrchestrator(
                new DynamicConnectionFactory(properties),
                DatasourceRegistry.empty(),
                new SchemaIntrospector(),
                new SchemaCacheService(properties, null),
                new SqlGenerationService(renderer, llm, properties),
                guard,
                new QueryExecutionService(guard, properties),
                new ResultRedactor(),
                new AnswerSynthesisService(llm),
                properties,
                new AskAuditLogger(properties, null),
                new AskMetrics(),
                new LlmUsageRecorder());
    }

    private static ConnectionRequest named(String name, String url) {
        ConnectionRequest c = new ConnectionRequest(url, null, null, "sqlite");
        c.setName(name);
        return c;
    }

    private JsonObject federatedPlan() {
        JsonArray queries = new JsonArray();
        queries.add(subQuery("orders", "SELECT amount FROM orders", "orders"));
        queries.add(subQuery("billing", "SELECT total FROM invoices", "invoices"));
        JsonObject plan = new JsonObject();
        plan.add("queries", queries);
        plan.add("assumptions", new JsonArray());
        plan.addProperty("clarificationNeeded", false);
        return plan;
    }

    private JsonObject subQuery(String database, String sql, String table) {
        JsonObject q = new JsonObject();
        q.addProperty("database", database);
        q.addProperty("sql", sql);
        JsonArray tables = new JsonArray();
        tables.add(table);
        q.add("tablesUsed", tables);
        return q;
    }

    /** Two sum operations over the namespaced combined columns the federated view exposes. */
    private JsonObject twoSumPlan() {
        JsonArray ops = new JsonArray();
        ops.add(sumOp("orders_total", "orders.amount"));
        ops.add(sumOp("billing_total", "billing.total"));
        JsonObject plan = new JsonObject();
        plan.addProperty("lookupOnly", false);
        plan.add("operations", ops);
        return plan;
    }

    private JsonObject sumOp(String name, String column) {
        JsonObject op = new JsonObject();
        op.addProperty("name", name);
        op.addProperty("formula", "sum");
        JsonArray cols = new JsonArray();
        cols.add(column);
        op.add("columns", cols);
        return op;
    }

    private JsonObject answerText(String text) {
        JsonObject json = new JsonObject();
        json.addProperty("answer", text);
        return json;
    }

    /** A capturing LLM stub that routes by structured-tool name (federated SQL, plan, narrative). */
    private static final class RoutingLlmClient implements LlmClient {
        private final JsonObject federatedJson;
        private final JsonObject planJson;
        private final JsonObject answerJson;
        private final List<LlmRequest> requests = new ArrayList<>();

        RoutingLlmClient(JsonObject federatedJson, JsonObject planJson, JsonObject answerJson) {
            this.federatedJson = federatedJson;
            this.planJson = planJson;
            this.answerJson = answerJson;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            JsonObject json;
            switch (request.getStructuredToolName()) {
                case "emit_federated_sql":
                    json = federatedJson;
                    break;
                case "emit_computation_plan":
                    json = planJson;
                    break;
                default:
                    json = answerJson;
            }
            return new LlmResponse("", json, "tool_use", 5, 5);
        }
    }
}
