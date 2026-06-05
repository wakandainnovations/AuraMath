package com.lit.fire.flame.nlq.math;

import com.lit.fire.flame.nlq.sql.QueryResult;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Step 2b of F7 — evaluate a {@link ComputationPlan} <b>deterministically in Java</b>. The LLM never
 * supplies a final number; this evaluator does, using {@code commons-math3} for statistics and a
 * restricted {@code exp4j} expression evaluator for ad-hoc arithmetic.
 *
 * <p>It is defensive by construction: empty result sets, non-numeric columns, missing columns,
 * division by zero, and non-finite results never throw — the offending operation is skipped and a
 * human-readable note is recorded in {@link Result#getIssues()} so the narrative can be honest about
 * what could not be computed. Only operations that produced a finite value appear in
 * {@link Result#getComputedValues()} and {@link Result#getFormulasApplied()}.
 *
 * <p><b>Supported formula catalog</b> (case-insensitive, with common aliases):
 * {@code count}, {@code sum}, {@code mean}/{@code average}, {@code min}, {@code max},
 * {@code median}, {@code std_dev}, {@code variance}, {@code percentile} (arg {@code percentile}/{@code p}),
 * {@code weighted_average} (value, weight columns), {@code growth_rate}, {@code cagr}
 * (optional arg {@code periods}), {@code regression_slope}/{@code regression_intercept} (x, y columns),
 * {@code correlation} (x, y columns), and {@code expression} (exp4j over earlier operations' results).
 */
public final class FormulaEvaluator {

    /** The deterministic outcome of evaluating a plan. */
    public static final class Result {
        private final Map<String, Double> computedValues;
        private final List<AppliedFormula> formulasApplied;
        private final List<String> issues;

        Result(Map<String, Double> computedValues, List<AppliedFormula> formulasApplied,
               List<String> issues) {
            this.computedValues = Collections.unmodifiableMap(new LinkedHashMap<>(computedValues));
            this.formulasApplied = Collections.unmodifiableList(new ArrayList<>(formulasApplied));
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }

        /** Operation name &rarr; the finite value Java computed; never {@code null}. */
        public Map<String, Double> getComputedValues() {
            return computedValues;
        }

        /** One entry per successfully computed operation, in plan order; never {@code null}. */
        public List<AppliedFormula> getFormulasApplied() {
            return formulasApplied;
        }

        /** Notes about operations that could not be computed (and why); never {@code null}. */
        public List<String> getIssues() {
            return issues;
        }
    }

    /** Functions allowed inside an ad-hoc {@code expression} operation; nothing else can be called. */
    private static final Set<String> ALLOWED_EXPRESSION_FUNCTIONS = Set.of(
            "abs", "sqrt", "cbrt", "exp", "log", "log2", "log10", "min", "max",
            "pow", "ceil", "floor", "signum");

    /**
     * Evaluate every operation in {@code plan} against {@code result}.
     *
     * @return the computed values, applied-formula records, and any per-operation issues; never null
     */
    public Result evaluate(ComputationPlan plan, QueryResult result) {
        Map<String, Double> computed = new LinkedHashMap<>();
        List<AppliedFormula> applied = new ArrayList<>();
        List<String> issues = new ArrayList<>();

        for (ComputationPlan.Operation op : plan.getOperations()) {
            String name = (op.getName() == null || op.getName().trim().isEmpty())
                    ? op.getFormula() : op.getName().trim();
            try {
                AppliedFormula formula = evaluateOne(op, name, result, computed);
                double value = formula.getResult();
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    issues.add("Skipped '" + name + "': computation produced a non-finite result "
                            + "(empty data or division by zero).");
                    continue;
                }
                computed.put(name, value);
                applied.add(formula);
            } catch (EvaluationException e) {
                issues.add("Skipped '" + name + "': " + e.getMessage());
            }
        }
        return new Result(computed, applied, issues);
    }

    private AppliedFormula evaluateOne(ComputationPlan.Operation op, String name, QueryResult result,
                                       Map<String, Double> computed) {
        String formula = normalize(op.getFormula());
        switch (formula) {
            case "count":
            case "sum":
            case "mean":
            case "min":
            case "max":
            case "median":
            case "std_dev":
            case "variance":
            case "percentile":
                return statistic(op, name, formula, result);
            case "weighted_average":
                return weightedAverage(op, name, result);
            case "growth_rate":
                return growthRate(op, name, result);
            case "cagr":
                return cagr(op, name, result);
            case "regression_slope":
            case "regression_intercept":
            case "correlation":
                return paired(op, name, formula, result);
            case "expression":
                return expression(op, name, computed);
            default:
                throw new EvaluationException("unsupported formula '" + op.getFormula() + "'");
        }
    }

    /** Single-column descriptive statistics via {@code commons-math3}. */
    private AppliedFormula statistic(ComputationPlan.Operation op, String name, String formula,
                                     QueryResult result) {
        String column = requireColumn(op, 0);
        double[] values = numericColumn(result, column);
        if (values.length == 0) {
            throw new EvaluationException("column '" + column + "' has no numeric values");
        }
        DescriptiveStatistics stats = new DescriptiveStatistics(values);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("column", column);
        inputs.put("n", values.length);

        double value;
        String expr;
        switch (formula) {
            case "count":
                value = values.length;
                expr = "count(" + column + ")";
                break;
            case "sum":
                value = stats.getSum();
                expr = "sum(" + column + ")";
                break;
            case "mean":
                value = stats.getMean();
                expr = "mean(" + column + ")";
                break;
            case "min":
                value = stats.getMin();
                expr = "min(" + column + ")";
                break;
            case "max":
                value = stats.getMax();
                expr = "max(" + column + ")";
                break;
            case "median":
                value = stats.getPercentile(50.0);
                expr = "median(" + column + ")";
                break;
            case "std_dev":
                value = stats.getStandardDeviation();
                expr = "std_dev(" + column + ")";
                break;
            case "variance":
                value = stats.getVariance();
                expr = "variance(" + column + ")";
                break;
            case "percentile":
                double p = arg(op, "percentile", "p", 95.0);
                if (p <= 0.0 || p > 100.0) {
                    throw new EvaluationException("percentile must be in (0, 100], got " + p);
                }
                value = stats.getPercentile(p);
                expr = "percentile(" + column + ", p=" + trim(p) + ")";
                inputs.put("percentile", p);
                break;
            default:
                throw new EvaluationException("unsupported statistic '" + formula + "'");
        }
        return new AppliedFormula(name, expr, inputs, value);
    }

    /** {@code sum(value*weight) / sum(weight)} over row-aligned value/weight columns. */
    private AppliedFormula weightedAverage(ComputationPlan.Operation op, String name, QueryResult result) {
        String valueCol = requireColumn(op, 0);
        String weightCol = requireColumn(op, 1);
        List<Double> values = column(result, valueCol);
        List<Double> weights = column(result, weightCol);

        double weightedSum = 0.0;
        double weightTotal = 0.0;
        int n = 0;
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            Double w = (i < weights.size()) ? weights.get(i) : null;
            if (v == null || w == null) {
                continue;
            }
            weightedSum += v * w;
            weightTotal += w;
            n++;
        }
        if (n == 0) {
            throw new EvaluationException("no rows with both '" + valueCol + "' and '" + weightCol + "' numeric");
        }
        if (weightTotal == 0.0) {
            throw new EvaluationException("weights in '" + weightCol + "' sum to zero (division by zero)");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("valueColumn", valueCol);
        inputs.put("weightColumn", weightCol);
        inputs.put("n", n);
        return new AppliedFormula(name,
                "weighted_average(" + valueCol + " by " + weightCol + ")", inputs, weightedSum / weightTotal);
    }

    /** {@code (last - first) / first} over a column in result (row) order. */
    private AppliedFormula growthRate(ComputationPlan.Operation op, String name, QueryResult result) {
        String column = requireColumn(op, 0);
        double[] series = numericColumn(result, column);
        if (series.length < 2) {
            throw new EvaluationException("growth_rate needs at least two values in '" + column + "'");
        }
        double first = series[0];
        double last = series[series.length - 1];
        if (first == 0.0) {
            throw new EvaluationException("first value of '" + column + "' is zero (division by zero)");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("column", column);
        inputs.put("first", first);
        inputs.put("last", last);
        return new AppliedFormula(name, "growth_rate(" + column + ")", inputs, (last - first) / first);
    }

    /** Compound annual growth rate {@code (last/first)^(1/periods) - 1}. */
    private AppliedFormula cagr(ComputationPlan.Operation op, String name, QueryResult result) {
        String column = requireColumn(op, 0);
        double[] series = numericColumn(result, column);
        if (series.length < 2) {
            throw new EvaluationException("cagr needs at least two values in '" + column + "'");
        }
        double first = series[0];
        double last = series[series.length - 1];
        if (first == 0.0) {
            throw new EvaluationException("first value of '" + column + "' is zero (division by zero)");
        }
        if (first < 0.0 || last < 0.0) {
            throw new EvaluationException("cagr is undefined for negative values in '" + column + "'");
        }
        double periods = arg(op, "periods", "n", series.length - 1);
        if (periods <= 0.0) {
            throw new EvaluationException("cagr periods must be positive, got " + periods);
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("column", column);
        inputs.put("first", first);
        inputs.put("last", last);
        inputs.put("periods", periods);
        return new AppliedFormula(name, "cagr(" + column + ", periods=" + trim(periods) + ")",
                inputs, Math.pow(last / first, 1.0 / periods) - 1.0);
    }

    /** Two-column regression slope/intercept or Pearson correlation, row-aligned. */
    private AppliedFormula paired(ComputationPlan.Operation op, String name, String formula,
                                  QueryResult result) {
        String xCol = requireColumn(op, 0);
        String yCol = requireColumn(op, 1);
        List<Double> xs = column(result, xCol);
        List<Double> ys = column(result, yCol);

        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < xs.size(); i++) {
            Double x = xs.get(i);
            Double y = (i < ys.size()) ? ys.get(i) : null;
            if (x != null && y != null) {
                pairs.add(new double[]{x, y});
            }
        }
        if (pairs.size() < 2) {
            throw new EvaluationException("need at least two row-aligned pairs of '" + xCol + "','" + yCol + "'");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("xColumn", xCol);
        inputs.put("yColumn", yCol);
        inputs.put("n", pairs.size());

        if ("correlation".equals(formula)) {
            double[] xa = new double[pairs.size()];
            double[] ya = new double[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                xa[i] = pairs.get(i)[0];
                ya[i] = pairs.get(i)[1];
            }
            double r = new PearsonsCorrelation().correlation(xa, ya);
            return new AppliedFormula(name, "correlation(" + xCol + ", " + yCol + ")", inputs, r);
        }

        SimpleRegression regression = new SimpleRegression();
        for (double[] pair : pairs) {
            regression.addData(pair[0], pair[1]);
        }
        if ("regression_slope".equals(formula)) {
            return new AppliedFormula(name, "regression_slope(" + yCol + " ~ " + xCol + ")",
                    inputs, regression.getSlope());
        }
        return new AppliedFormula(name, "regression_intercept(" + yCol + " ~ " + xCol + ")",
                inputs, regression.getIntercept());
    }

    /** Ad-hoc arithmetic over earlier operations' results via a restricted exp4j evaluator. */
    private AppliedFormula expression(ComputationPlan.Operation op, String name,
                                      Map<String, Double> computed) {
        String raw = op.getExpression();
        if (raw == null || raw.trim().isEmpty()) {
            throw new EvaluationException("expression operation has no expression");
        }
        String expr = raw.trim();
        rejectUnlistedFunctions(expr);
        try {
            Expression compiled = new ExpressionBuilder(expr)
                    .variables(computed.keySet())
                    .build();
            // Bind earlier results, then validate that every referenced variable is now set.
            compiled.setVariables(computed);
            if (!compiled.validate(true).isValid()) {
                throw new EvaluationException("expression references unknown names or is malformed");
            }
            double value = compiled.evaluate();
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("variables", new LinkedHashMap<>(computed));
            return new AppliedFormula(name, expr, inputs, value);
        } catch (EvaluationException e) {
            throw e;
        } catch (RuntimeException e) {
            // exp4j throws IllegalArgumentException/ArithmeticException for bad syntax or unknown functions.
            String message = (e.getMessage() == null) ? e.getClass().getSimpleName() : e.getMessage();
            throw new EvaluationException("could not evaluate expression: " + message);
        }
    }

    /** Reject any {@code name(} call whose function is not on the numeric whitelist. */
    private void rejectUnlistedFunctions(String expr) {
        java.util.regex.Matcher m = FUNCTION_CALL.matcher(expr);
        while (m.find()) {
            String fn = m.group(1).toLowerCase(Locale.ROOT);
            if (!ALLOWED_EXPRESSION_FUNCTIONS.contains(fn)) {
                throw new EvaluationException("function '" + fn + "' is not permitted in expressions");
            }
        }
    }

    /** An identifier immediately followed by '(' — i.e. a function call. */
    private static final java.util.regex.Pattern FUNCTION_CALL =
            java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    // --- column / value helpers -------------------------------------------------------------

    /** A column's values in row order, {@code null} where the cell is null or non-numeric. */
    private List<Double> column(QueryResult result, String columnName) {
        if (!hasColumn(result, columnName)) {
            throw new EvaluationException("column '" + columnName + "' is not in the result set");
        }
        List<Double> out = new ArrayList<>(result.getRows().size());
        for (Map<String, Object> row : result.getRows()) {
            out.add(toDouble(row.get(columnName)));
        }
        return out;
    }

    /** A column reduced to just its finite numeric values, in row order. */
    private double[] numericColumn(QueryResult result, String columnName) {
        List<Double> values = column(result, columnName);
        List<Double> numeric = new ArrayList<>(values.size());
        for (Double v : values) {
            if (v != null) {
                numeric.add(v);
            }
        }
        double[] out = new double[numeric.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = numeric.get(i);
        }
        return out;
    }

    private boolean hasColumn(QueryResult result, String columnName) {
        for (QueryResult.Column column : result.getColumns()) {
            if (column.getName().equalsIgnoreCase(columnName)) {
                return true;
            }
        }
        // Fall back to row keys in case a column label differs from metadata (defensive).
        return !result.getRows().isEmpty() && result.getRows().get(0).containsKey(columnName);
    }

    /** Convert a JSON-friendly cell value to a {@code double}, or {@code null} if not numeric. */
    static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).doubleValue();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Boolean) {
            return null;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String requireColumn(ComputationPlan.Operation op, int index) {
        List<String> columns = op.getColumns();
        if (columns.size() <= index || columns.get(index) == null || columns.get(index).trim().isEmpty()) {
            throw new EvaluationException("missing required column at position " + index);
        }
        return columns.get(index).trim();
    }

    private double arg(ComputationPlan.Operation op, String key, String altKey, double fallback) {
        Double v = op.getArgs().get(key);
        if (v == null && altKey != null) {
            v = op.getArgs().get(altKey);
        }
        return (v == null) ? fallback : v;
    }

    private static String normalize(String formula) {
        if (formula == null) {
            return "";
        }
        String f = formula.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (f) {
            case "average":
            case "avg":
                return "mean";
            case "stddev":
            case "standard_deviation":
                return "std_dev";
            case "weighted_avg":
            case "weighted_mean":
                return "weighted_average";
            case "slope":
                return "regression_slope";
            case "intercept":
                return "regression_intercept";
            case "pearson":
            case "pearson_correlation":
            case "corr":
                return "correlation";
            case "percentile_rank":
                return "percentile";
            default:
                return f;
        }
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** Internal control-flow signal: an operation could not be computed (recorded, never thrown out). */
    private static final class EvaluationException extends RuntimeException {
        EvaluationException(String message) {
            super(message);
        }
    }
}
