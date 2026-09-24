package ai.lingshu.examples.demoread;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.reload.AgentConfigRegistry;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;


/**
 * Story #007 demo — AgentConfigRegistry AtomicReference + freeze semantics + YamlWatcher。
 *
 * <p><b>本 demo 不 exclude YamlWatcher</b>(默认 5s 轮询 + 启动期捕获 initial mtime);
 * 通过 {@code agent.yaml-watcher.poll-interval-seconds: 1} 配置 1s 轮询,缩短测试等待时间。
 * BlackBoxVerificationTest 通过 {@code Files.touch()} 模拟 yml 修改 + 验证 {@code ConfigChangeListener} 触发。
 *
 * <p><b>YamlWatcher 手动 wire</b>:本 demo 显式定义 {@code @Bean yamlWatcher(...)} 解决
 * Spring 6 多 constructor 自动选择歧义(framework 默认行为是单 public ctor 自动注入,
 * 但 YamlWatcher 双 ctor 设计让 Spring 6 选不到正确的那个)。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demoread", "ai.lingshu.core"})
public class DemoReloadApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    /**
     * Manual YamlWatcher bean to bypass Spring 6 multi-constructor ambiguity.
     * Replicates the framework's intended wiring: yml path from properties,
     * AgentConfigRegistry + AgentFactory injected.
     */
    @Bean
    public YamlWatcher yamlWatcher(
            @Value("${spring.config.location:application.yml}") String ymlPathValue,
            AgentConfigRegistry registry,
            AgentFactory factory) {
        return new YamlWatcher(ymlPathValue, registry, factory);
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoReloadApplication.class, args);
    }
}