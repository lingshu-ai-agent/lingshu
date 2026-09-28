package ai.lingshu.examples.demomaxsteps;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #008 demo — MaxStepsExceeded 事件发射 + LinearTurnEngine 终止路径。
 *
 * <p>main 启动 Spring 上下文;{@code EchoLlmProvider} + {@code AlwaysToolCallLlmProvider}
 * 反复 emit tool call → engine 触发 ReAct loop → 超过 max-steps=3 后发射
 * {@code MaxStepsExceeded} 事件 + TurnCompleted(stopReason=MAX_STEPS)。
 *
 * <p>BlackBoxVerificationTest 通过订阅 {@code agent.events()} 收集事件序列 assert 11 事件 + MaxStepsExceeded 在末尾。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demomaxsteps", "ai.lingshu.core"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoMaxStepsApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoMaxStepsApplication.class, args);
    }
}