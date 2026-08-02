package io.macroapi.plan;

import io.macroapi.effect.ApiError;
import io.macroapi.effect.Endpoint;
import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Functor;
import io.macroapi.hkt.Traverse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Factory methods for building {@link Plan} values.
 *
 * <p>Plans are constructed here and refined with the fluent methods on {@link Plan} itself. A
 * static import of this class reads well at the composition site:</p>
 *
 * {@snippet lang = "java":
 * import static io.macroapi.plan.Plans.*;
 *
 * Plan<Dashboard> dashboard =
 *         call(CUSTOMER, id)
 *             .combine(call(LOYALTY, id), CustomerLoyalty::new, "profile")
 *             .combine(orderSummary(id), Dashboard::new, "with-orders")
 *             .named("customer-dashboard");
 *}
 */
public final class Plans {

    private Plans() {
        throw new AssertionError("no instances");
    }

    /**
     * A plan yielding a constant, performing no call.
     *
     * @param value the constant
     * @param <A>   the value type
     * @return the constant plan
     */
    public static <A> Plan<A> pure(A value) {
        return new Pure<>(value);
    }

    /**
     * A plan that always fails, typically used by a recovery handler to re-raise.
     *
     * @param error the failure to produce
     * @param <A>   the type that would have been produced
     * @return the failing plan
     */
    public static <A> Plan<A> failed(ApiError error) {
        return new Fail<>(error);
    }

    /**
     * A plan invoking one low-level endpoint.
     *
     * @param endpoint the endpoint to call
     * @param request  the request value
     * @param <Q>      the request type
     * @param <R>      the response type
     * @return the single-call plan
     */
    public static <Q, R> Plan<R> call(Endpoint<Q, R> endpoint, Q request) {
        return new Invoke<>(endpoint, request);
    }

    /**
     * Runs three independent plans concurrently and merges their results.
     *
     * <p>Nests two {@link Combine} nodes, so the concurrency and cost properties are those of a
     * flat fan-out rather than a chain.</p>
     *
     * @param first    the first plan
     * @param second   the second plan
     * @param third    the third plan
     * @param merge    combines all three results
     * @param label    a short description used in documentation and diagrams
     * @param <A>      the first result type
     * @param <B>      the second result type
     * @param <C>      the third result type
     * @param <R>      the merged result type
     * @return the fan-out plan
     */
    public static <A, B, C, R> Plan<R> parallel(Plan<A> first,
                                                Plan<B> second,
                                                Plan<C> third,
                                                TriFunction<A, B, C, R> merge,
                                                String label) {
        Objects.requireNonNull(merge, "merge");
        return first.combine(second, Plan.Both::new, label + "/1")
                .combine(third, (both, thirdValue) -> merge.apply(both.left(), both.right(), thirdValue), label);
    }

    /**
     * Turns a list of independent plans into one plan producing the list of results.
     *
     * <p>Built from {@link Combine} nodes, so all elements are eligible to run concurrently and the
     * cost interpreter reports the maximum latency rather than the sum. Result order matches input
     * order regardless of completion order.</p>
     *
     * @param plans the independent plans
     * @param <A>   the element type
     * @return a plan producing all results
     */
    public static <A> Plan<List<A>> sequence(List<Plan<A>> plans) {
        Plan<List<A>> accumulator = pure(List.of());
        for (Plan<A> element : plans) {
            accumulator = accumulator.combine(element, (soFar, next) -> {
                List<A> grown = new ArrayList<>(soFar);
                grown.add(next);
                return List.copyOf(grown);
            }, "sequence");
        }
        return accumulator;
    }

    /**
     * Applies a catamorphism to a recursive structure produced by another plan.
     *
     * @param source  the plan producing the structure
     * @param functor the functor instance for the structure's shape
     * @param algebra the layer-collapsing rule
     * @param label   a short description used in documentation and diagrams
     * @param <G>     witness for the shape
     * @param <A>     the folded result type
     * @return the folding plan
     * @see Fold
     */
    public static <G, A> Plan<A> fold(Plan<Fix<G>> source, Functor<G> functor, Algebra<G, A> algebra, String label) {
        return new Fold<>(source, functor, algebra, label);
    }

    /**
     * Grows a recursive structure by repeated API calls and immediately reduces it.
     *
     * <p>The fused form: no intermediate structure is materialised. See {@link Hylo} for the
     * fusion and concurrency properties.</p>
     *
     * @param seed      the starting seed
     * @param traversal the traversable instance for the intermediate shape
     * @param coalgebra grows one layer per seed by calling APIs
     * @param algebra   collapses one layer of already-reduced children
     * @param label     a short description used in documentation and diagrams
     * @param <G>       witness for the intermediate shape
     * @param <S>       the seed type
     * @param <A>       the reduced result type
     * @return the hylomorphism plan
     */
    public static <G, S, A> Plan<A> hylo(S seed,
                                         Traverse<G> traversal,
                                         PlanCoalgebra<G, S> coalgebra,
                                         Algebra<G, A> algebra,
                                         String label) {
        return new Hylo<>(seed, traversal, coalgebra, algebra, label);
    }

    /**
     * Grows a recursive structure by repeated API calls and returns it whole.
     *
     * <p>Exactly {@link #hylo} with {@link Fix#wrap} as the algebra — the identity of the
     * catamorphism. Use this when the structure itself is the deliverable, or when several different
     * folds will be applied to the same fetched structure; otherwise prefer {@code hylo}, which
     * avoids building it.</p>
     *
     * @param seed      the starting seed
     * @param traversal the traversable instance for the shape
     * @param coalgebra grows one layer per seed by calling APIs
     * @param label     a short description used in documentation and diagrams
     * @param <G>       witness for the shape
     * @param <S>       the seed type
     * @return a plan producing the materialised structure
     */
    public static <G, S> Plan<Fix<G>> unfold(S seed,
                                             Traverse<G> traversal,
                                             PlanCoalgebra<G, S> coalgebra,
                                             String label) {
        return hylo(seed, traversal, coalgebra, Fix::wrap, label);
    }

    /**
     * Wraps a plan in a named boundary.
     *
     * @param name  the boundary name
     * @param inner the enclosed plan
     * @param <A>   the result type
     * @return the labelled plan
     */
    public static <A> Plan<A> named(String name, Plan<A> inner) {
        return new Labeled<>(name, inner);
    }

    /**
     * A three-argument function, absent from {@code java.util.function}.
     *
     * @param <A> first argument type
     * @param <B> second argument type
     * @param <C> third argument type
     * @param <R> result type
     */
    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        /**
         * Applies the function.
         *
         * @param first  first argument
         * @param second second argument
         * @param third  third argument
         * @return the result
         */
        R apply(A first, B second, C third);
    }
}
