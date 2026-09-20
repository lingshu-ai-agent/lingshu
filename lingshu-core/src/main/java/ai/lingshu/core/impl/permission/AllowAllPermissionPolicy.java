package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.ToolExecutionContext;

/**
 * Story #001 default {@link PermissionPolicy} — allow-all.
 *
 * <p>Story #008 (react-max-steps) + Story #014 (session-store) replace this with a
 * {@code StrictPermissionPolicy} that consults a configurable whitelist.
 */
public class AllowAllPermissionPolicy implements PermissionPolicy {

    @Override
    public Decision check(ToolCall call, ToolExecutionContext ctx) {
        return new Decision.Allow("default policy: allow all");
    }
}