package io.macroapi.effect;

import java.util.Objects;

/**
 * The bridge from exception-throwing client code into the {@link ApiError} value world.
 *
 * <p>Real HTTP clients signal failure by throwing. Rather than sprinkle try/catch through every
 * endpoint implementation, an implementation may throw this exception and let
 * {@link Eff#async(java.util.concurrent.Executor, String, java.util.concurrent.Callable)} convert
 * it into an {@link Outcome.Failure}. The stack trace is suppressed because the carried error is
 * the payload of interest and these are thrown on expected paths.</p>
 */
public final class ApiFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ApiError error;

    /**
     * Wraps a failure value so it can cross a throwing boundary.
     *
     * @param error the failure to carry
     */
    public ApiFailure(ApiError error) {
        super(Objects.requireNonNull(error, "error").describe(), null, false, false);
        this.error = error;
    }

    /**
     * The carried failure value.
     *
     * @return the wrapped error
     */
    public ApiError error() {
        return error;
    }
}
