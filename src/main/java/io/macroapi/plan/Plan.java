package io.macroapi.plan;

import io.macroapi.effect.ApiError;
import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Functor;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A <em>description</em> of a composed API call that produces a value of type {@code A}.
 *
 * <p>A plan is the reified counterpart of a {@link io.macroapi.effect.Kleisli} arrow. Where an
 * arrow <em>is</em> the composition and can therefore only be run, a plan is an immutable syntax
 * tree <em>describing</em> the composition, and can be interpreted in as many ways as there are
 * {@link PlanAlgebra} implementations. This project ships four — execute, document, graph, cost —
 * and adding a fifth requires no change to any plan.</p>
 *
 * <h2>The node set</h2>
 * <p>The hierarchy is sealed, so a fold over it is total and the compiler enforces that every
 * interpreter handles every node:</p>
 * <table class="striped">
 *   <caption>Plan node summary</caption>
 *   <tr><th>Node</th><th>Role</th></tr>
 *   <tr><td>{@link Pure}</td><td>a constant; no call is made</td></tr>
 *   <tr><td>{@link Fail}</td><td>a constant failure, so recovery handlers can re-raise</td></tr>
 *   <tr><td>{@link Invoke}</td><td>one low-level endpoint call: the only leaf that does I/O</td></tr>
 *   <tr><td>{@link Transform}</td><td>a pure function applied to a result</td></tr>
 *   <tr><td>{@link Combine}</td><td>two <em>independent</em> sub-plans, run concurrently</td></tr>
 *   <tr><td>{@link Chain}</td><td>a <em>dependent</em> sub-plan chosen from a result</td></tr>
 *   <tr><td>{@link Recover}</td><td>an alternative sub-plan chosen from a failure</td></tr>
 *   <tr><td>{@link Labeled}</td><td>a named boundary, typically a macro expansion</td></tr>
 *   <tr><td>{@link Fold}</td><td>a catamorphism applied to a recursive intermediate result</td></tr>
 *   <tr><td>{@link Hylo}</td><td>an effectful unfold immediately consumed by a catamorphism</td></tr>
 * </table>
 *
 * <h2>Why {@code Combine} and {@code Chain} are separate</h2>
 * <p>Both could be expressed with {@code Chain} alone, but the distinction is the single most
 * valuable piece of information in the tree. {@code Combine} states that neither branch depends on
 * the other, which lets the executing interpreter fan them out, lets the cost interpreter take a
 * maximum instead of a sum, and lets the graph interpreter draw them side by side. Prefer
 * {@code Combine} whenever the data flow permits it, and reserve {@code Chain} for genuine
 * dependencies.</p>
 *
 * <h2>Static analysability and the probe</h2>
 * <p>A {@code Chain} holds a function {@code X -> Plan<A>}, so its downstream shape is not
 * determined until a value of {@code X} exists. This is the standard limitation of folding a free
 * monad: the tree beyond a bind is not present in the tree. Rather than pretending otherwise, a
 * {@code Chain} may carry a <em>static probe</em> — a representative input that non-executing
 * interpreters apply to reveal one plausible continuation, clearly marked as conditional in the
 * output. Without a probe, static interpreters report an opaque dynamic frontier.</p>
 *
 * <p>Plans are immutable values; the fluent methods below all return new plans.</p>
 *
 * @param <A> the type this plan produces when executed
 */
public sealed interface Plan<A>
        permits Pure, Fail, Invoke, Transform, Combine, Chain, Recover, Labeled, Fold, Hylo {

    /**
     * Applies a pure function to this plan's result.
     *
     * @param fn    the transformation
     * @param label a short description used in documentation and diagrams
     * @param <B>   the transformed type
     * @return a plan producing the transformed value
     */
    default <B> Plan<B> map(Function<? super A, ? extends B> fn, String label) {
        return new Transform<>(this, fn, label);
    }

    /**
     * Applies a pure function to this plan's result, with a generic label.
     *
     * @param fn  the transformation
     * @param <B> the transformed type
     * @return a plan producing the transformed value
     */
    default <B> Plan<B> map(Function<? super A, ? extends B> fn) {
        return map(fn, "map");
    }

    /**
     * Combines this plan with an <em>independent</em> one; both may run concurrently.
     *
     * @param other   the plan to run alongside, which must not depend on this plan's result
     * @param combine merges the two results
     * @param label   a short description used in documentation and diagrams
     * @param <B>     the other plan's result type
     * @param <C>     the merged result type
     * @return a plan producing the merged value
     */
    default <B, C> Plan<C> combine(Plan<B> other, BiFunction<? super A, ? super B, ? extends C> combine, String label) {
        return new Combine<>(this, other, combine, label);
    }

    /**
     * Combines this plan with an independent one, pairing the results.
     *
     * @param other the plan to run alongside
     * @param <B>   the other plan's result type
     * @return a plan producing both results as a {@link Both}
     */
    default <B> Plan<Both<A, B>> combine(Plan<B> other) {
        return combine(other, Both::new, "combine");
    }

    /**
     * Sequences a <em>dependent</em> plan chosen from this plan's result, with no static probe.
     *
     * <p>Static interpreters will not be able to see past this point; prefer
     * {@link #flatMap(Function, Object, String)} where a representative input exists.</p>
     *
     * @param continuation chooses the next plan
     * @param label        a short description used in documentation and diagrams
     * @param <B>          the continuation's result type
     * @return a plan producing the continuation's value
     */
    default <B> Plan<B> flatMap(Function<? super A, Plan<B>> continuation, String label) {
        return new Chain<>(this, continuation, Optional.empty(), label);
    }

    /**
     * Sequences a dependent plan, supplying a representative input so that non-executing
     * interpreters can explore one branch of the continuation.
     *
     * <p>The probe is never used during execution and therefore never affects results; it only
     * makes documentation and dependency diagrams more complete.</p>
     *
     * @param continuation chooses the next plan
     * @param staticProbe  a representative value for analysis only
     * @param label        a short description used in documentation and diagrams
     * @param <B>          the continuation's result type
     * @return a plan producing the continuation's value
     */
    default <B> Plan<B> flatMap(Function<? super A, Plan<B>> continuation, A staticProbe, String label) {
        return new Chain<>(this, continuation, Optional.of(staticProbe), label);
    }

    /**
     * Supplies an alternative plan to use if this one fails.
     *
     * @param handler chooses the recovery plan from the failure; it may re-raise with
     *                {@link Plans#failed(ApiError)}
     * @return a guarded plan
     */
    default Plan<A> recoverWith(Function<? super ApiError, Plan<A>> handler) {
        return new Recover<>(this, handler);
    }

    /**
     * Marks this plan as a named unit, drawn as a cluster in diagrams and as a heading in
     * documentation.
     *
     * @param name the boundary name
     * @return the labelled plan
     */
    default Plan<A> named(String name) {
        return new Labeled<>(name, this);
    }

    /**
     * Applies a catamorphism to a recursive intermediate result produced by this plan.
     *
     * <p>Only valid where {@code A} is a {@link Fix} point; the compiler cannot express that
     * constraint on a default method, so this convenience lives on {@link Plans#fold} instead. See
     * {@link Fold}.</p>
     *
     * @param functor the functor instance for the intermediate shape
     * @param algebra the rule collapsing one layer
     * @param label   a short description used in documentation and diagrams
     * @param <G>     witness for the intermediate shape
     * @param <B>     the folded result type
     * @return a plan producing the folded value
     * @throws ClassCastException never; retained only to document that the cast below is checked by
     *                            construction at the call sites in {@link Plans}
     */
    default <G, B> Plan<B> foldWith(Functor<G> functor, Algebra<G, B> algebra, String label) {
        @SuppressWarnings("unchecked")
        Plan<Fix<G>> recursive = (Plan<Fix<G>>) this;
        return new Fold<>(recursive, functor, algebra, label);
    }

    /**
     * A pair of independently obtained results.
     *
     * @param left  the first plan's result
     * @param right the second plan's result
     * @param <L>   the first result type
     * @param <R>   the second result type
     */
    record Both<L, R>(L left, R right) {
    }
}
