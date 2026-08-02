package io.macroapi.interpret;

import io.macroapi.effect.Eff;
import io.macroapi.effect.Outcome;
import io.macroapi.effect.RetryPolicy;
import io.macroapi.plan.Plan;
import io.macroapi.plan.PlanCata;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The execution context for plans: retry policy, trace sink, and the entry point that turns a
 * {@link Plan} into a running computation.
 *
 * <p>Operational policy lives here rather than in plan structure, which is what allows the same
 * plan value to be run under different regimes — aggressive retries in a batch job, none in a
 * latency-sensitive request path — without rewriting the composition or perturbing its
 * documentation.</p>
 *
 * <p>Instances are thread-safe and intended to be long-lived and shared.</p>
 */
public final class ApiRuntime {

    private final RetryPolicy retryPolicy;
    private final Consumer<TraceEvent> traceSink;

    /**
     * Creates a runtime.
     *
     * @param retryPolicy applied around every endpoint call
     * @param traceSink   receives one event per endpoint call and per named boundary; it may be
     *                    called concurrently from several threads
     */
    public ApiRuntime(RetryPolicy retryPolicy, Consumer<TraceEvent> traceSink) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
    }

    /**
     * A runtime with the standard retry policy that discards trace events.
     *
     * @return the default runtime
     */
    public static ApiRuntime standard() {
        return new ApiRuntime(RetryPolicy.standard(), event -> {
        });
    }

    /**
     * A runtime that additionally records every trace event for later inspection.
     *
     * <p>Useful in tests and demonstrations, where the sequence of calls a macro produced is itself
     * the thing being asserted on.</p>
     *
     * @param retryPolicy the policy to apply
     * @return the runtime paired with its recording
     */
    public static Recording recording(RetryPolicy retryPolicy) {
        ConcurrentLinkedQueue<TraceEvent> events = new ConcurrentLinkedQueue<>();
        return new Recording(new ApiRuntime(retryPolicy, events::add), events);
    }

    /**
     * The retry policy applied around endpoint calls.
     *
     * @return the policy
     */
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * Publishes a trace event, ignoring any failure of the sink.
     *
     * @param event the event to publish
     */
    public void trace(TraceEvent event) {
        try {
            traceSink.accept(event);
        } catch (RuntimeException ignored) {
            // Tracing must never influence execution.
        }
    }

    /**
     * Interprets a plan into a deferred effect. No call is made until the effect is run.
     *
     * @param plan the plan to interpret
     * @param <A>  the result type
     * @return the effect that will execute the plan
     */
    public <A> Eff<A> effect(Plan<A> plan) {
        return Eff.narrow(PlanCata.fold(plan, new ExecutionAlgebra(this)));
    }

    /**
     * Interprets and runs a plan, blocking until it completes.
     *
     * @param plan the plan to run
     * @param <A>  the result type
     * @return the outcome
     */
    public <A> Outcome<A> execute(Plan<A> plan) {
        return effect(plan).runBlocking();
    }

    /**
     * A runtime together with the trace events it has recorded.
     *
     * @param runtime the runtime to execute with
     * @param events  the live event queue, appended to as plans run
     */
    public record Recording(ApiRuntime runtime, ConcurrentLinkedQueue<TraceEvent> events) {

        /**
         * A snapshot of the events recorded so far, in completion order.
         *
         * @return an immutable copy
         */
        public List<TraceEvent> snapshot() {
            return List.copyOf(events);
        }

        /**
         * The names of the endpoints called so far, in completion order and with repeats.
         *
         * @return the call sequence
         */
        public List<String> endpointCalls() {
            return events.stream()
                    .filter(event -> event.kind() == TraceEvent.Kind.ENDPOINT)
                    .map(TraceEvent::name)
                    .toList();
        }
    }
}
