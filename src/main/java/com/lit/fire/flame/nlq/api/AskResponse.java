package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.math.AppliedFormula;
import com.lit.fire.flame.nlq.math.AskAnswer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The response body for {@code POST /api/ask}. It is one of two shapes, distinguished by
 * {@link #isClarificationNeeded()}:
 *
 * <ul>
 *   <li><b>An answer</b> — the synthesized {@link AskAnswer} from F7 flattened out (the NL
 *       {@link #getAnswer() answer}, the {@link #getSql() SQL} that ran, the
 *       {@link #getFormulasApplied() formulas applied} with their Java-computed results, the exact
 *       {@link #getComputedValues() computed values}, the {@link #getAssumptions() assumptions}, and a
 *       {@link #getRowsPreview() rows preview}), plus the {@link #getTablesUsed() tables used},
 *       whether the result was {@link #isTruncated() truncated}, and per-stage
 *       {@link #getTimingMillis() timings}.</li>
 *   <li><b>A clarification</b> — {@link #isClarificationNeeded()} is {@code true} and
 *       {@link #getClarificationQuestion()} carries a specific question to put back to the caller;
 *       no SQL was generated or run, so the answer fields are empty. This is what a question that
 *       cannot be answered from the non-skipped schema (e.g. one targeting a skipped table) yields —
 *       the engine asks rather than leaks or guesses.</li>
 * </ul>
 *
 * <p>It deliberately carries <b>no credentials</b> — the connection's password never appears here.
 * Every response also carries the {@link #getRequestId() requestId} (F10) that the audit log records,
 * so an operator can correlate what the caller saw with the server-side trace. Build one with
 * {@link #answered} or {@link #clarification}; all collection accessors are unmodifiable and never
 * {@code null}.
 */
public final class AskResponse {

    /** Correlation id, echoed from the audit log so the caller and operators can match a request. */
    private final String requestId;

    private final boolean clarificationNeeded;
    private final String clarificationQuestion;

    /**
     * When the engine cannot answer (clarification), the specific data the question needs but the
     * schema(s) do not provide — e.g. "a refund-date column", "a costs table". Empty for an answer.
     */
    private final List<String> missingData;

    private final String answer;
    private final String sql;
    private final List<String> tablesUsed;
    private final List<AppliedFormula> formulasApplied;
    private final Map<String, Double> computedValues;
    private final List<String> assumptions;
    private final List<Map<String, Object>> rowsPreview;
    private final int rowCount;
    private final boolean truncated;

    /**
     * For a federated (multi-database) answer, the per-database queries that ran (database, SQL, tables,
     * row count). Empty for a single-database answer or a clarification — then the top-level
     * {@link #getSql() sql}/{@link #getTablesUsed() tablesUsed} describe the one query, exactly as before.
     */
    private final List<SubQueryInfo> subQueries;

    /** Per-stage wall-clock timings in milliseconds (connect, introspect, generate, …, total). */
    private final Map<String, Long> timingMillis;

    private AskResponse(String requestId, boolean clarificationNeeded, String clarificationQuestion,
                        List<String> missingData, String answer, String sql, List<String> tablesUsed,
                        List<AppliedFormula> formulasApplied, Map<String, Double> computedValues,
                        List<String> assumptions, List<Map<String, Object>> rowsPreview, int rowCount,
                        boolean truncated, List<SubQueryInfo> subQueries, Map<String, Long> timingMillis) {
        this.requestId = requestId;
        this.clarificationNeeded = clarificationNeeded;
        this.clarificationQuestion = clarificationQuestion;
        this.missingData = copyList(missingData);
        this.answer = answer;
        this.sql = sql;
        this.tablesUsed = copyList(tablesUsed);
        this.formulasApplied = copyList(formulasApplied);
        this.computedValues = (computedValues == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(computedValues));
        this.assumptions = copyList(assumptions);
        this.rowsPreview = copyRows(rowsPreview);
        this.rowCount = rowCount;
        this.truncated = truncated;
        this.subQueries = copyList(subQueries);
        this.timingMillis = (timingMillis == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(timingMillis));
    }

    /**
     * Build an answered response by flattening the F7 {@link AskAnswer} and attaching the
     * <b>executed</b> SQL (the validated, row-capped query F5/F6 actually ran — not the raw draft),
     * the tables the query read, the truncation flag, the row count, and the per-stage timings.
     */
    public static AskResponse answered(String requestId, AskAnswer answer, String executedSql,
                                       List<String> tablesUsed, boolean truncated, int rowCount,
                                       Map<String, Long> timingMillis) {
        return new AskResponse(requestId, false, null, null,
                answer.getAnswer(), executedSql, tablesUsed, answer.getFormulasApplied(),
                answer.getComputedValues(), answer.getAssumptions(), answer.getRowsPreview(),
                rowCount, truncated, null, timingMillis);
    }

    /**
     * Build a federated (multi-database) answer. The collated {@link AskAnswer} supplies the NL answer,
     * formulas, computed values, assumptions, and combined rows preview; {@code subQueries} carries the
     * per-database SQL that ran. The top-level {@link #getSql() sql} is {@code null} (there are several);
     * {@code tablesUsed} is the {@code database.table} union and {@code rowCount} the total across DBs.
     */
    public static AskResponse answeredFederated(String requestId, AskAnswer answer,
                                                List<SubQueryInfo> subQueries, List<String> tablesUsed,
                                                boolean truncated, int rowCount,
                                                Map<String, Long> timingMillis) {
        return new AskResponse(requestId, false, null, null,
                answer.getAnswer(), null, tablesUsed, answer.getFormulasApplied(),
                answer.getComputedValues(), answer.getAssumptions(), answer.getRowsPreview(),
                rowCount, truncated, subQueries, timingMillis);
    }

    /**
     * Build a clarification response: no SQL was generated or executed, so only the question to put
     * back to the caller, the tables the model reported considering, and the specific
     * {@code missingData} the question needs but the schema(s) lack are carried.
     */
    public static AskResponse clarification(String requestId, String clarificationQuestion,
                                            List<String> tablesUsed, List<String> missingData,
                                            Map<String, Long> timingMillis) {
        return new AskResponse(requestId, true, clarificationQuestion, missingData,
                null, null, tablesUsed, null, null, null, null, 0, false, null, timingMillis);
    }

    /** The correlation id shared with the audit log; never {@code null}. */
    public String getRequestId() {
        return requestId;
    }

    /** Whether the engine is asking the caller to refine the question instead of answering. */
    public boolean isClarificationNeeded() {
        return clarificationNeeded;
    }

    /** The question to put back to the caller when {@link #isClarificationNeeded()}; {@code null} otherwise. */
    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    /** The specific data the question needs but the schema(s) do not provide; never {@code null}. */
    public List<String> getMissingData() {
        return missingData;
    }

    /** The natural-language answer, or {@code null} for a clarification. */
    public String getAnswer() {
        return answer;
    }

    /** The validated, row-capped read-only SQL that was executed, or {@code null} for a clarification. */
    public String getSql() {
        return sql;
    }

    /** Tables the query read (from generation); never {@code null}. */
    public List<String> getTablesUsed() {
        return tablesUsed;
    }

    /** Formulas the mathematician layer applied, each with its Java-computed result; never {@code null}. */
    public List<AppliedFormula> getFormulasApplied() {
        return formulasApplied;
    }

    /** Every figure Java computed, keyed by operation name; never {@code null}. */
    public Map<String, Double> getComputedValues() {
        return computedValues;
    }

    /** Interpretations from generation plus any evaluation notes; never {@code null}. */
    public List<String> getAssumptions() {
        return assumptions;
    }

    /** A capped preview of the underlying rows; never {@code null}. */
    public List<Map<String, Object>> getRowsPreview() {
        return rowsPreview;
    }

    /** Number of rows the query returned. */
    public int getRowCount() {
        return rowCount;
    }

    /** {@code true} when the result filled the row cap and more rows may exist. */
    public boolean isTruncated() {
        return truncated;
    }

    /** Per-database queries that ran for a federated answer; empty for single-DB answers; never {@code null}. */
    public List<SubQueryInfo> getSubQueries() {
        return subQueries;
    }

    /** Per-stage wall-clock timings in milliseconds; never {@code null}. */
    public Map<String, Long> getTimingMillis() {
        return timingMillis;
    }

    private static <T> List<T> copyList(List<T> in) {
        return (in == null) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(in));
    }

    private static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> copied = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copied.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return Collections.unmodifiableList(copied);
    }
}
