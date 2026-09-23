package ai.lingshu.examples.demolocaltools;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #019 — minimal Spring Boot main that triggers
 * {@code LocalToolsAutoConfiguration#afterPropertiesSet()} on context refresh.
 *
 * <p>The wiring test ({@code LocalToolsWiringTest}) is the AC verification path;
 * this class exists so {@code ./mvn spring-boot:run} also produces the
 * "LocalTools ready — 4 tool(s) registered: [Bash, Edit, Read, Write]" log line
 * a user can eyeball against the design doc.
 *
 * <p>{@code @ComponentScan} pulls in {@code ai.lingshu.core} so the 4
 * {@code @Component} Tools + {@code DefaultRuntimeSandbox} + the registry /
 * executor Providers participate in the same context as this demo's own beans.
 *
 * <p><b>Why expose an {@code AgentConfig @Bean}:</b> component-scan picks up
 * {@code YamlTenantConfigProvider} (a {@code @Component} in
 * {@code ai.lingshu.core.impl.tenant} that constructor-injects
 * {@code AgentConfig}). Without a bean of that type in the context, Spring
 * bootstrap fails with
 * {@code UnsatisfiedDependencyException: ...required a bean of type 'AgentConfig'}.
 * The {@code @Bean} below supplies the canonical defaults — matches the
 * "demo-engineer" pattern (Story #002 same pattern, same rationale).
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demolocaltools", "ai.lingshu.core"},
    // Exclude YamlWatcher: it's the Story #007 daemon that hot-reloads agent.yml
    // and requires a fully-configured AgentFactory + ~70 args to construct.
    // This demo is a wiring verification, not a hot-reload one.
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = YamlWatcher.class))
public class DemoLocalToolsApplication {

    /**
     * Canonical default {@link AgentConfig} bean — feeds every
     * {@code AgentConfig}-typed constructor parameter in {@code ai.lingshu.core}
     * (e.g. {@code YamlTenantConfigProvider}, the 4 MemorySource Providers).
     *
     * <p>Real runtime config still comes from {@code AgentFactory.create(cfg)}
     * (the user passes their own {@link AgentConfig}); this bean is only a
     * bootstrap-time stub so Spring can resolve the dependency graph.
     */
    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoLocalToolsApplication.class, args);
    }
}
