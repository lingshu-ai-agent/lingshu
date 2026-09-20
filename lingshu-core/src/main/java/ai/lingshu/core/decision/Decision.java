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

    /** Policy requires human confirmation; engine pauses and routes to {@code ApprovalGate.ask}. */
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