package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.audit.AskAuditLogger;
import com.lit.fire.flame.nlq.audit.AskAuditRecord;
import com.lit.fire.flame.nlq.audit.AskMetrics;
import com.lit.fire.flame.nlq.audit.LlmUsageRecorder;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.connection.ConnectionRequest;
import com.lit.fire.flame.nlq.connection.DynamicConnectionFactory;
import com.lit.fire.flame.nlq.math.AnswerSynthesisService;
import com.lit.fire.flame.nlq.math.AskAnswer;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.SchemaIntrospector;
import com.lit.fire.flame.nlq.schema.SkipList;
import com.lit.fire.flame.nlq.sql.QueryExecutionException;
import com.lit.fire.flame.nlq.sql.QueryExecutionService;
import com.lit.fire.flame.nlq.sql.QueryResult;
import com.lit.fire.flame.nlq.sql.ResultRedactor;
import com.lit.fire.flame.nlq.sql.SqlGenerationResult;
import com.lit.fire.flame.nlq.sql.SqlGenerationService;
import com.lit.fire.flame.nlq.sql.SqlSafetyGuard;
import com.lit.fire.flame.nlq.sql.UnsafeSqlException;
import com.lit.fire.flame.nlq.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final SchemaIntrospector introspector;
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
                           SchemaIntrospector introspector,
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
        this.introspector = introspector;
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

        Connection conn = null;
        try {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            ConnectionRequest connection = request.getConnection();
            if (connection == null) {
                throw new IllegalArgumentException("connection is required");
            }
            String question = request.getQuestion();
            if (question == null || question.trim().isEmpty()) {
                throw new IllegalArgumentException("question is required");
            }
            audit.question(question.trim())
                    .databaseHost(AskAuditLogger.targetHost(connection.getJdbcUrl()));

            SkipList skipList = effectiveSkipList(request);
            audit.skippedTables(new ArrayList<>(skipList.skippedTables()))
                    .skippedColumns(new ArrayList<>(skipList.skippedColumns()))
                    .maskedColumns(new ArrayList<>(skipList.maskedColumns()));
            ExecutionComponents components = componentsFor(request.getMaxRows());
            String model = blankToNull(request.getModel());

            long t = System.nanoTime();
            conn = connectionFactory.open(connection);
            timings.put("connectMillis", millisSince(t));

            // (F2) Introspect through the effective skip-list, so skipped objects never reach the model.
            t = System.nanoTime();
            DatabaseSchema schema = introspector.introspect(conn, skipList);
            timings.put("introspectMillis", millisSince(t));
            audit.databaseProduct(schema.getProductName());

            // (F4) Draft a single read-only query from the question + skip-filtered schema.
            t = System.nanoTime();
            SqlGenerationResult generation = sqlGenerationService.generate(question, schema, model);
            timings.put("generateMillis", millisSince(t));
            audit.tablesUsed(generation.getTablesUsed());

            if (generation.isClarificationNeeded()) {
                timings.put("totalMillis", millisSince(startNanos));
                log.debug("Ask returned a clarification after generation ({} table(s) visible)",
                        schema.getTables().size());
                metrics.incrementClarifications();
                recordAudit(audit, AskAuditRecord.Outcome.CLARIFICATION, "clarification", timings);
                return AskResponse.clarification(requestId,
                        generation.getClarificationQuestion(), generation.getTablesUsed(), timings);
            }

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

            // (F7) Synthesize the final answer over the REDACTED rows, applying any statistics in Java.
            t = System.nanoTime();
            AskAnswer answer = answerSynthesisService.answer(question, generation, redacted, model);
            timings.put("synthesizeMillis", millisSince(t));

            timings.put("totalMillis", millisSince(startNanos));
            log.debug("Ask answered: {} row(s), {} formula(s), truncated={}, total={}ms",
                    redacted.getRowCount(), answer.getFormulasApplied().size(),
                    redacted.isTruncated(), timings.get("totalMillis"));
            metrics.incrementAnswers();
            recordAudit(audit, AskAuditRecord.Outcome.ANSWERED, null, timings);
            return AskResponse.answered(requestId, answer, safeSql, generation.getTablesUsed(),
                    redacted.isTruncated(), redacted.getRowCount(), timings);
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
            closeQuietly(conn);
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
    private SkipList effectiveSkipList(AskRequest request) {
        ConnectionRequest connection = request.getConnection();
        List<String> tables = new ArrayList<>();
        addAll(tables, connection.getSkipTables());
        addAll(tables, request.getSkipTables());
        List<String> columns = new ArrayList<>();
        addAll(columns, connection.getSkipColumns());
        addAll(columns, request.getSkipColumns());
        AskEngineProperties.AutoSkip autoSkip = properties.getAutoSkip();
        return SkipList.builder()
                .addSkipTables(properties.getDefaultSkipTables())
                .addSkipTables(tables)
                .addSkipColumns(properties.getDefaultSkipColumns())
                .addSkipColumns(columns)
                .addMaskedColumns(properties.getMaskedColumns())
                .autoSkipPatterns(autoSkip.getPatterns(), autoSkip.isEnabled())
                .build();
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

    private static void addAll(List<String> target, Collection<String> source) {
        if (source != null) {
            target.addAll(source);
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
}
