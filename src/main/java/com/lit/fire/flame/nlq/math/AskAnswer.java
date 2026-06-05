package com.lit.fire.flame.nlq.math;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The final, synthesized answer produced by the mathematician layer (F7).
 *
 * <p>It pairs a natural-language {@link #getAnswer() answer} with the deterministic evidence behind
 * it: the {@link #getFormulasApplied() formulas} that were applied, the {@link #getComputedValues()
 * computed values} (every figure Java calculated, keyed by operation name), the {@link #getSql() SQL}
 * that produced the rows, the {@link #getAssumptions() assumptions} carried from generation plus any
 * notes recorded while evaluating, and a small {@link #getRowsPreview() preview} of the underlying
 * rows. The narrative never invents numbers beyond {@code computedValues} and the previewed rows.
 *
 * <p>Immutable; build one with {@link #builder()}. List/map accessors return unmodifiable views and
 * are never {@code null}.
 */
public final class AskAnswer {

    private final String answer;
    private final List<AppliedFormula> formulasApplied;
    private final Map<String, Double> computedValues;
    private final String sql;
    private final List<String> assumptions;
    private final List<Map<String, Object>> rowsPreview;

    private AskAnswer(Builder b) {
        this.answer = b.answer;
        this.formulasApplied = Collections.unmodifiableList(new ArrayList<>(b.formulasApplied));
        this.computedValues = Collections.unmodifiableMap(new LinkedHashMap<>(b.computedValues));
        this.sql = b.sql;
        this.assumptions = Collections.unmodifiableList(new ArrayList<>(b.assumptions));
        List<Map<String, Object>> rows = new ArrayList<>(b.rowsPreview.size());
        for (Map<String, Object> row : b.rowsPreview) {
            rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        this.rowsPreview = Collections.unmodifiableList(rows);
    }

    /** The concise natural-language answer; never {@code null}. */
    public String getAnswer() {
        return answer;
    }

    /** The formulas applied to reach the answer, in plan order; never {@code null}. */
    public List<AppliedFormula> getFormulasApplied() {
        return formulasApplied;
    }

    /** Every value Java computed, keyed by operation name; never {@code null}. */
    public Map<String, Double> getComputedValues() {
        return computedValues;
    }

    /** The read-only SQL whose rows were synthesized, or {@code null} if none was run. */
    public String getSql() {
        return sql;
    }

    /** Interpretations from SQL generation plus any notes from evaluation; never {@code null}. */
    public List<String> getAssumptions() {
        return assumptions;
    }

    /** A capped preview of the underlying rows; never {@code null}. */
    public List<Map<String, Object>> getRowsPreview() {
        return rowsPreview;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link AskAnswer}. */
    public static final class Builder {
        private String answer = "";
        private List<AppliedFormula> formulasApplied = new ArrayList<>();
        private Map<String, Double> computedValues = new LinkedHashMap<>();
        private String sql;
        private List<String> assumptions = new ArrayList<>();
        private List<Map<String, Object>> rowsPreview = new ArrayList<>();

        public Builder answer(String answer) {
            this.answer = (answer == null) ? "" : answer;
            return this;
        }

        public Builder formulasApplied(List<AppliedFormula> formulasApplied) {
            this.formulasApplied = (formulasApplied == null) ? new ArrayList<>() : formulasApplied;
            return this;
        }

        public Builder computedValues(Map<String, Double> computedValues) {
            this.computedValues = (computedValues == null) ? new LinkedHashMap<>() : computedValues;
            return this;
        }

        public Builder sql(String sql) {
            this.sql = sql;
            return this;
        }

        public Builder assumptions(List<String> assumptions) {
            this.assumptions = (assumptions == null) ? new ArrayList<>() : assumptions;
            return this;
        }

        public Builder rowsPreview(List<Map<String, Object>> rowsPreview) {
            this.rowsPreview = (rowsPreview == null) ? new ArrayList<>() : rowsPreview;
            return this;
        }

        public AskAnswer build() {
            return new AskAnswer(this);
        }
    }
}
