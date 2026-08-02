package io.macroapi.interpret;

import io.macroapi.effect.ApiError;
import io.macroapi.effect.Eff;
import io.macroapi.effect.Endpoint;
import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.App;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Functor;
import io.macroapi.hkt.Recursion;
import io.macroapi.hkt.Traverse;
import io.macroapi.plan.Plan;
import io.macroapi.plan.PlanAlgebra;
import io.macroapi.plan.PlanCata;
import io.macroapi.plan.PlanCoalgebra;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The interpreter that actually performs the calls, turning a {@link Plan} into an {@link Eff}.
 *
 * <p>Every method is a direct translation of a plan node into the corresponding effect combinator,
 * with two operational concerns layered on by the owning {@link ApiRuntime}: each endpoint call is
 * wrapped in the retry policy and timed for tracing, and each named boundary emits a span.</p>
 *
 * <p>Instances are stateless and reusable; all mutable context lives in the runtime.</p>
 *
 * @see ApiRuntime
 */
public final class ExecutionAlgebra implements PlanAlgebra<Eff.Witness> {

    private final ApiRuntime runtime;

    /**
     * Creates an interpreter bound to a runtime.
     *
     * @param runtime supplies the retry policy and the trace sink
     */
    public ExecutionAlgebra(ApiRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public <A> App<Eff.Witness, A> pure(A value) {
        return Eff.succeed(value);
    }

    @Override
    public <A> App<Eff.Witness, A> failed(ApiError error) {
        return Eff.fail(error);
    }

    @Override
    public <Q, R> App<Eff.Witness, R> invoke(Endpoint<Q, R> endpoint, Q request) {
        String name = endpoint.spec().name();
        Eff<R> call = Eff.defer(() -> endpoint.run(request))
                .observed((elapsed, outcome) ->
                        runtime.trace(new TraceEvent(TraceEvent.Kind.ENDPOINT, name, elapsed, outcome)));
        return runtime.retryPolicy().guard(call);
    }

    @Override
    public <X, A> App<Eff.Witness, A> transform(App<Eff.Witness, X> source,
                                                Function<? super X, ? extends A> function,
                                                String label) {
        return Eff.narrow(source).map(function);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses the parallel zip, so both branches are in flight before either is awaited. This is the
     * pay-off for having modelled independence explicitly in the plan rather than expressing
     * everything as a chain.</p>
     */
    @Override
    public <X, Y, A> App<Eff.Witness, A> combine(App<Eff.Witness, X> left,
                                                 App<Eff.Witness, Y> right,
                                                 BiFunction<? super X, ? super Y, ? extends A> combiner,
                                                 String label) {
        return Eff.narrow(left).zipPar(Eff.narrow(right), combiner);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The continuation is folded lazily: only once the upstream value exists is the resulting
     * sub-plan interpreted, by re-entering the shared fold with this same interpreter. The static
     * probe is ignored here — it exists solely for analysis and must never influence execution.</p>
     */
    @Override
    public <X, A> App<Eff.Witness, A> chain(App<Eff.Witness, X> source,
                                            Function<? super X, Plan<A>> continuation,
                                            Optional<X> staticProbe,
                                            String label) {
        return Eff.narrow(source).flatMap(value -> Eff.narrow(PlanCata.fold(continuation.apply(value), this)));
    }

    @Override
    public <A> App<Eff.Witness, A> recover(App<Eff.Witness, A> source,
                                           Function<? super ApiError, Plan<A>> handler) {
        return Eff.narrow(source).recoverWith(error -> Eff.narrow(PlanCata.fold(handler.apply(error), this)));
    }

    @Override
    public <A> App<Eff.Witness, A> labeled(String name, App<Eff.Witness, A> inner) {
        return Eff.narrow(inner).observed((elapsed, outcome) ->
                runtime.trace(new TraceEvent(TraceEvent.Kind.BOUNDARY, name, elapsed, outcome)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The structure has already been fetched by the source plan, so the fold itself is a pure
     * computation applied to the result.</p>
     */
    @Override
    public <G, A> App<Eff.Witness, A> fold(App<Eff.Witness, Fix<G>> source,
                                           Functor<G> functor,
                                           Algebra<G, A> algebra,
                                           String label) {
        return Eff.narrow(source).map(structure -> Recursion.cata(functor, algebra, structure));
    }

    /**
     * {@inheritDoc}
     *
     * <p>This is the fused hylomorphism. For each seed the coalgebra's plan is interpreted to fetch
     * one layer; the layer's recursive positions are then expanded by traversing with the effect's
     * <em>parallel</em> applicative, and the algebra is applied as soon as the children have been
     * reduced. No {@link Fix} value is ever constructed, so memory use is proportional to the depth
     * of the structure rather than its size.</p>
     *
     * <p>Note the shape of the recursion: {@code expand} calls itself through {@code traverse},
     * which means the concurrency of sibling expansion is decided entirely by the traversable
     * instance — sequential for a page chain, concurrent for a tree level.</p>
     */
    @Override
    public <G, S, A> App<Eff.Witness, A> hylo(S seed,
                                              Traverse<G> traversal,
                                              PlanCoalgebra<G, S> coalgebra,
                                              Algebra<G, A> algebra,
                                              String label) {
        return expand(seed, traversal, coalgebra, algebra);
    }

    private <G, S, A> Eff<A> expand(S seed,
                                    Traverse<G> traversal,
                                    PlanCoalgebra<G, S> coalgebra,
                                    Algebra<G, A> algebra) {
        Eff<App<G, S>> layer = Eff.defer(() -> Eff.narrow(PlanCata.fold(coalgebra.step(seed), this)));
        return layer.flatMap(grown -> {
            App<Eff.Witness, App<G, A>> reducedChildren = traversal.traverse(
                    Eff.applicative(),
                    grown,
                    childSeed -> expand(childSeed, traversal, coalgebra, algebra));
            return Eff.narrow(reducedChildren).map(algebra::apply);
        });
    }
}
