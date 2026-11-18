package com.example.agent.rag.query;

import com.example.agent.core.tool.ToolContextKeys;
import com.example.agent.testsupport.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryRewriterTest {

    private static final ToolContext CTX = new ToolContext(Map.of(ToolContextKeys.SCOPE, "s1"));

    private final ChatMemory memory = MessageWindowChatMemory.builder().build();

    private QueryRewriter rewriter(StubChatModel model) {
        return new QueryRewriter(ChatClient.builder(model).build(), memory, 6);
    }

    @Test
    void aFollowUpBecomesAStandaloneQueryUsingTheHistory() {
        memory.add("s1", List.of(new UserMessage("How much is express shipping?"),
                new AssistantMessage("Express shipping costs 15 dollars."),
                new UserMessage("and how long does it take?")));
        StubChatModel model = new StubChatModel("Query: \"express shipping delivery time\"");

        String query = rewriter(model).rewrite("how long does it take", CTX);

        assertEquals("express shipping delivery time", query, "label and quotes are stripped");
        String prompt = StubChatModel.text(model.calls.get(0));
        assertTrue(prompt.contains("express shipping"), "the model sees the conversation");
        assertTrue(prompt.contains("Latest search question: how long does it take"));
    }

    @Test
    void theFirstTurnIsNotRewrittenAndCostsNoModelCall() {
        memory.add("s1", List.of(new UserMessage("How long do card refunds take?")));
        StubChatModel model = new StubChatModel("should not be used");

        assertEquals("card refunds", rewriter(model).rewrite("card refunds", CTX));
        assertTrue(model.calls.isEmpty());
    }

    @Test
    void anyFailureFallsBackToTheOriginalQuestion() {
        memory.add("s1", List.of(new UserMessage("a"), new AssistantMessage("b"), new UserMessage("c")));
        assertEquals("c question", rewriter(StubChatModel.failing()).rewrite("c question", CTX));
    }

    @Test
    void withoutAConversationScopeTheQuestionIsUsedAsIs() {
        StubChatModel model = new StubChatModel("x");
        assertEquals("q", rewriter(model).rewrite("q", new ToolContext(Map.of())));
        assertEquals("q", rewriter(model).rewrite("q", null));
        assertTrue(model.calls.isEmpty());
    }
}
