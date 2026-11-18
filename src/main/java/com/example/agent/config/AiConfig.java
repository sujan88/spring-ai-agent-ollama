package com.example.agent.config;

import com.example.agent.core.advisor.InputGuardrailAdvisor;
import com.example.agent.core.advisor.TokenUsageAuditAdvisor;
import com.example.agent.rag.tool.KnowledgeBaseTool;
import com.example.agent.tools.OrderTools;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * One ChatClient per agent. Each gets its own prompt, tools and advisors, so tool routing stays
 * focused. Advisor order (lowest runs first):
 *
 *   InputGuardrailAdvisor     HIGHEST_PRECEDENCE        refuses injections before any token is spent
 *   MessageChatMemoryAdvisor  HIGHEST_PRECEDENCE + 200  loads/saves history (Spring AI 2.0 default)
 *   TokenUsageAuditAdvisor    HIGHEST_PRECEDENCE + 250  counts tokens once per turn, tool loop included
 *   ToolCallingAdvisor        HIGHEST_PRECEDENCE + 300  added by Spring AI; runs the tool loop
 *   SimpleLoggerAdvisor       0                         inside the loop: logs every model call
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

    /** The support agent: order tools, the knowledge-base tool (when RAG is on), memory. */
    @Bean
    public ChatClient supportChatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                        InputGuardrailAdvisor guardrail, TokenUsageAuditAdvisor tokenAudit,
                                        OrderTools orderTools, ObjectProvider<KnowledgeBaseTool> knowledgeBase,
                                        @Value("classpath:/prompts/system.st") Resource systemPrompt) {
        List<Object> tools = new ArrayList<>(List.of(orderTools));
        knowledgeBase.ifAvailable(tools::add);
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(guardrail, MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        tokenAudit, new SimpleLoggerAdvisor())
                .defaultTools(tools.toArray())
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
}
