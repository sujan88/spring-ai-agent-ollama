package com.example.agent.core.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Output check: is the answer supported by the knowledge-base chunks it was built from?
 *
 * A second, small model call (an LLM-as-judge "faithfulness" check) after the answer. It only runs
 * when the knowledge base was searched, because order facts come from tools, not documents.
 * The verdict is returned to the client (a "grounding" event or field) so the UI can warn the user;
 * it does not rewrite the answer. EvalIT uses the same checker to measure faithfulness offline.
 *
 * Never throws: a failed check is reported as not checked.
 */
public class GroundingChecker {

    /** checked=false means no verdict (no context, disabled, or the check failed). */
    public record Verdict(boolean checked, boolean supported, String reason) {
        public static Verdict notChecked(String reason) {
            return new Verdict(false, true, reason);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GroundingChecker.class);
    private static final int MAX_CONTEXT_CHARS = 6000;

    static final String INSTRUCTIONS = """
            You check whether an assistant's answer is supported by the given policy documents.
            Look only at statements about policies, rules, times, fees and processes.
            Ignore statements about the customer's specific orders (those come from live systems),
            greetings, and offers to help.
            Reply on ONE line, starting with SUPPORTED or UNSUPPORTED, then a colon and a short reason.
            Example: UNSUPPORTED: the documents say 30 days, the answer says 60 days.""";

    private final ChatClient chatClient;

    public GroundingChecker(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Verdict check(String question, List<Document> context, String answer) {
        if (context == null || context.isEmpty()) return Verdict.notChecked("no knowledge-base context");
        if (answer == null || answer.isBlank()) return Verdict.notChecked("empty answer");
        try {
            String reply = chatClient.prompt()
                    .system(INSTRUCTIONS)
                    .user("DOCUMENTS:\n" + documents(context) + "\n\nQUESTION:\n" + question + "\n\nANSWER:\n" + answer)
                    .call()
                    .content();
            Verdict verdict = parse(reply);
            if (verdict.checked() && !verdict.supported()) log.warn("Grounding check failed: {}", verdict.reason());
            return verdict;
        } catch (RuntimeException e) {
            log.warn("Grounding check could not run ({})", e.toString());
            return Verdict.notChecked("check failed: " + e.getClass().getSimpleName());
        }
    }

    static Verdict parse(String reply) {
        if (reply == null) return Verdict.notChecked("no verdict");
        String line = reply.strip().lines().findFirst().orElse("").strip();
        String upper = line.toUpperCase();
        String reason = line.contains(":") ? line.substring(line.indexOf(':') + 1).strip() : "";
        if (upper.startsWith("UNSUPPORTED")) return new Verdict(true, false, reason);
        if (upper.startsWith("SUPPORTED")) return new Verdict(true, true, reason);
        return Verdict.notChecked("unreadable verdict: " + (line.length() > 80 ? line.substring(0, 80) : line));
    }

    private static String documents(List<Document> context) {
        String all = context.stream()
                .map(d -> "[" + d.getMetadata().getOrDefault("source", "unknown") + "]\n" + d.getText())
                .collect(Collectors.joining("\n\n"));
        return all.length() <= MAX_CONTEXT_CHARS ? all : all.substring(0, MAX_CONTEXT_CHARS);
    }
}
