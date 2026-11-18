package com.example.agent.config;

import com.example.agent.core.advisor.InputGuardrailAdvisor;
import com.example.agent.core.advisor.TokenUsageAuditAdvisor;
import com.example.agent.core.guard.GroundingChecker;
import com.example.agent.core.guard.OutputRedactionAdvisor;
import com.example.agent.core.tool.ObservedToolCallback;
import com.example.agent.rag.tool.KnowledgeBaseTool;
import com.example.agent.tools.OrderTools;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * One ChatClient per job. The support agent gets prompt, tools and the full advisor chain
 * (lowest order runs first, outermost):
 *
 *   InputGuardrailAdvisor     HIGHEST_PRECEDENCE        refuses injections before any token is spent
 *   OutputRedactionAdvisor    HIGHEST_PRECEDENCE + 50   masks card numbers / SSNs in the answer (call + stream)
 *   MessageChatMemoryAdvisor  HIGHEST_PRECEDENCE + 200  loads/saves history (Spring AI 2.0 default)
 *   TokenUsageAuditAdvisor    HIGHEST_PRECEDENCE + 250  counts tokens once per turn, tool loop included
 *   ToolCallingAdvisor        HIGHEST_PRECEDENCE + 300  added by Spring AI; runs the tool loop
 *   SimpleLoggerAdvisor       0                         inside the loop: logs every model call
 *
 * Every tool is wrapped in ObservedToolCallback (log, timer metric, per-request ToolCallLog).
 */
@Configuration
public class AiConfig {

    @Bean
    public InputGuardrailAdvisor inputGuardrailAdvisor(@Value("${app.guardrail.enabled:true}") boolean enabled,
                                                       @Value("${app.guardrail.log-full-message:false}") boolean logFull,
                                                       @Value("${app.guardrail.refusal:}") String refusal,
                                                       MeterRegistry registry) {
        return new InputGuardrailAdvisor(enabled, logFull, refusal, registry);
    }

    @Bean
    public TokenUsageAuditAdvisor tokenUsageAuditAdvisor(MeterRegistry registry) {
        return new TokenUsageAuditAdvisor(registry);
    }

    /** The support agent: order tools, the knowledge-base tool (when RAG is on), memory, guardrails. */
    @Bean
    public ChatClient supportChatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                        InputGuardrailAdvisor guardrail, TokenUsageAuditAdvisor tokenAudit,
                                        OrderTools orderTools, ObjectProvider<KnowledgeBaseTool> knowledgeBase,
                                        MeterRegistry registry,
                                        @Value("${app.guard.redact-output.enabled:true}") boolean redactOutput,
                                        @Value("classpath:/prompts/system.st") Resource systemPrompt) {
        List<Object> toolObjects = new ArrayList<>(List.of(orderTools));
        knowledgeBase.ifAvailable(toolObjects::add);
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(toolObjects.toArray()).build().getToolCallbacks();

        List<Advisor> advisors = new ArrayList<>();
        advisors.add(guardrail);
        if (redactOutput) advisors.add(new OutputRedactionAdvisor());
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        advisors.add(tokenAudit);
        advisors.add(new SimpleLoggerAdvisor());

        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(advisors)
                .defaultTools(ObservedToolCallback.wrap(callbacks, registry).toArray())
                .build();
    }

    /**
     * Structured-output classifier: no tools, no memory, one call in and one record out.
     * No guardrail here on purpose: its refusal is plain text and would not parse into the record.
     * With no tools and enum fields, an injected prompt can at worst change the label.
     */
    @Bean
    public ChatClient triageChatClient(ChatClient.Builder builder, TokenUsageAuditAdvisor tokenAudit,
                                       @Value("classpath:/prompts/triage.st") Resource triagePrompt) {
        return builder
                .defaultSystem(triagePrompt)
                .defaultAdvisors(tokenAudit)
                .build();
    }

    /** Small internal jobs (query rewrite, LLM rerank, grounding check): no prompt, no memory, no tools. */
    @Bean
    public ChatClient utilityChatClient(ChatClient.Builder builder, TokenUsageAuditAdvisor tokenAudit) {
        return builder.defaultAdvisors(tokenAudit).build();
    }

    @Bean
    public GroundingChecker groundingChecker(@Qualifier("utilityChatClient") ChatClient utility) {
        return new GroundingChecker(utility);
    }
}
