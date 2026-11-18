package com.example.agent.rag;

import com.example.agent.rag.rerank.RagRerankSettings;
import com.example.agent.rag.rerank.Reranker;
import com.example.agent.rag.rerank.RerankingDocumentRetriever;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Builds the retriever behind the knowledge-base tool. The chat and the RAG evaluation both call this,
 * so what the evaluation measures is what users get.
 *
 *   vector only            app.rag.hybrid.enabled=false, app.rag.rerank.enabled=false
 *   hybrid (BM25 + vector) app.rag.hybrid.enabled=true: both lists gated, then fused with RRF
 *   + rerank               app.rag.rerank.enabled=true and COHERE_API_KEY set: a wider candidate list
 *                          is re-ordered by a cross-encoder and cut to top-k
 */
public final class RagRetrieverFactory {

    private RagRetrieverFactory() {
    }

    public static DocumentRetriever retriever(VectorStore vectorStore, RagSearchSettings search,
                                              RagHybridSettings hybrid, Bm25Index keywordIndex) {
        return retriever(vectorStore, search, hybrid, keywordIndex, null, RagRerankSettings.defaults());
    }

    /** Same, with an optional second stage: the reranker re-orders a wider candidate list. */
    public static DocumentRetriever retriever(VectorStore vectorStore, RagSearchSettings search,
                                              RagHybridSettings hybrid, Bm25Index keywordIndex,
                                              Reranker reranker, RagRerankSettings rerank) {
        boolean rerankOn = reranker != null && rerank.enabled();
        if (hybrid.enabled()) {
            HybridDocumentRetriever hybridRetriever = new HybridDocumentRetriever(vectorStore, keywordIndex, search, hybrid);
            return rerankOn
                    ? new RerankingDocumentRetriever(hybridRetriever::retrieve, reranker,
                            search.topK(), rerank.candidates(), rerank.minScore())
                    : hybridRetriever;
        }
        if (rerankOn) {
            return new RerankingDocumentRetriever(
                    (text, n) -> vectorStore.similaritySearch(SearchRequest.builder()
                            .query(text).topK(n).similarityThreshold(search.similarityThreshold()).build()),
                    reranker, search.topK(), rerank.candidates(), rerank.minScore());
        }
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(search.topK())
                .similarityThreshold(search.similarityThreshold())
                .build();
    }

    public static String describe(RagHybridSettings hybrid, RagRerankSettings rerank, boolean rerankerPresent) {
        String first = hybrid.enabled() ? "hybrid: BM25 + vector, fused with RRF (k=" + hybrid.rrfK() + ")" : "vector only";
        return first + (rerank.enabled() && rerankerPresent
                ? ", then rerank " + rerank.candidates() + " candidates with " + rerank.model()
                : "");
    }
}
