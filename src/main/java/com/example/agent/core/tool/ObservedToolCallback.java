package com.example.agent.core.tool;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps every tool the agent can call: logs it, times it (metric ai.tool.calls{tool,outcome}) and
 * records it in the request's ToolCallLog. Tool code stays free of this plumbing.
 */
public class ObservedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ObservedToolCallback.class);

    private final ToolCallback delegate;
    private final MeterRegistry registry;

    public ObservedToolCallback(ToolCallback delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /** Wraps each callback. */
    public static List<ToolCallback> wrap(ToolCallback[] callbacks, MeterRegistry registry) {
        return Arrays.stream(callbacks).<ToolCallback>map(c -> new ObservedToolCallback(c, registry)).toList();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        long start = System.nanoTime();
        boolean failed = false;
        try {
            return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("Tool {} {} in {} ms", name, failed ? "FAILED" : "ok", ms);
            if (registry != null) {
                Timer.builder("ai.tool.calls").tag("tool", name).tag("outcome", failed ? "error" : "ok")
                        .register(registry).record(ms, TimeUnit.MILLISECONDS);
            }
            if (toolContext != null && toolContext.getContext().get(ToolCallLog.CONTEXT_KEY) instanceof ToolCallLog callLog) {
                callLog.add(new ToolCallLog.Call(name, ms, failed));
            }
        }
    }
}
