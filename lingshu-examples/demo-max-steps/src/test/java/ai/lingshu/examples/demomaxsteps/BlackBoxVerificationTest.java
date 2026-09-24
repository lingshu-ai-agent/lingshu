package ai.lingshu.examples.demomaxsteps;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #008 AC-07 skeleton — LinearTurnEngine 在 ReAct loop 超过 max-steps 时发射
 * {@code MaxStepsExceeded} 事件。
 *
 * <p><b>Skeleton 限制</b>:本骨架只验证 scripted LLM Provider wired + returns expected shape;
 * 完整 AC-07 black-box(MaxStepsExceeded event fired + TurnCompleted(stopReason=END_TURN per FR-004))
 * 在 Stage B 通过 {@code loadYamlAndValidate} 加载 {@code application.yml} max-steps=3 配置跑 ——
 * AgentConfigDefaults.defaults() 不读 yml,默认 reactMaxSteps=50,50 步循环太慢不适合骨架。
 *
 * <p>覆盖:
 * <ul>
 *   <li>AC-07-1: demo-max-steps LLM Provider 通过 SPI 注册 + Router 可见</li>
 *   <li>AC-07-2: stream() 返回预期 shape(1 tool call, name="noop", input=empty obj, stopReason=TOOL_USE)</li>
 *   <li>AC-07-3: NoopTool @Component 注册到 ToolRegistry 并返 success</li>
 * </ul>
 */
@SpringBootTest(
    classes = DemoMaxStepsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private AgentFactory agentFactory;
    @Autowired private Routers.LlmProviderRouter llmRouter;
    @Autowired private DemoMaxStepsLlmProviderProvider llmProviderProvider;
    @Autowired private NoopTool noopTool;

    @Test
    @DisplayName("AC-07-1: demo-max-steps LLM Provider registered + visible via Router")
    void ac07_llmProviderVisible() {
        assertThat(llmRouter.available()).contains("demo-max-steps");
    }

    @Test
    @DisplayName("AC-07-2: scripted LLM stream() returns expected tool-call shape")
    void ac07_llmStreamReturnsExpectedShape() {
        DemoMaxStepsLlmProvider provider =
            (DemoMaxStepsLlmProvider) llmProviderProvider.create(AgentConfigDefaults.defaults());

        Prompt prompt = Prompt.builder().build();
        java.util.concurrent.CompletableFuture<LlmResponse> future =
            provider.stream(prompt, null, new Subscriber<AgentEvent>() {
                @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(AgentEvent event) {}
                @Override public void onError(Throwable t) {}
                @Override public void onComplete() {}
            });

        LlmResponse resp = future.getNow(null);
        assertThat(resp).isNotNull();
        assertThat(resp.getToolCalls()).hasSize(1);
        ToolCall call = resp.getToolCalls().get(0);
        assertThat(call.getName()).isEqualTo("noop");
        assertThat(call.getInput().size()).isEqualTo(0);
        assertThat(resp.getStopReason().name()).isEqualTo("TOOL_USE");
    }

    @Test
    @DisplayName("AC-07-3: NoopTool bean registered with correct metadata")
    void ac07_noopToolRegistered() {
        assertThat(noopTool).isNotNull();
        assertThat(noopTool.name()).isEqualTo("noop");
        assertThat(noopTool.description()).contains("no-op");
    }
}