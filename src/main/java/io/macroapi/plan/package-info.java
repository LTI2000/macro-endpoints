/**
 * The reified representation of a composed API call, and the single fold over it.
 *
 * <p>{@link io.macroapi.plan.Plan} is a sealed syntax tree describing what calls to make and how
 * their results combine; {@link io.macroapi.plan.PlanAlgebra} is the interface an interpreter
 * implements; {@link io.macroapi.plan.PlanCata} is the one traversal that connects them.</p>
 *
 * <p>Nothing in this package performs I/O or knows about any particular interpretation. That is the
 * point of the split: a plan is data, so it can be executed in production, rendered into
 * documentation at build time, and analysed for cost and dependencies in a test — all from the same
 * value.</p>
 */
package io.macroapi.plan;
