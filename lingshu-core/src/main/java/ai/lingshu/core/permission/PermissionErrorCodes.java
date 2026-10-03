package ai.lingshu.core.permission;

/**
 * ErrorCode constants for the Permission domain (Slot 4 — {@code PermissionPolicy}).
 *
 * <p>New domain letter <b>P</b> introduced by Story #029 — dsh §15.4 ErrorCode 域字母表
 * expands from 9 ({@code C/S/L/T/X/R/A/M/Z}) to 10 (adds {@code P = Permission}).
 * Aligns with the precedent set by {@code #028 LINGS-S01} (Sandbox) and
 * {@code #023 LINGS-D01} (Delegate): the new domain letter's first ErrorCode
 * is reserved for the deny outcome.
 *
 * <p>Embedding pattern (Story #028 + #022 + #023 + #029 all converge):
 * {@code StrictPermissionPolicy.check()} returns {@code new Decision.Deny("[LINGS-P01] Tool '" + toolName + "' not in allow-list")}
 * so {@code ToolExecutor.dispatch()} — which never throws (§4.10.1 硬规则 2) —
 * surfaces the failure as a {@code ToolResult.error} containing the code.
 * AssertJ {@code assertThat(decision.getReason()).contains("LINGS-P01")} works
 * out of the box.
 *
 * <p><b>🆕 Story #030 — adds {@link #LINGS_P02} {@code PERMISSION_APPROVAL_TIMEOUT}:</b>
 * emitted when {@link ai.lingshu.core.slot.ToolExecutionContext.ApprovalGate#ask}
 * blocks past {@code AgentConfig.approvalTimeoutSeconds} (default {@code 0} = wait
 * indefinitely to match Claude Code overnight approval behavior; {@code >0} =
 * timeout triggers {@code Decision.Deny} with this code embedded). The slot-4
 * AskUser outcome path is now real wiring (replaces 3 dead stubs in
 * {@code DefaultToolExecutionContext} / {@code DefaultToolExecutor} /
 * {@code LinearTurnEngine.dispatchWithPolicy}).
 *
 * <p><b>🆕 Story #041 — SPI extraction.</b> The production engine implementation is
 * now {@link ai.lingshu.core.impl.tool.DefaultToolExecutionContext.DefaultApprovalGate}
 * (a {@code private static final} inner class on
 * {@code DefaultToolExecutionContext}). It still emits {@code LINGS-P02} on
 * timeout / cancel / no-sink paths, and {@code LINGS-P02} is also embedded in
 * the fail-safe {@code Deny} returned by CLI / A2A-server / demo-product-a2a-server
 * (those paths block for &lt; 1 second and never hit the timeout branch in practice).
 *
 * <p>Future P03+ (e.g. {@code LINGS-P03 PERMISSION_CONFIG_INVALID}) are reserved
 * for later Stories.
 */
public final class PermissionErrorCodes {

    /** {@code PERMISSION_DENIED} — tool call rejected by allow-list or deny-list. */
    public static final String LINGS_P01 = "LINGS-P01";

    /**
     * 🆕 Story #030 — {@code PERMISSION_APPROVAL_TIMEOUT}: the {@link
     * ai.lingshu.core.slot.ToolExecutionContext.ApprovalGate#ask} blocking call
     * exceeded {@code AgentConfig.approvalTimeoutSeconds}; the policy fell back to
     * {@code Decision.Deny} with reason
     * {@code "[LINGS-P02] Permission approval timed out after Ns (default policy: ask user)"}.
     * 🆕 Story #041 — also embedded in the cancel / no-sink / fail-safe
     * {@code Deny} returned by {@code DefaultApprovalGate} and the CLI / A2A / demo
     * stubs (message variants differ — see each implementation for the exact text).
     */
    public static final String LINGS_P02 = "LINGS-P02";

    private PermissionErrorCodes() {}
}