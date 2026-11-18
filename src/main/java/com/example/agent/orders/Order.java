package com.example.agent.orders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** An order as the domain sees it. deliveredOn is null until the order is delivered. */
public record Order(String id, String customerId, OrderStatus status, LocalDate placedOn, LocalDate deliveredOn,
                    List<OrderItem> items, BigDecimal total) {

    public Order {
        items = List.copyOf(items);
    }

    public Order withStatus(OrderStatus newStatus) {
        return new Order(id, customerId, newStatus, placedOn, deliveredOn, items, total);
    }
}
