package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.permission.PermissionErrorCodes;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.ToolExecutionContext;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Story #030 — ask-list-gated {@link PermissionPolicy}. Sibling of
 * {@link StrictPermissionPolicy} that introduces a third input list — the
 * {@code askList} — so the engine can drive an {@link Decision.AskUser}
 * pause-and-route workflow on specific tools rather than a hard deny.
 *
 * <p>Wired into {@code ToolExecutor.dispatch()} as §4.10.1 硬规则 2 第 1 步
 * (sibling of {@link StrictPermissionPolicy}; the two are independent
 * Providers selected by {@code AgentConfig.permissionPolicy} name).
 *
 * <p>Four decision paths (in order, short-circuit on first match):
 * <ol>
 *   <li><b>deny-list hit</b> → {@link Decision.Deny}
 *       with reason {@code "[LINGS-P01] Tool '<name>' matches deny pattern '<pattern>'"}</li>
 *   <li><b>ask-list hit</b> → {@link Decision.AskUser}
 *       with prompt {@code "Permission required to call '<name>' (matches pattern '<pattern>')"}.
 *       The engine (LinearTurnEngine) will emit {@code AgentEvent.ApprovalRequired}
 *       and block on {@code ctx.approval().ask(ask)} until the human answers or
 *       {@code AgentConfig.approvalTimeoutSeconds} elapses.</li>
 *   <li><b>allow-list empty OR allow-list hit</b> → {@link Decision.Allow}
 *       with reason reflecting default-allow vs explicit-allow-match</li>
 *   <li><b>allow-list non-empty AND no pattern matches</b> → {@link Decision.Deny}
 *       with reason {@code "[LINGS-P01] Tool '<name>' not in allow-list (category=<cat>)"}</li>
 * </ol>
 *
 * <p><b>Pattern matching reuse (Story #031):</b> identical 3-form grammar
 * ({@code "*"} / {@code "<category>:*"} / {@code "<exact-name>"}) via
 * {@link PermissionPatterns#matches} so demo yml can mix-and-match
 * exact tool names ({@code "bash"}) and category prefixes ({@code "mcp:*"}).
 *
 * <p><b>Back-compat with Story #029 / #031:</b> the yml field
 * {@code agent.tools.ask-list} is purely additive. {@code StrictPermissionPolicy}
 * ignores it. Existing Story #029 allow-list / deny-list fixtures pass through
 * unchanged because {@code AskUserPermissionPolicy.check()} consults the same
 * lists first.
 *
 * <p><b>Not {@code @Component}:</b> instances are produced on demand by
 * {@link AskUserPermissionPolicyProvider#create(AgentConfig)} with the
 * {@code ToolsConfig} + {@code nameToCategory} bound at call time. Same
 * rationale as {@link StrictPermissionPolicy} — Spring would otherwise
 * force a no-arg ctor that we deliberately don't provide.
 */
@Getter
@ToString
public class AskUserPermissionPolicy implements PermissionPolicy {

    /** Immutable per-turn config (dsh §4.12.2). Carries allow-list / deny-list / ask-list. */
    private final AgentConfig.ToolsConfig tools;

    /**
     * Precomputed {@code toolName → sourceCategory} lookup, populated by
     * {@link AskUserPermissionPolicyProvider} from {@code ToolRegistry.findAll()}.
     * A tool not in the map falls back to {@code "local"}.
     */
    private final Map<String, String> nameToCategory;

    /**
     * Story #030 1-arg constructor preserved for test fixtures (e.g.,
     * {@code AskUserPermissionPolicyTest}) that construct the policy directly
     * without a provider. Delegates to the 2-arg constructor with an empty
     * category map — strict-equals pattern path still works.
     */
    public AskUserPermissionPolicy(AgentConfig.ToolsConfig tools) {
        this(tools, Collections.<String, String>emptyMap());
    }

    /**
     * Story #030 primary constructor used by {@link AskUserPermissionPolicyProvider}.
     */
    public AskUserPermissionPolicy(AgentConfig.ToolsConfig tools, Map<String, String> nameToCategory) {
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        this.tools = tools;
        this.nameToCategory = nameToCategory == null
            ? Collections.<String, String>emptyMap()
            : nameToCategory;
    }

    @Override
    public Decision check(ToolCall call, ToolExecutionContext ctx) {
        String toolName = call.getName();
        Map<String, String> map = nameToCategory == null
            ? Collections.<String, String>emptyMap()
            : nameToCategory;
        String toolCategory = map.get(toolName);
        if (toolCategory == null) {
            toolCategory = "local";
        }
        List<String> allowList = tools.getAllowList();
        List<String> denyList = tools.getDenyList();
        List<String> askList = tools.getAskList();

        // Path 1: deny-list — first match wins. Pure defensive check.
        if (denyList != null && !denyList.isEmpty()) {
            for (String pattern : denyList) {
                if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
                    return new Decision.Deny(
                        "[" + PermissionErrorCodes.LINGS_P01 + "] Tool '" + toolName
                            + "' matches deny pattern '" + pattern + "'");
                }
            }
        }

        // Path 2: ask-list — first match wins. NEW in Story #030.
        // Engine pauses turn; LinearTurnEngine.dispatchWithPolicy() emits
        // ApprovalRequired + blocks on ctx.approval().ask() until the human answers.
        if (askList != null && !askList.isEmpty()) {
            for (String pattern : askList) {
                if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
                    Decision.AskUser ask = new Decision.AskUser(
                        "Permission required to call '" + toolName
                            + "' (matches pattern '" + pattern + "')",
                        Collections.<Decision.Option>emptyList());
                    return ask;
                }
            }
        }

        // Path 3: allow-list empty → default allow.
        if (allowList == null || allowList.isEmpty()) {
            return new Decision.Allow("default policy: allow (no allow-list)");
        }

        // Path 4: allow-list non-empty → first pattern match wins.
        for (String pattern : allowList) {
            if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
                return new Decision.Allow(
                    "ask policy: allow (matches pattern '" + pattern + "')");
            }
        }

        // Path 5: no allow-list pattern matched → Deny with category context.
        return new Decision.Deny(
            "[" + PermissionErrorCodes.LINGS_P01 + "] Tool '" + toolName
                + "' not in allow-list (category=" + toolCategory + ")");
    }
}
