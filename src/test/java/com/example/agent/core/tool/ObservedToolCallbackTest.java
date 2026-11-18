package com.example.agent.core.tool;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservedToolCallbackTest {

    static class EchoTools {
        @Tool(name = "echo", description = "Echo the text back")
        public String echo(String text, ToolContext toolContext) {
            if ("boom".equals(text)) throw new IllegalStateException("broken tool");
            return text;
        }
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ToolCallback echo = ObservedToolCallback.wrap(
            MethodToolCallbackProvider.builder().toolObjects(new EchoTools()).build().getToolCallbacks(), registry).get(0);

    @Test
    void recordsEachCallInTheRequestsLogAndMetrics() {
        ToolCallLog log = new ToolCallLog();
        String result = echo.call("{\"text\":\"hi\"}", new ToolContext(Map.of(ToolCallLog.CONTEXT_KEY, log)));

        assertTrue(result.contains("hi"));
        assertEquals(List.of("echo"), log.toolNames());
        assertFalse(log.calls().get(0).failed());
        assertEquals(1, registry.get("ai.tool.calls").tag("tool", "echo").tag("outcome", "ok").timer().count());
    }

    @Test
    void aFailingToolIsRecordedAsFailedAndStillFails() {
        ToolCallLog log = new ToolCallLog();
        assertThrows(RuntimeException.class,
                () -> echo.call("{\"text\":\"boom\"}", new ToolContext(Map.of(ToolCallLog.CONTEXT_KEY, log))));
        assertTrue(log.calls().get(0).failed());
    }

    @Test
    void keepsTheDelegatesDefinitionSoTheModelSeesTheSameTool() {
        assertEquals("echo", echo.getToolDefinition().name());
    }
}
