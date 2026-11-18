package com.example.agent.rag.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cohere Rerank v2: POST /v2/rerank with the question and the candidate texts.
 *
 * The response is bound to a small record instead of a JSON tree, so the code does not depend on
 * which Jackson major version is on the classpath (Spring Boot 4 uses Jackson 3; the annotations
 * package is the same in both).
 */
public class CohereReranker implements Reranker {

    record RerankResponse(List<Result> results) {
        record Result(int index, @JsonProperty("relevance_score") double relevanceScore) {}
    }

    private final RestClient http;
    private final String model;

    public CohereReranker(RagRerankSettings settings, String apiKey) {
        this(settings, apiKey, RestClient.builder().requestFactory(timeouts(settings.timeoutMs())));
    }

    /** For tests: a builder bound to a mock server. */
    public CohereReranker(RagRerankSettings settings, String apiKey, RestClient.Builder builder) {
        this.model = settings.model();
        this.http = builder
                .baseUrl(settings.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader("X-Client-Name", "spring-ai-agent-ollama")
                .build();
    }

    private static SimpleClientHttpRequestFactory timeouts(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    @Override
    public List<Scored> rerank(String query, List<String> texts, int topN) {
        RerankResponse response = http.post()
                .uri("/v2/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "query", query, "documents", texts, "top_n", Math.min(topN, texts.size())))
                .retrieve()
                .body(RerankResponse.class);
        if (response == null || response.results() == null) {
            throw new IllegalStateException("Cohere rerank returned no results array");
        }
        List<Scored> scored = new ArrayList<>();
        for (RerankResponse.Result r : response.results()) {
            if (r.index() < 0 || r.index() >= texts.size()) {
                throw new IllegalStateException("Cohere rerank returned an index outside the request: " + r.index());
            }
            scored.add(new Scored(r.index(), r.relevanceScore()));
        }
        return scored;
    }
}
