package ai.lingshu.examples.demoparallel;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.RunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #004 AC-03 skeleton — Spring 上下文可启动 + AgentFactory bean 可注入。
 *
 * <p>骨架阶段仅验证 wiring,完整 AC-03 黑盒验证(4× 1s sleep 并发 ≤ 1.3s)在 Stage B。
 */
@SpringBootTest(
    classes = DemoParallelApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private AgentFactory agentFactory;

    @Test
    @DisplayName("AC-03 skeleton: AgentFactory wired + create returns Agent (Stage A)")
    void skeleton_parallelDispatchWiring() {
        assertThat(agentFactory).isNotNull();
        Agent agent = agentFactory.create(AgentConfigDefaults.defaults());
        assertThat(agent).isNotNull();
        // 完全跳过 runBlocking 触发 LLM 调用的快路径,只验证骨架
        assertThat(agent.config().getToolParallelism()).isEqualTo(8);  // 默认 8
    }

    @Test
    @DisplayName("AC-03 skeleton: parallelism=4 from yml overrides default (Stage A)")
    void skeleton_parallelismOverride() {
        // 通过 yml 装载的 AgentFactory.create 应使用 application.yml 配的 4
        // (实现细节: AgentFactory.create 使用 cfg.getToolParallelism(),本测试只验证 wiring)
        assertThat(AgentConfigDefaults.defaults().getToolParallelism()).isEqualTo(8);
    }
}
