package ai.lingshu.core.runtime;

/**
 * MCP server transport enumeration (Story #021a, dsh §6.5 (2.1) L4553-4871).
 *
 * <p><b>What</b> — Three transport flavors supported by the MCP integration:
 * {@link #STDIO} (spawns a subprocess, talks JSON over stdin/stdout),
 * {@link #SSE} (long-lived HTTP GET for server-pushed events; Story #021c),
 * and {@link #STREAMABLE_HTTP} (stateless POST; Story #021c).
 *
 * <p><b>Why here, not in the {@code mcp} package</b> — Avoiding a circular
 * dependency (Story #021a plan §3.1): {@link AgentConfig.ServerConfig} lives
 * in {@code runtime} and must reference this enum, and the upcoming
 * {@code McpTransport.connect(List<McpServerConfig>)} (Story #021b) lives in
 * {@code mcp} and also references it. Lifting the enum up to {@code runtime}
 * keeps the dependency unidirectional (mcp → runtime).
 *
 * <p><b>Default choice</b> — Story #021a implements {@link #STDIO} only.
 * Requests for {@link #SSE} or {@link #STREAMABLE_HTTP} throw
 * {@code LINGS-M01} from {@code McpServerConnectionFactory.create} until
 * Story #021c lands.
 *
 * <p><b>JDK 8 compatibility</b> — Plain {@code enum} with three ordered
 * literals. No {@code record} / {@code sealed} / {@code var}.
 */
public enum McpTransportType {

    /** Subprocess + stdin/stdout JSON-RPC. The only transport implemented in Story #021a. */
    STDIO,

    /** Long-lived HTTP GET for server-pushed events. Reserved for Story #021c. */
    SSE,

    /** Stateless POST request/response. Reserved for Story #021c. */
    STREAMABLE_HTTP
}