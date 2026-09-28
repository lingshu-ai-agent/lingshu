package ai.lingshu.examples.demospi;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Story #003 demo —— {@link DemoSpiLlmProvider} 的 SPI Provider 包装。
 *
 * <p>4 项元数据(name / priority / version / create)按 {@link ai.lingshu.core.spi.SlotProvider}
 * 契约提供。{@code name="demo-spi"} 与默认 Anthropic provider("anthropic")严格命名空间隔离,
 * yml {@code agent.llm.provider: demo-spi} 切换即用。
 *
 * <p>{@code version="1.0.0"} 必须与 LlmProvider slot 的 CONTRACT_VERSION major 一致 —— 由
 * {@code SlotRouter} 构造期校验,版本不兼容直接抛 {@code LINGS-S05} 启动失败。
 */
@Component
public class DemoSpiLlmProviderProvider implements Providers.LlmProviderProvider {

    @Override public String name() { return "demo-spi"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public LlmProvider create(AgentConfig config) {
        return new DemoSpiLlmProvider();
    }
}
