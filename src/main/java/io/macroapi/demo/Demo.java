package io.macroapi.demo;

import io.macroapi.demo.Storefront.Dashboard;
import io.macroapi.demo.Storefront.Overview;
import io.macroapi.effect.Outcome;
import io.macroapi.effect.RetryPolicy;
import io.macroapi.interpret.ApiRuntime;
import io.macroapi.interpret.Interpreters;
import io.macroapi.interpret.TraceEvent;
import io.macroapi.macro.Macro;
import io.macroapi.macro.MacroRegistry;
import io.macroapi.plan.Plan;

import java.time.Duration;
import java.time.Instant;

/**
 * A runnable tour of the facility: the same plan value interpreted four different ways.
 *
 * <p>Sections one to three make no network call at all — they document, budget and diagram a
 * composition purely from its structure. Section four executes it, and section five compares the
 * static estimate against what actually happened.</p>
 */
public final class Demo {

    private Demo() {
        throw new AssertionError("no instances");
    }

    /**
     * Runs the tour.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        try (StorefrontApi api = new StorefrontApi()) {
            StorefrontMacros macros = new StorefrontMacros(api);
            Macro<String, Dashboard> dashboard = macros.customerDashboard();
            Plan<Dashboard> plan = dashboard.expand("c-1");

            heading("1. Structure, derived from the plan (no calls made)");
            System.out.println(Interpreters.describe(plan).render());

            heading("2. Cost estimate, derived from the same plan");
            System.out.println(Interpreters.estimate(plan).format());
            System.out.println("  parallel branches count once toward latency, always toward charge");

            heading("3. Dependency graph (Graphviz DOT)");
            System.out.println(Interpreters.graph(plan).toDot("customer-dashboard"));

            heading("4. Execution");
            ApiRuntime.Recording recording = ApiRuntime.recording(RetryPolicy.standard());
            Instant started = Instant.now();
            Outcome<Dashboard> outcome = recording.runtime().execute(plan);
            Duration elapsed = Duration.between(started, Instant.now());

            outcome.fold(value -> {
                System.out.println("customer   : " + value.customer().name() + " (" + value.customer().tier() + ")");
                System.out.println("loyalty    : " + value.loyalty().points() + " points");
                System.out.println("orders     : " + value.orders().orderCount() + " orders over "
                        + value.orders().pageCount() + " page(s), total " + value.orders().total()
                        + ", average " + value.orders().averageOrderValue());
                System.out.println("concierge  : " + value.concierge()
                        .map(Storefront.Concierge::agentName).orElse("none"));
                return null;
            }, error -> {
                System.out.println("failed: " + error.describe());
                return null;
            });

            heading("5. Trace, and estimate versus reality");
            recording.snapshot().stream().map(TraceEvent::format).forEach(System.out::println);
            System.out.println();
            System.out.printf("estimated : %s%n", Interpreters.estimate(plan).format());
            System.out.printf("actual    : %d call(s), %d ms%n", api.callCount(), elapsed.toMillis());
            System.out.println("  the estimate assumes " + Interpreters.DEFAULT_LOOP_FACTOR
                    + " iterations for each recursive node");

            heading("6. Degraded path: a customer whose loyalty service is down");
            Outcome<Dashboard> degraded = ApiRuntime.standard().execute(dashboard.expand("c-3"));
            degraded.fold(value -> {
                System.out.println(value.customer().name() + " -> loyalty " + value.loyalty().points()
                        + " points (recovered placeholder), " + value.orders().orderCount() + " orders");
                return null;
            }, error -> {
                System.out.println("failed: " + error.describe());
                return null;
            });

            heading("7. A macro composed of macros: one graph, every endpoint");
            Macro<Overview.Request, Overview> overview = macros.storefrontOverview();
            Plan<Overview> overviewPlan = overview.expand(new Overview.Request("c-1", "root"));
            System.out.println(Interpreters.describe(overviewPlan).render());
            System.out.println();
            Outcome<Overview> overviewOutcome = ApiRuntime.standard().execute(overviewPlan);
            overviewOutcome.fold(value -> {
                System.out.println("dashboard  : " + value.dashboard().customer().name());
                System.out.println("catalogue  : " + value.categories().categoryCount() + " categories, depth "
                        + value.categories().depth() + ", " + value.categories().totalStock() + " units");
                System.out.println("deepest    : " + String.join(" > ", value.categories().deepestPath()));
                return null;
            }, error -> {
                System.out.println("failed: " + error.describe());
                return null;
            });

            heading("8. The registry, as published to the generated site");
            MacroRegistry registry = macros.registry();
            registry.entries().forEach(entry ->
                    System.out.printf("%-22s %-14s %s%n", entry.name(),
                            entry.macro().spec().stability(), entry.cost().format()));
            System.out.println();
            System.out.println("reachable endpoints: " + String.join(", ", registry.reachableEndpoints()));
        }
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println(title);
        System.out.println("=".repeat(78));
    }
}
