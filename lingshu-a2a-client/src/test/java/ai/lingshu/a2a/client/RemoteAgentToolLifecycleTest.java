package ai.lingshu.a2a.client;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 slice tests — {@link RemoteAgentToolLifecycle} (Story #009e).
 *
 * <p>Direct wiring without Spring: instantiate the {@link RemoteAgentTool}
 * with a stub {@link A2aTransport}, a fresh {@link DefaultToolRegistry}, and
 * the {@link RemoteAgentToolLifecycle} under test. This isolates the
 * lifecycle behavior from Spring's bean factory, so any future change to
 * bean discovery logic does not mask a lifecycle regression.</p>
 */
class RemoteAgentToolLifecycleTest {

    private RemoteAgentTool tool;
    private ToolRegistry registry;
    private RemoteAgentToolLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        // Build a minimal RemoteAgentTool using the 2-arg ctor (backward compat).
        // The lifecycle under test only uses tool.name() and the registry;
        // it never invokes execute(), so the transport can be a no-op stub.
        A2aTransport stubTransport = new StubA2aTransport();
        ObjectMapper json = new ObjectMapper();
        tool = new RemoteAgentTool(stubTransport, json);
        registry = new DefaultToolRegistry();
        lifecycle = new RemoteAgentToolLifecycle(tool, registry);
    }

    @Test
    @DisplayName("TC-009e-LC-1: start_registersRemoteAgentTool_withRegistry")
    void start_registersRemoteAgentTool_withRegistry() {
        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(registry.findByName("remote_agent"))
            .as("after start(), remote_agent must be registered")
            .isSameAs(tool);
    }

    @Test
    @DisplayName("TC-009e-LC-2: stop_unregistersRemoteAgentTool_fromRegistry")
    void stop_unregistersRemoteAgentTool_fromRegistry() {
        lifecycle.start();
        assertThat(registry.findByName("remote_agent")).isNotNull();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
        // After unregister, findByName throws (per ToolRegistry.findByName contract)
        Throwable thrown = catchThrowable(() -> registry.findByName("remote_agent"));
        assertThat(thrown)
            .as("after stop(), findByName must throw (tool removed)")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-009e-LC-3: start_isIdempotent_secondCallIsNoop")
    void start_isIdempotent_secondCallIsNoop() {
        lifecycle.start();
        // Second start should NOT throw or double-register
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        // registry still has exactly one entry under "remote_agent"
        assertThat(registry.findByName("remote_agent")).isSameAs(tool);
        // names().size() should be 1
        assertThat(registry.names()).hasSize(1);
    }

    @Test
    @DisplayName("TC-009e-LC-4: isRunning_reflectsState_beforeStart_afterStart_afterStop")
    void isRunning_reflectsState_beforeStart_afterStart_afterStop() {
        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("TC-009e-LC-5: isAutoStartup_returnsTrue")
    void isAutoStartup_returnsTrue() {
        assertThat(lifecycle.isAutoStartup())
            .as("SmartLifecycle.isAutoStartup must return true so Spring auto-starts it")
            .isTrue();
    }

    @Test
    @DisplayName("TC-009e-LC-6: getPhase_returnsSmartLifecycleDefault")
    void getPhase_returnsSmartLifecycleDefault() {
        assertThat(lifecycle.getPhase())
            .as("phase must match SmartLifecycle default Integer.MAX_VALUE - 1024")
            .isEqualTo(Integer.MAX_VALUE - 1024);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // suppress unused — JsonNode/ObjectNode referenced via inputSchema behavior
    @SuppressWarnings("unused")
    private static JsonNode sampleSchema(ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        return root;
    }

    /**
     * No-op A2aTransport stub. Only {@link #name()} is referenced by
     * {@link RemoteAgentTool}; the rest are throw-only to keep this slice
     * test free of remote-server setup.
     */
    private static final class StubA2aTransport implements A2aTransport {
        @Override
        public Map<String, Object> fetchCard(String agentName) {
            return Collections.emptyMap();
        }
        @Override
        public ToolResult submit(String agentName, String skill, String inputJson) {
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .content("stub-ok")
                .build();
        }
        @Override
        public ToolResult get(String taskId) { return null; }
        @Override
        public boolean cancel(String taskId) { return false; }
        @Override
        public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent) { /* no-op */ }
    }
}