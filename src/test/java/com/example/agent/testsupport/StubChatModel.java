package com.example.agent.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A ChatModel for unit tests: answers from a function, records every prompt, no network.
 * Streams the answer in two halves, so stream-handling code sees a split.
 */
public class StubChatModel implements ChatModel {

    public final List<Prompt> calls = new ArrayList<>();
    public final List<Prompt> streams = new ArrayList<>();
    private final Function<Prompt, String> answer;

    public StubChatModel(String answer) {
        this(prompt -> answer);
    }

    public StubChatModel(Function<Prompt, String> answer) {
        this.answer = answer;
    }

    /** A model whose every call fails, for fallback tests. */
    public static StubChatModel failing() {
        return new StubChatModel(prompt -> {
            throw new IllegalStateException("model down");
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.add(prompt);
        return response(answer.apply(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        streams.add(prompt);
        String text = answer.apply(prompt);
        int mid = text.length() / 2;
        return Flux.just(text.substring(0, mid), text.substring(mid)).map(StubChatModel::response);
    }

    /** The concatenated text of all messages in a prompt. */
    public static String text(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        prompt.getInstructions().forEach(m -> sb.append(m.getText()).append('\n'));
        return sb.toString();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
