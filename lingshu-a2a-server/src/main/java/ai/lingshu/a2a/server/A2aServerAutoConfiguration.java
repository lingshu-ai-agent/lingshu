package ai.lingshu.a2a.server;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.ToolRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 🆕 Story #009 — Spring Boot SPI registration for {@link A2aServer}.
 *
 * <p>Wires the {@code A2aServer} bean from the validated {@link AgentConfig} that
 * {@code AgentFactory.loadYamlAndValidate()} already produced and the shared
 * {@link ToolRegistry} that {@code LocalToolsAutoConfiguration} exposes. The bean
 * {@link A2aServer#start()} method (annotated {@code @PostConstruct}) takes care
 * of binding the JDK {@code HttpServer}; this class is just the assembly point.
 *
 * <p>🆕 Story a2a-server-tool-registry-dispatch — the {@code A2aServer} now needs
 * the {@link ToolRegistry} to dispatch inbound {@code message/send} JSON-RPC calls
 * to registered local {@link ai.lingshu.core.slot.Tool Tools} and to advertise
 * them in the AgentCard's {@code skills[]} list. Spring auto-wires the registry
 * bean (a process-wide singleton from {@code LocalToolsAutoConfiguration}).
 *
 * <p>No {@code @ConditionalOnMissingBean} (per dsh §5.5 v1.5.28 multi-Provider
 * convention) — the {@code A2aServer} is bound 1:1 to {@code AgentConfig} so there
 * is no parallel Provider to compete with. If a future Story needs a non-JDK
 * transport (gRPC / in-process), it should sit alongside, not replace this bean.
 */
@AutoConfiguration
public class A2aServerAutoConfiguration {

    @Bean(name = "a2aServer", initMethod = "start", destroyMethod = "stop")
    public A2aServer a2aServer(AgentConfig cfg, ToolRegistry toolRegistry) {
        return new A2aServer(cfg, toolRegistry);
    }
}