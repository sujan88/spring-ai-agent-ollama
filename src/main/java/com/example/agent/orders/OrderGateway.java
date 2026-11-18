package com.example.agent.orders;

import java.util.List;
import java.util.Optional;

/**
 * The port between the agent and the system that owns orders.
 *
 * Tools, prompt and controllers depend only on this interface. Swap the adapter
 * (app.orders.gateway=in-memory | your-http-adapter) to move from demo data to a real backend
 * without touching the AI code. Every new backend call goes here first, then into each adapter.
 */
public interface OrderGateway {

    Optional<Order> findOrder(String orderId);

    /** Most recent first. */
    List<Order> ordersForCustomer(String customerId);

    /** Cancels a PROCESSING order. Throws IllegalStateException if it is no longer cancellable. */
    Order cancel(String orderId, String reason);
}
