package com.example.agent.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hybrid retriever with a mocked vector store and a real keyword index: fusion, the relevance
 * gates, and what is passed to the vector store.
 */
class HybridDocumentRetrieverTest {

    private VectorStore vectorStore;
    private Bm25Index keywordIndex;

    private static Document chunk(String id, String text) {
        return Document.builder().id(id).text(text).metadata("source", id + ".md").build();
    }

    private static Document denseHit(String id, String text, double score) {
        return Document.builder().id(id).text(text).metadata("source", id + ".md").score(score).build();
    }

    private static final String FLAG_TEXT = "Partial refunds are issued only when AllowPartialRefund = true is set on the order.";
    private static final String COUPON_TEXT = "Coupon restrictions limit how many coupon redemptions a shopper can make each month.";
    private static final String SAGA_TEXT = "Charge first, then save the shipment. A later failure voids the charge.";
    private static final String CHECKOUT_TEXT = "The checkout engine supports several channels.";

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        keywordIndex = new Bm25Index();
        keywordIndex.replaceAll(List.of(
                chunk("flag", FLAG_TEXT), chunk("coupon", COUPON_TEXT), chunk("saga", SAGA_TEXT), chunk("checkout", CHECKOUT_TEXT)));
    }

    private HybridDocumentRetriever retriever(int topK, double threshold, double minTermMatch) {
        return new HybridDocumentRetriever(vectorStore, keywordIndex,
                new RagSearchSettings(topK, threshold), new RagHybridSettings(true, 20, 60, minTermMatch));
    }

    private void vectorReturns(Document... docs) {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docs));
    }

    private static List<String> ids(List<Document> docs) {
        return docs.stream().map(Document::getId).toList();
    }

    @Test
    void aChunkOnlyKeywordSearchFindsIsAddedToTheVectorResults() {
        vectorReturns(denseHit("coupon", COUPON_TEXT, 0.80));

        List<Document> result = retriever(3, 0.55, 0.6).retrieve(new Query("What does the AllowPartialRefund flag do?"));

        assertTrue(ids(result).containsAll(List.of("coupon", "flag")), "got " + ids(result));
        Document flag = result.stream().filter(d -> d.getId().equals("flag")).findFirst().orElseThrow();
        assertEquals("bm25", flag.getMetadata().get("retrievedBy"));
        Document coupon = result.stream().filter(d -> d.getId().equals("coupon")).findFirst().orElseThrow();
        assertEquals("vector", coupon.getMetadata().get("retrievedBy"));
    }

    @Test
    void aChunkBothRetrieversFindRanksFirstAndRecordsBothScores() {
        // The vector side puts "coupon" first. Both sides find "flag", so it should win after fusion.
        vectorReturns(denseHit("coupon", COUPON_TEXT, 0.80), denseHit("flag", FLAG_TEXT, 0.70));

        List<Document> result = retriever(3, 0.55, 0.6).retrieve(new Query("What does the AllowPartialRefund flag do?"));

        assertEquals("flag", result.get(0).getId());
        assertEquals("vector+bm25", result.get(0).getMetadata().get("retrievedBy"));
        assertEquals(0.70, (Double) result.get(0).getMetadata().get("denseScore"), 1e-9);
        assertNotNull(result.get(0).getMetadata().get("keywordScore"));
        assertTrue(result.get(0).getScore() > result.get(1).getScore(), "fused score decides the order");
    }

    @Test
    void theVectorStoreGetsTheProductionThresholdWhenGatedAndZeroForRanking() {
        vectorReturns();
        HybridDocumentRetriever hybrid = retriever(3, 0.55, 0.6);

        hybrid.retrieve(new Query("coupon redemptions"));
        hybrid.rank("coupon redemptions", 5);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, org.mockito.Mockito.times(2)).similaritySearch(captor.capture());
        SearchRequest gated = captor.getAllValues().get(0);
        SearchRequest ranking = captor.getAllValues().get(1);
        assertEquals(0.55, gated.getSimilarityThreshold(), 1e-9);
        assertEquals(0.0, ranking.getSimilarityThreshold(), 1e-9, "ranking for evaluation must not hide low scores");
        assertEquals(20, gated.getTopK(), "each retriever contributes its own candidate pool before fusion");
    }

    @Test
    void anUnrelatedQuestionReturnsNothing() {
        vectorReturns(); // nothing passes the vector threshold
        assertTrue(retriever(3, 0.55, 0.6).retrieve(new Query("What is the capital of France?")).isEmpty());
    }

    @Test
    void aWeakKeywordMatchIsGatedOutButStillVisibleWhenRanking() {
        vectorReturns();
        // "checkout" and "engine" match, "reset" and "password" do not: 2 of 4 terms is under the 0.6 gate.
        HybridDocumentRetriever hybrid = retriever(3, 0.55, 0.6);

        assertTrue(hybrid.retrieve(new Query("How do I reset my password for the checkout engine?")).isEmpty());
        // Ranking skips the gates. The weak match is first.
        assertEquals("checkout", hybrid.rank("How do I reset my password for the checkout engine?", 3).get(0).getId());
    }

    @Test
    void aLooserGateLetsThatWeakMatchThrough() {
        vectorReturns();
        assertEquals(List.of("checkout"),
                ids(retriever(3, 0.55, 0.5).retrieve(new Query("How do I reset my password for the checkout engine?"))));
    }

    @Test
    void theResultIsCutToTopK() {
        vectorReturns(denseHit("a", "alpha", 0.9), denseHit("b", "beta", 0.8), denseHit("c", "gamma", 0.7));
        assertEquals(2, retriever(2, 0.55, 0.6).retrieve(new Query("zzz qqq")).size());
    }

    @Test
    void withAnEmptyKeywordIndexItBehavesLikeVectorSearch() {
        keywordIndex.replaceAll(List.of());
        vectorReturns(denseHit("a", "alpha", 0.9), denseHit("b", "beta", 0.8));

        List<Document> result = retriever(3, 0.55, 0.6).retrieve(new Query("anything at all"));

        assertEquals(List.of("a", "b"), ids(result));
        assertEquals("vector", result.get(0).getMetadata().get("retrievedBy"));
    }

    @Test
    void aBlankQuestionReturnsNothingAndDoesNotCallTheVectorStore() {
        // Spring AI's Query refuses blank text, so only rank() can be handed one.
        HybridDocumentRetriever hybrid = retriever(3, 0.55, 0.6);
        assertTrue(hybrid.rank("   ", 3).isEmpty());
        assertTrue(hybrid.rank(null, 3).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(vectorStore);
    }

    @Test
    void theGateNeedsTwoTermsUnlessTheQuestionHasOnlyOne() {
        Bm25Index.KeywordHit twoOfThree = new Bm25Index.KeywordHit(chunk("x", "x"), 1.0, 2, 3);
        Bm25Index.KeywordHit oneOfOne = new Bm25Index.KeywordHit(chunk("x", "x"), 1.0, 1, 1);
        Bm25Index.KeywordHit oneOfThree = new Bm25Index.KeywordHit(chunk("x", "x"), 1.0, 1, 3);
        assertTrue(HybridDocumentRetriever.passesKeywordGate(twoOfThree, 0.6));
        assertTrue(HybridDocumentRetriever.passesKeywordGate(oneOfOne, 0.6), "a one-word question may match on one word");
        assertFalse(HybridDocumentRetriever.passesKeywordGate(oneOfThree, 0.1), "one word of several is never enough");
    }
}
