package com.example.agent.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers McpTools with the MCP server starter. Endpoint and on/off switch: spring.ai.mcp.server.* */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpToolCallbacks(McpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
