package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.LinearTurnEngine;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — L3 E2E integration test for the built-in Read {@link ai.lingshu.core.slot.Tool}
 * wired through a real {@link LinearTurnEngine}.
 *
 * <p>The turn script:
 * <ol>
 *   <li>LLM emits one {@code Read} tool call pointing at a real temp file.</li>
 *   <li>{@link LinearTurnEngine} dispatches via {@link DefaultToolExecutor} → {@link ReadTool}.</li>
 *   <li>{@link ReadTool} reads the file and returns the content in a {@link ai.lingshu.core.message.ToolResult}.</li>
 *   <li>LLM emits {@link StopReason#END_TURN}.</li>
 * </ol>
 *
 * <p>Verifies AC-019-14 (built-in Read works end-to-end through the engine loop).
 */
class LocalToolsE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-019-14: linearTurnEngineWithReadTool_runsRealToolAndCompletes")
    void linearTurnEngineWithReadTool_runsRealToolAndCompletes(@TempDir Path tmp) throws Exception {
        // 1. Real file on disk
        Path file = tmp.resolve("poem.txt");
        String content = "The answer is 42.";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        // 2. Default executor with a real ReadTool
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DefaultToolExecutor toolExec = new DefaultToolExecutor(new AllowAllPermissionPolicy(), registry);
        registry.register(new ReadTool(new LocalToolProps(200_000, 1_000_000)));

        // 3. Scripted LLM: first call emits Read tool call, second emits END_TURN
        ObjectNode input = MAPPER.createObjectNode();
        input.put("file_path", file.toString());
        ToolCall readCall = new ToolCall("c1", "Read", input);
        LlmResponse toolCallResponse = new LlmResponse(
            "",
            Arrays.asList(readCall),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok",
            Collections.<ToolCall>emptyList(),
            StopReason.END_TURN,
            Usage.zero());

        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));
        // CRITICAL: working directory must equal the @TempDir so ReadTool.resolveSafePath()
        // accepts the absolute path returned by JUnit (paths starting with /var/folders/...).
        AgentConfig cfg = defaultConfig(tmp);
        TurnContext ctx = new DefaultTurnContext(new DefaultSession(), cfg, new CapturingSubscriber(), "hi");
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-e2e-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        try {
            LinearTurnEngine engine = new LinearTurnEngine(
                new RecordingPromptBuilder(), llm, toolExec, new AllowAllPermissionPolicy(), pool);
            engine.runTurn(ctx, sink);
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        // 4. Verify event chain
        List<AgentEvent> events = sink.events();
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) last).getReason()).isEqualTo(StopReason.END_TURN);

        // 5. Verify the Read content flowed through ToolCompleted
        List<AgentEvent.ToolCompleted> toolCompleted = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCompleted)
            .map(e -> (AgentEvent.ToolCompleted) e)
            .collect(java.util.stream.Collectors.toList());
        assertThat(toolCompleted).hasSize(1);
        assertThat(toolCompleted.get(0).getResult().getContent()).contains("The answer is 42.");
        assertThat(toolCompleted.get(0).getResult().getStatus().name()).isEqualTo("SUCCESS");
        assertThat(toolCompleted.get(0).getResult().isError()).isFalse();
    }

    private AgentConfig defaultConfig(Path workingDir) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", workingDir, Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1,                          // toolParallelism
            5,                          // toolTimeoutSeconds
            0, 0, 0,                    // approval / turn / llm timeout
            10,                         // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,                       // a2aTransport
            null,                       // tenants
            AgentConfig.A2a.defaults(), // a2a
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults()
        );
    }
}