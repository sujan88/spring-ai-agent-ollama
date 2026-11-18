package com.example.agent.core.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns an HTTP request into a {@link Caller}.
 *
 * The customer comes from the authenticated principal. The "conversation" request parameter only
 * selects one of the caller's conversations: it is hashed together with the principal and the HTTP
 * session, so one user can never read or continue another user's conversation by guessing its id.
 *
 * Demo mode (app.security.demo-mode=true, on in the local profile) lets an unauthenticated browser
 * act as app.security.demo-customer-id. Never enable it in a deployed environment; add Spring
 * Security and a real login instead.
 */
@Component
public class RequestIdentity {

    private static final Pattern CONVERSATION = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final boolean demoMode;
    private final String demoCustomerId;

    public RequestIdentity(@Value("${app.security.demo-mode:false}") boolean demoMode,
                           @Value("${app.security.demo-customer-id:C-1001}") String demoCustomerId) {
        this.demoMode = demoMode;
        this.demoCustomerId = demoCustomerId;
    }

    public Caller resolve(HttpServletRequest request, String conversation) {
        String convo = conversation == null || conversation.isBlank() ? "default" : conversation;
        if (!CONVERSATION.matcher(convo).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversation must be 1-64 letters, digits, - or _");
        }
        Principal principal = request.getUserPrincipal();
        if (principal == null && !demoMode) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authentication required. For local demos set app.security.demo-mode=true.");
        }
        String customerId = principal != null ? principal.getName() : demoCustomerId;
        String session = request.getSession(true).getId();
        return new Caller(customerId, scope(customerId, session, convo));
    }

    /**
     * SHA-256 over length-prefixed parts (so ("ab","c") and ("a","bc") never hash the same), shaped as a
     * UUID: the first 128 bits of the hash. 36 characters, because Spring AI's chat-memory table stores
     * conversation_id as VARCHAR(36). 128 bits from a hash that includes the session id cannot be guessed.
     */
    static String scope(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) joined.append(part.length()).append(':').append(part);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            ByteBuffer first128 = ByteBuffer.wrap(digest, 0, 16);
            return new UUID(first128.getLong(), first128.getLong()).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
