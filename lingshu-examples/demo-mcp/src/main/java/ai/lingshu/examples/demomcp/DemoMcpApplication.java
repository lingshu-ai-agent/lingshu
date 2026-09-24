package ai.lingshu.examples.demomcp;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #021a + #021b + #021c demo — MCP 3 transport + Tool adapter + Factory dispatch 黑盒 wiring。
 *
 * <p>Stage A 骨架只验证:
 * <ol>
 *   <li>Spring Boot main 启动成功</li>
 *   <li>{@code McpTransport} + {@code McpTransportLifecycle} Bean 由 lingshu-core 注入容器</li>
 *   <li>{@code McpServerConnectionFactory.create(cfg)} 能按 transport 分派到
 *       stdio / SSE / streamable HTTP 三个 concrete 实现</li>
 *   <li>{@code ConnectionState} 6 态 enum 完整</li>
 * </ol>
 *
 * <p>完整 AC 黑盒覆盖(MCP 子进程拉起 + 指数退避 + tool 重连 + dispatch)在 Stage B 用 mock
 * subprocess(stdio)+ 内嵌 HttpServer(SSE / STREAMABLE_HTTP)做集成测试。
 *
 * <p>本 demo 不配置任何 {@code agent.mcp.servers} —— 默认空配置让 {@code McpTransport} 走
 * "idle" 分支,避免启动期拉不存在的 MCP server 卡住测试。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demomcp", "ai.lingshu.core"},
    // 排除:
    //   - YamlWatcher(Story #007 hot reload daemon —— 本 demo 不验证 hot-reload)
    //   - AgentToolScanner(setApplicationContext 阶段 ctx.getBeansWithAnnotation 自引用 circular ref)
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoMcpApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoMcpApplication.class, args);
    }
}