package com.example.agent.core.tool;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The tools called during one chat request, in order. ChatService puts one in the ToolContext;
 * ObservedToolCallback fills it. Used for the reply's "toolCalls", logs, and tool-trajectory evals
 * (did the agent call getOrder before answering? did it avoid prepareCancellation?).
 */
public final class ToolCallLog {

    public static final String CONTEXT_KEY = "toolCallLog";

    public record Call(String tool, long durationMs, boolean failed) {}

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    void add(Call call) {
        calls.add(call);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<String> toolNames() {
        return calls.stream().map(Call::tool).toList();
    }
}
