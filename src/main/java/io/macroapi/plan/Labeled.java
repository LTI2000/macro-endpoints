package io.macroapi.plan;

import java.util.Objects;

/**
 * A named boundary around a sub-plan.
 *
 * <p>Inserted automatically by {@link io.macroapi.macro.Macro#expand}, so that after expansion the
 * resulting tree still records which macro each region came from. Interpreters use it as a heading
 * in documentation and as a cluster in diagrams; it has no effect on execution beyond emitting a
 * trace span.</p>
 *
 * @param name  the boundary name
 * @param inner the enclosed plan
 * @param <A>   the result type
 */
public record Labeled<A>(String name, Plan<A> inner) implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param name  boundary name, non-null
     * @param inner enclosed plan, non-null
     */
    public Labeled {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inner, "inner");
    }
}
