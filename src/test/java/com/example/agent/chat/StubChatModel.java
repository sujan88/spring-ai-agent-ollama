package com.example.agent.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/** A ChatModel with a fixed answer that records every prompt it receives. No network. */
class StubChatModel implements ChatModel {

    final List<Prompt> calls = new ArrayList<>();
    final List<Prompt> streams = new ArrayList<>();
    private final String answer;

    StubChatModel(String answer) {
        this.answer = answer;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        streams.add(prompt);
        int mid = answer.length() / 2;
        return Flux.just(answer.substring(0, mid), answer.substring(mid))
                .map(part -> new ChatResponse(List.of(new Generation(new AssistantMessage(part)))));
    }
}
