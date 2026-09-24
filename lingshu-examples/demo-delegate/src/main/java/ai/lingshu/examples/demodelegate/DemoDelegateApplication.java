package ai.lingshu.examples.demodelegate;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #022 + #023 + #024 demo — DelegateTool (Task tool) + @AgentTool + [TOOL SCHEMAS] 段 wiring。
 *
 * <p>Stage A 骨架只验证 Spring 上下文能启动 + DelegateTool / SubAgentType wiring 可见;Stage B
 * 完整 AC 黑盒覆盖(SubAgentType 3 值 enum 遍历 + DelegateTool execute sub-agent 派发 +
 * DefaultPromptBuilder [TOOL SCHEMAS] 段注入 tool schemas)。
 *
 * <p>本 demo 不调用真实 LLM,用 {@link DemoDelegateEchoLlmProvider} 模拟
 * "LLM 发出 Task tool call → 引擎 dispatch DelegateTool" 路径。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demodelegate", "ai.lingshu.core"},
    // 排除:
    //   - YamlWatcher(Story #007 hot reload daemon —— 本 demo 不验证 hot-reload)
    //   - AgentToolScanner(setApplicationContext 阶段调 ctx.getBeansWithAnnotation(Component.class)
    //     自引用产生 circular reference —— 与 demo-spi 同样的兜底;Stage A 只验证 wiring 可见,
    //     Stage B 加 @Component + @AgentTool method 时再单独 wire 该 scanner 跑一次 scan 验证)
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoDelegateApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoDelegateApplication.class, args);
    }
}
