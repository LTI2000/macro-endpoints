package io.macroapi.plan;

import io.macroapi.hkt.Higher;

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

    /** Not instantiable; this class is a holder for the static fold. */
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
    public static <F, A> Higher<F, A> fold(Plan<A> plan, PlanAlgebra<F> algebra) {
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

    /**
     * Binds the request type of an {@link Invoke} so its endpoint and request line up, then hands
     * the leaf to the algebra. Invoke has no sub-plans, so this is where the recursion bottoms out.
     *
     * @param node    the invoke node
     * @param algebra the interpreter
     * @param <F>     witness for the interpreter's carrier
     * @param <Q>     the request type
     * @param <R>     the response type
     * @return the interpretation of the node
     */
    private static <F, Q, R> Higher<F, R> foldInvoke(Invoke<Q, R> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Invoke<Q, R>(var endpoint, var request) -> algebra.invoke(endpoint, request);
        };
    }

    /**
     * Binds the source type {@code X} of a {@link Transform} so the folded source and the mapping
     * function share one capture, then folds the source and applies the algebra.
     *
     * @param node    the transform node
     * @param algebra the interpreter
     * @param <F>     witness for the interpreter's carrier
     * @param <X>     the source result type consumed by the function
     * @param <A>     the transformed result type
     * @return the interpretation of the node
     */
    private static <F, X, A> Higher<F, A> foldTransform(Transform<X, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Transform<X, A>(var source, var function, var label) ->
                    algebra.transform(fold(source, algebra), function, label);
        };
    }

    /**
     * Binds the two operand types {@code X} and {@code Y} of a {@link Combine} so both folded
     * branches and the combining function agree, then folds both sides and applies the algebra.
     *
     * @param node    the combine node
     * @param algebra the interpreter
     * @param <F>     witness for the interpreter's carrier
     * @param <X>     the left operand result type
     * @param <Y>     the right operand result type
     * @param <A>     the combined result type
     * @return the interpretation of the node
     */
    private static <F, X, Y, A> Higher<F, A> foldCombine(Combine<X, Y, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Combine<X, Y, A>(var left, var right, var combiner, var label) ->
                    algebra.combine(fold(left, algebra), fold(right, algebra), combiner, label);
        };
    }

    /**
     * Binds the intermediate type {@code X} of a {@link Chain} so the folded source and the
     * runtime-dependent continuation share one capture. The continuation is passed through
     * unfolded; the interpreter re-enters {@link #fold} once it has a value to feed it.
     *
     * @param node    the chain node
     * @param algebra the interpreter
     * @param <F>     witness for the interpreter's carrier
     * @param <X>     the source result type consumed by the continuation
     * @param <A>     the chained result type
     * @return the interpretation of the node
     */
    private static <F, X, A> Higher<F, A> foldChain(Chain<X, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Chain<X, A>(var source, var continuation, var probe, var label) ->
                    algebra.chain(fold(source, algebra), continuation, probe, label);
        };
    }

    /**
     * Binds the structure functor {@code G} of a {@link Fold} so the folded source, the functor
     * instance and the element algebra agree, then folds the source and applies the algebra.
     *
     * @param node    the fold node
     * @param algebra the interpreter
     * @param <F>     witness for the interpreter's carrier
     * @param <G>     witness for the structure's pattern functor
     * @param <A>     the reduced result type
     * @return the interpretation of the node
     */
    private static <F, G, A> Higher<F, A> foldFold(Fold<G, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Fold<G, A>(var source, var functor, var elementAlgebra, var label) ->
                    algebra.fold(fold(source, algebra), functor, elementAlgebra, label);
        };
    }

    /**
     * Binds the structure functor {@code G} and the seed type {@code S} of a {@link Hylo} so the
     * seed, coalgebra, traversal and element algebra all agree. A hylomorphism has no sub-plan to
     * fold; its seed and functions are handed straight to the algebra, which drives the unfold.
     *
     * @param node    the hylomorphism node
     * @param algebra the interpreter
     * @param <F>     witness for the interpreter's carrier
     * @param <G>     witness for the structure's pattern functor
     * @param <S>     the seed type the coalgebra unfolds from
     * @param <A>     the produced result type
     * @return the interpretation of the node
     */
    private static <F, G, S, A> Higher<F, A> foldHylo(Hylo<G, S, A> node, PlanAlgebra<F> algebra) {
        return switch (node) {
            case Hylo<G, S, A>(var seed, var traversal, var coalgebra, var elementAlgebra, var label) ->
                    algebra.hylo(seed, traversal, coalgebra, elementAlgebra, label);
        };
    }
}
