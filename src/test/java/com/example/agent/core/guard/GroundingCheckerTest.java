package com.example.agent.core.guard;

import com.example.agent.core.guard.GroundingChecker.Verdict;
import com.example.agent.testsupport.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GroundingCheckerTest {

    private static final List<Document> CONTEXT = List.of(
            new Document("Most items can be returned within 30 days of delivery.", Map.of("source", "returns-policy.md")));

    private static GroundingChecker checker(StubChatModel model) {
        return new GroundingChecker(ChatClient.builder(model).build());
    }

    @Test
    void reportsAnUnsupportedClaimWithItsReason() {
        StubChatModel judge = new StubChatModel("UNSUPPORTED: documents say 30 days, answer says 60.");
        Verdict v = checker(judge).check("How long?", CONTEXT, "You have 60 days.");
        assertTrue(v.checked());
        assertFalse(v.supported());
        assertEquals("documents say 30 days, answer says 60.", v.reason());
        assertTrue(StubChatModel.text(judge.calls.get(0)).contains("returns-policy.md"), "the judge sees the sources");
    }

    @Test
    void acceptsASupportedAnswer() {
        assertTrue(checker(new StubChatModel("SUPPORTED: matches the 30-day rule")).check("q", CONTEXT, "30 days").supported());
    }

    @Test
    void doesNotCallTheModelWithoutContext() {
        StubChatModel judge = new StubChatModel("SUPPORTED");
        assertFalse(checker(judge).check("q", List.of(), "answer").checked());
        assertTrue(judge.calls.isEmpty());
    }

    @Test
    void aFailedOrUnreadableCheckIsReportedAsNotCheckedNeverThrown() {
        assertFalse(checker(StubChatModel.failing()).check("q", CONTEXT, "a").checked());
        assertFalse(GroundingChecker.parse("Maybe?").checked());
    }
}
