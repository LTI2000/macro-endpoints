/**
 * The macro facility: named compositions that expand into inspectable plan structure.
 *
 * <p>{@link io.macroapi.macro.Macro} is a function from a request to a
 * {@link io.macroapi.plan.Plan}, so composing macros composes syntax trees rather than opaque
 * function calls; {@link io.macroapi.macro.MacroSpec} is the published contract; and
 * {@link io.macroapi.macro.MacroRegistry} is the catalogue from which documentation and diagrams are
 * generated at build time.</p>
 */
package io.macroapi.macro;
