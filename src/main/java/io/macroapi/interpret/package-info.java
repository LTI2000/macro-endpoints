/**
 * Interpreters: one {@link io.macroapi.plan.PlanAlgebra} per question you might ask of a plan.
 *
 * <p>{@link io.macroapi.interpret.ExecutionAlgebra} runs it,
 * {@link io.macroapi.interpret.OutlineAlgebra} documents it,
 * {@link io.macroapi.interpret.GraphAlgebra} draws its call dependencies, and
 * {@link io.macroapi.interpret.CostAlgebra} budgets it. All four traverse the identical tree through
 * {@link io.macroapi.plan.PlanCata}, so the documentation and the diagrams describe the code that
 * will actually run.</p>
 *
 * <p>The three static interpreters share {@link io.macroapi.interpret.Const} as their carrier, since
 * their output type does not vary with what the plan computes.</p>
 */
package io.macroapi.interpret;
