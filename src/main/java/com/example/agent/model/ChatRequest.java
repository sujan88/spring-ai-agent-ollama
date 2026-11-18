package com.example.agent.model;

/** Body of POST /api/chat and /api/chat/triage. conversation is optional (default "default"). */
public record ChatRequest(String conversation, String message) {
}
