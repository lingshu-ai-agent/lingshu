package ai.lingshu.examples.democompactor;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #018 demo — TruncatingCompactor + CompactorRouter。
 *
 * <p>main 启动 Spring 上下文;{@code TruncatingCompactorProvider} 通过 SPI 注册;
 * BlackBoxVerificationTest 喂长 history + 调 {@code Compactor.shouldCompact(prompt)} 验证
 * token 估算 + 触发 {@code compact(ctx)} 后 history 缩短。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.democompactor", "ai.lingshu.core"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoCompactorApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoCompactorApplication.class, args);
    }
}