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

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import ai.lingshu.core.impl.config.AgentConfigDefaults;

/**
 * Story #003 demo — SPI 多 Provider 解析 + 启动期版本兼容校验。
 *
 * <p>AC-02 黑盒契约:
 * <ol>
 *   <li>注册自定义 {@link DemoSpiLlmProvider} + {@link DemoSpiLlmProviderProvider}</li>
 *   <li>Spring 启动期自动发现所有 {@code @Bean Providers.LlmProviderProvider},装配到
 *       {@code Routers.LlmProviderRouter} 内部 {@code Map<String, P>}</li>
 *   <li>{@link AgentFactory#description()} 输出所有 Slot Provider(name / version / priority)清单</li>
 *   <li>yml {@code agent.llm.provider: demo-spi} 切换到自定义 Provider,Router 按 name 解析</li>
 *   <li>(扩展点)启动期版本不兼容 → 抛 {@code LINGS-S05} 启动失败</li>
 * </ol>
 *
 * <p>本 demo 不调用真实 LLM,只展示 SPI 多 Provider 共存机制。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demospi", "ai.lingshu.core"},
    // 排除 Story #007 hot reload daemon + Story #022 @AgentTool scanner
    // (后者在 setApplicationContext 调 ctx.getBeansWithAnnotation 时自引用产生循环依赖,
    //  本 demo 不需要这些特性,直接排除)
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoSpiApplication implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoSpiApplication.class);

    private final AgentFactory agentFactory;

    public DemoSpiApplication(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoSpiApplication.class, args);
    }

    @Override
    public void run(String... args) {
        // 打印 AgentFactory 自描述(Story #003 AC-02 US1 Scenario 1)
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.println("AgentFactory.description() output:");
        System.out.println("──────────────────────────────────────────────────────────");
        System.out.println(agentFactory.description());
        System.out.println("══════════════════════════════════════════════════════════");
    }
}
