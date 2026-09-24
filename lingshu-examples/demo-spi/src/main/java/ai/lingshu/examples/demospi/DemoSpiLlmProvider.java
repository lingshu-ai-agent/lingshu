package ai.lingshu.examples.demospi;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import org.reactivestreams.Subscriber;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Story #003 demo 自定义 LlmProvider —— echo 模式返回固定文本。
 *
 * <p>本 demo 不模拟 tool call / 流式事件,只验证 SPI 注册路径走通。
 * 真实 LLM 行为覆盖在 demo-engineer / demo-parallel / demo-cancellation 等 demo 中。
 */
public class DemoSpiLlmProvider implements LlmProvider {

    @Override
    public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                Subscriber<? super AgentEvent> sink) {
        return CompletableFuture.completedFuture(
            new LlmResponse(
                "demo-spi: provider wired through SlotProvider SPI",
                Collections.emptyList(),
                StopReason.END_TURN,
                Usage.zero()));
    }
}
