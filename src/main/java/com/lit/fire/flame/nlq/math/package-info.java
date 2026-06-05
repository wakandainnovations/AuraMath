/**
 * The "mathematician" layer (F7): turns Ask-engine result sets into a final natural-language answer,
 * applying formulas deterministically.
 *
 * <p>{@link com.lit.fire.flame.nlq.math.AnswerSynthesisService} drives a plan-then-evaluate design.
 * The LLM produces a {@link com.lit.fire.flame.nlq.math.ComputationPlan} — <i>which</i> formulas
 * apply over <i>which</i> columns, never the final numbers — and
 * {@link com.lit.fire.flame.nlq.math.FormulaEvaluator} evaluates it deterministically in Java with
 * {@code commons-math3} (mean, std dev, regression, correlation, percentile, …) and a restricted
 * {@code exp4j} evaluator for ad-hoc arithmetic. The model then writes a concise answer
 * <i>given</i> those computed values, never inventing numbers beyond them. The outcome is an
 * {@link com.lit.fire.flame.nlq.math.AskAnswer}.
 */
package com.lit.fire.flame.nlq.math;
