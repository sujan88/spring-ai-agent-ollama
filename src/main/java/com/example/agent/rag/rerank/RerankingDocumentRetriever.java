package com.example.agent.rag.rerank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Wraps a retriever with a second stage. The first stage returns a wider list of candidates (the
 * RRF result). The reranker reads the question and each chunk together and picks the best top-k.
 *
 * If the reranker fails (timeout, quota, network), the first-stage order is used, so a reranker
 * outage lowers quality a little but never breaks the chat.
 */
public class RerankingDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(RerankingDocumentRetriever.class);

    private final BiFunction<String, Integer, List<Document>> firstStage;
    private final Reranker reranker;
    private final int topK;
    private final int candidates;
    private final double minScore;

    public RerankingDocumentRetriever(BiFunction<String, Integer, List<Document>> firstStage, Reranker reranker,
                                      int topK, int candidates, double minScore) {
        this.firstStage = firstStage;
        this.reranker = reranker;
        this.topK = topK;
        this.candidates = Math.max(candidates, topK);
        this.minScore = minScore;
    }

    /**
     * Logs, per search:
     *   INFO  "Rerank start"   question and how many candidates the first stage gave
     *   INFO  "Rerank done"    time taken, kept / dropped counts, and each kept chunk with its rerank
     *                          score and its position before reranking ("was #4")
     *   DEBUG "Rerank scores"  every candidate's score, for tuning app.rag.rerank.min-score
     *   WARN  "Rerank failed"  the error and time taken; the fused order is used instead
     */
    @Override
    public List<Document> retrieve(Query query) {
        List<Document> pool = firstStage.apply(query.text(), candidates);
        if (pool.isEmpty()) {
            log.info("Rerank skipped: no candidates for \"{}\"", shorten(query.text()));
            return pool;
        }
        log.info("Rerank start: \"{}\", {} candidate(s) from the first stage, keeping up to {}",
                shorten(query.text()), pool.size(), topK);
        long started = System.nanoTime();
        try {
            // Ask for every candidate's score (not just top-k) so the summary can show what was dropped.
            List<Reranker.Scored> ranked = reranker.rerank(query.text(),
                    pool.stream().map(Document::getText).toList(), pool.size());
            long ms = (System.nanoTime() - started) / 1_000_000;
            List<Reranker.Scored> kept = ranked.stream()
                    .filter(s -> s.score() >= minScore)
                    .limit(topK)
                    .toList();
            long belowMinScore = ranked.stream().filter(s -> s.score() < minScore).count();
            log.info("Rerank done in {} ms: kept {} of {} (below min-score {}: {}) -> {}",
                    ms, kept.size(), pool.size(), minScore, belowMinScore, describe(kept, pool));
            if (log.isDebugEnabled()) {
                log.debug("Rerank scores: {}", describe(ranked, pool));
            }
            return kept.stream()
                    .map(s -> pool.get(s.index()).mutate()
                            .metadata("rerankScore", s.score())
                            .build())
                    .toList();
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            log.warn("Rerank failed after {} ms ({}); using the fused order instead -> {}",
                    ms, e.toString(), pool.stream().limit(topK).map(RerankingDocumentRetriever::label).toList());
            return pool.stream().limit(topK).toList();
        }
    }

    /** "[1] comp-restrictions.md#2 0.91 (was #4), [2] ..." in the reranker's order. */
    static String describe(List<Reranker.Scored> scored, List<Document> pool) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < scored.size(); i++) {
            Reranker.Scored s = scored.get(i);
            if (i > 0) out.append(", ");
            out.append('[').append(i + 1).append("] ")
                    .append(label(pool.get(s.index())))
                    .append(' ').append(String.format(java.util.Locale.ROOT, "%.2f", s.score()))
                    .append(" (was #").append(s.index() + 1).append(')');
        }
        return out.length() == 0 ? "nothing" : out.toString();
    }

    /** source#chunkIndex, or the id when the chunk has no source metadata. */
    static String label(Document d) {
        Object source = d.getMetadata().get("source");
        Object index = d.getMetadata().get("chunkIndex");
        if (source == null) return d.getId();
        return index == null ? source.toString() : source + "#" + index;
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
