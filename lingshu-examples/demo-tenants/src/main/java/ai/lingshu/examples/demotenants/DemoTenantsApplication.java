package ai.lingshu.examples.demotenants;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #006 demo — TenantContext ThreadLocal 4 维隔离
 * (nested / exception-safe / cross-thread / null-validation)。
 *
 * <p>main 启动 Spring 上下文 + agentFactory + tenants 注入;
 * 4 维隔离行为测试在 BlackBoxVerificationTest 跑(避免 main 被 ThreadLocal 状态污染)。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demotenants", "ai.lingshu.core"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoTenantsApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoTenantsApplication.class, args);
    }
}