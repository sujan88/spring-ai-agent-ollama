package com.example.agent.mcp;

import com.example.agent.orders.OrderGateway;
import com.example.agent.rag.tool.KnowledgeBaseTool;
import com.example.agent.rag.tool.KnowledgeBaseTool.KnowledgeBaseResult;
import com.example.agent.tools.OrderTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tools exposed to other AI clients over MCP (Claude Desktop, Claude Code, IDE agents).
 *
 * The MCP endpoint has no user login, so these are read-only and the server is enabled only in the
 * local profile (spring.ai.mcp.server.enabled). Before exposing it anywhere else, put it behind
 * authentication and scope every tool to the authenticated caller, as OrderTools does.
 */
@Component
public class McpTools {

    private final OrderGateway orders;
    private final OrderTools orderTools;
    private final ObjectProvider<KnowledgeBaseTool> knowledgeBase;

    public McpTools(OrderGateway orders, OrderTools orderTools, ObjectProvider<KnowledgeBaseTool> knowledgeBase) {
        this.orders = orders;
        this.orderTools = orderTools;
        this.knowledgeBase = knowledgeBase;
    }

    @Tool(name = "lookupOrder", description = "Development tool: look up any order by id (ORD-1234), with status, "
            + "items, dates and whether it can still be cancelled or returned.")
    public OrderTools.OrderLookup lookupOrder(@ToolParam(description = "Order id, for example ORD-1001") String orderId) {
        String id = orderId == null ? "" : orderId.strip().toUpperCase();
        return orders.findOrder(id)
                .map(o -> new OrderTools.OrderLookup(true, "OK", orderTools.view(o)))
                .orElseGet(() -> new OrderTools.OrderLookup(false, "NOT_FOUND", null));
    }

    @Tool(name = "searchPolicies", description = "Search the company policy documents (returns, refunds, shipping, "
            + "cancellations, error codes) and return the matching passages with their source file.")
    public KnowledgeBaseResult searchPolicies(@ToolParam(description = "The question, in plain words") String question) {
        KnowledgeBaseTool kb = knowledgeBase.getIfAvailable();
        if (kb == null) return new KnowledgeBaseResult(false, "RAG is disabled on this server.", List.of());
        return kb.search(question, null, KnowledgeBaseTool.NOT_FOUND);
    }
}
