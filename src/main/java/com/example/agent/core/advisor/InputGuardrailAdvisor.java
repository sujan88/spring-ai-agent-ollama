package com.example.agent.core.advisor;

import com.example.agent.core.guard.PromptInjectionDetector;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

/**
 * First line of defence against prompt injection: PromptInjectionDetector's regex families, checked before the model
 * is called. A match returns a fixed refusal and the LLM is never called, so a blocked prompt costs
 * no tokens.
 *
 * It implements BOTH CallAdvisor and StreamAdvisor. Spring AI runs only StreamAdvisors on
 * ChatClient.stream() and only CallAdvisors on call(). An advisor that implements just CallAdvisor is
 * silently skipped on a streaming endpoint.
 *
 * Regex is cheap and easy to get around. Treat it as one layer: tools must still validate their own
 * inputs, and identity must come from the server, never from the prompt.
 */
public class InputGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(InputGuardrailAdvisor.class);

    public static final String DEFAULT_REFUSAL =
            "I'm not able to process that request. " +
            "I can help with questions about your orders, returns and shipping. " +
            "What would you like to do?";

    private final boolean enabled;
    private final boolean logFullMessage;
    private final String refusal;
    private final Counter blocks;

    public InputGuardrailAdvisor() {
        this(true, false, DEFAULT_REFUSAL, null);
    }

    public InputGuardrailAdvisor(boolean enabled, boolean logFullMessage, String refusal, MeterRegistry registry) {
        this.enabled = enabled;
        this.logFullMessage = logFullMessage;
        this.refusal = refusal == null || refusal.isBlank() ? DEFAULT_REFUSAL : refusal;
        this.blocks = registry == null ? null
                : Counter.builder("ai.guardrail.blocks").description("Prompts refused by the input guardrail").register(registry);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return blockedPattern(request).map(p -> refuse(request, p)).orElseGet(() -> chain.nextCall(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return blockedPattern(request).map(p -> Flux.just(refuse(request, p))).orElseGet(() -> chain.nextStream(request));
    }

    /** The name of the first pattern the user's message matches, if any. Package-private for tests. */
    Optional<String> blockedPattern(ChatClientRequest request) {
        if (!enabled) return Optional.empty();
        String text = lastUserMessage(request);
        if (text == null || text.isBlank()) return Optional.empty();
        return PromptInjectionDetector.firstMatch(text)
                .map(name -> {
                    log.warn("GUARDRAIL TRIGGERED: pattern={}, snippet=\"{}\"", name, logFullMessage ? text : truncate(text, 80));
                    return name;
                });
    }

    private ChatClientResponse refuse(ChatClientRequest request, String pattern) {
        if (blocks != null) blocks.increment();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(refusal))));
        return ChatClientResponse.builder().chatResponse(chatResponse).context(request.context()).build();
    }

    @Override
    public String getName() {
        return "InputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String lastUserMessage(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) return user.getText();
        }
        return null;
    }

    private static String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

}
