package com.lit.fire.flame.nlq.math;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.audit.FormulaGapLogger;
import com.lit.fire.flame.nlq.audit.FormulaGapRecord;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import com.lit.fire.flame.nlq.sql.QueryResult;
import com.lit.fire.flame.nlq.sql.SqlGenerationResult;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the F14 fallback in {@link AnswerSynthesisService}: a planned formula that is NOT in the
 * deterministic {@link FormulaEvaluator} catalog is computed by the LLM, its value appears in the
 * answer, and a {@link FormulaGapRecord} is logged for later cataloging — while a catalog formula is
 * computed in Java and never reaches the LLM-compute fallback.
 */
class AnswerSynthesisFallbackTest {

    private QueryResult result() {
        List<QueryResult.Column> columns = List.of(new QueryResult.Column("score", Types.REAL, "REAL"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (double v : new double[]{10, 20, 30, 40}) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("score", v);
            rows.add(row);
        }
        return new QueryResult(columns, rows, false, 1L);
    }

    private SqlGenerationResult generation() {
        return SqlGenerationResult.ofSql("SELECT score FROM t", List.of("t"), new ArrayList<>(), null);
    }

    @Test
    void uncataloguedFormulaIsLlmComputedAndLogged() throws Exception {
        // Plan asks for "gini" — not in the catalog — over the score column.
        JsonObject plan = planWith("inequality", "gini", "score");
        JsonObject computed = computedValue(true, 0.25, "gini(score)", "Gini over the four scores.");
        RoutingLlm llm = new RoutingLlm(plan, answer("Inequality is 0.25."), computed);

        CapturingGapLogger gaps = new CapturingGapLogger();
        AnswerSynthesisService svc = new AnswerSynthesisService(llm, new LlmComputeService(llm), gaps);

        AskAnswer answer = svc.answer("how unequal are the scores", generation(), result(), null, "req-1");

        // The LLM-computed value is surfaced as a computed value...
        assertEquals(0.25, answer.getComputedValues().get("inequality"), 1e-9);
        assertTrue(answer.getFormulasApplied().stream().anyMatch(f -> f.getName().equals("inequality")));
        // ...and the gap was logged for later cataloging.
        assertEquals(1, gaps.records.size());
        assertEquals("gini", gaps.records.get(0).getFormulaName());
        assertEquals("req-1", gaps.records.get(0).getRequestId());
        // The fallback was used exactly once.
        assertEquals(1, llm.computeCalls);
    }

    @Test
    void catalogFormulaNeverHitsTheLlmFallback() throws Exception {
        // "mean" IS in the catalog, so commons-math3 computes it and the LLM-compute fallback is unused.
        JsonObject plan = planWith("avg_score", "mean", "score");
        RoutingLlm llm = new RoutingLlm(plan, answer("The mean is 25."), null);

        CapturingGapLogger gaps = new CapturingGapLogger();
        AnswerSynthesisService svc = new AnswerSynthesisService(llm, new LlmComputeService(llm), gaps);

        AskAnswer answer = svc.answer("what is the mean score", generation(), result(), null, "req-2");

        assertEquals(25.0, answer.getComputedValues().get("avg_score"), 1e-9);
        assertEquals(0, llm.computeCalls, "a catalog formula must not use the LLM-compute fallback");
        assertTrue(gaps.records.isEmpty(), "no formula gap for a catalog formula");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private JsonObject planWith(String name, String formula, String column) {
        JsonObject op = new JsonObject();
        op.addProperty("name", name);
        op.addProperty("formula", formula);
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

    private JsonObject computedValue(boolean computable, double value, String expr, String explanation) {
        JsonObject o = new JsonObject();
        o.addProperty("computable", computable);
        o.addProperty("value", value);
        o.addProperty("expression", expr);
        o.addProperty("explanation", explanation);
        return o;
    }

    private JsonObject answer(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("answer", text);
        return o;
    }

    /** Routes by structured-tool name and counts LLM-compute fallback invocations. */
    private static final class RoutingLlm implements LlmClient {
        private final JsonObject planJson;
        private final JsonObject answerJson;
        private final JsonObject computedJson;
        private int computeCalls = 0;

        RoutingLlm(JsonObject planJson, JsonObject answerJson, JsonObject computedJson) {
            this.planJson = planJson;
            this.answerJson = answerJson;
            this.computedJson = computedJson;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            JsonObject json;
            switch (request.getStructuredToolName()) {
                case "emit_computation_plan":
                    json = planJson;
                    break;
                case "emit_computed_value":
                    computeCalls++;
                    json = computedJson;
                    break;
                default:
                    json = answerJson;
            }
            return new LlmResponse("", json, "tool_use", 5, 5);
        }
    }

    /** Captures formula-gap records in memory instead of logging/persisting. */
    private static final class CapturingGapLogger extends FormulaGapLogger {
        private final List<FormulaGapRecord> records = new ArrayList<>();

        CapturingGapLogger() {
            super(new AskEngineProperties(), null);
        }

        @Override
        public void record(FormulaGapRecord record) {
            records.add(record);
        }
    }
}
