package com.example.agent.chat;

import com.example.agent.core.security.Caller;
import com.example.agent.core.tool.ToolContextKeys;
import com.example.agent.model.ChatReply;
import com.example.agent.model.SupportTriage;
import com.example.agent.rag.RagSourceCollector;
import com.example.agent.rag.RagSourcesEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs one chat turn. The controller decides WHO is asking (Caller); this class turns that into
 * the conversation id for memory and the ToolContext the tools read identity from.
 */
@Service
public class ChatService {

    /** One streamed token wrapped in JSON, so leading spaces survive SSE parsing ("data: " eats one). */
    public record TokenDelta(String t) {}

    private final ChatClient support;
    private final ChatClient triage;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(@Qualifier("supportChatClient") ChatClient support,
                       @Qualifier("triageChatClient") ChatClient triage) {
        this(support, triage, Clock.systemDefaultZone());
    }

    ChatService(ChatClient support, ChatClient triage, Clock clock) {
        this.support = support;
        this.triage = triage;
        this.clock = clock;
    }

    /** Streams TokenDelta events, then one "sources" event if the knowledge base was searched. */
    public Flux<Object> stream(Caller caller, String message) {
        RagSourceCollector sources = new RagSourceCollector();
        return Flux.defer(() -> request(caller, message, sources)
                        .stream()
                        .content()
                        .map(text -> (Object) new TokenDelta(text)))
                .concatWith(Flux.defer(() -> RagSourcesEvent.after(sources)));
    }

    public ChatReply call(Caller caller, String message) {
        RagSourceCollector sources = new RagSourceCollector();
        String answer = request(caller, message, sources).call().content();
        return new ChatReply(answer, RagSourcesEvent.sources(sources));
    }

    public SupportTriage triage(String message) {
        return triage.prompt().user(message).call().entity(SupportTriage.class);
    }

    private ChatClient.ChatClientRequestSpec request(Caller caller, String message, RagSourceCollector sources) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.CUSTOMER_ID, caller.customerId());
        toolContext.put(ToolContextKeys.SCOPE, caller.scope());
        toolContext.put(RagSourceCollector.CONTEXT_KEY, sources);
        return support.prompt()
                // Session facts go in system-prompt params, not the user message, so memory does not replay them.
                .system(s -> s.param("today", LocalDate.now(clock).toString()))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, caller.scope()))
                .toolContext(toolContext)
                .user(message);
    }
}
