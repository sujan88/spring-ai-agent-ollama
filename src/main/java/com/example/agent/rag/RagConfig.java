package com.example.agent.rag;

import com.example.agent.rag.load.KnowledgeBaseLoader;
import com.example.agent.rag.query.QueryRewriter;
import com.example.agent.rag.rerank.CohereReranker;
import com.example.agent.rag.rerank.LlmReranker;
import com.example.agent.rag.rerank.RagRerankSettings;
import com.example.agent.rag.rerank.Reranker;
import com.example.agent.rag.tool.KnowledgeBaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG wiring. Switch it all off with app.rag.enabled=false; the agent then simply has no
 * searchKnowledgeBase tool and everything else keeps working.
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public KnowledgeBaseLoader knowledgeBaseLoader(VectorStore vectorStore, Bm25Index keywordIndex,
                                                   @Value("${app.rag.docs-pattern:" + KnowledgeBaseLoader.DEFAULT_DOCS_PATTERN + "}") String docsPattern,
                                                   @Value("${app.rag.force-reload:false}") boolean forceReload) {
        return new KnowledgeBaseLoader(vectorStore, keywordIndex, docsPattern, forceReload, null, "KnowledgeBase");
    }

    /** Ingests after startup, so a slow embedding model never blocks the app from starting. */
    @Bean
    public ApplicationRunner knowledgeBaseIngest(KnowledgeBaseLoader loader) {
        return args -> loader.load();
    }

    @Bean
    public KnowledgeBaseTool knowledgeBaseTool(VectorStore vectorStore, Bm25Index keywordIndex,
                                               RagSearchSettings search, RagHybridSettings hybrid, RagRerankSettings rerank,
                                               @Qualifier("utilityChatClient") ChatClient utility, ChatMemory chatMemory,
                                               @Value("${app.rag.query-rewrite.enabled:true}") boolean rewriteEnabled,
                                               @Value("${app.rag.query-rewrite.history-messages:6}") int historyMessages,
                                               @Value("${COHERE_API_KEY:}") String cohereKey) {
        Reranker reranker = reranker(rerank, utility, cohereKey);
        QueryRewriter rewriter = rewriteEnabled ? new QueryRewriter(utility, chatMemory, historyMessages) : null;
        log.info("Knowledge-base tool ON: {}{}", RagRetrieverFactory.describe(hybrid, rerank, reranker != null),
                rewriter != null ? ", follow-up questions rewritten with the last " + historyMessages + " messages" : "");
        return new KnowledgeBaseTool(
                RagRetrieverFactory.retriever(vectorStore, search, hybrid, keywordIndex, reranker, rerank), rewriter);
    }

    private static Reranker reranker(RagRerankSettings rerank, ChatClient utility, String cohereKey) {
        if (!rerank.enabled()) return null;
        if ("llm".equals(rerank.provider())) return new LlmReranker(utility, rerank.timeoutMs());
        if (!cohereKey.isBlank()) return new CohereReranker(rerank, cohereKey);
        log.warn("app.rag.rerank.provider=cohere but COHERE_API_KEY is not set; reranking is OFF.");
        return null;
    }
}
