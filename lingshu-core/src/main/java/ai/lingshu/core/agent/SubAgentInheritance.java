package ai.lingshu.core.agent;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentConfig.Identity;
import ai.lingshu.core.runtime.AgentConfig.Instructions;
import ai.lingshu.core.runtime.AgentConfig.Memory;

/**
 * Story #023 — Field-level config inheritance helper for the {@code Task} tool's
 * sub-Agent {@link AgentConfig} composition (dsh v1.5.40 §6.6.1 L5131-5146).
 *
 * <p><b>Role.</b> When {@link DelegateTool#execute(ToolCall, ToolExecutionContext)}
 * invokes a sub-Agent, the sub-Agent's {@link AgentConfig} is computed by merging
 * three sources, in priority order:
 * <ol>
 *   <li><b>Sub-agent explicit overrides</b> — the {@link AgentConfig} skeleton
 *       built from {@code AgentConfig.TypeConfig} (set by
 *       {@link DelegateTool#loadConfigs}). Only fields the user explicitly
 *       populated are non-null; everything else is null/0.</li>
 *   <li><b>Parent config</b> — the {@link AgentConfig} of the parent Agent that
 *       dispatched the {@code Task} tool call. Captured at construction time.</li>
 *   <li><b>JDK 8 defaults</b> — when both sources are missing for the
 *       business-config trio ({@link Identity} / {@link Instructions} /
 *       {@link Memory}), the standard empty/default factories apply.</li>
 * </ol>
 *
 * <p><b>Why a pure static helper (no Spring bean, no state):</b> the inheritance
 * is a deterministic field-by-field merge with no I/O, no listener firing, no
 * side effects. {@link DelegateTool} builds three pre-merged child configs at
 * construction time and stores them in
 * {@code Map<SubAgentType, AgentConfig> typeConfigs}; re-running the merge on
 * every {@code execute} call would be O(N) per dispatch.
 *
 * <p><b>Why manual {@code new AgentConfig(...)} (no {@code toBuilder()}):</b>
 * {@link AgentConfig} is {@link lombok.Value @Value}-only; Lombok
 * {@code @Builder(toBuilder = true)} is intentionally <b>not</b> opted into.
 * Hand-assembling the 24-arg constructor is verbose but guarantees compile-time
 * safety on every field (Lombok regenerates the constructor signature the moment
 * a field is added; the helper either still compiles or fails loudly).
 *
 * <p><b>Identity / Instructions / Memory merge semantics</b> (per tasks.md T03):
 * <ul>
 *   <li><b>Identity</b> — when child supplies its own, it is used verbatim.
 *       Otherwise the parent's identity is reused with the parent's {@code name}
 *       appended {@code " (Sub-agent: <type.configKey>)"} to surface sub-agent
 *       provenance in logs / LLM-visible prompts. When the parent itself has a
 *       {@code null} identity, {@link Identity#defaults()} is used and no suffix
 *       is appended (the default name has no override surface).</li>
 *   <li><b>Instructions</b> — full replace if child supplies its own;
 *       fall back to parent's verbatim; fall back to {@link Instructions#empty()}
 *       when parent itself is null.</li>
 *   <li><b>Memory</b> — full replace if child supplies its own; fall back to
 *       parent's verbatim; fall back to {@link Memory#defaults()} when parent
 *       itself is null.</li>
 * </ul>
 *
 * <p><b>{@code Delegate} field in the result is forced to {@code null}.</b>
 * Sub-agents do not have their own sub-agents in {@code Story #023}
 * (OQ-Future {@code OQ-#023-C}: sub-子-agent recursion). Forcing {@code null}
 * here prevents accidental delegation chains through accidentally-inherited
 * parent {@code Delegate} configs.
 *
 * <p><b>JDK 8 compatibility</b> — no {@code record} / sealed / {@code var} /
 * {@code Map.of} / {@code List.of}. Plain {@code final class} +
 * {@code private} ctor {@code throws AssertionError}.
 *
 * @see SubAgentType
 * @see DelegateTool
 * @since 1.0.0
 */
public final class SubAgentInheritance {

    private SubAgentInheritance() {
        // utility class — instantiation is a programming error
        throw new AssertionError("SubAgentInheritance must not be instantiated");
    }

    /**
     * Merge a parent {@link AgentConfig} with a child-skeleton
     * {@link AgentConfig} (built from {@link AgentConfig.TypeConfig}) and produce
     * a fully-constructed, {@code AgentFactory.validate}-ready child
     * {@link AgentConfig}.
     *
     * <p><b>Per-field rules</b>:
     * <ul>
     *   <li><b>Reference fields</b> ({@code llm} / {@code prompt} / {@code sandbox} /
     *       {@code mcp} / {@code skills} / {@code tenants} / {@code a2a} /
     *       {@code compactorConfig} / {@code tools}) — child's non-null value
     *       wins; otherwise parent.</li>
     *   <li><b>String fields</b> ({@code flowEngine} / {@code toolExecutor} /
     *       {@code compactor} / {@code sessionStore} / {@code a2aTransport}) —
     *       child's non-null non-empty value wins; otherwise parent.</li>
     *   <li><b>int fields</b> ({@code toolParallelism} / {@code toolTimeoutSeconds}
     *       / {@code approvalTimeoutSeconds} / {@code turnTimeoutSeconds} /
     *       {@code llmTimeoutSeconds} / {@code reactMaxSteps}) — child's
     *       non-zero value wins (0 in any of these is treated as "unset" — they
     *       are merely capacity knobs); otherwise parent. (Note: real value
     *       {@code 0} for e.g. {@code turnTimeoutSeconds} means
     *       "no timeout" per the {@link AgentConfig} field docs — but the child
     *       skeleton never sets these to 0 by intent; the typical skeleton
     *       carries all-zero ints meaning "inherit from parent".)</li>
     *   <li><b>Business-config trio</b> ({@code identity} / {@code instructions} /
     *       {@code memory}) — see class Javadoc "merge semantics" bullet list.</li>
     *   <li><b>{@code delegate}</b> — always {@code null} (no sub-sub-agents).</li>
     * </ul>
     *
     * @param parent the parent Agent's config — never {@code null} (caller
     *               passes {@code AgentConfigRegistry.current()} at
     *               construction time)
     * @param child  the child skeleton built from {@code TypeConfig} — never
     *               {@code null} (fields the user did not populate are
     *               already {@code null} / 0 by Lombok default)
     * @param type   the sub-Agent type — never {@code null}; used only for the
     *               Identity name suffix
     * @return a fully-populated {@link AgentConfig} safe to pass to
     *         {@code AgentFactory.create(...)}
     * @throws IllegalArgumentException if any of {@code parent} / {@code child} /
     *                                  {@code type} is {@code null}
     */
    public static AgentConfig inheritFromParent(AgentConfig parent,
                                                AgentConfig child,
                                                SubAgentType type) {
        if (parent == null) {
            throw new IllegalArgumentException(
                "parent AgentConfig must not be null");
        }
        if (child == null) {
            throw new IllegalArgumentException(
                "child AgentConfig skeleton must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException(
                "SubAgentType must not be null");
        }

        return new AgentConfig(
            /* flowEngine      */ optString(child.getFlowEngine(), parent.getFlowEngine()),
            /* llm             */ optRef(child.getLlm(), parent.getLlm()),
            /* prompt          */ optRef(child.getPrompt(), parent.getPrompt()),
            /* toolExecutor    */ optString(child.getToolExecutor(), parent.getToolExecutor()),
            /* sandbox         */ optRef(child.getSandbox(), parent.getSandbox()),
            /* compactor       */ optString(child.getCompactor(), parent.getCompactor()),
            /* sessionStore    */ optString(child.getSessionStore(), parent.getSessionStore()),
            /* delegate        */ null, // sub-Agent has no sub-agents (Story #023 OQ-#023-C deferred)
            /* mcp             */ optRef(child.getMcp(), parent.getMcp()),
            /* skills          */ optRef(child.getSkills(), parent.getSkills()),
            /* toolParallelism */ optInt(child.getToolParallelism(), parent.getToolParallelism()),
            /* toolTimeoutSec  */ optInt(child.getToolTimeoutSeconds(), parent.getToolTimeoutSeconds()),
            /* approvalTimeSec */ optInt(child.getApprovalTimeoutSeconds(), parent.getApprovalTimeoutSeconds()),
            /* turnTimeoutSec  */ optInt(child.getTurnTimeoutSeconds(), parent.getTurnTimeoutSeconds()),
            /* llmTimeoutSec   */ optInt(child.getLlmTimeoutSeconds(), parent.getLlmTimeoutSeconds()),
            /* reactMaxSteps   */ optInt(child.getReactMaxSteps(), parent.getReactMaxSteps()),
            /* identity        */ mergeIdentity(parent.getIdentity(), child.getIdentity(), type),
            /* instructions    */ mergeInstructions(parent.getInstructions(), child.getInstructions()),
            /* memory          */ mergeMemory(parent.getMemory(), child.getMemory()),
            /* a2aTransport    */ optString(child.getA2aTransport(), parent.getA2aTransport()),
            /* tenants         */ optRef(child.getTenants(), parent.getTenants()),
            /* a2a             */ optRef(child.getA2a(), parent.getA2a()),
            /* compactorConfig */ optRef(child.getCompactorConfig(), parent.getCompactorConfig()),
            /* tools           */ optRef(child.getTools(), parent.getTools())
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Field-level helpers — package-private for unit tests; never null on
    // either side (callers guarantee via the validate(AgentConfig) in
    // DelegateTool.loadConfigs).
    // ─────────────────────────────────────────────────────────────────────

    /** Reference field — child wins when non-null. */
    static <T> T optRef(T child, T parent) {
        return child != null ? child : parent;
    }

    /** String field — child wins when non-null and non-blank. */
    static String optString(String child, String parent) {
        return (child != null && !child.isEmpty()) ? child : parent;
    }

    /**
     * int field — child wins when non-zero. {@code 0} is treated as
     * "unset by the skeleton"; the parent always carries a meaningful value
     * (the {@code AgentFactory.validate} path rejects {@code reactMaxSteps <= 0}
     * and {@code llmTimeoutSeconds <= 0}, so a real parent must be > 0 here).
     */
    static int optInt(int child, int parent) {
        return child != 0 ? child : parent;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Business-config trio merge — see class Javadoc.
    // ─────────────────────────────────────────────────────────────────────

    static Identity mergeIdentity(Identity parent, Identity child, SubAgentType type) {
        if (child != null) {
            // Child overrides parent's identity outright — no suffix appended.
            return child;
        }
        if (parent != null) {
            // Surface sub-agent provenance by appending the type key to the parent's name.
            String baseName = parent.getName() != null ? parent.getName() : "lingShu-agent";
            String suffix = " (Sub-agent: " + type.configKey() + ")";
            return new Identity(
                baseName + suffix,
                parent.getRole(),
                parent.getLanguage(),
                parent.getTraits(),
                parent.getTone(),
                parent.getAvatar()
            );
        }
        // Both null — fall back to defaults; no suffix applied (defaults carry a generic name).
        return Identity.defaults();
    }

    static Instructions mergeInstructions(Instructions parent, Instructions child) {
        if (child != null) {
            return child;
        }
        if (parent != null) {
            return parent;
        }
        return Instructions.empty();
    }

    static Memory mergeMemory(Memory parent, Memory child) {
        if (child != null) {
            return child;
        }
        if (parent != null) {
            return parent;
        }
        return Memory.defaults();
    }
}
