package ai.lingshu.examples.demomcp;

import ai.lingshu.core.impl.mcp.McpTransport;
import ai.lingshu.core.impl.mcp.McpTransportLifecycle;
import ai.lingshu.core.mcp.ConnectionState;
import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.mcp.McpServerConnection;
import ai.lingshu.core.mcp.McpServerConnectionFactory;
import ai.lingshu.core.mcp.SseMcpServerConnection;
import ai.lingshu.core.mcp.StdioMcpServerConnection;
import ai.lingshu.core.mcp.StreamableHttpMcpServerConnection;
import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a + #021b + #021c AC skeleton — MCP 3 transport wiring 黑盒。
 *
 * <p>骨架阶段(Stage A)只验证:
 * <ol>
 *   <li>Spring Boot main 启动成功</li>
 *   <li>{@code McpTransport} + {@code McpTransportLifecycle} Bean 由 lingshu-core 注入容器</li>
 *   <li>{@link McpTransportType} enum 三值齐全(STDIO / SSE / STREAMABLE_HTTP)</li>
 *   <li>{@link ConnectionState} 6 态 enum 完整(IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED)</li>
 *   <li>{@link McpServerConnectionFactory#create(McpServerConfig)} 按 transport 分派到 3 个 concrete class</li>
 * </ol>
 *
 * <p>完整 AC 黑盒覆盖(MCP 子进程拉起 + 心跳保活 + 指数退避 + Tool 注册 / 注销 / dispatch)留 Stage B,
 * 用 mock subprocess(stdio)+ 内嵌 JDK HttpServer(SSE / STREAMABLE_HTTP)做集成测试。
 */
@SpringBootTest(
    classes = DemoMcpApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private McpTransport mcpTransport;
    @Autowired private McpTransportLifecycle mcpTransportLifecycle;

    @Test
    @DisplayName("AC-021b skeleton: McpTransport + McpTransportLifecycle beans are wired")
    void skeleton_mcpTransportBeanWiring() {
        // McpTransport @Component + McpTransportLifecycle SmartLifecycle 都应被注入
        assertThat(mcpTransport)
            .as("McpTransport @Component must be in Spring container")
            .isNotNull();
        assertThat(mcpTransportLifecycle)
            .as("McpTransportLifecycle SmartLifecycle must be in Spring container")
            .isNotNull();
        // 启动日志样例:No MCP servers configured — McpTransport idle
        // 空配置应让 McpTransport 走 idle 分支(无 connection)
        assertThat(mcpTransport.getConnections())
            .as("Empty mcp config must leave McpTransport idle (no connections)")
            .isEmpty();
    }

    @Test
    @DisplayName("AC-021a + #021c skeleton: McpTransportType has 3 transport values")
    void skeleton_mcpTransportType() {
        // STDIO + SSE + STREAMABLE_HTTP —— Story #021a 落 STDIO,#021c 落 SSE/HTTP
        Set<McpTransportType> all = new HashSet<>(Arrays.asList(McpTransportType.values()));
        assertThat(all)
            .as("McpTransportType must expose all 3 transport flavors")
            .containsExactlyInAnyOrder(
                McpTransportType.STDIO,
                McpTransportType.SSE,
                McpTransportType.STREAMABLE_HTTP);
        assertThat(McpTransportType.values().length)
            .as("McpTransportType must have exactly 3 values (Story #021c contract)")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("AC-021a + #021c skeleton: ConnectionState has 6 lifecycle states")
    void skeleton_connectionState() {
        // IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED —— dsh §6.5 (2.1) L4577
        Set<ConnectionState> all = new HashSet<>(Arrays.asList(ConnectionState.values()));
        assertThat(all)
            .as("ConnectionState must expose all 6 lifecycle states")
            .containsExactlyInAnyOrder(
                ConnectionState.IDLE,
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED,
                ConnectionState.RECONNECTING,
                ConnectionState.FAILED);
        assertThat(ConnectionState.values().length)
            .as("ConnectionState must have exactly 6 values")
            .isEqualTo(6);
    }

    @Test
    @DisplayName("AC-021a skeleton: Factory dispatches STDIO to StdioMcpServerConnection")
    void skeleton_factoryStdio() {
        // stdio 配置 + 任意无效 command —— Stage A 不拉进程,只验证 class 分派
        // connection 应处于 IDLE 态(start() 未调)
        McpServerConfig cfg = McpServerConfig.builder()
            .name("demo-stdio")
            .transport(McpTransportType.STDIO)
            .command("cat")    // 任何 binary;Stage A 不真 start
            .build();
        McpServerConnection conn = McpServerConnectionFactory.create(cfg);
        assertThat(conn)
            .as("Factory.create(STDIO) must return StdioMcpServerConnection")
            .isInstanceOf(StdioMcpServerConnection.class);
        assertThat(conn.name()).isEqualTo("demo-stdio");
        assertThat(conn.state()).isEqualTo(ConnectionState.IDLE);
        assertThat(conn.lastHeartbeatAt())
            .as("No probe has run yet — lastHeartbeatAt must be null")
            .isNull();
        assertThat(conn.listTools())
            .as("Before start(), listTools must be empty (never null)")
            .isNotNull()
            .isEmpty();
    }

    @Test
    @DisplayName("AC-021c skeleton: Factory dispatches SSE to SseMcpServerConnection")
    void skeleton_factorySse() {
        McpServerConfig cfg = McpServerConfig.builder()
            .name("demo-sse")
            .transport(McpTransportType.SSE)
            .url("http://127.0.0.1:65535/sse")  // 无效端口;Stage A 不真连
            .build();
        McpServerConnection conn = McpServerConnectionFactory.create(cfg);
        assertThat(conn)
            .as("Factory.create(SSE) must return SseMcpServerConnection")
            .isInstanceOf(SseMcpServerConnection.class);
        assertThat(conn.name()).isEqualTo("demo-sse");
        assertThat(conn.state()).isEqualTo(ConnectionState.IDLE);
    }

    @Test
    @DisplayName("AC-021c skeleton: Factory dispatches STREAMABLE_HTTP to StreamableHttpMcpServerConnection")
    void skeleton_factoryStreamableHttp() {
        McpServerConfig cfg = McpServerConfig.builder()
            .name("demo-http")
            .transport(McpTransportType.STREAMABLE_HTTP)
            .url("http://127.0.0.1:65535/mcp")  // 无效端口;Stage A 不真连
            .build();
        McpServerConnection conn = McpServerConnectionFactory.create(cfg);
        assertThat(conn)
            .as("Factory.create(STREAMABLE_HTTP) must return StreamableHttpMcpServerConnection")
            .isInstanceOf(StreamableHttpMcpServerConnection.class);
        assertThat(conn.name()).isEqualTo("demo-http");
        assertThat(conn.state()).isEqualTo(ConnectionState.IDLE);
    }
}