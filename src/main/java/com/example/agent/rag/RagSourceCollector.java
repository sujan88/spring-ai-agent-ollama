package com.example.agent.rag;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the chunks the knowledge-base tool returned during one chat request. The controller puts
 * one in the tool context, the tool fills it, and the controller sends the contents as the
 * "sources" event when the answer ends. A request that never calls the tool ends with no sources.
 */
public final class RagSourceCollector {

    /** Key of the collector in the tool context map. */
    public static final String CONTEXT_KEY = "ragSources";

    private final List<Document> documents = new CopyOnWriteArrayList<>();

    public void addAll(List<Document> retrieved) {
        for (Document d : retrieved) {
            boolean seen = documents.stream().anyMatch(existing -> existing.getId().equals(d.getId()));
            if (!seen) documents.add(d);
        }
    }

    public List<Document> documents() {
        return List.copyOf(documents);
    }
}
