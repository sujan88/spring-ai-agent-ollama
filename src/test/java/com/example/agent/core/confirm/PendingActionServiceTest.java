package com.example.agent.core.confirm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The two-phase confirm flow on an in-memory H2 database. */
class PendingActionServiceTest {

    private final List<Map<String, String>> executed = new ArrayList<>();
    private MutableClock clock;
    private PendingActionService actions;

    private final PendingActionExecutor recorder = new PendingActionExecutor() {
        public String type() { return "TEST"; }
        public String execute(Map<String, String> payload) {
            executed.add(payload);
            if ("boom".equals(payload.get("mode"))) throw new IllegalStateException("BACKEND_DOWN");
            return "done " + payload.get("orderId");
        }
    };

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        clock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));
        actions = new PendingActionService(new JdbcTemplate(ds), List.of(recorder), Duration.ofMinutes(5), clock);
    }

    private static HttpStatus statusOf(ResponseStatusException e) {
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void prepareDoesNothingUntilAHumanConfirmsWithTheToken() {
        PendingActionService.Draft draft = actions.prepare("s1", "TEST", "Cancel ORD-1", Map.of("orderId", "ORD-1"));
        assertTrue(executed.isEmpty(), "preparing must not execute");

        PendingActionService.Review review = actions.review("s1").orElseThrow();
        assertEquals(draft.id(), review.id());
        assertEquals("Cancel ORD-1", review.summary());

        PendingActionService.Outcome outcome = actions.confirm("s1", review.id(), review.confirmationToken());
        assertEquals("DONE", outcome.status());
        assertEquals(List.of(Map.of("orderId", "ORD-1")), executed);
    }

    @Test
    void aDraftRunsAtMostOnce() {
        actions.prepare("s1", "TEST", "x", Map.of("orderId", "ORD-1"));
        PendingActionService.Review review = actions.review("s1").orElseThrow();
        actions.confirm("s1", review.id(), review.confirmationToken());

        ResponseStatusException again = assertThrows(ResponseStatusException.class,
                () -> actions.confirm("s1", review.id(), review.confirmationToken()));
        assertEquals(HttpStatus.CONFLICT, statusOf(again));
        assertEquals(1, executed.size());
    }

    @Test
    void aWrongTokenOrAnotherConversationCannotConfirm() {
        actions.prepare("s1", "TEST", "x", Map.of("orderId", "ORD-1"));
        PendingActionService.Review review = actions.review("s1").orElseThrow();

        assertEquals(HttpStatus.CONFLICT, statusOf(assertThrows(ResponseStatusException.class,
                () -> actions.confirm("s1", review.id(), "not-the-token"))));
        assertEquals(HttpStatus.CONFLICT, statusOf(assertThrows(ResponseStatusException.class,
                () -> actions.confirm("someone-else", review.id(), review.confirmationToken()))));
        assertTrue(actions.review("someone-else").isEmpty());
        assertTrue(executed.isEmpty());
    }

    @Test
    void anExpiredDraftCanNoLongerBeReviewedOrConfirmed() {
        actions.prepare("s1", "TEST", "x", Map.of("orderId", "ORD-1"));
        PendingActionService.Review review = actions.review("s1").orElseThrow();

        clock.advance(Duration.ofMinutes(6));

        assertTrue(actions.review("s1").isEmpty());
        assertThrows(ResponseStatusException.class, () -> actions.confirm("s1", review.id(), review.confirmationToken()));
        assertTrue(executed.isEmpty());
    }

    @Test
    void aNewDraftReplacesTheOpenOneInTheSameConversation() {
        actions.prepare("s1", "TEST", "first", Map.of("orderId", "ORD-1"));
        actions.prepare("s1", "TEST", "second", Map.of("orderId", "ORD-2"));

        assertEquals("second", actions.review("s1").orElseThrow().summary());
    }

    @Test
    void anExecutorFailureIsReportedAndNotRetried() {
        actions.prepare("s1", "TEST", "x", Map.of("orderId", "ORD-1", "mode", "boom"));
        PendingActionService.Review review = actions.review("s1").orElseThrow();

        PendingActionService.Outcome outcome = actions.confirm("s1", review.id(), review.confirmationToken());

        assertEquals("FAILED", outcome.status());
        assertEquals("BACKEND_DOWN", outcome.message());
        assertThrows(ResponseStatusException.class, () -> actions.confirm("s1", review.id(), review.confirmationToken()));
    }

    @Test
    void anUnknownActionTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> actions.prepare("s1", "NOPE", "x", Map.of()));
    }

    @Test
    void payloadSurvivesSpecialCharacters() {
        Map<String, String> payload = Map.of("reason", "wrong size = 42; \"quotes\" and\nnew line", "orderId", "ORD-1");
        assertEquals(payload, PendingActionService.decode(PendingActionService.encode(payload)));
    }
}
