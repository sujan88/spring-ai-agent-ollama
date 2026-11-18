package com.example.agent.model;

import com.example.agent.rag.RagSourcesEvent.Source;

import java.util.List;

/** Answer of POST /api/chat: the text plus the knowledge-base chunks it was grounded on, if any. */
public record ChatReply(String answer, List<Source> sources) {
}
