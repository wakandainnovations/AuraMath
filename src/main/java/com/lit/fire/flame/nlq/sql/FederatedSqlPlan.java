package com.lit.fire.flame.nlq.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of one federated NL&rarr;SQL generation attempt (the multi-database analogue of
 * {@link SqlGenerationResult}). It is one of two shapes:
 *
 * <ul>
 *   <li><b>A set of sub-queries</b> ({@link #ofQueries}) — one {@link SubQuery} per database the
 *       question needs, each a single read-only draft for that database. <b>Not yet validated.</b></li>
 *   <li><b>A request for clarification</b> ({@link #needingClarification}) — {@link #getQueries()} is
 *       empty, {@link #isClarificationNeeded()} is {@code true}, and {@link #getClarificationQuestion()}
 *       carries a specific question to put back to the caller instead of guessing.</li>
 * </ul>
 *
 * <p>Immutable; list accessors return unmodifiable copies and are never {@code null}.
 */
public final class FederatedSqlPlan {

    private final List<SubQuery> queries;
    private final List<String> assumptions;
    private final Double confidence;
    private final boolean clarificationNeeded;
    private final String clarificationQuestion;
    private final List<String> missingData;

    private FederatedSqlPlan(List<SubQuery> queries, List<String> assumptions, Double confidence,
                            boolean clarificationNeeded, String clarificationQuestion,
                            List<String> missingData) {
        this.queries = (queries == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(queries));
        this.assumptions = copy(assumptions);
        this.confidence = confidence;
        this.clarificationNeeded = clarificationNeeded;
        this.clarificationQuestion = clarificationQuestion;
        this.missingData = copy(missingData);
    }

    /** A drafted set of per-database sub-queries plus the interpretations the model made. */
    public static FederatedSqlPlan ofQueries(List<SubQuery> queries, List<String> assumptions,
                                            Double confidence) {
        return new FederatedSqlPlan(queries, assumptions, confidence, false, null, null);
    }

    /**
     * A clarification request: no sub-queries were produced. {@code missingData} names the specific
     * data the question needs but the databases do not provide (may be empty).
     */
    public static FederatedSqlPlan needingClarification(String clarificationQuestion,
                                                       List<String> assumptions, Double confidence,
                                                       List<String> missingData) {
        return new FederatedSqlPlan(Collections.emptyList(), assumptions, confidence, true,
                clarificationQuestion, missingData);
    }

    /** The per-database sub-queries to validate and run; empty when {@link #isClarificationNeeded()}. */
    public List<SubQuery> getQueries() {
        return queries;
    }

    /** Interpretations the model made across the databases; never {@code null}. */
    public List<String> getAssumptions() {
        return assumptions;
    }

    /** Model self-reported confidence in {@code [0,1]}, or {@code null} if not reported. */
    public Double getConfidence() {
        return confidence;
    }

    /** Whether the engine should ask the caller a question instead of running the queries. */
    public boolean isClarificationNeeded() {
        return clarificationNeeded;
    }

    /** The question to ask when {@link #isClarificationNeeded()}; {@code null} otherwise. */
    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    /** The specific data the question needs but the databases do not provide; never {@code null}. */
    public List<String> getMissingData() {
        return missingData;
    }

    /** Union of every table read across all sub-queries, prefixed {@code <database>.<table>}. */
    public List<String> allTablesUsed() {
        List<String> out = new ArrayList<>();
        for (SubQuery q : queries) {
            for (String t : q.getTablesUsed()) {
                out.add(q.getDatabase() + "." + t);
            }
        }
        return out;
    }

    private static List<String> copy(List<String> in) {
        return (in == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(in));
    }
}
