package io.macroapi.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The domain of the worked example: a storefront exposing customers, orders and a category tree.
 *
 * <p>All types are records, and the ones that appear as intermediate results are deliberately small
 * so that the algebras which reduce them stay readable.</p>
 */
public final class Storefront {

    private Storefront() {
        throw new AssertionError("no instances");
    }

    /** How much a customer is worth to the business, which drives one conditional branch. */
    public enum Tier {
        /** Standard customer. */
        STANDARD,
        /** High-value customer, eligible for concierge service. */
        PLATINUM
    }

    /**
     * A customer record.
     *
     * @param id   the customer identifier
     * @param name the display name
     * @param tier the value tier
     */
    public record Customer(String id, String name, Tier tier) {
    }

    /**
     * A loyalty account.
     *
     * @param customerId the owning customer
     * @param points     the accrued balance
     */
    public record Loyalty(String customerId, int points) {
        /**
         * The placeholder used when the loyalty service cannot answer, so that a dashboard degrades
         * rather than fails.
         *
         * @param customerId the customer the placeholder stands in for
         * @return a zero-balance account
         */
        public static Loyalty unknown(String customerId) {
            return new Loyalty(customerId, 0);
        }
    }

    /**
     * A single order.
     *
     * @param id     the order identifier
     * @param total  the order value
     * @param status the fulfilment status
     */
    public record Order(String id, BigDecimal total, String status) {
    }

    /**
     * One page of a customer's order history.
     *
     * @param orders     the orders on this page
     * @param nextCursor the cursor for the following page, empty on the last page
     */
    public record OrderPage(List<Order> orders, Optional<String> nextCursor) {
        /**
         * Canonical constructor; defensively copies.
         *
         * @param orders     the page contents
         * @param nextCursor the following cursor
         */
        public OrderPage {
            orders = List.copyOf(orders);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    /**
     * The seed of the paging unfold: where the next request should start, or that there is none.
     *
     * <p>A sealed type rather than a nullable cursor, so that "start from the beginning" and
     * "nothing left to fetch" cannot be confused — the paging coalgebra dispatches on it with an
     * exhaustive switch.</p>
     */
    public sealed interface PageCursor {
        /** Fetch the first page. */
        record First() implements PageCursor {
        }

        /**
         * Fetch the page identified by a cursor.
         *
         * @param token the opaque cursor returned by the previous page
         */
        record Next(String token) implements PageCursor {
        }

        /** Nothing remains to fetch. */
        record Exhausted() implements PageCursor {
        }
    }

    /**
     * A request for one page of a customer's orders.
     *
     * @param customerId whose orders to read
     * @param cursor     where to start
     */
    public record OrderPageRequest(String customerId, PageCursor cursor) {
    }

    /**
     * The reduction of a whole order history, produced by a catamorphism over the page chain.
     *
     * @param orderCount how many orders were seen
     * @param pageCount  how many pages were fetched
     * @param total      the sum of all order values
     * @param largest    the value of the largest single order
     */
    public record OrderSummary(int orderCount, int pageCount, BigDecimal total, BigDecimal largest) {

        /** The identity of the summary monoid: what an empty history reduces to. */
        public static final OrderSummary EMPTY = new OrderSummary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO);

        /**
         * Summarises a single page in isolation.
         *
         * @param page the page to summarise
         * @return the one-page summary
         */
        public static OrderSummary ofPage(OrderPage page) {
            BigDecimal sum = page.orders().stream()
                    .map(Order::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal max = page.orders().stream()
                    .map(Order::total)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            return new OrderSummary(page.orders().size(), 1, sum, max);
        }

        /**
         * Combines two summaries; associative with {@link #EMPTY} as its unit, which is what makes
         * the fold well defined however the page chain is bracketed.
         *
         * @param other the summary to merge in
         * @return the merged summary
         */
        public OrderSummary merge(OrderSummary other) {
            return new OrderSummary(
                    orderCount + other.orderCount,
                    pageCount + other.pageCount,
                    total.add(other.total),
                    largest.max(other.largest));
        }

        /**
         * The mean order value, or zero for an empty history.
         *
         * @return the average, to two decimal places
         */
        public BigDecimal averageOrderValue() {
            return orderCount == 0
                    ? BigDecimal.ZERO
                    : total.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);
        }
    }

    /**
     * A node of the product category tree.
     *
     * @param id       the category identifier
     * @param name     the display name
     * @param stock    units held directly in this category
     * @param childIds the identifiers of the sub-categories
     */
    public record Category(String id, String name, int stock, List<String> childIds) {
        /**
         * Canonical constructor; defensively copies.
         *
         * @param id       the identifier
         * @param name     the display name
         * @param stock    direct stock
         * @param childIds sub-category identifiers
         */
        public Category {
            childIds = List.copyOf(childIds);
        }
    }

    /**
     * The reduction of a category sub-tree, produced by a catamorphism over the tree.
     *
     * @param categoryCount nodes in the sub-tree, including the root
     * @param depth         longest path from the root, counting the root as one
     * @param totalStock    stock rolled up from the whole sub-tree
     * @param deepestPath   the names along the longest path, root first
     */
    public record CategoryRollup(int categoryCount, int depth, int totalStock, List<String> deepestPath) {
        /**
         * Canonical constructor; defensively copies.
         *
         * @param categoryCount node count
         * @param depth         tree depth
         * @param totalStock    rolled-up stock
         * @param deepestPath   names along the longest path
         */
        public CategoryRollup {
            deepestPath = List.copyOf(deepestPath);
        }
    }

    /**
     * A customer's concierge assignment, fetched only for the platinum tier.
     *
     * @param agentName the assigned agent
     * @param phone     the direct line
     */
    public record Concierge(String agentName, String phone) {
    }

    /**
     * The high-level view a single call to the dashboard macro produces.
     *
     * @param customer  the customer record
     * @param loyalty   the loyalty balance, possibly a degraded placeholder
     * @param orders    the reduction of the entire order history
     * @param concierge the concierge assignment, present only for platinum customers
     */
    public record Dashboard(Customer customer, Loyalty loyalty, OrderSummary orders, Optional<Concierge> concierge) {
        /**
         * Returns a copy with a concierge attached.
         *
         * @param assigned the concierge to attach
         * @return the enriched dashboard
         */
        public Dashboard withConcierge(Concierge assigned) {
            return new Dashboard(customer, loyalty, orders, Optional.of(assigned));
        }
    }

    /**
     * The reduction produced by the top-level overview macro.
     *
     * @param dashboard  the customer view
     * @param categories the catalogue roll-up
     */
    public record Overview(Dashboard dashboard, CategoryRollup categories) {

        /**
         * The request an overview is built from.
         *
         * @param customerId     whose dashboard to build
         * @param rootCategoryId the category to roll up from
         */
        public record Request(String customerId, String rootCategoryId) {
        }
    }
}
