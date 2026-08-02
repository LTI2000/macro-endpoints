package io.macroapi.plan;

import io.macroapi.effect.Endpoint;

import java.util.Objects;

/**
 * A single low-level API call: the only node that performs I/O.
 *
 * <p>Because the node holds the {@link Endpoint} rather than an anonymous function, its
 * {@link io.macroapi.effect.EndpointSpec} is available to every interpreter. That is what allows a
 * dependency diagram to be derived from a plan without executing it.</p>
 *
 * @param endpoint the endpoint to call
 * @param request  the request value to send
 * @param <Q>      the request type
 * @param <R>      the response type
 */
public record Invoke<Q, R>(Endpoint<Q, R> endpoint, Q request) implements Plan<R> {

    /**
     * Canonical constructor.
     *
     * @param endpoint the endpoint, non-null
     * @param request  the request value
     */
    public Invoke {
        Objects.requireNonNull(endpoint, "endpoint");
    }
}
