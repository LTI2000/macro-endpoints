/**
 * Pattern functors describing the shape of recursive intermediate results.
 *
 * <p>A recursive type is defined here in two steps: a non-recursive layer with a hole where the
 * recursion would be ({@link io.macroapi.structure.ListF}, {@link io.macroapi.structure.TreeF}), and
 * {@link io.macroapi.hkt.Fix} to tie the knot. The pay-off is that folding and effectful unfolding
 * are written once, generically, instead of once per data type — so adding a new remote shape means
 * adding a functor instance, not a new traversal.</p>
 */
package io.macroapi.structure;
