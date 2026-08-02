package io.macroapi.effect;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The static description of a low-level API call.
 *
 * <p>Separating the description from the implementation is what makes the whole facility work: an
 * {@code Invoke} node in a plan carries a spec, so an interpreter can name endpoints in
 * documentation, place them in a dependency graph and add up their costs without ever performing a
 * call.</p>
 *
 * @param name           a stable, unique identifier used as the node label in diagrams
 * @param method         the request method
 * @param path           the request path template, for example {@code /customers/{id}}
 * @param tags           free-form classification, for example a bounded context or owning team
 * @param typicalLatency the expected round trip, used by the cost interpreter
 * @param costUnits      an abstract cost (quota, money, load) charged per call
 */
public record EndpointSpec(String name,
                           HttpMethod method,
                           String path,
                           Set<String> tags,
                           Duration typicalLatency,
                           int costUnits) {

    /** The request methods this facility distinguishes. */
    public enum HttpMethod {
        /** Safe, idempotent read. */
        GET,
        /** Non-idempotent write. */
        POST,
        /** Idempotent replace. */
        PUT,
        /** Partial update. */
        PATCH,
        /** Idempotent removal. */
        DELETE
    }

    /**
     * Canonical constructor: validates and defensively copies.
     *
     * @param name           endpoint identifier, must be non-blank
     * @param method         request method
     * @param path           request path template
     * @param tags           classification tags, copied
     * @param typicalLatency expected latency, must not be negative
     * @param costUnits      cost per call, must not be negative
     */
    public EndpointSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(typicalLatency, "typicalLatency");
        if (name.isBlank()) {
            throw new IllegalArgumentException("endpoint name must not be blank");
        }
        if (typicalLatency.isNegative()) {
            throw new IllegalArgumentException("typicalLatency must not be negative: " + typicalLatency);
        }
        if (costUnits < 0) {
            throw new IllegalArgumentException("costUnits must not be negative: " + costUnits);
        }
        tags = Set.copyOf(tags);
    }

    /**
     * Convenience factory for a read endpoint with default cost characteristics.
     *
     * @param name endpoint identifier
     * @param path request path template
     * @param tags classification tags
     * @return a {@code GET} spec with a 50&nbsp;ms typical latency and unit cost
     */
    public static EndpointSpec read(String name, String path, String... tags) {
        return new EndpointSpec(name, HttpMethod.GET, path, Set.of(tags), Duration.ofMillis(50), 1);
    }

    /**
     * Returns a copy of this spec with a different typical latency.
     *
     * @param latency the new expected round trip
     * @return the adjusted spec
     */
    public EndpointSpec withLatency(Duration latency) {
        return new EndpointSpec(name, method, path, tags, latency, costUnits);
    }

    /**
     * Returns a copy of this spec with a different cost.
     *
     * @param units the new per-call cost
     * @return the adjusted spec
     */
    public EndpointSpec withCost(int units) {
        return new EndpointSpec(name, method, path, tags, typicalLatency, units);
    }

    /**
     * A compact one-line rendering such as {@code GET /customers/{id}}.
     *
     * @return the signature line
     */
    public String signature() {
        return method + " " + path;
    }
}
