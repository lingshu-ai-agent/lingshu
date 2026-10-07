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
package ai.lingshu.examples.demodelegate;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Story #023 demo —— {@link DemoDelegateEchoLlmProvider} 的 SPI Provider 包装。
 *
 * <p>{@code name="demo-delegate"} 与默认 anthropic provider 严格命名空间隔离,yml
 * {@code agent.llm.provider: demo-delegate} 切换即用。
 */
@Component
public class DemoDelegateEchoLlmProviderProvider implements Providers.LlmProviderProvider {

    @Override public String name() { return "demo-delegate"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public LlmProvider create(AgentConfig config) {
        return new DemoDelegateEchoLlmProvider();
    }
}
