package ai.lingshu.examples.demoa2a;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.a2a.client.RemoteAgentToolAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #009 + #009a—#009e demo — A2A server (AgentCard) + 3 transport client
 * (HTTP-JSON-RPC / gRPC / InProcess) + RemoteAgentTool wiring。
 *
 * <p>Stage A 骨架只验证:
 * <ol>
 *   <li>Spring Boot main + 内嵌 {@code lingshu-a2a-server} 启动成功</li>
 *   <li>{@code http://localhost:8080/.well-known/agent.json} 返回 AgentCard JSON</li>
 *   <li>{@link ai.lingshu.core.impl.router.A2aTransportRouter} 可见且 3 transport Provider 同存</li>
 * </ol>
 *
 * <p>完整 AC 黑盒覆盖(transport 切换 / RemoteAgentTool 调用 / schema builder 动态生成)留 Stage B。
 */
@SpringBootApplication(exclude = {
    // RemoteAgentToolAutoConfiguration 用 agentConfig() 的 a2aTransport="default" 解析,
    // 但 Router 实际 provider 名是 grpc-1.0.0/http-jsonrpc-1.0.0/in-process-1.0.0 → 启动失败
    // (Stage B 用 application.yml 显式设 a2aTransport 或程序化 wire 时再开启)
    RemoteAgentToolAutoConfiguration.class
})
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demoa2a", "ai.lingshu.core", "ai.lingshu.a2a.server"},
    // 排除:
    //   - YamlWatcher(Story #007 hot reload daemon —— 本 demo 不验证 hot-reload)
    //   - AgentToolScanner(setApplicationContext 阶段 ctx.getBeansWithAnnotation 自引用 circular ref)
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoA2aApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoA2aApplication.class, args);
    }
}
