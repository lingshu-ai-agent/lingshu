package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.mcp.ConnectionState;
import ai.lingshu.core.mcp.McpCallResult;
import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.mcp.McpServerConnection;
import ai.lingshu.core.mcp.McpServerConnectionFactory;
import ai.lingshu.core.mcp.McpToolAdapter;
import ai.lingshu.core.mcp.McpToolDescriptor;
import ai.lingshu.core.mcp.McpTransportException;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP server 总装组件 (Story #021b, dsh §6.5 (2) L4454-4551).
 *
 * <p><b>职责</b> (dsh §6.5 (2) L4511-4551):
 * <ol>
 *   <li>持有 {@code List<McpServerConnection> connections},每个 MCP server 一份</li>
 *   <li>{@link #connect(List, ToolRegistry)} 按 cfg 走 {@link McpServerConnectionFactory}
 *       拉连接,挂 listener,异步 start</li>
 *   <li>{@link #callTool(String, String, JsonNode)} 转发 tool call 到对应 server 的 connection</li>
 *   <li>状态变化回调:CONNECTED → {@code registry.register(new McpToolAdapter(...))} × N;
 *       DISCONNECTED/FAILED → {@code registry.unregister(...)} × N</li>
 * </ol>
 *
 * <p><b>为什么这里</b> — {@link McpServerConnection}(Story #021a)只解决
 * 「单 server 长生命周期 + 心跳保活 + 指数退避」,但没有协调者把 N 个 server
 * 接到共享 {@link ToolRegistry}。本类是 §6.5 (2) 总装组件,**唯一**调用方是
 * {@link McpTransportLifecycle}(启动期 {@link #connect}) + {@link McpToolAdapter}(运行期
 * {@link #callTool})。
 *
 * <p><b>Listener 模式</b> — 每个 connection 在 {@code start()} 之前先注册 listener
 * (plan §3.1.2 + §7 R-021b-03)。{@code StdioMcpServerConnection#transition} 内部
 * listener 异常隔离已就位 (Story #021a §T-15 verified),本类自身 listener body 内部
 * try/catch 隔离单 tool register/unregister 失败(plan §7 R-021b-02)。
 *
 * <p><b>命名空间</b> — Tool 名走 {@code serverName + ":" + toolName} 形式
 * (e.g. {@code "github:search_repos"}),避免与本地 Read/Write/Edit/Bash
 * 撞名。{@link McpToolAdapter} 内部已知 {@code serverName},
 * {@code execute} 时 {@code transport.callTool("github", "search_repos", input)} 转发。
 *
 * <p><b>与 §4.10.1 硬规则 2 兼容</b> — {@link McpToolAdapter#execute} 拿到的永远是
 * {@link McpCallResult}(success/error 包装),{@code ToolExecutor.dispatch} 走 5 步流水线
 * (Permission → Registry → Timeout → Sandbox → Execute → Checkpoint)不绕过任何一步,
 * MCP 断线只是「error 替代 success」。
 *
 * <p><b>JDK 8 兼容</b> — {@code @Component},{@link CopyOnWriteArrayList},
 * 无 {@code var} / {@code List.of} / sealed / records。
 */
@Component
public class McpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(McpTransport.class);

    private final List<McpServerConnection> connections = new CopyOnWriteArrayList<>();

    /**
     * 启动期调用,按 cfg 拉连接 + 挂 listener + 异步 start。
     *
     * <p><b>Idempotent enough</b> — 同一 {@code McpTransport} 实例可被多次调用;
     * 但每次调用会 add N 个 connection 到内部 list。{@link McpTransportLifecycle}
     * 默认只在 ApplicationContext 启动期调一次。
     *
     * <p><b>失败处理</b> — {@link McpServerConnectionFactory#create} 抛
     * {@link McpTransportException} (e.g. {@code LINGS-M01} for SSE/HTTP)→ 打 ERROR 日志,
     * 不重试,等 {@code Story #021c} 落 SSE/HTTP。后续 listener 不会被触发,此 cfg 的 tool
     * 不会注册到 {@code registry}。
     *
     * @param configs   runtime config 列表(McpServerConfig);null 或 empty → 静默 idle
     * @param registry  共享 ToolRegistry Bean;CONNECTED 时注册, DISCONNECTED 时注销
     * @throws IllegalStateException 仅当 {@code registry} 为 null(参数校验)
     */
    public void connect(List<McpServerConfig> configs, ToolRegistry registry) {
        if (registry == null) {
            throw new IllegalStateException("ToolRegistry must not be null");
        }
        if (configs == null || configs.isEmpty()) {
            LOG.info("No MCP servers configured — McpTransport idle");
            return;
        }
        for (McpServerConfig cfg : configs) {
            try {
                // 🆕 Story #021b:用 factory 按 transport 分派(stdio 已实现,SSE/HTTP 抛 LINGS-M01)
                McpServerConnection conn = McpServerConnectionFactory.create(cfg);
                // listener **必须**在 start() 之前注册 —— 否则首次 transition 时收不到事件(plan §7 R-021b-03)
                conn.onStateChange(state -> onConnectionStateChange(cfg, conn, state, registry));
                connections.add(conn);
                conn.start();   // 异步非阻塞,首次 CONNECTED 由 listener 触发 register
            } catch (McpTransportException e) {
                LOG.error("[MCP:{}] factory rejected transport={}: code={} msg={}",
                    cfg.getName(), cfg.getTransport(), e.getCode(), e.getMessage());
                // 不重试 —— 等 Story #021c 落 SSE/HTTP
            }
        }
        LOG.info("McpTransport connected to {} server(s): {}",
            connections.size(), summarizeNames());
    }

    /**
     * 状态变化回调 —— CONNECTED 注册 tools,DISCONNECTED/FAILED 注销 tools。
     *
     * <p><b>中间态不变 registry</b>(plan §3.1.3)—— CONNECTING / RECONNECTING / IDLE
     * 不动 registry,避免抖动;只在 CONNECTED 与 DISCONNECTED/FAILED 两个终态动。
     *
     * <p><b>异常隔离</b>(plan §7 R-021b-02)—— 每个 tool 单点 try/catch,一个 tool
     * register 失败不影响其他 tool。{@link McpServerConnection#listTools} 由 caller
     * 保证非 null,本方法不会 NPE。
     */
    /**
     * Package-private for unit tests (Story #021b, T-11).
     *
     * <p>Production caller is the {@code Consumer<ConnectionState>} registered
     * in {@link #connect(List, ToolRegistry)}. Tests in {@code ai.lingshu.core.impl.mcp}
     * invoke this directly with a stubbed connection.
     */
    void onConnectionStateChange(McpServerConfig cfg, McpServerConnection conn,
                                  ConnectionState state, ToolRegistry registry) {
        if (state == ConnectionState.CONNECTED) {
            int registered = 0;
            for (McpToolDescriptor t : conn.listTools()) {
                try {
                    String namespacedName = cfg.getName() + ":" + t.getName();
                    registry.register(new McpToolAdapter(this, cfg.getName(), namespacedName, t));
                    registered++;
                } catch (Exception e) {
                    LOG.warn("[MCP:{}] register tool {} failed: {}",
                        cfg.getName(), t.getName(), e.toString());
                }
            }
            LOG.info("[MCP:{}] (re)connected, {} tools registered", cfg.getName(), registered);
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) {
            int unregistered = 0;
            for (McpToolDescriptor t : conn.listTools()) {
                try {
                    String namespacedName = cfg.getName() + ":" + t.getName();
                    if (registry.unregister(namespacedName)) {
                        unregistered++;
                    }
                } catch (Exception e) {
                    LOG.warn("[MCP:{}] unregister tool {} failed: {}",
                        cfg.getName(), t.getName(), e.toString());
                }
            }
            LOG.warn("[MCP:{}] disconnected ({}), {} tools unregistered",
                cfg.getName(), state, unregistered);
        }
        // CONNECTING / RECONNECTING / IDLE → 不动 registry(中间态,避免抖动)
    }

    /**
     * 转发 tool call 到对应 server 的 connection。
     *
     * @param serverName MCP server 名(匹配 {@code McpServerConnection#name()})
     * @param toolName   远端 tool 名(去除 namespace,e.g. {@code "search_repos"} 非
     *                   {@code "github:search_repos"})
     * @param input      JSON args,匹配 tool 的 {@code McpToolDescriptor.inputSchema}
     * @return success/error 结果;永不 null
     * @throws IllegalStateException 找不到对应 {@code serverName}
     */
    public McpCallResult callTool(String serverName, String toolName, JsonNode input) {
        McpServerConnection conn = findConnection(serverName);
        if (conn == null) {
            throw new IllegalStateException("Unknown MCP server: " + serverName);
        }
        return conn.callTool(toolName, input);
    }

    /** 优雅停机 —— 关闭所有 connection,清理 connections 列表。幂等。 */
    public void close() {
        LOG.info("McpTransport shutting down — closing {} connection(s)", connections.size());
        // 快照避免 CopyOnWriteArrayList 并发修改
        for (McpServerConnection conn : new ArrayList<>(connections)) {
            try {
                conn.close();
            } catch (Exception e) {
                LOG.warn("[MCP:{}] close() failed: {}", conn.name(), e.toString());
            }
        }
        connections.clear();
    }

    /** 当前持有的 connection 列表(只读快照)—— 用于测试 / 健康检查。 */
    public List<McpServerConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    private McpServerConnection findConnection(String serverName) {
        for (McpServerConnection c : connections) {
            if (c.name().equals(serverName)) {
                return c;
            }
        }
        return null;
    }

    private String summarizeNames() {
        List<String> names = new ArrayList<>(connections.size());
        for (McpServerConnection c : connections) {
            names.add(c.name());
        }
        return names.toString();
    }
}