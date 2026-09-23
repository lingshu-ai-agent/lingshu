package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;

/**
 * Dispatch factory for {@link McpServerConnection} implementations
 * (Story #021a / Story #021c, dsh §6.5 (2.1) L4601-4614).
 *
 * <p><b>Story #021a scope</b> — Only {@link McpTransportType#STDIO} was wired.
 * <p><b>Story #021c scope</b> — All three transports wired. Adding a new
 * transport flavor in a later Story means adding one {@code case} to the
 * switch below; no other class changes.
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
     * @throws IllegalStateException if {@code cfg} is null, has a null transport,
     *         or requests an unhandled transport.
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
                return new SseMcpServerConnection(cfg);
            case STREAMABLE_HTTP:
                return new StreamableHttpMcpServerConnection(cfg);
            default:
                throw new IllegalStateException("Unhandled MCP transport: " + t);
        }
    }
}