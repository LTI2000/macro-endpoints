package io.macroapi.interpret;

import io.macroapi.plan.Plan;
import io.macroapi.plan.PlanCata;

/**
 * Convenience entry points for the non-executing interpretations of a plan.
 *
 * <p>Each method is one fold of the same tree with a different {@link io.macroapi.plan.PlanAlgebra}.
 * None of them performs any I/O, so all three are safe to call at build time, in a test, or in a
 * health endpoint that publishes what a service will do.</p>
 *
 * <p>For execution use {@link ApiRuntime}, which is separated out because it needs configuration
 * and a lifecycle that these do not.</p>
 */
public final class Interpreters {

    /** Not instantiable; this class is a holder for static entry points. */
    private Interpreters() {
        throw new AssertionError("no instances");
    }

    /** Iterations assumed for a recursive node when no better estimate is supplied. */
    public static final int DEFAULT_LOOP_FACTOR = 3;

    /**
     * Describes a plan's structure as an outline tree.
     *
     * @param plan the plan to describe
     * @return the outline
     */
    public static Outline describe(Plan<?> plan) {
        return Const.unwrap(PlanCata.fold(plan, new OutlineAlgebra()));
    }

    /**
     * Derives the dependency graph of the calls a plan will make.
     *
     * @param plan the plan to analyse
     * @return the graph, renderable as DOT or PlantUML
     */
    public static CallGraph graph(Plan<?> plan) {
        return Const.unwrap(PlanCata.fold(plan, new GraphAlgebra()));
    }

    /**
     * Estimates a plan's cost using {@link #DEFAULT_LOOP_FACTOR}.
     *
     * @param plan the plan to estimate
     * @return the estimate
     */
    public static Cost estimate(Plan<?> plan) {
        return estimate(plan, DEFAULT_LOOP_FACTOR);
    }

    /**
     * Estimates a plan's cost.
     *
     * @param plan       the plan to estimate
     * @param loopFactor iterations assumed for each recursive node
     * @return the estimate
     */
    public static Cost estimate(Plan<?> plan, int loopFactor) {
        return Const.unwrap(PlanCata.fold(plan, new CostAlgebra(loopFactor)));
    }
}
