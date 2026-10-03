package ai.lingshu.core.decision;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;

import java.util.List;

/**
 * Outcome of a {@code PermissionPolicy.check} call — allow / deny / ask.
 *
 * <p>Polymorphic via abstract + nested {@code @Value} classes; sealed semantics at the source level.
 * Runtime instanceof checks ({@code Decision instanceof Allow}) are exhaustive because the 3 subclasses
 * are the only legal constructions.
 */
public abstract class Decision {

    /** Kind tag — "allow" / "ask" / "deny". */
    public abstract String kind();

    /** Policy approved the action. */
    @Value
    public static class Allow extends Decision {
        String reason;

        @Override public String kind() { return "allow"; }
    }

    /** Policy rejected the action; {@code ToolExecutor} must surface {@code PermissionDeniedException}. */
    @Value
    public static class Deny extends Decision {
        String reason;

        @Override public String kind() { return "deny"; }
    }

    /**
     * Policy requires human confirmation; engine pauses and routes to
     * {@code ToolExecutionContext.ApprovalGate.ask}. The returned
     * {@link Decision} (typically {@link Allow} or {@link Deny}) unblocks the
     * engine and resumes the tool dispatch.
     *
     * <p><b>🆕 Story #041 — SPI extraction.</b> Production engine path:
     * {@link ai.lingshu.core.impl.tool.DefaultApprovalGate#ask(Decision.AskUser)}
     * (private static inner class on {@code DefaultToolExecutionContext}) emits an
     * {@code AgentEvent.ApprovalRequired} event carrying a fresh UUID
     * {@code approvalId}, blocks the engine thread on a
     * {@link java.util.concurrent.CompletableFuture Decision CompletableFuture}, and
     * registers a continuation with the singleton
     * {@link ai.lingshu.core.impl.runtime.ApprovalRegistry} keyed by that id. When the
     * transport (e.g. demo-product
     * {@code POST /api/approvals/&#123;sessionId&#125;/&#123;approvalId&#125;})
     * invokes the continuation with the human's resolved {@link Decision}, the future
     * completes and the engine resumes.
     *
     * <p>Fail-safe paths (CLI single-shot, standalone A2A server, A2A {@code message/send}
     * without UI) return {@link Deny} immediately with a context-specific message
     * rather than blocking — see the fail-safe implementations listed on
     * {@code ToolExecutionContext.ApprovalGate}.
     */
    @Getter
    @RequiredArgsConstructor
    public static class AskUser extends Decision {
        private final String prompt;
        private final List<Option> options;

        @Override public String kind() { return "ask"; }
    }

    /** One selectable answer for a {@link AskUser} prompt. */
    @Value
    public static class Option {
        String label;
        String description;
    }
}