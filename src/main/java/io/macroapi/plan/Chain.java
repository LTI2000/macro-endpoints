package io.macroapi.plan;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A dependent step: the continuation plan is chosen from the result of the source plan.
 *
 * <p>This is the monadic bind, and the only source of dynamism in a plan. Its continuation is a
 * function, so the sub-plan it will produce is not part of the tree; see the discussion of the
 * static probe in {@link Plan}.</p>
 *
 * @param source       the plan producing the value the decision is based on
 * @param continuation chooses the next plan
 * @param staticProbe  a representative input used <em>only</em> by non-executing interpreters
 * @param label        a short description of the decision being made
 * @param <X>          the type the decision is based on
 * @param <A>          the final result type
 */
public record Chain<X, A>(Plan<X> source,
                          Function<? super X, Plan<A>> continuation,
                          Optional<X> staticProbe,
                          String label) implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param source       upstream plan, non-null
     * @param continuation continuation function, non-null
     * @param staticProbe  optional analysis-only input, non-null optional
     * @param label        description, non-null
     */
    public Chain {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(staticProbe, "staticProbe");
        Objects.requireNonNull(label, "label");
    }
}
