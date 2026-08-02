package io.macroapi.hkt;

import java.util.function.Function;

/**
 * A traversable functor: a shape whose element positions can be visited left-to-right while
 * running an effect, collecting the results back into the same shape.
 *
 * <p>Traversal is what makes an <em>effectful</em> unfold possible. When the interpreter expands a
 * recursive API call it produces one layer {@code App<F, S>} of seeds, and must then expand each
 * seed with a further round of API calls. Turning {@code F} of effects into an effect of {@code F}
 * is precisely {@link #traverse}.</p>
 *
 * @param <F> witness for the traversable type constructor
 */
public interface Traverse<F> extends Functor<F> {

    /**
     * Visits every element position of {@code fa}, running {@code fn} for each, and rebuilds the
     * shape inside the applicative effect {@code G}.
     *
     * <p>Because the recombination goes through {@link Applicative#map2}, sibling positions are
     * independent and may be executed concurrently by the chosen applicative.</p>
     *
     * @param applicative the applicative instance for the effect being run
     * @param fa          the shape to traverse
     * @param fn          the effectful function applied at each element position
     * @param <G>         witness for the effect type constructor
     * @param <A>         the source element type
     * @param <B>         the target element type
     * @return the effect producing the rebuilt shape
     */
    <G, A, B> App<G, App<F, B>> traverse(Applicative<G> applicative,
                                         App<F, A> fa,
                                         Function<? super A, ? extends App<G, B>> fn);
}
