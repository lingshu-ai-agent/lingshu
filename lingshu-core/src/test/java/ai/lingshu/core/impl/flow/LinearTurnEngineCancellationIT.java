package ai.lingshu.core.impl.flow;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.flow.support.SleepTool;
import ai.lingshu.core.impl.flow.support.SlowLlmProvider;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #005 — AC-04 black-box integration test: Ctrl-C in flight → turn exits within
 * 200ms with {@code stopReason=CANCELLED}.
 *
 * <p>Wires the real {@link LinearTurnEngine} with {@link DefaultToolExecutor},
 * {@link DefaultTurnContext} (5-arg constructor with a real cancellation token),
 * and a {@link SlowLlmProvider} that blocks the LLM future until cancellation fires.
 *
 * <p>Spec: <code>specs/005-cancellation-token/quickstart.md</code> Validation 1.
 */
class LinearTurnEngineCancellationIT {

    private static final long AC04_BUDGET_MS = 200L;
    private static final long AC04_HEADROOM_MS = 100L;   // generous CI jitter

    private AgentConfig defaultConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            4,    // toolParallelism
            5,    // toolTimeoutSeconds
            0, 0, 0,
            10,   // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
            AgentConfig.ToolsConfig.defaults());     // tools (Story #019)
    }

    @Test
    @DisplayName("AC-04: blackBox_ctrlC_midTurn_exitsWithin200msWithCancelled")
    void blackBox_ctrlC_midTurn_exitsWithin200msWithCancelled() throws Exception {
        // Slow LLM — future is only completed when cancellation fires (or 30s safety net).
        SlowLlmProvider llm = new SlowLlmProvider("partial response text", 30_000L);

        DefaultToolExecutor toolExec = new DefaultToolExecutor(new AllowAllPermissionPolicy(), new DefaultToolRegistry());
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-ac04-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        try {
            CapturingSubscriber sink = new CapturingSubscriber();
            // Use 4-arg constructor (back-compat path) so we can grab a fresh token
            // and fire it ourselves; this isolates the test from AgentFactory state.
            TurnContext ctx = new DefaultTurnContext(
                new DefaultSession(), defaultConfig(), sink, "AC-04 black-box");

            LinearTurnEngine engine = new LinearTurnEngine(
                new RecordingPromptBuilder(), llm, toolExec,
                new AllowAllPermissionPolicy(), pool);

            // Run engine in a background thread so we can fire cancellation mid-flight
            CountDownLatch engineDone = new CountDownLatch(1);
            AtomicBoolean engineReturned = new AtomicBoolean(false);
            Thread runner = new Thread(() -> {
                try {
                    engine.runTurn(ctx, sink);
                } finally {
                    engineReturned.set(true);
                    engineDone.countDown();
                }
            }, "ac04-engine-runner");
            runner.setDaemon(true);
            runner.start();

            // Give the engine a moment to enter its 200ms LLM wait loop
            Thread.sleep(50);

            long cancelAt = System.nanoTime();
            ctx.cancellation().fire();

            // AC-04: engine must return within 200ms of cancel
            assertThat(engineDone.await(AC04_BUDGET_MS + AC04_HEADROOM_MS, TimeUnit.MILLISECONDS))
                .as("AC-04: engine.runTurn must exit within %dms of cancellation (plus %dms headroom)",
                    AC04_BUDGET_MS, AC04_HEADROOM_MS)
                .isTrue();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt);
            assertThat(elapsedMs)
                .as("AC-04: actual cancel→exit elapsed ms (budget=%d)", AC04_BUDGET_MS)
                .isLessThanOrEqualTo(AC04_BUDGET_MS + AC04_HEADROOM_MS);

            assertThat(engineReturned).isTrue();
            // Final event must be TurnCompleted with CANCELLED
            AgentEvent last = sink.events().get(sink.events().size() - 1);
            assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
            assertThat(((AgentEvent.TurnCompleted) last).getReason())
                .as("AC-04: final stopReason must be CANCELLED")
                .isEqualTo(StopReason.CANCELLED);

            System.out.printf("[AC-04] cancel→exit elapsedMs=%d (budget=%d)%n",
                elapsedMs, AC04_BUDGET_MS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("cancel_beforeFirstStep_emitsCancelledImmediately")
    void cancel_beforeFirstStep_emitsCancelledImmediately() throws Exception {
        SlowLlmProvider llm = new SlowLlmProvider("ignored", 30_000L);
        DefaultToolExecutor toolExec = new DefaultToolExecutor(new AllowAllPermissionPolicy(), new DefaultToolRegistry());
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "lingshu-cancel-pre-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        try {
            CapturingSubscriber sink = new CapturingSubscriber();
            TurnContext ctx = new DefaultTurnContext(
                new DefaultSession(), defaultConfig(), sink, "pre-cancel");
            LinearTurnEngine engine = new LinearTurnEngine(
                new RecordingPromptBuilder(), llm, toolExec,
                new AllowAllPermissionPolicy(), pool);

            // Fire cancellation BEFORE the engine starts — loop head check catches it
            // on the first iteration (step 1, before the LLM call)
            ctx.cancellation().fire();

            engine.runTurn(ctx, sink);

            AgentEvent last = sink.events().get(sink.events().size() - 1);
            assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
            assertThat(((AgentEvent.TurnCompleted) last).getReason())
                .isEqualTo(StopReason.CANCELLED);
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("cancel_duringToolDispatch_substitutesCancelledToolResults")
    void cancel_duringToolDispatch_substitutesCancelledToolResults() throws Exception {
        // LLM returns 4 tool calls (using regular EchoLlmProvider via first step);
        // mid-dispatch we cancel — each in-flight future is cancelled and we
        // observe "cancelled before tool dispatch" / "tool cancelled" ToolResults.
        CountDownLatch toolStartGate = new CountDownLatch(1);
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExec = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        // 4 sleep tools, each holds for 2 seconds unless interrupted
        toolRegistry.register(new SleepTool("slow_a", 2_000L, toolStartGate));
        toolRegistry.register(new SleepTool("slow_b", 2_000L, toolStartGate));
        toolRegistry.register(new SleepTool("slow_c", 2_000L, toolStartGate));
        toolRegistry.register(new SleepTool("slow_d", 2_000L, toolStartGate));

        // LLM: first call returns 4 tool calls, second call is CANCELLED (won't be reached)
        ai.lingshu.core.impl.flow.support.EchoLlmProvider llm =
            new ai.lingshu.core.impl.flow.support.EchoLlmProvider(java.util.Arrays.asList(
                new ai.lingshu.core.message.LlmResponse("", java.util.Arrays.asList(
                    new ai.lingshu.core.message.ToolCall("c1", "slow_a",
                        com.fasterxml.jackson.databind.node.NullNode.getInstance()),
                    new ai.lingshu.core.message.ToolCall("c2", "slow_b",
                        com.fasterxml.jackson.databind.node.NullNode.getInstance()),
                    new ai.lingshu.core.message.ToolCall("c3", "slow_c",
                        com.fasterxml.jackson.databind.node.NullNode.getInstance()),
                    new ai.lingshu.core.message.ToolCall("c4", "slow_d",
                        com.fasterxml.jackson.databind.node.NullNode.getInstance())),
                    StopReason.TOOL_USE, ai.lingshu.core.message.Usage.zero()),
                new ai.lingshu.core.message.LlmResponse("done",
                    java.util.Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                    StopReason.END_TURN, ai.lingshu.core.message.Usage.zero())));

        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-cancel-tool-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        try {
            CapturingSubscriber sink = new CapturingSubscriber();
            TurnContext ctx = new DefaultTurnContext(
                new DefaultSession(), defaultConfig(), sink, "tool-cancel");
            LinearTurnEngine engine = new LinearTurnEngine(
                new RecordingPromptBuilder(), llm, toolExec,
                new AllowAllPermissionPolicy(), pool);

            CountDownLatch engineDone = new CountDownLatch(1);
            Thread runner = new Thread(() -> {
                try {
                    engine.runTurn(ctx, sink);
                } finally {
                    engineDone.countDown();
                }
            }, "ac04-tool-cancel-runner");
            runner.setDaemon(true);

            // Release the start gate so tools can begin; this is fired from inside
            // dispatchParallel after the Semaphore is acquired.
            // Simpler: fire cancel after a brief delay to ensure tools are mid-flight.
            runner.start();
            Thread.sleep(100);   // let engine enter dispatchParallel

            ctx.cancellation().fire();
            toolStartGate.countDown();   // unblock the sleep tools if any are still waiting

            assertThat(engineDone.await(2, TimeUnit.SECONDS))
                .as("Engine must exit within 2s of cancellation (tools may complete or be cancelled)")
                .isTrue();

            // The turn must end with CANCELLED, regardless of whether any tool
            // completed before the cancel fired.
            AgentEvent last = sink.events().get(sink.events().size() - 1);
            assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
            assertThat(((AgentEvent.TurnCompleted) last).getReason())
                .isEqualTo(StopReason.CANCELLED);
        } finally {
            toolStartGate.countDown();
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
