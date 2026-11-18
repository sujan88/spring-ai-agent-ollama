package com.example.agent.rag.rerank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings for the reranking step, read from app.rag.rerank.*. Off by default.
 *
 * provider: "llm" reranks with the chat model (Ollama/OpenAI, no extra key, slower);
 *           "cohere" uses Cohere Rerank, a dedicated cross-encoder (needs COHERE_API_KEY, read from
 *           the environment and never from a properties file).
 */
@Component
public class RagRerankSettings {

    public static final boolean DEFAULT_ENABLED = false;
    public static final String DEFAULT_PROVIDER = "llm";
    public static final String DEFAULT_MODEL = "rerank-v3.5";
    public static final String DEFAULT_BASE_URL = "https://api.cohere.com";
    public static final int DEFAULT_CANDIDATES = 10;
    public static final double DEFAULT_MIN_SCORE = 0.0;
    public static final int DEFAULT_TIMEOUT_MS = 3000;

    private final boolean enabled;
    private final String provider;
    private final String model;
    private final String baseUrl;
    private final int candidates;
    private final double minScore;
    private final int timeoutMs;

    /** Same settings with the default provider (llm). Kept for tests and callers that predate the provider switch. */
    public RagRerankSettings(boolean enabled, String model, String baseUrl, int candidates, double minScore, int timeoutMs) {
        this(enabled, DEFAULT_PROVIDER, model, baseUrl, candidates, minScore, timeoutMs);
    }

    @Autowired
    public RagRerankSettings(@Value("${app.rag.rerank.enabled:" + DEFAULT_ENABLED + "}") boolean enabled,
                             @Value("${app.rag.rerank.provider:" + DEFAULT_PROVIDER + "}") String provider,
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
        if (!"llm".equals(provider) && !"cohere".equals(provider)) {
            throw new IllegalArgumentException("app.rag.rerank.provider must be llm or cohere, was " + provider);
        }
        this.enabled = enabled;
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.candidates = candidates;
        this.minScore = minScore;
        this.timeoutMs = timeoutMs;
    }

    public static RagRerankSettings defaults() {
        return new RagRerankSettings(DEFAULT_ENABLED, DEFAULT_PROVIDER, DEFAULT_MODEL, DEFAULT_BASE_URL,
                DEFAULT_CANDIDATES, DEFAULT_MIN_SCORE, DEFAULT_TIMEOUT_MS);
    }

    public boolean enabled() { return enabled; }
    /** llm or cohere. */
    public String provider() { return provider; }
    public String model() { return model; }
    public String baseUrl() { return baseUrl; }
    /** How many fused chunks go to the reranker. Only the best top-k of them reach the LLM. */
    public int candidates() { return candidates; }
    /** Chunks the reranker scores below this are dropped. 0 keeps everything. */
    public double minScore() { return minScore; }
    public int timeoutMs() { return timeoutMs; }
}
