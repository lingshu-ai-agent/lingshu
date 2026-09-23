package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.runtime.AgentConfig;
import lombok.Value;

/**
 * Immutable three-field config for {@link TruncatingCompactor} (Story #018).
 *
 * <p>Built from {@link AgentConfig.CompactorConfig} by
 * {@link TruncatingCompactorProvider#create(AgentConfig)} — the Provider is the
 * <em>only</em> place where {@link AgentConfig} is translated to {@link CompactorProps},
 * keeping the Slot core ({@code Compactor} interface / {@code CompactorProvider} SPI)
 * free of any config type (dsh §5.5 — Adapter does not copy Slot, only translates Slot).
 *
 * <p>Field semantics — see {@link AgentConfig.CompactorConfig} for full description:
 * <ul>
 *   <li>{@code maxPromptTokens} — token estimate threshold above which compact runs</li>
 *   <li>{@code maxToolResultBytes} — byte threshold for in-place ToolResult truncation</li>
 *   <li>{@code keepRecentTurns} — sliding-window retention of assistant messages</li>
 * </ul>
 *
 * @see TruncatingCompactor
 * @see TruncatingCompactorProvider
 * @see AgentConfig.CompactorConfig
 */
@Value
public class CompactorProps {

    int maxPromptTokens;
    int maxToolResultBytes;
    int keepRecentTurns;

    /**
     * Translate {@link AgentConfig.CompactorConfig} → {@code CompactorProps}.
     *
     * <p>When {@code cfg.getCompactorConfig()} is {@code null} (legacy callers / older
     * yml without the nested {@code agent.compactor.*} block), falls back to
     * {@link AgentConfig.CompactorConfig#defaults()} so the compactor always has a
     * usable config. This is the "backwards-compat with pre-Story-#018 callers"
     * guarantee surfaced as AC-018-8 in the spec.
     *
     * @param cfg the immutable per-turn AgentConfig; never null
     * @return CompactorProps matching the effective config
     */
    public static CompactorProps from(AgentConfig cfg) {
        AgentConfig.CompactorConfig cc = cfg.getCompactorConfig();
        if (cc == null) {
            cc = AgentConfig.CompactorConfig.defaults();
        }
        return new CompactorProps(
            cc.getMaxPromptTokens(),
            cc.getMaxToolResultBytes(),
            cc.getKeepRecentTurns());
    }
}