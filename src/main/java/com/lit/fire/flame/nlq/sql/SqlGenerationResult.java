package com.lit.fire.flame.nlq.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of one NL&rarr;SQL generation attempt (F4).
 *
 * <p>It is one of two shapes:
 * <ul>
 *   <li><b>A drafted query</b> ({@link #ofSql}) — {@link #getSql()} is a single read-only
 *       {@code SELECT}/{@code WITH} statement, with the {@link #getTablesUsed() tables it reads} and
 *       any {@link #getAssumptions() assumptions} the model made. <b>Not yet validated</b>: the SQL
 *       is still untrusted here and must pass F5 validation before execution.</li>
 *   <li><b>A request for clarification</b> ({@link #needingClarification}) — {@link #getSql()} is
 *       {@code null}, {@link #isClarificationNeeded()} is {@code true}, and
 *       {@link #getClarificationQuestion()} carries a specific question to put back to the caller
 *       instead of guessing.</li>
 * </ul>
 *
 * <p>Immutable; list accessors return unmodifiable copies and are never {@code null}.
 */
public final class SqlGenerationResult {

    private final String sql;
    private final List<String> tablesUsed;
    private final List<String> assumptions;
    private final Double confidence;
    private final boolean clarificationNeeded;
    private final String clarificationQuestion;
    private final List<String> missingData;

    private SqlGenerationResult(String sql, List<String> tablesUsed, List<String> assumptions,
                                Double confidence, boolean clarificationNeeded,
                                String clarificationQuestion, List<String> missingData) {
        this.sql = sql;
        this.tablesUsed = copy(tablesUsed);
        this.assumptions = copy(assumptions);
        this.confidence = confidence;
        this.clarificationNeeded = clarificationNeeded;
        this.clarificationQuestion = clarificationQuestion;
        this.missingData = copy(missingData);
    }

    private static List<String> copy(List<String> in) {
        return (in == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(in));
    }

    /** A drafted (still-unvalidated) read-only query and its supporting metadata. */
    public static SqlGenerationResult ofSql(String sql, List<String> tablesUsed,
                                            List<String> assumptions, Double confidence) {
        return new SqlGenerationResult(sql, tablesUsed, assumptions, confidence, false, null, null);
    }

    /**
     * A clarification request: no SQL was produced because the question could not be answered from
     * the schema (or referenced something not in it). {@code missingData} names the specific data the
     * question needs but the schema does not provide (may be empty).
     */
    public static SqlGenerationResult needingClarification(String clarificationQuestion,
                                                           List<String> tablesUsed,
                                                           List<String> assumptions,
                                                           Double confidence,
                                                           List<String> missingData) {
        return new SqlGenerationResult(null, tablesUsed, assumptions, confidence, true,
                clarificationQuestion, missingData);
    }

    /** The drafted query, or {@code null} when {@link #isClarificationNeeded()} is {@code true}. */
    public String getSql() {
        return sql;
    }

    /** Tables the drafted query reads, exactly as named in the schema; never {@code null}. */
    public List<String> getTablesUsed() {
        return tablesUsed;
    }

    /** Interpretations the model made (e.g. which column means "signed up"); never {@code null}. */
    public List<String> getAssumptions() {
        return assumptions;
    }

    /** Model self-reported confidence in {@code [0,1]}, or {@code null} if not reported. */
    public Double getConfidence() {
        return confidence;
    }

    /** Whether the engine should ask the caller a question instead of running a query. */
    public boolean isClarificationNeeded() {
        return clarificationNeeded;
    }

    /** The question to ask when {@link #isClarificationNeeded()} is {@code true}; {@code null} otherwise. */
    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    /** The specific data the question needs but the schema does not provide; never {@code null}. */
    public List<String> getMissingData() {
        return missingData;
    }
}
