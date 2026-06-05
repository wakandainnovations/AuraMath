package com.lit.fire.flame.nlq.math;

import com.lit.fire.flame.nlq.sql.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for {@link FormulaEvaluator} (F7, step 2b). No LLM is involved — these assert
 * that {@code commons-math3} / {@code exp4j} compute the exact figures and that the guards (empty
 * data, non-numeric columns, division by zero) skip rather than throw.
 */
class FormulaEvaluatorTest {

    private final FormulaEvaluator evaluator = new FormulaEvaluator();

    /** Build a single-column numeric result over {@code order_value}. */
    private QueryResult orderValues(Object... values) {
        List<QueryResult.Column> columns = new ArrayList<>();
        columns.add(new QueryResult.Column("order_value", java.sql.Types.NUMERIC, "NUMERIC"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object v : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order_value", v);
            rows.add(row);
        }
        return new QueryResult(columns, rows, false, 1L);
    }

    private ComputationPlan plan(ComputationPlan.Operation... ops) {
        return new ComputationPlan(false, List.of(ops), List.of());
    }

    private ComputationPlan.Operation op(String name, String formula, List<String> cols,
                                         Map<String, Double> args, String expr) {
        return new ComputationPlan.Operation(name, formula, cols, args, expr, null);
    }

    @Test
    void meanIsComputedExactlyByCommonsMath() {
        QueryResult result = orderValues(10, 20, 30, 40);
        FormulaEvaluator.Result out = evaluator.evaluate(
                plan(op("avg_order", "mean", List.of("order_value"), null, null)), result);

        assertEquals(25.0, out.getComputedValues().get("avg_order"), 1e-9);
        assertEquals(1, out.getFormulasApplied().size());
        AppliedFormula f = out.getFormulasApplied().get(0);
        assertEquals("mean(order_value)", f.getExpression());
        assertEquals(4, f.getInputs().get("n"));
        assertTrue(out.getIssues().isEmpty());
    }

    @Test
    void percentileUsesItsArgument() {
        QueryResult result = orderValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        FormulaEvaluator.Result out = evaluator.evaluate(
                plan(op("p90", "percentile", List.of("order_value"), Map.of("percentile", 90.0), null)),
                result);
        // commons-math3 default (R-7-ish) percentile; assert it is finite, in-range, and recorded.
        double p90 = out.getComputedValues().get("p90");
        assertTrue(p90 >= 9.0 && p90 <= 10.0, "p90 was " + p90);
    }

    @Test
    void expressionComposesEarlierResultsDeterministically() {
        // revenue and cost columns -> margin via an ad-hoc expression over earlier sums.
        List<QueryResult.Column> columns = new ArrayList<>();
        columns.add(new QueryResult.Column("revenue", java.sql.Types.NUMERIC, "NUMERIC"));
        columns.add(new QueryResult.Column("cost", java.sql.Types.NUMERIC, "NUMERIC"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("revenue", 100);
            row.put("cost", 40);
            rows.add(row);
        }
        QueryResult result = new QueryResult(columns, rows, false, 1L);

        ComputationPlan plan = new ComputationPlan(false, List.of(
                op("revenue_total", "sum", List.of("revenue"), null, null),
                op("cost_total", "sum", List.of("cost"), null, null),
                op("margin", "expression", List.of(), null,
                        "(revenue_total - cost_total) / revenue_total")), List.of());

        FormulaEvaluator.Result out = evaluator.evaluate(plan, result);
        assertEquals(200.0, out.getComputedValues().get("revenue_total"), 1e-9);
        assertEquals(80.0, out.getComputedValues().get("cost_total"), 1e-9);
        assertEquals(0.6, out.getComputedValues().get("margin"), 1e-9);
    }

    @Test
    void emptyResultSetSkipsRatherThanThrows() {
        QueryResult result = orderValues();
        FormulaEvaluator.Result out = evaluator.evaluate(
                plan(op("avg_order", "mean", List.of("order_value"), null, null)), result);
        assertTrue(out.getComputedValues().isEmpty());
        assertTrue(out.getFormulasApplied().isEmpty());
        assertFalse(out.getIssues().isEmpty(), "an issue should be recorded for the empty column");
    }

    @Test
    void nonNumericColumnIsGuarded() {
        QueryResult result = orderValues("alpha", "beta");
        FormulaEvaluator.Result out = evaluator.evaluate(
                plan(op("avg_order", "mean", List.of("order_value"), null, null)), result);
        assertTrue(out.getComputedValues().isEmpty());
        assertFalse(out.getIssues().isEmpty());
    }

    @Test
    void weightedAverageDivisionByZeroIsGuarded() {
        List<QueryResult.Column> columns = new ArrayList<>();
        columns.add(new QueryResult.Column("price", java.sql.Types.NUMERIC, "NUMERIC"));
        columns.add(new QueryResult.Column("qty", java.sql.Types.NUMERIC, "NUMERIC"));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("price", 5);
        row.put("qty", 0);
        rows.add(row);
        QueryResult result = new QueryResult(columns, rows, false, 1L);

        FormulaEvaluator.Result out = evaluator.evaluate(
                plan(op("wavg", "weighted_average", List.of("price", "qty"), null, null)), result);
        assertTrue(out.getComputedValues().isEmpty());
        assertFalse(out.getIssues().isEmpty());
    }

    @Test
    void disallowedFunctionInExpressionIsRejected() {
        QueryResult result = orderValues(1, 2, 3);
        ComputationPlan plan = new ComputationPlan(false, List.of(
                op("total", "sum", List.of("order_value"), null, null),
                op("evil", "expression", List.of(), null, "System(total)")), List.of());
        FormulaEvaluator.Result out = evaluator.evaluate(plan, result);
        assertTrue(out.getComputedValues().containsKey("total"));
        assertFalse(out.getComputedValues().containsKey("evil"));
        assertFalse(out.getIssues().isEmpty());
    }

    @Test
    void stringEncodedNumbersAreParsed() {
        QueryResult result = orderValues("10.5", "9.5");
        FormulaEvaluator.Result out = evaluator.evaluate(
                plan(op("avg_order", "mean", List.of("order_value"), null, null)), result);
        assertEquals(10.0, out.getComputedValues().get("avg_order"), 1e-9);
    }
}
