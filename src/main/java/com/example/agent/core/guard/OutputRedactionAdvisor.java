package com.example.agent.core.guard;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Output guardrail: masks card numbers and SSNs in what the model says (PiiRedactor), on both
 * call() and stream().
 *
 * Streaming is the hard part: a number can arrive split across chunks ("4111 1111" + " 1111 1111").
 * Each chunk is appended to a small carry buffer; everything except a trailing run of digits/spaces/
 * dashes is redacted and emitted, and that tail waits for the next chunk. The last tail is flushed
 * when the stream ends. Chunks without text (usage/finish metadata) pass through unchanged.
 *
 * Order HIGHEST_PRECEDENCE + 50: inside the input guardrail, outside memory and token audit, so those
 * still see the model's original output.
 */
public class OutputRedactionAdvisor implements CallAdvisor, StreamAdvisor {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        String text = textOf(response);
        if (text == null || text.isEmpty()) return response;
        String redacted = PiiRedactor.redact(text);
        return redacted.equals(text) ? response : withText(response, redacted);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        StringBuilder carry = new StringBuilder();
        AtomicReference<ChatClientResponse> last = new AtomicReference<>();
        Flux<ChatClientResponse> body = chain.nextStream(request).concatMap(response -> {
            String text = textOf(response);
            if (text == null || text.isEmpty()) return Flux.just(response);
            last.set(response);
            carry.append(text);
            int emitUpTo = carry.length() - PiiRedactor.unsafeTail(carry);
            if (emitUpTo == 0) return Flux.empty();
            String emit = PiiRedactor.redact(carry.substring(0, emitUpTo));
            carry.delete(0, emitUpTo);
            return Flux.just(withText(response, emit));
        });
        Flux<ChatClientResponse> flush = Flux.defer(() -> carry.length() == 0 || last.get() == null
                ? Flux.<ChatClientResponse>empty()
                : Flux.just(withText(last.get(), PiiRedactor.redact(carry.toString()))));
        return body.concatWith(flush);
    }

    static String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null) return null;
        AssistantMessage output = response.chatResponse().getResult().getOutput();
        return output == null ? null : output.getText();
    }

    static ChatClientResponse withText(ChatClientResponse response, String text) {
        ChatResponse original = response.chatResponse();
        Generation generation = new Generation(new AssistantMessage(text), original.getResult().getMetadata());
        ChatResponse changed = new ChatResponse(List.of(generation), original.getMetadata());
        return ChatClientResponse.builder().chatResponse(changed).context(response.context()).build();
    }

    @Override
    public String getName() {
        return "OutputRedactionAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
