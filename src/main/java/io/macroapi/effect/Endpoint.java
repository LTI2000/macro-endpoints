package io.macroapi.effect;

import java.util.Objects;

/**
 * A low-level API call: a {@link Kleisli} arrow that additionally carries a {@link EndpointSpec}.
 *
 * <p>This is the leaf of every composition. Everything above it — macros, higher-level endpoints,
 * whole workflows — is built by combining endpoints inside a {@link io.macroapi.plan.Plan}. Because
 * an endpoint is opaque to the interpreters (they see the spec, never the body), it also serves as
 * the deliberate encapsulation boundary: {@link io.macroapi.macro.Macro#asEndpoint} seals an
 * expanded macro back into one, hiding its internals from callers that should not depend on them.</p>
 *
 * @param <Q> the request type
 * @param <R> the response type
 */
public interface Endpoint<Q, R> extends Kleisli<Q, R> {

    /**
     * The static description of this endpoint.
     *
     * @return the spec, never {@code null}
     */
    EndpointSpec spec();

    /**
     * Builds an endpoint from a spec and an implementation arrow.
     *
     * @param spec           the static description
     * @param implementation the effectful body
     * @param <Q>            the request type
     * @param <R>            the response type
     * @return the assembled endpoint
     */
    static <Q, R> Endpoint<Q, R> of(EndpointSpec spec, Kleisli<Q, R> implementation) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(implementation, "implementation");
        return new Endpoint<>() {
            @Override
            public EndpointSpec spec() {
                return spec;
            }

            @Override
            public Eff<R> run(Q request) {
                return implementation.run(request);
            }

            @Override
            public String toString() {
                return "Endpoint[" + spec.name() + " " + spec.signature() + "]";
            }
        };
    }
}
