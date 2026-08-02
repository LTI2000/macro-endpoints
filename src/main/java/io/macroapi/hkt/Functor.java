package io.macroapi.hkt;

import java.util.function.Function;

/**
 * A functor instance for the type constructor witnessed by {@code F}.
 *
 * <p>Instances are ordinary values (usually singletons) rather than subtypes, which lets the same
 * data type carry several instances and lets generic code such as
 * {@link Recursion#cata(Functor, Algebra, Fix)} take the instance as a parameter.</p>
 *
 * <p>Implementations are expected to satisfy the usual laws:</p>
 * <ul>
 *   <li><em>identity</em>: {@code map(fa, x -> x)} equals {@code fa};</li>
 *   <li><em>composition</em>: {@code map(map(fa, f), g)} equals {@code map(fa, f.andThen(g))}.</li>
 * </ul>
 *
 * @param <F> witness for the mapped type constructor
 */
public interface Functor<F> {

    /**
     * Applies {@code fn} to every {@code A} position held by {@code fa}, leaving the surrounding
     * shape untouched.
     *
     * @param fa  the structure to map over
     * @param fn  the transformation to apply to each element position
     * @param <A> the source element type
     * @param <B> the target element type
     * @return the structure with all element positions transformed
     */
    <A, B> App<F, B> map(App<F, A> fa, Function<? super A, ? extends B> fn);
}
