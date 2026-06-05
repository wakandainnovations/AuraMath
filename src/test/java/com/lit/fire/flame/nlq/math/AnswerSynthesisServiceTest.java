package com.lit.fire.flame.nlq.math;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import com.lit.fire.flame.nlq.sql.QueryResult;
import com.lit.fire.flame.nlq.sql.SqlGenerationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AnswerSynthesisService} (F7). The LLM is a capturing stub that answers each of the
 * two structured calls (plan, narrate) by tool name, so the tests run offline with no API key and
 * assert the deterministic core: a "mean" plan is computed exactly by Java and surfaced as the exact
 * figure, an empty result set short-circuits to "no data" without any model call, and a lookup plan
 * answers straight from the rows with no fabricated math.
 */
class AnswerSynthesisServiceTest {

    /** Routes the two structured calls by their advertised tool name and records what it saw. */
    private static final class StubLlmClient implements LlmClient {
        private final JsonObject planJson;
        private final JsonObject answerJson;
        private LlmRequest planRequest;
        private LlmRequest answerRequest;
        private int calls;

        StubLlmClient(JsonObject planJson, JsonObject answerJson) {
            this.planJson = planJson;
            this.answerJson = answerJson;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            if ("emit_computation_plan".equals(request.getStructuredToolName())) {
                planRequest = request;
                return new LlmResponse("", planJson, "tool_use", 10, 20);
            }
            answerRequest = request;
            return new LlmResponse("", answerJson, "tool_use", 10, 20);
        }
    }

    private QueryResult orderValues(Object... values) {
        List<QueryResult.Column> columns = new ArrayList<>();
        columns.add(new QueryResult.Column("order_value", java.sql.Types.NUMERIC, "NUMERIC"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object v : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order_value", v);
            rows.add(row);
        }
        return new QueryResult(columns, rows, false, 1L);
    }

    private JsonObject meanPlan() {
        JsonObject op = new JsonObject();
        op.addProperty("name", "average_order_value");
        op.addProperty("formula", "mean");
        JsonArray cols = new JsonArray();
        cols.add("order_value");
        op.add("columns", cols);
        JsonArray ops = new JsonArray();
        ops.add(op);
        JsonObject plan = new JsonObject();
        plan.addProperty("lookupOnly", false);
        plan.add("operations", ops);
        return plan;
    }

    private JsonObject answer(String text) {
        JsonObject json = new JsonObject();
        json.addProperty("answer", text);
        return json;
    }

    private SqlGenerationResult generation() {
        return SqlGenerationResult.ofSql("SELECT order_value FROM orders LIMIT 1000",
                List.of("orders"), List.of("'order value' maps to orders.order_value"), 0.9);
    }

    @Test
    void averageOrderValueIsComputedInJavaAndStatedExactly() throws Exception {
        StubLlmClient stub = new StubLlmClient(meanPlan(),
                answer("The average order value is 25."));
        AnswerSynthesisService service = new AnswerSynthesisService(stub);

        AskAnswer result = service.answer("what is the average order value",
                generation(), orderValues(10, 20, 30, 40));

        // commons-math3 computed the mean; it is surfaced exactly.
        assertEquals(25.0, result.getComputedValues().get("average_order_value"), 1e-9);
        assertEquals(1, result.getFormulasApplied().size());
        assertEquals("mean(order_value)", result.getFormulasApplied().get(0).getExpression());
        assertNotNull(result.getAnswer());
        assertFalse(result.getAnswer().isEmpty());
        assertEquals("SELECT order_value FROM orders LIMIT 1000", result.getSql());

        // The narrative call was handed the exact computed figure to state (and nothing more).
        assertNotNull(stub.answerRequest);
        assertTrue(stub.answerRequest.getUserPrompt().contains("25"),
                "computed value should reach the narrative prompt");
        // The plan call carried the result columns.
        assertTrue(stub.planRequest.getUserPrompt().contains("order_value"));
    }

    @Test
    void emptyResultSetYieldsNoDataWithoutCallingTheModel() throws Exception {
        StubLlmClient stub = new StubLlmClient(meanPlan(), answer("unused"));
        AnswerSynthesisService service = new AnswerSynthesisService(stub);

        AskAnswer result = service.answer("what is the average order value",
                generation(), orderValues());

        assertTrue(result.getAnswer().toLowerCase(java.util.Locale.ROOT).contains("no data"));
        assertTrue(result.getComputedValues().isEmpty());
        assertTrue(result.getFormulasApplied().isEmpty());
        assertEquals(0, stub.calls, "an empty result set must not reach the LLM");
    }

    @Test
    void lookupQuestionAnswersFromRowsWithNoMath() throws Exception {
        JsonObject lookup = new JsonObject();
        lookup.addProperty("lookupOnly", true);
        lookup.add("operations", new JsonArray());

        StubLlmClient stub = new StubLlmClient(lookup,
                answer("The most recent order value is 40."));
        AnswerSynthesisService service = new AnswerSynthesisService(stub);

        AskAnswer result = service.answer("what is the latest order value",
                generation(), orderValues(10, 20, 30, 40));

        assertTrue(result.getComputedValues().isEmpty());
        assertTrue(result.getFormulasApplied().isEmpty());
        assertFalse(result.getAnswer().isEmpty());
        // The narrative was told there are no computed values (pure lookup).
        assertTrue(stub.answerRequest.getUserPrompt().contains("none — this is a lookup"));
    }
}
