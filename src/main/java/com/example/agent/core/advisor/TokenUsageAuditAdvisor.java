package com.example.agent.core.advisor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * Post-call advisor that tracks token usage per conversation and exposes
 * cumulative metrics via Micrometer (scraped by Prometheus).
 *
 * Two levels of tracking:
 *
 * 1. PER-CONVERSATION (in-memory): ConcurrentHashMap keyed by conversationId.
 *    Answers "how many tokens has session X consumed?" — useful for per-user cost
 *    attribution and budget enforcement.
 *
 * 2. GLOBAL COUNTERS (Micrometer): Total prompt/completion/total tokens across ALL
 *    conversations, exposed at /actuator/prometheus:
 *      ai_tokens_prompt_total
 *      ai_tokens_completion_total
 *      ai_tokens_total
 *      ai_requests_total
 *      ai_guardrail_blocks_total  (from InputGuardrailAdvisor)
 *
 * Estimated cost is logged per call using configurable $/1K-token rates for the
 * model in use. The default rates are placeholders; pass your model's real prices.
 *
 * Ordering: Spring AI 2.0 runs tool calls inside the advisor chain (ToolCallingAdvisor at
 * HIGHEST_PRECEDENCE + 300) and accumulates Usage across the tool loop. This advisor sits just
 * outside that loop (HIGHEST_PRECEDENCE + 250), so one user turn is counted once, tool calls included.
 *
 * It implements both CallAdvisor and StreamAdvisor: Spring AI skips CallAdvisor-only advisors on
 * ChatClient.stream(). For streams the usage arrives on the last chunk; the OpenAI integration
 * needs stream usage switched on for it to be reported.
 *
 * USAGE:
 *   // With Micrometer (auto-injected by Spring Boot Actuator):
 *   new TokenUsageAuditAdvisor(meterRegistry)
 *
 *   // Without Micrometer (logging only):
 *   new TokenUsageAuditAdvisor()
 */
public class TokenUsageAuditAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageAuditAdvisor.class);

    /** Just outside the tool-calling loop (ToolCallingAdvisor is HIGHEST_PRECEDENCE + 300). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 250;

    // Placeholder cost per 1K tokens. Set real rates for your model with the 3-argument constructor.
    private static final double DEFAULT_PROMPT_COST_PER_1K = 0.00025;
    private static final double DEFAULT_COMPLETION_COST_PER_1K = 0.002;

    // ── Per-conversation accumulation ────────────────────────────────────────
    private final ConcurrentHashMap<String, SessionTokenStats> sessionStats = new ConcurrentHashMap<>();

    // ── Micrometer counters (null-safe if no registry provided) ──────────────
    private final Counter promptTokenCounter;
    private final Counter completionTokenCounter;
    private final Counter totalTokenCounter;
    private final Counter requestCounter;

    // ── Cost rates ───────────────────────────────────────────────────────────
    private final double promptCostPer1K;
    private final double completionCostPer1K;

    /** No-metrics constructor — logging only. */
    public TokenUsageAuditAdvisor() {
        this(null, DEFAULT_PROMPT_COST_PER_1K, DEFAULT_COMPLETION_COST_PER_1K);
    }

    /** With Micrometer registry — exposes counters to Prometheus. */
    public TokenUsageAuditAdvisor(MeterRegistry registry) {
        this(registry, DEFAULT_PROMPT_COST_PER_1K, DEFAULT_COMPLETION_COST_PER_1K);
    }

    /** Full constructor with custom cost rates. */
    public TokenUsageAuditAdvisor(MeterRegistry registry, double promptCostPer1K, double completionCostPer1K) {
        this.promptCostPer1K = promptCostPer1K;
        this.completionCostPer1K = completionCostPer1K;

        if (registry != null) {
            this.promptTokenCounter = Counter.builder("ai.tokens.prompt")
                    .description("Cumulative prompt tokens across all conversations")
                    .register(registry);
            this.completionTokenCounter = Counter.builder("ai.tokens.completion")
                    .description("Cumulative completion tokens across all conversations")
                    .register(registry);
            this.totalTokenCounter = Counter.builder("ai.tokens.total")
                    .description("Cumulative total tokens across all conversations")
                    .register(registry);
            this.requestCounter = Counter.builder("ai.requests")
                    .description("Total LLM API requests")
                    .register(registry);
        } else {
            this.promptTokenCounter = null;
            this.completionTokenCounter = null;
            this.totalTokenCounter = null;
            this.requestCounter = null;
        }
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        Usage usage = usageOf(response);
        if (usage != null) record(request, usage);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        AtomicReference<Usage> last = new AtomicReference<>();
        return chain.nextStream(request)
                .doOnNext(response -> {
                    Usage usage = usageOf(response);
                    if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) last.set(usage);
                })
                .doOnComplete(() -> {
                    if (last.get() != null) record(request, last.get());
                });
    }

    private static Usage usageOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getMetadata() == null) return null;
        return response.chatResponse().getMetadata().getUsage();
    }

    private void record(ChatClientRequest request, Usage usage) {
        long prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        long total = usage.getTotalTokens() == null ? prompt + completion : usage.getTotalTokens();
        double costUsd = (prompt / 1000.0) * promptCostPer1K + (completion / 1000.0) * completionCostPer1K;

        String conversationId = resolveConversationId(request);
        SessionTokenStats stats = sessionStats.computeIfAbsent(conversationId, k -> new SessionTokenStats());
        stats.addUsage(prompt, completion, total, costUsd);

        log.info("Token usage [session={}] prompt: {}, completion: {}, total: {} | cost: ${} | session: {} tokens, ${}",
                abbreviate(conversationId), prompt, completion, total, String.format("%.4f", costUsd),
                stats.totalTokens.get(), String.format("%.4f", stats.totalCostUsd()));

        if (promptTokenCounter != null) {
            promptTokenCounter.increment(prompt);
            completionTokenCounter.increment(completion);
            totalTokenCounter.increment(total);
            requestCounter.increment();
        }
    }

    /** Conversation ids are hashes; the first 12 characters are enough to follow a session in the logs. */
    private static String abbreviate(String id) {
        return id.length() <= 12 ? id : id.substring(0, 12);
    }

    @Override
    public String getName() {
        return "TokenUsageAuditAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ── Public accessors for programmatic cost queries ────────────────────────

    /**
     * Get cumulative token stats for a specific conversation.
     * Returns null if the conversation hasn't been seen yet.
     */
    public SessionTokenStats getSessionStats(String conversationId) {
        return sessionStats.get(conversationId);
    }

    /** Get the number of active tracked sessions. */
    public int getActiveSessionCount() {
        return sessionStats.size();
    }

    /**
     * Clear stats for a conversation (e.g., when session ends).
     * The Micrometer counters are NOT decremented — they are monotonic.
     */
    public void clearSession(String conversationId) {
        sessionStats.remove(conversationId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveConversationId(ChatClientRequest request) {
        // Try to extract from advisor params (set by controller via .advisors(a -> a.param(...)))
        if (request.context() != null) {
            Object convId = request.context().get(CONVERSATION_ID);
            if (convId != null) {
                return convId.toString();
            }
        }
        return "unknown";
    }

    // ── Per-session stats record ─────────────────────────────────────────────

    public static class SessionTokenStats {
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong totalTokens = new AtomicLong();
        private final AtomicLong requestCount = new AtomicLong();
        private volatile double cumulativeCostUsd = 0.0;

        void addUsage(long prompt, long completion, long total, double costUsd) {
            promptTokens.addAndGet(prompt);
            completionTokens.addAndGet(completion);
            totalTokens.addAndGet(total);
            requestCount.incrementAndGet();
            synchronized (this) {
                cumulativeCostUsd += costUsd;
            }
        }

        public long getPromptTokens() { return promptTokens.get(); }
        public long getCompletionTokens() { return completionTokens.get(); }
        public long getTotalTokens() { return totalTokens.get(); }
        public long getRequestCount() { return requestCount.get(); }
        public synchronized double totalCostUsd() { return cumulativeCostUsd; }

        @Override
        public String toString() {
            return String.format("SessionTokenStats{requests=%d, prompt=%d, completion=%d, total=%d, cost=$%.4f}",
                    requestCount.get(), promptTokens.get(), completionTokens.get(),
                    totalTokens.get(), cumulativeCostUsd);
        }
    }
}
