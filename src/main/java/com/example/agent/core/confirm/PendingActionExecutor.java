package com.example.agent.core.confirm;

import java.util.Map;

/**
 * Carries out one kind of irreversible action after a human confirmed it.
 *
 * Implementations must re-check everything (ownership, current state): time has passed since the
 * draft was prepared. Throw to report a failure; the message is shown to the user.
 */
public interface PendingActionExecutor {

    /** The action type this executor handles, for example CANCEL_ORDER. */
    String type();

    /** Runs the action and returns a short human-readable result. */
    String execute(Map<String, String> payload);
}
