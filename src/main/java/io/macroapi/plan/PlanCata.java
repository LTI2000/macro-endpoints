package io.macroapi.plan;

import io.macroapi.hkt.App;

/**
 * The catamorphism over {@link Plan}: the single recursive traversal shared by every interpreter.
 *
 * <p>There is exactly one dispatch over the plan node set in this project, and it is here.
 * Everything else — executing, documenting, graphing, costing — is a {@link PlanAlgebra} with no
 * recursion of its own. Because {@code Plan} is sealed, the switch is exhaustive by compiler
 * verification: adding a node breaks this fold and every interpreter at compile time, which is the
 * intended failure mode.</p>
 *
 * <h2>Record patterns and existential type variables</h2>
 * <p>Every node is deconstructed with a record pattern rather than an {@code instanceof} test
 * followed by accessor calls. Where a node's type parameters all appear in {@code Plan<A>} — as for
 * {@link Pure}, {@link Fail}, {@link Recover} and {@link Labeled} — the pattern can be written
 * inline in the main switch.</p>
 *
 * <p>The other six nodes carry a type parameter that {@code Plan<A>} does not mention: the {@code X}
 * of a {@link Transform}, the {@code Q} of an {@link Invoke}, the seed type of a {@link Hylo}. Those
 * are <em>existential</em> from the fold's point of view and can only be matched through a wildcard.
 * Java's capture conversion is applied per component expression, not once per pattern, so an inline
 * {@code Transform<?, A>(var source, var function, var label)} yields a {@code Plan<CAP#1>} and a
 * {@code Function<CAP#2, ...>} with <em>unrelated</em> captures, and the algebra call is rejected.</p>
 *
 * <p>The remedy is one line of ceremony per node: match the wildcard type, then hand the node to a
 * private generic method whose own type parameter binds the capture once for the whole record. The
 * record pattern then appears inside that method, where the type arguments are ordinary type
 * variables and everything lines up. No casts and no unchecked warnings are needed anywhere in this
 * class.</p>
 */
public final class PlanCata {

    private PlanCata() {
        throw new AssertionError("no instances");
    }

    /**
     * Folds a plan with an interpreter.
     *
     * <p>The traversal is bottom-up and structural: each node's sub-plans are folded first and the
     * interpreter is handed the results. Nodes whose sub-plans depend on a runtime value —
     * {@link Chain}, {@link Recover}, {@link Hylo} — pass their functions through unfolded, and the
     * interpreter re-enters this method once it has a value to apply them to.</p>
     *
     * @param plan    the plan to interpret
     * @param algebra the interpreter
     * @param <F>     witness for the interpreter's carrier
     * @param <A>     the plan's result type
     * @return the interpretation of the whole plan
     */
    public static <F, A> App<F, A> fold(Plan<A> plan, PlanAlgebra<F> algebra) {
        return switch (plan) {
            // Nodes whose type parameters are fully determined by Plan<A>: matched inline.
            case Pure<A>(var value) -> algebra.pure(value);
            case Fail<A>(var error) -> algebra.failed(error);
            case Recover<A>(var source, var handler) -> algebra.recover(fold(source, algebra), handler);
            case Labeled<A>(var name, var inner) -> algebra.labeled(name, fold(inner, algebra));

            // Nodes with existential parameters: the capture is bound by the helper's signature.
            case Invoke<?, A> node -> foldInvoke(node, algebra);
            case Transform<?, A> node -> foldTransform(node, algebra);
            case Combine<?, ?, A> node -> foldCombine(node, algebra);
            case Chain<?, A> node -> foldChain(node, algebra);
            case Fold<?, A> node -> foldFold(node, algebra);
            case Hylo<?, ?, A> node -> foldHylo(node, algebra);
        };
    }

    private static <F, Q, R> App<F, R> foldInvoke(Invoke<Q, R> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Invoke<Q, R>(var endpoint, var request) -> algebra.invoke(endpoint, request);
        };
    }

    private static <F, X, A> App<F, A> foldTransform(Transform<X, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Transform<X, A>(var source, var function, var label) ->
                    algebra.transform(fold(source, algebra), function, label);
        };
    }

    private static <F, X, Y, A> App<F, A> foldCombine(Combine<X, Y, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Combine<X, Y, A>(var left, var right, var combiner, var label) ->
                    algebra.combine(fold(left, algebra), fold(right, algebra), combiner, label);
        };
    }

    private static <F, X, A> App<F, A> foldChain(Chain<X, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Chain<X, A>(var source, var continuation, var probe, var label) ->
                    algebra.chain(fold(source, algebra), continuation, probe, label);
        };
    }

    private static <F, G, A> App<F, A> foldFold(Fold<G, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Fold<G, A>(var source, var functor, var elementAlgebra, var label) ->
                    algebra.fold(fold(source, algebra), functor, elementAlgebra, label);
        };
    }

    private static <F, G, S, A> App<F, A> foldHylo(Hylo<G, S, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Hylo<G, S, A>(var seed, var traversal, var coalgebra, var elementAlgebra, var label) ->
                    algebra.hylo(seed, traversal, coalgebra, elementAlgebra, label);
        };
    }
}
