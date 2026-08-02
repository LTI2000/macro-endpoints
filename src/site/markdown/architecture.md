# Architecture

## Why reify the composition

The natural way to model an API call in a functional style is as a **Kleisli arrow**: a function
from a request to an effect, `A -> Eff<B>`, where `Eff` is some type describing an asynchronous
computation that may fail. Kleisli arrows compose, so `getCustomer.andThen(getLoyalty)` is itself an
arrow, and building higher-level endpoints out of lower-level ones is straightforward.

It is also a dead end for anything but execution. A composed arrow is a Java lambda. Nothing can ask
it which endpoints it will call, how many round trips it implies, or whether two of its branches are
independent. A macro built this way is documented by hand, and the documentation goes stale.

So arrows are kept as the *execution* model, and composition is moved into a data structure:

```java
public sealed interface Plan<A>
        permits Pure, Fail, Invoke, Transform, Combine,
                Chain, Recover, Labeled, Fold, Hylo {
```

`Plan<A>` is the free structure over the operations the facility supports. Building one performs
nothing. `PlanCata.fold` walks it once, handing each node to a `PlanAlgebra<F>`, and the algebra
decides what the walk means. Four exist:

| Algebra | Carrier | Result |
|---|---|---|
| `ExecutionAlgebra` | `Eff` | actually performs the calls |
| `OutlineAlgebra` | `Const<Outline>` | a tree of prose describing the composition |
| `CostAlgebra` | `Const<Cost>` | call count, estimated latency, charge |
| `GraphAlgebra` | `Const<CallGraph>` | nodes, edges and clusters, emitted as DOT or PlantUML |

The three static interpreters use `Const`, the constant functor: their carrier ignores the type
parameter, because a description of a plan has the same shape whatever the plan returns.

### The cost of it: higher-kinded types

`PlanAlgebra` must be parameterised by its carrier, and the carrier is a type *constructor*. Java
cannot express that. The standard workaround, defunctionalisation, is used:

```java
public interface Higher<F, A> { }            // "F applied to A"

public final class Eff<A> implements Higher<Eff.Witness, A> {

    public static final class Witness {     // uninstantiable brand
        private Witness() { throw new AssertionError("no instances"); }
    }

    public static <A> Eff<A> narrow(Higher<Witness, A> higher) { return (Eff<A>) higher; }
}
```

`Witness` has a private constructor and no instances, so `Eff` is the only implementation of
`Higher<Eff.Witness, A>` that can exist and the cast inside `narrow` cannot fail. The unchecked casts are confined to a handful of
`narrow` methods in `io.macroapi.hkt` and `io.macroapi.effect`.

## Combine is not Chain

Two nodes handle composition of two plans, and keeping them distinct is the single most useful
structural decision here.

- `Chain<X, A>` is **bind**: the second plan is a function of the first plan's *result*, so it
  cannot be known until the first has run.
- `Combine<X, Y, A>` is **independent**: two plans and a function to join their results.

Because independence is recorded in the structure rather than inferred, the execution algebra fans
`Combine` out concurrently for free, the cost algebra takes the **maximum** of the two latencies
instead of the sum, and the graph algebra draws the branches side by side rather than in series.
A design in which everything is `flatMap` throws that information away at the point of composition
and can never recover it.

### The bind frontier, and the static probe

`Chain` holds a Java function, and a function's body is not inspectable. A static interpreter
therefore cannot see past a bind — which would leave large parts of a realistic composition
undocumented.

`Chain` accordingly carries an optional **static probe**: an exemplar value of the intermediate type
that non-executing interpreters may pass to the continuation to explore *one* representative branch.

```java
Optional<X> staticProbe
```

The probe is never consulted during execution. Where present, the outline marks the result
`branch (probed): one possible continuation` and the graph uses a diamond node, so a reader is never
misled into thinking a conditional continuation is unconditional. Where absent, the interpretation
simply stops at the frontier and says so. This is an honest partial answer rather than a false total
one.

## Two levels of catamorphism

### Level one: folding the plan

Described above — `PlanCata.fold` is a catamorphism over the plan AST.

### Level two: folding the intermediate results

A composite endpoint usually has to consume something recursive before it can answer: paginate
through an order history, walk a category hierarchy. The obvious implementation fetches the whole
structure into a list or tree and then reduces it, which means holding it all in memory and writing
the traversal by hand each time.

Instead the *reduction* is separated from the *traversal*, in the usual recursion-schemes way. A
**pattern functor** describes one layer with a hole where the recursion goes:

```java
public sealed interface ListF<E, A> extends Higher<ListF.Witness<E>, A> {
    record Nil<E, A>() implements ListF<E, A> { }
    record Cons<E, A>(E head, A tail) implements ListF<E, A> { }
}
```

An **algebra** collapses one layer, `ListF<OrderPage, OrderSummary> -> OrderSummary`. A
**coalgebra** grows one layer, and here it is *effectful* — producing the next layer requires a call:

```java
PageCursor -> Plan<ListF<OrderPage, PageCursor>>
```

The `Hylo` node fuses the two. It unfolds from a seed and folds the result in one pass, so no
intermediate `Fix` structure is ever built: each page is reduced into the running summary as it
arrives.

```java
Plans.hylo(PageCursor.first(),           // seed
           ListF.<OrderPage>traversal(), // how to traverse one layer
           orderPages(customerId),       // effectful coalgebra: grow
           summariseOrders(),            // algebra: reduce
           "page through orders");
```

Two things follow that are more than tidiness.

**Concurrency comes from the functor.** The traversal runs through `Eff`'s *parallel* applicative.
For `ListF` each layer has one recursive position, so paging stays sequential — as it must, since a
cursor depends on the previous page. For `TreeF` a layer has a list of children, so the siblings are
fetched concurrently. Nothing at the call site says anything about threads; the shape of the pattern
functor determines the available parallelism.

**The reduction is testable without a network.** The same `summariseOrders()` algebra can be applied
to a literal `Fix` structure built in a test, and `RecursionSchemeTest` asserts that doing so agrees
with the fused version that fetches for real.

## Macros expand; they do not invoke

```java
public record Macro<Q, R>(MacroSpec spec, Function<Q, Plan<R>> expansion) {
    public Plan<R> expand(Q request) {
        return Plans.named(spec.name(), expansion.apply(request));
    }
}
```

`expand` returns *structure*. Composing two macros therefore composes their trees, and the
interpreters see through the abstraction: the `storefront-overview` macro is built from two other
macros, and its generated graph names all five underlying endpoints. The `Labeled` boundary is
retained so the outline can show the nesting and the graph can draw each macro as a cluster.

Sometimes opacity is wanted — at a module boundary, a macro should look like any other endpoint.
That is available, but it is a deliberate act:

```java
Endpoint<String, CategoryRollup> sealed =
        macros.catalogueRollup().asEndpoint(runtime, "root");
```

The sealed form contributes exactly one node to a graph, carrying the cost estimate derived from the
exemplar expansion. `PlanCataTest.sealedMacroIsOpaque` pins that behaviour down.

## What is deliberately not in the plan

Retries and tracing are properties of *how* a call is performed, not of *what* the composition
means. They live in `ApiRuntime` and `RetryPolicy`. Had they been plan nodes, every static
interpreter would have had to handle them, and the cost model would have had to guess at retry
counts. Keeping them out leaves the AST describing intent only.

## Record patterns and capture conversion

Record patterns are used throughout, and the plan fold is exactly the switch-over-a-sealed-hierarchy
that they exist for. One obstacle is worth recording, because the error message does not point at
the cause.

The natural formulation does not compile:

```java
// Does not compile.
return switch (plan) {
    case Transform<?, A>(var source, var function, var label) ->
            algebra.transform(fold(source, algebra), function, label);
    ...
};
```

`javac` applies capture conversion **per component expression**, not once for the whole pattern. The
wildcard in `Transform<?, A>` is therefore captured separately in `source` and in `function`, giving
two *unrelated* types `capture#1` and `capture#2`, and the algebra call is rejected because it
cannot see that the source's element type is the function's argument type.

The fix is to match the wildcard type in the outer switch and delegate to a private generic method
whose type parameter binds the capture once:

```java
case Transform<?, A> node -> foldTransform(node, algebra);

private static <F, X, A> Higher<F, A> foldTransform(Transform<X, A> node, PlanAlgebra<F> algebra) {
    return switch (node) {
        case Transform<X, A>(var source, var function, var label) ->
                algebra.transform(fold(source, algebra), function, label);
    };
}
```

Two details make this pleasant rather than merely workable. A single-case record pattern switch **is
exhaustive** over a record type in Java 21, so no `default` arm and no cast is needed. And the
destructuring still happens — it has simply moved one level in, to where the type variable is bound.

A second, smaller restriction: two record patterns may not share a `case` label. Where two cases
have identical bodies they must be written as separate arms delegating to a common helper.

## Package structure

| Package | Responsibility |
|---|---|
| `io.macroapi.hkt` | `Higher`, `Functor`, `Applicative`, `Traverse`, `Algebra`, `Fix`, `Recursion` |
| `io.macroapi.effect` | `Eff`, `Outcome`, `ApiError`, `Kleisli`, `Endpoint`, `RetryPolicy` |
| `io.macroapi.plan` | `Plan` and its ten nodes, `PlanAlgebra`, `PlanCata`, `Plans` |
| `io.macroapi.structure` | Pattern functors: `ListF`, `TreeF` |
| `io.macroapi.interpret` | The four algebras, `ApiRuntime`, `Outline`, `Cost`, `CallGraph` |
| `io.macroapi.macro` | `Macro`, `MacroSpec`, `MacroRegistry` |
| `io.macroapi.demo` | A worked storefront example |
| `io.macroapi.docs` | Build-time site documentation generation |

Dependencies run strictly downward: `interpret` depends on `plan`, never the reverse. The generated
[package diagram](apidocs/index.html) shows this, and `mvn -Pjdepend site` adds the numbers.
