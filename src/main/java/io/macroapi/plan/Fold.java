package io.macroapi.plan;

import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Functor;

import java.util.Objects;

/**
 * A catamorphism applied to a recursive intermediate result.
 *
 * <p>This is the node that answers "how do I get from many low-level results to one high-level
 * answer". The source plan produces a recursive structure — a page chain, a category tree, a
 * comment thread — and the algebra collapses it to whatever the caller actually wants: a total, a
 * summary, a rendered view, a validation report.</p>
 *
 * <p>Keeping the algebra as a separate value rather than inlining a loop is what makes the reduction
 * reusable and testable in isolation: the same {@code Algebra} can be applied to a structure
 * obtained from a live API, from a fixture, or fused into a {@link Hylo} so the structure is never
 * built at all.</p>
 *
 * @param source  the plan producing the recursive structure
 * @param functor the functor instance for the structure's shape
 * @param algebra the rule collapsing one layer
 * @param label   a short description used in documentation and diagrams
 * @param <G>     witness for the structure's shape
 * @param <A>     the folded result type
 */
public record Fold<G, A>(Plan<Fix<G>> source, Functor<G> functor, Algebra<G, A> algebra, String label)
        implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param source  upstream plan, non-null
     * @param functor functor instance, non-null
     * @param algebra folding rule, non-null
     * @param label   description, non-null
     */
    public Fold {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(functor, "functor");
        Objects.requireNonNull(algebra, "algebra");
        Objects.requireNonNull(label, "label");
    }
}
