package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring lifecycle 桥接 (Story #021b, plan §3.3).
 *
 * <p><b>What</b> — 把 {@link McpTransport#connect} 挂到 Spring 启动阶段,把
 * {@link McpTransport#close} 挂到关闭阶段。{@link McpTransport} 自身是
 * 纯协调者(无 Spring 依赖),{@link McpTransportLifecycle} 负责 Spring 集成层。
 *
 * <p><b>为什么 {@link SmartLifecycle} 而不是 {@code @PostConstruct}</b> —
 * MCP 需要 <b>start + stop 双钩子</b>(connect 在启动期,close 在关闭期),
 * {@link SmartLifecycle} 同时提供;{@code spring-context} 已 transitive,不增依赖。
 * <ul>
 *   <li>{@code @PostConstruct} 来自 {@code javax.annotation-api}(JDK 9+ 内置,JDK 8 需要
 *       单独引入),与 R-13 mitigation philosophy(避免 {@code javax.annotation-api} 依赖)
 *       直接冲突 —— 见 Story #019 {@code LocalToolsAutoConfiguration} 同款 rationale。</li>
 *   <li>{@code @Bean(initMethod = ...)} 单方法启动期不可表达 close,不适合。</li>
 *   <li>{@link SmartLifecycle} 同时给 {@link #start} / {@link #stop} /
 *       {@link #isRunning} / {@link #isAutoStartup} / {@link #getPhase},**spring-context
 *       已 transitive,0 新依赖**。</li>
 * </ul>
 *
 * <p><b>phase 默认</b> — {@link SmartLifecycle} 默认 {@code getPhase() = Integer.MAX_VALUE - 1024},
 * 在所有标准 lifecycle bean 之后启动,先关停(同 phase 内部顺序由 bean name 字典序定)。
 *
 * <p><b>JDK 8 兼容</b> — Plain {@link SmartLifecycle} 实现,无 default method 桥接,
 * 无 {@code var} / sealed / records。
 */
@Component
public class McpTransportLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(McpTransportLifecycle.class);

    private final McpTransport transport;
    private final List<McpServerConfig> configs;
    private final ToolRegistry toolRegistry;
    private volatile boolean running;

    /**
     * Constructor injection (Spring auto-wires {@code mcpServerConfigs} Bean from
     * {@link McpTransportAutoConfiguration}).
     */
    public McpTransportLifecycle(McpTransport transport,
                                  List<McpServerConfig> configs,
                                  ToolRegistry toolRegistry) {
        this.transport = transport;
        this.configs = configs;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void start() {
        transport.connect(configs, toolRegistry);
        running = true;
        LOG.info("McpTransportLifecycle started — McpTransport connected");
    }

    @Override
    public void stop() {
        try {
            transport.close();
        } finally {
            running = false;
        }
        LOG.info("McpTransportLifecycle stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // SmartLifecycle 默认值 — 在所有标准 bean 之后启动 / 先关停
        return Integer.MAX_VALUE - 1024;
    }
}