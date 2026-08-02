/**
 * The minimal type-constructor machinery the rest of the library is built on.
 *
 * <p>Java has no higher-kinded types: one cannot write {@code F<A>} with {@code F} as a parameter.
 * The interpreters need exactly that, because a single fold has to produce an {@code Eff<A>} for one
 * interpretation and a plain {@code Outline} for another. {@link io.macroapi.hkt.App} supplies the
 * standard workaround — defunctionalisation, after Yallop and White — in which {@code App<F, A>}
 * stands for the application of a brand {@code F} to an argument {@code A}, and each type
 * constructor provides a {@code narrow} method to recover its concrete form.</p>
 *
 * <p>On top of that sit {@link io.macroapi.hkt.Functor}, {@link io.macroapi.hkt.Applicative} and
 * {@link io.macroapi.hkt.Traverse}, and then the recursion machinery:
 * {@link io.macroapi.hkt.Algebra} reduces one layer of a structure,
 * {@link io.macroapi.hkt.Fix} ties a pattern functor into a recursive type, and
 * {@link io.macroapi.hkt.Recursion#cata} folds the whole thing.</p>
 *
 * <p>The cost of the encoding is a cast inside each {@code narrow}. It is confined to these few
 * classes, and is safe because a brand type is uninhabited: nothing but the intended constructor can
 * ever produce a value tagged with it.</p>
 */
package io.macroapi.hkt;
