package io.macroapi.effect;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A retry rule applied by the executing interpreter around every {@code Invoke} node.
 *
 * <p>Retries live in the interpreter rather than in plan structure on purpose. Whether to retry is
 * an operational decision that varies by environment; keeping it out of the plan means the same
 * plan can be replayed aggressively in a batch job and conservatively behind a user request, and
 * that documentation and dependency diagrams are not cluttered with attempts.</p>
 *
 * @param maxAttempts total attempts including the first, at least one
 * @param backoff     fixed pause between attempts
 * @param retryable   decides, per failure, whether another attempt is worthwhile
 */
public record RetryPolicy(int maxAttempts, Duration backoff, Predicate<ApiError> retryable) {

    /**
     * Canonical constructor.
     *
     * @param maxAttempts total attempts, must be at least one
     * @param backoff     pause between attempts, must not be negative
     * @param retryable   failure predicate
     */
    public RetryPolicy {
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(retryable, "retryable");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        if (backoff.isNegative()) {
            throw new IllegalArgumentException("backoff must not be negative: " + backoff);
        }
    }

    /**
     * A policy that never retries.
     *
     * @return a single-attempt policy
     */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, error -> false);
    }

    /**
     * A policy of three attempts, 20&nbsp;ms apart, for failures classified as transient by
     * {@link ApiError#transient_()}.
     *
     * @return the default policy
     */
    public static RetryPolicy standard() {
        return new RetryPolicy(3, Duration.ofMillis(20), ApiError::transient_);
    }

    /**
     * Wraps an effect so that failing attempts are repeated according to this policy.
     *
     * <p>The effect must be safely repeatable, which holds for {@code Eff} by construction since it
     * is a deferred recipe rather than a running computation. The pause between attempts is
     * implemented by sleeping the completing thread; on a virtual-thread executor that is cheap,
     * but note that it does occupy the carrier of a platform thread pool.</p>
     *
     * @param action the effect to protect
     * @param <A>    the success type
     * @return the retrying effect
     */
    public <A> Eff<A> guard(Eff<A> action) {
        Objects.requireNonNull(action, "action");
        return attempt(action, 1);
    }

    private <A> Eff<A> attempt(Eff<A> action, int attemptNumber) {
        if (attemptNumber >= maxAttempts) {
            return action;
        }
        return action.recoverWith(error -> {
            if (!retryable.test(error)) {
                return Eff.fail(error);
            }
            return Eff.defer(() -> {
                sleep();
                return attempt(action, attemptNumber + 1);
            });
        });
    }

    private void sleep() {
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
