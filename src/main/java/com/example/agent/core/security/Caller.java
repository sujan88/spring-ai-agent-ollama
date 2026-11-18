package com.example.agent.core.security;

/** Who is asking: the customer whose data the tools may read, and the server-side conversation scope. */
public record Caller(String customerId, String scope) {
}
