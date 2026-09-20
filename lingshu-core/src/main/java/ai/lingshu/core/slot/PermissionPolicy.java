package ai.lingshu.core.slot;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;

/**
 * Slot 3 model layer — decides whether a tool call may proceed. Called once per
 * {@code ToolExecutor.dispatch} before the sandbox wraps the call (dsh §4.7).
 *
 * <p>Three outcomes:
 * <ul>
 *   <li>{@link Decision.Allow} — proceed</li>
 *   <li>{@link Decision.Deny} — throw {@code PermissionDeniedException}</li>
 *   <li>{@link Decision.AskUser} — pause and route to {@link ToolExecutionContext.ApprovalGate}</li>
 * </ul>
 *
 * <p>Implementations may consult filesystem state (read-only tools allowed everywhere),
 * config (domain whitelist), history (previous grants), or any combination. Policy MUST be
 * deterministic for a given (call, ctx) tuple if audit reproducibility matters.
 */
public interface PermissionPolicy {

    /**
     * Check whether {@code call} may proceed given {@code ctx}.
     *
     * @param call the candidate tool call (name + JSON args)
     * @param ctx  the surrounding execution context (fs scope, session, callConfig)
     * @return allow / deny / ask-user; never null
     */
    Decision check(ToolCall call, ToolExecutionContext ctx);
}