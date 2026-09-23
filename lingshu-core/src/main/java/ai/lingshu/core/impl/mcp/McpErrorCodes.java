package ai.lingshu.core.impl.mcp;

/**
 * MCP error-code constants (Story #021b / Story #021c).
 *
 * <p><b>What</b> — Single-source-of-truth for the {@code LINGS-Mxx} codes that the
 * MCP integration throws. Concentrating the strings here avoids the prior
 * {@code Story #021a} pattern of passing the literal {@code "LINGS-M01"} into the
 * {@link ai.lingshu.core.mcp.McpTransportException} constructor from multiple call
 * sites — every addition / typo would otherwise need a separate grep.
 *
 * <p><b>Why here</b> — Lives in {@code impl.mcp} (next to {@link McpTransport} and
 * {@code McpToolAdapter.execute()}, which is the consumer). Not in the public
 * {@code mcp} SPI package because error codes are an implementation-level
 * detail (the public SPI is {@link ai.lingshu.core.mcp.McpTransportException},
 * which already carries the code as an opaque {@code String}).
 *
 * <p><b>Per-code map</b>:
 * <ul>
 *   <li>{@link #LINGS_M01} — {@code MCP_CONNECT_FAILED}. Originally thrown by
 *       {@link ai.lingshu.core.mcp.McpServerConnectionFactory#create} when the
 *       requested transport was not yet implemented (Story #021a origin).
 *       <b>Story #021c</b>: factory now wires all three transports, so this
 *       code is no longer raised at startup — but the constant is preserved
 *       for backward compatibility with {@code #021a} tests and as a reserved
 *       slot for future plugin transports that may temporarily fall back to it.</li>
 *   <li>{@link #LINGS_M02} — {@code MCP_TOOL_CALL_FAILED}. Thrown by
 *       {@link ai.lingshu.core.mcp.McpToolAdapter#execute} when an unexpected
 *       exception escapes the {@code transport.callTool(...)} delegation. The
 *       adapter translates every {@link Exception} into a {@code ToolResult.error}
 *       so this code surfaces to the LLM as an error message rather than as a
 *       thrown exception. Story #021b origin.</li>
 *   <li>{@link #LINGS_M03} — {@code MCP_HTTP_SSE_FAILED}. Thrown by
 *       {@link ai.lingshu.core.mcp.McpHttpSupport} for any HTTP-level
 *       failure encountered by the SSE or streamable-HTTP transports:
 *       4xx / 5xx response, connection refused, read / connect timeout,
 *       socket reset, malformed JSON body. The state machine in
 *       {@link ai.lingshu.core.mcp.SseMcpServerConnection} and
 *       {@link ai.lingshu.core.mcp.StreamableHttpMcpServerConnection} also
 *       catches {@code McpTransportException} from heartbeat probes and
 *       routes them through {@code handleDisconnect} rather than re-throwing
 *       so a single bad health probe does not crash the connection. Story
 *       #021c origin.</li>
 * </ul>
 *
 * <p><b>JDK 8 compatibility</b> — {@code final class} with a private ctor
 * (utility-class idiom); no instance is ever created.
 */
public final class McpErrorCodes {

    /** Original {@code MCP_CONNECT_FAILED} thrown by the factory when an unimplemented transport was requested (Story #021a). Reserved for backward compatibility. */
    public static final String LINGS_M01 = "LINGS-M01";

    /** {@code MCP_TOOL_CALL_FAILED} thrown by {@code McpToolAdapter.execute} when an unexpected exception escapes delegation (Story #021b). */
    public static final String LINGS_M02 = "LINGS-M02";

    /** 🆕 Story #021c — {@code MCP_HTTP_SSE_FAILED} — HTTP 4xx / 5xx / IO / timeout failures from {@code McpHttpSupport} (SSE or streamable HTTP transport). */
    public static final String LINGS_M03 = "LINGS-M03";

    private McpErrorCodes() {
        // utility class — instantiation is a programming error
        throw new AssertionError("McpErrorCodes must not be instantiated");
    }
}