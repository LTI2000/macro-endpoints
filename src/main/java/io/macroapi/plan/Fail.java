package io.macroapi.plan;

import io.macroapi.effect.ApiError;

import java.util.Objects;

/**
 * A plan that always fails with a fixed error.
 *
 * <p>Its main use is inside a {@link Recover} handler: having inspected the failure, a handler that
 * decides not to recover returns this node to re-raise, which keeps recovery total without needing
 * an escape hatch that throws.</p>
 *
 * @param error the failure to produce
 * @param <A>   the type this plan would have produced
 */
public record Fail<A>(ApiError error) implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param error the failure to produce, non-null
     */
    public Fail {
        Objects.requireNonNull(error, "error");
    }
}
