package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.audit.AskAuditLogger;
import com.lit.fire.flame.nlq.audit.AskAuditRecord;
import com.lit.fire.flame.nlq.audit.AskMetrics;
import com.lit.fire.flame.nlq.audit.LlmUsageRecorder;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.connection.ConnectionRequest;
import com.lit.fire.flame.nlq.connection.DatasourceRegistry;
import com.lit.fire.flame.nlq.connection.DynamicConnectionFactory;
import com.lit.fire.flame.nlq.math.AnswerSynthesisService;
import com.lit.fire.flame.nlq.math.AskAnswer;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.NamedSchema;
import com.lit.fire.flame.nlq.schema.SchemaCacheService;
import com.lit.fire.flame.nlq.schema.SchemaFilter;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SkipList;
import com.lit.fire.flame.nlq.schema.SkipListFactory;
import com.lit.fire.flame.nlq.sql.FederatedSqlPlan;
import com.lit.fire.flame.nlq.sql.QueryExecutionException;
import com.lit.fire.flame.nlq.sql.QueryExecutionService;
import com.lit.fire.flame.nlq.sql.QueryResult;
import com.lit.fire.flame.nlq.sql.ResultRedactor;
import com.lit.fire.flame.nlq.sql.SqlGenerationResult;
import com.lit.fire.flame.nlq.sql.SqlGenerationService;
import com.lit.fire.flame.nlq.sql.SqlSafetyGuard;
import com.lit.fire.flame.nlq.sql.SubQuery;
import com.lit.fire.flame.nlq.sql.UnsafeSqlException;
import com.lit.fire.flame.nlq.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Feature F8 — the end-to-end orchestrator that runs one Ask through the F1–F7 pipeline:
 *
 * <pre>
 *   open connection (F1) → introspect with the effective skip-list (F2) → generate SQL (F4)
 *     → if clarification needed, return early → validate (F5) → execute (F6) → synthesize (F7)
 * </pre>
 *
 * <p>The target connection (F1) is <b>always closed in a {@code finally} block</b>; it is short-lived
 * and owned here. The effective skip-list is the union of the server-side
 * {@code aura.ask.default-skip-tables}, the connection's own skip lists, and the request's — and it is
 * honoured at <em>every</em> layer (removed from the schema the model sees in F2, and re-enforced by
 * the guard in F5 and the executor in F6). Per-stage timings are recorded for the response.
 *
 * <p><b>No secrets leak.</b> Nothing here logs or echoes the connection's credentials; failures are
 * raised as the pipeline's own typed, sanitized exceptions and a clarification short-circuit returns
 * a question rather than touching the database.
 *
 * <p><b>Audit &amp; observability (F10).</b> Every request — answered, clarified, or failed — produces
 * exactly one credential-free {@link AskAuditRecord} (assembled as the pipeline runs and emitted by
 * {@link AskAuditLogger}) tagged with the caller's {@code requestId}, bumps the {@link AskMetrics}
 * counters, and reports the LLM token usage gathered by the {@link LlmUsageRecorder}.
 */
@Service
public class AskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AskOrchestrator.class);

    private final DynamicConnectionFactory connectionFactory;
    private final DatasourceRegistry registry;
    private final SchemaIntrospector introspector;
    private final SchemaCacheService schemaCache;
    private final SqlGenerationService sqlGenerationService;
    private final SqlSafetyGuard safetyGuard;
    private final QueryExecutionService executionService;
    private final ResultRedactor resultRedactor;
    private final AnswerSynthesisService answerSynthesisService;
    private final AskEngineProperties properties;
    private final AskAuditLogger auditLogger;
    private final AskMetrics metrics;
    private final LlmUsageRecorder usageRecorder;

    public AskOrchestrator(DynamicConnectionFactory connectionFactory,
                           DatasourceRegistry registry,
                           SchemaIntrospector introspector,
                           SchemaCacheService schemaCache,
                           SqlGenerationService sqlGenerationService,
                           SqlSafetyGuard safetyGuard,
                           QueryExecutionService executionService,
                           ResultRedactor resultRedactor,
                           AnswerSynthesisService answerSynthesisService,
                           AskEngineProperties properties,
                           AskAuditLogger auditLogger,
                           AskMetrics metrics,
                           LlmUsageRecorder usageRecorder) {
        this.connectionFactory = connectionFactory;
        this.registry = registry;
        this.introspector = introspector;
        this.schemaCache = schemaCache;
        this.sqlGenerationService = sqlGenerationService;
        this.safetyGuard = safetyGuard;
        this.executionService = executionService;
        this.resultRedactor = resultRedactor;
        this.answerSynthesisService = answerSynthesisService;
        this.properties = properties;
        this.auditLogger = auditLogger;
        this.metrics = metrics;
        this.usageRecorder = usageRecorder;
    }

    /**
     * Run the full pipeline for one request under a freshly minted correlation id. Convenience
     * overload for programmatic callers; the REST layer mints the id and uses
     * {@link #ask(AskRequest, String)} so it can echo the same id on errors.
     */
    public AskResponse ask(AskRequest request)
            throws SQLException, LlmException, UnsafeSqlException, QueryExecutionException {
        return ask(request, UUID.randomUUID().toString());
    }

    /**
     * Run the full pipeline for one request and return either an answered or a clarification
     * {@link AskResponse}, recording exactly one {@link AskAuditRecord} (tagged {@code requestId}) and
     * bumping the {@link AskMetrics} counters for every outcome — answer, clarification, or failure.
     *
     * @param requestId the caller-supplied correlation id, echoed on the response and the audit line
     * @throws IllegalArgumentException if the request is malformed (missing connection/question) or
     *                                  the connection details fail validation (mapped to {@code 400})
     * @throws LlmException             if a model call fails (mapped to {@code 502}/{@code 504})
     * @throws UnsafeSqlException       if the drafted SQL fails the safety guard (mapped to {@code 422})
     * @throws QueryExecutionException  if execution fails or times out (mapped to {@code 502}/{@code 504})
     * @throws SQLException             if the target connection cannot be opened (mapped to {@code 502}/{@code 504})
     */
    public AskResponse ask(AskRequest request, String requestId)
            throws SQLException, LlmException, UnsafeSqlException, QueryExecutionException {
        metrics.incrementRequests();
        usageRecorder.start();

        AskAuditRecord.Builder audit = AskAuditRecord.builder()
                .requestId(requestId)
                .timestamp(Instant.now());
        Map<String, Long> timings = new LinkedHashMap<>();
        long startNanos = System.nanoTime();

        List<Connection> openConnections = new ArrayList<>();
        try {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            String question = request.getQuestion();
            if (question == null || question.trim().isEmpty()) {
                throw new IllegalArgumentException("question is required");
            }
            question = question.trim();

            // Resolve which database(s) to answer against: explicit connection(s), else the registry.
            List<ConnectionRequest> targets = resolveTargets(request);

            if (targets.size() == 1) {
                return askSingle(request, requestId, targets.get(0), question,
                        audit, timings, startNanos, openConnections);
            }
            return askFederated(request, requestId, targets, question,
                    audit, timings, startNanos, openConnections);
        } catch (Exception e) {
            // One audit line and the right counters for the failure, then rethrow the typed exception
            // unchanged so the controller maps it to a sanitized HTTP status. The reason recorded here
            // is a sanitized category, never the raw driver/credential text.
            timings.putIfAbsent("totalMillis", millisSince(startNanos));
            classifyFailure(e);
            metrics.incrementErrors();
            recordAudit(audit, AskAuditRecord.Outcome.ERROR, sanitizedReason(e), timings);
            throw e;
        } finally {
            usageRecorder.clear();
            for (Connection conn : openConnections) {
                closeQuietly(conn);
            }
        }
    }

    /**
     * The single-database pipeline (F1–F7): resolve the schema (from the hourly cache if present, else
     * a live introspection), draft one query, validate, execute, redact, and synthesize. The execution
     * connection is opened <b>lazily</b> — only when live introspection is needed or the query is about
     * to run — so a cached schema + a clarification needs no target connection at all. Used whenever
     * exactly one database is resolved (an ad-hoc {@code connection} or a single registry database).
     */
    private AskResponse askSingle(AskRequest request, String requestId, ConnectionRequest connection,
                                  String question, AskAuditRecord.Builder audit,
                                  Map<String, Long> timings, long startNanos,
                                  List<Connection> openConnections)
            throws SQLException, LlmException, UnsafeSqlException, QueryExecutionException {
        audit.question(question)
                .databaseHost(AskAuditLogger.targetHost(connection.getJdbcUrl()));

        SkipList skipList = effectiveSkipList(request, connection);
        audit.skippedTables(new ArrayList<>(skipList.skippedTables()))
                .skippedColumns(new ArrayList<>(skipList.skippedColumns()))
                .maskedColumns(new ArrayList<>(skipList.maskedColumns()));
        ExecutionComponents components = componentsFor(request.getMaxRows());
        String model = blankToNull(request.getModel());
        Map<String, Connection> open = new LinkedHashMap<>();
        timings.put("connectMillis", 0L);
        timings.put("introspectMillis", 0L);

        // (F2) Schema from the cache (filtered for any per-request skips), or a live introspection.
        DatabaseSchema schema = resolveSchema(connection, skipList, open, openConnections, timings);
        audit.databaseProduct(schema.getProductName());

        // (F4) Draft a single read-only query from the question + skip-filtered schema.
        long t = System.nanoTime();
        SqlGenerationResult generation = sqlGenerationService.generate(question, schema, model);
        timings.put("generateMillis", millisSince(t));
        audit.tablesUsed(generation.getTablesUsed());

        if (generation.isClarificationNeeded()) {
            timings.put("totalMillis", millisSince(startNanos));
            log.debug("Ask returned a clarification after generation ({} table(s) visible)",
                    schema.getTables().size());
            metrics.incrementClarifications();
            recordAudit(audit, AskAuditRecord.Outcome.CLARIFICATION, "clarification", timings);
            return AskResponse.clarification(requestId, generation.getClarificationQuestion(),
                    generation.getTablesUsed(), generation.getMissingData(), timings);
        }

        // The query is about to run — ensure a live connection (may already be open from introspection).
        Connection conn = openConnection(connection, open, openConnections, timings);

        // (F5) The trust boundary: validate read-only + skip-list and bound the row cap.
        t = System.nanoTime();
        String safeSql = components.guard.validate(generation.getSql(), skipList, schema);
        timings.put("validateMillis", millisSince(t));
        audit.generatedSql(safeSql);

        // (F6) Execute exactly the validated SQL, read-only and bounded.
        t = System.nanoTime();
        QueryResult execution = components.executor.execute(conn, safeSql, skipList, schema);
        timings.put("executeMillis", millisSince(t));

        // (F9) Redact the rows before anything downstream sees them: mask masked-column values and
        // drop any skip-listed column that slipped through (e.g. via a database-expanded SELECT *).
        t = System.nanoTime();
        QueryResult redacted = resultRedactor.redact(execution, skipList);
        timings.put("redactMillis", millisSince(t));
        audit.rowCount(redacted.getRowCount()).truncated(redacted.isTruncated());

        // (F7) Synthesize the final answer over the REDACTED rows, applying any statistics in Java
        // (and, for formulas not in the code catalog, via the logged LLM-compute fallback).
        t = System.nanoTime();
        AskAnswer answer = answerSynthesisService.answer(question, generation, redacted, model, requestId);
        timings.put("synthesizeMillis", millisSince(t));

        timings.put("totalMillis", millisSince(startNanos));
        log.debug("Ask answered: {} row(s), {} formula(s), truncated={}, total={}ms",
                redacted.getRowCount(), answer.getFormulasApplied().size(),
                redacted.isTruncated(), timings.get("totalMillis"));
        metrics.incrementAnswers();
        recordAudit(audit, AskAuditRecord.Outcome.ANSWERED, null, timings);
        return AskResponse.answered(requestId, answer, safeSql, generation.getTablesUsed(),
                redacted.isTruncated(), redacted.getRowCount(), timings);
    }

    /**
     * The federated (multi-database) pipeline: resolve each target's schema (cache-first, live
     * fallback), ask the model for one read-only sub-query per database (it cannot JOIN across them),
     * then validate→execute→redact each against its own connection and collate the labeled results into
     * one answer (F7). Execution connections are opened <b>lazily</b> — only for the databases a
     * sub-query actually targets — so unused databases are never connected to. Per-stage timings are
     * summed across databases; the planning and synthesis stages are each a single LLM round.
     */
    private AskResponse askFederated(AskRequest request, String requestId,
                                     List<ConnectionRequest> targets, String question,
                                     AskAuditRecord.Builder audit, Map<String, Long> timings,
                                     long startNanos, List<Connection> openConnections)
            throws SQLException, LlmException, UnsafeSqlException, QueryExecutionException {
        String model = blankToNull(request.getModel());
        ExecutionComponents components = componentsFor(request.getMaxRows());

        audit.question(question);
        List<String> hosts = new ArrayList<>();
        for (ConnectionRequest target : targets) {
            String host = AskAuditLogger.targetHost(target.getJdbcUrl());
            if (host != null && !host.isEmpty()) {
                hosts.add(host);
            }
        }
        audit.databaseHost(hosts.isEmpty() ? null : String.join(",", hosts));

        // (F2) Resolve each database's schema — from the hourly cache when present, else a live
        // introspection (which opens, and keeps, that database's connection for reuse at execution).
        Map<String, Connection> open = new LinkedHashMap<>();
        Map<String, DbContext> contexts = new LinkedHashMap<>();
        timings.put("connectMillis", 0L);
        timings.put("introspectMillis", 0L);
        List<NamedSchema> schemas = new ArrayList<>();
        List<String> products = new ArrayList<>();
        for (ConnectionRequest target : targets) {
            SkipList skipList = effectiveSkipList(request, target);
            DatabaseSchema schema = resolveSchema(target, skipList, open, openConnections, timings);
            products.add(schema.getProductName());
            schemas.add(new NamedSchema(target.getName(), schema));
            contexts.put(target.getName().toLowerCase(Locale.ROOT),
                    new DbContext(target.getName(), target, skipList, schema));
        }
        audit.databaseProduct(String.join(",", products));

        // (F4) One model round drafts a read-only sub-query per database it needs.
        long t = System.nanoTime();
        FederatedSqlPlan plan = sqlGenerationService.generateFederated(question, schemas, model);
        timings.put("generateMillis", millisSince(t));
        audit.tablesUsed(plan.allTablesUsed());

        if (plan.isClarificationNeeded()) {
            timings.put("totalMillis", millisSince(startNanos));
            log.debug("Federated Ask returned a clarification ({} database(s) visible)", schemas.size());
            metrics.incrementClarifications();
            recordAudit(audit, AskAuditRecord.Outcome.CLARIFICATION, "clarification", timings);
            return AskResponse.clarification(requestId,
                    plan.getClarificationQuestion(), plan.allTablesUsed(), plan.getMissingData(), timings);
        }

        // (F5/F6/F9) Validate, execute, and redact each sub-query against its own database, opening the
        // connection lazily for just the databases that a sub-query targets.
        long validateTotal = 0L;
        long executeTotal = 0L;
        long redactTotal = 0L;
        int totalRows = 0;
        boolean anyTruncated = false;
        List<AnswerSynthesisService.LabeledResult> labeled = new ArrayList<>();
        List<SubQueryInfo> subQueries = new ArrayList<>();
        List<String> allTables = new ArrayList<>();
        List<String> sqls = new ArrayList<>();
        for (SubQuery sub : plan.getQueries()) {
            DbContext ctx = contexts.get(sub.getDatabase().toLowerCase(Locale.ROOT));
            if (ctx == null) {
                // enforceFederated guarantees the database matches; defensive only.
                continue;
            }
            Connection conn = openConnection(ctx.connection, open, openConnections, timings);

            long tv = System.nanoTime();
            String safeSql = components.guard.validate(sub.getSql(), ctx.skipList, ctx.schema);
            validateTotal += millisSince(tv);

            long te = System.nanoTime();
            QueryResult execution = components.executor.execute(conn, safeSql, ctx.skipList, ctx.schema);
            executeTotal += millisSince(te);

            long tr = System.nanoTime();
            QueryResult redacted = resultRedactor.redact(execution, ctx.skipList);
            redactTotal += millisSince(tr);

            labeled.add(new AnswerSynthesisService.LabeledResult(ctx.name, redacted));
            subQueries.add(new SubQueryInfo(ctx.name, safeSql, sub.getTablesUsed(),
                    redacted.getRowCount(), redacted.isTruncated()));
            for (String table : sub.getTablesUsed()) {
                allTables.add(ctx.name + "." + table);
            }
            sqls.add("-- " + ctx.name + "\n" + safeSql);
            totalRows += redacted.getRowCount();
            anyTruncated = anyTruncated || redacted.isTruncated();
        }
        timings.put("validateMillis", validateTotal);
        timings.put("executeMillis", executeTotal);
        timings.put("redactMillis", redactTotal);
        audit.generatedSql(String.join("\n", sqls));
        audit.rowCount(totalRows).truncated(anyTruncated);

        // (F7) Collate the labeled result sets into one answer.
        long ts = System.nanoTime();
        AskAnswer answer = answerSynthesisService.answerFederated(question, plan.getAssumptions(),
                labeled, model, requestId);
        timings.put("synthesizeMillis", millisSince(ts));

        timings.put("totalMillis", millisSince(startNanos));
        log.debug("Federated Ask answered across {} database(s): {} total row(s), {} formula(s), total={}ms",
                targets.size(), totalRows, answer.getFormulasApplied().size(), timings.get("totalMillis"));
        metrics.incrementAnswers();
        recordAudit(audit, AskAuditRecord.Outcome.ANSWERED, null, timings);
        return AskResponse.answeredFederated(requestId, answer, subQueries, allTables,
                anyTruncated, totalRows, timings);
    }

    /**
     * Resolve the schema for one database: prefer the hourly cache (filtering it for any per-request
     * skips), and on a miss introspect live — opening (and keeping, for execution reuse) that database's
     * connection. Accumulates connect/introspect timings.
     */
    private DatabaseSchema resolveSchema(ConnectionRequest connection, SkipList skipList,
                                         Map<String, Connection> open, List<Connection> openConnections,
                                         Map<String, Long> timings) throws SQLException {
        if (properties.getSchemaCache().isEnabled()) {
            Optional<DatabaseSchema> cached = schemaCache.get(connection.getName());
            if (cached.isPresent()) {
                return SchemaFilter.apply(cached.get(), skipList);
            }
        }
        Connection conn = openConnection(connection, open, openConnections, timings);
        long t = System.nanoTime();
        DatabaseSchema schema = introspector.introspect(conn, skipList);
        addMillis(timings, "introspectMillis", millisSince(t));
        return schema;
    }

    /** Open a database's connection once and reuse it; accumulates connect timing. */
    private Connection openConnection(ConnectionRequest connection, Map<String, Connection> open,
                                      List<Connection> openConnections, Map<String, Long> timings)
            throws SQLException {
        String key = connection.getName();
        Connection existing = open.get(key);
        if (existing != null) {
            return existing;
        }
        long t = System.nanoTime();
        Connection conn = connectionFactory.open(connection);
        addMillis(timings, "connectMillis", millisSince(t));
        open.put(key, conn);
        openConnections.add(conn);
        return conn;
    }

    private static void addMillis(Map<String, Long> timings, String key, long add) {
        timings.merge(key, add, Long::sum);
    }

    /**
     * Resolve the target databases in precedence order: an explicit {@code connections} list, else a
     * single {@code connection}, else the server-side registry ({@code databases} subset, or all).
     * Each target is given a unique non-blank name and the count is capped at {@code maxDatabases}.
     *
     * @throws IllegalArgumentException if nothing is resolved, a named registry database is unknown, or
     *                                  too many databases are requested
     */
    private List<ConnectionRequest> resolveTargets(AskRequest request) {
        List<ConnectionRequest> targets = new ArrayList<>();
        if (!request.getConnections().isEmpty()) {
            targets.addAll(request.getConnections());
        } else if (request.getConnection() != null) {
            targets.add(request.getConnection());
        } else {
            List<String> names = request.getDatabases();
            if (names == null || names.isEmpty()) {
                targets.addAll(registry.all());
                if (targets.isEmpty()) {
                    throw new IllegalArgumentException(
                            "no target database: none supplied in the request and the registry is empty");
                }
            } else {
                for (String name : names) {
                    ConnectionRequest c = registry.get(name);
                    if (c == null) {
                        throw new IllegalArgumentException("unknown database '" + name + "'");
                    }
                    targets.add(c);
                }
            }
        }
        assignNames(targets);
        int max = properties.getMaxDatabases();
        if (targets.size() > max) {
            throw new IllegalArgumentException(
                    "too many target databases: " + targets.size() + " exceeds the configured maximum " + max);
        }
        return targets;
    }

    /** Give every target a unique, non-blank name (used as the federated database label). */
    private static void assignNames(List<ConnectionRequest> targets) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            ConnectionRequest c = targets.get(i);
            String name = (c.getName() == null || c.getName().trim().isEmpty())
                    ? "db" + (i + 1) : c.getName().trim();
            String base = name;
            int n = 2;
            while (!used.add(name.toLowerCase(Locale.ROOT))) {
                name = base + "_" + n++;
            }
            c.setName(name);
        }
    }

    /** Stamp the token usage and timings onto the record and hand it to the audit logger. */
    private void recordAudit(AskAuditRecord.Builder audit, AskAuditRecord.Outcome outcome,
                             String reason, Map<String, Long> timings) {
        LlmUsageRecorder.Usage usage = usageRecorder.snapshot();
        audit.outcome(outcome)
                .reason(reason)
                .timingMillis(timings)
                .llmUsage(usage.getCalls(), usage.getInputTokens(), usage.getOutputTokens());
        auditLogger.record(audit.build());
    }

    /** Bump the specific failure counter (where the cause is known) for an error outcome. */
    private void classifyFailure(Exception e) {
        if (e instanceof UnsafeSqlException) {
            metrics.incrementUnsafeSqlRejections();
        } else if (e instanceof QueryExecutionException) {
            QueryExecutionException.Kind kind = ((QueryExecutionException) e).getKind();
            if (kind == QueryExecutionException.Kind.TIMEOUT) {
                metrics.incrementExecutionTimeouts();
            } else if (kind == QueryExecutionException.Kind.UNSAFE_SQL) {
                metrics.incrementUnsafeSqlRejections();
            }
        } else if (e instanceof LlmException) {
            metrics.incrementLlmFailures();
        }
    }

    /** A short, sanitized failure category for the audit line — never raw driver/credential text. */
    private static String sanitizedReason(Exception e) {
        if (e instanceof UnsafeSqlException) {
            return "unsafe_sql:" + ((UnsafeSqlException) e).getReason();
        }
        if (e instanceof QueryExecutionException) {
            return "execution:" + ((QueryExecutionException) e).getKind();
        }
        if (e instanceof LlmException) {
            return "llm:" + ((LlmException) e).getKind();
        }
        if (e instanceof SQLException) {
            return "connect_failed";
        }
        if (e instanceof IllegalArgumentException) {
            return "bad_request:" + e.getMessage();
        }
        return "internal_error";
    }

    /**
     * The effective sensitive-data policy (F9): the union of the server-side
     * {@code default-skip-tables}/{@code default-skip-columns}, the connection's and request's skip
     * lists, the server-side {@code masked-columns}, and the configurable auto-skip name patterns.
     * Re-enforced downstream at introspection, validation, execution, and output redaction.
     */
    private SkipList effectiveSkipList(AskRequest request, ConnectionRequest connection) {
        return SkipListFactory.effective(request.getSkipTables(), request.getSkipColumns(),
                connection, properties);
    }

    /**
     * Choose the guard + executor to use, honouring an optional per-request {@code maxRows} override.
     * The override is clamped to the configured ceiling (it can only lower the cap, never raise it).
     * When it matches the ceiling — or is absent — the shared singletons are used; otherwise a pair
     * bound to the lowered cap is built for this request so F5 and F6 agree on the limit.
     */
    private ExecutionComponents componentsFor(Integer maxRowsOverride) {
        int ceiling = properties.getMaxRows();
        if (maxRowsOverride == null) {
            return new ExecutionComponents(safetyGuard, executionService);
        }
        int clamped = Math.min(Math.max(1, maxRowsOverride), ceiling);
        if (clamped == ceiling) {
            return new ExecutionComponents(safetyGuard, executionService);
        }
        AskEngineProperties effective = copyWithMaxRows(clamped);
        SqlSafetyGuard guard = new SqlSafetyGuard(effective);
        return new ExecutionComponents(guard, new QueryExecutionService(guard, effective));
    }

    private AskEngineProperties copyWithMaxRows(int maxRows) {
        AskEngineProperties copy = new AskEngineProperties();
        copy.setEnabled(properties.isEnabled());
        copy.setMaxRows(maxRows);
        copy.setQueryTimeoutSeconds(properties.getQueryTimeoutSeconds());
        copy.setConnectionTimeoutSeconds(properties.getConnectionTimeoutSeconds());
        copy.setDefaultSkipTables(properties.getDefaultSkipTables());
        copy.setDefaultSkipColumns(properties.getDefaultSkipColumns());
        copy.setMaskedColumns(properties.getMaskedColumns());
        copy.setAutoSkip(properties.getAutoSkip());
        copy.setAudit(properties.getAudit());
        copy.setLlmProvider(properties.getLlmProvider());
        copy.setSecretsPath(properties.getSecretsPath());
        copy.setMaxDatabases(properties.getMaxDatabases());
        copy.setSchemaCache(properties.getSchemaCache());
        copy.setFormulaGap(properties.getFormulaGap());
        return copy;
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            // Best-effort cleanup of a short-lived connection. Never log the connection details.
            log.debug("failed to close target connection cleanly: {}", e.getSQLState());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** The guard + executor pair to run a request with — either the shared singletons or a capped pair. */
    private static final class ExecutionComponents {
        private final SqlSafetyGuard guard;
        private final QueryExecutionService executor;

        ExecutionComponents(SqlSafetyGuard guard, QueryExecutionService executor) {
            this.guard = guard;
            this.executor = executor;
        }
    }

    /** Per-database state held across the federated pipeline: its connection details, skip-list, schema. */
    private static final class DbContext {
        private final String name;
        private final ConnectionRequest connection;
        private final SkipList skipList;
        private final DatabaseSchema schema;

        DbContext(String name, ConnectionRequest connection, SkipList skipList, DatabaseSchema schema) {
            this.name = name;
            this.connection = connection;
            this.skipList = skipList;
            this.schema = schema;
        }
    }
}
