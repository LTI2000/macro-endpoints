package io.macroapi.interpret;

import io.macroapi.effect.ApiError;
import io.macroapi.effect.Endpoint;
import io.macroapi.hkt.*;
import io.macroapi.hkt.Higher;
import io.macroapi.plan.Plan;
import io.macroapi.plan.PlanAlgebra;
import io.macroapi.plan.PlanCata;
import io.macroapi.plan.PlanCoalgebra;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The interpreter that estimates a plan's execution cost without executing it.
 *
 * <p>Being a fold over the same tree as the executing interpreter, the estimate follows the real
 * concurrency structure rather than a guess about it: {@code Combine} nodes take a maximum latency
 * because the runtime really will fan them out. That makes the output suitable for a build-time
 * budget check — a test can assert that a public composite endpoint stays under a call count or a
 * projected latency, and adding an accidental extra hop breaks the build.</p>
 *
 * <p>Two things must be assumed rather than derived. Recursive nodes have no statically known
 * iteration count, so {@code loopFactor} projects one; and recovery paths are costed on the
 * assumption that the fallback does <em>not</em> run, since the happy path is the one worth
 * budgeting.</p>
 */
public final class CostAlgebra implements PlanAlgebra<Const.Witness<Cost>> {

    private final int loopFactor;

    /**
     * Creates a cost interpreter.
     *
     * @param loopFactor the assumed number of iterations for a recursive node; must be at least one
     */
    public CostAlgebra(int loopFactor) {
        if (loopFactor < 1) {
            throw new IllegalArgumentException("loopFactor must be >= 1: " + loopFactor);
        }
        this.loopFactor = loopFactor;
    }

    @Override
    public <A> Higher<Const.Witness<Cost>, A> pure(A value) {
        return Const.of(Cost.FREE);
    }

    @Override
    public <A> Higher<Const.Witness<Cost>, A> failed(ApiError error) {
        return Const.of(Cost.FREE);
    }

    @Override
    public <Q, R> Higher<Const.Witness<Cost>, R> invoke(Endpoint<Q, R> endpoint, Q request) {
        return Const.of(new Cost(endpoint.spec().typicalLatency(), endpoint.spec().costUnits(), 1));
    }

    @Override
    public <X, A> Higher<Const.Witness<Cost>, A> transform(Higher<Const.Witness<Cost>, X> source,
                                                           Function<? super X, ? extends A> function,
                                                           String label) {
        return Const.<Cost, X>narrow(source).retag();
    }

    @Override
    public <X, Y, A> Higher<Const.Witness<Cost>, A> combine(Higher<Const.Witness<Cost>, X> left,
                                                            Higher<Const.Witness<Cost>, Y> right,
                                                            BiFunction<? super X, ? super Y, ? extends A> combiner,
                                                            String label) {
        return Const.of(Const.unwrap(left).alongside(Const.unwrap(right)));
    }

    @Override
    public <X, A> Higher<Const.Witness<Cost>, A> chain(Higher<Const.Witness<Cost>, X> source,
                                                       Function<? super X, Plan<A>> continuation,
                                                       Optional<X> staticProbe,
                                                       String label) {
        Cost upstream = Const.unwrap(source);
        Cost downstream = staticProbe.map(probe -> costOf(continuation.apply(probe))).orElse(Cost.FREE);
        return Const.of(upstream.then(downstream));
    }

    @Override
    public <A> Higher<Const.Witness<Cost>, A> recover(Higher<Const.Witness<Cost>, A> source,
                                                      Function<? super ApiError, Plan<A>> handler) {
        return Const.<Cost, A>narrow(source).retag();
    }

    @Override
    public <A> Higher<Const.Witness<Cost>, A> labeled(String name, Higher<Const.Witness<Cost>, A> inner) {
        return Const.<Cost, A>narrow(inner).retag();
    }

    @Override
    public <G, A> Higher<Const.Witness<Cost>, A> fold(Higher<Const.Witness<Cost>, Fix<G>> source,
                                                      Functor<G> functor,
                                                      Algebra<G, A> algebra,
                                                      String label) {
        return Const.<Cost, Fix<G>>narrow(source).retag();
    }

    @Override
    public <G, S, A> Higher<Const.Witness<Cost>, A> hylo(S seed,
                                                         Traverse<G> traversal,
                                                         PlanCoalgebra<G, S> coalgebra,
                                                         Algebra<G, A> algebra,
                                                         String label) {
        return Const.of(costOf(coalgebra.step(seed)).times(loopFactor));
    }

    private Cost costOf(Plan<?> plan) {
        return Const.unwrap(PlanCata.fold(plan, this));
    }
}
