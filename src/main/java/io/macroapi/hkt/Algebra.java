package io.macroapi.hkt;

/**
 * An {@code F}-algebra: a rule for collapsing <em>one layer</em> of a recursive structure whose
 * sub-structures have already been collapsed to a value of type {@code A}.
 *
 * <p>An algebra is the reusable half of a catamorphism. It knows nothing about recursion — the
 * recursion lives entirely in {@link Recursion#cata(Functor, Algebra, Fix)} — so the same algebra
 * can be applied to a materialised structure, fused into a {@code Hylo} plan node so that no
 * intermediate structure is ever built, or composed with other algebras.</p>
 *
 * <p>Example. For a list-shaped functor with an element type of {@code Order}, the algebra that
 * totals order values has type {@code Algebra<ListF.Witness<Order>, BigDecimal>} and needs only two
 * cases: an empty layer yields zero, and a cons layer adds its head to the already-totalled tail.</p>
 *
 * @param <F> witness for the shape being collapsed
 * @param <A> the carrier: the type each layer collapses to
 */
@FunctionalInterface
public interface Algebra<F, A> {

    /**
     * Collapses a single layer whose recursive positions already hold carrier values.
     *
     * @param layer one layer of structure, with sub-results in place of sub-structures
     * @return the carrier value for this layer
     */
    A apply(App<F, A> layer);
}
