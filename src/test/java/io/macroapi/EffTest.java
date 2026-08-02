package io.macroapi;

import io.macroapi.effect.ApiError;
import io.macroapi.effect.Eff;
import io.macroapi.effect.Kleisli;
import io.macroapi.effect.Outcome;
import io.macroapi.effect.RetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the effect type's laws and the behaviour the interpreters rely on. */
class EffTest {

    @Test
    @DisplayName("an effect is a reusable recipe, not a one-shot promise")
    void effectsAreDeferredAndRepeatable() {
        AtomicInteger runs = new AtomicInteger();
        Eff<Integer> effect = Eff.of(() ->
                java.util.concurrent.CompletableFuture.completedFuture(Outcome.success(runs.incrementAndGet())));

        assertEquals(0, runs.get(), "constructing an effect must not run it");
        assertEquals(Outcome.success(1), effect.runBlocking());
        assertEquals(Outcome.success(2), effect.runBlocking());
    }

    @Test
    @DisplayName("a failure short-circuits the continuation")
    void failureShortCircuits() {
        AtomicInteger continuations = new AtomicInteger();
        Outcome<String> outcome = Eff.<String>fail(new ApiError.NotFound("thing", "x"))
                .flatMap(value -> {
                    continuations.incrementAndGet();
                    return Eff.succeed(value + "!");
                })
                .runBlocking();

        assertEquals(0, continuations.get());
        assertInstanceOf(Outcome.Failure.class, outcome);
    }

    @Test
    @DisplayName("when both parallel branches fail, both errors are kept")
    void parallelFailuresAggregate() {
        Outcome<String> outcome = Eff.<String>fail(new ApiError.NotFound("a", "1"))
                .zipPar(Eff.<String>fail(new ApiError.Unauthorized("realm")), (left, right) -> left + right)
                .runBlocking();

        ApiError error = outcome.fold(value -> null, failure -> failure);
        ApiError.Aggregate aggregate = assertInstanceOf(ApiError.Aggregate.class, error);
        assertEquals(2, aggregate.causes().size());
    }

    @Test
    @DisplayName("parallel composition really is concurrent")
    void zipParOverlaps() {
        Eff<String> slow = Eff.of(() -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Outcome.success("done");
        }));

        long started = System.nanoTime();
        slow.zipPar(slow, (left, right) -> left + right).runBlocking();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMillis < 220, "two 120 ms branches took " + elapsedMillis + " ms; expected overlap");
    }

    @Test
    @DisplayName("only transient failures are retried, and only up to the limit")
    void retryRespectsPolicy() {
        AtomicInteger attempts = new AtomicInteger();
        Eff<String> flaky = Eff.defer(() -> attempts.incrementAndGet() < 3
                ? Eff.fail(new ApiError.Timeout("slow", Duration.ofMillis(10)))
                : Eff.succeed("recovered"));

        Outcome<String> outcome = new RetryPolicy(3, Duration.ZERO, ApiError::transient_).guard(flaky).runBlocking();

        assertEquals(Outcome.success("recovered"), outcome);
        assertEquals(3, attempts.get());

        AtomicInteger deterministic = new AtomicInteger();
        Eff<String> broken = Eff.defer(() -> {
            deterministic.incrementAndGet();
            return Eff.fail(new ApiError.NotFound("thing", "x"));
        });
        new RetryPolicy(5, Duration.ZERO, ApiError::transient_).guard(broken).runBlocking();
        assertEquals(1, deterministic.get(), "a deterministic failure must not be retried");
    }

    @Test
    @DisplayName("Kleisli composition is associative with identity as its unit")
    void kleisliLaws() {
        Kleisli<Integer, Integer> doubler = value -> Eff.succeed(value * 2);
        Kleisli<Integer, Integer> increment = value -> Eff.succeed(value + 1);
        Kleisli<Integer, String> render = value -> Eff.succeed("=" + value);

        Outcome<String> leftAssociated = doubler.andThen(increment).andThen(render).run(5).runBlocking();
        Outcome<String> rightAssociated = doubler.andThen(increment.andThen(render)).run(5).runBlocking();
        assertEquals(leftAssociated, rightAssociated);
        assertEquals(Outcome.success("=11"), leftAssociated);

        assertEquals(Outcome.success(10), Kleisli.<Integer>identity().andThen(doubler).run(5).runBlocking());
        assertEquals(Outcome.success(10), doubler.andThen(Kleisli.identity()).run(5).runBlocking());
    }

    @Test
    @DisplayName("failures describe themselves, including nested aggregates")
    void errorsDescribeThemselves() {
        ApiError aggregate = new ApiError.Aggregate(List.of(
                new ApiError.NotFound("customer", "c-9"),
                new ApiError.Remote("loyalty.get", 503, "unavailable")));

        String description = aggregate.describe();
        assertTrue(description.contains("not-found"));
        assertTrue(description.contains("503"));
        assertTrue(aggregate.transient_() == false, "a mix of deterministic and transient is not retryable");
    }
}
