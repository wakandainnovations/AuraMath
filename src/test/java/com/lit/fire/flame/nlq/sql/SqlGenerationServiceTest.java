package com.lit.fire.flame.nlq.sql;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmException;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SchemaRenderer;
import com.lit.fire.flame.nlq.schema.SkipList;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SqlGenerationService} (F4).
 *
 * <p>Written on JUnit Jupiter so it runs under {@code mvn test} (see {@code ClaudeLlmClientTest} for
 * why the project's JUnit 4 tests are not discovered). The LLM is replaced with a capturing stub —
 * no network or API key is needed — so the tests assert exactly what the service builds and how it
 * interprets the model's structured reply. A real {@link SchemaIntrospector}/{@link SchemaRenderer}
 * over in-memory SQLite supplies the "small SQLite schema" from the acceptance criteria.
 */
class SqlGenerationServiceTest {

    private final SchemaIntrospector introspector = new SchemaIntrospector();
    private final SchemaRenderer renderer = new SchemaRenderer();

    /** A small SQLite fixture with a single {@code users} table. */
    private DatabaseSchema usersSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE users (" +
                        "id INTEGER PRIMARY KEY, " +
                        "email TEXT NOT NULL, " +
                        "created_at TEXT)");
            }
            return introspector.introspect(connection, SkipList.empty());
        }
    }

    /** A capturing {@link LlmClient} that records the request and returns a canned structured reply. */
    private static final class StubLlmClient implements LlmClient {
        private final JsonObject structured;
        private LlmRequest captured;

        StubLlmClient(JsonObject structured) {
            this.structured = structured;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            this.captured = request;
            return new LlmResponse("", structured, "tool_use", 12, 34);
        }
    }

    @Test
    void returnsSingleSelectWithLimitAndCorrectTable() throws Exception {
        DatabaseSchema schema = usersSchema();

        JsonObject modelOutput = new JsonObject();
        modelOutput.addProperty("sql", "SELECT count(*) AS signups FROM users "
                + "WHERE created_at >= date('now', 'start of month', '-1 month') "
                + "AND created_at < date('now', 'start of month') LIMIT 100");
        JsonArray tables = new JsonArray();
        tables.add("users");
        modelOutput.add("tablesUsed", tables);
        JsonArray assumptions = new JsonArray();
        assumptions.add("'signed up' maps to users.created_at");
        modelOutput.add("assumptions", assumptions);
        modelOutput.addProperty("confidence", 0.82);
        modelOutput.addProperty("clarificationNeeded", false);
        modelOutput.addProperty("clarificationQuestion", "");

        StubLlmClient stub = new StubLlmClient(modelOutput);
        SqlGenerationService service =
                new SqlGenerationService(renderer, stub, new AskEngineProperties());

        SqlGenerationResult result = service.generate("how many users signed up last month", schema);

        // A plausible single SELECT with a LIMIT and the correct table.
        assertFalse(result.isClarificationNeeded());
        assertNotNull(result.getSql());
        assertTrue(result.getSql().toLowerCase(java.util.Locale.ROOT).startsWith("select"));
        assertTrue(result.getSql().toLowerCase(java.util.Locale.ROOT).contains("limit"));
        assertTrue(result.getTablesUsed().stream().anyMatch(t -> t.equalsIgnoreCase("users")));
        assertEquals(0.82, result.getConfidence(), 1e-9);

        // The request actually carried the schema, the dialect, the row cap, and asked for structured output.
        LlmRequest sent = stub.captured;
        assertNotNull(sent);
        assertTrue(sent.isStructured(), "should request structured output");
        assertTrue(sent.getSystemPrompt().contains("sqlite"), "dialect should reach the system prompt");
        assertTrue(sent.getSystemPrompt().contains("1000"), "maxRows (default 1000) should reach the system prompt");
        assertTrue(sent.getUserPrompt().contains("TABLE users"), "rendered schema should reach the user prompt");
        assertTrue(sent.getUserPrompt().contains("how many users signed up last month"),
                "the question should reach the user prompt");
    }

    @Test
    void unknownTableReferenceForcesClarification() throws Exception {
        DatabaseSchema schema = usersSchema();

        // Model drafts SQL over a table that is not in the schema.
        JsonObject modelOutput = new JsonObject();
        modelOutput.addProperty("sql", "SELECT id, total FROM orders LIMIT 50");
        JsonArray tables = new JsonArray();
        tables.add("orders");
        modelOutput.add("tablesUsed", tables);
        modelOutput.add("assumptions", new JsonArray());
        modelOutput.addProperty("clarificationNeeded", false);

        StubLlmClient stub = new StubLlmClient(modelOutput);
        SqlGenerationService service =
                new SqlGenerationService(renderer, stub, new AskEngineProperties());

        SqlGenerationResult result = service.generate("list the orders", schema);

        assertTrue(result.isClarificationNeeded(), "an unknown table must force clarification");
        assertNull(result.getSql());
        assertNotNull(result.getClarificationQuestion());
        assertTrue(result.getClarificationQuestion().toLowerCase(java.util.Locale.ROOT).contains("orders"));
    }

    @Test
    void modelClarificationIsPassedThrough() throws Exception {
        DatabaseSchema schema = usersSchema();

        JsonObject modelOutput = new JsonObject();
        modelOutput.addProperty("sql", "");
        modelOutput.add("tablesUsed", new JsonArray());
        modelOutput.add("assumptions", new JsonArray());
        modelOutput.addProperty("clarificationNeeded", true);
        modelOutput.addProperty("clarificationQuestion", "Which time zone defines 'last month'?");

        StubLlmClient stub = new StubLlmClient(modelOutput);
        SqlGenerationService service =
                new SqlGenerationService(renderer, stub, new AskEngineProperties());

        SqlGenerationResult result = service.generate("how many signed up last month?", schema);

        assertTrue(result.isClarificationNeeded());
        assertNull(result.getSql());
        assertEquals("Which time zone defines 'last month'?", result.getClarificationQuestion());
    }

    @Test
    void missingStructuredOutputThrowsBadResponse() throws Exception {
        DatabaseSchema schema = usersSchema();
        StubLlmClient stub = new StubLlmClient(null); // no structured JSON came back
        SqlGenerationService service =
                new SqlGenerationService(renderer, stub, new AskEngineProperties());

        LlmException e = assertThrows(LlmException.class,
                () -> service.generate("anything", schema));
        assertEquals(LlmException.Kind.BAD_RESPONSE, e.getKind());
    }
}
