package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.McpTransportType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * McpTransport 启动接线 (Story #021b, plan §3.4).
 *
 * <p><b>职责</b> — 把 {@link AgentConfig.Mcp#servers}(YAML 绑定层,#021a 扩 5 字段)
 * 解构为 {@link McpTransport} 需要的 {@link McpServerConfig} runtime config 列表。
 *
 * <p><b>为什么不用 {@code @ConfigurationProperties}</b> —
 * {@link McpServerConfig} 是 Lombok {@code @Value} POJO(JDK 8 不允许 record),
 * 没有 Spring Boot 元数据;从 {@link AgentConfig.ServerConfig} 手写 to-runtime 转换,
 * **0 依赖**,符合 lingshu-core R-13 mitigation philosophy。
 *
 * <p><b>Bean 名约定</b> — {@code "mcpServerConfigs"} 显式 Bean 名(对齐 dsh §5.4
 * 「唯一 Bean 名约定」,Story #021a 后的多 Provider 模式)。{@link McpTransportLifecycle}
 * 按 type 注入(非 by name),本 Bean 名仅供调试 / 显式查找。
 *
 * <p><b>关键不变项</b> — {@link AgentConfig.ServerConfig}(Story #021a 落地,扩 5 字段)
 * <b>0 改动</b>。
 *
 * <p><b>JDK 8 兼容</b> — Plain Spring {@code @Configuration},无 default method / sealed。
 */
@Configuration
public class McpTransportAutoConfiguration {

    /** 显式 Bean 名,符合 dsh §5.4「唯一 Bean 名约定」。 */
    public static final String MCP_SERVER_CONFIGS_BEAN = "mcpServerConfigs";

    /**
     * 从 {@link AgentConfig} 解构 runtime config 列表。
     *
     * <p>空配置(无 {@code agent.mcp} / servers 为空)返回 {@link Collections#emptyList()};
     * {@link McpTransport#connect} 会打 INFO 日志 "idle"。
     *
     * @param agentConfig {@link AgentConfig} Spring Bean;null 返回 empty list
     * @return unmodifiable 列表(可能是 empty)
     */
    @Bean(name = MCP_SERVER_CONFIGS_BEAN)
    public List<McpServerConfig> mcpServerConfigs(AgentConfig agentConfig) {
        if (agentConfig == null || agentConfig.getMcp() == null
                || agentConfig.getMcp().getServers() == null
                || agentConfig.getMcp().getServers().isEmpty()) {
            return Collections.emptyList();
        }
        List<McpServerConfig> out = new ArrayList<>();
        for (AgentConfig.ServerConfig sc : agentConfig.getMcp().getServers()) {
            out.add(toRuntimeConfig(sc));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * AgentConfig.ServerConfig (YAML) → McpServerConfig (runtime) 转换器。
     *
     * <p>三个心跳参数 {@code heartbeatIntervalMs / heartbeatTimeoutMs / reconnectCapMs}
     * 仅在 {@code > 0} 时覆盖;否则走 {@link McpServerConfig} 的 {@code @Builder.Default}
     * 默认值(30s / 10s / 60s)。
     */
    private McpServerConfig toRuntimeConfig(AgentConfig.ServerConfig sc) {
        McpServerConfig.McpServerConfigBuilder b = McpServerConfig.builder()
            .name(sc.getName())
            .transport(sc.getTransport() != null
                ? sc.getTransport()
                : McpTransportType.STDIO)   // null transport → 默认 stdio
            .command(sc.getCommand())
            .args(sc.getArgs() != null
                ? sc.getArgs()
                : Collections.<String>emptyList())
            .env(sc.getEnv() != null
                ? sc.getEnv()
                : Collections.<String, String>emptyMap())
            .url(sc.getUrl());
        if (sc.getHeartbeatIntervalMs() > 0) {
            b.heartbeatIntervalMs(sc.getHeartbeatIntervalMs());
        }
        if (sc.getHeartbeatTimeoutMs() > 0) {
            b.heartbeatTimeoutMs(sc.getHeartbeatTimeoutMs());
        }
        if (sc.getReconnectCapMs() > 0) {
            b.reconnectCapMs(sc.getReconnectCapMs());
        }
        return b.build();
    }
}