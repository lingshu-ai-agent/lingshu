package ai.lingshu.examples.democancellation;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #005 demo — CancellationToken 三层贯通(AC-04 黑盒 200ms 协作式取消)。
 *
 * <p>Spring Boot main;具体 cancel 测试逻辑在 BlackBoxVerificationTest 里通过
 * {@code AgentFactory.broadcastCancel()} 触发。
 *
 * <p>main 不直接跑 turn(避免阻塞主线程等待 cancel),改在 BlackBoxVerificationTest
 * 通过子线程异步触发 cancellation。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.democancellation", "ai.lingshu.core"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoCancellationApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoCancellationApplication.class, args);
    }
}
