package com.example.agent.rag;

import com.example.agent.rag.rrf.ReciprocalRankFusion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings for hybrid retrieval (BM25 keyword search plus vector search, merged with
 * Reciprocal Rank Fusion). Read from application.properties under app.rag.hybrid.*.
 *
 * Hybrid is off by default. Turn it on once EvalIT shows it helps on your questions.
 */
@Component
public class RagHybridSettings {

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_CANDIDATES = 20;
    public static final int DEFAULT_RRF_K = ReciprocalRankFusion.DEFAULT_K;
    // 0.6 means more than half of the question's distinct terms. On the golden set, 0.5 let "How do I reset my
    // password for the booking engine?" through on the words "booking" and "engine"; 0.6 blocks it and costs one
    // question its keyword evidence. Re-check with Bm25GoldenSetTest#printGateSweep as the golden set grows.
    public static final double DEFAULT_MIN_TERM_MATCH = 0.6;

    private final boolean enabled;
    private final int candidates;
    private final int rrfK;
    private final double minTermMatch;

    public RagHybridSettings(@Value("${app.rag.hybrid.enabled:" + DEFAULT_ENABLED + "}") boolean enabled,
                             @Value("${app.rag.hybrid.candidates:" + DEFAULT_CANDIDATES + "}") int candidates,
                             @Value("${app.rag.hybrid.rrf-k:" + DEFAULT_RRF_K + "}") int rrfK,
                             @Value("${app.rag.hybrid.min-term-match:" + DEFAULT_MIN_TERM_MATCH + "}") double minTermMatch) {
        if (candidates < 1) throw new IllegalArgumentException("app.rag.hybrid.candidates must be at least 1, was " + candidates);
        if (rrfK < 1) throw new IllegalArgumentException("app.rag.hybrid.rrf-k must be at least 1, was " + rrfK);
        if (minTermMatch < 0.0 || minTermMatch > 1.0) {
            throw new IllegalArgumentException("app.rag.hybrid.min-term-match must be between 0 and 1, was " + minTermMatch);
        }
        this.enabled = enabled;
        this.candidates = candidates;
        this.rrfK = rrfK;
        this.minTermMatch = minTermMatch;
    }

    public static RagHybridSettings defaults() {
        return new RagHybridSettings(DEFAULT_ENABLED, DEFAULT_CANDIDATES, DEFAULT_RRF_K, DEFAULT_MIN_TERM_MATCH);
    }

    /** Same settings with hybrid switched on, used when comparing hybrid against vector-only. */
    public RagHybridSettings enabledCopy() {
        return new RagHybridSettings(true, candidates, rrfK, minTermMatch);
    }

    public boolean enabled() {
        return enabled;
    }

    /** How many results each retriever contributes before fusion. */
    public int candidates() {
        return candidates;
    }

    public int rrfK() {
        return rrfK;
    }

    /** A keyword hit counts only if it contains at least this share of the question's distinct terms. */
    public double minTermMatch() {
        return minTermMatch;
    }
}
