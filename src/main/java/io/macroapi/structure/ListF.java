package io.macroapi.structure;

import io.macroapi.hkt.Higher;
import io.macroapi.hkt.Applicative;
import io.macroapi.hkt.Traverse;

import java.util.function.Function;

/**
 * The pattern functor of a cons list: one layer is either empty, or an element followed by a hole
 * where the rest of the list goes.
 *
 * <p>This is the shape of any <em>sequential</em> remote iteration — most obviously a cursor-paged
 * collection, where each layer holds one page and the recursive position holds whatever the next
 * cursor yields. Because the recursion is expressed as a hole rather than as a field of the same
 * type, the generic recursion schemes apply and the paging loop never has to be written by hand.</p>
 *
 * @param <E> the element type carried by each layer
 * @param <A> the recursive position, which is a further {@code ListF} layer in a fixed point and a
 *            reduced value during a fold
 */
public sealed interface ListF<E, A> extends Higher<ListF.Witness<E>, A> {

    /**
     * Uninhabited type-level tag standing for the partially applied constructor {@code ListF<E, _>}.
     *
     * @param <E> the element type held fixed by the partial application
     */
    final class Witness<E> {
        private Witness() {
            throw new AssertionError("no instances");
        }
    }

    /**
     * The terminal layer: no element, no continuation.
     *
     * @param <E> the element type
     * @param <A> the recursive position
     */
    record Nil<E, A>() implements ListF<E, A> {
    }

    /**
     * A layer holding one element and a continuation.
     *
     * @param head the element at this layer
     * @param tail the recursive position
     * @param <E>  the element type
     * @param <A>  the recursive position
     */
    record Cons<E, A>(E head, A tail) implements ListF<E, A> {
    }

    /**
     * Recovers the concrete type from its {@link Higher} encoding; see {@link Higher} for why this cast is
     * safe.
     *
     * @param higher the encoded layer
     * @param <E> the element type
     * @param <A> the recursive position
     * @return the same value, statically typed
     */
    @SuppressWarnings("unchecked")
    static <E, A> ListF<E, A> narrow(Higher<Witness<E>, A> higher) {
        return (ListF<E, A>) higher;
    }

    /**
     * The traversable instance for lists of a fixed element type.
     *
     * <p>A cons layer has exactly one recursive position, so traversal is inherently sequential —
     * which is correct for cursor paging, where the next request is not known until the current
     * response arrives.</p>
     *
     * @param <E> the element type
     * @return the instance
     */
    static <E> Traverse<Witness<E>> traversal() {
        return new Traverse<Witness<E>>() {
            @Override
            public <A, B> Higher<Witness<E>, B> map(Higher<Witness<E>, A> fa, Function<? super A, ? extends B> fn) {
                return switch (narrow(fa)) {
                    case Nil<E, A>() -> new Nil<>();
                    case Cons<E, A>(var head, var tail) -> new Cons<>(head, fn.apply(tail));
                };
            }

            @Override
            public <G, A, B> Higher<G, Higher<Witness<E>, B>> traverse(Applicative<G> applicative,
                                                                       Higher<Witness<E>, A> fa,
                                                                       Function<? super A, ? extends Higher<G, B>> fn) {
                return switch (narrow(fa)) {
                    case Nil<E, A>() -> applicative.pure(new Nil<E, B>());
                    case Cons<E, A>(var head, var tail) ->
                            applicative.<B, Higher<Witness<E>, B>>map(fn.apply(tail), rest -> new Cons<>(head, rest));
                };
            }
        };
    }
}
