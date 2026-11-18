package com.example.agent.tools;

import com.example.agent.core.confirm.PendingActionService;
import com.example.agent.core.tool.ToolContextKeys;
import com.example.agent.orders.CancelOrderExecutor;
import com.example.agent.orders.InMemoryOrderGateway;
import com.example.agent.orders.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderToolsTest {

    private static final ToolContext ALICE = new ToolContext(Map.of(
            ToolContextKeys.CUSTOMER_ID, "C-1001", ToolContextKeys.SCOPE, "scope-alice"));

    private InMemoryOrderGateway orders;
    private PendingActionService actions;
    private OrderTools tools;

    @BeforeEach
    void setUp() {
        orders = new InMemoryOrderGateway();
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        actions = new PendingActionService(new JdbcTemplate(ds), List.of(new CancelOrderExecutor(orders)), Duration.ofMinutes(5));
        tools = new OrderTools(orders, actions);
    }

    @Test
    void listsOnlyTheCallersOwnOrders() {
        OrderTools.OrderList list = tools.listMyOrders(ALICE);
        assertEquals(3, list.count());
        assertTrue(list.orders().stream().noneMatch(o -> o.orderId().equals("ORD-2001")));
    }

    @Test
    void someoneElsesOrderLooksExactlyLikeAMissingOne() {
        OrderTools.OrderLookup other = tools.getOrder("ORD-2001", ALICE);
        OrderTools.OrderLookup missing = tools.getOrder("ORD-9999", ALICE);
        assertFalse(other.found());
        assertEquals(missing.message(), other.message(), "must not reveal that ORD-2001 exists");
    }

    @Test
    void aMalformedIdAsksForInputInsteadOfGuessing() {
        assertTrue(tools.getOrder("my last order", ALICE).message().startsWith("INPUT_REQUIRED"));
    }

    @Test
    void theReturnWindowIsComputedInJavaNotByTheModel() {
        assertTrue(tools.getOrder("ORD-1001", ALICE).order().returnWindowOpen(), "delivered 10 days ago");
        assertFalse(tools.getOrder("ORD-1003", ALICE).order().returnWindowOpen(), "delivered 45 days ago");
        assertTrue(tools.getOrder("ord-1002 ", ALICE).order().cancellable(), "ids are normalised");
    }

    @Test
    void aDeliveredOrderCannotBeCancelled() {
        OrderTools.ActionResult result = tools.prepareCancellation("ORD-1001", "changed my mind", ALICE);
        assertFalse(result.prepared());
        assertTrue(result.message().startsWith("ORDER_ALREADY_SHIPPED"));
    }

    @Test
    void cancellationIsOnlyADraftUntilTheHumanConfirms() {
        OrderTools.ActionResult result = tools.prepareCancellation("ORD-1002", "wrong size", ALICE);

        assertTrue(result.prepared());
        assertEquals(OrderStatus.PROCESSING, orders.findOrder("ORD-1002").orElseThrow().status(), "nothing cancelled yet");

        PendingActionService.Review review = actions.review("scope-alice").orElseThrow();
        PendingActionService.Outcome outcome = actions.confirm("scope-alice", review.id(), review.confirmationToken());

        assertEquals("DONE", outcome.status());
        assertEquals(OrderStatus.CANCELLED, orders.findOrder("ORD-1002").orElseThrow().status());
    }

    @Test
    void aMissingIdentityIsAWiringBugAndFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> tools.listMyOrders(new ToolContext(Map.of())));
    }
}
