package com.example.agent.chat;

import com.example.agent.core.security.Caller;
import com.example.agent.core.security.RequestIdentity;
import com.example.agent.model.ChatReply;
import com.example.agent.model.ChatRequest;
import com.example.agent.model.SupportTriage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    static final int MAX_MESSAGE_CHARS = 4000;

    private final ChatService chat;
    private final RequestIdentity identity;

    public ChatController(ChatService chat, RequestIdentity identity) {
        this.chat = chat;
        this.identity = identity;
    }

    /** SSE: data {"t":"..."} per token, then data {"sources":[...]} when the knowledge base was used. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Object> stream(HttpServletRequest request,
                               @RequestParam(required = false) String conversation,
                               @RequestParam String message) {
        Caller caller = identity.resolve(request, conversation);
        return chat.stream(caller, validated(message));
    }

    @PostMapping
    public ChatReply chat(HttpServletRequest request, @RequestBody ChatRequest body) {
        Caller caller = identity.resolve(request, body.conversation());
        return chat.call(caller, validated(body.message()));
    }

    /** Structured output: classifies a support message into a SupportTriage record. */
    @PostMapping("/triage")
    public SupportTriage triage(HttpServletRequest request, @RequestBody ChatRequest body) {
        identity.resolve(request, body.conversation());
        return chat.triage(validated(body.message()));
    }

    static String validated(String message) {
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is longer than " + MAX_MESSAGE_CHARS + " characters");
        }
        return message;
    }
}
