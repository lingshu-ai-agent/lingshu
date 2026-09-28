package ai.lingshu.examples.demoparallel;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #004 demo — LinearTurnEngine 并行 Tool dispatch (AC-03 黑盒).
 *
 * <p>核心场景:
 * <ol>
 *   <li>注册 4 个 {@link SleepTool @Component} 各 sleep 1000ms</li>
 *   <li>{@link DemoParallelLlmProvider} 返回 4 个并行 tool call,然后 END_TURN</li>
 *   <li>{@link AgentFactory#create} 通过 yml {@code agent.tool-parallelism: 4} 启用并发 dispatch</li>
 *   <li>实测 wall-clock ≤ 1.3s(vs 4s 串行 baseline,加速比 ≥ 3×)</li>
 * </ol>
 *
 * <p>本 demo 启动需要 LLM 调用,可用 mock 模式(yaml {@code agent.llm.provider: demo-parallel-echo})
 * 绕过真实 LLM 调用。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demoparallel", "ai.lingshu.core"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoParallelApplication implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoParallelApplication.class);

    private final AgentFactory agentFactory;

    public DemoParallelApplication(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoParallelApplication.class, args);
    }

    @Override
    public void run(String... args) {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        Agent agent = agentFactory.create(cfg);
        long start = System.nanoTime();
        RunResult result = agent.runBlocking("dispatch 4 parallel tools");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("══════════════════════════════════════════════════════════");
        System.out.println("Parallel Dispatch Result:");
        System.out.println("  Elapsed       : " + elapsedMs + " ms");
        System.out.println("  Stop reason   : " + result.getStopReason());
        System.out.println("  Total turns   : " + result.getTurns());
        System.out.println("══════════════════════════════════════════════════════════");
    }
}
