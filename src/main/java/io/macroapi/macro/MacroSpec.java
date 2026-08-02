package io.macroapi.macro;

import io.macroapi.effect.EndpointSpec;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The published contract of a macro.
 *
 * <p>The counterpart of {@link EndpointSpec} one level up. Where an endpoint spec describes a call
 * that is made, a macro spec describes a composition that is expanded — so it carries a stability
 * marker, which callers need in order to judge how much to depend on it, and no path, since a macro
 * is not itself addressable until it is sealed by {@link Macro#asEndpoint}.</p>
 *
 * @param name      a stable, unique identifier, also used as the plan's boundary label
 * @param summary   one sentence describing what the macro produces
 * @param tags      free-form classification, for example the owning bounded context
 * @param stability how much callers may rely on the shape of the result
 */
public record MacroSpec(String name, String summary, List<String> tags, Stability stability) {

    /** How much a macro's contract may be relied upon. */
    public enum Stability {
        /** May change without notice; not for use outside the owning module. */
        EXPERIMENTAL,
        /** Changes are announced and versioned. */
        STABLE,
        /** Scheduled for removal; migrate away. */
        DEPRECATED
    }

    /**
     * Canonical constructor; validates and defensively copies.
     *
     * @param name      macro identifier, must be non-blank
     * @param summary   one-sentence description
     * @param tags      classification tags, copied
     * @param stability contract stability
     */
    public MacroSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(stability, "stability");
        if (name.isBlank()) {
            throw new IllegalArgumentException("macro name must not be blank");
        }
        tags = List.copyOf(tags);
    }

    /**
     * Convenience factory for a stable macro.
     *
     * @param name    macro identifier
     * @param summary one-sentence description
     * @param tags    classification tags
     * @return the spec
     */
    public static MacroSpec of(String name, String summary, String... tags) {
        return new MacroSpec(name, summary, List.of(tags), Stability.STABLE);
    }

    /**
     * Derives an endpoint spec for use when the macro is sealed into an opaque endpoint.
     *
     * <p>The synthetic path {@code /macro/{name}} records the provenance of the endpoint, and the
     * supplied cost estimate lets a macro-turned-endpoint still contribute a realistic figure to
     * the budget of anything that composes it.</p>
     *
     * @param latency the estimated round trip for the whole expansion
     * @param units   the estimated abstract charge for the whole expansion
     * @return an endpoint spec describing the sealed macro
     */
    public EndpointSpec asEndpointSpec(Duration latency, int units) {
        return new EndpointSpec(name, EndpointSpec.HttpMethod.POST, "/macro/" + name,
                java.util.stream.Stream.concat(tags.stream(), java.util.stream.Stream.of("macro"))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                latency, units);
    }
}
