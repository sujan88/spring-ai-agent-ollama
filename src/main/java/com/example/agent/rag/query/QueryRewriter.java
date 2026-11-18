package com.example.agent.rag.query;

import com.example.agent.core.tool.ToolContextKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns a follow-up question into a standalone search query, using the recent conversation.
 *
 * Why: retrieval sees only the query text. After "How much is express shipping?", the follow-up
 * "and to Canada?" retrieves nothing useful on its own. Rewritten with the history it becomes
 * "express shipping to Canada", which finds the right chunk. This is the same idea as Spring AI's
 * CompressionQueryTransformer, done here inside the RAG tool because tools do not receive the
 * chat history; it is read from ChatMemory with the conversation scope in the ToolContext.
 *
 * Costs one small model call, and only when there is history. Any failure falls back to the
 * original question, so rewriting can never break a search.
 */
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final int MAX_CHARS_PER_MESSAGE = 500;

    static final String INSTRUCTIONS = """
            You rewrite search queries for a document search engine.
            Given a conversation and the latest search question, write ONE standalone search query that
            contains every detail the question depends on (topics, products, places, numbers) taken from
            the conversation. Keep it short. Do not answer the question. Output only the query.""";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final int historyMessages;

    public QueryRewriter(ChatClient chatClient, ChatMemory chatMemory, int historyMessages) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.historyMessages = Math.max(1, historyMessages);
    }

    /** A standalone version of {@code question}, or the question itself when there is no history or anything fails. */
    public String rewrite(String question, ToolContext toolContext) {
        Object scope = toolContext == null ? null : toolContext.getContext().get(ToolContextKeys.SCOPE);
        if (!(scope instanceof String conversationId) || question == null || question.isBlank()) return question;
        try {
            List<Message> history = recent(chatMemory.get(conversationId));
            if (history.size() < 2) return question; // first turn: nothing to resolve against
            String transcript = history.stream()
                    .map(m -> m.getMessageType().name().toLowerCase() + ": " + shorten(m.getText()))
                    .collect(Collectors.joining("\n"));
            String rewritten = chatClient.prompt()
                    .system(INSTRUCTIONS)
                    .user("Conversation:\n" + transcript + "\n\nLatest search question: " + question)
                    .call()
                    .content();
            String query = clean(rewritten);
            if (query.isBlank()) return question;
            if (!query.equalsIgnoreCase(question)) log.info("Query rewritten: \"{}\" -> \"{}\"", question, query);
            return query;
        } catch (RuntimeException e) {
            log.warn("Query rewrite failed ({}); searching with the original question", e.toString());
            return question;
        }
    }

    /** The last N user/assistant messages. System and tool messages are not conversation. */
    private List<Message> recent(List<Message> all) {
        List<Message> talk = all.stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .filter(m -> m.getText() != null && !m.getText().isBlank())
                .toList();
        return talk.subList(Math.max(0, talk.size() - historyMessages), talk.size());
    }

    static String clean(String text) {
        if (text == null) return "";
        String firstLine = text.strip().lines().findFirst().orElse("").strip();
        if (firstLine.regionMatches(true, 0, "query:", 0, 6)) firstLine = firstLine.substring(6).strip();
        return firstLine.replaceAll("^[\"'`]+|[\"'`]+$", "").strip();
    }

    private static String shorten(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_CHARS_PER_MESSAGE ? flat : flat.substring(0, MAX_CHARS_PER_MESSAGE) + "…";
    }
}
