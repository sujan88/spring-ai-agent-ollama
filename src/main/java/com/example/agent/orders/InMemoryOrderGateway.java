package com.example.agent.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seeded, in-memory adapter for demos and tests. Dates are relative to today so the return window
 * examples keep working whenever you run it:
 *   C-1001: ORD-1001 delivered 10 days ago (returnable), ORD-1002 processing (cancellable),
 *           ORD-1003 delivered 45 days ago (outside the 30-day window)
 *   C-2002: ORD-2001 shipped (someone else's order: the tools must not show it to C-1001)
 */
@Component
@ConditionalOnProperty(name = "app.orders.gateway", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryOrderGateway implements OrderGateway {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public InMemoryOrderGateway() {
        LocalDate today = LocalDate.now();
        put(new Order("ORD-1001", "C-1001", OrderStatus.DELIVERED, today.minusDays(14), today.minusDays(10),
                List.of(new OrderItem("SKU-1042", "Trail running shoes", 1, new BigDecimal("129.00"))), new BigDecimal("129.00")));
        put(new Order("ORD-1002", "C-1001", OrderStatus.PROCESSING, today.minusDays(1), null,
                List.of(new OrderItem("SKU-2210", "Rain jacket", 1, new BigDecimal("89.50")),
                        new OrderItem("SKU-0007", "Wool socks (3 pack)", 2, new BigDecimal("18.00"))), new BigDecimal("125.50")));
        put(new Order("ORD-1003", "C-1001", OrderStatus.DELIVERED, today.minusDays(50), today.minusDays(45),
                List.of(new OrderItem("SKU-3301", "Insulated bottle", 1, new BigDecimal("32.00"))), new BigDecimal("32.00")));
        put(new Order("ORD-2001", "C-2002", OrderStatus.SHIPPED, today.minusDays(3), null,
                List.of(new OrderItem("SKU-5150", "Camping stove", 1, new BigDecimal("74.99"))), new BigDecimal("74.99")));
    }

    private void put(Order order) {
        orders.put(order.id(), order);
    }

    @Override
    public Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public List<Order> ordersForCustomer(String customerId) {
        return orders.values().stream()
                .filter(o -> o.customerId().equals(customerId))
                .sorted(Comparator.comparing(Order::placedOn).reversed())
                .toList();
    }

    @Override
    public Order cancel(String orderId, String reason) {
        return orders.compute(orderId, (id, order) -> {
            if (order == null) throw new IllegalStateException("ORDER_NOT_FOUND");
            if (order.status() != OrderStatus.PROCESSING) throw new IllegalStateException("ORDER_ALREADY_SHIPPED");
            return order.withStatus(OrderStatus.CANCELLED);
        });
    }
}
