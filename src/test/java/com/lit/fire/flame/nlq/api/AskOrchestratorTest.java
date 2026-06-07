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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the F8 {@link AskOrchestrator}: the whole F1–F7 pipeline wired together over a
 * real file-backed SQLite database (opened read-only by F1, like production), with the LLM replaced
 * by a capturing stub that routes the three structured calls (SQL generation, computation plan,
 * narrative) by tool name. No network or API key is needed.
 *
 * <p>The acceptance cases: a full question returns an {@link AskResponse} carrying the SQL, the rows
 * preview, the formulas applied, and a coherent NL answer; a question targeting a <b>skipped</b> table
 * yields a clarification (never a leak) — and the stub asserts the skipped table never even appeared
 * in the schema the model was shown.
 */
class AskOrchestratorTest {

    private Path dbFile;

    @BeforeEach
    void createDatabase() throws Exception {
        dbFile = Files.createTempFile("ask-f8-", ".db");
        Files.deleteIfExists(dbFile); // let SQLite create it fresh
        try (Connection write = DriverManager.getConnection(url());
             Statement st = write.createStatement()) {
            st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL, score REAL)");
            for (int i = 1; i <= 5; i++) {
                st.executeUpdate("INSERT INTO users (id, email, score) VALUES ("
                        + i + ", 'user" + i + "@example.com', " + (i * 10) + ")");
            }
            st.executeUpdate("CREATE TABLE secrets (id INTEGER PRIMARY KEY, token TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO secrets (id, token) VALUES (1, 'top-secret')");
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

    // --- a capturing LLM stub that routes by structured-tool name ------------------------------

    private static final class RoutingLlmClient implements LlmClient {
        private final JsonObject sqlJson;
        private final JsonObject planJson;
        private final JsonObject answerJson;
        private final List<LlmRequest> requests = new ArrayList<>();

        RoutingLlmClient(JsonObject sqlJson, JsonObject planJson, JsonObject answerJson) {
            this.sqlJson = sqlJson;
            this.planJson = planJson;
            this.answerJson = answerJson;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            JsonObject json;
            switch (request.getStructuredToolName()) {
                case "emit_sql":
                    json = sqlJson;
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

    private AskOrchestrator orchestrator(LlmClient llm, AskEngineProperties properties) {
        SchemaRenderer renderer = new SchemaRenderer();
        SqlSafetyGuard guard = new SqlSafetyGuard(properties);
        // F10 audit deps: a log-only audit logger (no JdbcTemplate → no persistence), fresh metrics,
        // and an unwrapped token recorder (the stub LLM is not the RecordingLlmClient, so tokens stay 0).
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

    private AskRequest request(String question) {
        AskRequest req = new AskRequest();
        ConnectionRequest connection = new ConnectionRequest(url(), null, null, "sqlite");
        req.setConnection(connection);
        req.setQuestion(question);
        return req;
    }

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

    private JsonObject meanPlan(String column) {
        JsonObject op = new JsonObject();
        op.addProperty("name", "average_score");
        op.addProperty("formula", "mean");
        JsonArray cols = new JsonArray();
        cols.add(column);
        op.add("columns", cols);
        JsonArray ops = new JsonArray();
        ops.add(op);
        JsonObject plan = new JsonObject();
        plan.addProperty("lookupOnly", false);
        plan.add("operations", ops);
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

    // --- acceptance: a full question answers end to end ----------------------------------------

    @Test
    void fullQuestionReturnsSqlRowsFormulasAndAnswer() throws Exception {
        RoutingLlmClient llm = new RoutingLlmClient(
                sqlResult("SELECT score FROM users", "users"),
                meanPlan("score"),
                answerText("The average score is 30."));
        AskResponse response = orchestrator(llm, new AskEngineProperties())
                .ask(request("what is the average score"));

        assertFalse(response.isClarificationNeeded());
        assertNotNull(response.getSql());
        assertTrue(response.getSql().toLowerCase(java.util.Locale.ROOT).contains("limit"),
                "the safety guard should have injected a row cap");
        assertEquals(List.of("users"), response.getTablesUsed());

        // The mathematician layer computed the mean in Java and surfaced the exact figure.
        assertEquals(30.0, response.getComputedValues().get("average_score"), 1e-9);
        assertEquals(1, response.getFormulasApplied().size());
        assertEquals("mean(score)", response.getFormulasApplied().get(0).getExpression());

        assertNotNull(response.getAnswer());
        assertFalse(response.getAnswer().isEmpty());
        assertEquals(5, response.getRowCount());
        assertEquals(5, response.getRowsPreview().size());
        assertFalse(response.isTruncated());

        // Per-stage timings are recorded.
        assertTrue(response.getTimingMillis().containsKey("connectMillis"));
        assertTrue(response.getTimingMillis().containsKey("executeMillis"));
        assertTrue(response.getTimingMillis().containsKey("totalMillis"));

        // F10: the response carries a correlation id for the audit line.
        assertNotNull(response.getRequestId());
    }

    // --- F10: the caller's request id is echoed and the metrics counters are bumped ---------------

    @Test
    void requestIdIsEchoedAndMetricsAreCounted() throws Exception {
        RoutingLlmClient llm = new RoutingLlmClient(
                sqlResult("SELECT score FROM users", "users"),
                meanPlan("score"),
                answerText("The average score is 30."));
        AskEngineProperties properties = new AskEngineProperties();
        SchemaRenderer renderer = new SchemaRenderer();
        SqlSafetyGuard guard = new SqlSafetyGuard(properties);
        AskMetrics metrics = new AskMetrics();
        AskOrchestrator orchestrator = new AskOrchestrator(
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
                metrics,
                new LlmUsageRecorder());

        AskResponse response = orchestrator.ask(request("what is the average score"), "req-12345");

        // The exact id the caller passed is echoed back for correlation with the audit log.
        assertEquals("req-12345", response.getRequestId());
        // An answered request bumps the request and answer counters, and nothing else.
        assertEquals(1, metrics.snapshot().getRequests());
        assertEquals(1, metrics.snapshot().getAnswers());
        assertEquals(0, metrics.snapshot().getClarifications());
        assertEquals(0, metrics.snapshot().getErrors());
    }

    // --- acceptance: a skipped table yields a clarification, and never reaches the model -------

    @Test
    void questionTargetingSkippedTableClarifiesAndDoesNotLeak() throws Exception {
        // The model cannot see the skipped table, so it asks for clarification rather than answering.
        JsonObject clarify = new JsonObject();
        clarify.addProperty("sql", "");
        clarify.add("tablesUsed", new JsonArray());
        clarify.add("assumptions", new JsonArray());
        clarify.addProperty("clarificationNeeded", true);
        clarify.addProperty("clarificationQuestion", "Which table holds the data you mean?");

        RoutingLlmClient llm = new RoutingLlmClient(clarify, meanPlan("score"), answerText("unused"));

        AskRequest req = request("how many secret tokens are there");
        req.setSkipTables(List.of("secrets"));

        AskResponse response = orchestrator(llm, new AskEngineProperties()).ask(req);

        assertTrue(response.isClarificationNeeded());
        assertEquals("Which table holds the data you mean?", response.getClarificationQuestion());
        assertNull(response.getSql(), "no SQL should be produced for a clarification");

        // The skipped table was removed from the schema before the model ever saw it (no leak): the
        // generation prompt lists the visible table but not the skipped one.
        assertEquals(1, llm.requests.size(), "only the generation call should have run");
        String generationPrompt = llm.requests.get(0).getUserPrompt();
        assertTrue(generationPrompt.contains("TABLE users"), "visible table should be in the schema");
        assertFalse(generationPrompt.contains("TABLE secrets"),
                "the skipped table must not appear in the schema shown to the model");
    }

    // --- F-missing-data: a clarification reports the specific data that is missing ----------------

    @Test
    void clarificationCarriesMissingData() throws Exception {
        JsonObject clarify = new JsonObject();
        clarify.addProperty("sql", "");
        clarify.add("tablesUsed", new JsonArray());
        clarify.add("assumptions", new JsonArray());
        clarify.addProperty("clarificationNeeded", true);
        clarify.addProperty("clarificationQuestion", "What counts as a refund?");
        JsonArray missing = new JsonArray();
        missing.add("a refunds table or a refund-date column");
        clarify.add("missingData", missing);

        RoutingLlmClient llm = new RoutingLlmClient(clarify, meanPlan("score"), answerText("unused"));

        AskResponse response = orchestrator(llm, new AskEngineProperties())
                .ask(request("how many orders were refunded last month"));

        assertTrue(response.isClarificationNeeded());
        assertEquals(List.of("a refunds table or a refund-date column"), response.getMissingData());
    }

    // --- F9: a masked column can be counted, but its raw values can never be projected ----------

    @Test
    void maskedColumnCanBeCountedButRawValuesAreNeverReturned() throws Exception {
        // The model aggregates the masked 'email' column — allowed — and the answer comes back with
        // no raw email anywhere in the response.
        RoutingLlmClient llm = new RoutingLlmClient(
                sqlResult("SELECT count(email) AS user_count FROM users", "users"),
                lookupPlan(),
                answerText("There are 5 users."));
        AskEngineProperties props = new AskEngineProperties();
        props.setMaskedColumns(List.of("users.email"));

        AskResponse response = orchestrator(llm, props).ask(request("how many users are there"));

        assertFalse(response.isClarificationNeeded());
        assertNotNull(response.getSql());
        assertTrue(response.getSql().toLowerCase(java.util.Locale.ROOT).contains("count(email)"),
                "the count over the masked column should have been allowed: " + response.getSql());
        assertEquals(1, response.getRowCount());
        assertFalse(response.getRowsPreview().toString().contains("@example.com"),
                "no raw masked email value may appear in the response");
    }

    @Test
    void rawProjectionOfMaskedColumnIsRejected() {
        // If the model tries to select the masked column's raw values, the guard rejects it (→ 422).
        RoutingLlmClient llm = new RoutingLlmClient(
                sqlResult("SELECT id, email FROM users", "users"),
                lookupPlan(),
                answerText("unused"));
        AskEngineProperties props = new AskEngineProperties();
        props.setMaskedColumns(List.of("users.email"));

        com.lit.fire.flame.nlq.sql.UnsafeSqlException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.lit.fire.flame.nlq.sql.UnsafeSqlException.class,
                        () -> orchestrator(llm, props).ask(request("list every user email")));
        assertEquals(com.lit.fire.flame.nlq.sql.UnsafeSqlException.Reason.MASKED_COLUMN, e.getReason());
    }

    // --- the per-request maxRows override is clamped and honoured at execution ------------------

    @Test
    void maxRowsOverrideTruncatesTheResult() throws Exception {
        RoutingLlmClient llm = new RoutingLlmClient(
                sqlResult("SELECT score FROM users", "users"),
                meanPlan("score"),
                answerText("The average score is 15."));

        AskRequest req = request("what is the average score");
        req.setMaxRows(2); // 5 rows exist, so the result fills the lowered cap

        AskResponse response = orchestrator(llm, new AskEngineProperties()).ask(req);

        assertFalse(response.isClarificationNeeded());
        assertEquals(2, response.getRowCount());
        assertTrue(response.isTruncated());
        assertEquals(2, response.getRowsPreview().size());
    }
}
