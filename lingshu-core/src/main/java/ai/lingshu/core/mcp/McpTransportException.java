package ai.lingshu.core.mcp;

/**
 * MCP transport-domain exception (Story #021a, dsh §15.9).
 *
 * <p>Introduces the {@code M} (MCP) error domain. Code names follow the
 * {@code LINGS-M<NN>} convention where {@code <NN>} is the per-domain code.
 * Story #021a defines {@code LINGS-M01 = MCP_CONNECT_FAILED}, raised by
 * {@link McpServerConnectionFactory#create(McpServerConfig)} when an
 * unsupported transport is requested (e.g. {@code SSE} or
 * {@code STREAMABLE_HTTP} before Story #021c lands).
 *
 * <p><b>JDK 8 compatibility</b> — Plain {@code RuntimeException} subclass;
 * no {@code Exception.captureStackTrace}-style cleverness.
 */
public class McpTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Canonical error code (e.g. {@code "LINGS-M01"}). */
    private final String code;

    public McpTransportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public McpTransportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return the canonical error code. */
    public String getCode() {
        return code;
    }
}