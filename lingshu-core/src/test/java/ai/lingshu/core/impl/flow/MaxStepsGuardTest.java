package ai.lingshu.core.impl.flow;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #008 — AC-07 black-box tests for {@link LinearTurnEngine} ReAct loop max-steps
 * guard. Verifies that {@link AgentEvent.MaxStepsExceeded} is emitted exactly when:
 * <ol>
 *   <li>for-loop 因 {@code step == maxSteps} 自然 bound 结束 (no break / cancellation / done / exception), AND</li>
 *   <li>最后一次 LLM 响应仍含 tool calls (no final answer)</li>
 * </ol>
 *
 * <p>5 终止路径分支全覆盖(US1/US2/US3 + EC-5/EC-7 = 11 case):
 * <ul>
 *   <li>US1-AS1: reactMaxSteps=3 + LLM 永远返 tool call → 11 events 含 MaxStepsExceeded</li>
 *   <li>US1-AS2: reactMaxSteps=5 + 3 tool-call + 1 END_TURN → 10 events 无 MaxStepsExceeded</li>
 *   <li>US1-AS3: reactMaxSteps=1 + 1 tool-call → 5 events 含 MaxStepsExceeded(1)</li>
 *   <li>US1-AS4: reactMaxSteps=0 → AgentFactory.validate() 抛 IllegalArgumentException</li>
 *   <li>US2-AS1: reactMaxSteps=2 + LLM 第 1 步抛 RuntimeException → 3 events 无 MaxStepsExceeded</li>
 *   <li>US2-AS2: reactMaxSteps=3 + 3 tool-call(tool 异常 → ToolResult.error)→ 11 events 含 MaxStepsExceeded</li>
 *   <li>US3-AS1: 反射验证 {@code AgentEvent.MaxStepsExceeded} 字段 int maxSteps + Usage totalUsage</li>
 *   <li>US3-AS2: 反射验证 {@code StopReason} 6 值无 MAX_STEPS</li>
 *   <li>US3-AS3: 末 2 个事件顺序 MaxStepsExceeded → TurnCompleted + assertSame(usage)</li>
 *   <li>EC-5: reactMaxSteps=10 + 第 5 步前 cancellation → TurnCompleted(CANCELLED) 无 MaxStepsExceeded</li>
 *   <li>EC-7: reactMaxSteps=3 + 2 tool-call + 第 3 步 END_TURN → 8 events 无 MaxStepsExceeded(break 优先)</li>
 * </ul>
 *
 * <p>Story #008 / AC-07 黑盒。复用 Story #004 既有 fixture(EchoLlmProvider / CapturingSubscriber /
 * RecordingPromptBuilder / SleepTool)+ 2 个新 fixture(ThrowingLlmProvider / ThrowTool)。
 */
class MaxStepsGuardTest {

    private DefaultToolExecutor toolExecutor;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy());
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-maxsteps-" + System.nanoTime());
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

    /**
     * Build a configurable AgentConfig with the given reactMaxSteps. Mirrors
     * {@code LinearTurnEngineToolDispatchTest.defaultConfig} but adds the
     * {@code reactMaxSteps} parameter.
     */
    private AgentConfig configWithMaxSteps(int parallelism, int timeoutSec, int reactMaxSteps) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            parallelism,
            timeoutSec,
            0, 0, 0,                                       // approval / turn / llm timeout
            reactMaxSteps,                                  // ← the parameter we vary
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,                                          // a2aTransport
            null,                                          // tenants (Story #006 — single-tenant)
            AgentConfig.A2a.defaults(),                   // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults()    // compactorConfig (Story #018)
        );
    }

    private static ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, NullNode.getInstance());
    }

    private TurnContext newTurn(AgentConfig cfg) {
        return new DefaultTurnContext(new DefaultSession(), cfg, new CapturingSubscriber(), "hi");
    }

    private LinearTurnEngine engine(EchoLlmProvider llm) {
        return new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor,
            new AllowAllPermissionPolicy(), pool);
    }

    /**
     * Build a scripted LLM that emits N tool-call responses (no END_TURN).
     * If the engine asks for more than N, {@link EchoLlmProvider} fails the future with
     * IllegalStateException — surfaces as a test failure rather than silent infinite loop.
     */
    private EchoLlmProvider toolCallLlm(int count) {
        LlmResponse toolCallResp = new LlmResponse(
            "",
            Collections.singletonList(call("c1", "noop")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse[] arr = new LlmResponse[count];
        Arrays.fill(arr, toolCallResp);
        return new EchoLlmProvider(Arrays.asList(arr));
    }

    /** Build a scripted LLM with N tool-call responses followed by 1 END_TURN. */
    private EchoLlmProvider toolCallThenEndTurn(int toolCallSteps) {
        LlmResponse toolCallResp = new LlmResponse(
            "",
            Collections.singletonList(call("c1", "noop")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "all done",
            Collections.<ToolCall>emptyList(),
            StopReason.END_TURN,
            Usage.zero());
        LlmResponse[] arr = new LlmResponse[toolCallSteps + 1];
        for (int i = 0; i < toolCallSteps; i++) {
            arr[i] = toolCallResp;
        }
        arr[toolCallSteps] = endTurn;
        return new EchoLlmProvider(Arrays.asList(arr));
    }

    /** Register a tool that does nothing (returns SUCCESS). */
    private static class NoopTool implements Tool {
        @Override public String name() { return "noop"; }
        @Override public String description() { return "no-op"; }
        @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
        @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content("OK")
                .isError(false)
                .build();
        }
    }

    /** Throwing tool — for US2-AS2 (ToolExecutor translates to ToolResult.error). */
    private static class ThrowTool implements Tool {
        @Override public String name() { return "boom"; }
        @Override public String description() { return "always throws"; }
        @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
        @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
            throw new RuntimeException("tool execution failed intentionally");
        }
    }

    /** Throwing LLM provider — for US2-AS1 (LLM throws on first stream() call). */
    private static class ThrowingLlmProvider extends EchoLlmProvider {
        public ThrowingLlmProvider() {
            // Initialize with one valid response (we'll throw before reading it).
            super(Collections.singletonList(new LlmResponse(
                "ignored", Collections.<ToolCall>emptyList(),
                StopReason.END_TURN, Usage.zero())));
        }
        // The actual throw happens via a wrapper — EchoLlmProvider is final-ish; we
        // simulate by feeding an empty script list so first stream() throws. But EchoLlmProvider
        // requires non-empty script. Use a special subclass that overrides stream().
    }

    // ─── US1 — Main path: MaxStepsExceeded emission ────────────────────────

    @Test
    @DisplayName("US1-AS1: maxSteps3_llmAlwaysToolCall_emitsMaxStepsExceeded_after3rdStep")
    void maxSteps3_llmAlwaysToolCall_emitsMaxStepsExceeded_after3rdStep() {
        // 3 tool-call responses — engine enters for-loop 3 times, each iteration ends with
        // ObservationAppended, then loop hits the bound at step=3 (no break, last has tool calls)
        // → MaxStepsExceeded + TurnCompleted(END_TURN)
        toolExecutor.register(new NoopTool());
        EchoLlmProvider llm = toolCallLlm(3);
        AgentConfig cfg = configWithMaxSteps(1, 5, 3);
        TurnContext ctx = newTurn(cfg);
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        // Expected event sequence (11 events):
        //   3 × (ReasoningStarted(step,3) + ToolCompleted + ObservationAppended)
        //   + MaxStepsExceeded(3)
        //   + TurnCompleted(END_TURN)
        List<AgentEvent> events = sink.events();
        assertThat(events).hasSize(11);

        // Verify the 3 ReasoningStarted carry step=1,2,3
        List<Integer> steps = events.stream()
            .filter(e -> e instanceof AgentEvent.ReasoningStarted)
            .map(e -> ((AgentEvent.ReasoningStarted) e).getStep())
            .collect(Collectors.toList());
        assertThat(steps).containsExactly(1, 2, 3);

        // Event #10 (index 9) = MaxStepsExceeded(3)
        AgentEvent mxe = events.get(9);
        assertThat(mxe).isInstanceOf(AgentEvent.MaxStepsExceeded.class);
        assertThat(((AgentEvent.MaxStepsExceeded) mxe).getMaxSteps()).isEqualTo(3);

        // Event #11 (index 10) = TurnCompleted (reason = last.getStopReason() = TOOL_USE
        // because LLM last response had tool calls; NOT a new MAX_STEPS value)
        AgentEvent tc = events.get(10);
        assertThat(tc).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) tc).getReason()).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    @DisplayName("US1-AS2: maxSteps5_llmEndTurnAfter3Steps_noMaxStepsExceeded")
    void maxSteps5_llmEndTurnAfter3Steps_noMaxStepsExceeded() {
        // 3 tool-call + 1 END_TURN — engine enters for-loop 3 times, after 3rd ObservationAppended
        // the LLM returns END_TURN → break at L162-165 → maxStepsHit=false → no MaxStepsExceeded.
        // Total events: 3×(RS+TC+OA) + 1 TurnCompleted(END_TURN) = 10
        toolExecutor.register(new NoopTool());
        EchoLlmProvider llm = toolCallThenEndTurn(3);
        AgentConfig cfg = configWithMaxSteps(1, 5, 5);
        TurnContext ctx = newTurn(cfg);
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        List<AgentEvent> events = sink.events();
        // Expected: 3 tool-call steps × (RS+TC+OA) = 9 + step 4 RS(4,5) + TurnCompleted = 11
        // (engine enters 4th step, sees end_turn, breaks; never reaches maxSteps bound)
        assertThat(events).hasSize(11);
        assertThat(events.stream().noneMatch(e -> e instanceof AgentEvent.MaxStepsExceeded))
            .as("natural END_TURN must NOT emit MaxStepsExceeded (FR-003)")
            .isTrue();
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) events.get(events.size() - 1)).getReason())
            .isEqualTo(StopReason.END_TURN);
    }

    @Test
    @DisplayName("US1-AS3: maxSteps1_llmToolCall_emitsMaxStepsExceeded_after1stStep")
    void maxSteps1_llmToolCall_emitsMaxStepsExceeded_after1stStep() {
        // reactMaxSteps=1 — engine enters for-loop once, executes 1 tool call, ObservationAppended,
        // then for-loop bound (step==maxSteps==1) → MaxStepsExceeded(1) → TurnCompleted(END_TURN)
        // Total events: 1×(RS+TC+OA) + 1 MaxStepsExceeded + 1 TurnCompleted = 5
        toolExecutor.register(new NoopTool());
        EchoLlmProvider llm = toolCallLlm(1);
        AgentConfig cfg = configWithMaxSteps(1, 5, 1);
        TurnContext ctx = newTurn(cfg);
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        List<AgentEvent> events = sink.events();
        assertThat(events).hasSize(5);
        assertThat(events.get(3)).isInstanceOf(AgentEvent.MaxStepsExceeded.class);
        assertThat(((AgentEvent.MaxStepsExceeded) events.get(3)).getMaxSteps()).isEqualTo(1);
        // Last event = TurnCompleted with TOOL_USE (LLM last response had tool calls)
        assertThat(events.get(4)).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) events.get(4)).getReason())
            .isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    @DisplayName("US1-AS4: maxSteps0_factoryValidateThrows_LingsC02_neverEnterEngine")
    void maxSteps0_factoryValidateThrows_LingsC02_neverEnterEngine() throws Exception {
        // reactMaxSteps=0 — AgentFactory.validate() L233-235 throws IllegalArgumentException
        // before engine is ever constructed. Test via reflection on private validate().
        AgentConfig bad = configWithMaxSteps(1, 5, 0);
        Method validate = AgentFactory.class.getDeclaredMethod("validate", AgentConfig.class);
        validate.setAccessible(true);
        assertThatThrownBy(() -> {
            try {
                validate.invoke(null, bad);
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Unwrap the IllegalArgumentException for assertThatThrownBy
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw new RuntimeException(e.getCause());
            }
        })
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("config.reactMaxSteps must be > 0")
            .hasMessageContaining("got 0");
    }

    // ─── US2 — Exception path: MaxStepsExceeded is NOT emitted on early failure ───

    @Test
    @DisplayName("US2-AS1: maxSteps2_llmThrowsFirstStep_errorPathNoMaxStepsExceeded")
    void maxSteps2_llmThrowsFirstStep_errorPathNoMaxStepsExceeded() {
        // LLM throws RuntimeException on first stream() call → catch at L184 emits ErrorEvent
        // + TurnCompleted(ERROR). No MaxStepsExceeded because exception path beats max-steps guard.
        toolExecutor.register(new NoopTool());
        // Subclass EchoLlmProvider to throw on first stream() call.
        EchoLlmProvider llm = new EchoLlmProvider(Collections.singletonList(
            new LlmResponse("", Collections.singletonList(call("c1", "noop")),
                StopReason.TOOL_USE, Usage.zero()))) {
            private int calls = 0;
            @Override
            public java.util.concurrent.CompletableFuture<LlmResponse> stream(
                    ai.lingshu.core.message.Prompt prompt, TurnContext ctx,
                    org.reactivestreams.Subscriber<? super AgentEvent> sink) {
                if (calls++ == 0) {
                    java.util.concurrent.CompletableFuture<LlmResponse> failed =
                        new java.util.concurrent.CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException("llm failed intentionally"));
                    return failed;
                }
                return super.stream(prompt, ctx, sink);
            }
        };
        AgentConfig cfg = configWithMaxSteps(1, 5, 2);
        TurnContext ctx = newTurn(cfg);
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        // Expected events: RS(1,2) → ErrorEvent → TurnCompleted(ERROR) = 3 events
        // (waitForLlm catches ExecutionException → RuntimeException → outer catch)
        List<AgentEvent> events = sink.events();
        assertThat(events.stream().noneMatch(e -> e instanceof AgentEvent.MaxStepsExceeded))
            .as("error path must NOT emit MaxStepsExceeded (FR-003)")
            .isTrue();
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) last).getReason()).isEqualTo(StopReason.ERROR);
    }

    @Test
    @DisplayName("US2-AS2: maxSteps3_toolExceptionMidPath_stepCountContinues_maxStepsHitFinally")
    void maxSteps3_toolExceptionMidPath_stepCountContinues_maxStepsHitFinally() {
        // Tool throws RuntimeException → ToolExecutor translates to ToolResult.error
        // (per LinearTurnEngine.runTurn Javadoc invariant) → engine continues to next step.
        // After 3 tool-call steps with tool error → MaxStepsExceeded(3) at end.
        toolExecutor.register(new ThrowTool());
        EchoLlmProvider llm = toolCallLlm(3);
        AgentConfig cfg = configWithMaxSteps(1, 5, 3);
        TurnContext ctx = newTurn(cfg);
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        // Same shape as US1-AS1: 3×(RS+TC+OA) + MaxStepsExceeded + TurnCompleted(END_TURN) = 11
        List<AgentEvent> events = sink.events();
        assertThat(events).hasSize(11);
        assertThat(events.get(9)).isInstanceOf(AgentEvent.MaxStepsExceeded.class);
        assertThat(((AgentEvent.MaxStepsExceeded) events.get(9)).getMaxSteps()).isEqualTo(3);
        // Last event = TurnCompleted with TOOL_USE (LLM last response had tool calls)
        assertThat(((AgentEvent.TurnCompleted) events.get(10)).getReason())
            .isEqualTo(StopReason.TOOL_USE);
    }

    // ─── US3 — Contract: event field types + StopReason unchanged ──────────

    @Test
    @DisplayName("US3-AS1: maxStepsExceeded_eventFields_intAndUsage")
    public void maxStepsExceeded_eventFields_intAndUsage() throws Exception {
        // Reflectively verify AgentEvent.MaxStepsExceeded carries int maxSteps + Usage totalUsage
        // + Lombok @Getter generates getter methods. This guards against accidental field
        // rename / type change that would break Story #005/#010 downstream.
        Class<?> cls = AgentEvent.MaxStepsExceeded.class;
        Field[] fields = cls.getDeclaredFields();
        assertThat(fields).hasSize(2);

        Field maxStepsField = findFieldByName(fields, "maxSteps");
        assertThat(maxStepsField).isNotNull();
        assertThat(maxStepsField.getType()).isEqualTo(int.class);

        Field totalUsageField = findFieldByName(fields, "totalUsage");
        assertThat(totalUsageField).isNotNull();
        assertThat(totalUsageField.getType()).isEqualTo(Usage.class);

        // Lombok @Getter generates getMaxSteps() / getTotalUsage()
        assertThat(cls.getMethod("getMaxSteps")).isNotNull();
        assertThat(cls.getMethod("getTotalUsage")).isNotNull();
    }

    @Test
    @DisplayName("US3-AS2: stopReason_enumHasNoMaxStepsValue")
    public void stopReason_enumHasNoMaxStepsValue() {
        // Guard: StopReason enum must NOT have MAX_STEPS — Story #008 deliberately reuses
        // END_TURN + emits MaxStepsExceeded as a separate diagnostic event. If a future
        // change adds MAX_STEPS, this test catches it (semver break).
        EnumSet<StopReason> values = EnumSet.allOf(StopReason.class);
        assertThat(values).containsExactlyInAnyOrder(
            StopReason.END_TURN,
            StopReason.TOOL_USE,
            StopReason.MAX_TOKENS,
            StopReason.COMPACTED,
            StopReason.CANCELLED,
            StopReason.ERROR
        );
        // Guard: StopReason must NOT have MAX_STEPS — Story #008 reuses TOOL_USE/END_TURN
        // + emits MaxStepsExceeded as a separate diagnostic event (no new enum value).
        assertThat(Arrays.asList(StopReason.values()))
            .as("StopReason must not contain MAX_STEPS (semver break guard)")
            .doesNotContain((StopReason) null);   // sentinel — actual check is below
        // Verify there's no constant named MAX_STEPS in the enum class
        boolean hasMaxSteps = false;
        for (StopReason sr : StopReason.values()) {
            if ("MAX_STEPS".equals(sr.name())) {
                hasMaxSteps = true;
                break;
            }
        }
        assertThat(hasMaxSteps)
            .as("StopReason enum must not have MAX_STEPS value")
            .isFalse();
    }

    @Test
    @DisplayName("US3-AS3: maxSteps3_eventOrder_maxStepsBeforeTurnCompleted_usageRefSame")
    void maxSteps3_eventOrder_maxStepsBeforeTurnCompleted_usageRefSame() {
        // Verify the 5th invariant (FR-005): MaxStepsExceeded is emitted BEFORE TurnCompleted
        // AND totalUsage is the SAME object reference (NFR-002 — 0 memory allocation).
        toolExecutor.register(new NoopTool());
        EchoLlmProvider llm = toolCallLlm(3);
        AgentConfig cfg = configWithMaxSteps(1, 5, 3);
        TurnContext ctx = newTurn(cfg);
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        List<AgentEvent> events = sink.events();
        AgentEvent secondLast = events.get(events.size() - 2);
        AgentEvent last = events.get(events.size() - 1);

        assertThat(secondLast).isInstanceOf(AgentEvent.MaxStepsExceeded.class);
        assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);

        // Same object reference — Usage is @Value, immutable; reference = value.
        Usage mxeUsage = ((AgentEvent.MaxStepsExceeded) secondLast).getTotalUsage();
        Usage tcUsage = ((AgentEvent.TurnCompleted) last).getUsage();
        assertThat(mxeUsage).isSameAs(tcUsage);
    }

    // ─── Edge cases ───────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-5: maxSteps10_cancellationMidPath_noMaxStepsExceeded")
    void maxSteps10_cancellationMidPath_noMaxStepsExceeded() throws Exception {
        // Cancel BEFORE runTurn — engine emits TurnCompleted(CANCELLED) on first iteration head
        // check. No MaxStepsExceeded (cancellation beats max-steps guard).
        toolExecutor.register(new NoopTool());
        EchoLlmProvider llm = toolCallLlm(10);   // never reached
        AgentConfig cfg = configWithMaxSteps(1, 5, 10);
        TurnContext ctx = newTurn(cfg);
        ctx.cancellation().fire();   // cancel BEFORE runTurn
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        List<AgentEvent> events = sink.events();
        assertThat(events.stream().noneMatch(e -> e instanceof AgentEvent.MaxStepsExceeded))
            .as("cancellation path must NOT emit MaxStepsExceeded (FR-003)")
            .isTrue();
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) last).getReason()).isEqualTo(StopReason.CANCELLED);
    }

    @Test
    @DisplayName("EC-7: maxSteps3_llmEndTurnAtLastStep_naturalEndTurn_noMaxStepsExceeded")
    void maxSteps3_llmEndTurnAtLastStep_naturalEndTurn_noMaxStepsExceeded() {
        // step==maxSteps but last has NO tool calls (END_TURN at step 3) → break at L162-165
        // runs BEFORE the step==maxSteps guard at L184 → maxStepsHit stays false.
        // Script: [toolCall, toolCall, endTurn] — step 3 returns END_TURN (no tool calls).
        // Expected: 2×(RS+TC+OA) + 1 RS(3,3) + 1 TurnCompleted(END_TURN) = 8 events
        toolExecutor.register(new NoopTool());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(
            new LlmResponse("", Collections.singletonList(call("c1", "noop")),
                StopReason.TOOL_USE, Usage.zero()),
            new LlmResponse("", Collections.singletonList(call("c2", "noop")),
                StopReason.TOOL_USE, Usage.zero()),
            new LlmResponse("done", Collections.<ToolCall>emptyList(),
                StopReason.END_TURN, Usage.zero())
        ));
        AgentConfig cfg = configWithMaxSteps(1, 5, 3);
        TurnContext ctx = newTurn(cfg);
        CapturingSubscriber sink = (CapturingSubscriber) ctx.sink();

        engine(llm).runTurn(ctx, sink);

        List<AgentEvent> events = sink.events();
        assertThat(events).hasSize(8);
        assertThat(events.stream().noneMatch(e -> e instanceof AgentEvent.MaxStepsExceeded))
            .as("EC-7: step==maxSteps but break at L162-165 BEFORE guard → NO MaxStepsExceeded")
            .isTrue();
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) last).getReason())
            .isEqualTo(StopReason.END_TURN);
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static Field findFieldByName(Field[] fields, String name) {
        for (Field f : fields) {
            if (f.getName().equals(name)) return f;
        }
        return null;
    }
}