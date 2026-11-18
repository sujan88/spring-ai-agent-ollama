package com.example.agent.rag;

import com.example.agent.rag.load.KnowledgeBaseLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * POST /api/rag/reload re-runs ingestion without a restart (unchanged files are skipped by hash).
 * Admin-only: enabled with app.rag.admin-endpoint=true (local profile). Protect it before deploying.
 */
@RestController
@ConditionalOnProperty(name = "app.rag.admin-endpoint", havingValue = "true")
public class IngestionController {

    private final ObjectProvider<KnowledgeBaseLoader> loader;
    private final Bm25Index keywordIndex;

    public IngestionController(ObjectProvider<KnowledgeBaseLoader> loader, Bm25Index keywordIndex) {
        this.loader = loader;
        this.keywordIndex = keywordIndex;
    }

    @PostMapping("/api/rag/reload")
    public Map<String, Object> reload() {
        KnowledgeBaseLoader kb = loader.getIfAvailable();
        if (kb == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG is disabled (app.rag.enabled=false)");
        kb.load();
        return Map.of("status", "reloaded", "chunksInKeywordIndex", keywordIndex.size());
    }
}
