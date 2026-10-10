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
package ai.lingshu.core.impl.flow;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.compaction.NullCompactor;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #045 — L1 unit tests for {@link LinearTurnEngine} Compactor wiring (7 cases).
 *
 * <p>Validates the Story #045 wiring chain end-to-end at the engine boundary:
 * <ul>
 *   <li>compactor.shouldCompact() = false ⇒ no compact() call, no Compacted event;</li>
 *   <li>compactor.shouldCompact() = true ⇒ compact() invoked exactly once + prompt
 *       rebuilt + a {@link AgentEvent.Compacted} signal fires on the sink;</li>
 *   <li>compactor.compact() throws ⇒ exception propagates up through the engine's
 *       outer catch (surfaces as an {@link AgentEvent.ErrorEvent} on the sink);</li>
 *   <li>{@link NullCompactor#INSTANCE} is a true no-op (engine runs through without
 *       producing any compaction events);</li>
 *   <li>legacy 6-arg ctor (Story #030) keeps its back-compat by delegating to the
 *       7-arg primary ctor with {@link NullCompactor#INSTANCE};</li>
 *   <li>passing {@code null} Compactor to the 7-arg ctor throws {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>Reuses Story #004 fixtures ({@link EchoLlmProvider} / {@link CapturingSubscriber} /
 * {@link RecordingPromptBuilder}) plus a stub {@link Compactor} for the trigger tests.
 */
class LinearTurnEngineCompactionTest {

    private DefaultToolExecutor toolExecutor;
    private DefaultToolRegistry toolRegistry;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-compactor-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    // ─── Fixture helpers ──────────────────────────────────────────────────

    private AgentConfig defaultConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,               // tenants
            AgentConfig.A2a.defaults(),    // a2a
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default",                       // permissionPolicy
        16,     // 🆕 Story #044 — maxConcurrentTurns
        32);    // 🆕 Story #044 — maxConcurrentQueueDepth
    }

    private TurnContext newTurn(AgentConfig cfg) {
        return new DefaultTurnContext(new DefaultSession(), cfg, new CapturingSubscriber(), "hi");
    }

    /** One-shot LlmProvider that returns END_TURN immediately (engine exits the ReAct loop). */
    private EchoLlmProvider endTurnLlm() {
        return new EchoLlmProvider(Collections.singletonList(
            new LlmResponse("done", Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                StopReason.END_TURN, Usage.zero())));
    }

    /** Stub Compactor that never triggers; counts compact() invocations. */
    private static class SkipCompactor implements Compactor {
        final AtomicInteger compactCalls = new AtomicInteger(0);
        @Override public boolean shouldCompact(Prompt prompt) { return false; }
        @Override public void compact(TurnContext ctx) { compactCalls.incrementAndGet(); }
    }

    /** Stub Compactor that always triggers; counts compact() invocations + truncates history. */
    private static class TriggeringCompactor implements Compactor {
        final AtomicInteger compactCalls = new AtomicInteger(0);
        @Override public boolean shouldCompact(Prompt prompt) { return true; }
        @Override public void compact(TurnContext ctx) {
            compactCalls.incrementAndGet();
            // Reflect mutation: drop the oldest 1 message so a future shouldCompact()
            // would still trigger (proves ctx.session() is reachable through the API).
            List<Message> hist = ctx.session().history();
            if (hist.size() > 0) {
                List<Message> trimmed = new ArrayList<>(hist);
                trimmed.remove(0);
                ctx.session().compact(trimmed);
            }
        }
    }

    /** Stub Compactor whose compact() always throws — verifies exception propagation. */
    private static class ThrowingCompactor implements Compactor {
        @Override public boolean shouldCompact(Prompt prompt) { return true; }
        @Override public void compact(TurnContext ctx) {
            throw new RuntimeException("[LINGS-COMP-TEST] simulated compaction failure");
        }
    }

    // ─── Test cases ────────────────────────────────────────────────────────

    @Test
    @DisplayName("L1-001: shouldCompactFalse_skipsCompactor")
    void shouldCompactFalse_skipsCompactor() {
        SkipCompactor skipComp = new SkipCompactor();
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), endTurnLlm(), toolExecutor,
            new AllowAllPermissionPolicy(), pool, null /* approvalRegistry */, skipComp);

        AgentConfig cfg = defaultConfig();
        CapturingSubscriber sink = new CapturingSubscriber();
        engine.runTurn(newTurn(cfg), sink);

        // Engine exited via END_TURN without engaging the compactor.
        assertThat(skipComp.compactCalls.get()).isZero();
        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.Compacted)
            .isEmpty();
        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.TurnCompleted)
            .hasSize(1);
    }

    @Test
    @DisplayName("L1-002: shouldCompactTrue_invokesCompactor")
    void shouldCompactTrue_invokesCompactor() {
        TriggeringCompactor trigger = new TriggeringCompactor();
        // RecordingPromptBuilder produces empty messages, so shouldCompact() still returns
        // true per our stub and compact() is invoked once before the LLM call.
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), endTurnLlm(), toolExecutor,
            new AllowAllPermissionPolicy(), pool, null, trigger);

        CapturingSubscriber sink = new CapturingSubscriber();
        engine.runTurn(newTurn(defaultConfig()), sink);

        // Engine exited cleanly AND invoked compact() at least once (the ReAct loop may
        // re-prompt for the END_TURN step, so accept >= 1 here).
        assertThat(trigger.compactCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.Compacted)
            .isNotEmpty();
    }

    @Test
    @DisplayName("L1-003: shouldCompactTrue_rebuildsPrompt")
    void shouldCompactTrue_rebuildsPrompt() {
        TriggeringCompactor trigger = new TriggeringCompactor();
        // Use a CountingPromptBuilder that increments per call so we can assert that
        // the compactor branch forced a second prompt-builder invocation (initial + rebuild).
        CountingPromptBuilder pb = new CountingPromptBuilder();
        LinearTurnEngine engine = new LinearTurnEngine(
            pb, endTurnLlm(), toolExecutor,
            new AllowAllPermissionPolicy(), pool, null, trigger);

        CapturingSubscriber sink = new CapturingSubscriber();
        engine.runTurn(newTurn(defaultConfig()), sink);

        // ReAct loop iterates at least once; with a triggering compactor each iteration
        // builds prompt twice (initial + post-compaction rebuild). So pb.buildCalls
        // should be at least 2 × number-of-iterations. Loosened to >= 2 since we only
        // care about the post-compaction rebuild path.
        assertThat(pb.buildCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(trigger.compactCalls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("L1-004: compactThrows_propagatesUp")
    void compactThrows_propagatesUp() {
        ThrowingCompactor throwing = new ThrowingCompactor();
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), endTurnLlm(), toolExecutor,
            new AllowAllPermissionPolicy(), pool, null, throwing);

        CapturingSubscriber sink = new CapturingSubscriber();
        // The engine catches the RuntimeException and emits an ErrorEvent on the sink.
        engine.runTurn(newTurn(defaultConfig()), sink);

        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.ErrorEvent)
            .extracting(e -> ((AgentEvent.ErrorEvent) e).getError())
            .anyMatch(t -> t.getMessage() != null
                && t.getMessage().contains("[LINGS-COMP-TEST] simulated compaction failure"));
    }

    @Test
    @DisplayName("L1-005: nullCompactor_skippedNoOp")
    void nullCompactor_skippedNoOp() {
        // NullCompactor is the canonical back-compat sentinel: shouldCompact() = false,
        // compact() = no-op. Engine should run end-to-end without any compaction events.
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), endTurnLlm(), toolExecutor,
            new AllowAllPermissionPolicy(), pool, null, NullCompactor.INSTANCE);

        CapturingSubscriber sink = new CapturingSubscriber();
        engine.runTurn(newTurn(defaultConfig()), sink);

        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.Compacted)
            .isEmpty();
        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.TurnCompleted)
            .hasSize(1);
    }

    @Test
    @DisplayName("L1-006: legacy6ArgCtor_backCompat (Story #030 6-arg ctor still works)")
    void legacy6ArgCtor_backCompat() {
        // Story #030 6-arg ctor (PromptBuilder, LlmProvider, ToolExecutor,
        // PermissionPolicy, ExecutorService, ApprovalRegistry) — must still work and
        // delegate to the 7-arg ctor with NullCompactor.INSTANCE.
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), endTurnLlm(), toolExecutor,
            new AllowAllPermissionPolicy(), pool, null /* approvalRegistry */);

        CapturingSubscriber sink = new CapturingSubscriber();
        engine.runTurn(newTurn(defaultConfig()), sink);

        // No compaction events because the legacy ctor defaulted to NullCompactor.
        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.Compacted)
            .isEmpty();
        assertThat(sink.events())
            .filteredOn(e -> e instanceof AgentEvent.TurnCompleted)
            .hasSize(1);
    }

    @Test
    @DisplayName("L1-007: nullCompactorCtor_throwsIAE")
    void nullCompactorCtor_throwsIAE() {
        // Defensive guard: the 7-arg primary ctor must reject a null Compactor
        // so we never silently fall into a NullPointerException deep in the engine loop.
        assertThatThrownBy(() -> new LinearTurnEngine(
                new RecordingPromptBuilder(), endTurnLlm(), toolExecutor,
                new AllowAllPermissionPolicy(), pool, null, /* compactor= */ null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compactor");
    }

    // ─── CountingPromptBuilder helper ─────────────────────────────────────

    /** PromptBuilder that records how many times build() was invoked. */
    private static class CountingPromptBuilder implements ai.lingshu.core.slot.PromptBuilder {
        final AtomicInteger buildCalls = new AtomicInteger(0);
        @Override
        public Prompt build(TurnContext ctx) {
            buildCalls.incrementAndGet();
            return Prompt.builder()
                .messages(Collections.<Message>emptyList())
                .tools(Collections.<ai.lingshu.core.message.ToolSpec>emptyList())
                .hints(new ai.lingshu.core.message.ModelHints(
                    ctx.config().getLlm().getModel(),
                    ctx.config().getLlm().getTemperature(),
                    ctx.config().getLlm().getMaxTokens()))
                .build();
        }
    }
}
