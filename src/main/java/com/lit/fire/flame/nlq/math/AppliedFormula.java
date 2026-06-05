package com.lit.fire.flame.nlq.math;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One formula the mathematician layer (F7) actually applied to the result set, with the deterministic
 * value Java computed for it.
 *
 * <p>Carries the operation {@code name} from the computation plan, a human-readable {@code expression}
 * describing what was computed (e.g. {@code mean(order_value)}, {@code percentile(latency_ms, p=95)},
 * or the raw arithmetic expression for an ad-hoc {@code expression} operation), the {@code inputs}
 * that fed it (which columns, how many data points, any parameters), and the {@code result} —
 * <b>always computed in Java</b>, never supplied by the model.
 *
 * <p>Immutable; the inputs accessor returns an unmodifiable view.
 */
public final class AppliedFormula {

    private final String name;
    private final String expression;
    private final Map<String, Object> inputs;
    private final double result;

    public AppliedFormula(String name, String expression, Map<String, Object> inputs, double result) {
        this.name = name;
        this.expression = expression;
        this.inputs = (inputs == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.result = result;
    }

    /** The operation name from the computation plan (e.g. {@code average_order_value}). */
    public String getName() {
        return name;
    }

    /** A readable description of the computation (e.g. {@code mean(order_value)}). */
    public String getExpression() {
        return expression;
    }

    /** The inputs that fed the formula — columns read, data-point count, parameters; never {@code null}. */
    public Map<String, Object> getInputs() {
        return inputs;
    }

    /** The figure Java computed deterministically for this formula. */
    public double getResult() {
        return result;
    }
}
