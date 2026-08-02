package io.macroapi.macro;

import io.macroapi.effect.Eff;
import io.macroapi.effect.Endpoint;
import io.macroapi.interpret.ApiRuntime;
import io.macroapi.interpret.CallGraph;
import io.macroapi.interpret.Cost;
import io.macroapi.interpret.Interpreters;
import io.macroapi.interpret.Outline;
import io.macroapi.plan.Plan;
import io.macroapi.plan.Plans;

import java.util.Objects;
import java.util.function.Function;

/**
 * A named, reusable composition of API calls: a function from a request to a {@link Plan}.
 *
 * <h2>Why expansion, not invocation</h2>
 * <p>The obvious way to build a higher-level endpoint from lower-level ones is to write a method
 * that calls them and returns the result. That works, and it is exactly what this class avoids. Such
 * a method is opaque: nothing can discover which endpoints it will call, in what order, or with what
 * concurrency, so its documentation is written by hand and drifts, its cost is unknown until
 * production, and a dependency diagram has to be maintained separately from the code.</p>
 *
 * <p>A macro instead <em>expands</em> into plan structure, in the sense the word has in a language
 * with a macro system. {@link #expand} does not call anything; it returns a tree, wrapped in a
 * boundary marker naming the macro. Composing macros therefore composes trees, and the composite is
 * every bit as inspectable as its parts — the fold sees straight through the abstraction, so a macro
 * built from three macros still yields one graph naming all the endpoints it will reach.</p>
 *
 * <p>Expansion is a purely syntactic step and performs no I/O, so it is cheap and can be repeated:
 * a plan is usually expanded once for execution and again, independently, at build time to produce
 * documentation.</p>
 *
 * <h2>Sealing the boundary</h2>
 * <p>Transparency is not always wanted. {@link #asEndpoint} converts a macro into an ordinary
 * {@link Endpoint}, which interpreters treat as a single opaque leaf. That is the right choice at a
 * genuine module or team boundary, where callers should depend on the contract rather than on the
 * calls behind it — and because the result is an endpoint, it composes with everything else exactly
 * as a low-level call does. Transparent composition and opaque publication both remain available;
 * the choice is explicit rather than accidental.</p>
 *
 * @param spec      the published contract
 * @param expansion the rule turning a request into plan structure
 * @param <Q>       the request type
 * @param <R>       the response type
 */
public record Macro<Q, R>(MacroSpec spec, Function<Q, Plan<R>> expansion) {

    /**
     * Canonical constructor.
     *
     * @param spec      the contract, non-null
     * @param expansion the expansion rule, non-null
     */
    public Macro {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(expansion, "expansion");
    }

    /**
     * Convenience factory.
     *
     * @param spec      the contract
     * @param expansion the expansion rule
     * @param <Q>       the request type
     * @param <R>       the response type
     * @return the macro
     */
    public static <Q, R> Macro<Q, R> of(MacroSpec spec, Function<Q, Plan<R>> expansion) {
        return new Macro<>(spec, expansion);
    }

    /**
     * Expands the macro into plan structure for a given request.
     *
     * <p>The result is wrapped in a {@link io.macroapi.plan.Labeled} boundary carrying the macro's
     * name, so that after expansion the tree still records where each region came from — that is
     * what lets diagrams draw a box per macro and traces emit a span per macro.</p>
     *
     * @param request the request to expand for
     * @return the plan, with no call yet made
     */
    public Plan<R> expand(Q request) {
        return Plans.named(spec.name(), expansion.apply(request));
    }

    /**
     * Seals the macro into an opaque endpoint, hiding its internal calls from interpreters.
     *
     * <p>The derived {@link io.macroapi.effect.EndpointSpec} carries a cost estimate taken from a
     * sample expansion, so callers that compose the sealed endpoint still get a meaningful budget
     * even though they can no longer see the individual calls.</p>
     *
     * @param runtime      executes the expansion when the endpoint is called
     * @param costExemplar a representative request, used once to estimate cost; it is expanded but
     *                     never executed
     * @return an endpoint delegating to this macro
     */
    public Endpoint<Q, R> asEndpoint(ApiRuntime runtime, Q costExemplar) {
        Objects.requireNonNull(runtime, "runtime");
        Cost estimate = estimateCost(costExemplar);
        return Endpoint.of(spec.asEndpointSpec(estimate.latency(), estimate.units()),
                request -> Eff.defer(() -> runtime.effect(expand(request))));
    }

    /**
     * Documents the macro by folding a sample expansion.
     *
     * @param exemplar a representative request; it is expanded but never executed
     * @return the structural outline
     */
    public Outline describe(Q exemplar) {
        return Interpreters.describe(expand(exemplar));
    }

    /**
     * Derives the macro's call dependency graph from a sample expansion.
     *
     * @param exemplar a representative request; it is expanded but never executed
     * @return the graph, renderable as DOT or PlantUML
     */
    public CallGraph graph(Q exemplar) {
        return Interpreters.graph(expand(exemplar));
    }

    /**
     * Estimates the macro's execution cost from a sample expansion.
     *
     * @param exemplar a representative request; it is expanded but never executed
     * @return the estimate
     */
    public Cost estimateCost(Q exemplar) {
        return Interpreters.estimate(expand(exemplar));
    }
}
