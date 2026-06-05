package com.lit.fire.flame.nlq.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.connection.ConnectionRequest;
import com.lit.fire.flame.nlq.connection.DynamicConnectionFactory;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import com.lit.fire.flame.nlq.math.AnswerSynthesisService;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SchemaRenderer;
import com.lit.fire.flame.nlq.sql.QueryExecutionService;
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
        return new AskOrchestrator(
                new DynamicConnectionFactory(properties),
                new SchemaIntrospector(),
                new SqlGenerationService(renderer, llm, properties),
                guard,
                new QueryExecutionService(guard, properties),
                new AnswerSynthesisService(llm),
                properties);
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
