package io.macroapi.interpret;

import io.macroapi.hkt.Higher;

import java.util.Objects;

/**
 * The constant functor: a value of type {@code M} wearing a phantom type parameter {@code A}.
 *
 * <p>Every static interpreter uses this as its carrier. A documenting interpreter produces an
 * {@link Outline} for a {@code Plan<Customer>} and an {@code Outline} for a {@code Plan<Order>} —
 * the same type regardless of what the plan computes. {@link io.macroapi.plan.PlanAlgebra} demands
 * a carrier of the form {@code Higher<F, A>}, and {@code Const} supplies one by ignoring {@code A}.</p>
 *
 * <p>This is what lets a single fold serve both a real effect, where the result type matters, and
 * an analysis, where it does not.</p>
 *
 * @param value the carried analysis result
 * @param <M>   the type actually carried
 * @param <A>   the ignored phantom type
 */
public record Const<M, A>(M value) implements Higher<Const.Witness<M>, A> {

    /**
     * Uninhabited type-level tag standing for the partially applied constructor {@code Const<M, _>}.
     *
     * @param <M> the carried type held fixed by the partial application
     */
    public static final class Witness<M> {
        /** Not instantiable; the tag exists only at the type level. */
        private Witness() {
            throw new AssertionError("no instances");
        }
    }

    /**
     * Canonical constructor.
     *
     * @param value the carried value, non-null
     */
    public Const {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Wraps a value, inferring the phantom parameter from the call site.
     *
     * @param value the value to carry
     * @param <M>   the carried type
     * @param <A>   the phantom type
     * @return the wrapper
     */
    public static <M, A> Const<M, A> of(M value) {
        return new Const<>(value);
    }

    /**
     * Re-labels the phantom parameter, which is a no-op at runtime.
     *
     * <p>Needed where an interpreter must return {@code Const<M, A>} but holds a
     * {@code Const<M, X>} obtained from a differently-typed sub-plan.</p>
     *
     * @param <B> the new phantom type
     * @return the same carried value under a different phantom type
     */
    public <B> Const<M, B> retag() {
        return new Const<>(value);
    }

    /**
     * Recovers the concrete type from its {@link Higher} encoding; see {@link Higher} for why this cast is
     * safe.
     *
     * @param higher the encoded value
     * @param <M> the carried type
     * @param <A> the phantom type
     * @return the same value, statically typed
     */
    @SuppressWarnings("unchecked")
    public static <M, A> Const<M, A> narrow(Higher<Witness<M>, A> higher) {
        return (Const<M, A>) higher;
    }

    /**
     * Extracts the carried value from an encoded constant.
     *
     * @param higher the encoded value
     * @param <M> the carried type
     * @param <A> the phantom type
     * @return the carried value
     */
    public static <M, A> M unwrap(Higher<Witness<M>, A> higher) {
        return narrow(higher).value();
    }
}
