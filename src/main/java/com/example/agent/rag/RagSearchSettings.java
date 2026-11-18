package com.example.agent.rag;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for how the chat retrieves knowledge-base chunks.
 *
 * The chat client and the RAG evaluation (RagIT, EvalIT) both read these values, so the
 * numbers the evaluation reports are the numbers users actually get. Change them in
 * application.properties (app.rag.top-k, app.rag.similarity-threshold), then re-run the
 * evaluation to see the effect.
 */
@Component
public class RagSearchSettings {

    public static final int DEFAULT_TOP_K = 3;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.55;

    private final int topK;
    private final double similarityThreshold;

    public RagSearchSettings(@Value("${app.rag.top-k:" + DEFAULT_TOP_K + "}") int topK,
                             @Value("${app.rag.similarity-threshold:" + DEFAULT_SIMILARITY_THRESHOLD + "}") double similarityThreshold) {
        if (topK < 1) throw new IllegalArgumentException("app.rag.top-k must be at least 1, was " + topK);
        if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("app.rag.similarity-threshold must be between 0 and 1, was " + similarityThreshold);
        }
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    public static RagSearchSettings defaults() {
        return new RagSearchSettings(DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public int topK() {
        return topK;
    }

    public double similarityThreshold() {
        return similarityThreshold;
    }

    /** Without a query: for callers that add the query themselves (for example a RAG advisor). */
    public SearchRequest searchRequest() {
        return SearchRequest.builder().topK(topK).similarityThreshold(similarityThreshold).build();
    }

    /** The same settings with a query attached, for direct vector store searches. */
    public SearchRequest searchRequest(String query) {
        return SearchRequest.builder().query(query).topK(topK).similarityThreshold(similarityThreshold).build();
    }
}
