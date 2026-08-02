package io.macroapi;

import io.macroapi.demo.Storefront;
import io.macroapi.demo.Storefront.Order;
import io.macroapi.demo.Storefront.OrderPage;
import io.macroapi.demo.Storefront.OrderSummary;
import io.macroapi.demo.StorefrontApi;
import io.macroapi.demo.StorefrontMacros;
import io.macroapi.hkt.Fix;
import io.macroapi.hkt.Recursion;
import io.macroapi.interpret.ApiRuntime;
import io.macroapi.structure.ListF;
import io.macroapi.structure.TreeF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the recursion schemes, and in particular that an algebra behaves identically whether it
 * is applied to a literal structure or fused into a plan that fetches one.
 *
 * <p>That equivalence is the practical argument for separating the algebra from the traversal: the
 * reduction logic can be tested without a network, a fixture server or a clock.</p>
 */
class RecursionSchemeTest {

    private static OrderPage page(String id, String total, String next) {
        return new OrderPage(List.of(new Order(id, new BigDecimal(total), "FULFILLED")),
                Optional.ofNullable(next));
    }

    @Test
    @DisplayName("the order algebra folds a literal page chain")
    void foldsLiteralChain() {
        // A three-page chain built by hand: no API, no plan, no effect.
        Fix<ListF.Witness<OrderPage>> chain =
                Fix.wrap(new ListF.Cons<>(page("o-1", "10.00", "1"),
                        Fix.wrap(new ListF.Cons<>(page("o-2", "20.00", "2"),
                                Fix.wrap(new ListF.Cons<>(page("o-3", "30.00", null),
                                        Fix.wrap(new ListF.Nil<>())))))));

        OrderSummary summary = Recursion.cata(
                ListF.<OrderPage>traversal(), StorefrontMacros.summariseOrders(), chain);

        assertEquals(3, summary.orderCount());
        assertEquals(3, summary.pageCount());
        assertEquals(new BigDecimal("60.00"), summary.total());
        assertEquals(new BigDecimal("30.00"), summary.largest());
    }

    @Test
    @DisplayName("the same algebra fused into a plan agrees with the literal fold")
    void fusedHyloAgreesWithLiteralFold() {
        try (StorefrontApi api = new StorefrontApi()) {
            OrderSummary fetched = ApiRuntime.standard()
                    .execute(new StorefrontMacros(api).orderSummary("c-1"))
                    .orElseThrow();

            // c-1's fixture holds seven orders across three pages of three.
            assertEquals(7, fetched.orderCount());
            assertEquals(3, fetched.pageCount());
            assertEquals(new BigDecimal("1021.49"), fetched.total());
            assertEquals(new BigDecimal("480.00"), fetched.largest());
        }
    }

    @Test
    @DisplayName("an empty history reduces to the algebra's identity")
    void emptyHistoryIsIdentity() {
        try (StorefrontApi api = new StorefrontApi()) {
            OrderSummary empty = ApiRuntime.standard()
                    .execute(new StorefrontMacros(api).orderSummary("c-3"))
                    .orElseThrow();

            assertEquals(0, empty.orderCount());
            assertEquals(BigDecimal.ZERO, empty.averageOrderValue());
        }
    }

    @Test
    @DisplayName("the category algebra folds a literal tree")
    void foldsLiteralTree() {
        Fix<TreeF.Witness<Storefront.Category>> tree =
                Fix.wrap(new TreeF.Node<>(new Storefront.Category("a", "A", 5, List.of("b")),
                        List.of(Fix.wrap(new TreeF.Node<>(
                                new Storefront.Category("b", "B", 7, List.of()), List.of())))));

        Storefront.CategoryRollup rollup = Recursion.cata(
                TreeF.<Storefront.Category>traversal(), StorefrontMacros.rollUpCategories(), tree);

        assertEquals(2, rollup.categoryCount());
        assertEquals(2, rollup.depth());
        assertEquals(12, rollup.totalStock());
        assertEquals(List.of("A", "B"), rollup.deepestPath());
    }

    @Test
    @DisplayName("a tree is expanded over the network and reduced to the same shape")
    void unfoldsAndReducesCategoryTree() {
        try (StorefrontApi api = new StorefrontApi()) {
            Storefront.CategoryRollup rollup = ApiRuntime.standard()
                    .execute(new StorefrontMacros(api).catalogueRollup().expand("root"))
                    .orElseThrow();

            assertEquals(6, rollup.categoryCount());
            assertEquals(3, rollup.depth());
            assertEquals(273, rollup.totalStock());
        }
    }
}
