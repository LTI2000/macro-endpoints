package io.macroapi.hkt;

import java.util.Objects;

/**
 * The least fixed point of a functor: {@code Fix<F>} is isomorphic to {@code F<Fix<F>>}.
 *
 * <p>Recursive data types in this project are defined in two steps. First a non-recursive
 * <em>pattern functor</em> describes a single layer and leaves a hole where recursion would go
 * (for example {@code ConsF(head, A tail)}). Then {@code Fix} ties the knot, plugging the whole
 * structure back into its own hole. The pay-off is that generic recursion schemes such as
 * {@link Recursion#cata(Functor, Algebra, Fix)} can be written once for every shape, instead of
 * hand-rolling a fold per data type.</p>
 *
 * @param unfix the single unwrapped layer, whose recursive positions hold further fixed points
 * @param <F>   witness for the pattern functor
 */
public record Fix<F>(App<F, Fix<F>> unfix) {

    /**
     * Canonical constructor, rejecting a null layer.
     *
     * @param unfix the wrapped layer
     */
    public Fix {
        Objects.requireNonNull(unfix, "unfix");
    }

    /**
     * Factory alias, convenient as a method reference {@code Fix::wrap} where an
     * {@code Algebra<F, Fix<F>>} is expected — that algebra is the identity of the catamorphism and
     * turns an unfold into a plain structure builder.
     *
     * @param layer the layer to wrap
     * @param <F>   witness for the pattern functor
     * @return the wrapped layer
     */
    public static <F> Fix<F> wrap(App<F, Fix<F>> layer) {
        return new Fix<>(layer);
    }
}
