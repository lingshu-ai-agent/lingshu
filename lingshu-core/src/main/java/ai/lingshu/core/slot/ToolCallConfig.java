package ai.lingshu.core.slot;

import lombok.Value;

/**
 * Per-call resource limits applied by {@link ToolExecutor} before delegating to a {@link Tool}.
 *
 * <p>Sourced from {@code AgentConfig.toolTimeoutSeconds} + per-call overrides.
 * Zero in any field means "no limit" (intentional, not default — defaults live in
 * {@code AgentConfig}, not here).
 */
@Value
public class ToolCallConfig {
    /** Single-call timeout (seconds). {@code 0} = no timeout. */
    int timeoutSeconds;
    /** Token budget for LLM-emitting tools (read tools, summarizers). {@code 0} = no budget. */
    int maxTokens;
    /** Cost ceiling (USD × 1e6). {@code 0} = no ceiling. */
    int maxCostMicros;
}