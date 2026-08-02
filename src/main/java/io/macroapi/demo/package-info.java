/**
 * A worked example: a storefront whose low-level endpoints are composed into three macros.
 *
 * <p>{@link io.macroapi.demo.StorefrontApi} simulates the low-level calls;
 * {@link io.macroapi.demo.StorefrontMacros} composes them, and is the file to read first — it holds
 * the algebras that reduce intermediate results and the coalgebras that grow them;
 * {@link io.macroapi.demo.Demo} interprets one plan four ways and prints the results.</p>
 */
package io.macroapi.demo;
