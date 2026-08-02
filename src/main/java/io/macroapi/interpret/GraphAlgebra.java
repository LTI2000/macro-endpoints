package io.macroapi.interpret;

import io.macroapi.effect.ApiError;
import io.macroapi.effect.Endpoint;
import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.Higher;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Functor;
import io.macroapi.hkt.Traverse;
import io.macroapi.plan.Plan;
import io.macroapi.plan.PlanAlgebra;
import io.macroapi.plan.PlanCata;
import io.macroapi.plan.PlanCoalgebra;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The interpreter that derives a dependency graph of the calls a plan will make.
 *
 * <p>Each plan node maps to a fragment operation on {@link CallGraph}: an {@code Invoke} becomes a
 * single vertex, a {@code Combine} places two fragments side by side, a {@code Chain} wires one
 * fragment's exits to the next's entries, and a {@code Labeled} boundary draws a box round whatever
 * it encloses. Because the composition rules are those of the fragment algebra, the graph is
 * assembled correctly without any global bookkeeping.</p>
 *
 * <p><strong>Statefulness.</strong> Vertex identifiers are drawn from a counter held by the
 * instance, so an interpreter must be used for exactly one fold. {@link Interpreters} creates a
 * fresh one per call; construct your own the same way.</p>
 */
public final class GraphAlgebra implements PlanAlgebra<Const.Witness<CallGraph>> {

    /** Creates an interpreter with a fresh identifier counter; use it for exactly one fold. */
    public GraphAlgebra() {
    }

    private final AtomicInteger sequence = new AtomicInteger();

    private String nextId(String prefix) {
        return prefix + sequence.incrementAndGet();
    }

    @Override
    public <A> Higher<Const.Witness<CallGraph>, A> pure(A value) {
        return Const.of(CallGraph.EMPTY);
    }

    @Override
    public <A> Higher<Const.Witness<CallGraph>, A> failed(ApiError error) {
        return Const.of(CallGraph.single(new CallGraph.GraphNode(
                nextId("fail"), "fail", CallGraph.NodeKind.FAILURE, error.describe())));
    }

    @Override
    public <Q, R> Higher<Const.Witness<CallGraph>, R> invoke(Endpoint<Q, R> endpoint, Q request) {
        return Const.of(CallGraph.single(new CallGraph.GraphNode(
                nextId("ep"), endpoint.spec().name(), CallGraph.NodeKind.ENDPOINT,
                endpoint.spec().signature())));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A pure transformation adds no vertex: it neither calls anything nor introduces a
     * dependency, so the fragment passes through unchanged.</p>
     */
    @Override
    public <X, A> Higher<Const.Witness<CallGraph>, A> transform(Higher<Const.Witness<CallGraph>, X> source,
                                                                Function<? super X, ? extends A> function,
                                                                String label) {
        return Const.<CallGraph, X>narrow(source).retag();
    }

    @Override
    public <X, Y, A> Higher<Const.Witness<CallGraph>, A> combine(Higher<Const.Witness<CallGraph>, X> left,
                                                                 Higher<Const.Witness<CallGraph>, Y> right,
                                                                 BiFunction<? super X, ? super Y, ? extends A> combiner,
                                                                 String label) {
        return Const.of(Const.unwrap(left).alongside(Const.unwrap(right)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>With a static probe the continuation is explored and drawn, joined by a dashed
     * "depends on" edge to record that the downstream shape was inferred rather than fixed. Without
     * one, a diamond marks the frontier so the diagram does not silently under-report.</p>
     */
    @Override
    public <X, A> Higher<Const.Witness<CallGraph>, A> chain(Higher<Const.Witness<CallGraph>, X> source,
                                                            Function<? super X, Plan<A>> continuation,
                                                            Optional<X> staticProbe,
                                                            String label) {
        CallGraph upstream = Const.unwrap(source);
        CallGraph downstream = staticProbe
                .map(probe -> graphOf(continuation.apply(probe)))
                .orElseGet(() -> CallGraph.single(new CallGraph.GraphNode(
                        nextId("dyn"), label, CallGraph.NodeKind.DYNAMIC, "not statically known")));
        return Const.of(upstream.then(downstream, CallGraph.EdgeKind.DYNAMIC));
    }

    @Override
    public <A> Higher<Const.Witness<CallGraph>, A> recover(Higher<Const.Witness<CallGraph>, A> source,
                                                           Function<? super ApiError, Plan<A>> handler) {
        CallGraph guarded = Const.unwrap(source);
        CallGraph fallback = graphOf(handler.apply(new ApiError.Synthetic("graph probe")));
        if (fallback.isEmpty()) {
            return Const.of(guarded);
        }
        CallGraph joined = guarded.alongside(fallback);
        for (String exit : guarded.exits()) {
            for (String entry : fallback.entries()) {
                joined = joined.withEdge(exit, entry, CallGraph.EdgeKind.RECOVERY);
            }
        }
        return Const.of(joined);
    }

    @Override
    public <A> Higher<Const.Witness<CallGraph>, A> labeled(String name, Higher<Const.Witness<CallGraph>, A> inner) {
        return Const.of(Const.unwrap(inner).inCluster(name));
    }

    @Override
    public <G, A> Higher<Const.Witness<CallGraph>, A> fold(Higher<Const.Witness<CallGraph>, Fix<G>> source,
                                                           Functor<G> functor,
                                                           Algebra<G, A> algebra,
                                                           String label) {
        CallGraph reduction = CallGraph.single(new CallGraph.GraphNode(
                nextId("cata"), label, CallGraph.NodeKind.REDUCTION, "catamorphism"));
        return Const.of(Const.unwrap(source).then(reduction, CallGraph.EdgeKind.SEQUENTIAL));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Drawn as the loop it is: a recursion vertex feeding the probed body, a back-edge from the
     * body to the vertex to show that each layer produces the next seed, and a reduction vertex
     * where the algebra consumes the layers.</p>
     */
    @Override
    public <G, S, A> Higher<Const.Witness<CallGraph>, A> hylo(S seed,
                                                              Traverse<G> traversal,
                                                              PlanCoalgebra<G, S> coalgebra,
                                                              Algebra<G, A> algebra,
                                                              String label) {
        CallGraph.GraphNode loop = new CallGraph.GraphNode(
                nextId("loop"), label, CallGraph.NodeKind.RECURSION, "unfold from " + seed);
        CallGraph.GraphNode reduction = new CallGraph.GraphNode(
                nextId("cata"), label + " reduce", CallGraph.NodeKind.REDUCTION, "catamorphism");

        CallGraph body = graphOf(coalgebra.step(seed));
        CallGraph assembled = CallGraph.single(loop).then(body, CallGraph.EdgeKind.SEQUENTIAL);
        for (String exit : body.exits()) {
            assembled = assembled.withEdge(exit, loop.id(), CallGraph.EdgeKind.LOOP);
        }
        return Const.of(assembled.then(CallGraph.single(reduction), CallGraph.EdgeKind.SEQUENTIAL));
    }

    private CallGraph graphOf(Plan<?> plan) {
        return Const.unwrap(PlanCata.fold(plan, this));
    }
}
