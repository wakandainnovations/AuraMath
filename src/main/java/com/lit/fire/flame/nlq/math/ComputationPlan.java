package com.lit.fire.flame.nlq.math;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model-produced computation plan (step 2a of F7): <b>which</b> formulas apply and over which
 * result columns — never the final numbers. The plan is evaluated deterministically in Java by
 * {@link FormulaEvaluator}; the LLM only decides the recipe.
 *
 * <p>{@link #isLookupOnly()} marks a pure-lookup question (no math): the synthesis service then
 * short-circuits to a factual answer from the rows rather than fabricating statistics. Otherwise the
 * ordered {@link #getOperations() operations} are applied in sequence, so a later {@code expression}
 * operation may reference the result of an earlier one by name.
 *
 * <p>Immutable; accessors return unmodifiable views.
 */
public final class ComputationPlan {

    /**
     * A single named operation in the plan: a catalog {@link #getFormula() formula} applied to one or
     * more result {@link #getColumns() columns}, with optional numeric {@link #getArgs() args} (e.g.
     * {@code percentile}, {@code periods}) and, for the {@code expression} formula, an
     * {@link #getExpression() arithmetic expression} over earlier operations' results.
     */
    public static final class Operation {
        private final String name;
        private final String formula;
        private final List<String> columns;
        private final Map<String, Double> args;
        private final String expression;
        private final String description;

        public Operation(String name, String formula, List<String> columns, Map<String, Double> args,
                         String expression, String description) {
            this.name = name;
            this.formula = formula;
            this.columns = (columns == null)
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(columns));
            this.args = (args == null)
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(args));
            this.expression = expression;
            this.description = description;
        }

        /** Stable name for the operation, used as the key in {@link AskAnswer#getComputedValues()}. */
        public String getName() {
            return name;
        }

        /** The catalog formula key (e.g. {@code mean}, {@code weighted_average}, {@code expression}). */
        public String getFormula() {
            return formula;
        }

        /** Result columns the formula reads, in the order the formula expects; never {@code null}. */
        public List<String> getColumns() {
            return columns;
        }

        /** Numeric parameters (e.g. {@code percentile=95}, {@code periods=4}); never {@code null}. */
        public Map<String, Double> getArgs() {
            return args;
        }

        /** Arithmetic expression for the {@code expression} formula, referencing earlier names; else {@code null}. */
        public String getExpression() {
            return expression;
        }

        /** Free-text note on what this operation means; may be {@code null}. */
        public String getDescription() {
            return description;
        }
    }

    private final boolean lookupOnly;
    private final List<Operation> operations;
    private final List<String> notes;

    public ComputationPlan(boolean lookupOnly, List<Operation> operations, List<String> notes) {
        this.lookupOnly = lookupOnly;
        this.operations = (operations == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(operations));
        this.notes = (notes == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(notes));
    }

    /** {@code true} when the question is a pure lookup (no math) and should be answered from the rows. */
    public boolean isLookupOnly() {
        return lookupOnly;
    }

    /** The operations to evaluate, in order; never {@code null}. */
    public List<Operation> getOperations() {
        return operations;
    }

    /** Model notes about the plan (e.g. assumptions about which column to use); never {@code null}. */
    public List<String> getNotes() {
        return notes;
    }
}
