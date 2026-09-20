package ai.lingshu.core.impl.tool;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolException;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story #001 default {@link ToolExecutor} — registry-backed dispatch with the 5-step pipeline.
 *
 * <p>Purpose:
 * <ul>
 *   <li>Story #001 demo path has no tools registered, so every call throws {@link ToolException.ToolNotFoundException}.</li>
 *   <li>Story #002+ add the first registered {@link Tool}s and exercises the full pipeline.</li>
 * </ul>
 *
 * <p>The 5-step pipeline (dsh §4.6):
 * <ol>
 *   <li>{@link PermissionPolicy#check} — gated here so the same code path works whether
 *       the policy is {@code allow-all} (demo) or {@code strict} (production).</li>
 *   <li>Registry lookup by name — throws {@link ToolException.ToolNotFoundException}.</li>
 *   <li>Timeout wrap — TODO Story #001 follow-up.</li>
 *   <li>Sandbox application — TODO Story #001 follow-up.</li>
 *   <li>Tool execution + checkpoint — TODO Story #001 follow-up.</li>
 * </ol>
 */
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final Map<String, Tool> registry = new ConcurrentHashMap<>();
    private final PermissionPolicy permissionPolicy;

    public DefaultToolExecutor(PermissionPolicy permissionPolicy) {
        this.permissionPolicy = permissionPolicy;
    }

    /** Register a tool; typically called by Spring's auto-discovery of {@code @Component Tool} beans. */
    public void register(Tool tool) {
        Tool prior = registry.putIfAbsent(tool.name(), tool);
        if (prior != null && prior != tool) {
            LOG.warn("Duplicate tool registration: name={} prior={} new={}",
                tool.name(), prior.getClass().getSimpleName(), tool.getClass().getSimpleName());
        }
    }

    @Override
    public ToolResult dispatch(ToolCall call, ToolExecutionContext ctx) {
        // Step 1: Permission policy
        Decision decision = permissionPolicy.check(call, ctx);
        if (decision instanceof Decision.Deny) {
            throw new ToolException.PermissionDeniedException(
                ((Decision.Deny) decision).getReason());
        }
        // AskUser → for Story #001 we shortcut to deny (no approval gate wired yet)
        if (decision instanceof Decision.AskUser) {
            throw new ToolException.PermissionDeniedException(
                "AskUser approval flow is wired in Story #005 follow-up");
        }

        // Step 2: Registry lookup
        Tool tool = registry.get(call.getName());
        if (tool == null) {
            throw new ToolException.ToolNotFoundException(call.getName());
        }

        // Steps 3-5: TODO Story #001 follow-up (timeout / sandbox / checkpoint).
        // Direct execution for now — Story #004 wraps the 5-step pipeline.
        LOG.warn("Direct tool.execute() — Story #004 wires timeout/sandbox/checkpoint");
        return tool.execute(call, ctx);
    }
}