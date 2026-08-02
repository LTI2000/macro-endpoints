package io.macroapi.plan;

import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Traverse;

import java.util.Objects;

/**
 * A hylomorphism over API calls: an effectful unfold immediately consumed by a catamorphism.
 *
 * <p>The two halves are the recurring shape of every non-trivial aggregation over a remote API.
 * The {@link PlanCoalgebra} grows a recursive structure by calling endpoints — following page
 * cursors, descending a hierarchy — and the {@link Algebra} collapses it into the answer. Stating
 * them separately is what makes each half reusable: the same paging coalgebra feeds a total, a
 * histogram or an export, and the same summarising algebra works over a live page chain or a test
 * fixture.</p>
 *
 * <p><strong>Fusion.</strong> Writing this as one node rather than an unfold followed by a
 * {@link Fold} is not merely convenient. The executing interpreter applies the algebra to each
 * layer as soon as its children have been reduced, so the intermediate {@link Fix} structure is
 * never materialised — a genuine deforestation, and the reason a million-row page chain can be
 * totalled in constant memory. {@link Plans#unfold} recovers the unfused behaviour by passing
 * {@link Fix#wrap} as the algebra, which is precisely the identity of the fold.</p>
 *
 * <p><strong>Concurrency.</strong> The expansion of sibling recursive positions goes through
 * {@link Traverse#traverse}, which is driven by the effect's applicative. Since that applicative
 * combines in parallel, the children of a tree node are fetched concurrently while an inherently
 * sequential shape such as a cursor chain remains sequential — with no extra code either way.</p>
 *
 * @param seed       the starting seed
 * @param traversal  the traversable instance for the intermediate shape
 * @param coalgebra  grows one layer per seed by calling APIs
 * @param algebra    collapses one layer of already-reduced children
 * @param label      a short description used in documentation and diagrams
 * @param <G>        witness for the intermediate shape
 * @param <S>        the seed type
 * @param <A>        the reduced result type
 */
public record Hylo<G, S, A>(S seed,
                            Traverse<G> traversal,
                            PlanCoalgebra<G, S> coalgebra,
                            Algebra<G, A> algebra,
                            String label) implements Plan<A> {

    /**
     * Canonical constructor.
     *
     * @param seed      starting seed
     * @param traversal traversable instance, non-null
     * @param coalgebra layer-growing rule, non-null
     * @param algebra   layer-collapsing rule, non-null
     * @param label     description, non-null
     */
    public Hylo {
        Objects.requireNonNull(traversal, "traversal");
        Objects.requireNonNull(coalgebra, "coalgebra");
        Objects.requireNonNull(algebra, "algebra");
        Objects.requireNonNull(label, "label");
    }
}
