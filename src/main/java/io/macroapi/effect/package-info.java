/**
 * Effectful API calls, modelled as Kleisli arrows.
 *
 * <p>{@link io.macroapi.effect.Eff} is the effect type: a deferred, repeatable description of an
 * asynchronous computation that yields an {@link io.macroapi.effect.Outcome} — either a value or an
 * {@link io.macroapi.effect.ApiError}. Deferral matters because a plan is a recipe that may be
 * interpreted many times, or never; treating failure as a value rather than an exceptional
 * completion matters because recovery is then an ordinary combinator instead of a catch block.</p>
 *
 * <p>{@link io.macroapi.effect.Kleisli} is the arrow {@code A -> Eff<B>}, closed under composition,
 * and {@link io.macroapi.effect.Endpoint} refines it with an
 * {@link io.macroapi.effect.EndpointSpec} describing the call. That specification is what makes the
 * static interpreters possible: an endpoint carries its own name, method, path, expected latency and
 * charge, so a composition can be documented and budgeted without being run.</p>
 *
 * <p>Cross-cutting execution concerns live here rather than in the plan structure — see
 * {@link io.macroapi.effect.RetryPolicy}. Retrying is a property of how a call is performed, not of
 * what the composition means, and keeping it out of the AST leaves the documentation and cost
 * interpreters undisturbed by it.</p>
 */
package io.macroapi.effect;
