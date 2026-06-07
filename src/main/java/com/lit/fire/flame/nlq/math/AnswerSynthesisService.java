package com.lit.fire.flame.nlq.math;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.audit.FormulaGapLogger;
import com.lit.fire.flame.nlq.audit.FormulaGapRecord;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmException;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import com.lit.fire.flame.nlq.sql.QueryResult;
import com.lit.fire.flame.nlq.sql.SqlGenerationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Feature F7 — the "mathematician" layer. Turns retrieved rows into a final natural-language answer,
 * applying formulas <b>deterministically</b> so the model never does arithmetic.
 *
 * <p>The design is plan-then-evaluate, in two model calls around a Java core:
 * <ol>
 *   <li><b>Plan</b> — the {@link LlmClient} (structured output) produces a {@link ComputationPlan}:
 *       which catalog formulas apply and over which result columns — <i>never</i> the final numbers.</li>
 *   <li><b>Evaluate</b> — {@link FormulaEvaluator} computes every value in Java with
 *       {@code commons-math3} and a restricted {@code exp4j} expression evaluator. Division-by-zero,
 *       empty result sets, and non-numeric columns are guarded: the offending operation is skipped
 *       and noted, never fabricated.</li>
 *   <li><b>Narrate</b> — the {@link LlmClient} writes a concise answer <i>given</i> the deterministic
 *       computed values and the rows; it is instructed never to invent numbers beyond them.</li>
 * </ol>
 *
 * <p>Two short-circuits skip the math entirely: an <b>empty result set</b> yields a factual "no data"
 * answer (never a hallucinated number), and a <b>pure-lookup</b> question (the plan sets
 * {@code lookupOnly}, or produces no operations) is answered directly from the rows.
 */
@Service
public class AnswerSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(AnswerSynthesisService.class);

    private static final String PLAN_PROMPT_RESOURCE = "/nlq/computation_plan_system.txt";
    private static final String NARRATIVE_PROMPT_RESOURCE = "/nlq/answer_narrative_system.txt";
    private static final String PLAN_TOOL_NAME = "emit_computation_plan";
    private static final String NARRATIVE_TOOL_NAME = "emit_answer";
    private static final int PLAN_MAX_TOKENS = 1536;
    private static final int NARRATIVE_MAX_TOKENS = 1024;

    /** Rows surfaced to the model and returned on {@link AskAnswer#getRowsPreview()}. */
    private static final int PREVIEW_ROWS = 20;

    private final LlmClient llmClient;
    private final FormulaEvaluator evaluator;
    private final LlmComputeService llmComputeService;
    private final FormulaGapLogger formulaGapLogger;
    private final Gson gson = new Gson();
    private final String planPrompt;
    private final String narrativePrompt;

    /**
     * Convenience constructor with the LLM-compute fallback disabled — only the deterministic catalog is
     * used (a formula outside it is skipped, as before). Used by tests and programmatic callers.
     */
    public AnswerSynthesisService(LlmClient llmClient) {
        this(llmClient, null, null);
    }

    /**
     * @param llmComputeService fallback that computes uncatalogued formulas via the LLM (F14); when
     *                          {@code null} the fallback is disabled
     * @param formulaGapLogger  records each LLM-computed formula for later cataloging; may be {@code null}
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AnswerSynthesisService(LlmClient llmClient,
                                  @org.springframework.lang.Nullable LlmComputeService llmComputeService,
                                  @org.springframework.lang.Nullable FormulaGapLogger formulaGapLogger) {
        this.llmClient = llmClient;
        this.evaluator = new FormulaEvaluator();
        this.llmComputeService = llmComputeService;
        this.formulaGapLogger = formulaGapLogger;
        this.planPrompt = loadResource(PLAN_PROMPT_RESOURCE);
        this.narrativePrompt = loadResource(NARRATIVE_PROMPT_RESOURCE);
    }

    /**
     * Synthesize a final answer for {@code question} from {@code execution}'s rows, applying any
     * statistics the question calls for deterministically.
     *
     * @param question  the original natural-language question
     * @param generation the SQL-generation result (for the executed SQL and its assumptions)
     * @param execution the typed rows returned by F6
     * @return the natural-language answer plus the formulas applied and exact computed values
     * @throws LlmException if a model call fails or returns no structured output
     */
    public AskAnswer answer(String question, SqlGenerationResult generation, QueryResult execution)
            throws LlmException {
        return answer(question, generation, execution, null, null);
    }

    /** As {@link #answer(String, SqlGenerationResult, QueryResult)} with a model override and no requestId. */
    public AskAnswer answer(String question, SqlGenerationResult generation, QueryResult execution,
                            String modelId) throws LlmException {
        return answer(question, generation, execution, modelId, null);
    }

    /**
     * As {@link #answer(String, SqlGenerationResult, QueryResult)}, but using {@code modelId} for the
     * plan and narrative model calls when it is non-blank, and tagging any formula-gap record (F14) with
     * {@code requestId}.
     *
     * @param modelId   optional model id override; {@code null}/blank falls back to the client default
     * @param requestId correlation id for formula-gap logging; may be {@code null}
     */
    public AskAnswer answer(String question, SqlGenerationResult generation, QueryResult execution,
                            String modelId, String requestId) throws LlmException {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question is required");
        }
        Objects.requireNonNull(execution, "execution");
        String q = question.trim();
        String model = blankToNull(modelId);

        String sql = (generation == null) ? null : generation.getSql();
        List<String> assumptions = new ArrayList<>();
        if (generation != null) {
            assumptions.addAll(generation.getAssumptions());
        }
        List<Map<String, Object>> preview = preview(execution);

        // Short-circuit 1 — no rows: a factual "no data", never a fabricated figure.
        if (execution.getRowCount() == 0) {
            return AskAnswer.builder()
                    .answer("No data: the query returned no rows, so there is nothing to compute.")
                    .sql(sql)
                    .assumptions(assumptions)
                    .rowsPreview(preview)
                    .build();
        }

        ComputationPlan plan = requestPlan(q, execution, model);

        // Short-circuit 2 — pure lookup (no math): answer straight from the rows.
        if (plan.isLookupOnly() || plan.getOperations().isEmpty()) {
            assumptions.addAll(plan.getNotes());
            String narrative = requestNarrative(q, new ArrayList<>(), new LinkedHashMap<>(), preview, model);
            return AskAnswer.builder()
                    .answer(narrative)
                    .sql(sql)
                    .assumptions(assumptions)
                    .rowsPreview(preview)
                    .build();
        }

        Computation comp = runPlan(plan, execution, preview, q, model, requestId);
        assumptions.addAll(plan.getNotes());
        assumptions.addAll(comp.issues);

        String narrative = requestNarrative(q, comp.formulasApplied, comp.computedValues, preview, model);

        return AskAnswer.builder()
                .answer(narrative)
                .formulasApplied(comp.formulasApplied)
                .computedValues(comp.computedValues)
                .sql(sql)
                .assumptions(assumptions)
                .rowsPreview(preview)
                .build();
    }

    /**
     * Synthesize one answer that <b>collates across several databases</b> (the federated path). Each
     * {@link LabeledResult} is one database's redacted rows; they are combined into a single view whose
     * columns are namespaced {@code <database>.<column>} so the {@link FormulaEvaluator} computes
     * per-database aggregates deterministically (a value missing for a row from another database is
     * simply skipped), while the narrative is shown which database each row came from so the LLM can
     * reason across them.
     *
     * <p>The result carries no single {@code sql} — the executed per-database queries are surfaced
     * separately on the response. Mirrors {@link #answer} otherwise: a fully empty result set yields a
     * factual "no data", and a pure-lookup plan answers straight from the rows.
     *
     * @param priorAssumptions assumptions gathered during federated SQL generation (may be {@code null})
     * @param results          one labeled, already-redacted result set per database
     * @param modelId          optional model id override; {@code null}/blank uses the client default
     */
    public AskAnswer answerFederated(String question, List<String> priorAssumptions,
                                     List<LabeledResult> results, String modelId) throws LlmException {
        return answerFederated(question, priorAssumptions, results, modelId, null);
    }

    /**
     * As {@link #answerFederated(String, List, List, String)}, but tagging any formula-gap record (F14)
     * with {@code requestId}.
     */
    public AskAnswer answerFederated(String question, List<String> priorAssumptions,
                                     List<LabeledResult> results, String modelId, String requestId)
            throws LlmException {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question is required");
        }
        Objects.requireNonNull(results, "results");
        String q = question.trim();
        String model = blankToNull(modelId);

        List<String> assumptions = new ArrayList<>();
        if (priorAssumptions != null) {
            assumptions.addAll(priorAssumptions);
        }

        QueryResult combined = combine(results);
        List<Map<String, Object>> preview = preview(combined);

        int totalRows = 0;
        for (LabeledResult r : results) {
            totalRows += r.getResult().getRowCount();
        }

        // Short-circuit 1 — no rows anywhere: a factual "no data", never a fabricated figure.
        if (totalRows == 0) {
            return AskAnswer.builder()
                    .answer("No data: every database returned no rows, so there is nothing to compute.")
                    .assumptions(assumptions)
                    .rowsPreview(preview)
                    .build();
        }

        ComputationPlan plan = requestPlan(q, combined, model);

        // Short-circuit 2 — pure lookup (no math): answer straight from the labeled rows.
        if (plan.isLookupOnly() || plan.getOperations().isEmpty()) {
            assumptions.addAll(plan.getNotes());
            String narrative = requestNarrative(q, new ArrayList<>(), new LinkedHashMap<>(), preview, model);
            return AskAnswer.builder()
                    .answer(narrative)
                    .assumptions(assumptions)
                    .rowsPreview(preview)
                    .build();
        }

        Computation comp = runPlan(plan, combined, preview, q, model, requestId);
        assumptions.addAll(plan.getNotes());
        assumptions.addAll(comp.issues);

        String narrative = requestNarrative(q, comp.formulasApplied, comp.computedValues, preview, model);

        return AskAnswer.builder()
                .answer(narrative)
                .formulasApplied(comp.formulasApplied)
                .computedValues(comp.computedValues)
                .assumptions(assumptions)
                .rowsPreview(preview)
                .build();
    }

    /**
     * Evaluate the plan deterministically (F7), then — when the LLM-compute fallback is wired (F14) —
     * compute each operation whose formula is not in the code catalog via the LLM, merge its value, and
     * log a formula gap. When the fallback is disabled the whole plan goes to the evaluator (uncatalogued
     * formulas are skipped with a note, as before).
     */
    private Computation runPlan(ComputationPlan plan, QueryResult execution,
                                List<Map<String, Object>> preview, String question, String model,
                                String requestId) throws LlmException {
        boolean fallback = (llmComputeService != null);

        List<ComputationPlan.Operation> forEvaluator = new ArrayList<>();
        List<ComputationPlan.Operation> forLlm = new ArrayList<>();
        for (ComputationPlan.Operation op : plan.getOperations()) {
            if (fallback && !FormulaEvaluator.isSupported(op.getFormula())) {
                forLlm.add(op);
            } else {
                forEvaluator.add(op);
            }
        }

        FormulaEvaluator.Result evaluated = evaluator.evaluate(
                new ComputationPlan(plan.isLookupOnly(), forEvaluator, plan.getNotes()), execution);
        Map<String, Double> computed = new LinkedHashMap<>(evaluated.getComputedValues());
        List<AppliedFormula> applied = new ArrayList<>(evaluated.getFormulasApplied());
        List<String> issues = new ArrayList<>(evaluated.getIssues());

        for (ComputationPlan.Operation op : forLlm) {
            String name = (op.getName() == null || op.getName().trim().isEmpty())
                    ? op.getFormula() : op.getName().trim();
            LlmComputeService.Computed result = llmComputeService.compute(question, op, preview, model);
            if (result == null) {
                issues.add("Could not compute '" + name + "': formula '" + op.getFormula()
                        + "' is not in the code catalog and the model could not compute it from the data.");
                continue;
            }
            computed.put(name, result.getValue());
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("columns", op.getColumns());
            inputs.put("source", "llm");
            String expression = (result.getExpression() == null || result.getExpression().isBlank())
                    ? op.getFormula() + "(" + String.join(", ", op.getColumns()) + ")"
                    : result.getExpression();
            applied.add(new AppliedFormula(name, expression, inputs, result.getValue()));
            if (result.getExplanation() != null && !result.getExplanation().isBlank()) {
                issues.add(name + ": computed by the model — " + result.getExplanation());
            }
            if (formulaGapLogger != null) {
                formulaGapLogger.record(new FormulaGapRecord(requestId, question, op.getFormula(),
                        op.getDescription(), expression, op.getColumns(), result.getValue()));
            }
        }
        return new Computation(computed, applied, issues);
    }

    /** The merged outcome of the deterministic + LLM-fallback computation. */
    private static final class Computation {
        private final Map<String, Double> computedValues;
        private final List<AppliedFormula> formulasApplied;
        private final List<String> issues;

        Computation(Map<String, Double> computedValues, List<AppliedFormula> formulasApplied,
                    List<String> issues) {
            this.computedValues = computedValues;
            this.formulasApplied = formulasApplied;
            this.issues = issues;
        }
    }

    /**
     * Fold the per-database result sets into one {@link QueryResult}: columns become
     * {@code <database>.<column>} (the union across databases), and each row carries a {@code _database}
     * label plus only its own database's namespaced cells. Because a row from one database has no key
     * for another's columns, single-column statistics aggregate strictly within their database.
     */
    private QueryResult combine(List<LabeledResult> results) {
        List<QueryResult.Column> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        long executionMillis = 0L;
        for (LabeledResult labeled : results) {
            String db = labeled.getDatabase();
            QueryResult r = labeled.getResult();
            truncated = truncated || r.isTruncated();
            executionMillis += r.getExecutionMillis();
            for (QueryResult.Column c : r.getColumns()) {
                columns.add(new QueryResult.Column(db + "." + c.getName(), c.getSqlType(), c.getTypeName()));
            }
            for (Map<String, Object> row : r.getRows()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("_database", db);
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    out.put(db + "." + e.getKey(), e.getValue());
                }
                rows.add(out);
            }
        }
        return new QueryResult(columns, rows, truncated, executionMillis);
    }

    // --- step 2a: ask for a computation plan -----------------------------------------------

    private ComputationPlan requestPlan(String question, QueryResult execution, String modelId)
            throws LlmException {
        String userPrompt = "Question:\n" + question + "\n\n"
                + "Result columns:\n" + describeColumns(execution) + "\n\n"
                + "Sample rows (JSON):\n" + gson.toJson(preview(execution)) + "\n";

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(planPrompt)
                .userPrompt(userPrompt)
                .jsonSchema(buildPlanSchema())
                .structuredToolName(PLAN_TOOL_NAME)
                .maxTokens(PLAN_MAX_TOKENS)
                .modelId(modelId)
                .build();

        log.debug("Requesting computation plan over {} column(s), {} row(s)",
                execution.getColumns().size(), execution.getRowCount());

        JsonObject json = structured(llmClient.complete(request), "computation plan");
        return parsePlan(json);
    }

    private ComputationPlan parsePlan(JsonObject json) {
        boolean lookupOnly = getBool(json, "lookupOnly");
        List<String> notes = getStringList(json, "notes");
        List<ComputationPlan.Operation> operations = new ArrayList<>();

        JsonElement opsElement = json.get("operations");
        if (opsElement != null && opsElement.isJsonArray()) {
            for (JsonElement element : opsElement.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject op = element.getAsJsonObject();
                String name = getString(op, "name");
                String formula = getString(op, "formula");
                if (formula == null || formula.trim().isEmpty()) {
                    continue;
                }
                operations.add(new ComputationPlan.Operation(
                        name,
                        formula,
                        getStringList(op, "columns"),
                        getNumberMap(op, "args"),
                        getString(op, "expression"),
                        getString(op, "description")));
            }
        }
        return new ComputationPlan(lookupOnly, operations, notes);
    }

    // --- step 3: ask for the natural-language answer ---------------------------------------

    private String requestNarrative(String question, List<AppliedFormula> formulas,
                                    Map<String, Double> computedValues,
                                    List<Map<String, Object>> preview, String modelId)
            throws LlmException {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Question:\n").append(question).append("\n\n");
        userPrompt.append("Computed values (the only numbers you may state, besides row cells):\n")
                .append(computedValues.isEmpty() ? "(none — this is a lookup)" : gson.toJson(computedValues))
                .append("\n\n");
        if (!formulas.isEmpty()) {
            userPrompt.append("Formulas applied:\n").append(gson.toJson(describeFormulas(formulas))).append("\n\n");
        }
        userPrompt.append("Sample rows (JSON):\n").append(gson.toJson(preview)).append("\n");

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(narrativePrompt)
                .userPrompt(userPrompt.toString())
                .jsonSchema(buildNarrativeSchema())
                .structuredToolName(NARRATIVE_TOOL_NAME)
                .maxTokens(NARRATIVE_MAX_TOKENS)
                .modelId(modelId)
                .build();

        JsonObject json = structured(llmClient.complete(request), "answer narrative");
        String answer = getString(json, "answer");
        if (answer == null || answer.trim().isEmpty()) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE, "model returned an empty answer");
        }
        return answer.trim();
    }

    private List<Map<String, Object>> describeFormulas(List<AppliedFormula> formulas) {
        List<Map<String, Object>> out = new ArrayList<>(formulas.size());
        for (AppliedFormula f : formulas) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", f.getName());
            entry.put("expression", f.getExpression());
            entry.put("inputs", f.getInputs());
            entry.put("result", f.getResult());
            out.add(entry);
        }
        return out;
    }

    // --- helpers ----------------------------------------------------------------------------

    private List<Map<String, Object>> preview(QueryResult execution) {
        int cap = Math.min(PREVIEW_ROWS, execution.getRows().size());
        return new ArrayList<>(execution.getRows().subList(0, cap));
    }

    private String describeColumns(QueryResult execution) {
        StringBuilder sb = new StringBuilder();
        for (QueryResult.Column column : execution.getColumns()) {
            sb.append("- ").append(column.getName())
                    .append(" (").append(column.getTypeName()).append(")\n");
        }
        return sb.toString();
    }

    private JsonObject structured(LlmResponse response, String what) throws LlmException {
        JsonObject json = response.getStructuredJson();
        if (json == null) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE,
                    "model did not return a structured " + what);
        }
        return json;
    }

    private static JsonObject buildPlanSchema() {
        JsonObject operation = new JsonObject();
        operation.addProperty("type", "object");
        JsonObject opProps = new JsonObject();
        opProps.add("name", stringProperty("Unique snake_case name for this operation."));
        opProps.add("formula", stringProperty(
                "Catalog key: count, sum, mean, min, max, median, std_dev, variance, percentile, "
                        + "weighted_average, growth_rate, cagr, regression_slope, regression_intercept, "
                        + "correlation, or expression."));
        opProps.add("columns", stringArrayProperty(
                "Exact result-column names the formula reads, in the order it expects."));
        JsonObject args = new JsonObject();
        args.addProperty("type", "object");
        JsonObject argValue = new JsonObject();
        argValue.addProperty("type", "number");
        args.add("additionalProperties", argValue);
        args.addProperty("description", "Numeric parameters, e.g. {\"percentile\": 95} or {\"periods\": 4}.");
        opProps.add("args", args);
        opProps.add("expression", stringProperty(
                "For formula=expression only: arithmetic over earlier operation names, e.g. "
                        + "\"(revenue_total - cost_total) / revenue_total\"."));
        opProps.add("description", stringProperty("What this operation means; optional."));
        operation.add("properties", opProps);
        JsonArray opRequired = new JsonArray();
        opRequired.add("name");
        opRequired.add("formula");
        operation.add("required", opRequired);

        JsonObject operations = new JsonObject();
        operations.addProperty("type", "array");
        operations.add("items", operation);
        operations.addProperty("description",
                "Ordered operations to apply; empty when lookupOnly is true.");

        JsonObject lookupOnly = new JsonObject();
        lookupOnly.addProperty("type", "boolean");
        lookupOnly.addProperty("description",
                "True for a pure-lookup question that needs no math; then operations is empty.");

        JsonObject properties = new JsonObject();
        properties.add("lookupOnly", lookupOnly);
        properties.add("operations", operations);
        properties.add("notes", stringArrayProperty(
                "Interpretations made (which column, what a term means)."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("lookupOnly");
        required.add("operations");
        schema.add("required", required);
        return schema;
    }

    private static JsonObject buildNarrativeSchema() {
        JsonObject properties = new JsonObject();
        properties.add("answer", stringProperty(
                "The concise natural-language answer, stating the exact computed figure(s)."));
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("answer");
        schema.add("required", required);
        return schema;
    }

    private static JsonObject stringProperty(String description) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "string");
        node.addProperty("description", description);
        return node;
    }

    private static JsonObject stringArrayProperty(String description) {
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        JsonObject node = new JsonObject();
        node.addProperty("type", "array");
        node.add("items", items);
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

    private static List<String> getStringList(JsonObject object, String key) {
        List<String> out = new ArrayList<>();
        JsonElement element = object.get(key);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                }
            }
        }
        return out;
    }

    private static Map<String, Double> getNumberMap(JsonObject object, String key) {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonElement element = object.get(key);
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement v = entry.getValue();
                if (v != null && v.isJsonPrimitive()) {
                    try {
                        out.put(entry.getKey(), v.getAsDouble());
                    } catch (RuntimeException ignored) {
                        // non-numeric arg — skip it
                    }
                }
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String loadResource(String path) {
        try (InputStream in = AnswerSynthesisService.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load prompt resource: " + path, e);
        }
    }

    /** One database's redacted result set, tagged with its logical name, for {@link #answerFederated}. */
    public static final class LabeledResult {
        private final String database;
        private final QueryResult result;

        public LabeledResult(String database, QueryResult result) {
            this.database = database;
            this.result = result;
        }

        /** The logical database name this result came from; never {@code null}. */
        public String getDatabase() {
            return database;
        }

        /** The (already skip-list-redacted) rows from that database; never {@code null}. */
        public QueryResult getResult() {
            return result;
        }
    }
}
