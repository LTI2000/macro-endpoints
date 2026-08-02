package io.macroapi.plan;

import io.macroapi.effect.ApiError;

import java.util.Objects;
import java.util.function.Function;

/**
 * An alternative sub-plan selected from a failure.
 *
 * <p>The handler receives the {@link ApiError} as a value and typically dispatches on it with
 * record patterns — substituting a default for a missing resource, falling back to a cache on a
 * timeout, and re-raising anything else with {@link Plans#failed(ApiError)}.</p>
 *
 * @param source  the plan that might fail
 * @param handler chooses the recovery plan
 * @param <A>     the result type, identical on both paths
 */
public record Recover<A>(Plan<A> source, Function<? super ApiError, Plan<A>> handler) implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param source  guarded plan, non-null
     * @param handler recovery function, non-null
     */
    public Recover {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(handler, "handler");
    }
}
