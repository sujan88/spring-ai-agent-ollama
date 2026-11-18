package com.example.agent.rag.rerank;

import java.util.List;

/** Re-scores candidate texts against the question with a model that reads both together. */
public interface Reranker {

    /** One candidate's position in the input list and its relevance, 0 to 1. */
    record Scored(int index, double score) {}

    /**
     * @return at most {@code topN} entries, best first. Indexes point into {@code texts}.
     * @throws RuntimeException when the service is unreachable or answers with an error
     */
    List<Scored> rerank(String query, List<String> texts, int topN);
}
