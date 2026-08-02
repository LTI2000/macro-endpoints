package io.macroapi.effect;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An arrow in the Kleisli category of {@link Eff}: a function {@code A -> Eff<B>}.
 *
 * <p>Plain functions compose with {@code andThen}; effectful functions cannot, because the output
 * {@code Eff<B>} does not fit the input {@code B} of the next step. A Kleisli arrow restores
 * composition by threading the bind through, which is what lets an API call be treated as an
 * ordinary composable building block. The category laws hold as expected: {@link #identity()} is a
 * two-sided unit for {@link #andThen}, and composition is associative.</p>
 *
 * <p>This interface is the <em>value-level</em> half of the design. Composing arrows directly is
 * convenient but produces an opaque function: nothing downstream can see which endpoints it will
 * call. {@link io.macroapi.plan.Plan} is the <em>reified</em> half, describing the same composition
 * as inspectable data. Use arrows for local glue, plans for anything that should be documented,
 * costed or graphed.</p>
 *
 * @param <A> the input type
 * @param <B> the output type
 */
@FunctionalInterface
public interface Kleisli<A, B> {

    /**
     * Applies the arrow, producing a deferred effect.
     *
     * @param input the argument
     * @return the effect that will produce the result
     */
    Eff<B> run(A input);

    /**
     * Kleisli composition: run this arrow, then feed its result to {@code next}.
     *
     * @param next the downstream arrow
     * @param <C>  the final output type
     * @return the composed arrow
     */
    default <C> Kleisli<A, C> andThen(Kleisli<? super B, C> next) {
        Objects.requireNonNull(next, "next");
        return input -> run(input).flatMap(next::run);
    }

    /**
     * Kleisli composition in diagrammatic reverse: run {@code previous} first, then this arrow.
     *
     * @param previous the upstream arrow
     * @param <Z>      the initial input type
     * @return the composed arrow
     */
    default <Z> Kleisli<Z, B> compose(Kleisli<Z, ? extends A> previous) {
        Objects.requireNonNull(previous, "previous");
        return input -> previous.run(input).flatMap(this::run);
    }

    /**
     * Post-processes the result with a pure function.
     *
     * @param fn  the transformation
     * @param <C> the new output type
     * @return the mapped arrow
     */
    default <C> Kleisli<A, C> map(Function<? super B, ? extends C> fn) {
        Objects.requireNonNull(fn, "fn");
        return input -> run(input).map(fn);
    }

    /**
     * Pre-processes the argument with a pure function.
     *
     * @param fn  the transformation applied before this arrow
     * @param <Z> the new input type
     * @return the adapted arrow
     */
    default <Z> Kleisli<Z, B> contraMap(Function<? super Z, ? extends A> fn) {
        Objects.requireNonNull(fn, "fn");
        return input -> run(fn.apply(input));
    }

    /**
     * Fan-out: feeds the same input to both arrows, runs them concurrently and merges the results.
     *
     * @param other   the arrow to run alongside
     * @param combine merges both results
     * @param <C>     the other arrow's output type
     * @param <D>     the merged output type
     * @return the fan-out arrow
     */
    default <C, D> Kleisli<A, D> fanOut(Kleisli<? super A, C> other,
                                        BiFunction<? super B, ? super C, ? extends D> combine) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(combine, "combine");
        return input -> run(input).zipPar(other.run(input), combine);
    }

    /**
     * Supplies a fallback for failures.
     *
     * @param handler chooses a replacement effect from the failure
     * @return the guarded arrow
     */
    default Kleisli<A, B> recoverWith(Function<? super ApiError, ? extends Eff<B>> handler) {
        Objects.requireNonNull(handler, "handler");
        return input -> run(input).recoverWith(handler);
    }

    /**
     * The identity arrow, which performs no effect.
     *
     * @param <A> the input and output type
     * @return an arrow returning its argument
     */
    static <A> Kleisli<A, A> identity() {
        return Eff::succeed;
    }

    /**
     * Lifts a pure function into the Kleisli category.
     *
     * @param fn  the function to lift
     * @param <A> the input type
     * @param <B> the output type
     * @return an effect-free arrow
     */
    static <A, B> Kleisli<A, B> lift(Function<? super A, ? extends B> fn) {
        Objects.requireNonNull(fn, "fn");
        return input -> Eff.succeed(fn.apply(input));
    }
}
