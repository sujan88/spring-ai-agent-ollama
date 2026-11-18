package com.example.agent.core.guard;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regex families for prompt-injection shapes. Used in two places:
 *   - InputGuardrailAdvisor: the user's own message (direct injection)
 *   - KnowledgeBaseTool: retrieved document chunks (indirect injection: instructions hidden in data)
 *
 * Cheap, deterministic and easy to get around. It is one layer; tools still validate their inputs
 * and identity never comes from text.
 */
public final class PromptInjectionDetector {

    private record InjectionPattern(String name, Pattern pattern) {}

    private static final List<InjectionPattern> PATTERNS = List.of(
            new InjectionPattern("INSTRUCTION_OVERRIDE",
                    Pattern.compile("(?i)\\b(ignore|disregard|forget|override|bypass)\\b.{0,30}\\b(previous|prior|above|system|all)\\b.{0,20}\\b(instructions?|prompts?|rules?|constraints?)\\b")),
            new InjectionPattern("ROLE_OVERRIDE",
                    Pattern.compile("(?i)\\b(you are now|act as|pretend to be|roleplay as|behave as|from now on you)\\b.{0,50}")),
            // Targets the assistant's own prompt only. A broader version also matched "rules"/"context" alone,
            // which refused ordinary questions such as "can you show me the return rules?".
            new InjectionPattern("PROMPT_EXTRACTION",
                    Pattern.compile("(?i)\\b(reveal|show|display|print|output|repeat|echo)\\b.{0,30}\\b(system prompt|hidden prompt|initial prompt|your (instructions|rules|prompt|context))\\b")),
            new InjectionPattern("NEW_RULES",
                    Pattern.compile("(?i)\\b(new rules|new instructions|updated prompt|revised instructions|here are your new)\\b")),
            new InjectionPattern("JAILBREAK",
                    Pattern.compile("(?i)\\b(DAN|do anything now|jailbreak|developer mode|unrestricted mode|god mode)\\b")),
            new InjectionPattern("ENCODED_PAYLOAD",
                    Pattern.compile("(?i)(base64|\\beval\\b|\\bdecode\\b).{0,20}(aWdub3Jl|SWdub3Jl|cHJvbXB0)")),
            new InjectionPattern("DELIMITER_INJECTION",
                    Pattern.compile("(?i)(\\[SYSTEM\\]|\\[INST\\]|<\\|im_start\\|>|<\\|system\\|>|###\\s*(System|Assistant|Human))")),
            new InjectionPattern("SAFETY_BYPASS",
                    Pattern.compile("(?i)\\b(ignore safety|disable filter|remove restriction|no censorship|without guidelines|bypass safety)\\b"))
    );

    private PromptInjectionDetector() {
    }

    /** The name of the first pattern the text matches, if any. */
    public static Optional<String> firstMatch(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        return PATTERNS.stream().filter(p -> p.pattern().matcher(text).find()).map(InjectionPattern::name).findFirst();
    }
}
