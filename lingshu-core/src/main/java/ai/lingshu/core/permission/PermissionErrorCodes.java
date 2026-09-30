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
 * <p>Future P02+ (e.g. {@code LINGS-P02 PERMISSION_ASK_USER_TIMEOUT},
 * {@code LINGS-P03 PERMISSION_CONFIG_INVALID}) are reserved for later Stories.
 */
public final class PermissionErrorCodes {

    /** {@code PERMISSION_DENIED} — tool call rejected by allow-list or deny-list. */
    public static final String LINGS_P01 = "LINGS-P01";

    private PermissionErrorCodes() {}
}