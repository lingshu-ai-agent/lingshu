package ai.lingshu.core.impl.mcp;

/**
 * MCP error-code constants (Story #021b).
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
 *   <li>{@link #LINGS_M01} — {@code MCP_CONNECT_FAILED}. Thrown by
 *       {@link ai.lingshu.core.mcp.McpServerConnectionFactory#create} when the
 *       requested transport is not yet implemented (e.g. {@code SSE} /
 *       {@code STREAMABLE_HTTP} before Story #021c lands). Story #021a origin.</li>
 *   <li>{@link #LINGS_M02} — {@code MCP_TOOL_CALL_FAILED}. Thrown by
 *       {@link ai.lingshu.core.mcp.McpToolAdapter#execute} when an unexpected
 *       exception escapes the {@code transport.callTool(...)} delegation. The
 *       adapter translates every {@link Exception} into a {@code ToolResult.error}
 *       so this code surfaces to the LLM as an error message rather than as a
 *       thrown exception. Story #021b origin.</li>
 * </ul>
 *
 * <p><b>JDK 8 compatibility</b> — {@code final class} with a private ctor
 * (utility-class idiom); no instance is ever created.
 */
public final class McpErrorCodes {

    /** Thrown when {@code McpServerConnectionFactory.create} rejects an unimplemented transport (Story #021a). */
    public static final String LINGS_M01 = "LINGS-M01";

    /** Thrown when {@code McpToolAdapter.execute} catches an unexpected exception (Story #021b). */
    public static final String LINGS_M02 = "LINGS-M02";

    private McpErrorCodes() {
        // utility class — instantiation is a programming error
        throw new AssertionError("McpErrorCodes must not be instantiated");
    }
}