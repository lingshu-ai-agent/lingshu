package ai.lingshu.core.message;

/**
 * Reason a turn stopped. Drives downstream flow in {@code FlowEngine} (compact / cancel / error).
 *
 * @see ai.lingshu.core.runtime.FlowEngine
 */
public enum StopReason {
    /** Model returned end_turn / stop — natural finish. */
    END_TURN,
    /** Model emitted tool calls — engine must dispatch then loop. */
    TOOL_USE,
    /** Hit the configured max_tokens limit before finishing. */
    MAX_TOKENS,
    /** Compactor truncated the history mid-turn. */
    COMPACTED,
    /** User pressed Ctrl+C / engine.markDone() / timeout cascaded. */
    CANCELLED,
    /** Unhandled exception surfaced to sink. */
    ERROR
}