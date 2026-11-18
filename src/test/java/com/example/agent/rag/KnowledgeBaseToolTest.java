package com.example.agent.rag;

import com.example.agent.rag.tool.KnowledgeBaseTool;
import com.example.agent.rag.tool.KnowledgeBaseTool.KnowledgeBaseResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The knowledge-base tool: what the model receives and what the controller later sends as sources. */
class KnowledgeBaseToolTest {

    private static Document chunk(String id, String source, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of("source", source)).build();
    }

    @Test
    void returnsChunksAndRecordsThemAsSources() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        when(retriever.retrieve(any())).thenReturn(List.of(chunk("1", "returns-policy.md", "Returns are accepted within 30 days.")));
        RagSourceCollector sources = new RagSourceCollector();
        ToolContext ctx = new ToolContext(Map.of(RagSourceCollector.CONTEXT_KEY, sources));

        KnowledgeBaseResult result = new KnowledgeBaseTool(retriever).searchKnowledgeBase("how do returns work", ctx);

        assertTrue(result.found());
        assertEquals("returns-policy.md", result.chunks().get(0).source());
        assertEquals("Returns are accepted within 30 days.", result.chunks().get(0).text());
        assertEquals(1, sources.documents().size());
    }

    @Test
    void nothingRetrievedTellsTheModelNotToGuessAndRecordsNoSources() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        when(retriever.retrieve(any())).thenReturn(List.of());
        RagSourceCollector sources = new RagSourceCollector();
        ToolContext ctx = new ToolContext(Map.of(RagSourceCollector.CONTEXT_KEY, sources));

        KnowledgeBaseResult result = new KnowledgeBaseTool(retriever).searchKnowledgeBase("weather in Paris", ctx);

        assertFalse(result.found());
        assertTrue(result.message().startsWith("NO_MATCH"));
        assertTrue(result.chunks().isEmpty());
        assertTrue(sources.documents().isEmpty());
    }

    @Test
    void blankQuestionDoesNotSearch() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);

        KnowledgeBaseResult result = new KnowledgeBaseTool(retriever).searchKnowledgeBase("  ", new ToolContext(Map.of()));

        assertFalse(result.found());
        verify(retriever, never()).retrieve(any());
    }

    @Test
    void worksWithoutACollectorInTheContext() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        when(retriever.retrieve(any())).thenReturn(List.of(chunk("1", "a.md", "text")));

        assertTrue(new KnowledgeBaseTool(retriever).searchKnowledgeBase("q", new ToolContext(Map.of())).found());
    }

    @Test
    void collectorKeepsEachChunkOnceWhenTheToolRunsTwice() {
        RagSourceCollector sources = new RagSourceCollector();
        sources.addAll(List.of(chunk("1", "a.md", "x"), chunk("2", "b.md", "y")));
        sources.addAll(List.of(chunk("2", "b.md", "y"), chunk("3", "c.md", "z")));

        assertEquals(3, sources.documents().size());
    }

    @Test
    void retrieverFactoryPicksHybridOrVectorOnly() {
        VectorStore store = mock(VectorStore.class);
        RagSearchSettings search = new RagSearchSettings(3, 0.55);
        Bm25Index index = new Bm25Index();

        assertInstanceOf(HybridDocumentRetriever.class,
                RagRetrieverFactory.retriever(store, search, hybridSettings(true), index));
        assertFalse(RagRetrieverFactory.retriever(store, search, hybridSettings(false), index)
                instanceof HybridDocumentRetriever);
    }

    private static RagHybridSettings hybridSettings(boolean enabled) {
        return new RagHybridSettings(enabled, 20, 60, 0.6);
    }
}
