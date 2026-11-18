package com.example.agent.core.tool;

import org.springframework.ai.chat.model.ToolContext;

/**
 * Keys of the per-request ToolContext map, and a safe way to read them.
 *
 * Why a ToolContext and not a ThreadLocal: tools may run on another thread than the HTTP request
 * (streaming, Reactor workers), so a ThreadLocal set by the controller is often empty inside the
 * tool. The ToolContext map travels with the tool call. Identity always comes from here, never
 * from a tool argument the model filled in.
 */
public final class ToolContextKeys {

    /** The signed-in customer. Tools must only show this customer's data. */
    public static final String CUSTOMER_ID = "customerId";

    /** Server-side hash of principal + session + conversation; keys memory and pending actions. */
    public static final String SCOPE = "scope";

    private ToolContextKeys() {
    }

    /** The value under {@code key}; throws if the controller did not set it, so a wiring bug fails loudly. */
    public static String require(ToolContext context, String key) {
        Object value = context == null ? null : context.getContext().get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("ToolContext has no " + key + "; the controller must set it");
        }
        return s;
    }
}
