package io.macroapi.effect;

import io.macroapi.hkt.App;
import io.macroapi.hkt.Applicative;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A deferred, asynchronous computation that eventually yields an {@link Outcome}.
 *
 * <p>{@code Eff} is the effect type every API call is expressed in, and the target of the executing
 * interpreter. Three properties matter for the rest of the design:</p>
 *
 * <dl>
 *   <dt>Deferred</dt>
 *   <dd>The body is a {@link Supplier} of a future, not a future. Constructing an {@code Eff} — and
 *       therefore constructing a whole plan — performs no work, which is what allows the same plan
 *       value to be executed, documented, costed and graphed. Work starts only at {@link #run()},
 *       and starts afresh on each call, so an {@code Eff} is a reusable recipe rather than a
 *       one-shot promise.</dd>
 *   <dt>Typed failure</dt>
 *   <dd>Failures are carried in the {@link Outcome}, not as an exceptionally-completed future, so
 *       {@link #flatMap} short-circuits on a failure without any exception machinery.</dd>
 *   <dt>Parallel by construction</dt>
 *   <dd>{@link #zipPar} starts both sides before awaiting either, and is the basis of the
 *       {@link #applicative()} instance, so independent branches of a plan fan out automatically.</dd>
 * </dl>
 *
 * <p>Instances are immutable and safe to share; the concurrency behaviour of a call is decided by
 * the {@link Executor} supplied when the underlying endpoint is built.</p>
 *
 * @param <A> the type produced on success
 */
public final class Eff<A> implements App<Eff.Witness, A> {

    /**
     * Uninhabited type-level tag standing for the {@code Eff} type constructor in
     * {@link App}-encoded signatures.
     */
    public static final class Witness {
        private Witness() {
            throw new AssertionError("no instances");
        }
    }

    private final Supplier<CompletableFuture<Outcome<A>>> body;

    private Eff(Supplier<CompletableFuture<Outcome<A>>> body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    /**
     * Builds an effect from a thunk that starts the work.
     *
     * <p>The supplier must start a <em>fresh</em> unit of work on every invocation; returning a
     * cached future would make the effect a one-shot value and break replay.</p>
     *
     * @param body the work starter
     * @param <A>  the success type
     * @return the deferred effect
     */
    public static <A> Eff<A> of(Supplier<CompletableFuture<Outcome<A>>> body) {
        return new Eff<>(body);
    }

    /**
     * An effect that immediately succeeds with the given value.
     *
     * @param value the value to produce
     * @param <A>   the success type
     * @return the completed effect
     */
    public static <A> Eff<A> succeed(A value) {
        return of(() -> CompletableFuture.completedFuture(Outcome.success(value)));
    }

    /**
     * An effect that immediately fails.
     *
     * @param error the failure to produce
     * @param <A>   the success type that was expected
     * @return the failed effect
     */
    public static <A> Eff<A> fail(ApiError error) {
        Objects.requireNonNull(error, "error");
        return of(() -> CompletableFuture.completedFuture(Outcome.failure(error)));
    }

    /**
     * Postpones the construction of an effect until it is run.
     *
     * <p>Needed wherever building the effect is itself expensive or recursive; without it a
     * self-referential definition would diverge at construction time.</p>
     *
     * @param thunk produces the effect when the outer effect is run
     * @param <A>   the success type
     * @return an effect equivalent to the one the thunk returns
     */
    public static <A> Eff<A> defer(Supplier<Eff<A>> thunk) {
        Objects.requireNonNull(thunk, "thunk");
        return of(() -> thunk.get().run());
    }

    /**
     * Adapts blocking, possibly throwing client code into an effect.
     *
     * <p>This is the intended construction point for real endpoint implementations. The body runs
     * on the supplied executor — a virtual-thread executor is a good default, since the body is
     * expected to block on I/O. Any {@link ApiFailure} is unwrapped into its carried error, and any
     * other exception becomes an {@link ApiError.Transport} attributed to {@code endpointName}, so
     * no exception escapes into the future's exceptional channel.</p>
     *
     * @param executor     where the body runs
     * @param endpointName used to attribute unexpected exceptions
     * @param body         the blocking call
     * @param <A>          the success type
     * @return the deferred effect
     */
    public static <A> Eff<A> async(Executor executor, String endpointName, Callable<A> body) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(endpointName, "endpointName");
        Objects.requireNonNull(body, "body");
        return of(() -> CompletableFuture.supplyAsync(() -> {
            try {
                return Outcome.success(body.call());
            } catch (ApiFailure failure) {
                return Outcome.<A>failure(failure.error());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Outcome.<A>failure(new ApiError.Transport(endpointName, "interrupted"));
            } catch (Exception unexpected) {
                return Outcome.<A>failure(new ApiError.Transport(endpointName, String.valueOf(unexpected)));
            }
        }, executor));
    }

    /**
     * Starts the computation and returns its future outcome.
     *
     * @return a fresh future for this run
     */
    public CompletableFuture<Outcome<A>> run() {
        return body.get();
    }

    /**
     * Starts the computation and blocks until it completes.
     *
     * @return the outcome of this run
     */
    public Outcome<A> runBlocking() {
        return run().join();
    }

    /**
     * Transforms a successful result; a failure passes through untouched.
     *
     * @param fn  the transformation
     * @param <B> the new success type
     * @return the transformed effect
     */
    public <B> Eff<B> map(Function<? super A, ? extends B> fn) {
        Objects.requireNonNull(fn, "fn");
        return of(() -> run().thenApply(outcome -> outcome.map(fn)));
    }

    /**
     * Sequences a dependent effect: {@code fn} sees the value and decides what to do next.
     *
     * <p>This is the monadic bind that makes {@code Eff} the target of a Kleisli category. A
     * failure short-circuits: {@code fn} is never invoked and the error propagates.</p>
     *
     * @param fn  produces the next effect from this one's value
     * @param <B> the success type of the continuation
     * @return the composed effect
     */
    public <B> Eff<B> flatMap(Function<? super A, ? extends Eff<B>> fn) {
        Objects.requireNonNull(fn, "fn");
        return of(() -> run().thenCompose(outcome -> switch (outcome) {
            case Outcome.Success<A>(A value) -> fn.apply(value).run();
            case Outcome.Failure<A>(ApiError error) -> CompletableFuture.completedFuture(Outcome.<B>failure(error));
        }));
    }

    /**
     * Runs this effect and {@code other} concurrently and combines their results.
     *
     * <p>Both sides are started before either is awaited, so the elapsed time is the maximum rather
     * than the sum. If both fail the errors are kept together in an {@link ApiError.Aggregate}
     * rather than silently discarding one.</p>
     *
     * @param other   the independent effect to run alongside
     * @param combine merges the two successful results
     * @param <B>     the other effect's success type
     * @param <C>     the combined success type
     * @return an effect producing the combination
     */
    public <B, C> Eff<C> zipPar(Eff<B> other, BiFunction<? super A, ? super B, ? extends C> combine) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(combine, "combine");
        return of(() -> {
            CompletableFuture<Outcome<A>> left = this.run();
            CompletableFuture<Outcome<B>> right = other.run();
            return left.thenCombine(right, (leftOutcome, rightOutcome) -> switch (leftOutcome) {
                case Outcome.Success<A>(A leftValue) -> switch (rightOutcome) {
                    case Outcome.Success<B>(B rightValue) -> Outcome.<C>success(combine.apply(leftValue, rightValue));
                    case Outcome.Failure<B>(ApiError rightError) -> Outcome.<C>failure(rightError);
                };
                case Outcome.Failure<A>(ApiError leftError) -> switch (rightOutcome) {
                    case Outcome.Success<B> ignored -> Outcome.<C>failure(leftError);
                    case Outcome.Failure<B>(ApiError rightError) ->
                            Outcome.<C>failure(new ApiError.Aggregate(List.of(leftError, rightError)));
                };
            });
        });
    }

    /**
     * Substitutes an alternative effect when this one fails.
     *
     * @param handler chooses the replacement effect from the failure
     * @return an effect that never exposes the original failure unless the handler re-raises it
     */
    public Eff<A> recoverWith(Function<? super ApiError, ? extends Eff<A>> handler) {
        Objects.requireNonNull(handler, "handler");
        return of(() -> run().thenCompose(outcome -> switch (outcome) {
            case Outcome.Success<A> success -> CompletableFuture.completedFuture(success);
            case Outcome.Failure<A>(ApiError error) -> handler.apply(error).run();
        }));
    }

    /**
     * Exposes the outcome as an ordinary value so that both cases can be inspected downstream.
     *
     * @return an effect that always succeeds, carrying this effect's outcome
     */
    public Eff<Outcome<A>> attempt() {
        return of(() -> run().thenApply(Outcome::success));
    }

    /**
     * Attaches an observer that is notified with the elapsed wall time and the outcome.
     *
     * <p>Purely a side channel for tracing: the observed effect behaves exactly as before, and an
     * exception thrown by the observer is swallowed so instrumentation can never change program
     * behaviour.</p>
     *
     * @param observer receives the duration and outcome of each run
     * @return an instrumented effect
     */
    public Eff<A> observed(BiConsumer<Duration, Outcome<A>> observer) {
        Objects.requireNonNull(observer, "observer");
        return of(() -> {
            Instant started = Instant.now();
            return run().thenApply(outcome -> {
                try {
                    observer.accept(Duration.between(started, Instant.now()), outcome);
                } catch (RuntimeException ignored) {
                    // Instrumentation must never affect the observed computation.
                }
                return outcome;
            });
        });
    }

    /**
     * Recovers the concrete type from its {@link App} encoding.
     *
     * <p>The cast cannot fail: {@link Witness} is uninstantiable, so {@code Eff} is the only
     * implementation of {@code App<Eff.Witness, A>} that can exist.</p>
     *
     * @param app the encoded effect
     * @param <A> the success type
     * @return the same value, statically typed as {@code Eff}
     */
    @SuppressWarnings("unchecked")
    public static <A> Eff<A> narrow(App<Witness, A> app) {
        return (Eff<A>) app;
    }

    private static final Applicative<Witness> APPLICATIVE = new Applicative<>() {
        @Override
        public <A> App<Witness, A> pure(A value) {
            return succeed(value);
        }

        @Override
        public <A, B> App<Witness, B> map(App<Witness, A> fa, Function<? super A, ? extends B> fn) {
            return narrow(fa).map(fn);
        }

        @Override
        public <A, B, C> App<Witness, C> map2(App<Witness, A> fa, App<Witness, B> fb,
                                              BiFunction<? super A, ? super B, ? extends C> fn) {
            return narrow(fa).zipPar(narrow(fb), fn);
        }
    };

    /**
     * The applicative instance, whose {@code map2} is the parallel {@link #zipPar}.
     *
     * <p>Handing this to {@link io.macroapi.hkt.Traverse#traverse} is what makes sibling branches of
     * an effectful unfold — for example the children of a category node — execute concurrently.</p>
     *
     * @return the shared applicative instance
     */
    public static Applicative<Witness> applicative() {
        return APPLICATIVE;
    }
}
