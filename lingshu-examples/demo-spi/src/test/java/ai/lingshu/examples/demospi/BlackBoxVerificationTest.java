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
package ai.lingshu.examples.demospi;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #003 AC-02 — SPI 多 Provider 解析 + AgentFactory self-describe.
 *
 * <p>骨架阶段(Stage A)只验证 Spring 上下文能启动 + 自定义 Provider 通过 SPI 注册。
 * 完整 AC 黑盒覆盖(version mismatch 抛 LINGS-S05 / yml 切换 Provider 等)留 Stage B。
 *
 * <p>骨架测试通过标准:
 * <ol>
 *   <li>{@link AgentFactory} bean 可注入</li>
 *   <li>{@link Routers.LlmProviderRouter} bean 可注入</li>
 *   <li>{@link Routers.LlmProviderRouter#available()} 包含 demo-spi(自定义)+ anthropic(默认)</li>
 *   <li>{@link AgentFactory#description()} 输出非空且包含 "demo-spi"</li>
 * </ol>
 */
@SpringBootTest(
    classes = DemoSpiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private AgentFactory agentFactory;
    @Autowired private Routers.LlmProviderRouter llmRouter;

    @Test
    @DisplayName("AC-02 skeleton: SPI multi-Provider wiring visible (Stage A)")
    void skeleton_spiMultiProviderWiring() {
        // AgentFactory 6 Router 全部就位
        assertThat(agentFactory).isNotNull();

        // LlmProviderRouter 至少包含 2 个 Provider:默认 anthropic + demo-spi 自定义
        assertThat(llmRouter.available())
            .as("Router should discover both default + custom Providers")
            .contains("anthropic", "demo-spi");

        // AgentFactory.description() 自描述输出非空且包含 demo-spi
        String desc = agentFactory.description();
        assertThat(desc).isNotNull().isNotEmpty();
        assertThat(desc).contains("demo-spi");
    }
}
