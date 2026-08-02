package io.macroapi.effect;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * The result of a completed API call: either a value or an {@link ApiError}.
 *
 * <p>This is the usual "either" type specialised to a fixed error side, which keeps signatures
 * throughout the project down to one type parameter. Being sealed, every consumer can destructure
 * it with an exhaustive switch over record patterns.</p>
 *
 * @param <A> the success type
 */
public sealed interface Outcome<A> {

    /**
     * A completed call that produced a value.
     *
     * @param value the produced value
     * @param <A>   the success type
     */
    record Success<A>(A value) implements Outcome<A> {
    }

    /**
     * A completed call that produced a failure.
     *
     * @param error the failure
     * @param <A>   the success type that was expected
     */
    record Failure<A>(ApiError error) implements Outcome<A> {
        /**
         * Canonical constructor.
         *
         * @param error the failure value, non-null
         */
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * Creates a successful outcome.
     *
     * @param value the value produced
     * @param <A>   the success type
     * @return a {@link Success}
     */
    static <A> Outcome<A> success(A value) {
        return new Success<>(value);
    }

    /**
     * Creates a failed outcome.
     *
     * @param error the failure produced
     * @param <A>   the success type that was expected
     * @return a {@link Failure}
     */
    static <A> Outcome<A> failure(ApiError error) {
        return new Failure<>(error);
    }

    /**
     * Transforms the value of a successful outcome, leaving a failure untouched.
     *
     * @param fn  the transformation
     * @param <B> the new success type
     * @return the transformed outcome
     */
    default <B> Outcome<B> map(Function<? super A, ? extends B> fn) {
        return switch (this) {
            case Success<A>(A value) -> new Success<>(fn.apply(value));
            case Failure<A>(ApiError error) -> new Failure<>(error);
        };
    }

    /**
     * Collapses both cases into a single value.
     *
     * @param onSuccess applied to the value of a {@link Success}
     * @param onFailure applied to the error of a {@link Failure}
     * @param <R>       the common result type
     * @return whichever branch applies
     */
    default <R> R fold(Function<? super A, ? extends R> onSuccess, Function<? super ApiError, ? extends R> onFailure) {
        return switch (this) {
            case Success<A>(A value) -> onSuccess.apply(value);
            case Failure<A>(ApiError error) -> onFailure.apply(error);
        };
    }

    /**
     * Whether this outcome carries a value.
     *
     * @return {@code true} for {@link Success}
     */
    default boolean isSuccess() {
        return this instanceof Success<A>;
    }

    /**
     * Returns the value, or throws if this outcome is a failure.
     *
     * <p>Intended for tests, demos and the outermost edge of an application where a failure must
     * finally become an exception; plan code should prefer {@link #fold} or recovery nodes.</p>
     *
     * @return the carried value
     * @throws NoSuchElementException if this is a {@link Failure}
     */
    default A orElseThrow() {
        return switch (this) {
            case Success<A>(A value) -> value;
            case Failure<A>(ApiError error) -> throw new NoSuchElementException(error.describe());
        };
    }
}
