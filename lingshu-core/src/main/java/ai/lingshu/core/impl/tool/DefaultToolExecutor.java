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
 *   <li>🆕 Story #004 — translates {@link ToolException} (and any other {@link RuntimeException})
 *       into {@link ToolResult#error} so the engine loop can continue past failures
 *       (dsh §4.10.1 硬规则 2 + FR-007/FR-008).</li>
 * </ul>
 *
 * <p>The 5-step pipeline (dsh §4.6):
 * <ol>
 *   <li>{@link PermissionPolicy#check} — gated here so the same code path works whether
 *       the policy is {@code allow-all} (demo) or {@code strict} (production).</li>
 *   <li>Registry lookup by name — throws {@link ToolException.ToolNotFoundException}
 *       (now translated to {@code ToolResult.error} by the outer wrapper).</li>
 *   <li>Timeout wrap — TODO Story #011.</li>
 *   <li>Sandbox application — TODO Story #016.</li>
 *   <li>Tool execution + checkpoint — Tool.execute() called directly; checkpoint emission
 *       deferred to Story #016.</li>
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
        try {
            return dispatchInternal(call, ctx);
        } catch (ToolException e) {
            // FR-007: translate ToolException to ToolResult.error so the engine loop continues.
            // dsh §4.10.1 硬规则 2 + §15 LINGS-T02 / T04: errors must be returned, not thrown.
            LOG.debug("Tool {} failed with ToolException: {}", call.getName(), e.getMessage());
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content(e.getMessage())
                .isError(true)
                .build();
        } catch (RuntimeException e) {
            // FR-008: any unexpected RuntimeException (NPE / ISE / etc.) is also translated —
            // the engine must not be crashed by tool implementation bugs.
            LOG.warn("Tool {} threw unexpected exception: {}", call.getName(), e.getMessage(), e);
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("tool error: " + e.getMessage())
                .isError(true)
                .build();
        }
    }

    /**
     * Internal dispatch — runs the 5-step pipeline (PermissionPolicy → Registry → Execute).
     * Throws {@link ToolException} on failure paths so {@link #dispatch(ToolCall, ToolExecutionContext)}
     * can translate them.
     */
    private ToolResult dispatchInternal(ToolCall call, ToolExecutionContext ctx) {
        // Step 1: Permission policy
        Decision decision = permissionPolicy.check(call, ctx);
        if (decision instanceof Decision.Deny) {
            throw new ToolException.PermissionDeniedException(
                ((Decision.Deny) decision).getReason());
        }
        // AskUser → Story #005 will replace this stub with the full ApprovalGate flow
        if (decision instanceof Decision.AskUser) {
            throw new ToolException.PermissionDeniedException(
                "AskUser approval flow is wired in Story #005 follow-up");
        }

        // Step 2: Registry lookup
        Tool tool = registry.get(call.getName());
        if (tool == null) {
            throw new ToolException.ToolNotFoundException(call.getName());
        }

        // Steps 3-5: TODO Story #011 / Story #016 (timeout / sandbox / checkpoint).
        // Direct execution for now — Story #004's job is parallel orchestration, not
        // adding new pipeline steps.
        return tool.execute(call, ctx);
    }
}