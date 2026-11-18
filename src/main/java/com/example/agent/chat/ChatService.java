package com.example.agent.chat;

import com.example.agent.core.guard.GroundingChecker;
import com.example.agent.core.guard.GroundingChecker.Verdict;
import com.example.agent.core.security.Caller;
import com.example.agent.core.tool.ToolCallLog;
import com.example.agent.core.tool.ToolContextKeys;
import com.example.agent.model.ChatReply;
import com.example.agent.model.SupportTriage;
import com.example.agent.rag.RagSourceCollector;
import com.example.agent.rag.RagSourcesEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs one chat turn. The controller decides WHO is asking (Caller); this class turns that into
 * the conversation id for memory and the ToolContext the tools read identity from, and runs the
 * grounding check on answers that used the knowledge base.
 */
@Service
public class ChatService {

    /** One streamed token wrapped in JSON, so leading spaces survive SSE parsing ("data: " eats one). */
    public record TokenDelta(String t) {}

    /** Trailing SSE event with the grounding verdict, sent only when the knowledge base was used. */
    public record GroundingEvent(Verdict grounding) {}

    private final ChatClient support;
    private final ChatClient triage;
    private final GroundingChecker grounding;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(@Qualifier("supportChatClient") ChatClient support,
                       @Qualifier("triageChatClient") ChatClient triage,
                       GroundingChecker grounding,
                       @Value("${app.guard.grounding.enabled:true}") boolean groundingEnabled) {
        this(support, triage, groundingEnabled ? grounding : null, Clock.systemDefaultZone());
    }

    /** @param grounding null switches the grounding check off */
    ChatService(ChatClient support, ChatClient triage, GroundingChecker grounding, Clock clock) {
        this.support = support;
        this.triage = triage;
        this.grounding = grounding;
        this.clock = clock;
    }

    /** Streams TokenDelta events, then "sources" and "grounding" events if the knowledge base was searched. */
    public Flux<Object> stream(Caller caller, String message) {
        RagSourceCollector sources = new RagSourceCollector();
        ToolCallLog toolCalls = new ToolCallLog();
        StringBuilder answer = new StringBuilder();
        return Flux.defer(() -> request(caller, message, sources, toolCalls)
                        .stream()
                        .content()
                        .map(text -> {
                            answer.append(text);
                            return (Object) new TokenDelta(text);
                        }))
                .concatWith(Flux.defer(() -> RagSourcesEvent.after(sources)))
                .concatWith(Flux.defer(() -> groundingEvent(message, sources, answer)));
    }

    public ChatReply call(Caller caller, String message) {
        RagSourceCollector sources = new RagSourceCollector();
        ToolCallLog toolCalls = new ToolCallLog();
        String answer = request(caller, message, sources, toolCalls).call().content();
        return new ChatReply(answer, RagSourcesEvent.sources(sources), toolCalls.toolNames(),
                verdict(message, sources, answer));
    }

    public SupportTriage triage(String message) {
        return triage.prompt().user(message).call().entity(SupportTriage.class);
    }

    private Verdict verdict(String question, RagSourceCollector sources, String answer) {
        if (grounding == null || sources.documents().isEmpty()) return null;
        return grounding.check(question, sources.documents(), answer);
    }

    private Flux<Object> groundingEvent(String question, RagSourceCollector sources, StringBuilder answer) {
        if (grounding == null || sources.documents().isEmpty()) return Flux.empty();
        // The check is a blocking model call: keep it off the stream's thread.
        return Mono.fromCallable(() -> (Object) new GroundingEvent(grounding.check(question, sources.documents(), answer.toString())))
                .subscribeOn(Schedulers.boundedElastic())
                .flux();
    }

    private ChatClient.ChatClientRequestSpec request(Caller caller, String message, RagSourceCollector sources,
                                                     ToolCallLog toolCalls) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.CUSTOMER_ID, caller.customerId());
        toolContext.put(ToolContextKeys.SCOPE, caller.scope());
        toolContext.put(RagSourceCollector.CONTEXT_KEY, sources);
        toolContext.put(ToolCallLog.CONTEXT_KEY, toolCalls);
        return support.prompt()
                // Session facts go in system-prompt params, not the user message, so memory does not replay them.
                .system(s -> s.param("today", LocalDate.now(clock).toString()))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, caller.scope()))
                .toolContext(toolContext)
                .user(message);
    }
}
