package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;

/**
 * Dispatch factory for {@link McpServerConnection} implementations
 * (Story #021a, dsh §6.5 (2.1)).
 *
 * <p><b>Story #021a scope</b> — Only {@link McpTransportType#STDIO} is wired;
 * {@link McpTransportType#SSE} and {@link McpTransportType#STREAMABLE_HTTP}
 * throw {@link McpTransportException} with code {@code LINGS-M01}. Story
 * #021c will extend the switch.
 *
 * <p><b>JDK 8 compatibility</b> — Final class with private ctor + static
 * factory; no enum singleton tricks.
 */
public final class McpServerConnectionFactory {

    private McpServerConnectionFactory() {
        // utility
    }

    /**
     * Build a {@link McpServerConnection} for the requested transport.
     *
     * @param cfg server configuration (name, transport, command/args/env/url, heartbeat params)
     * @return a fresh connection in {@link ConnectionState#IDLE}
     * @throws McpTransportException with code {@code LINGS-M01} if the
     *         transport is not yet implemented (Story #021c territory).
     * @throws IllegalStateException if {@code cfg} is null
     */
    public static McpServerConnection create(McpServerConfig cfg) {
        if (cfg == null) {
            throw new IllegalStateException("McpServerConfig must not be null");
        }
        McpTransportType t = cfg.getTransport();
        if (t == null) {
            throw new IllegalStateException(
                "McpServerConfig.transport must not be null (cfg=" + cfg.getName() + ")");
        }
        switch (t) {
            case STDIO:
                return new StdioMcpServerConnection(cfg);
            case SSE:
            case STREAMABLE_HTTP:
                throw new McpTransportException("LINGS-M01",
                    "MCP transport " + t + " is not implemented yet (Story #021a only supports STDIO; "
                        + "SSE / STREAMABLE_HTTP land in Story #021c)");
            default:
                throw new IllegalStateException("Unhandled MCP transport: " + t);
        }
    }
}