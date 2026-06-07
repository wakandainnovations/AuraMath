package com.lit.fire.flame.nlq.sql;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmException;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.NamedSchema;
import com.lit.fire.flame.nlq.schema.SchemaRenderer;
import com.lit.fire.flame.nlq.schema.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Feature F4 — translate a natural-language question into a single read-only {@code SELECT}/{@code WITH}
 * query, using the rendered schema (F2) and the pluggable {@link LlmClient} (F3).
 *
 * <p>The service builds a strong, schema-bounded prompt and asks the model — via the client's
 * structured-output mode — to return a {@link SqlGenerationResult} (sql, tablesUsed, assumptions,
 * confidence, and a clarification path). It does a cheap pre-check that every reported table exists
 * in the schema and, if not, converts the result into a clarification rather than handing back SQL
 * that references something the model invented.
 *
 * <p><b>It does not execute anything and does not trust the output.</b> The returned SQL is a draft
 * only — read-only and skip-list validation (F5) and bounded execution (F6) happen downstream.
 */
@Service
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    /** Classpath location of the system-prompt template (placeholders {@code ${dialect}}, {@code ${maxRows}}). */
    private static final String SYSTEM_PROMPT_RESOURCE = "/nlq/sql_generation_system.txt";
    /** Classpath location of the federated (multi-database) system-prompt template ({@code ${maxRows}}). */
    private static final String FEDERATED_SYSTEM_PROMPT_RESOURCE = "/nlq/sql_generation_federated_system.txt";
    /** Output cap — a single SQL statement plus its small metadata comfortably fits. */
    private static final int MAX_OUTPUT_TOKENS = 2048;
    /** Output cap for the federated plan — several sub-queries plus metadata. */
    private static final int FEDERATED_MAX_OUTPUT_TOKENS = 4096;
    /** Name advertised for the structured-output tool. */
    private static final String TOOL_NAME = "emit_sql";
    /** Name advertised for the federated structured-output tool. */
    private static final String FEDERATED_TOOL_NAME = "emit_federated_sql";

    private final SchemaRenderer schemaRenderer;
    private final LlmClient llmClient;
    private final AskEngineProperties properties;
    private final String systemPromptTemplate;
    private final String federatedSystemPromptTemplate;

    public SqlGenerationService(SchemaRenderer schemaRenderer, LlmClient llmClient,
                                AskEngineProperties properties) {
        this.schemaRenderer = schemaRenderer;
        this.llmClient = llmClient;
        this.properties = properties;
        this.systemPromptTemplate = loadResource(SYSTEM_PROMPT_RESOURCE);
        this.federatedSystemPromptTemplate = loadResource(FEDERATED_SYSTEM_PROMPT_RESOURCE);
    }

    /**
     * Draft a single read-only query answering {@code question} against {@code schema}. The schema's
     * detected {@link DatabaseSchema#getDialect() dialect} drives dialect-correct syntax.
     *
     * @return a drafted (unvalidated) SQL result, or one flagged {@link SqlGenerationResult#isClarificationNeeded()}
     * @throws LlmException if the model call fails or returns no structured output
     */
    public SqlGenerationResult generate(String question, DatabaseSchema schema) throws LlmException {
        return generate(question, schema, null);
    }

    /**
     * As {@link #generate(String, DatabaseSchema)}, but using {@code modelId} for the underlying
     * model call when it is non-blank (otherwise the {@link LlmClient}'s default model is used).
     *
     * @param modelId optional model id override; {@code null}/blank falls back to the client default
     */
    public SqlGenerationResult generate(String question, DatabaseSchema schema, String modelId)
            throws LlmException {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question is required");
        }
        Objects.requireNonNull(schema, "schema");

        int maxRows = properties.getMaxRows();
        String dialect = schema.getDialect();
        String dialectName = (dialect == null || dialect.isEmpty()) ? "sql" : dialect;
        String schemaText = schemaRenderer.render(schema);

        String systemPrompt = systemPromptTemplate
                .replace("${dialect}", dialectName)
                .replace("${maxRows}", Integer.toString(maxRows));
        String userPrompt = buildUserPrompt(question.trim(), dialectName, schemaText);

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .jsonSchema(buildResultSchema())
                .structuredToolName(TOOL_NAME)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .modelId(blankToNull(modelId))
                .build();

        log.debug("Generating SQL for dialect '{}' over {} table(s), maxRows={}",
                dialectName, schema.getTables().size(), maxRows);

        LlmResponse response = llmClient.complete(request);
        JsonObject json = response.getStructuredJson();
        if (json == null) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE,
                    "model did not return structured SQL output");
        }

        return enforceTableSubset(parse(json), schema);
    }

    private String buildUserPrompt(String question, String dialect, String schemaText) {
        return "SQL dialect: " + dialect + "\n\n"
                + "Database schema — only these tables and columns exist:\n"
                + schemaText + "\n"
                + "Question:\n" + question + "\n";
    }

    /**
     * Draft one read-only sub-query per database needed to answer {@code question} across several
     * independent {@code schemas} (the federated, multi-database path). Because separate JDBC
     * connections cannot be joined, the model emits at most one query per database; downstream layers
     * validate (F5), execute (F6), and collate the results (F7).
     *
     * @param schemas the skip-list-filtered schemas, each tagged with its logical database name
     * @param modelId optional model id override; {@code null}/blank falls back to the client default
     * @return a federated plan of per-database sub-queries, or one flagged
     *         {@link FederatedSqlPlan#isClarificationNeeded()}
     * @throws LlmException if the model call fails or returns no structured output
     */
    public FederatedSqlPlan generateFederated(String question, List<NamedSchema> schemas, String modelId)
            throws LlmException {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question is required");
        }
        if (schemas == null || schemas.isEmpty()) {
            throw new IllegalArgumentException("at least one schema is required");
        }

        int maxRows = properties.getMaxRows();
        String systemPrompt = federatedSystemPromptTemplate.replace("${maxRows}", Integer.toString(maxRows));
        String userPrompt = buildFederatedUserPrompt(question.trim(), schemas);

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .jsonSchema(buildFederatedSchema())
                .structuredToolName(FEDERATED_TOOL_NAME)
                .maxTokens(FEDERATED_MAX_OUTPUT_TOKENS)
                .modelId(blankToNull(modelId))
                .build();

        log.debug("Generating federated SQL over {} database(s), maxRows={}", schemas.size(), maxRows);

        LlmResponse response = llmClient.complete(request);
        JsonObject json = response.getStructuredJson();
        if (json == null) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE,
                    "model did not return structured federated SQL output");
        }
        return enforceFederated(parseFederated(json), schemas);
    }

    private String buildFederatedUserPrompt(String question, List<NamedSchema> schemas) {
        StringBuilder sb = new StringBuilder();
        sb.append("There are ").append(schemas.size())
                .append(" separate databases. They cannot be joined to each other.\n\n");
        for (NamedSchema named : schemas) {
            DatabaseSchema schema = named.getSchema();
            String dialect = (schema.getDialect() == null || schema.getDialect().isEmpty())
                    ? "sql" : schema.getDialect();
            sb.append("=== database: ").append(named.getName())
                    .append(" (dialect: ").append(dialect).append(") ===\n")
                    .append(schemaRenderer.render(schema)).append('\n');
        }
        sb.append("Question:\n").append(question).append('\n');
        return sb.toString();
    }

    /** Map the model's structured JSON into a {@link FederatedSqlPlan}, defending against omissions. */
    private FederatedSqlPlan parseFederated(JsonObject json) {
        boolean clarificationNeeded = getBool(json, "clarificationNeeded");
        List<String> assumptions = getStringList(json, "assumptions");
        List<String> missingData = getStringList(json, "missingData");
        Double confidence = getDouble(json, "confidence");
        String clarificationQuestion = getString(json, "clarificationQuestion");

        List<SubQuery> queries = new ArrayList<>();
        JsonElement queriesElement = json.get("queries");
        if (queriesElement != null && queriesElement.isJsonArray()) {
            for (JsonElement element : queriesElement.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject q = element.getAsJsonObject();
                String database = getString(q, "database");
                String sql = getString(q, "sql");
                if (database == null || database.trim().isEmpty()
                        || sql == null || sql.trim().isEmpty()) {
                    continue;
                }
                queries.add(new SubQuery(database.trim(), sql.trim(), getStringList(q, "tablesUsed")));
            }
        }

        if (clarificationNeeded || queries.isEmpty()) {
            String q = (clarificationQuestion == null || clarificationQuestion.trim().isEmpty())
                    ? "The question could not be turned into queries from the available databases. "
                            + "Please add detail or point at the relevant database(s)."
                    : clarificationQuestion.trim();
            return FederatedSqlPlan.needingClarification(q, assumptions, confidence, missingData);
        }
        return FederatedSqlPlan.ofQueries(queries, assumptions, confidence);
    }

    /**
     * Pre-check (the federated analogue of {@link #enforceTableSubset}): every sub-query must target a
     * known database and read only tables that exist in <em>that</em> database's schema. Any miss
     * converts the whole plan to a clarification rather than handing back SQL referencing something the
     * model invented. F5 still re-checks read-only and skip-list constraints per sub-query.
     */
    private FederatedSqlPlan enforceFederated(FederatedSqlPlan plan, List<NamedSchema> schemas) {
        if (plan.isClarificationNeeded()) {
            return plan;
        }
        Map<String, Set<String>> tablesByDb = new HashMap<>();
        for (NamedSchema named : schemas) {
            Set<String> known = new HashSet<>();
            for (TableInfo table : named.getSchema().getTables()) {
                known.add(table.getName().toLowerCase(Locale.ROOT));
                known.add(table.qualifiedName().toLowerCase(Locale.ROOT));
            }
            tablesByDb.put(named.getName().toLowerCase(Locale.ROOT), known);
        }

        List<String> problems = new ArrayList<>();
        for (SubQuery q : plan.getQueries()) {
            Set<String> known = tablesByDb.get(q.getDatabase().toLowerCase(Locale.ROOT));
            if (known == null) {
                problems.add("unknown database '" + q.getDatabase() + "'");
                continue;
            }
            for (String used : q.getTablesUsed()) {
                if (used == null) {
                    continue;
                }
                String normalized = used.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty() && !known.contains(normalized)) {
                    problems.add("table '" + used.trim() + "' not in database '" + q.getDatabase() + "'");
                }
            }
        }
        if (problems.isEmpty()) {
            return plan;
        }
        log.debug("Federated SQL referenced unknown database/table(s) {} — returning clarification", problems);
        String question = "The drafted queries reference things not in the provided databases: "
                + String.join("; ", problems)
                + ". Please rephrase the question or specify which available database/table to use.";
        return FederatedSqlPlan.needingClarification(question, plan.getAssumptions(),
                plan.getConfidence(), plan.getMissingData());
    }

    /** Map the model's structured JSON into a {@link SqlGenerationResult}, defending against omissions. */
    private SqlGenerationResult parse(JsonObject json) {
        boolean clarificationNeeded = getBool(json, "clarificationNeeded");
        List<String> tablesUsed = getStringList(json, "tablesUsed");
        List<String> assumptions = getStringList(json, "assumptions");
        List<String> missingData = getStringList(json, "missingData");
        Double confidence = getDouble(json, "confidence");
        String sql = getString(json, "sql");
        String clarificationQuestion = getString(json, "clarificationQuestion");

        // Treat a missing/empty SQL as a clarification even if the model forgot to set the flag.
        if (clarificationNeeded || sql == null || sql.trim().isEmpty()) {
            String question = (clarificationQuestion == null || clarificationQuestion.trim().isEmpty())
                    ? "The question could not be turned into a SQL query from the available schema. "
                            + "Please add detail or point at the relevant tables."
                    : clarificationQuestion.trim();
            return SqlGenerationResult.needingClarification(question, tablesUsed, assumptions,
                    confidence, missingData);
        }
        return SqlGenerationResult.ofSql(sql.trim(), tablesUsed, assumptions, confidence);
    }

    /**
     * Cheap pre-check (step 4 of F4): every reported table must exist in the schema. If the model
     * named a table that is not present, do not hand back the SQL — convert it to a clarification.
     * This is a guard, not the validator: F5 re-checks read-only and skip-list constraints.
     */
    private SqlGenerationResult enforceTableSubset(SqlGenerationResult result, DatabaseSchema schema) {
        if (result.isClarificationNeeded()) {
            return result;
        }
        Set<String> known = new HashSet<>();
        for (TableInfo table : schema.getTables()) {
            known.add(table.getName().toLowerCase(Locale.ROOT));
            known.add(table.qualifiedName().toLowerCase(Locale.ROOT));
        }
        List<String> unknown = new ArrayList<>();
        for (String used : result.getTablesUsed()) {
            if (used == null) {
                continue;
            }
            String normalized = used.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !known.contains(normalized)) {
                unknown.add(used.trim());
            }
        }
        if (unknown.isEmpty()) {
            return result;
        }
        log.debug("Generated SQL referenced table(s) not in schema {} — returning clarification", unknown);
        String question = "The drafted query references table(s) not in the provided schema: "
                + String.join(", ", unknown)
                + ". Please rephrase the question or specify which available table to use.";
        return SqlGenerationResult.needingClarification(
                question, result.getTablesUsed(), result.getAssumptions(), result.getConfidence(),
                result.getMissingData());
    }

    /** The JSON Schema the model must fill in via structured output. */
    private static JsonObject buildResultSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        properties.add("sql", stringProperty(
                "A single read-only SELECT or WITH query for the target dialect, "
                        + "or an empty string when clarificationNeeded is true."));
        properties.add("tablesUsed", stringArrayProperty(
                "Tables (or schema.table) the query reads; every one must exist in the provided schema."));
        properties.add("assumptions", stringArrayProperty(
                "Interpretations made while answering (e.g. which column means 'signed up')."));

        JsonObject confidence = new JsonObject();
        confidence.addProperty("type", "number");
        confidence.addProperty("description", "Confidence in the generated SQL, from 0.0 to 1.0.");
        properties.add("confidence", confidence);

        JsonObject clarificationNeeded = new JsonObject();
        clarificationNeeded.addProperty("type", "boolean");
        clarificationNeeded.addProperty("description",
                "True when the question cannot be answered from the schema; return a question instead of SQL.");
        properties.add("clarificationNeeded", clarificationNeeded);

        properties.add("clarificationQuestion", stringProperty(
                "A specific question to ask the user when clarificationNeeded is true; empty otherwise."));
        properties.add("missingData", stringArrayProperty(
                "When clarificationNeeded is true, the specific data the question needs but the schema "
                        + "does not provide (e.g. 'a refund-date column', 'a costs table'); empty otherwise."));

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("sql");
        required.add("tablesUsed");
        required.add("assumptions");
        required.add("clarificationNeeded");
        schema.add("required", required);
        return schema;
    }

    /** The JSON Schema the model must fill in for the federated (multi-database) path. */
    private static JsonObject buildFederatedSchema() {
        JsonObject query = new JsonObject();
        query.addProperty("type", "object");
        JsonObject queryProps = new JsonObject();
        queryProps.add("database", stringProperty(
                "The exact database name (from its '=== database: <name> ===' header) this query runs against."));
        queryProps.add("sql", stringProperty(
                "A single read-only SELECT or WITH query, valid for that database's dialect."));
        queryProps.add("tablesUsed", stringArrayProperty(
                "Tables (or schema.table) the query reads; every one must exist in that database's schema."));
        query.add("properties", queryProps);
        JsonArray queryRequired = new JsonArray();
        queryRequired.add("database");
        queryRequired.add("sql");
        queryRequired.add("tablesUsed");
        query.add("required", queryRequired);

        JsonObject queries = new JsonObject();
        queries.addProperty("type", "array");
        queries.add("items", query);
        queries.addProperty("description",
                "One read-only query per database needed; empty when clarificationNeeded is true.");

        JsonObject properties = new JsonObject();
        properties.add("queries", queries);
        properties.add("assumptions", stringArrayProperty(
                "Interpretations made while answering, including how the databases' rows correspond."));

        JsonObject confidence = new JsonObject();
        confidence.addProperty("type", "number");
        confidence.addProperty("description", "Confidence in the generated queries, from 0.0 to 1.0.");
        properties.add("confidence", confidence);

        JsonObject clarificationNeeded = new JsonObject();
        clarificationNeeded.addProperty("type", "boolean");
        clarificationNeeded.addProperty("description",
                "True when the question cannot be answered from the databases; return a question instead.");
        properties.add("clarificationNeeded", clarificationNeeded);
        properties.add("clarificationQuestion", stringProperty(
                "A specific question to ask the user when clarificationNeeded is true; empty otherwise."));
        properties.add("missingData", stringArrayProperty(
                "When clarificationNeeded is true, the specific data the question needs but the databases "
                        + "do not provide; empty otherwise."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("queries");
        required.add("clarificationNeeded");
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

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String loadResource(String path) {
        try (InputStream in = SqlGenerationService.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load prompt resource: " + path, e);
        }
    }
}
