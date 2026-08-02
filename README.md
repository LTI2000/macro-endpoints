# Macro Endpoints

A macro facility for API endpoints. Low-level calls are modelled as **Kleisli arrows**; compositions
of them are **reified as a plan tree**; and a single **catamorphism** over that tree yields
execution, documentation, cost estimation and dependency diagrams from one definition.
Catamorphisms are applied a second time, at a different level, to the recursive **intermediate
results** a composition produces on its way to an answer.

Java 21, no runtime dependencies, Maven build with a generated site.

```java
Macro<String, Dashboard> dashboard = macros.customerDashboard();
Plan<Dashboard> plan = dashboard.expand("c-1");

Interpreters.describe(plan).render();      // documentation      no call made
Interpreters.estimate(plan).format();      // cost and latency   no call made
Interpreters.graph(plan).toDot("dash");    // dependency graph   no call made
ApiRuntime.standard().execute(plan);       // the actual calls
```

---

## The problem

Compose two Kleisli arrows and you get a Kleisli arrow — that is the appeal. But you also get a Java
lambda, and a lambda is opaque. Nothing can ask a composed arrow which endpoints it touches, how
many round trips it implies, or which of its branches are independent of each other. Documentation
for a composite endpoint therefore gets written by hand, and drifts.

So arrows stay as the execution model, and *composition* becomes data:

```
Endpoint<Q, R>            a Kleisli arrow plus a specification describing it
   |
   v
Plan<A>                   sealed AST, ten nodes: the composition, as a value
   |
   +-- PlanCata.fold ---> PlanAlgebra<F>            one traversal, many meanings
                             |
                             +-- ExecutionAlgebra  -> Eff<A>       run it
                             +-- OutlineAlgebra    -> Outline      document it
                             +-- CostAlgebra       -> Cost         budget it
                             +-- GraphAlgebra      -> CallGraph    diagram it
```

`Plan<A>` is the free structure over the supported operations. Building one performs nothing.
`PlanCata.fold` walks it once and an algebra decides what the walk means.

## Two levels of catamorphism

**Level one — over the plan.** The fold above.

**Level two — over intermediate results.** A composite endpoint usually has to consume something
recursive before it can answer: pages of orders, a tree of categories. Rather than fetching the
whole structure and then reducing it, the reduction is separated from the traversal in the usual
recursion-schemes way. A pattern functor (`ListF`, `TreeF`) describes one layer; an `Algebra`
collapses one layer; an *effectful* coalgebra grows one layer by making a call. The `Hylo` node
fuses the two so nothing is materialised:

```java
Plans.hylo(PageCursor.first(),            // seed
           ListF.<OrderPage>traversal(),  // how to traverse one layer
           orderPages(customerId),        // effectful coalgebra: grow
           summariseOrders(),             // algebra: reduce
           "page through orders");
```

Two consequences beyond tidiness:

- **Concurrency falls out of the functor.** Traversal runs through `Eff`'s parallel applicative.
  `ListF` has one recursive position per layer, so paging stays sequential — as it must. `TreeF` has
  a list of children, so siblings are fetched concurrently. No thread appears at the call site.
- **The reduction is testable without a network.** `summariseOrders()` can be applied to a literal
  `Fix` built in a test, and `RecursionSchemeTest` asserts that this agrees with the fused version
  that fetches for real.

## Design decisions worth knowing about

**`Combine` is not `Chain`.** `Chain` is bind: the second plan depends on the first plan's result.
`Combine` is independent: two plans plus a joining function. Because independence is *recorded*
rather than inferred, execution fans out concurrently for free, costing takes the maximum latency
instead of the sum, and diagrams draw the branches side by side. A design where everything is
`flatMap` discards that at composition time and can never recover it.

**The static probe.** A `Chain` holds a function, and a function body is not inspectable, so static
interpreters cannot see past a bind. `Chain` therefore carries an optional exemplar intermediate
value that non-executing interpreters may use to explore *one* representative branch. It is never
consulted during execution, and the outline labels the result `branch (probed)` so no reader mistakes
a conditional continuation for an unconditional one. An honest partial answer beats a false total one.

**Macros expand, they do not invoke.** `Macro.expand(q)` returns structure, wrapped in a labelled
boundary. Composing macros composes trees, so the `storefront-overview` macro — built from two other
macros — produces a single graph naming all five underlying endpoints. Where opacity *is* wanted,
`asEndpoint(runtime, exemplar)` seals a macro into an ordinary `Endpoint` that contributes one node
to a diagram, carrying the cost derived from its exemplar expansion.

**Retries and tracing are not plan nodes.** They describe how a call is performed, not what a
composition means, so they live in `ApiRuntime` and `RetryPolicy`. Had they been nodes, every static
interpreter would have had to handle them and the cost model would have had to guess at retry counts.

**Higher-kinded types are emulated.** `PlanAlgebra` is parameterised by its carrier, which is a type
constructor — inexpressible in Java. The standard defunctionalisation is used: `App<F, A>` stands for
`F` applied to `A`, each constructor supplies a `narrow` method, and brands are uninstantiable so the
casts cannot fail. They are confined to a handful of `narrow` methods.

## A finding about record patterns

Record patterns are used throughout — the plan fold is precisely the switch-over-a-sealed-hierarchy
they exist for. One obstacle deserves recording, because the compiler error does not point at the
cause.

This does **not** compile:

```java
case Transform<?, A>(var source, var function, var label) ->
        algebra.transform(fold(source, algebra), function, label);
```

`javac` applies capture conversion **per component expression**, not once per pattern. The wildcard
is captured separately in `source` and in `function`, producing two *unrelated* captures, and the
call is rejected because nothing connects the source's element type to the function's argument type.

The remedy is to match the wildcard type and delegate to a private generic method whose type
parameter binds the capture once; the record pattern then lives inside that method:

```java
case Transform<?, A> node -> foldTransform(node, algebra);

private static <F, X, A> App<F, A> foldTransform(Transform<X, A> node, PlanAlgebra<F> algebra) {
    return switch (node) {
        case Transform<X, A>(var source, var function, var label) ->
                algebra.transform(fold(source, algebra), function, label);
    };
}
```

A single-case record pattern switch **is** exhaustive over a record type in Java 21, so no `default`
and no cast is needed. Separately: two record patterns may not share one `case` label, so identical
arms must be written out and delegated to a shared helper.

## Layout

| Package | Responsibility |
|---|---|
| `io.macroapi.hkt` | `App`, `Functor`, `Applicative`, `Traverse`, `Algebra`, `Fix`, `Recursion` |
| `io.macroapi.effect` | `Eff`, `Outcome`, `ApiError`, `Kleisli`, `Endpoint`, `EndpointSpec`, `RetryPolicy` |
| `io.macroapi.plan` | `Plan` and its ten nodes, `PlanAlgebra`, `PlanCata`, `Plans` |
| `io.macroapi.structure` | Pattern functors: `ListF`, `TreeF` |
| `io.macroapi.interpret` | The four algebras, `ApiRuntime`, `Outline`, `Cost`, `CallGraph` |
| `io.macroapi.macro` | `Macro`, `MacroSpec`, `MacroRegistry` |
| `io.macroapi.demo` | A worked storefront example — read `StorefrontMacros` first |
| `io.macroapi.docs` | Build-time site documentation generation |

Dependencies run strictly downward; `interpret` depends on `plan` and never the reverse.

## Building

```bash
mvn test                                                      # 21 tests
mvn compile exec:java -Dexec.mainClass=io.macroapi.demo.Demo  # the tour
mvn site && mvn site:run                                      # site at localhost:8080
```

### The generated site

The site is built from `target/site-src`, not `src/site`. At `pre-site` the hand-written pages are
copied there and `io.macroapi.docs.SiteDocsGenerator` writes the machine-derived ones alongside:
a catalogue page listing every registered macro with its structure and cost, plus one `.dot` and one
`.puml` call graph per macro. All of it comes from folding the macros through the static
interpreters, so a published description cannot drift from the composition it describes. Generation
makes no API call — expansion is syntactic.

### Dependency diagrams

Package and class diagrams come from **UMLDoclet**, configured in the Javadoc report. It emits normal
Javadoc plus, for each class, a diagram of fields, methods, inheritance and associations; for each
package, a package diagram; and a package dependency diagram on the overview page. Diagrams are
embedded SVG, so no native tooling is required. The sealed hierarchies render usefully: `Plan` with
its ten permitted nodes, `ApiError` with its seven, and the algebras fanning out from `PlanAlgebra`.

Two optional profiles add more:

```bash
mvn -Pgraphviz site   # renders the generated call graphs; adds a module dependency graph
mvn -Pjdepend site    # numeric package coupling: afferent/efferent, instability, cycles
```

Both are profile-gated deliberately. `graphviz` needs `dot` on the `PATH`; JDepend reads bytecode
with an old ASM and has historically struggled with recent class file versions. The default build
produces the full set of package and class diagrams without either.

## Sample output

From `mvn compile exec:java -Dexec.mainClass=io.macroapi.demo.Demo`, abridged:

```
1. Structure, derived from the plan (no calls made)
macro: customer-dashboard
'-- sequence: platinum customers get a concierge
    |-- parallel: gather profile
    |   |-- parallel: gather profile/1
    |   |   |-- call: customer.get (GET /customers/{id})
    |   |   '-- recover: on failure
    |   |       |-- call: loyalty.get (GET /loyalty/{customerId})
    |   |       '-- fallback
    |   |           '-- fail: synthetic: outline probe
    |   '-- hylomorphism: page through orders
    |       |-- unfold: seed = First[]
    |       |   '-- transform: advance cursor
    |       |       '-- call: orders.page (GET /customers/{id}/orders)
    |       '-- catamorphism: reduce each layer as its children complete
    '-- branch (probed): one possible continuation
        '-- transform: attach concierge
            '-- call: concierge.get (GET /concierge/{customerId})

2. Cost estimate, derived from the same plan
6 call(s), ~300 ms, 11 cost unit(s)

5. Trace, and estimate versus reality
ENDPOINT customer.get       80 ms  ok
ENDPOINT orders.page        73 ms  ok
ENDPOINT loyalty.get        85 ms  ok
ENDPOINT orders.page        73 ms  ok
ENDPOINT orders.page        70 ms  ok
ENDPOINT concierge.get      95 ms  ok
BOUNDARY customer-dashboard 356 ms  ok

estimated : 6 call(s), ~300 ms, 11 cost unit(s)
actual    : 6 call(s), 368 ms
```

Note that the six calls sum to 476 ms of latency but complete in 356 ms: `customer.get`,
`loyalty.get` and the paging loop overlap, because `Combine` recorded them as independent. The
sequential paging chain then dominates the critical path.

## Verification status

Everything under `src/` compiles clean under `javac -Xlint:all` on JDK 21, all 21 tests pass, and
`javadoc -Xdoclint:all` reports no warnings.

**The `pom.xml` has not been executed.** It was written in an environment without access to Maven
Central, so no plugin or dependency could be resolved and `mvn` could never run. Sources were
compiled with `javac` directly and the tests were run through a small stub of the JUnit API. The
build file is therefore carefully written but unverified; expect to adjust plugin versions to suit
your Maven installation. Two specifics to check first:

- **`maven-site-plugin` 3.12.1** is pinned for compatibility with Maven 3.8 and 3.9. On Maven 3.9.6+
  you may prefer 3.21.0, which also wants a newer `doxia-module-markdown`.
- **UMLDoclet option names have changed across major versions.** The `additionalOptions` block
  targets 2.x; if you raise `umldoclet.version`, check the names against that release.

## Tests

| Test | Concern |
|---|---|
| `PlanCataTest` | The interpretations agree with each other and with execution — that the graph covers every executed call, that the estimated call count matches the real one, that macro boundaries survive, and that sealing makes a macro opaque |
| `RecursionSchemeTest` | An algebra applied to a literal structure agrees with the same algebra fused into a plan that fetches one |
| `EffTest` | Effects are deferred and repeatable, failures short-circuit, parallel failures aggregate, `zipPar` really overlaps, retries respect the policy, and Kleisli composition is associative with identity as its unit |
