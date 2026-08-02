package io.macroapi.plan;

import io.macroapi.effect.ApiError;
import io.macroapi.effect.Endpoint;
import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.App;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Functor;
import io.macroapi.hkt.Traverse;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An interpreter for {@link Plan}: one method per node, each collapsing a node whose sub-plans have
 * already been interpreted into some result type {@code F}.
 *
 * <p>This is a {@code Plan}-algebra in the same sense as {@link Algebra} is an {@code F}-algebra —
 * the recursion lives in {@link PlanCata#fold}, not here. Because the result type varies with the
 * node's own type parameter, the carrier cannot be a plain type: an interpreter targeting execution
 * produces {@code Eff<A>} for a {@code Plan<A>}, while one targeting documentation produces the
 * same {@code Outline} whatever {@code A} is. Both are expressed as {@code App<F, A>}, using the
 * effect witness in the first case and a constant functor in the second.</p>
 *
 * <h2>Implementing an interpreter</h2>
 * <p>Six of the ten methods receive their sub-plans already interpreted. Four cannot, because the
 * sub-plan in question does not exist until a value does:</p>
 * <ul>
 *   <li>{@link #chain} receives the continuation as a function and an optional probe;</li>
 *   <li>{@link #recover} receives the handler as a function;</li>
 *   <li>{@link #hylo} receives the coalgebra as a function.</li>
 * </ul>
 * <p>An interpreter handles these by recursively invoking {@code PlanCata.fold(subPlan, this)} —
 * at execution time for a running interpreter, or against a probe value for a static one. This is
 * the usual consequence of folding a free monad rather than a first-order syntax tree, and is
 * documented rather than hidden because it is the one place where the "one fold, many
 * interpretations" story needs care.</p>
 *
 * @param <F> witness for the carrier this interpreter produces
 */
public interface PlanAlgebra<F> {

    /**
     * Interprets a constant.
     *
     * @param value the constant
     * @param <A>   the value type
     * @return the interpretation
     */
    <A> App<F, A> pure(A value);

    /**
     * Interprets a constant failure.
     *
     * @param error the failure
     * @param <A>   the type that would have been produced
     * @return the interpretation
     */
    <A> App<F, A> failed(ApiError error);

    /**
     * Interprets a single endpoint call.
     *
     * @param endpoint the endpoint being called
     * @param request  the request value
     * @param <Q>      the request type
     * @param <R>      the response type
     * @return the interpretation
     */
    <Q, R> App<F, R> invoke(Endpoint<Q, R> endpoint, Q request);

    /**
     * Interprets a pure transformation of an already-interpreted source.
     *
     * @param source   the interpreted upstream
     * @param function the transformation
     * @param label    the node's description
     * @param <X>      the input type
     * @param <A>      the output type
     * @return the interpretation
     */
    <X, A> App<F, A> transform(App<F, X> source, Function<? super X, ? extends A> function, String label);

    /**
     * Interprets the merge of two independent, already-interpreted branches.
     *
     * @param left     the interpreted first branch
     * @param right    the interpreted second branch
     * @param combiner the merge function
     * @param label    the node's description
     * @param <X>      the first result type
     * @param <Y>      the second result type
     * @param <A>      the merged result type
     * @return the interpretation
     */
    <X, Y, A> App<F, A> combine(App<F, X> left,
                                App<F, Y> right,
                                BiFunction<? super X, ? super Y, ? extends A> combiner,
                                String label);

    /**
     * Interprets a dependent step.
     *
     * @param source       the interpreted upstream
     * @param continuation chooses the next plan from the upstream value
     * @param staticProbe  a representative upstream value for analysis only
     * @param label        the node's description
     * @param <X>          the type the decision is based on
     * @param <A>          the final result type
     * @return the interpretation
     */
    <X, A> App<F, A> chain(App<F, X> source,
                           Function<? super X, Plan<A>> continuation,
                           Optional<X> staticProbe,
                           String label);

    /**
     * Interprets a recovery boundary.
     *
     * @param source  the interpreted guarded plan
     * @param handler chooses the recovery plan from a failure
     * @param <A>     the result type
     * @return the interpretation
     */
    <A> App<F, A> recover(App<F, A> source, Function<? super ApiError, Plan<A>> handler);

    /**
     * Interprets a named boundary around an already-interpreted plan.
     *
     * @param name  the boundary name
     * @param inner the interpreted enclosed plan
     * @param <A>   the result type
     * @return the interpretation
     */
    <A> App<F, A> labeled(String name, App<F, A> inner);

    /**
     * Interprets a catamorphism over a recursive intermediate result.
     *
     * @param source  the interpreted plan producing the structure
     * @param functor the functor instance for the structure
     * @param algebra the layer-collapsing rule
     * @param label   the node's description
     * @param <G>     witness for the structure's shape
     * @param <A>     the folded result type
     * @return the interpretation
     */
    <G, A> App<F, A> fold(App<F, Fix<G>> source, Functor<G> functor, Algebra<G, A> algebra, String label);

    /**
     * Interprets an effectful unfold fused with a catamorphism.
     *
     * @param seed      the starting seed
     * @param traversal the traversable instance for the intermediate shape
     * @param coalgebra grows one layer per seed
     * @param algebra   collapses one layer
     * @param label     the node's description
     * @param <G>       witness for the intermediate shape
     * @param <S>       the seed type
     * @param <A>       the reduced result type
     * @return the interpretation
     */
    <G, S, A> App<F, A> hylo(S seed,
                             Traverse<G> traversal,
                             PlanCoalgebra<G, S> coalgebra,
                             Algebra<G, A> algebra,
                             String label);
}
