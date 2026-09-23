package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpTransport;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 integration tests for {@link McpToolAdapter} via real stdio subprocess (Story #021b, T-13).
 *
 * <p>Two cases:
 * <ol>
 *   <li>stdio MCP server up → adapter registered in {@link ToolRegistry} →
 *       {@code execute()} → {@link ai.lingshu.core.message.ToolResult#isError()} == false.</li>
 *   <li>stdio MCP server killed mid-test → adapter unregistered by McpTransport's
 *       {@code onConnectionStateChange} listener → {@link ToolRegistry#lookup(String)} returns null.</li>
 * </ol>
 *
 * <p>Uses {@link McpTestSupport#stdioCfg(String, long, long, long)} for shrunken heartbeats
 * (200ms / 1s reconnect cap) so the L3 suite completes in seconds, and the
 * {@code test.mcp.exitAfter} system property (forwarded to {@code TestMcpServer})
 * to simulate a subprocess death for the disconnect case.
 */
@DisplayName("Story #021b — McpToolAdapter integration (real stdio subprocess)")
class McpToolAdapterIT {

    private McpTransport transport;
    private ToolRegistry registry;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        transport = new McpTransport();
        registry = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    /** Poll the connection state until target reached or timeout. */
    private boolean waitForState(McpServerConnection conn, ConnectionState target, long maxMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (conn.state() == target) return true;
            Thread.sleep(50);
        }
        return conn.state() == target;
    }

    @Test
    @DisplayName("stdio MCP server: (re)connected → adapter registered → execute succeeds")
    void stdioConnectedRegistersToolAndExecuteSucceeds() throws Exception {
        // small heartbeats so test runs in seconds
        McpServerConfig cfg = McpTestSupport.stdioCfg("gh", 200L, 1000L, 1000L);

        transport.connect(Collections.singletonList(cfg), registry);

        // Wait for the connection to come up
        assertTrue(transport.getConnections().size() == 1, "Expected 1 connection");
        McpServerConnection conn = transport.getConnections().get(0);
        assertTrue(waitForState(conn, ConnectionState.CONNECTED, 10_000L),
            "Connection must reach CONNECTED within 10s; actual=" + conn.state());

        // Registry should now have a tool with the namespaced name
        Tool adapter = registry.lookup("gh:echo");
        assertNotNull(adapter, "ToolRegistry must contain the namespaced tool gh:echo");
        assertTrue(adapter instanceof McpToolAdapter,
            "Registered tool must be McpToolAdapter");

        // Execute the tool
        ToolCall call = new ToolCall("test-call-1", "gh:echo",
            mapper.readTree("{\"input\":\"hello world\"}"));
        ai.lingshu.core.message.ToolResult result =
            adapter.execute(call, mockContext());
        assertTrue(!result.isError(), "execute() must return SUCCESS, got: "
            + result.getStatus() + " / " + result.getContent());
        assertNotNull(result.getContent());
        assertTrue(result.getContent().length() > 0);
        assertTrue(result.getContent().contains("fake-result"),
            "TestMcpServer.tools/call handler returns content=\"fake-result\"; got: "
                + result.getContent());
    }

    @Test
    @DisplayName("stdio MCP server killed → adapter unregistered from ToolRegistry")
    void stdioKilledUnregistersTool() throws Exception {
        // Force TestMcpServer to exit after 3 messages — covers initialize + initialized +
        // tools/list → CONNECTED → then exits → DISCONNECTED
        System.setProperty("test.mcp.exitAfter", "3");

        try {
            McpServerConfig cfg = McpTestSupport.stdioCfg("killable", 200L, 1000L, 500L);
            transport.connect(Collections.singletonList(cfg), registry);

            McpServerConnection conn = transport.getConnections().get(0);
            assertTrue(waitForState(conn, ConnectionState.CONNECTED, 10_000L),
                "Connection must reach CONNECTED before kill test");
            assertNotNull(registry.lookup("killable:echo"),
                "Tool must be registered while connected");

            // The server will exit (exitAfter=3) — the heartbeat probe will eventually
            // detect process.isAlive() == false and transition to DISCONNECTED.
            // Wait for unregister.
            boolean unregistered = false;
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline) {
                if (registry.lookup("killable:echo") == null) {
                    unregistered = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(unregistered,
                "Tool must be unregistered within 15s of subprocess death");

            // The connection should have transitioned away from CONNECTED (DISCONNECTED
            // or RECONNECTING). Just check it's not CONNECTED anymore.
            assertTrue(conn.state() != ConnectionState.CONNECTED,
                "Connection state must transition away from CONNECTED; actual=" + conn.state());
        } finally {
            System.clearProperty("test.mcp.exitAfter");
        }
    }

    /** Minimal ToolExecutionContext stub — adapter.execute() does not touch it. */
    private ToolExecutionContext mockContext() {
        return new ToolExecutionContext() {
            @Override public ai.lingshu.core.runtime.Session session() { return null; }
            @Override public ToolExecutionContext.ToolSink sink() { return null; }
            @Override public java.nio.file.Path workingDirectory() {
                return java.nio.file.Paths.get("/tmp");
            }
            @Override public java.nio.file.FileSystem fs() { return null; }
            @Override public ToolExecutionContext.NetworkClient http() { return null; }
            @Override public ToolExecutionContext.ApprovalGate approval() { return null; }
            @Override public ToolExecutionContext.CancellationToken cancellation() { return null; }
            @Override public ai.lingshu.core.slot.ToolCallConfig callConfig() { return null; }
        };
    }
}