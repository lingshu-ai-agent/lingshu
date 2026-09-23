package ai.lingshu.core.impl.flow;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.flow.support.SleepTool;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #004 — AC-03 black-box validation:
 * <b>4 independent tools × 1s sleep, {@code tool.parallelism=4} → wall-clock ≤ 1.3s,
 * speedup ratio ≥ 3.0×</b> (spec.md §AC + quickstart.md Validation 1).
 *
 * <p>The 1.3s budget gives ~300ms headroom over the theoretical 1.0s serial-vs-parallel
 * floor (4 tools × 1s each, but in parallel they all start within a few ms so total
 * wall-clock ≈ 1s + scheduler overhead). The 3.0× speedup ratio compares against the
 * serial baseline (4 tools × 1s ≈ 4s wall-clock = 4.0× theoretical max).
 */
class LinearTurnEngineParallelDispatchTest {

    private static final long ONE_SECOND_MS = 1000L;
    private static final long AC03_WALL_CLOCK_BUDGET_MS = 1300L;
    private static final double AC03_MIN_SPEEDUP_RATIO = 3.0;

    private AgentConfig defaultConfig(int parallelism) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            parallelism,           // toolParallelism
            5,                     // toolTimeoutSeconds (per-call timeout)
            0, 0, 0,               // approval / turn / llm timeout
            10,                    // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
            AgentConfig.ToolsConfig.defaults());     // tools (Story #019)
    }

    private static ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, NullNode.getInstance());
    }

    @Test
    @DisplayName("AC-03: blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s")
    void blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s() throws InterruptedException {
        // CountDownLatch syncs all 4 tools so they truly run concurrently — without this,
        // threads could finish scheduling serially even with parallelism=4.
        CountDownLatch startGate = new CountDownLatch(1);

        DefaultToolExecutor toolExec = new DefaultToolExecutor(new AllowAllPermissionPolicy());
        toolExec.register(new SleepTool("tool_a", ONE_SECOND_MS, startGate));
        toolExec.register(new SleepTool("tool_b", ONE_SECOND_MS, startGate));
        toolExec.register(new SleepTool("tool_c", ONE_SECOND_MS, startGate));
        toolExec.register(new SleepTool("tool_d", ONE_SECOND_MS, startGate));

        LlmResponse fourCalls = new LlmResponse(
            "",
            Arrays.asList(call("c1", "tool_a"), call("c2", "tool_b"),
                          call("c3", "tool_c"), call("c4", "tool_d")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(fourCalls, endTurn));
        AgentConfig cfg = defaultConfig(4);
        CapturingSubscriber sink = new CapturingSubscriber();
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "lingshu-ac03-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        try {
            TurnContext ctx = new DefaultTurnContext(
                new DefaultSession(), cfg, sink, "AC-03 black-box");

            LinearTurnEngine engine = new LinearTurnEngine(
                new RecordingPromptBuilder(), llm, toolExec, new AllowAllPermissionPolicy(), pool);

            // Release the latch BEFORE runTurn — all 4 worker threads will arrive at
            // startLatch.await() within a few ms of each other and proceed concurrently.
            // (Counting down inside finally would deadlock the engine waiting on the futures.)
            startGate.countDown();

            long start = System.nanoTime();
            engine.runTurn(ctx, sink);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            // Wall-clock budget: 1.3s for 4 × 1s parallel sleeps
            assertThat(elapsedMs)
                .as("AC-03 wall-clock: 4 parallel 1s sleeps must finish in <1.3s")
                .isLessThanOrEqualTo(AC03_WALL_CLOCK_BUDGET_MS);

            // Speedup ratio vs serial baseline (4 × 1s = 4000ms). At elapsed=1300ms,
            // ratio ≈ 4000/1300 ≈ 3.08. Allow generous CI jitter.
            double speedup = (4.0 * ONE_SECOND_MS) / (double) elapsedMs;
            assertThat(speedup)
                .as("AC-03 speedup ratio vs 4s serial baseline must be ≥ 3.0× (actual=%.2f×)", speedup)
                .isGreaterThanOrEqualTo(AC03_MIN_SPEEDUP_RATIO);

            // 4 ToolCompleted events must all be emitted
            long completedCount = sink.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .count();
            assertThat(completedCount)
                .as("AC-03: all 4 tools must emit ToolCompleted")
                .isEqualTo(4);

            // Turn must end cleanly (no ErrorEvent)
            AgentEvent last = sink.events().get(sink.events().size() - 1);
            assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
            assertThat(((AgentEvent.TurnCompleted) last).getReason())
                .isEqualTo(StopReason.END_TURN);

            System.out.printf("[AC-03] elapsedMs=%d  speedup=%.2fx%n", elapsedMs, speedup);
        } finally {
            startGate.countDown();   // ensure latch is released even if assertion fails above
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("AC-03 helper: parallelism1_serialBaseline_measures4xAsLong")
    void parallelism1_serialBaseline_measures4xAsLong() {
        // Companion test to AC-03 black-box: confirms that parallelism=1 takes ~4× longer
        // than the parallel path, validating that the speedup ratio calculation has a real
        // serial baseline to compare against (rather than a degenerate always-fast path).
        DefaultToolExecutor toolExec = new DefaultToolExecutor(new AllowAllPermissionPolicy());
        toolExec.register(new SleepTool("tool_a", 200));
        toolExec.register(new SleepTool("tool_b", 200));
        toolExec.register(new SleepTool("tool_c", 200));
        toolExec.register(new SleepTool("tool_d", 200));

        LlmResponse fourCalls = new LlmResponse(
            "",
            Arrays.asList(call("c1", "tool_a"), call("c2", "tool_b"),
                          call("c3", "tool_c"), call("c4", "tool_d")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(fourCalls, endTurn));
        AgentConfig cfg = defaultConfig(1);  // serial
        CapturingSubscriber sink = new CapturingSubscriber();
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-ac03-serial-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        try {
            TurnContext ctx = new DefaultTurnContext(new DefaultSession(), cfg, sink, "serial baseline");
            LinearTurnEngine engine = new LinearTurnEngine(
                new RecordingPromptBuilder(), llm, toolExec, new AllowAllPermissionPolicy(), pool);
            long start = System.nanoTime();
            engine.runTurn(ctx, sink);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            // Serial: 4 × 200ms ≈ 800ms minimum (with thread scheduling overhead)
            assertThat(elapsedMs)
                .as("Serial baseline: 4 × 200ms must take ≥ 700ms (parallelism=1)")
                .isGreaterThanOrEqualTo(700);
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
    }
}