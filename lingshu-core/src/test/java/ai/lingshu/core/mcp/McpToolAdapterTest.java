package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpTransport;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link McpToolAdapter} (Story #021b, T-09).
 *
 * <p>Covers the 5 public-API contracts (name / description / inputSchema / execute / neverThrows)
 * + the 3 error-translation paths (success / mcpError / unknownException / knownMcpException).
 *
 * <p>{@code McpTransport} is stubbed via a tiny anonymous subclass because JDK 23 with
 * strong encapsulation blocks Mockito's inline mock-maker from redefining concrete
 * classes (dsh §11.6 — Spring Boot 3.2.5 test stack stays on JDK 8 bytecode;
 * mockito 5.8 inline mode needs {@code --add-opens java.base/java.lang=ALL-UNNAMED}
 * on JDK 23). Subclass stub avoids touching the test JVM args.
 *
 * <p>{@code ToolExecutionContext} is mocked via Mockito because it has 8 methods we
 * do not need to drive here — the adapter does not touch it.
 */
@DisplayName("McpToolAdapter")
class McpToolAdapterTest {

    private StubTransport transport;
    private ObjectMapper mapper;
    private McpToolDescriptor descriptor;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        transport = new StubTransport();
        mapper = new ObjectMapper();
        ctx = mock(ToolExecutionContext.class);
        descriptor = McpToolDescriptor.builder()
            .name("search_repos")
            .description("Search GitHub repositories")
            .inputSchema(mapper.readTree("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"))
            .build();
    }

    /**
     * Minimal {@link McpTransport} subclass — only {@link #callTool} is overridden
     * (the only method {@link McpToolAdapter} touches). All other methods inherit
     * the no-op / default behaviors of the parent.
     */
    private static class StubTransport extends McpTransport {
        /** Per-test behavior — may return a value or throw. */
        private McpCallResult nextResult;
        private RuntimeException nextError;

        void willReturn(McpCallResult r) {
            this.nextResult = r;
            this.nextError = null;
        }

        void willThrow(RuntimeException e) {
            this.nextError = e;
            this.nextResult = null;
        }

        @Override
        public McpCallResult callTool(String serverName, String toolName, JsonNode input) {
            if (nextError != null) {
                throw nextError;
            }
            return nextResult;
        }
    }

    // ── name / description / inputSchema ─────────────────────────────

    @Test
    @DisplayName("name() returns namespacedName (serverName:toolName)")
    void nameReturnsNamespacedName() {
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        assertEquals("github:search_repos", adapter.name());
    }

    @Test
    @DisplayName("description() returns desc.getDescription()")
    void descriptionReturnsDescriptorDescription() {
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        assertEquals("Search GitHub repositories", adapter.description());
    }

    @Test
    @DisplayName("inputSchema() returns desc.getInputSchema()")
    void inputSchemaReturnsDescriptorSchema() {
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        assertNotNull(adapter.inputSchema());
        assertEquals("object", adapter.inputSchema().get("type").asText());
    }

    // ── execute: success path ────────────────────────────────────────

    @Test
    @DisplayName("execute(mcpSuccess) → ToolResult.success with content")
    void executeSuccessReturnsToolResultSuccess() {
        transport.willReturn(McpCallResult.success("ok content"));
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        ToolCall call = new ToolCall("call-1", "github:search_repos", null);
        ai.lingshu.core.message.ToolResult result = adapter.execute(call, ctx);
        assertFalse(result.isError());
        assertEquals("ok content", result.getContent());
        assertEquals("call-1", result.getToolUseId());
        assertEquals(ai.lingshu.core.message.ToolResult.Status.SUCCESS, result.getStatus());
    }

    // ── execute: McpCallResult.isError() == true ─────────────────────

    @Test
    @DisplayName("execute(mcpError) → ToolResult.error with errorMessage")
    void executeMcpErrorReturnsToolResultError() {
        transport.willReturn(McpCallResult.error("rate limit exceeded"));
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        ToolCall call = new ToolCall("call-2", "github:search_repos", null);
        ai.lingshu.core.message.ToolResult result = adapter.execute(call, ctx);
        assertTrue(result.isError());
        assertEquals("rate limit exceeded", result.getContent());
        assertEquals(ai.lingshu.core.message.ToolResult.Status.ERROR, result.getStatus());
    }

    // ── execute: McpTransportException known code ────────────────────

    @Test
    @DisplayName("execute(McpTransportException known code) → error message contains [CODE]")
    void executeKnownMcpTransportException() {
        transport.willThrow(new McpTransportException("LINGS-M01", "transport not implemented"));
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        ToolCall call = new ToolCall("call-3", "github:search_repos", null);
        ai.lingshu.core.message.ToolResult result = adapter.execute(call, ctx);
        assertTrue(result.isError());
        assertTrue(result.getContent().contains("LINGS-M01"),
            "Error message must contain the original McpTransportException code: " + result.getContent());
        assertTrue(result.getContent().contains("transport not implemented"));
    }

    // ── execute: unexpected Exception → LINGS-M02 ────────────────────

    @Test
    @DisplayName("execute(unexpected Exception) → ToolResult.error with LINGS-M02")
    void executeUnexpectedExceptionReturnsLingsM02Error() {
        transport.willThrow(new RuntimeException("boom"));
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        ToolCall call = new ToolCall("call-4", "github:search_repos", null);
        ai.lingshu.core.message.ToolResult result = adapter.execute(call, ctx);
        assertTrue(result.isError());
        assertTrue(result.getContent().contains("LINGS-M02"),
            "Unexpected exception must translate to LINGS-M02: " + result.getContent());
        assertTrue(result.getContent().contains("boom"));
    }

    // ── execute: NEVER throws (§4.10.1 硬规则 2) ─────────────────────

    @Test
    @DisplayName("execute(ANY) never throws — even with null input + null result content")
    void executeNeverThrows() {
        transport.willThrow(new NullPointerException("NPE for fun"));
        McpToolAdapter adapter = new McpToolAdapter(transport, "github",
            "github:search_repos", descriptor);
        ToolCall call = new ToolCall("call-5", "github:search_repos", null);
        assertDoesNotThrow(() -> adapter.execute(call, ctx),
            "execute() must NEVER throw — §4.10.1 硬规则 2");
    }

    // ── ctor: rejects nulls ──────────────────────────────────────────

    @Test
    @DisplayName("ctor rejects null transport / serverName / namespacedName / desc")
    void ctorRejectsNulls() {
        assertThrows(IllegalArgumentException.class,
            () -> new McpToolAdapter(null, "gh", "gh:search", descriptor),
            "null transport must throw");
        assertThrows(IllegalArgumentException.class,
            () -> new McpToolAdapter(transport, null, "gh:search", descriptor),
            "null serverName must throw");
        assertThrows(IllegalArgumentException.class,
            () -> new McpToolAdapter(transport, "gh", null, descriptor),
            "null namespacedName must throw");
        assertThrows(IllegalArgumentException.class,
            () -> new McpToolAdapter(transport, "gh", "gh:search", null),
            "null desc must throw");
    }
}