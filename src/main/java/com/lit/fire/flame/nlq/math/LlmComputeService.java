package com.lit.fire.flame.nlq.math;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmException;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The fallback that asks the LLM to compute one numeric value for a formula that is <b>not</b> in the
 * deterministic {@link FormulaEvaluator} catalog (F14). This is the only place the model is permitted to
 * do arithmetic — and only for uncatalogued formulas; every use is logged as a formula gap so the
 * formula can be implemented in code later.
 *
 * <p>It computes strictly from the provided rows (structured output {@code {computable, value,
 * expression, explanation}}) and returns {@code null} when the model reports it cannot compute a single
 * well-defined number, so the synthesis layer never fabricates a figure.
 */
@Service
public class LlmComputeService {

    private static final Logger log = LoggerFactory.getLogger(LlmComputeService.class);

    private static final String SYSTEM_PROMPT_RESOURCE = "/nlq/llm_compute_system.txt";
    private static final String TOOL_NAME = "emit_computed_value";
    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final LlmClient llmClient;
    private final Gson gson = new Gson();
    private final String systemPrompt;

    public LlmComputeService(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.systemPrompt = loadResource(SYSTEM_PROMPT_RESOURCE);
    }

    /**
     * Ask the model to compute {@code operation}'s value from {@code rows}. Returns the computed value
     * (with the expression/explanation the model used), or {@code null} if it could not compute a single
     * finite number.
     *
     * @throws LlmException if the model call itself fails
     */
    public Computed compute(String question, ComputationPlan.Operation operation,
                            List<Map<String, Object>> rows, String modelId) throws LlmException {
        String userPrompt = buildUserPrompt(question, operation, rows);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .jsonSchema(buildSchema())
                .structuredToolName(TOOL_NAME)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .modelId(blankToNull(modelId))
                .build();

        log.debug("LLM-computing uncatalogued formula '{}' over {} row(s)",
                operation.getFormula(), rows.size());

        LlmResponse response = llmClient.complete(request);
        JsonObject json = response.getStructuredJson();
        if (json == null) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE,
                    "model did not return a structured computed value");
        }
        if (!getBool(json, "computable")) {
            return null;
        }
        Double value = getDouble(json, "value");
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return new Computed(value, getString(json, "expression"), getString(json, "explanation"));
    }

    private String buildUserPrompt(String question, ComputationPlan.Operation op,
                                   List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question:\n").append(question).append("\n\n");
        sb.append("Formula to compute (not in the code catalog):\n");
        sb.append("- name: ").append(op.getName()).append('\n');
        sb.append("- formula: ").append(op.getFormula()).append('\n');
        if (op.getDescription() != null && !op.getDescription().isBlank()) {
            sb.append("- description: ").append(op.getDescription()).append('\n');
        }
        if (op.getExpression() != null && !op.getExpression().isBlank()) {
            sb.append("- expression hint: ").append(op.getExpression()).append('\n');
        }
        sb.append("- result columns to use: ").append(op.getColumns()).append('\n');
        if (!op.getArgs().isEmpty()) {
            sb.append("- parameters: ").append(gson.toJson(op.getArgs())).append('\n');
        }
        sb.append("\nData rows (JSON):\n").append(gson.toJson(rows)).append('\n');
        return sb.toString();
    }

    private static JsonObject buildSchema() {
        JsonObject computable = new JsonObject();
        computable.addProperty("type", "boolean");
        computable.addProperty("description",
                "True only if a single well-defined number can be computed from the provided rows.");
        JsonObject value = new JsonObject();
        value.addProperty("type", "number");
        value.addProperty("description", "The computed value; 0 when computable is false.");
        JsonObject expression = stringProperty("Short formula/method used, for later code implementation.");
        JsonObject explanation = stringProperty("One sentence on how the value was computed.");

        JsonObject properties = new JsonObject();
        properties.add("computable", computable);
        properties.add("value", value);
        properties.add("expression", expression);
        properties.add("explanation", explanation);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("computable");
        required.add("value");
        schema.add("required", required);
        return schema;
    }

    private static JsonObject stringProperty(String description) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "string");
        node.addProperty("description", description);
        return node;
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static boolean getBool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Double getDouble(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String loadResource(String path) {
        try (InputStream in = LlmComputeService.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load prompt resource: " + path, e);
        }
    }

    /** The LLM-computed value plus the method it reported, for the answer and the formula-gap log. */
    public static final class Computed {
        private final double value;
        private final String expression;
        private final String explanation;

        public Computed(double value, String expression, String explanation) {
            this.value = value;
            this.expression = expression;
            this.explanation = explanation;
        }

        public double getValue() {
            return value;
        }

        public String getExpression() {
            return expression;
        }

        public String getExplanation() {
            return explanation;
        }
    }
}
