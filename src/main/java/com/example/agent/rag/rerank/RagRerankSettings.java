package com.example.agent.rag.rerank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings for the reranking step, read from app.rag.rerank.*. Off by default. The API key is read
 * from the COHERE_API_KEY environment variable and never from a properties file.
 */
@Component
public class RagRerankSettings {

    public static final boolean DEFAULT_ENABLED = false;
    public static final String DEFAULT_MODEL = "rerank-v3.5";
    public static final String DEFAULT_BASE_URL = "https://api.cohere.com";
    public static final int DEFAULT_CANDIDATES = 10;
    public static final double DEFAULT_MIN_SCORE = 0.0;
    public static final int DEFAULT_TIMEOUT_MS = 3000;

    private final boolean enabled;
    private final String model;
    private final String baseUrl;
    private final int candidates;
    private final double minScore;
    private final int timeoutMs;

    public RagRerankSettings(@Value("${app.rag.rerank.enabled:" + DEFAULT_ENABLED + "}") boolean enabled,
                             @Value("${app.rag.rerank.model:" + DEFAULT_MODEL + "}") String model,
                             @Value("${app.rag.rerank.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                             @Value("${app.rag.rerank.candidates:" + DEFAULT_CANDIDATES + "}") int candidates,
                             @Value("${app.rag.rerank.min-score:" + DEFAULT_MIN_SCORE + "}") double minScore,
                             @Value("${app.rag.rerank.timeout-ms:" + DEFAULT_TIMEOUT_MS + "}") int timeoutMs) {
        if (candidates < 1) throw new IllegalArgumentException("app.rag.rerank.candidates must be at least 1, was " + candidates);
        if (minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("app.rag.rerank.min-score must be between 0 and 1, was " + minScore);
        }
        if (timeoutMs < 100) throw new IllegalArgumentException("app.rag.rerank.timeout-ms must be at least 100, was " + timeoutMs);
        this.enabled = enabled;
        this.model = model;
        this.baseUrl = baseUrl;
        this.candidates = candidates;
        this.minScore = minScore;
        this.timeoutMs = timeoutMs;
    }

    public static RagRerankSettings defaults() {
        return new RagRerankSettings(DEFAULT_ENABLED, DEFAULT_MODEL, DEFAULT_BASE_URL,
                DEFAULT_CANDIDATES, DEFAULT_MIN_SCORE, DEFAULT_TIMEOUT_MS);
    }

    public boolean enabled() { return enabled; }
    public String model() { return model; }
    public String baseUrl() { return baseUrl; }
    /** How many fused chunks go to the reranker. Only the best top-k of them reach the LLM. */
    public int candidates() { return candidates; }
    /** Chunks the reranker scores below this are dropped. 0 keeps everything. */
    public double minScore() { return minScore; }
    public int timeoutMs() { return timeoutMs; }
}
