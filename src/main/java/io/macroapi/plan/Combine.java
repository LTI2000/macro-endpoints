package io.macroapi.plan;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Two independent sub-plans whose results are merged.
 *
 * <p>The node is an assertion by the author that neither branch depends on the other. Interpreters
 * rely on it: execution fans the branches out concurrently, costing takes the maximum latency
 * rather than the sum, and the graph draws them as parallel paths.</p>
 *
 * @param left     the first sub-plan
 * @param right    the second sub-plan
 * @param combiner merges the two results
 * @param label    a short description used in documentation and diagrams
 * @param <X>      the first result type
 * @param <Y>      the second result type
 * @param <A>      the merged result type
 */
public record Combine<X, Y, A>(Plan<X> left,
                               Plan<Y> right,
                               BiFunction<? super X, ? super Y, ? extends A> combiner,
                               String label) implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param left     first sub-plan, non-null
     * @param right    second sub-plan, non-null
     * @param combiner merge function, non-null
     * @param label    description, non-null
     */
    public Combine {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(combiner, "combiner");
        Objects.requireNonNull(label, "label");
    }
}
