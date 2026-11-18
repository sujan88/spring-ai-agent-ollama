package com.example.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * The trailing "sources" Server-Sent Event the streaming chat sends after the last token:
 * {"sources":[{"source":..., "score":..., "snippet":..., "retrievedBy":...}]}. It is sent only when a
 * knowledge-base tool returned chunks during the request.
 */
public final class RagSourcesEvent {

    private static final Logger log = LoggerFactory.getLogger(RagSourcesEvent.class);
    private static final int SNIPPET_CHARS = 220;

    /** One retrieved chunk: originating file, score, and a short preview. */
    public record Source(String source, Double score, String snippet, String retrievedBy) {}

    /** The event body. Clients that only read "t" ignore it. */
    public record SourcesEvent(List<Source> sources) {}

    private RagSourcesEvent() {
    }

    /** The one trailing event, or nothing when no knowledge base was searched. */
    public static Flux<Object> after(RagSourceCollector collector) {
        List<Source> sources = sources(collector);
        return sources.isEmpty() ? Flux.empty() : Flux.just(new SourcesEvent(sources));
    }

    /** The chunks the tool returned during this request, as citations. Empty when it was never called. */
    public static List<Source> sources(RagSourceCollector collector) {
        List<Source> sources = collector.documents().stream()
                .map(d -> new Source(
                        String.valueOf(d.getMetadata().getOrDefault("source", "unknown")),
                        shownScore(d),
                        snippet(d.getText()),
                        d.getMetadata().get("retrievedBy") instanceof String how ? how : null))
                .toList();
        sources.forEach(s -> log.info("RAG source: {} (score={}, via {})", s.source(), s.score(),
                s.retrievedBy() == null ? "vector" : s.retrievedBy()));
        return sources;
    }

    /** The reranker's score when there is one. Otherwise the vector similarity, because the hybrid main score is a fused rank score. */
    static Double shownScore(Document d) {
        if (d.getMetadata().get("rerankScore") instanceof Double r) return Math.round(r * 100) / 100.0;
        Object cosine = d.getMetadata().get("denseScore");
        Double score = cosine instanceof Double c ? c : d.getScore();
        return score == null ? null : Math.round(score * 100) / 100.0;
    }

    static String snippet(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }
}
