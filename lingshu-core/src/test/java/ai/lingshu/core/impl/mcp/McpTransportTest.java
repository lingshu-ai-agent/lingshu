package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.mcp.ConnectionState;
import ai.lingshu.core.mcp.McpCallResult;
import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.mcp.McpServerConnection;
import ai.lingshu.core.mcp.McpToolAdapter;
import ai.lingshu.core.mcp.McpToolDescriptor;
import ai.lingshu.core.mcp.McpTransportException;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link McpTransport} (Story #021b, T-11).
 *
 * <p>Tests the package-private {@link McpTransport#onConnectionStateChange} method
 * directly (it's package-private specifically for this test). This avoids wiring
 * through {@code McpServerConnectionFactory} (which would spawn a real subprocess)
 * and exercises the listener body in isolation.
 *
 * <p>Three test groups:
 * <ol>
 *   <li>Lifecycle — empty / register / unregister on state transitions.</li>
 *   <li>Dispatch — {@code callTool} routes to the correct connection.</li>
 *   <li>Cleanup — {@code close} tears down all connections.</li>
 * </ol>
 *
 * <p>{@link McpServerConnection} is hand-rolled as {@link FakeConnection} because
 * JDK 23 with strong encapsulation blocks Mockito's inline mock-maker from
 * redefining interfaces that extend {@code AutoCloseable}.
 */
@DisplayName("McpTransport")
class McpTransportTest {

    private McpTransport transport;
    private ToolRegistry registry;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        transport = new McpTransport();
        registry = mock(ToolRegistry.class);
        mapper = new ObjectMapper();
    }

    // ── fake connection ─────────────────────────────────────────────

    /**
     * Minimal hand-rolled {@link McpServerConnection} for unit tests.
     */
    private static class FakeConnection implements McpServerConnection {
        private final String serverName;
        private final List<McpToolDescriptor> tools;
        private final List<Consumer<ConnectionState>> listeners = new CopyOnWriteArrayList<>();
        private ConnectionState state = ConnectionState.IDLE;
        private Instant lastHeartbeatAt;
        private McpCallResult callToolResult;
        private McpTransportException callToolError;
        private boolean closed;

        FakeConnection(String serverName, List<McpToolDescriptor> tools) {
            this.serverName = serverName;
            this.tools = tools;
        }

        @Override public String name() { return serverName; }
        @Override public ConnectionState state() { return state; }
        @Override public Instant lastHeartbeatAt() { return lastHeartbeatAt; }
        @Override public List<McpToolDescriptor> listTools() { return tools; }
        @Override
        public McpCallResult callTool(String toolName, com.fasterxml.jackson.databind.JsonNode input) {
            if (callToolError != null) {
                throw callToolError;
            }
            return callToolResult;
        }
        @Override
        public void onStateChange(Consumer<ConnectionState> listener) {
            listeners.add(listener);
        }
        @Override public void start() { /* no-op for tests */ }
        @Override public void close() { closed = true; }

        boolean isClosed() { return closed; }
    }

    /** Add a connection into transport.connections via reflection. */
    private void addConnection(McpServerConnection conn) throws Exception {
        Field f = McpTransport.class.getDeclaredField("connections");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<McpServerConnection> conns = (List<McpServerConnection>) f.get(transport);
        conns.add(conn);
    }

    private McpServerConfig cfg(String name) {
        return McpServerConfig.builder().name(name).build();
    }

    private McpToolDescriptor desc(String name) throws Exception {
        return McpToolDescriptor.builder()
            .name(name)
            .description("desc " + name)
            .inputSchema(mapper.readTree("{\"type\":\"object\"}"))
            .build();
    }

    // ── onConnectionStateChange: CONNECTED → register ────────────────

    @Test
    @DisplayName("CONNECTED → registry.register called once per tool")
    void listenerConnectedTriggersRegister() throws Exception {
        McpToolDescriptor t1 = desc("search");
        McpToolDescriptor t2 = desc("create_issue");
        FakeConnection conn = new FakeConnection("github", Arrays.asList(t1, t2));

        transport.onConnectionStateChange(cfg("github"), conn,
            ConnectionState.CONNECTED, registry);

        // Exactly 2 register calls
        ArgumentCaptor<ai.lingshu.core.slot.Tool> toolCaptor =
            ArgumentCaptor.forClass(ai.lingshu.core.slot.Tool.class);
        verify(registry, times(2)).register(toolCaptor.capture());
        // Each registered tool is an McpToolAdapter with namespaced name
        for (ai.lingshu.core.slot.Tool t : toolCaptor.getAllValues()) {
            assertTrue(t instanceof McpToolAdapter,
                "Registered tool must be McpToolAdapter");
            assertTrue(t.name().startsWith("github:"),
                "Tool name must be namespaced: " + t.name());
        }
        assertTrue(toolCaptor.getAllValues().stream().anyMatch(t -> t.name().equals("github:search")));
        assertTrue(toolCaptor.getAllValues().stream().anyMatch(t -> t.name().equals("github:create_issue")));
    }

    // ── onConnectionStateChange: DISCONNECTED → unregister ───────────

    @Test
    @DisplayName("DISCONNECTED → registry.unregister called once per tool")
    void listenerDisconnectedTriggersUnregister() throws Exception {
        McpToolDescriptor t1 = desc("search");
        FakeConnection conn = new FakeConnection("github", Collections.singletonList(t1));
        // Make unregister return true (as DefaultToolRegistry does for registered names)
        when(registry.unregister("github:search")).thenReturn(true);

        transport.onConnectionStateChange(cfg("github"), conn,
            ConnectionState.DISCONNECTED, registry);

        verify(registry, times(1)).unregister("github:search");
    }

    @Test
    @DisplayName("FAILED → registry.unregister called once per tool")
    void listenerFailedTriggersUnregister() throws Exception {
        McpToolDescriptor t1 = desc("search");
        FakeConnection conn = new FakeConnection("github", Collections.singletonList(t1));
        when(registry.unregister("github:search")).thenReturn(true);

        transport.onConnectionStateChange(cfg("github"), conn,
            ConnectionState.FAILED, registry);

        verify(registry, times(1)).unregister("github:search");
    }

    // ── onConnectionStateChange: intermediate states are no-ops ──────

    @Test
    @DisplayName("CONNECTING/RECONNECTING/IDLE → no registry calls")
    void listenerIntermediateStatesNoOp() throws Exception {
        McpToolDescriptor t1 = desc("search");
        FakeConnection conn = new FakeConnection("github", Collections.singletonList(t1));

        for (ConnectionState s : new ConnectionState[]{
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.IDLE}) {
            transport.onConnectionStateChange(cfg("github"), conn, s, registry);
        }
        verify(registry, never()).register(any(ai.lingshu.core.slot.Tool.class));
        verify(registry, never()).unregister(anyString());
    }

    // ── listener exception isolation ─────────────────────────────────

    @Test
    @DisplayName("CONNECTED: one tool failing to register does NOT prevent the others")
    void listenerExceptionIsolationOnRegister() throws Exception {
        McpToolDescriptor t1 = desc("search");
        McpToolDescriptor t2 = desc("create_issue");
        FakeConnection conn = new FakeConnection("github", Arrays.asList(t1, t2));
        // First register throws, second succeeds
        org.mockito.Mockito.doThrow(new RuntimeException("simulated register failure"))
            .doNothing()
            .when(registry).register(any(ai.lingshu.core.slot.Tool.class));

        transport.onConnectionStateChange(cfg("github"), conn,
            ConnectionState.CONNECTED, registry);

        // Both calls attempted; the second still happens after the first throws
        verify(registry, times(2)).register(any(ai.lingshu.core.slot.Tool.class));
    }

    // ── callTool dispatch ───────────────────────────────────────────

    @Test
    @DisplayName("callTool routes to the matching connection by serverName")
    void callToolRoutesToCorrectConnection() throws Exception {
        FakeConnection c1 = new FakeConnection("server1", new ArrayList<>());
        c1.callToolResult = McpCallResult.success("hello from server1");
        FakeConnection c2 = new FakeConnection("server2", new ArrayList<>());
        c2.callToolResult = McpCallResult.success("hello from server2");
        addConnection(c1);
        addConnection(c2);

        McpCallResult actual = transport.callTool("server1", "anyTool", null);
        assertEquals("hello from server1", actual.getContent());
    }

    @Test
    @DisplayName("callTool(unknownServer) → IllegalStateException")
    void callToolUnknownServerThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> transport.callTool("ghost", "tool", null));
        assertTrue(ex.getMessage().contains("Unknown MCP server: ghost"),
            "Error message must mention serverName");
    }

    // ── callTool propagates McpTransportException unchanged ─────────

    @Test
    @DisplayName("callTool propagates McpTransportException unchanged")
    void callToolPropagatesTransportException() throws Exception {
        FakeConnection conn = new FakeConnection("server1", new ArrayList<>());
        conn.callToolError = new McpTransportException("LINGS-M01", "boom");
        addConnection(conn);

        McpTransportException ex = assertThrows(McpTransportException.class,
            () -> transport.callTool("server1", "tool", null));
        assertEquals("LINGS-M01", ex.getCode());
        assertEquals("boom", ex.getMessage());
    }

    // ── getConnections is read-only ─────────────────────────────────

    @Test
    @DisplayName("getConnections returns unmodifiable list snapshot")
    void getConnectionsIsReadOnly() throws Exception {
        FakeConnection conn = new FakeConnection("server1", new ArrayList<>());
        addConnection(conn);

        List<McpServerConnection> snap = transport.getConnections();
        assertEquals(1, snap.size());
        assertSame(conn, snap.get(0));
        // Modifying the returned list must throw
        assertThrows(UnsupportedOperationException.class,
            () -> snap.add(new FakeConnection("x", new ArrayList<>())));
    }

    // ── close tears down all connections ─────────────────────────────

    @Test
    @DisplayName("close() calls connection.close() on every connection and clears list")
    void closeClosesAllConnections() throws Exception {
        FakeConnection c1 = new FakeConnection("c1", new ArrayList<>());
        FakeConnection c2 = new FakeConnection("c2", new ArrayList<>());
        addConnection(c1);
        addConnection(c2);

        transport.close();

        assertTrue(c1.isClosed());
        assertTrue(c2.isClosed());
        assertEquals(0, transport.getConnections().size());
    }

    @Test
    @DisplayName("close() continues even if one connection.close() throws")
    void closeContinuesDespiteFailures() throws Exception {
        // First connection throws on close
        FakeConnection c1 = new FakeConnection("c1", new ArrayList<>()) {
            @Override public void close() {
                throw new RuntimeException("simulated close failure");
            }
        };
        FakeConnection c2 = new FakeConnection("c2", new ArrayList<>());
        addConnection(c1);
        addConnection(c2);

        transport.close();   // must not propagate c1's exception

        assertTrue(c2.isClosed());
    }

    // ── connect validates registry ──────────────────────────────────

    @Test
    @DisplayName("connect(null registry) → IllegalStateException")
    void connectNullRegistryThrows() {
        // null registry with a non-null cfg (otherwise null-cfg branch returns silently)
        McpServerConfig cfg = cfg("x");
        assertThrows(IllegalStateException.class,
            () -> transport.connect(Collections.singletonList(cfg), null));
    }

    @Test
    @DisplayName("connect(empty configs) is a no-op")
    void connectEmptyConfigsNoOp() {
        transport.connect(Collections.emptyList(), registry);
        assertEquals(0, transport.getConnections().size());
    }
}