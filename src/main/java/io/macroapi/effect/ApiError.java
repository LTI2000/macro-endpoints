package io.macroapi.effect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The closed set of failures an API call can report.
 *
 * <p>Modelling failure as a sealed hierarchy of records rather than as an exception hierarchy has
 * two benefits here. Failures become ordinary values that a plan can carry, combine and recover
 * from without unwinding the stack, and every consumer can dispatch on them exhaustively with
 * record patterns — the compiler will flag any switch that forgets a case when a new failure kind
 * is added.</p>
 */
public sealed interface ApiError {

    /**
     * The requested resource does not exist.
     *
     * @param resource the resource type that was queried, for example {@code "customer"}
     * @param key      the identifier that produced no result
     */
    record NotFound(String resource, String key) implements ApiError {
        /**
         * Canonical constructor.
         *
         * @param resource the resource type, non-null
         * @param key      the missing identifier, non-null
         */
        public NotFound {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * The caller's credentials were absent, expired or insufficient.
     *
     * @param realm the security realm that rejected the call
     */
    record Unauthorized(String realm) implements ApiError {
        /**
         * Canonical constructor.
         *
         * @param realm the rejecting realm, non-null
         */
        public Unauthorized {
            Objects.requireNonNull(realm, "realm");
        }
    }

    /**
     * The call exceeded its deadline.
     *
     * @param endpoint the endpoint that timed out
     * @param elapsed  how long the caller waited before giving up
     */
    record Timeout(String endpoint, Duration elapsed) implements ApiError {
        /**
         * Canonical constructor.
         *
         * @param endpoint the timed-out endpoint, non-null
         * @param elapsed  the time waited, non-null
         */
        public Timeout {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(elapsed, "elapsed");
        }
    }

    /**
     * The remote side answered, but with a failure status.
     *
     * @param endpoint the endpoint that was called
     * @param status   the protocol status code returned
     * @param detail   a short human-readable explanation
     */
    record Remote(String endpoint, int status, String detail) implements ApiError {
        /**
         * Canonical constructor.
         *
         * @param endpoint the called endpoint, non-null
         * @param status   the returned status code
         * @param detail   the explanation, non-null
         */
        public Remote {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * The call never reached the remote side, or the response could not be read.
     *
     * @param endpoint the endpoint that was being called
     * @param message  the underlying transport diagnostic
     */
    record Transport(String endpoint, String message) implements ApiError {
        /**
         * Canonical constructor.
         *
         * @param endpoint the called endpoint, non-null
         * @param message  the transport diagnostic, non-null
         */
        public Transport {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Several independent branches failed at once.
     *
     * <p>Produced by parallel combination, where both sides may fail before either result is
     * needed; keeping every cause avoids the arbitrary "first failure wins" behaviour that makes
     * fan-out bugs hard to diagnose.</p>
     *
     * @param causes the individual failures, in branch order
     */
    record Aggregate(List<ApiError> causes) implements ApiError {
        /**
         * Canonical constructor; defensively copies the cause list.
         *
         * @param causes the collected failures
         */
        public Aggregate {
            causes = List.copyOf(causes);
        }
    }

    /**
     * A placeholder failure fed to recovery handlers by <em>static</em> interpreters.
     *
     * <p>Documentation and dependency analysis need to look inside a recovery handler without ever
     * running the plan. Since a handler is a function from a failure to a plan, the analysis has to
     * supply some failure; this variant marks that value as fabricated so it can never be mistaken
     * for a real incident in a log.</p>
     *
     * @param reason why the synthetic failure was created
     */
    record Synthetic(String reason) implements ApiError {
        /**
         * Canonical constructor.
         *
         * @param reason the provenance of the fabricated failure, non-null
         */
        public Synthetic {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Renders the failure as a single diagnostic line.
     *
     * @return a human-readable description
     */
    default String describe() {
        return switch (this) {
            case NotFound(String resource, String key) -> "not-found: " + resource + "[" + key + "]";
            case Unauthorized(String realm) -> "unauthorized: realm " + realm;
            case Timeout(String endpoint, Duration elapsed) -> "timeout: " + endpoint + " after " + elapsed.toMillis() + "ms";
            case Remote(String endpoint, int status, String detail) -> "remote: " + endpoint + " -> " + status + " " + detail;
            case Transport(String endpoint, String message) -> "transport: " + endpoint + " -> " + message;
            case Aggregate(List<ApiError> causes) -> causes.stream().map(ApiError::describe)
                    .reduce((left, right) -> left + " | " + right).map(joined -> "aggregate(" + joined + ")").orElse("aggregate()");
            case Synthetic(String reason) -> "synthetic: " + reason;
        };
    }

    /**
     * Whether retrying the same call unchanged could plausibly succeed.
     *
     * <p>Used as the default predicate of {@link RetryPolicy}. Timeouts and transport faults are
     * transient; a 5xx response is worth one more attempt, while 4xx responses, missing resources
     * and authorisation failures are deterministic and retrying them only adds load.</p>
     *
     * @return {@code true} when a retry is worth attempting
     */
    default boolean transient_() {
        return switch (this) {
            case Timeout ignored -> true;
            case Transport ignored -> true;
            case Remote(String ignoredEndpoint, int status, String ignoredDetail) -> status >= 500;
            case Aggregate(List<ApiError> causes) -> causes.stream().allMatch(ApiError::transient_);
            case NotFound ignored -> false;
            case Unauthorized ignored -> false;
            case Synthetic ignored -> false;
        };
    }
}
