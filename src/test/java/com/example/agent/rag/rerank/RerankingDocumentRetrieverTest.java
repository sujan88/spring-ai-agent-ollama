package com.example.agent.rag.rerank;

import com.example.agent.rag.Bm25Index;
import com.example.agent.rag.HybridDocumentRetriever;
import com.example.agent.rag.RagRetrieverFactory;
import com.example.agent.rag.RagHybridSettings;
import com.example.agent.rag.RagSearchSettings;
import com.example.agent.rag.rerank.Reranker.Scored;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The second stage: reorder, cut to top-k, drop low scores, and never break the chat when the service fails. */
class RerankingDocumentRetrieverTest {

    private static Document doc(String id) {
        return Document.builder().id(id).text("text " + id).metadata(Map.of("source", id + ".md")).build();
    }

    private static final List<Document> POOL = List.of(doc("a"), doc("b"), doc("c"), doc("d"));

    @Test
    void reordersByRerankScoreAndKeepsTopK() {
        Reranker reranker = (q, texts, n) -> List.of(new Scored(2, 0.9), new Scored(0, 0.4), new Scored(3, 0.1));
        var retriever = new RerankingDocumentRetriever((t, n) -> POOL, reranker, 2, 4, 0.0);

        List<Document> result = retriever.retrieve(new Query("q"));

        assertEquals(List.of("c", "a"), result.stream().map(Document::getId).toList());
        assertEquals(0.9, (Double) result.get(0).getMetadata().get("rerankScore"));
    }

    @Test
    void dropsChunksBelowTheMinimumScore() {
        Reranker reranker = (q, texts, n) -> List.of(new Scored(1, 0.8), new Scored(0, 0.05));
        var retriever = new RerankingDocumentRetriever((t, n) -> POOL, reranker, 3, 4, 0.2);

        assertEquals(List.of("b"), retriever.retrieve(new Query("q")).stream().map(Document::getId).toList());
    }

    @Test
    void asksTheFirstStageForTheWiderCandidateCountAndSendsAllTextsToTheReranker() {
        List<Integer> asked = new ArrayList<>();
        List<Integer> sent = new ArrayList<>();
        Reranker reranker = (q, texts, n) -> { sent.add(texts.size()); return List.of(new Scored(0, 0.5)); };
        var retriever = new RerankingDocumentRetriever((t, n) -> { asked.add(n); return POOL; }, reranker, 3, 10, 0.0);

        retriever.retrieve(new Query("q"));

        assertEquals(List.of(10), asked);
        assertEquals(List.of(4), sent);
    }

    @Test
    void rerankerFailureFallsBackToTheFusedOrder() {
        Reranker broken = (q, texts, n) -> { throw new IllegalStateException("429 quota"); };
        var retriever = new RerankingDocumentRetriever((t, n) -> POOL, broken, 2, 4, 0.5);

        assertEquals(List.of("a", "b"), retriever.retrieve(new Query("q")).stream().map(Document::getId).toList());
    }

    @Test
    void nothingRetrievedMeansNoRerankerCall() {
        Reranker reranker = (q, texts, n) -> { throw new AssertionError("must not be called"); };
        var retriever = new RerankingDocumentRetriever((t, n) -> List.of(), reranker, 3, 10, 0.0);

        assertTrue(retriever.retrieve(new Query("q")).isEmpty());
    }

    @Test
    void factoryAddsTheSecondStageOnlyWhenEnabledAndARerankerExists() {
        VectorStore store = mock(VectorStore.class);
        RagSearchSettings search = new RagSearchSettings(3, 0.55);
        RagHybridSettings hybrid = new RagHybridSettings(true, 20, 60, 0.6);
        Reranker reranker = (q, texts, n) -> List.of();
        RagRerankSettings on = new RagRerankSettings(true, "m", "http://x", 10, 0.0, 1000);

        assertInstanceOf(RerankingDocumentRetriever.class,
                RagRetrieverFactory.retriever(store, search, hybrid, new Bm25Index(), reranker, on));
        assertInstanceOf(HybridDocumentRetriever.class,
                RagRetrieverFactory.retriever(store, search, hybrid, new Bm25Index(), reranker, RagRerankSettings.defaults()));
        assertInstanceOf(HybridDocumentRetriever.class,
                RagRetrieverFactory.retriever(store, search, hybrid, new Bm25Index(), null, on));
    }

    @Test
    void summaryNamesEachKeptChunkWithItsScoreAndOldPosition() {
        List<Document> pool = List.of(
                Document.builder().id("a").text("x").metadata(Map.of("source", "returns-policy.md", "chunkIndex", 0)).build(),
                Document.builder().id("b").text("y").metadata(Map.of("source", "shipping.md", "chunkIndex", 2)).build(),
                Document.builder().id("c").text("z").build());

        String summary = RerankingDocumentRetriever.describe(List.of(new Scored(1, 0.912), new Scored(2, 0.05)), pool);

        assertEquals("[1] shipping.md#2 0.91 (was #2), [2] c 0.05 (was #3)", summary);
        assertEquals("nothing", RerankingDocumentRetriever.describe(List.of(), pool));
    }

    @Test
    void theRerankerScoresEveryCandidateSoTheLogCanShowWhatWasDropped() {
        List<Integer> asked = new ArrayList<>();
        Reranker reranker = (q, texts, n) -> { asked.add(n); return List.of(new Scored(0, 0.5)); };
        new RerankingDocumentRetriever((t, n) -> POOL, reranker, 2, 4, 0.0).retrieve(new Query("q"));

        assertEquals(List.of(4), asked);
    }

    @Test
    void settingsRejectBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new RagRerankSettings(true, "m", "u", 0, 0.0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new RagRerankSettings(true, "m", "u", 5, 1.5, 1000));
        assertThrows(IllegalArgumentException.class, () -> new RagRerankSettings(true, "m", "u", 5, 0.0, 10));
    }
}
