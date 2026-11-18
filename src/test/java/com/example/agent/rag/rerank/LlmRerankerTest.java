package com.example.agent.rag.rerank;

import com.example.agent.testsupport.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmRerankerTest {

    @Test
    void parsesTheModelsStructuredScoresBestFirst() {
        StubChatModel judge = new StubChatModel("{\"items\":[{\"index\":0,\"score\":2},{\"index\":1,\"score\":9},{\"index\":2,\"score\":5}]}");
        LlmReranker reranker = new LlmReranker(ChatClient.builder(judge).build(), 5000);

        List<Reranker.Scored> top = reranker.rerank("refund time", List.of("weather", "refunds take 5 days", "shipping"), 2);

        assertEquals(List.of(1, 2), top.stream().map(Reranker.Scored::index).toList());
        assertEquals(0.9, top.get(0).score(), 1e-9);
        assertTrue(StubChatModel.text(judge.calls.get(0)).contains("[1] refunds take 5 days"));
    }

    @Test
    void ignoresBadIndexesDuplicatesAndClampsScores() {
        LlmReranker.Scores scores = new LlmReranker.Scores(List.of(
                new LlmReranker.Scores.Item(7, 10), new LlmReranker.Scores.Item(0, 14),
                new LlmReranker.Scores.Item(0, 1), new LlmReranker.Scores.Item(1, -3)));
        List<Reranker.Scored> out = LlmReranker.toScored(scores, 2, 5);
        assertEquals(2, out.size());
        assertEquals(1.0, out.get(0).score(), 1e-9);
        assertEquals(0.0, out.get(1).score(), 1e-9);
    }

    @Test
    void failuresThrowSoTheRetrieverFallsBackToTheFusedOrder() {
        LlmReranker broken = new LlmReranker(ChatClient.builder(StubChatModel.failing()).build(), 5000);
        assertThrows(IllegalStateException.class, () -> broken.rerank("q", List.of("a", "b"), 1));
        assertThrows(IllegalStateException.class, () -> LlmReranker.toScored(new LlmReranker.Scores(List.of()), 2, 1));
    }
}
