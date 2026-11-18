package com.example.agent.core.confirm;

import com.example.agent.core.security.Caller;
import com.example.agent.core.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The human side of the two-phase flow. The model has no route to these endpoints. */
@RestController
@RequestMapping("/api/actions")
public class PendingActionController {

    public record ConfirmRequest(String conversation, String token) {}

    private final PendingActionService actions;
    private final RequestIdentity identity;

    public PendingActionController(PendingActionService actions, RequestIdentity identity) {
        this.actions = actions;
        this.identity = identity;
    }

    @GetMapping("/pending")
    public ResponseEntity<PendingActionService.Review> pending(HttpServletRequest request,
                                                               @RequestParam(required = false) String conversation) {
        Caller caller = identity.resolve(request, conversation);
        return actions.review(caller.scope()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/confirm")
    public PendingActionService.Outcome confirm(HttpServletRequest request, @PathVariable String id,
                                                @RequestBody ConfirmRequest body) {
        Caller caller = identity.resolve(request, body.conversation());
        return actions.confirm(caller.scope(), id, body.token());
    }
}
