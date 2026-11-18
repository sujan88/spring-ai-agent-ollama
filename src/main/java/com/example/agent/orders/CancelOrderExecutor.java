package com.example.agent.orders;

import com.example.agent.core.confirm.PendingActionExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Runs a confirmed CANCEL_ORDER draft. Re-checks ownership and status: the order may have shipped meanwhile. */
@Component
public class CancelOrderExecutor implements PendingActionExecutor {

    public static final String TYPE = "CANCEL_ORDER";

    private final OrderGateway orders;

    public CancelOrderExecutor(OrderGateway orders) {
        this.orders = orders;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String execute(Map<String, String> payload) {
        String orderId = payload.get("orderId");
        Order order = orders.findOrder(orderId)
                .filter(o -> o.customerId().equals(payload.get("customerId")))
                .orElseThrow(() -> new IllegalStateException("ORDER_NOT_FOUND"));
        if (order.status() != OrderStatus.PROCESSING) {
            throw new IllegalStateException("ORDER_ALREADY_SHIPPED: " + orderId + " is " + order.status() + " and can no longer be cancelled");
        }
        orders.cancel(orderId, payload.getOrDefault("reason", ""));
        return "Order " + orderId + " is cancelled. The refund goes back to the original payment method.";
    }
}
