package io.macroapi.plan;

import io.macroapi.hkt.App;

/**
 * An <em>effectful coalgebra</em>: the rule that grows one layer of a recursive structure by
 * calling APIs.
 *
 * <p>A pure coalgebra has type {@code S -> F<S>}: given a seed, produce one layer of shape whose
 * recursive positions hold further seeds. Here the layer is not available directly — it has to be
 * fetched — so the result is wrapped in a {@link Plan}. Expanding a seed therefore consumes network
 * calls, and expanding the whole structure is a recursive traversal of those calls.</p>
 *
 * <p>Two canonical uses appear in the demonstration:</p>
 * <ul>
 *   <li><strong>Paging.</strong> The seed is a cursor. Each step fetches one page and yields a
 *       cons-layer holding that page plus the next cursor, or a nil-layer at the end.</li>
 *   <li><strong>Hierarchies.</strong> The seed is a node identifier. Each step fetches the node and
 *       yields a tree-layer holding it plus the identifiers of its children, which the traversal
 *       then expands concurrently.</li>
 * </ul>
 *
 * <p>The coalgebra states the shape of the recursion in one place; termination is its
 * responsibility, exactly as with a hand-written loop.</p>
 *
 * @param <G> witness for the shape being grown
 * @param <S> the seed type
 */
@FunctionalInterface
public interface PlanCoalgebra<G, S> {

    /**
     * Grows one layer from a seed.
     *
     * @param seed the current seed
     * @return a plan producing one layer whose recursive positions hold the next seeds
     */
    Plan<App<G, S>> step(S seed);
}
