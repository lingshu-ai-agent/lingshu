package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Story #025 demo — comprehensive HTTP streaming product.
 *
 * <p>Combines 8 features in one Spring Boot web app:
 * <ol>
 *   <li>{@link ChatController} — SSE chat endpoint</li>
 *   <li>{@link SessionRegistry} — multi-turn sessionId → Agent map</li>
 *   <li>{@link AgentEventMapper} — 12 AgentEvent → SSE event names</li>
 *   <li>Local {@code @Component} Tools (read/write/list/bash_safe)</li>
 *   <li>{@code @AgentTool} methods (time/calc/random/uuid)</li>
 *   <li>Skill markdown sources (/help /clear /compact)</li>
 *   <li>Delegate sub-agent config (explore / engineer / reviewer)</li>
 *   <li>Compactor token threshold + browser event panel</li>
 * </ol>
 *
 * <p>Unlike {@code demo-empty} which uses {@code CommandLineRunner} and exits
 * before the web layer starts, this demo keeps the embedded Tomcat running so
 * the browser UI at {@code http://localhost:8080} is interactive.
 */
@SpringBootApplication
public class DemoProductApplication {

    private static final Logger LOG = LoggerFactory.getLogger(DemoProductApplication.class);

    /**
     * Expose {@link AgentConfig} as a Spring bean so that
     * {@code YamlTenantConfigProvider} (Story #006) can inject it.
     * Mirrors the pattern in {@code demo-tenants/DemoTenantsApplication.java:27-30}.
     * {@link ChatController} does <b>not</b> use this bean — each session
     * builds its own config via {@link AgentConfigDefaults#defaults()}.
     */
    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoProductApplication.class, args);
        LOG.info("demo-product ready — open http://localhost:8080");
    }
}