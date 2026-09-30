package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.permission.PermissionErrorCodes;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.ToolExecutionContext;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Story #029 — strict tool-level {@link PermissionPolicy} backed by
 * {@link AgentConfig.ToolsConfig#allowList} (allow-list) and
 * {@link AgentConfig.ToolsConfig#denyList} (deny-list).
 *
 * <p>Wired into {@code ToolExecutor.dispatch()} as §4.10.1 硬规则 2 第 1 步
 * (previously a stub that always returned {@link Decision.Allow}). Now consults
 * the configured lists deterministically.
 *
 * <p>Three decision paths (in order, short-circuit on first match):
 * <ol>
 *   <li><b>allow-list not empty AND tool not in it</b> → {@link Decision.Deny}
 *       with reason {@code "[LINGS-P01] Tool 'foo' not in allow-list"}</li>
 *   <li><b>deny-list not empty AND tool in it</b> → {@link Decision.Deny}
 *       with reason {@code "[LINGS-P01] Tool 'foo' in deny-list"}</li>
 *   <li><b>default</b> → {@link Decision.Allow}
 *       (covers the "both lists empty" zero-config case)</li>
 * </ol>
 *
 * <p>Tool name matching is <b>strict equals</b> via {@link List#contains} — no
 * case folding, no whitespace trim, no wildcard. The framework trusts the
 * configured list to be exactly the tool names registered in
 * {@code ToolRegistry}. This matches the precedent in Story #028's sandbox
 * domain whitelist.
 *
 * <p>ErrorCode prefix {@code "[LINGS-P01]"} is embedded in
 * {@code Decision.Deny.reason} (see {@link PermissionErrorCodes}) so the
 * downstream {@code ToolExecutor} can surface the failure as a
 * {@code ToolResult.error} without ever throwing — aligns with
 * §4.10.1 硬规则 2 (ToolExecutor.execute() never throws).
 */
@Component
@Value
public class StrictPermissionPolicy implements PermissionPolicy {

    /** Immutable per-turn config (dsh §4.12.2). Carries allow-list + deny-list. */
    AgentConfig.ToolsConfig tools;

    @Override
    public Decision check(ToolCall call, ToolExecutionContext ctx) {
        String toolName = call.getName();
        List<String> allowList = tools.getAllowList();
        List<String> denyList = tools.getDenyList();

        // Path 1: allow-list is configured and does NOT contain this tool.
        if (!allowList.isEmpty() && !allowList.contains(toolName)) {
            return new Decision.Deny(
                "[" + PermissionErrorCodes.LINGS_P01 + "] Tool '" + toolName + "' not in allow-list");
        }

        // Path 2: deny-list is configured and DOES contain this tool.
        if (!denyList.isEmpty() && denyList.contains(toolName)) {
            return new Decision.Deny(
                "[" + PermissionErrorCodes.LINGS_P01 + "] Tool '" + toolName + "' in deny-list");
        }

        // Path 3: default allow (covers the zero-config "both lists empty" case).
        return new Decision.Allow("strict policy: allow");
    }
}