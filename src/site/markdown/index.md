# Macro Endpoints

A facility for building **higher-level API endpoints out of lower-level ones**, in which a
composition is a value that can be inspected rather than a function that can only be called.

Low-level calls are modelled as **Kleisli arrows** — effectful functions `A -> Eff<B>`. Composing
them directly would give a working but opaque result: a composed arrow can be run, and nothing else.
So composition is instead **reified** as a small tree, `Plan<A>`, and a single **catamorphism** over
that tree, `PlanCata.fold`, supplies every interpretation the system needs.

```java
Macro<String, Dashboard> dashboard = macros.customerDashboard();
Plan<Dashboard> plan = dashboard.expand("c-1");

Interpreters.describe(plan).render();   // documentation      no call made
Interpreters.estimate(plan).format();   // cost and latency   no call made
Interpreters.graph(plan).toDot("x");    // dependency graph   no call made
ApiRuntime.standard().execute(plan);    // the actual calls
```

The first three lines are why the plan is reified. They read a composite endpoint's structure
without performing any part of it — which is what lets this site publish an accurate catalogue of
every macro, its cost, and its dependency diagram, generated from the macros themselves at build
time.

## What is on this site

| Page | Contents |
|---|---|
| [Architecture](architecture.html) | The design, and the reasoning behind each decision |
| [Macro catalogue](macros.html) | Generated: every registered macro, its cost and its call graph |
| [Javadoc](apidocs/index.html) | API documentation, with UML class and package diagrams |
| [Cross-referenced source](xref/index.html) | Browsable source |

## The shape of the thing

```
Endpoint<Q, R>            a Kleisli arrow plus a specification describing it
   |
   v
Plan<A>                   a sealed AST of ten nodes: the composition, as data
   |
   +-- PlanCata.fold ---> PlanAlgebra<F>          one traversal, many meanings
                              |
                              +-- ExecutionAlgebra  -> Eff<A>       run it
                              +-- OutlineAlgebra    -> Outline      document it
                              +-- CostAlgebra       -> Cost         budget it
                              +-- GraphAlgebra      -> CallGraph    diagram it
```

A macro is a function from a request to a `Plan`. It **expands** rather than invokes, so a macro
built from other macros yields one tree containing all of them, and the interpreters see straight
through the abstraction to every endpoint underneath.

## Two levels of fold

The word *catamorphism* is doing double duty in this design, and the two uses are worth separating.

1. **Over the plan.** The composition is folded to produce an interpretation. This is the
   structure above.
2. **Over intermediate results.** A composition frequently produces something recursive on its way
   to an answer — a chain of pages, a tree of categories. Those are reduced by an `Algebra` applied
   through a `Hylo` node that **fuses** the fetch with the fold, so the intermediate structure is
   consumed as it arrives and never fully materialised.

The second is described at length under
[two levels of catamorphism](architecture.html#two-levels-of-catamorphism).

## Running the example

```bash
mvn test                                  # 21 tests
mvn compile exec:java -Dexec.mainClass=io.macroapi.demo.Demo
mvn site && mvn site:run                  # this site, at http://localhost:8080
```

The demo prints one plan interpreted four ways, then compares the statically derived cost estimate
against what the run actually cost.
