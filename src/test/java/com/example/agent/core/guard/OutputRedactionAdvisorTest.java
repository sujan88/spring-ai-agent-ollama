package com.example.agent.core.guard;

import com.example.agent.testsupport.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutputRedactionAdvisorTest {

    private static final String ANSWER = "Your card 4111 1111 1111 1111 is on file for ORD-1002.";
    private static final String MASKED = "Your card [REDACTED CARD] is on file for ORD-1002.";

    private final ChatClient client = ChatClient.builder(new StubChatModel(ANSWER))
            .defaultAdvisors(new OutputRedactionAdvisor())
            .build();

    @Test
    void masksTheCallAnswer() {
        assertEquals(MASKED, client.prompt().user("x").call().content());
    }

    @Test
    void masksANumberThatArrivesSplitAcrossStreamChunks() {
        // StubChatModel streams the answer in two halves; the split falls inside the card number.
        List<String> chunks = client.prompt().user("x").stream().content().collectList().block();
        assertNotNull(chunks);
        assertEquals(MASKED, String.join("", chunks));
        assertTrue(chunks.stream().noneMatch(c -> c.contains("4111")), "no chunk may leak digits: " + chunks);
    }
}
