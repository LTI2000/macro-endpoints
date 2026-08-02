package io.macroapi.demo;

import io.macroapi.demo.Storefront.Category;
import io.macroapi.demo.Storefront.CategoryRollup;
import io.macroapi.demo.Storefront.Customer;
import io.macroapi.demo.Storefront.Dashboard;
import io.macroapi.demo.Storefront.Loyalty;
import io.macroapi.demo.Storefront.OrderPage;
import io.macroapi.demo.Storefront.OrderPageRequest;
import io.macroapi.demo.Storefront.OrderSummary;
import io.macroapi.demo.Storefront.Overview;
import io.macroapi.demo.Storefront.PageCursor;
import io.macroapi.demo.Storefront.Tier;
import io.macroapi.effect.ApiError;
import io.macroapi.hkt.Algebra;
import io.macroapi.hkt.Higher;
import io.macroapi.macro.Macro;
import io.macroapi.macro.MacroRegistry;
import io.macroapi.macro.MacroSpec;
import io.macroapi.plan.Plan;
import io.macroapi.plan.PlanCoalgebra;
import io.macroapi.plan.Plans;
import io.macroapi.structure.ListF;
import io.macroapi.structure.TreeF;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The worked example: three macros built over {@link StorefrontApi}, each composing low-level calls
 * into a higher-level one.
 *
 * <p>Read in order, they show the facility's three composition patterns. {@code order-summary} folds
 * an unbounded sequence of calls into a single value. {@code customer-dashboard} fans out
 * independent calls, degrades gracefully when one fails, and branches on a result. {@code
 * storefront-overview} composes macros with macros, and its diagram still names every underlying
 * endpoint.</p>
 */
public final class StorefrontMacros {

    private final StorefrontApi api;

    /**
     * Binds the macros to a set of low-level endpoints.
     *
     * @param api the endpoints to compose
     */
    public StorefrontMacros(StorefrontApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    // ---------------------------------------------------------------------------------------
    // Algebras: the reductions applied to intermediate results.
    // Each is an ordinary value, independent of how the structure it consumes was obtained.
    // ---------------------------------------------------------------------------------------

    /**
     * Reduces a chain of order pages to a single summary.
     *
     * <p>Two cases and no loop: an exhausted chain is the empty summary, and a page is merged into
     * the already-reduced remainder. That the recursion is a network paging loop is nowhere visible
     * here, which is exactly why the same algebra can be unit-tested against a literal chain.</p>
     *
     * @return the algebra over the page-chain shape
     */
    public static Algebra<ListF.Witness<OrderPage>, OrderSummary> summariseOrders() {
        return layer -> switch (ListF.narrow(layer)) {
            case ListF.Nil<OrderPage, OrderSummary>() ->
                    OrderSummary.EMPTY;
            case ListF.Cons<OrderPage, OrderSummary>(var page, var rest) ->
                    OrderSummary.ofPage(page).merge(rest);
        };
    }

    /**
     * Reduces a category sub-tree to a roll-up.
     *
     * <p>The children arrive already reduced, so the node only has to combine them: sum the counts
     * and stock, take the deepest child path and extend it with this node's name.</p>
     *
     * @return the algebra over the category-tree shape
     */
    public static Algebra<TreeF.Witness<Category>, CategoryRollup> rollUpCategories() {
        return layer -> switch (TreeF.narrow(layer)) {
            case TreeF.Node<Category, CategoryRollup>(var category, var children) -> {
                int count = 1;
                int stock = category.stock();
                int deepest = 0;
                List<String> deepestPath = List.of();
                for (CategoryRollup child : children) {
                    count += child.categoryCount();
                    stock += child.totalStock();
                    if (child.depth() > deepest) {
                        deepest = child.depth();
                        deepestPath = child.deepestPath();
                    }
                }
                List<String> path = new ArrayList<>();
                path.add(category.name());
                path.addAll(deepestPath);
                yield new CategoryRollup(count, deepest + 1, stock, path);
            }
        };
    }

    // ---------------------------------------------------------------------------------------
    // Coalgebras: the rules that grow a recursive structure by calling APIs.
    // ---------------------------------------------------------------------------------------

    /**
     * Grows the page chain for one customer, one API call per layer.
     *
     * <p>The seed is a {@link PageCursor}; each step fetches a page and decides, from the returned
     * cursor, whether the next seed continues the chain or terminates it. This is the whole paging
     * loop — there is no {@code while}, no accumulator and no mutable cursor variable, because the
     * recursion scheme supplies all three.</p>
     *
     * @param customerId whose orders to page through
     * @return the coalgebra
     */
    public PlanCoalgebra<ListF.Witness<OrderPage>, PageCursor> orderPages(String customerId) {
        return cursor -> switch (cursor) {
            // A terminated chain closes the structure without a further call.
            case PageCursor.Exhausted() ->
                    Plans.pure(new ListF.Nil<>());
            // Both live cursors fetch identically; a record pattern may not share a case label with
            // another pattern, so the two are listed separately rather than combined.
            case PageCursor.First() ->
                    fetchPage(customerId, cursor);
            case PageCursor.Next(var ignoredToken) ->
                    fetchPage(customerId, cursor);
        };
    }

    private Plan<Higher<ListF.Witness<OrderPage>, PageCursor>> fetchPage(String customerId,
                                                                         PageCursor cursor) {
        return Plans.call(api.listOrders, new OrderPageRequest(customerId, cursor))
                .map(page -> new ListF.Cons<>(page, nextCursor(page)), "advance cursor");
    }

    private static PageCursor nextCursor(OrderPage page) {
        return page.nextCursor()
                .<PageCursor>map(PageCursor.Next::new)
                .orElseGet(PageCursor.Exhausted::new);
    }

    /**
     * Grows the category tree from a root identifier, one API call per node.
     *
     * <p>A node's children are returned as identifiers, which become the next seeds. Because a tree
     * layer holds many recursive positions and the effect's applicative combines in parallel, every
     * level of the tree is fetched concurrently.</p>
     *
     * @return the coalgebra
     */
    public PlanCoalgebra<TreeF.Witness<Category>, String> categoryTree() {
        return categoryId -> Plans.call(api.getCategory, categoryId)
                .map(category -> new TreeF.Node<>(category, category.childIds()), "expand children");
    }

    // ---------------------------------------------------------------------------------------
    // Plans and macros.
    // ---------------------------------------------------------------------------------------

    /**
     * The order history of one customer, reduced to a summary.
     *
     * <p>A hylomorphism: the coalgebra pages forward, the algebra reduces, and no list of pages is
     * ever held in memory.</p>
     *
     * @param customerId whose history to summarise
     * @return the plan
     */
    public Plan<OrderSummary> orderSummary(String customerId) {
        return Plans.hylo(
                new PageCursor.First(),
                ListF.traversal(),
                orderPages(customerId),
                summariseOrders(),
                "page through orders");
    }

    /**
     * The loyalty balance, degraded to a placeholder if the rewards service cannot answer.
     *
     * <p>The handler dispatches on the failure with record patterns: a missing account and an
     * unavailable service both yield a zero balance, since a dashboard is more useful degraded than
     * absent, while anything else is re-raised rather than quietly swallowed.</p>
     *
     * @param customerId whose balance to read
     * @return the guarded plan
     */
    public Plan<Loyalty> loyaltyOrDefault(String customerId) {
        return Plans.call(api.getLoyalty, customerId)
                .recoverWith(error -> switch (error) {
                    case ApiError.NotFound(var resource, var key) ->
                            Plans.pure(Loyalty.unknown(customerId));
                    case ApiError.Remote(var endpoint, var status, var detail) when status >= 500 ->
                            Plans.pure(Loyalty.unknown(customerId));
                    case ApiError.Timeout(var endpoint, var elapsed) ->
                            Plans.pure(Loyalty.unknown(customerId));
                    default ->
                            Plans.failed(error);
                });
    }

    /**
     * Everything a customer dashboard needs, from one call.
     *
     * <p>Three independent reads — the customer record, the guarded loyalty balance and the paged
     * order summary — are combined with {@code parallel}, so all three are in flight at once and the
     * whole thing costs the slowest rather than the sum. The result is then chained into a
     * conditional: only a platinum customer triggers the concierge lookup. A static probe is
     * supplied so that documentation and diagrams can still show that branch.</p>
     *
     * @return the macro
     */
    public Macro<String, Dashboard> customerDashboard() {
        return Macro.of(
                MacroSpec.of("customer-dashboard",
                        "Customer record, loyalty balance, full order history summary, and concierge "
                                + "assignment for platinum customers.",
                        "crm", "read-model"),
                customerId -> Plans.parallel(
                                Plans.call(api.getCustomer, customerId),
                                loyaltyOrDefault(customerId),
                                orderSummary(customerId),
                                (customer, loyalty, summary) ->
                                        new Dashboard(customer, loyalty, summary, Optional.empty()),
                                "gather profile")
                        .flatMap(this::attachConciergeIfPlatinum,
                                probeDashboard(customerId),
                                "platinum customers get a concierge"));
    }

    private Plan<Dashboard> attachConciergeIfPlatinum(Dashboard dashboard) {
        return switch (dashboard.customer().tier()) {
            case PLATINUM -> Plans.call(api.getConcierge, dashboard.customer().id())
                    .map(dashboard::withConcierge, "attach concierge");
            case STANDARD -> Plans.pure(dashboard);
        };
    }

    /**
     * A fabricated dashboard used only as a {@code Chain} probe.
     *
     * <p>It names the platinum tier so that static analysis explores the branch that makes an extra
     * call — the conservative choice for a cost budget, and the more informative one for a diagram.
     * The value never reaches execution.</p>
     */
    private static Dashboard probeDashboard(String customerId) {
        return new Dashboard(
                new Customer(customerId, "<probe>", Tier.PLATINUM),
                Loyalty.unknown(customerId),
                OrderSummary.EMPTY,
                Optional.empty());
    }

    /**
     * The catalogue tree below a root category, rolled up.
     *
     * @return the macro
     */
    public Macro<String, CategoryRollup> catalogueRollup() {
        return Macro.of(
                MacroSpec.of("catalogue-rollup",
                        "Walks the category tree from a root and rolls up node count, depth and stock.",
                        "catalog", "read-model"),
                rootId -> Plans.hylo(
                        rootId,
                        TreeF.traversal(),
                        categoryTree(),
                        rollUpCategories(),
                        "walk category tree"));
    }

    /**
     * A macro composed from two other macros.
     *
     * <p>The point of the example: expansion is transparent, so folding this plan yields one graph
     * naming every endpoint both children reach, grouped into a box per macro. Had the children been
     * sealed with {@link Macro#asEndpoint} instead, the same fold would show two opaque leaves —
     * both are available, and the choice is explicit.</p>
     *
     * @return the macro
     */
    public Macro<Overview.Request, Overview> storefrontOverview() {
        Macro<String, Dashboard> dashboard = customerDashboard();
        Macro<String, CategoryRollup> catalogue = catalogueRollup();
        return Macro.of(
                MacroSpec.of("storefront-overview",
                        "Combines a customer dashboard with a catalogue roll-up in a single response.",
                        "aggregate"),
                request -> dashboard.expand(request.customerId())
                        .combine(catalogue.expand(request.rootCategoryId()), Overview::new, "overview"));
    }

    /**
     * A registry of all three macros with representative requests, ready for site generation.
     *
     * @return the populated registry
     */
    public MacroRegistry registry() {
        return new MacroRegistry()
                .register(customerDashboard(), "c-1")
                .register(catalogueRollup(), "root")
                .register(storefrontOverview(), new Overview.Request("c-1", "root"));
    }
}
