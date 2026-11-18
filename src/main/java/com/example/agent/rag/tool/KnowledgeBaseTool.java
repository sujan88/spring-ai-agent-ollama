package com.example.agent.rag.tool;

import com.example.agent.rag.RagSourceCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * RAG as a tool. The model calls it only for policy and "how does it work" questions, so live
 * requests such as "where is my order" never trigger an embedding call or a vector search.
 */
public class KnowledgeBaseTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);

    public static final String NOT_FOUND = "NO_MATCH: the knowledge base has nothing about this. Tell the user you "
            + "cannot find it in the program documents. Do not guess or invent rules.";

    /** One retrieved chunk as the model sees it. */
    public record Chunk(String source, String text) {}

    public record KnowledgeBaseResult(boolean found, String message, List<Chunk> chunks) {}

    private final DocumentRetriever retriever;

    public KnowledgeBaseTool(DocumentRetriever retriever) {
        this.retriever = retriever;
    }

    @Tool(name = "searchKnowledgeBase",
            description = "Search the company documents for POLICY and HOW-IT-WORKS answers: return and "
                    + "refund rules, shipping times, cancellation rules, error meanings, process steps. "
                    + "Call it for any 'how does X work' or 'what are the rules' question. "
                    + "Do NOT call it for live data such as a specific order, its status or its items. "
                    + "Answer only from the returned chunks.")
    public KnowledgeBaseResult searchKnowledgeBase(
            @ToolParam(description = "The user's policy question, in their own words") String question,
            ToolContext toolContext) {
        return search(question, toolContext, NOT_FOUND);
    }

    /** The search both knowledge-base tools share. {@code notFound} is what the model is told when nothing matches. */
    public KnowledgeBaseResult search(String question, ToolContext toolContext, String notFound) {
        if (question == null || question.isBlank()) {
            return new KnowledgeBaseResult(false, "INPUT_REQUIRED: pass the user's question.", List.of());
        }
        List<Document> documents = retriever.retrieve(new Query(question)); // RAG call

        log.info("knowledge-base search: {} chunk(s) for \"{}\"", documents.size(), question);
        if (documents.isEmpty()) {
            return new KnowledgeBaseResult(false, notFound, List.of());
        }
        if (toolContext != null && toolContext.getContext().get(RagSourceCollector.CONTEXT_KEY)
                instanceof RagSourceCollector collector) {
            collector.addAll(documents);
        }
        List<Chunk> chunks = documents.stream()
                .map(d -> new Chunk(String.valueOf(d.getMetadata().getOrDefault("source", "unknown")), d.getText()))
                .toList();
        return new KnowledgeBaseResult(true, "OK", chunks);
    }
}
