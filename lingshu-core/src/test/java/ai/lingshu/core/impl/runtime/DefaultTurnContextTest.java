package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #027a — L1 test for {@link DefaultTurnContext#appendAssistant} signature
 * expansion (AC-NN-deps-2 adjacent). Verifies that the assistant turn persisted
 * in session history carries the {@code toolCalls} passed by the caller — i.e.
 * the fix is wired end-to-end and the hardcoded empty list from Story #001 is
 * truly gone.
 *
 * <p>Why this test matters: even if {@code LinearTurnEngine.L166} correctly
 * passes {@code resp.getToolCalls()} (covered by
 * {@link LinearTurnEngineToolDispatchTest} extension), if
 * {@code DefaultTurnContext.appendAssistant} silently ignored the new arg,
 * history would still have {@code toolCalls=[]} and the
 * {@code tool_use}/{@code tool_result} round-trip would break at request-build
 * time on the next turn. This test catches that regression directly.
 */
@DisplayName("Story #027a — DefaultTurnContext.appendAssistant carries toolCalls")
class DefaultTurnContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentConfig cfg() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1, 5, 0, 0, 0,
            10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(), "default"
                ,
        16,		// 🆕 Story #044 — maxConcurrentTurns
        32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    @Test
    @DisplayName("AC-NN-deps-2: appendAssistant_persistsToolCallsFromCaller")
    void appendAssistant_persistsToolCallsFromCaller() {
        DefaultSession session = new DefaultSession();
        DefaultTurnContext ctx = new DefaultTurnContext(
            session, cfg(), new CapturingSubscriber(), "user input");

        // Two real tool calls (not the empty list hardcoded by Story #001)
        ObjectNode arg1 = MAPPER.createObjectNode().put("path", "/tmp/a");
        ObjectNode arg2 = MAPPER.createObjectNode().put("cmd", "ls");
        ToolCall call1 = new ToolCall("c1", "read_file", arg1);
        ToolCall call2 = new ToolCall("c2", "bash", arg2);
        List<ToolCall> calls = Arrays.asList(call1, call2);

        ctx.appendAssistant("thinking out loud", calls, Usage.zero());

        // History must contain exactly one Assistant message with the same toolCalls reference
        List<Message> history = session.history();
        assertThat(history).hasSize(1);
        Message.Assistant a = (Message.Assistant) history.get(0);
        assertThat(a.getText()).isEqualTo("thinking out loud");
        assertThat(a.getToolCalls()).containsExactly(call1, call2);
        // stopReason stays END_TURN at this layer (LinearTurnEngine owns the
        // authoritative stop reason via `last` for turn boundary decisions)
        assertThat(a.getStopReason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    @DisplayName("appendAssistant_nullToolCalls_fallsBackToEmptyList")
    void appendAssistant_nullToolCalls_fallsBackToEmptyList() {
        // Defensive: callers that pass null (e.g. text-only turns without
        // explicitly building an empty list) should not NPE; the Message.Assistant
        // contract requires a non-null list.
        DefaultSession session = new DefaultSession();
        DefaultTurnContext ctx = new DefaultTurnContext(
            session, cfg(), new CapturingSubscriber(), "x");

        ctx.appendAssistant("text-only", null, Usage.zero());

        Message.Assistant a = (Message.Assistant) session.history().get(0);
        assertThat(a.getToolCalls()).isEmpty();
    }
}