package io.macroapi.hkt;

/**
 * Lightweight encoding of a <em>higher-kinded type application</em>, written {@code F<A>} in
 * languages that support type constructor polymorphism.
 *
 * <p>Java's type system is first-order: a type variable always stands for a <em>type</em>
 * ({@code List<String>}), never for a <em>type constructor</em> ({@code List}). That restriction
 * makes it impossible to write, say, "an algebra that produces some {@code F<A>} for every node of
 * a plan" — which is exactly what a catamorphism over a typed syntax tree needs. This interface
 * applies the well known defunctionalisation trick (Yallop &amp; White, <em>Lightweight
 * higher-kinded polymorphism</em>, FLOPS 2014): the type constructor is represented by an ordinary
 * <em>witness</em> (or <em>brand</em>) type {@code F}, and the application {@code F<A>} is
 * represented by the ordinary type {@code App<F, A>}.</p>
 *
 * <p>The convention used throughout this project is:</p>
 * <ul>
 *   <li>a concrete effect or container {@code Foo<A>} declares
 *       {@code class Foo<A> implements App<Foo.Witness, A>};</li>
 *   <li>the nested {@code Witness} type is uninhabited and exists only as a type-level tag;</li>
 *   <li>a static {@code narrow(App<Foo.Witness, A>)} method performs the (always safe, but
 *       unavoidably unchecked) downcast back to {@code Foo<A>}.</li>
 * </ul>
 *
 * <p>The cast in {@code narrow} is safe because {@code Witness} is package-private/uninstantiable,
 * so the only value that can ever have static type {@code App<Foo.Witness, A>} is a {@code Foo<A>}.</p>
 *
 * @param <F> the witness type standing for the type constructor
 * @param <A> the type the constructor is applied to
 */
public interface App<F, A> {
}
