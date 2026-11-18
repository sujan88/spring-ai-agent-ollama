package com.example.agent.core.confirm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Two-phase execution for anything the agent must not do on its own (cancel, refund, book, send).
 *
 *   1. prepare  the AI tool stores a DRAFT (status PENDING, short TTL). Nothing happens yet.
 *   2. review   the browser asks for the pending draft and gets a one-time token. Only the token's
 *               SHA-256 is stored, and the token never goes back to the model.
 *   3. confirm  a human presses Confirm; the browser sends id + token. One conditional UPDATE claims
 *               the row (PENDING to CLAIMED), so a double click or a replay runs the action at most once.
 *
 * A "yes" typed in the chat can therefore never execute anything: the model has no token.
 * Rows are keyed by the server-side conversation scope, so one user cannot confirm another's draft.
 */
@Service
public class PendingActionService {

    public record Draft(String id, String type, String summary, Instant expiresAt) {}

    public record Review(String id, String type, String summary, String confirmationToken, Instant expiresAt) {}

    public record Outcome(String id, String status, String message) {}

    static final String PENDING = "PENDING";

    private final JdbcTemplate jdbc;
    private final Map<String, PendingActionExecutor> executors;
    private final Duration ttl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public PendingActionService(JdbcTemplate jdbc, List<PendingActionExecutor> executors,
                                @Value("${app.actions.ttl:PT5M}") Duration ttl) {
        this(jdbc, executors, ttl, Clock.systemUTC());
    }

    PendingActionService(JdbcTemplate jdbc, List<PendingActionExecutor> executors, Duration ttl, Clock clock) {
        this.jdbc = jdbc;
        this.executors = executors.stream().collect(Collectors.toMap(PendingActionExecutor::type, Function.identity()));
        this.ttl = ttl;
        this.clock = clock;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pending_action (
                    id          VARCHAR(36)   PRIMARY KEY,
                    scope       VARCHAR(128)  NOT NULL,
                    action_type VARCHAR(64)   NOT NULL,
                    summary     VARCHAR(1000) NOT NULL,
                    payload     VARCHAR(4000) NOT NULL,
                    token_hash  VARCHAR(64),
                    expires_at  BIGINT        NOT NULL,
                    status      VARCHAR(16)   NOT NULL,
                    result      VARCHAR(1000)
                )""");
    }

    /** Stores a draft. One open draft per conversation: an older pending draft is replaced. */
    public Draft prepare(String scope, String type, String summary, Map<String, String> payload) {
        if (!executors.containsKey(type)) throw new IllegalArgumentException("No executor for action type " + type);
        long now = clock.millis();
        jdbc.update("DELETE FROM pending_action WHERE status = 'PENDING' AND (scope = ? OR expires_at < ?)", scope, now);
        String id = UUID.randomUUID().toString();
        Instant expires = clock.instant().plus(ttl);
        jdbc.update("INSERT INTO pending_action (id, scope, action_type, summary, payload, expires_at, status) VALUES (?,?,?,?,?,?,?)",
                id, scope, type, summary, encode(payload), expires.toEpochMilli(), PENDING);
        return new Draft(id, type, summary, expires);
    }

    /** For the browser only: the open draft with a fresh one-time token. Never call this from a tool. */
    public Optional<Review> review(String scope) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, action_type, summary, expires_at FROM pending_action WHERE scope = ? AND status = 'PENDING' AND expires_at > ?",
                scope, clock.millis());
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        String id = (String) row.get("id");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE pending_action SET token_hash = ? WHERE id = ? AND status = 'PENDING'", sha256(token), id);
        return Optional.of(new Review(id, (String) row.get("action_type"), (String) row.get("summary"), token,
                Instant.ofEpochMilli(((Number) row.get("expires_at")).longValue())));
    }

    /** Claims the draft with one conditional UPDATE, then runs it. A second call for the same draft gets 409. */
    public Outcome confirm(String scope, String id, String token) {
        if (token == null || token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token is required");
        int claimed = jdbc.update("""
                UPDATE pending_action SET status = 'CLAIMED'
                WHERE id = ? AND scope = ? AND status = 'PENDING' AND token_hash = ? AND expires_at > ?""",
                id, scope, sha256(token), clock.millis());
        if (claimed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing to confirm: the draft expired, was already used, or the token does not match");
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT action_type, payload FROM pending_action WHERE id = ?", id);
        PendingActionExecutor executor = executors.get((String) row.get("action_type"));
        try {
            String result = executor.execute(decode((String) row.get("payload")));
            jdbc.update("UPDATE pending_action SET status = 'DONE', result = ? WHERE id = ?", truncate(result), id);
            return new Outcome(id, "DONE", result);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("UPDATE pending_action SET status = 'FAILED', result = ? WHERE id = ?", truncate(message), id);
            return new Outcome(id, "FAILED", message);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** java.util.Properties text: no JSON library needed, and values may hold any character. */
    static String encode(Map<String, String> payload) {
        Properties p = new Properties();
        p.putAll(payload);
        StringWriter out = new StringWriter();
        try {
            p.store(out, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    static Map<String, String> decode(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> map = new LinkedHashMap<>();
        p.stringPropertyNames().forEach(k -> map.put(k, p.getProperty(k)));
        return map;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
