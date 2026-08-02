package io.macroapi.interpret;

import io.macroapi.effect.Outcome;

import java.time.Duration;
import java.util.Objects;

/**
 * One observation emitted by the executing interpreter.
 *
 * <p>Emitted for every endpoint call and every named boundary, so a trace reconstructs both the
 * calls made and the macro structure that produced them.</p>
 *
 * @param kind     what was observed
 * @param name     the endpoint or boundary name
 * @param elapsed  wall-clock duration
 * @param outcome  whether the step succeeded, and with what failure if not
 */
public record TraceEvent(Kind kind, String name, Duration elapsed, Outcome<?> outcome) {

    /** The kinds of step that are traced. */
    public enum Kind {
        /** A single low-level endpoint call. */
        ENDPOINT,
        /** A named boundary, normally a macro expansion. */
        BOUNDARY
    }

    /**
     * Canonical constructor.
     *
     * @param kind    step kind, non-null
     * @param name    step name, non-null
     * @param elapsed duration, non-null
     * @param outcome result, non-null
     */
    public TraceEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(outcome, "outcome");
    }

    /**
     * A single log-friendly line.
     *
     * @return the formatted event
     */
    public String format() {
        String status = outcome.fold(value -> "ok", error -> "ERR " + error.describe());
        return "%-8s %-34s %6d ms  %s".formatted(kind, name, elapsed.toMillis(), status);
    }
}
