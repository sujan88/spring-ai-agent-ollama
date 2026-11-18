package com.example.agent.model;

import com.example.agent.core.guard.GroundingChecker.Verdict;
import com.example.agent.rag.RagSourcesEvent.Source;

import java.util.List;

/**
 * Answer of POST /api/chat.
 *
 * @param sources   knowledge-base chunks the answer was grounded on (citations), empty if none
 * @param toolCalls tools the agent called this turn, in order
 * @param grounding verdict of the grounding check; null when it did not run (no sources or disabled)
 */
public record ChatReply(String answer, List<Source> sources, List<String> toolCalls, Verdict grounding) {
}
