package io.macroapi.hkt;

/**
 * Generic recursion schemes over {@link Fix} points of a {@link Functor}.
 *
 * <p>Only the schemes actually needed by the plan interpreters are provided. The effectful
 * counterparts (an unfold whose steps are API calls, and the fused hylomorphism that never
 * materialises the intermediate structure) live in the interpreters themselves, because they must
 * thread the effect type through {@link Traverse}.</p>
 */
public final class Recursion {

    private Recursion() {
        throw new AssertionError("no instances");
    }

    /**
     * The catamorphism: the unique structure-preserving fold determined by an algebra.
     *
     * <p>Operationally it rewrites the structure bottom-up, replacing every layer by the carrier
     * value the algebra computes for it. Termination is guaranteed for finite structures because
     * each recursive call sees one layer less.</p>
     *
     * <p><strong>Stack depth.</strong> This is the direct, non-tail-recursive formulation, so the
     * JVM stack bounds the depth of the structure that can be folded (tens of thousands of layers
     * in practice). Structures produced by paging over an API are far shallower than that; if a
     * genuinely deep structure is expected, fold to a function and apply it, or replace this call
     * with an explicit work-list.</p>
     *
     * @param functor  the functor instance describing how to map over one layer
     * @param algebra  the rule collapsing one layer of already-folded sub-results
     * @param value    the structure to fold
     * @param <F>      witness for the pattern functor
     * @param <A>      the carrier type produced by the fold
     * @return the carrier value for the whole structure
     */
    public static <F, A> A cata(Functor<F> functor, Algebra<F, A> algebra, Fix<F> value) {
        return algebra.apply(functor.map(value.unfix(), child -> cata(functor, algebra, child)));
    }
}
