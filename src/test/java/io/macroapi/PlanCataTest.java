package io.macroapi;

import io.macroapi.demo.Storefront;
import io.macroapi.demo.StorefrontApi;
import io.macroapi.demo.StorefrontMacros;
import io.macroapi.effect.ApiError;
import io.macroapi.effect.Outcome;
import io.macroapi.effect.RetryPolicy;
import io.macroapi.interpret.ApiRuntime;
import io.macroapi.interpret.CallGraph;
import io.macroapi.interpret.Cost;
import io.macroapi.interpret.Interpreters;
import io.macroapi.plan.Plan;
import io.macroapi.plan.Plans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the several interpretations of one plan agree with each other and with reality.
 *
 * <p>These are the tests that would catch the failure mode the whole design exists to prevent:
 * documentation, cost model and executed behaviour drifting apart.</p>
 */
class PlanCataTest {

    private StorefrontApi api;
    private StorefrontMacros macros;

    @BeforeEach
    void setUp() {
        api = new StorefrontApi();
        macros = new StorefrontMacros(api);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    @DisplayName("a constant plan performs no call and costs nothing")
    void pureIsFree() {
        Plan<String> plan = Plans.pure("value");
        assertEquals(Cost.FREE, Interpreters.estimate(plan));
        assertTrue(Interpreters.graph(plan).isEmpty());
        assertEquals(Outcome.success("value"), ApiRuntime.standard().execute(plan));
    }

    @Test
    @DisplayName("independent branches are costed as concurrent, not sequential")
    void combineTakesMaximumLatency() {
        Plan<?> plan = Plans.call(api.getCustomer, "c-1")
                .combine(Plans.call(api.getLoyalty, "c-1"));
        Cost cost = Interpreters.estimate(plan);

        assertEquals(2, cost.callCount());
        // customer.get is 60 ms and loyalty.get is 80 ms; concurrently that is 80, not 140.
        assertEquals(80, cost.latency().toMillis());
    }

    @Test
    @DisplayName("the executed call count matches the statically estimated one")
    void estimateMatchesExecution() {
        Plan<Storefront.Dashboard> plan = macros.customerDashboard().expand("c-1");
        Cost estimate = Interpreters.estimate(plan);

        ApiRuntime.Recording recording = ApiRuntime.recording(RetryPolicy.none());
        Outcome<Storefront.Dashboard> outcome = recording.runtime().execute(plan);

        assertTrue(outcome.isSuccess());
        // The estimate assumes three iterations of the paging loop, and c-1 really does have three
        // pages, so the two agree exactly for this fixture.
        assertEquals(estimate.callCount(), recording.endpointCalls().size());
    }

    @Test
    @DisplayName("every endpoint reached at runtime appears in the derived dependency graph")
    void graphCoversEveryExecutedCall() {
        Plan<Storefront.Dashboard> plan = macros.customerDashboard().expand("c-1");

        List<String> graphed = Interpreters.graph(plan).nodes().stream()
                .filter(node -> node.kind() == CallGraph.NodeKind.ENDPOINT)
                .map(CallGraph.GraphNode::label)
                .distinct()
                .toList();

        ApiRuntime.Recording recording = ApiRuntime.recording(RetryPolicy.none());
        recording.runtime().execute(plan);

        assertTrue(graphed.containsAll(recording.endpointCalls().stream().distinct().toList()),
                "graph " + graphed + " should cover executed calls " + recording.endpointCalls());
    }

    @Test
    @DisplayName("a macro boundary becomes a cluster in the diagram and a heading in the outline")
    void macroBoundaryIsPreserved() {
        Plan<Storefront.CategoryRollup> plan = macros.catalogueRollup().expand("root");

        assertTrue(Interpreters.describe(plan).render().contains("macro: catalogue-rollup"));
        assertTrue(Interpreters.graph(plan).clusters().stream()
                .anyMatch(cluster -> cluster.name().equals("catalogue-rollup")));
    }

    @Test
    @DisplayName("composing macros keeps the inner endpoints visible")
    void macroCompositionIsTransparent() {
        Plan<Storefront.Overview> plan = macros.storefrontOverview()
                .expand(new Storefront.Overview.Request("c-1", "root"));

        List<String> endpoints = Interpreters.graph(plan).nodes().stream()
                .filter(node -> node.kind() == CallGraph.NodeKind.ENDPOINT)
                .map(CallGraph.GraphNode::label)
                .distinct()
                .toList();

        assertTrue(endpoints.contains("customer.get"));
        assertTrue(endpoints.contains("catalog.category"));
        assertTrue(endpoints.contains("orders.page"));
    }

    @Test
    @DisplayName("sealing a macro into an endpoint hides its internals from interpreters")
    void sealedMacroIsOpaque() {
        var sealed = macros.catalogueRollup().asEndpoint(ApiRuntime.standard(), "root");
        Plan<Storefront.CategoryRollup> plan = Plans.call(sealed, "root");

        List<CallGraph.GraphNode> nodes = Interpreters.graph(plan).nodes();
        assertEquals(1, nodes.size(), "a sealed macro should contribute exactly one opaque node");
        assertEquals("catalogue-rollup", nodes.get(0).label());
        // The cost estimate survives sealing, taken from the exemplar expansion.
        assertTrue(Interpreters.estimate(plan).units() > 0);
    }

    @Test
    @DisplayName("a failing dependency is recovered without failing the whole plan")
    void recoveryDegradesGracefully() {
        // c-3's loyalty service answers 503, which the handler treats as recoverable.
        Outcome<Storefront.Dashboard> outcome =
                ApiRuntime.standard().execute(macros.customerDashboard().expand("c-3"));

        Storefront.Dashboard dashboard = outcome.orElseThrow();
        assertEquals(0, dashboard.loyalty().points());
        assertEquals("Grace Hopper", dashboard.customer().name());
    }

    @Test
    @DisplayName("an unrecoverable failure propagates")
    void unknownCustomerFails() {
        Outcome<Storefront.Dashboard> outcome =
                ApiRuntime.standard().execute(macros.customerDashboard().expand("nobody"));

        assertFalse(outcome.isSuccess());
        assertNotNull(outcome.fold(value -> null, ApiError::describe));
    }
}
