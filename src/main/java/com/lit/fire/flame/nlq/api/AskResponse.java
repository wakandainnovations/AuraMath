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
 * Build one with {@link #answered} or {@link #clarification}; all collection accessors are
 * unmodifiable and never {@code null}.
 */
public final class AskResponse {

    private final boolean clarificationNeeded;
    private final String clarificationQuestion;

    private final String answer;
    private final String sql;
    private final List<String> tablesUsed;
    private final List<AppliedFormula> formulasApplied;
    private final Map<String, Double> computedValues;
    private final List<String> assumptions;
    private final List<Map<String, Object>> rowsPreview;
    private final int rowCount;
    private final boolean truncated;

    /** Per-stage wall-clock timings in milliseconds (connect, introspect, generate, …, total). */
    private final Map<String, Long> timingMillis;

    private AskResponse(boolean clarificationNeeded, String clarificationQuestion, String answer,
                        String sql, List<String> tablesUsed, List<AppliedFormula> formulasApplied,
                        Map<String, Double> computedValues, List<String> assumptions,
                        List<Map<String, Object>> rowsPreview, int rowCount, boolean truncated,
                        Map<String, Long> timingMillis) {
        this.clarificationNeeded = clarificationNeeded;
        this.clarificationQuestion = clarificationQuestion;
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
        this.timingMillis = (timingMillis == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(timingMillis));
    }

    /**
     * Build an answered response by flattening the F7 {@link AskAnswer} and attaching the
     * <b>executed</b> SQL (the validated, row-capped query F5/F6 actually ran — not the raw draft),
     * the tables the query read, the truncation flag, the row count, and the per-stage timings.
     */
    public static AskResponse answered(AskAnswer answer, String executedSql, List<String> tablesUsed,
                                       boolean truncated, int rowCount, Map<String, Long> timingMillis) {
        return new AskResponse(false, null,
                answer.getAnswer(), executedSql, tablesUsed, answer.getFormulasApplied(),
                answer.getComputedValues(), answer.getAssumptions(), answer.getRowsPreview(),
                rowCount, truncated, timingMillis);
    }

    /**
     * Build a clarification response: no SQL was generated or executed, so only the question to put
     * back to the caller (and the tables the model reported considering) are carried.
     */
    public static AskResponse clarification(String clarificationQuestion, List<String> tablesUsed,
                                            Map<String, Long> timingMillis) {
        return new AskResponse(true, clarificationQuestion,
                null, null, tablesUsed, null, null, null, null, 0, false, timingMillis);
    }

    /** Whether the engine is asking the caller to refine the question instead of answering. */
    public boolean isClarificationNeeded() {
        return clarificationNeeded;
    }

    /** The question to put back to the caller when {@link #isClarificationNeeded()}; {@code null} otherwise. */
    public String getClarificationQuestion() {
        return clarificationQuestion;
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
