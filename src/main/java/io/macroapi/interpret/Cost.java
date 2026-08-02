package io.macroapi.interpret;

import java.time.Duration;
import java.util.Objects;

/**
 * An estimate of what executing a plan will cost.
 *
 * <p>The two composition rules are what make the estimate worth having. Sequencing adds latency;
 * parallel combination takes the maximum. Charges and call counts always add, because running two
 * calls at once does not make them free.</p>
 *
 * @param latency   estimated wall-clock time
 * @param units     total abstract charge, summed from {@code EndpointSpec.costUnits}
 * @param callCount number of endpoint invocations
 */
public record Cost(Duration latency, int units, int callCount) {

    /** The cost of doing nothing: the identity for both composition rules. */
    public static final Cost FREE = new Cost(Duration.ZERO, 0, 0);

    /**
     * Canonical constructor.
     *
     * @param latency   estimated duration, non-null
     * @param units     abstract charge
     * @param callCount number of calls
     */
    public Cost {
        Objects.requireNonNull(latency, "latency");
    }

    /**
     * Combines with a cost incurred <em>after</em> this one: latencies add.
     *
     * @param next the subsequent cost
     * @return the combined estimate
     */
    public Cost then(Cost next) {
        return new Cost(latency.plus(next.latency), units + next.units, callCount + next.callCount);
    }

    /**
     * Combines with a cost incurred <em>alongside</em> this one: the longer latency wins.
     *
     * @param sibling the concurrent cost
     * @return the combined estimate
     */
    public Cost alongside(Cost sibling) {
        Duration slower = latency.compareTo(sibling.latency) >= 0 ? latency : sibling.latency;
        return new Cost(slower, units + sibling.units, callCount + sibling.callCount);
    }

    /**
     * Scales the estimate, used to project the cost of a loop from the cost of one iteration.
     *
     * @param factor the assumed repetition count
     * @return the scaled estimate
     */
    public Cost times(int factor) {
        return new Cost(latency.multipliedBy(factor), units * factor, callCount * factor);
    }

    /**
     * The estimate as a single line.
     *
     * @return the formatted estimate
     */
    public String format() {
        return "%d call(s), ~%d ms, %d cost unit(s)".formatted(callCount, latency.toMillis(), units);
    }
}
