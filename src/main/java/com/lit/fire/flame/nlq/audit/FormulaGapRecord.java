package com.lit.fire.flame.nlq.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One record of the mathematician layer having to ask the LLM to compute a formula that is <b>not</b>
 * in the deterministic code catalog (F14). Each record is a candidate to implement in code in a later
 * release, so the engine stops paying the LLM to compute it.
 *
 * <p>Carries structure only — the question, the formula the model named, the expression/explanation it
 * used, the columns involved, and the single computed figure. It holds <b>no credentials and no raw row
 * values</b> beyond the one computed number.
 */
public final class FormulaGapRecord {

    private final String requestId;
    private final String question;
    private final String formulaName;
    private final String formulaDescription;
    private final String expression;
    private final List<String> columns;
    private final Double computedValue;

    public FormulaGapRecord(String requestId, String question, String formulaName,
                            String formulaDescription, String expression, List<String> columns,
                            Double computedValue) {
        this.requestId = requestId;
        this.question = question;
        this.formulaName = formulaName;
        this.formulaDescription = formulaDescription;
        this.expression = expression;
        this.columns = (columns == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(columns));
        this.computedValue = computedValue;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getQuestion() {
        return question;
    }

    /** The formula key the model named (e.g. {@code gini_coefficient}) — the gap to catalog. */
    public String getFormulaName() {
        return formulaName;
    }

    /** The model's description of the formula, if any. */
    public String getFormulaDescription() {
        return formulaDescription;
    }

    /** The expression/method the LLM used to compute the value, for later code implementation. */
    public String getExpression() {
        return expression;
    }

    /** The result columns the formula consumed; never {@code null}. */
    public List<String> getColumns() {
        return columns;
    }

    /** The single figure the LLM computed (the only number this record carries). */
    public Double getComputedValue() {
        return computedValue;
    }
}
