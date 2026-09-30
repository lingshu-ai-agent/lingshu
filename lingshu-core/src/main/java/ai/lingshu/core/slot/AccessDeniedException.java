package ai.lingshu.core.slot;

/**
 * Sandbox-layer denial — distinct from {@link ToolException.PermissionDeniedException}
 * which is policy-layer (§4.7 PermissionPolicy.check() returning {@link
 * ai.lingshu.core.decision.Decision.Deny}).
 *
 * <p>🆕 Story #028 — Sandbox runtime 真实现(§6.3 ChrootRuntimeSandbox). 4 抛点:
 * <ol>
 *   <li>{@code ChrootedFileSystem.getPath} — path escapes the configured working
 *       directory prefix</li>
 *   <li>{@code WhitelistedHttpClient.get/post/getStream} — host not in domain whitelist</li>
 *   <li>{@code ChrootRuntimeSandbox.process().run(...)} — command not in cmdWhitelist</li>
 *   <li>(reserved for §4.7 PermissionPolicy.check() AskUser deny path)</li>
 * </ol>
 *
 * <p>Each throw site uses the simple constructor {@code new AccessDeniedException(reason)};
 * the constructor automatically prefixes the {@code [LINGS-S01]} ErrorCode marker so that
 * {@code getMessage()} starts with the canonical code, matching the {@code getMessage()}
 * pattern of {@code LingsLlmProviderException} (Story #027a) and
 * {@code LingsDelegateException} (Story #023).
 *
 * <p>This is a {@link RuntimeException} (unchecked) — sandbox-layer denials do not pollute
 * any checked-exception signatures in the {@code ToolExecutor} 5-step pipeline (§4.10.1
 * hard rule 2). The pipeline's {@code SandboxApply} step catches {@code AccessDeniedException}
 * and translates it to a {@link ToolResult#error} entry; the rest of the pipeline is unaware.
 *
 * @see ToolException.PermissionDeniedException for the policy-layer denial counterpart
 */
public final class AccessDeniedException extends RuntimeException {

    /** Sandbox-domain ErrorCode — dsh §15.5 域字母 S 段 1 号 */
    public static final String ERROR_CODE = "LINGS-S01";

    private static final long serialVersionUID = 1L;

    /**
     * Construct an {@code AccessDeniedException} carrying the {@code [LINGS-S01]} prefix.
     *
     * <p>Callers should pass the human-readable reason (no ErrorCode prefix). The
     * constructor auto-prefixes so {@code getMessage()} returns {@code "[LINGS-S01] <reason>"}.
     *
     * @param reason human-readable description (e.g. {@code "Path escapes working dir: /etc/passwd"})
     */
    public AccessDeniedException(String reason) {
        super("[" + ERROR_CODE + "] " + (reason == null ? "" : reason));
    }

    /**
     * Construct an {@code AccessDeniedException} with a wrapped cause (e.g. an
     * {@link java.io.IOException} from the underlying {@code HttpURLConnection} that was
     * masked by the whitelist denial).
     */
    public AccessDeniedException(String reason, Throwable cause) {
        super("[" + ERROR_CODE + "] " + (reason == null ? "" : reason), cause);
    }
}