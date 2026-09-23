package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link McpTransportAutoConfiguration} (Story #021b, T-12).
 *
 * <p>Six cases covering field-by-field propagation from
 * {@link AgentConfig.ServerConfig} (YAML layer) to {@link McpServerConfig} (runtime layer),
 * plus null-handling and the {@code > 0} heartbeat override rule.
 */
@DisplayName("McpTransportAutoConfiguration")
class McpTransportAutoConfigurationTest {

    private McpTransportAutoConfiguration config;

    @BeforeEach
    void setUp() {
        config = new McpTransportAutoConfiguration();
    }

    /** Build AgentConfig (defaults + the given servers). */
    private static AgentConfig agentConfigWithMcp(AgentConfig.Mcp mcp) {
        AgentConfig base = AgentConfigDefaults.defaults();
        return new AgentConfig(
            base.getFlowEngine(), base.getLlm(), base.getPrompt(), base.getToolExecutor(),
            base.getSandbox(), base.getCompactor(), base.getSessionStore(),
            base.getDelegate(), mcp, base.getSkills(),
            base.getToolParallelism(), base.getToolTimeoutSeconds(),
            base.getApprovalTimeoutSeconds(), base.getTurnTimeoutSeconds(),
            base.getLlmTimeoutSeconds(), base.getReactMaxSteps(),
            base.getIdentity(), base.getInstructions(), base.getMemory(),
            base.getA2aTransport(),
            base.getTenants(),
            base.getA2a(),
            base.getCompactorConfig(),
            base.getTools());
    }

    @Test
    @DisplayName("null AgentConfig → empty list")
    void nullAgentConfigReturnsEmptyList() {
        List<McpServerConfig> result = config.mcpServerConfigs(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("AgentConfig without mcp (default) → empty list")
    void emptyMcpConfigReturnsEmptyList() {
        // defaults() returns AgentConfig with mcp = null
        List<McpServerConfig> result = config.mcpServerConfigs(AgentConfigDefaults.defaults());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("one stdio server → one McpServerConfig with all fields propagated")
    void oneStdioServerPropagatesAllFields() {
        Map<String, String> env = new HashMap<>();
        env.put("GITHUB_TOKEN", "abc123");
        AgentConfig.ServerConfig sc = new AgentConfig.ServerConfig(
            "github",
            "npx",
            Arrays.asList("-y", "@modelcontextprotocol/server-github"),
            env,
            McpTransportType.STDIO,
            null,
            30000L, 10000L, 60000L);
        AgentConfig ac = agentConfigWithMcp(new AgentConfig.Mcp(Arrays.asList(sc)));

        List<McpServerConfig> result = config.mcpServerConfigs(ac);
        assertEquals(1, result.size());
        McpServerConfig runtime = result.get(0);
        assertEquals("github", runtime.getName());
        assertEquals(McpTransportType.STDIO, runtime.getTransport());
        assertEquals("npx", runtime.getCommand());
        assertEquals(Arrays.asList("-y", "@modelcontextprotocol/server-github"), runtime.getArgs());
        assertEquals(env, runtime.getEnv());
        assertEquals(null, runtime.getUrl());
        // heartbeats propagated
        assertEquals(30000L, runtime.getHeartbeatIntervalMs());
        assertEquals(10000L, runtime.getHeartbeatTimeoutMs());
        assertEquals(60000L, runtime.getReconnectCapMs());
    }

    @Test
    @DisplayName("three stdio servers → three McpServerConfigs in order")
    void threeStdioServersReturnsThreeConfigs() {
        AgentConfig.ServerConfig s1 = new AgentConfig.ServerConfig(
            "github", "npx", Collections.emptyList(), Collections.emptyMap(),
            McpTransportType.STDIO, null, 0L, 0L, 0L);
        AgentConfig.ServerConfig s2 = new AgentConfig.ServerConfig(
            "filesystem", "uvx", Arrays.asList("mcp-server-filesystem", "/tmp"),
            Collections.emptyMap(), McpTransportType.STDIO, null, 0L, 0L, 0L);
        AgentConfig.ServerConfig s3 = new AgentConfig.ServerConfig(
            "postgres", "uvx", Collections.emptyList(), Collections.emptyMap(),
            McpTransportType.STDIO, null, 0L, 0L, 0L);
        AgentConfig ac = agentConfigWithMcp(new AgentConfig.Mcp(Arrays.asList(s1, s2, s3)));

        List<McpServerConfig> result = config.mcpServerConfigs(ac);
        assertEquals(3, result.size());
        assertEquals("github", result.get(0).getName());
        assertEquals("filesystem", result.get(1).getName());
        assertEquals("postgres", result.get(2).getName());
    }

    @Test
    @DisplayName("heartbeat fields zero or negative → runtime uses McpServerConfig default")
    void heartbeatFieldsZeroOrNegativeUseDefault() {
        AgentConfig.ServerConfig sc = new AgentConfig.ServerConfig(
            "github", "npx", Collections.emptyList(), Collections.emptyMap(),
            McpTransportType.STDIO, null,
            0L,    // heartbeatIntervalMs = 0 → default 30000
            -1L,   // heartbeatTimeoutMs = -1 → default 10000
            0L);   // reconnectCapMs = 0 → default 60000
        AgentConfig ac = agentConfigWithMcp(new AgentConfig.Mcp(Arrays.asList(sc)));

        List<McpServerConfig> result = config.mcpServerConfigs(ac);
        McpServerConfig runtime = result.get(0);
        assertEquals(30000L, runtime.getHeartbeatIntervalMs(),
            "heartbeatIntervalMs=0 must fall back to McpServerConfig default");
        assertEquals(10000L, runtime.getHeartbeatTimeoutMs(),
            "heartbeatTimeoutMs=-1 must fall back to McpServerConfig default");
        assertEquals(60000L, runtime.getReconnectCapMs(),
            "reconnectCapMs=0 must fall back to McpServerConfig default");
    }

    @Test
    @DisplayName("STDIO transport → runtime url = null")
    void stdioServerUrlIsNull() {
        AgentConfig.ServerConfig sc = new AgentConfig.ServerConfig(
            "github", "npx", Collections.emptyList(), Collections.emptyMap(),
            McpTransportType.STDIO, null, 0L, 0L, 0L);
        AgentConfig ac = agentConfigWithMcp(new AgentConfig.Mcp(Arrays.asList(sc)));

        List<McpServerConfig> result = config.mcpServerConfigs(ac);
        assertEquals(null, result.get(0).getUrl());
    }

    @Test
    @DisplayName("result is unmodifiable")
    void resultIsUnmodifiable() {
        AgentConfig.ServerConfig sc = new AgentConfig.ServerConfig(
            "github", "npx", Collections.emptyList(), Collections.emptyMap(),
            McpTransportType.STDIO, null, 0L, 0L, 0L);
        AgentConfig ac = agentConfigWithMcp(new AgentConfig.Mcp(Arrays.asList(sc)));

        List<McpServerConfig> result = config.mcpServerConfigs(ac);
        try {
            result.add(McpServerConfig.builder().name("x").build());
            assertTrue(false, "unmodifiable list must reject add()");
        } catch (UnsupportedOperationException expected) {
            // OK
        }
    }
}