package ai.lingshu.core.slot;

import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.spi.ContractVersionRef;

/**
 * Slot 9 — Agent-to-Agent transport (dsh §5.6). Bridges to a remote agent process via
 * one of three concrete implementations: HTTP/JSON-RPC (default), gRPC, or in-process.
 *
 * <p>The interface is intentionally narrow — five methods cover fetch-card / submit /
 * get / cancel / subscribe. Provider differences live in concrete classes, not here.
 *
 * <p>Implementation contract (dsh §5.6 + §5.6.3.1):
 * <ul>
 *   <li>{@link #fetchCard} caches via {@code AgentCardCache}; failures must NOT throw —
 *       transport is best-effort for discovery</li>
 *   <li>{@link #submit} blocks the caller until the remote agent returns; long-running
 *       agents should call back via {@link #subscribe}</li>
 *   <li>{@link #get} is the sync get-path for an in-flight task</li>
 *   <li>{@link #cancel} is best-effort; remote may have already completed</li>
 *   <li>{@link #subscribe} streams incremental events back; default impl polls every 1s</li>
 * </ul>
 */
public interface A2aTransport {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Look up the {@code AgentCard} for a remote agent by name. Returned as a generic
     * JSON map to keep the core module free of the A2A schema types (lives in
     * {@code lingshu-a2a-server}).
     *
     * @param agentName the logical identifier (e.g. {@code "remote-coding-agent"})
     * @return card data, or {@code null} if not reachable
     */
    java.util.Map<String, Object> fetchCard(String agentName);

    /**
     * Submit a task to the remote agent and wait for completion.
     *
     * @param agentName target agent
     * @param skill     which skill to invoke
     * @param inputJson JSON args matching the skill's input schema
     * @return the remote agent's tool-style result
     */
    ToolResult submit(String agentName, String skill, String inputJson);

    /**
     * Get the current state / result of an in-flight task.
     *
     * @param taskId id returned from a prior {@code submit} that did not block to completion
     */
    ToolResult get(String taskId);

    /**
     * Best-effort cancellation; returns {@code true} if the remote acknowledged.
     */
    boolean cancel(String taskId);

    /**
     * Stream incremental events from a running task. The default implementation polls every
     * second; gRPC variant uses streaming RPCs for lower latency.
     *
     * @param taskId the in-flight task
     * @param onEvent consumer invoked for each remote event map (analogous to {@code ToolSink.emitPartial})
     */
    void subscribe(String taskId, java.util.function.Consumer<java.util.Map<String, Object>> onEvent);
}