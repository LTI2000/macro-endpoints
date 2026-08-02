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
 * The interpreter that turns a plan into human-readable documentation.
 *
 * <p>It performs no I/O whatsoever, which is what makes it usable at build time: the project's site
 * generation folds every registered macro through this interpreter and publishes the result, so the
 * documentation of a composite endpoint cannot drift from its implementation.</p>
 *
 * <p>Where the plan is dynamic the outline says so. A {@code Chain} without a static probe is
 * reported as an opaque frontier; with a probe, the explored branch is marked as one possible
 * continuation rather than presented as fact.</p>
 */
public final class OutlineAlgebra implements PlanAlgebra<Const.Witness<Outline>> {

    /** Creates a stateless documentation interpreter. */
    public OutlineAlgebra() {
    }

    @Override
    public <A> Higher<Const.Witness<Outline>, A> pure(A value) {
        return Const.of(Outline.leaf("constant", String.valueOf(value)));
    }

    @Override
    public <A> Higher<Const.Witness<Outline>, A> failed(ApiError error) {
        return Const.of(Outline.leaf("fail", error.describe()));
    }

    @Override
    public <Q, R> Higher<Const.Witness<Outline>, R> invoke(Endpoint<Q, R> endpoint, Q request) {
        return Const.of(Outline.leaf("call", endpoint.spec().name() + " (" + endpoint.spec().signature() + ")"));
    }

    @Override
    public <X, A> Higher<Const.Witness<Outline>, A> transform(Higher<Const.Witness<Outline>, X> source,
                                                              Function<? super X, ? extends A> function,
                                                              String label) {
        return Const.of(Outline.of("transform", label, Const.unwrap(source)));
    }

    @Override
    public <X, Y, A> Higher<Const.Witness<Outline>, A> combine(Higher<Const.Witness<Outline>, X> left,
                                                               Higher<Const.Witness<Outline>, Y> right,
                                                               BiFunction<? super X, ? super Y, ? extends A> combiner,
                                                               String label) {
        return Const.of(Outline.of("parallel", label, Const.unwrap(left), Const.unwrap(right)));
    }

    @Override
    public <X, A> Higher<Const.Witness<Outline>, A> chain(Higher<Const.Witness<Outline>, X> source,
                                                          Function<? super X, Plan<A>> continuation,
                                                          Optional<X> staticProbe,
                                                          String label) {
        Outline upstream = Const.unwrap(source);
        return Const.of(staticProbe
                .map(probe -> Outline.of("sequence", label, upstream,
                        Outline.of("branch (probed)", "one possible continuation",
                                describe(continuation.apply(probe)))))
                .orElseGet(() -> Outline.of("sequence", label, upstream,
                        Outline.leaf("branch (dynamic)", "continuation depends on the result"))));
    }

    @Override
    public <A> Higher<Const.Witness<Outline>, A> recover(Higher<Const.Witness<Outline>, A> source,
                                                         Function<? super ApiError, Plan<A>> handler) {
        Plan<A> fallback = handler.apply(new ApiError.Synthetic("outline probe"));
        return Const.of(Outline.of("recover", "on failure", Const.unwrap(source),
                Outline.of("fallback", "", describe(fallback))));
    }

    @Override
    public <A> Higher<Const.Witness<Outline>, A> labeled(String name, Higher<Const.Witness<Outline>, A> inner) {
        return Const.of(Outline.of("macro", name, Const.unwrap(inner)));
    }

    @Override
    public <G, A> Higher<Const.Witness<Outline>, A> fold(Higher<Const.Witness<Outline>, Fix<G>> source,
                                                         Functor<G> functor,
                                                         Algebra<G, A> algebra,
                                                         String label) {
        return Const.of(Outline.of("catamorphism", label, Const.unwrap(source)));
    }

    @Override
    public <G, S, A> Higher<Const.Witness<Outline>, A> hylo(S seed,
                                                            Traverse<G> traversal,
                                                            PlanCoalgebra<G, S> coalgebra,
                                                            Algebra<G, A> algebra,
                                                            String label) {
        return Const.of(Outline.of("hylomorphism", label,
                Outline.of("unfold", "seed = " + seed, describe(coalgebra.step(seed))),
                Outline.leaf("catamorphism", "reduce each layer as its children complete")));
    }

    private Outline describe(Plan<?> plan) {
        return Const.unwrap(PlanCata.fold(plan, this));
    }
}
