package ai.lingshu.examples.demoparallel;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Subscriber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story #004 demo scripted LLM — 第一次 stream() 返 4 个并行 tool call,第二次返 END_TURN。
 *
 * <p>本 demo 的 LLM Provider(本类副本拷贝自 lingshu-core/src/test/java/.../EchoLlmProvider.java,
 * 因测试 fixture 不跨模块 import,见 plan §"EchoLlmProvider 模板")。
 */
public class DemoParallelLlmProvider implements LlmProvider {

    private final List<LlmResponse> scripted;
    private final AtomicInteger idx = new AtomicInteger(0);

    public DemoParallelLlmProvider() {
        ObjectMapper mapper = new ObjectMapper();
        List<LlmResponse> responses = new ArrayList<>();

        // Step 1: 4 个并行 tool call(指向同 demo-parallel 模块内的 SleepTool 副本,4 个)
        List<ai.lingshu.core.message.ToolCall> calls = new ArrayList<>();
        String[] toolNames = {"sleep_a", "sleep_b", "sleep_c", "sleep_d"};
        for (int i = 0; i < toolNames.length; i++) {
            ObjectNode input = mapper.createObjectNode();
            input.put("ms", 1000);
            calls.add(new ai.lingshu.core.message.ToolCall(
                "parallel-call-" + i, toolNames[i], input));
        }
        responses.add(new LlmResponse("", calls, StopReason.TOOL_USE, Usage.zero()));

        // Step 2: END_TURN
        responses.add(new LlmResponse("All parallel tools completed.",
            Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero()));

        this.scripted = Collections.unmodifiableList(responses);
    }

    @Override
    public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                Subscriber<? super AgentEvent> sink) {
        int i = idx.getAndIncrement();
        if (i >= scripted.size()) {
            CompletableFuture<LlmResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                "DemoParallelLlmProvider: scripted exhausted at call " + (i + 1)));
            return failed;
        }
        return CompletableFuture.completedFuture(scripted.get(i));
    }
}
