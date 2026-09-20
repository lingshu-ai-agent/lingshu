package ai.lingshu.core.slot;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;

/**
 * Slot 2 outer half — the single entry point that FlowEngine MUST use for every
 * {@code LlmResponse.getToolCalls()} element (see dsh §4.10.1 硬规则 2).
 *
 * <p>Five-step pipeline inside {@code dispatch} (all mandatory, in order):
 * <ol>
 *   <li>{@link Tool} / {@link Skill} registration lookup by name</li>
 *   <li>{@link ai.lingshu.core.slot.PermissionPolicy} check (allow / deny / ask-user)</li>
 *   <li>Timeout wrap using {@code ToolCallConfig.timeoutSeconds}</li>
 *   <li>Sandbox application (fs / http / process isolation per dsh §4.7)</li>
 *   <li>Tool execution + checkpoint emission</li>
 * </ol>
 *
 * <p>Bypassing any step (e.g. calling {@code tool.execute} directly) breaks the sandbox, the
 * permission gate, the cancellation chain, and the audit trail in one stroke. Spring AI's
 * {@code ChatClient.tools().call()} auto-execution is explicitly forbidden for the same reason
 * (dsh §4.10.1 反例).
 *
 * <p>The interface is decoupled from {@link Tool} internals — implementations see only the
 * {@link ToolCall} payload + {@link ToolExecutionContext}, never the {@code Tool} impl class.
 * That keeps the {@code Tool} side simply has three paths (hand-written / MCP / Spring AI annotation)
 * without imposing a common interface.
 */
public interface ToolExecutor {

    /**
     * Dispatch a single tool call.
     *
     * @param call name + JSON args from the LLM (or synthesized by CLI / Skill trigger)
     * @param ctx  sandbox context: fs / http / cancellation token / approval gate / callConfig
     * @return success / failure / cancelled (terminal, no further async)
     * @throws PermissionDeniedException   {@code PermissionPolicy.check} denied (dsh §4.7)
     * @throws ToolNotFoundException       name not registered
     * @throws ToolTimeoutException        {@code callConfig.timeoutSeconds} exceeded
     * @throws ToolCancelledException      cancellation token triggered (Ctrl+C / markDone / cascade)
     */
    ToolResult dispatch(ToolCall call, ToolExecutionContext ctx);
}