package com.example.agent.model;

/**
 * Structured output example: the model fills this record (ChatClient .entity(SupportTriage.class)).
 * Use it for anything another program reads, such as routing a ticket to the right queue.
 */
public record SupportTriage(Category category, Urgency urgency, String orderId, String summary, String suggestedNextStep) {

    public enum Category { ORDER_STATUS, CANCELLATION, RETURN_OR_REFUND, SHIPPING, ACCOUNT, OTHER }

    public enum Urgency { LOW, MEDIUM, HIGH }
}
