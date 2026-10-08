/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.examples.democancellation;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #005 AC-04 skeleton — Spring 上下文启动 + cancellation Provider 注册 + AgentFactory 注入。
 *
 * <p>骨架阶段不触发真实 cancellation(避免主线程被 3s safety-net 阻塞),仅验证 wiring。
 * 完整 AC-04 黑盒在 Stage B:
 * <pre>{@code
 * Thread t = new Thread(() -> {
 *     Agent agent = factory.create(AgentConfigDefaults.defaults());
 *     RunResult r = agent.runBlocking("never returns");
 *     assertThat(r.getStopReason()).isEqualTo(StopReason.CANCELLED);
 * });
 * t.start();
 * Thread.sleep(50);
 * AgentFactory.broadcastCancel();
 * t.join(200);
 * assertThat(!t.isAlive());  // AC-04: cancel→exit ≤ 200ms
 * }</pre>
 */
@SpringBootTest(
    classes = DemoCancellationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private AgentFactory agentFactory;
    @Autowired private Routers.LlmProviderRouter llmRouter;

    @Test
    @DisplayName("AC-04 skeleton: cancellation-slow LLM Provider wired (Stage A)")
    void skeleton_cancellationSlowProviderWired() {
        assertThat(agentFactory).isNotNull();
        // demo-cancellation-slow 已通过 SPI 注册,默认 anthropic 也并存
        assertThat(llmRouter.available())
            .as("LlmProviderRouter should have demo-cancellation-slow + default anthropic")
            .contains("demo-cancellation-slow", "anthropic");
    }

    @Test
    @DisplayName("AC-04 skeleton: AgentFactory.create returns Agent (Stage A)")
    void skeleton_agentCreate() {
        Agent agent = agentFactory.create(AgentConfigDefaults.defaults());
        assertThat(agent).isNotNull();
    }
}
