package ai.lingshu.core.impl.flow;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.flow.support.SleepTool;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.PermissionPolicy;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.AfterEach;
import org.reactivestreams.Subscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #030 — L2 slice tests for {@link LinearTurnEngine} AskUser branch (3 cases).
 *
 * <p>Verifies the real wiring end-to-end:
 * <ul>
 *   <li>Engine emits {@link AgentEvent.ApprovalRequired} with a stable {@code approvalId}
 *       when permission policy returns {@link Decision.AskUser};</li>
 *   <li>Engine resolves the blocked turn when the captured continuation is invoked
 *       with {@link Decision.Allow};</li>
 *   <li>ApprovalRegistry round-trip — engine registers, the test consumes via
 *       {@link ApprovalRegistry#consume}.</li>
 * </ul>
 *
 * <p>Story #030 follow-up: timeout fallback (LINGS-P02) is exercised in a separate
 * test by setting {@code approvalTimeoutSeconds = 1} and never invoking the
 * continuation.
 */
class LinearTurnEngineAskUserTest {

    private DefaultToolExecutor toolExecutor;
    private DefaultToolRegistry toolRegistry;
    private ApprovalRegistry approvalRegistry;
    private ExecutorService pool;
    private ExecutorService resolverPool;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        approvalRegistry = new ApprovalRegistry();
        // The executor used by the engine: AllowAllPermissionPolicy for the defensive
        // re-check (engine handles AskUser before reaching here).
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-test-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        // A separate pool to invoke the continuation asynchronously so the
        // engine's decisionFuture.get() can actually unblock.
        resolverPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "resolver-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        resolverPool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
        resolverPool.awaitTermination(2, TimeUnit.SECONDS);
    }

    private AgentConfig defaultConfig(int approvalTimeoutSec) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1,                              // toolParallelism
            5,                              // toolTimeoutSeconds
            approvalTimeoutSec,             // 🆕 Story #030 — approval timeout (0 = indefinite)
            0, 0,                           // turn / llm timeout
            10,                             // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null, AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default"
        );
    }

    private static ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, NullNode.getInstance());
    }

    /** PermissionPolicy stub that returns AskUser for a specific tool name. */
    private static PermissionPolicy askUserPolicyFor(final String askToolName) {
        return new PermissionPolicy() {
            @Override
            public Decision check(ToolCall call, ai.lingshu.core.slot.ToolExecutionContext ctx) {
                if (askToolName.equals(call.getName())) {
                    return new Decision.AskUser(
                        "permission required for '" + call.getName() + "'",
                        Collections.<Decision.Option>emptyList());
                }
                return new Decision.Allow("ok");
            }
        };
    }

    private LinearTurnEngine engine(PermissionPolicy policy) {
        return new LinearTurnEngine(
            new RecordingPromptBuilder(),
            new EchoLlmProvider(Arrays.asList(
                new LlmResponse("", Collections.<ToolCall>emptyList(),
                    StopReason.END_TURN, Usage.zero()))),
            toolExecutor, policy, pool, approvalRegistry);
    }

    /**
     * Custom sink that, when it sees {@link AgentEvent.ApprovalRequired}, schedules
     * the continuation to fire with {@link Decision.Allow} after a short delay
     * (so the engine's {@code decisionFuture.get()} can actually unblock).
     */
    private static class AskUserResolvingSink implements Subscriber<AgentEvent> {
        private final List<AgentEvent> events =
            Collections.synchronizedList(new java.util.ArrayList<AgentEvent>());
        private final ExecutorService resolver;
        private final Decision decision;
        private final long fireAfterMs;

        AskUserResolvingSink(ExecutorService resolver, Decision decision, long fireAfterMs) {
            this.resolver = resolver;
            this.decision = decision;
            this.fireAfterMs = fireAfterMs;
        }

        @Override public void onSubscribe(org.reactivestreams.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(AgentEvent event) {
            events.add(event);
            if (event instanceof AgentEvent.ApprovalRequired) {
                AgentEvent.ApprovalRequired ar = (AgentEvent.ApprovalRequired) event;
                java.util.function.Consumer<Decision> cont = ar.getContinuation();
                if (cont != null) {
                    resolver.submit(() -> {
                        try {
                            Thread.sleep(fireAfterMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        cont.accept(decision);
                    });
                }
            }
        }

        @Override public void onError(Throwable t) {
            events.add(new AgentEvent.ErrorEvent(t));
        }

        @Override public void onComplete() { /* no-op */ }

        List<AgentEvent> events() {
            return Collections.unmodifiableList(events);
        }
    }

    @Test
    @DisplayName("AC-030-19: engine_askUser_emitsApprovalRequiredWithApprovalId")
    void engine_askUser_emitsApprovalRequiredWithApprovalId() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // Engine emits ONE tool call to bash_safe; policy returns AskUser for it.
        // We need an LLM that emits the tool call, then we'll see what happens.
        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        AgentConfig cfg = defaultConfig(30);  // 30s timeout for the resolver pool
        TurnContext ctx = new DefaultTurnContext(new DefaultSession(), cfg,
            new CapturingSubscriber(), "hi");

        AskUserResolvingSink sink = new AskUserResolvingSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        // The sink should have seen at least one ApprovalRequired event.
        AtomicReference<AgentEvent.ApprovalRequired> arRef = new AtomicReference<>();
        for (AgentEvent e : sink.events()) {
            if (e instanceof AgentEvent.ApprovalRequired) {
                arRef.set((AgentEvent.ApprovalRequired) e);
                break;
            }
        }
        assertThat(arRef.get())
            .as("engine must emit ApprovalRequired when policy returns AskUser")
            .isNotNull();
        assertThat(arRef.get().getApprovalId())
            .as("approvalId must be non-null UUID")
            .isNotNull();
        assertThat(arRef.get().getApprovalId()).isNotEmpty();
        assertThat(arRef.get().getAsk()).isInstanceOf(Decision.AskUser.class);
    }

    @Test
    @DisplayName("AC-030-20: engine_askUser_continuationAllows_proceedsToToolExecution")
    void engine_askUser_continuationAllows_proceedsToToolExecution() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        AgentConfig cfg = defaultConfig(30);
        TurnContext ctx = new DefaultTurnContext(new DefaultSession(), cfg,
            new CapturingSubscriber(), "hi");

        AskUserResolvingSink sink = new AskUserResolvingSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        // Tool must have run → at least one ToolCompleted event
        long completed = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ToolCompleted)
            .count();
        assertThat(completed).isGreaterThanOrEqualTo(1);
        // The completed tool result content is from SleepTool("bash_safe", 10)
        ToolResult result = (ToolResult) sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ToolCompleted)
            .findFirst()
            .map(e -> ((AgentEvent.ToolCompleted) e).getResult())
            .orElse(null);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("AC-030-21: approvalRegistry_registerAndConsume")
    void approvalRegistry_registerAndConsume() {
        // Drive just the registry directly — engine emits ApprovalRequired and
        // registers the continuation; here we verify the registry contract.
        AtomicReference<Decision> captured = new AtomicReference<Decision>();
        java.util.function.Consumer<Decision> cont = captured::set;
        String id = "test-approval-id-001";
        approvalRegistry.register(id, cont);

        assertThat(approvalRegistry.size()).isEqualTo(1);
        java.util.function.Consumer<Decision> got = approvalRegistry.consume(id);
        assertThat(got).isNotNull();
        got.accept(new Decision.Allow("user-ok"));
        assertThat(captured.get()).isInstanceOf(Decision.Allow.class);
        assertThat(approvalRegistry.size()).isEqualTo(0);

        // Second consume → null (atomic, single-use)
        assertThat(approvalRegistry.consume(id)).isNull();
    }
}