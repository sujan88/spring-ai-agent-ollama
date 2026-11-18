package com.example.agent.tools;

import com.example.agent.core.confirm.PendingActionService;
import com.example.agent.core.tool.ToolContextKeys;
import com.example.agent.orders.CancelOrderExecutor;
import com.example.agent.orders.Order;
import com.example.agent.orders.OrderGateway;
import com.example.agent.orders.OrderItem;
import com.example.agent.orders.OrderStatus;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The agent's hands for the example domain. Rules every tool here follows (copy them for new tools):
 *
 *   1. ToolContext is the last parameter. Identity comes from it, never from an argument the model filled in.
 *   2. Validate arguments and return a result record with a machine-readable message
 *      ("NOT_FOUND: ...", "INPUT_REQUIRED: ..."), so the model can recover instead of guessing.
 *   3. Do the date maths and business rules in Java (returnWindowOpen, cancellable) and hand the model facts.
 *   4. Never change data directly. Anything irreversible becomes a PendingActionService draft
 *      that a human confirms in the app.
 */
@Component
public class OrderTools {

    public static final int RETURN_WINDOW_DAYS = 30;
    private static final Pattern ORDER_ID = Pattern.compile("ORD-\\d{4}");

    /** What the model sees for one order. */
    public record OrderView(String orderId, OrderStatus status, LocalDate placedOn, LocalDate deliveredOn,
                            List<OrderItem> items, BigDecimal total,
                            boolean cancellable, boolean returnWindowOpen, LocalDate returnableUntil) {}

    public record OrderLookup(boolean found, String message, OrderView order) {}

    public record OrderList(int count, List<OrderView> orders) {}

    public record ActionResult(boolean prepared, String message, String draftId) {}

    private final OrderGateway orders;
    private final PendingActionService actions;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderTools(OrderGateway orders, PendingActionService actions) {
        this(orders, actions, Clock.systemDefaultZone());
    }

    OrderTools(OrderGateway orders, PendingActionService actions, Clock clock) {
        this.orders = orders;
        this.actions = actions;
        this.clock = clock;
    }

    @Tool(name = "listMyOrders", description = "List the signed-in customer's orders, newest first, with status, "
            + "dates, total, whether each can still be cancelled and whether its return window is open. "
            + "Use when the customer asks about 'my orders' or has not given an order id.")
    public OrderList listMyOrders(ToolContext toolContext) {
        String customerId = ToolContextKeys.require(toolContext, ToolContextKeys.CUSTOMER_ID);
        List<OrderView> views = orders.ordersForCustomer(customerId).stream().map(this::view).toList();
        return new OrderList(views.size(), views);
    }

    @Tool(name = "getOrder", description = "Get one of the signed-in customer's orders by id (format ORD-1234): "
            + "status, items, dates, total, cancellable, returnWindowOpen and returnableUntil.")
    public OrderLookup getOrder(@ToolParam(description = "Order id, for example ORD-1001") String orderId,
                                ToolContext toolContext) {
        String customerId = ToolContextKeys.require(toolContext, ToolContextKeys.CUSTOMER_ID);
        if (orderId == null || !ORDER_ID.matcher(orderId.strip().toUpperCase()).matches()) {
            return new OrderLookup(false, "INPUT_REQUIRED: ask the customer for an order id like ORD-1001.", null);
        }
        return ownOrder(orderId.strip().toUpperCase(), customerId)
                .map(o -> new OrderLookup(true, "OK", view(o)))
                .orElseGet(() -> new OrderLookup(false,
                        "NOT_FOUND: no order with that id for this customer. Do not guess; ask them to check the id.", null));
    }

    @Tool(name = "prepareCancellation", description = "Prepare the cancellation of an order that is still PROCESSING. "
            + "This does NOT cancel anything: it creates a draft that the customer must approve with the Confirm "
            + "button in the app. A 'yes' in the chat is not a confirmation. Tell the customer to press Confirm.")
    public ActionResult prepareCancellation(
            @ToolParam(description = "Order id, for example ORD-1002") String orderId,
            @ToolParam(description = "The customer's reason, in their words", required = false) String reason,
            ToolContext toolContext) {
        String customerId = ToolContextKeys.require(toolContext, ToolContextKeys.CUSTOMER_ID);
        String scope = ToolContextKeys.require(toolContext, ToolContextKeys.SCOPE);
        String id = orderId == null ? "" : orderId.strip().toUpperCase();
        Optional<Order> order = ownOrder(id, customerId);
        if (order.isEmpty()) {
            return new ActionResult(false, "NOT_FOUND: no order with that id for this customer.", null);
        }
        if (order.get().status() != OrderStatus.PROCESSING) {
            return new ActionResult(false, "ORDER_ALREADY_SHIPPED: " + id + " is " + order.get().status()
                    + " and cannot be cancelled. Offer a return after delivery instead.", null);
        }
        String why = reason == null ? "" : reason.strip();
        PendingActionService.Draft draft = actions.prepare(scope, CancelOrderExecutor.TYPE,
                "Cancel order " + id + " (" + order.get().total() + ")" + (why.isEmpty() ? "" : ": " + why),
                Map.of("orderId", id, "customerId", customerId, "reason", why));
        return new ActionResult(true, "READY_FOR_CONFIRMATION: nothing is cancelled yet. The customer must press "
                + "Confirm in the app within a few minutes.", draft.id());
    }

    private Optional<Order> ownOrder(String orderId, String customerId) {
        // Someone else's order looks exactly like a missing one, so ids cannot be probed.
        return orders.findOrder(orderId).filter(o -> o.customerId().equals(customerId));
    }

    /** Public so other tool classes (McpTools) show orders the same way. */
    public OrderView view(Order o) {
        LocalDate today = LocalDate.now(clock);
        LocalDate until = o.deliveredOn() == null ? null : o.deliveredOn().plusDays(RETURN_WINDOW_DAYS);
        boolean windowOpen = o.status() == OrderStatus.DELIVERED && until != null && !today.isAfter(until);
        return new OrderView(o.id(), o.status(), o.placedOn(), o.deliveredOn(), o.items(), o.total(),
                o.status() == OrderStatus.PROCESSING, windowOpen, until);
    }
}
