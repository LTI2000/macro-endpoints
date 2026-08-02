package io.macroapi.plan;

/**
 * A plan that yields a constant without calling anything.
 *
 * <p>The unit of the plan monad: it is the neutral element for sequencing, and the natural result
 * of a recovery handler that substitutes a default value.</p>
 *
 * @param value the constant to produce
 * @param <A>   the produced type
 */
public record Pure<A>(A value) implements Plan<A> {
}
