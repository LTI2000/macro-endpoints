package io.macroapi.plan;

import java.util.Objects;
import java.util.function.Function;

/**
 * A pure function applied to the result of a sub-plan.
 *
 * <p>Carries no cost and adds no node to a dependency graph; it exists so that adaptation between
 * layers stays inside the plan and remains visible in documentation.</p>
 *
 * @param source   the plan producing the input
 * @param function the transformation
 * @param label    a short description used in documentation and diagrams
 * @param <X>      the input type
 * @param <A>      the output type
 */
public record Transform<X, A>(Plan<X> source, Function<? super X, ? extends A> function, String label)
        implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param source   upstream plan, non-null
     * @param function transformation, non-null
     * @param label    description, non-null
     */
    public Transform {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(label, "label");
    }
}
