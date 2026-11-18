package com.example.agent.rag;

import com.example.agent.rag.Bm25Index.KeywordHit;
import com.example.agent.rag.rrf.ReciprocalRankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval: vector search and BM25 keyword search run on the same question, and
 * Reciprocal Rank Fusion merges the two ranked lists.
 *
 * Relevance gates. Fusion scores say nothing about whether a chunk is relevant at all, only how
 * it ranks, so a question about the weather would still get "best" chunks. Each retriever is
 * therefore gated before fusion:
 *   - vector hits must reach the same similarity threshold as the vector-only chat (app.rag.similarity-threshold);
 *   - keyword hits must contain at least two of the question's distinct terms (one if it has only
 *     one) and at least app.rag.hybrid.min-term-match of them.
 * If both lists are empty after gating, nothing is returned and the model is told nothing was found.
 *
 * Each returned chunk keeps its source metadata and gains denseScore, keywordScore and retrievedBy
 * (vector, bm25 or vector+bm25) so a reader can see why it was chosen. Its score is the fused score.
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final Bm25Index keywordIndex;
    private final RagSearchSettings search;
    private final RagHybridSettings hybrid;
    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    public HybridDocumentRetriever(VectorStore vectorStore, Bm25Index keywordIndex,
                                   RagSearchSettings search, RagHybridSettings hybrid) {
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.search = search;
        this.hybrid = hybrid;
    }

    /** What the chat uses: gated, fused, cut to top-k. */
    @Override
    public List<Document> retrieve(Query query) {
        return retrieve(query.text(), search.topK());
    }

    /** Gated and fused, cut to {@code limit}. The reranker asks for more than top-k here. */
    public List<Document> retrieve(String text, int limit) {
        return fuse(text, true).stream().limit(limit).toList();
    }

    /** The fused ranking with no relevance gates, best first. For evaluation and threshold calibration. */
    public List<Document> rank(String text, int n) {
        return fuse(text, false).stream().limit(n).toList();
    }

    private List<Document> fuse(String text, boolean gated) {
        if (text == null || text.isBlank()) return List.of();

        List<Document> semanticResults = vectorStore.similaritySearch(SearchRequest.builder()
                .query(text)
                .topK(hybrid.candidates())
                .similarityThreshold(gated ? search.similarityThreshold() : 0.0)
                .build());

        Map<String, Double> semanticResultsScores = semanticResults
                .stream().collect(Collectors.toMap(Document::getId, Document::getScore,(previous, current) -> current,
                        HashMap::new));
        log.info("Semantic results : {}}", semanticResults.size());

        List<KeywordHit> keyword = keywordIndex.search(text, hybrid.candidates());
        if (gated) keyword = keyword.stream().filter(hit -> passesKeywordGate(hit, hybrid.minTermMatch())).toList();


        Map<String, Double> keywordScores = new HashMap<>();
        for (KeywordHit hit : keyword) keywordScores.put(hit.document().getId(), hit.score());

        log.info("keyword results : {}", keyword.size());

        List<Document> keywordRanking = keyword.stream().map(KeywordHit::document).toList();

        return ReciprocalRankFusion.fuse(List.of(semanticResults, keywordRanking), hybrid.rrfK()).stream()
                .map(d -> annotate(d, semanticResultsScores, keywordScores))
                .toList();
    }

    /**
     * The keyword relevance gate: the chunk must contain at least two of the question's distinct
     * terms (one, if the question has only one) and at least minTermMatch of them.
     */
    public static boolean passesKeywordGate(KeywordHit hit, double minTermMatch) {
        return hit.matchedTerms() >= Math.min(2, hit.queryTerms())
                && hit.termCoverage() >= minTermMatch;
    }

    private static Document annotate(Document fused, Map<String, Double> denseScores, Map<String, Double> keywordScores) {
        Double dense = denseScores.get(fused.getId());
        Double keyword = keywordScores.get(fused.getId());
        Document.Builder builder = fused.mutate();
        if (dense != null) builder.metadata("denseScore", dense);
        if (keyword != null) builder.metadata("keywordScore", keyword);
        builder.metadata("retrievedBy", dense != null && keyword != null ? "vector+bm25" : dense != null ? "vector" : "bm25");
        return builder.build();
    }
}
