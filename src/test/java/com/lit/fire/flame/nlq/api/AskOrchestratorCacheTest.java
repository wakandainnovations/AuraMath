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
import com.lit.fire.flame.nlq.schema.ColumnInfo;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.SchemaCacheService;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SchemaRenderer;
import com.lit.fire.flame.nlq.schema.TableInfo;
import com.lit.fire.flame.nlq.sql.QueryExecutionService;
import com.lit.fire.flame.nlq.sql.ResultRedactor;
import com.lit.fire.flame.nlq.sql.SqlGenerationService;
import com.lit.fire.flame.nlq.sql.SqlSafetyGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the F13 read path in the {@link AskOrchestrator}: when a database's schema is in the cache, the
 * engine answers from it (no live introspection), and in a federated Ask it opens execution connections
 * <b>lazily</b> — so a database the question does not query is never connected to.
 */
class AskOrchestratorCacheTest {

    private final List<Path> tempFiles = new ArrayList<>();
    private Path cacheDb;

    @BeforeEach
    void setup() throws IOException {
        cacheDb = newTempFile("ask-cache-orch-");
    }

    @AfterEach
    void cleanup() throws IOException {
        for (Path p : tempFiles) {
            Files.deleteIfExists(p);
        }
    }

    private Path newTempFile(String prefix) throws IOException {
        Path p = Files.createTempFile(prefix, ".db");
        Files.deleteIfExists(p);
        tempFiles.add(p);
        return p;
    }

    private String url(Path db) {
        return "jdbc:sqlite:" + db.toAbsolutePath();
    }

    private SchemaCacheService readyCache(AskEngineProperties props) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url(cacheDb));
        SchemaCacheService cache = new SchemaCacheService(props, new JdbcTemplate(ds));
        assertTrue(cache.isReady());
        return cache;
    }

    private AskOrchestrator orchestrator(LlmClient llm, AskEngineProperties props,
                                         SchemaCacheService cache) {
        SchemaRenderer renderer = new SchemaRenderer();
        SqlSafetyGuard guard = new SqlSafetyGuard(props);
        return new AskOrchestrator(
                new DynamicConnectionFactory(props),
                DatasourceRegistry.empty(),
                new SchemaIntrospector(),
                cache,
                new SqlGenerationService(renderer, llm, props),
                guard,
                new QueryExecutionService(guard, props),
                new ResultRedactor(),
                new AnswerSynthesisService(llm),
                props,
                new AskAuditLogger(props, null),
                new AskMetrics(),
                new LlmUsageRecorder());
    }

    private TableInfo usersTable(String... columns) {
        List<ColumnInfo> cols = new ArrayList<>();
        for (String c : columns) {
            cols.add(new ColumnInfo(c, Types.INTEGER, "INTEGER", true));
        }
        return new TableInfo(null, "users", cols, List.of(), List.of());
    }

    // --- cache hit: the cached schema is used, not a live introspection -------------------------

    @Test
    void answersFromCachedSchemaWithoutLiveIntrospection() throws Exception {
        Path ordersDb = newTempFile("ask-cache-orders-");
        // The LIVE database has id, email, score...
        try (Connection c = DriverManager.getConnection(url(ordersDb));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT, score REAL)");
            st.executeUpdate("INSERT INTO users (id, email, score) VALUES (1,'a@x',10),(2,'b@x',20)");
        }

        AskEngineProperties props = new AskEngineProperties();
        SchemaCacheService cache = readyCache(props);
        // ...but the CACHE holds a trimmed schema with only id.
        cache.put("orders", new DatabaseSchema(List.of(usersTable("id")), "SQLite", "sqlite"));

        RoutingLlm llm = new RoutingLlm()
                .sql(sqlResult("SELECT id FROM users", "users"))
                .plan(lookupPlan())
                .answer(answerText("There are 2 users."));

        ConnectionRequest connection = new ConnectionRequest(url(ordersDb), null, null, "sqlite");
        connection.setName("orders");
        AskRequest req = new AskRequest();
        req.setConnection(connection);
        req.setQuestion("how many users are there");

        AskResponse response = orchestrator(llm, props, cache).ask(req);

        assertFalse(response.isClarificationNeeded());
        assertNotNull(response.getAnswer());
        assertEquals(2, response.getRowCount());

        // The generation prompt shows the CACHED (trimmed) schema — id only, not the live email/score.
        String generationPrompt = llm.requestFor("emit_sql").getUserPrompt();
        assertTrue(generationPrompt.contains("users"), generationPrompt);
        assertFalse(generationPrompt.contains("email"),
                "live introspection would have shown 'email'; the cache (id-only) was used instead");
        assertFalse(generationPrompt.contains("score"), generationPrompt);
    }

    // --- federated: a database the question does not query is never connected to -----------------

    @Test
    void unusedDatabaseIsNeverConnected() throws Exception {
        Path ordersDb = newTempFile("ask-cache-fed-orders-");
        try (Connection c = DriverManager.getConnection(url(ordersDb));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, score REAL)");
            st.executeUpdate("INSERT INTO users (id, score) VALUES (1,10),(2,20)");
        }

        AskEngineProperties props = new AskEngineProperties();
        props.setConnectionTimeoutSeconds(1);
        SchemaCacheService cache = readyCache(props);
        cache.put("orders", new DatabaseSchema(List.of(usersTable("id", "score")), "SQLite", "sqlite"));
        cache.put("billing", new DatabaseSchema(List.of(usersTable("id")), "SQLite", "sqlite"));

        // The plan touches ONLY 'orders'. 'billing' points at a dead host: if the engine opened it
        // (instead of lazily skipping it), the request would fail.
        RoutingLlm llm = new RoutingLlm()
                .federated(federatedPlanOrdersOnly())
                .plan(lookupPlan())
                .answer(answerText("ok"));

        ConnectionRequest orders = new ConnectionRequest(url(ordersDb), null, null, "sqlite");
        orders.setName("orders");
        ConnectionRequest billing =
                new ConnectionRequest("jdbc:postgresql://127.0.0.1:1/nope", "u", "p", "postgresql");
        billing.setName("billing");

        AskRequest req = new AskRequest();
        req.setConnections(List.of(orders, billing));
        req.setQuestion("list user scores in orders");

        AskResponse response = orchestrator(llm, props, cache).ask(req);

        assertFalse(response.isClarificationNeeded());
        assertEquals(1, response.getSubQueries().size());
        assertEquals("orders", response.getSubQueries().get(0).getDatabase());
        // The federated planning prompt still showed BOTH cached schemas (no connection needed for that).
        assertTrue(llm.requestFor("emit_federated_sql").getUserPrompt().contains("=== database: billing"));
    }

    // --- LLM stub + JSON builders ---------------------------------------------------------------

    private JsonObject sqlResult(String sql, String table) {
        JsonObject json = new JsonObject();
        json.addProperty("sql", sql);
        JsonArray tables = new JsonArray();
        tables.add(table);
        json.add("tablesUsed", tables);
        json.add("assumptions", new JsonArray());
        json.addProperty("clarificationNeeded", false);
        return json;
    }

    private JsonObject federatedPlanOrdersOnly() {
        JsonObject q = new JsonObject();
        q.addProperty("database", "orders");
        q.addProperty("sql", "SELECT score FROM users");
        JsonArray tables = new JsonArray();
        tables.add("users");
        q.add("tablesUsed", tables);
        JsonArray queries = new JsonArray();
        queries.add(q);
        JsonObject plan = new JsonObject();
        plan.add("queries", queries);
        plan.add("assumptions", new JsonArray());
        plan.addProperty("clarificationNeeded", false);
        return plan;
    }

    private JsonObject lookupPlan() {
        JsonObject plan = new JsonObject();
        plan.addProperty("lookupOnly", true);
        plan.add("operations", new JsonArray());
        return plan;
    }

    private JsonObject answerText(String text) {
        JsonObject json = new JsonObject();
        json.addProperty("answer", text);
        return json;
    }

    /** A capturing LLM stub routed by structured-tool name, remembering the request per tool. */
    private static final class RoutingLlm implements LlmClient {
        private JsonObject sqlJson;
        private JsonObject federatedJson;
        private JsonObject planJson;
        private JsonObject answerJson;
        private final List<LlmRequest> requests = new ArrayList<>();

        RoutingLlm sql(JsonObject j) { this.sqlJson = j; return this; }
        RoutingLlm federated(JsonObject j) { this.federatedJson = j; return this; }
        RoutingLlm plan(JsonObject j) { this.planJson = j; return this; }
        RoutingLlm answer(JsonObject j) { this.answerJson = j; return this; }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            JsonObject json;
            switch (request.getStructuredToolName()) {
                case "emit_sql":
                    json = sqlJson;
                    break;
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

        LlmRequest requestFor(String tool) {
            for (LlmRequest r : requests) {
                if (tool.equalsIgnoreCase(r.getStructuredToolName())) {
                    return r;
                }
            }
            throw new AssertionError("no request captured for tool " + tool);
        }
    }
}
