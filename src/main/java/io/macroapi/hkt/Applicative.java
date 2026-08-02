package io.macroapi.hkt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * An applicative functor: a {@link Functor} that can also lift pure values and combine two
 * independent effects.
 *
 * <p>Independence is the point. Unlike a monadic bind, {@link #map2} receives both operands before
 * either has produced a value, so an implementation is free to run them concurrently. The effect
 * interpreter exploits exactly this: {@code io.macroapi.effect.Eff}'s applicative instance combines
 * with a parallel zip, so every {@code Combine} node in a plan and every sibling branch of a
 * traversal fans out automatically.</p>
 *
 * @param <F> witness for the applicative type constructor
 */
public interface Applicative<F> extends Functor<F> {

    /**
     * Lifts a plain value into the effect with no work attached.
     *
     * @param value the value to lift
     * @param <A>   the value type
     * @return the value embedded in {@code F}
     */
    <A> Higher<F, A> pure(A value);

    /**
     * Combines two <em>independent</em> effects with a binary function.
     *
     * @param fa  the first effect
     * @param fb  the second effect
     * @param fn  the combining function
     * @param <A> first result type
     * @param <B> second result type
     * @param <C> combined result type
     * @return an effect producing the combination of both results
     */
    <A, B, C> Higher<F, C> map2(Higher<F, A> fa, Higher<F, B> fb, BiFunction<? super A, ? super B, ? extends C> fn);

    /**
     * Turns a list of effects into a single effect producing the list of results, preserving order.
     *
     * <p>Implemented by repeated {@link #map2}, so implementations with a concurrent {@code map2}
     * get concurrent sequencing for free.</p>
     *
     * @param items the effects to sequence
     * @param <A>   the element type
     * @return one effect yielding all results in the original order
     */
    default <A> Higher<F, List<A>> sequence(List<Higher<F, A>> items) {
        Higher<F, List<A>> accumulator = pure(List.of());
        for (Higher<F, A> item : items) {
            accumulator = this.<List<A>, A, List<A>>map2(accumulator, item, (soFar, next) -> {
                List<A> grown = new ArrayList<>(soFar);
                grown.add(next);
                return List.copyOf(grown);
            });
        }
        return accumulator;
    }
}
