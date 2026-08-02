package io.macroapi.demo;

import io.macroapi.demo.Storefront.Category;
import io.macroapi.demo.Storefront.Concierge;
import io.macroapi.demo.Storefront.Customer;
import io.macroapi.demo.Storefront.Loyalty;
import io.macroapi.demo.Storefront.Order;
import io.macroapi.demo.Storefront.OrderPage;
import io.macroapi.demo.Storefront.OrderPageRequest;
import io.macroapi.demo.Storefront.PageCursor;
import io.macroapi.demo.Storefront.Tier;
import io.macroapi.effect.ApiError;
import io.macroapi.effect.ApiFailure;
import io.macroapi.effect.Eff;
import io.macroapi.effect.Endpoint;
import io.macroapi.effect.EndpointSpec;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simulated set of low-level endpoints, standing in for the several services a real storefront
 * would call.
 *
 * <p>Each endpoint sleeps for its declared typical latency before answering, so the demonstration's
 * wall-clock timings genuinely reflect the concurrency the interpreter achieves — a fan-out really
 * does finish in the time of its slowest branch. Bodies run on a virtual-thread executor, which is
 * the natural fit for blocking I/O.</p>
 *
 * <p>The loyalty endpoint fails for one known customer, which exercises the recovery path.</p>
 */
public final class StorefrontApi implements AutoCloseable {

    /** Creates the simulated API, starting a virtual-thread executor for its endpoint bodies. */
    public StorefrontApi() {
    }

    private static final int PAGE_SIZE = 3;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger callCount = new AtomicInteger();

    private final Map<String, Customer> customers = Map.of(
            "c-1", new Customer("c-1", "Ada Lovelace", Tier.PLATINUM),
            "c-2", new Customer("c-2", "Alan Turing", Tier.STANDARD),
            "c-3", new Customer("c-3", "Grace Hopper", Tier.STANDARD));

    private final Map<String, List<Order>> orders = Map.of(
            "c-1", List.of(
                    order("o-1", "120.00"), order("o-2", "35.50"), order("o-3", "480.00"),
                    order("o-4", "18.25"), order("o-5", "260.75"), order("o-6", "94.00"),
                    order("o-7", "12.99")),
            "c-2", List.of(order("o-8", "55.00"), order("o-9", "23.10")),
            "c-3", List.of());

    private final Map<String, Category> categories = Map.of(
            "root", new Category("root", "All products", 0, List.of("apparel", "tech")),
            "apparel", new Category("apparel", "Apparel", 120, List.of("shoes")),
            "shoes", new Category("shoes", "Shoes", 64, List.of()),
            "tech", new Category("tech", "Technology", 30, List.of("laptops", "audio")),
            "laptops", new Category("laptops", "Laptops", 12, List.of()),
            "audio", new Category("audio", "Audio", 47, List.of()));

    private static Order order(String id, String total) {
        return new Order(id, new BigDecimal(total), "FULFILLED");
    }

    /** Reads one customer by identifier. */
    public final Endpoint<String, Customer> getCustomer = Endpoint.of(
            EndpointSpec.read("customer.get", "/customers/{id}", "crm").withLatency(Duration.ofMillis(60)),
            id -> call("customer.get", Duration.ofMillis(60), () -> {
                Customer found = customers.get(id);
                if (found == null) {
                    throw new ApiFailure(new ApiError.NotFound("customer", id));
                }
                return found;
            }));

    /** Reads a loyalty balance; deliberately unavailable for customer {@code c-3}. */
    public final Endpoint<String, Loyalty> getLoyalty = Endpoint.of(
            EndpointSpec.read("loyalty.get", "/loyalty/{customerId}", "rewards").withLatency(Duration.ofMillis(80)),
            id -> call("loyalty.get", Duration.ofMillis(80), () -> {
                if ("c-3".equals(id)) {
                    throw new ApiFailure(new ApiError.Remote("loyalty.get", 503, "rewards service unavailable"));
                }
                return new Loyalty(id, id.hashCode() & 0x3ff);
            }));

    /** Reads one page of a customer's order history. */
    public final Endpoint<OrderPageRequest, OrderPage> listOrders = Endpoint.of(
            EndpointSpec.read("orders.page", "/customers/{id}/orders", "sales")
                    .withLatency(Duration.ofMillis(70)).withCost(2),
            request -> call("orders.page", Duration.ofMillis(70), () -> page(request)));

    /** Reads one category node, including the identifiers of its children. */
    public final Endpoint<String, Category> getCategory = Endpoint.of(
            EndpointSpec.read("catalog.category", "/categories/{id}", "catalog").withLatency(Duration.ofMillis(45)),
            id -> call("catalog.category", Duration.ofMillis(45), () -> {
                Category found = categories.get(id);
                if (found == null) {
                    throw new ApiFailure(new ApiError.NotFound("category", id));
                }
                return found;
            }));

    /** Reads the concierge assigned to a high-value customer. */
    public final Endpoint<String, Concierge> getConcierge = Endpoint.of(
            EndpointSpec.read("concierge.get", "/concierge/{customerId}", "crm", "premium")
                    .withLatency(Duration.ofMillis(90)).withCost(3),
            id -> call("concierge.get", Duration.ofMillis(90),
                    () -> new Concierge("Jean Bartik", "+44 20 7946 " + Math.abs(id.hashCode() % 10000))));

    private OrderPage page(OrderPageRequest request) {
        List<Order> all = orders.getOrDefault(request.customerId(), List.of());
        int from = switch (request.cursor()) {
            case PageCursor.First() -> 0;
            case PageCursor.Next(String token) -> Integer.parseInt(token);
            case PageCursor.Exhausted() -> all.size();
        };
        int to = Math.min(from + PAGE_SIZE, all.size());
        List<Order> slice = from >= to ? List.of() : all.subList(from, to);
        Optional<String> next = to < all.size() ? Optional.of(Integer.toString(to)) : Optional.empty();
        return new OrderPage(slice, next);
    }

    private <A> Eff<A> call(String endpointName, Duration latency, java.util.concurrent.Callable<A> body) {
        return Eff.async(executor, endpointName, () -> {
            callCount.incrementAndGet();
            Thread.sleep(latency.toMillis());
            return body.call();
        });
    }

    /**
     * How many endpoint bodies have actually executed, including retried attempts.
     *
     * <p>Compared against the cost interpreter's static estimate in the demonstration.</p>
     *
     * @return the running total
     */
    public int callCount() {
        return callCount.get();
    }

    /** Shuts the executor down; the API is unusable afterwards. */
    @Override
    public void close() {
        executor.close();
    }
}
