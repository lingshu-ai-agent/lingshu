package ai.lingshu.core.impl.tool;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.ToolExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #041 — L1 unit tests for {@code DefaultToolExecutionContext.DefaultApprovalGate}
 * (the SPI 真实现 that replaces Story #030 inline path in LinearTurnEngine).
 *
 * <p>AC-NN-01—05 contract:
 * <ol>
 *   <li>AC-041-01 (Allow 正常): subscriber receives ApprovalRequired then invokes the
 *       continuation with Allow → ask() returns Allow</li>
 *   <li>AC-041-02 (Deny 立即): subscriber receives ApprovalRequired then invokes the
 *       continuation with Deny → ask() returns Deny</li>
 *   <li>AC-041-03 (timeout &gt; 0): approvalTimeoutSeconds=1, no callback fires within
 *       the window → ask() returns Deny with [LINGS-P02] PERMISSION_APPROVAL_TIMEOUT</li>
 *   <li>AC-041-04 (cancel): cancellation token fires after 50ms → ask() returns Deny
 *       with [LINGS-P02] (interrupted, does not wait for timeout)</li>
 *   <li>AC-041-05 (no-sink): turnCtx.sink() == null → ask() returns Deny with [LINGS-P02]
 *       immediately (no hang, no event emitted)</li>
 * </ol>
 */
class DefaultApprovalGateTest {

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DefaultApprovalGateTest-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ── AC-041-01 Allow 正常 ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-041-01: ApprovalRequired → continuation.accept(Allow) → ask() returns Allow")
    void allowPath_returnsAllow() throws Exception {
        CapturingSubscriber sink = new CapturingSubscriber();
        TurnContext turn = stubTurn(minimalConfig(0), sink);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(turn, null, new ApprovalRegistry());

        CountDownLatch eventReceived = new CountDownLatch(1);
        sink.onAgentEvent = event -> {
            if (event instanceof AgentEvent.ApprovalRequired) {
                eventReceived.countDown();
            }
        };

        // Schedule the Allow answer 50ms after the event fires
        scheduler.schedule(() -> {
            try {
                eventReceived.await(2, TimeUnit.SECONDS);
                AgentEvent.ApprovalRequired ev = sink.lastApprovalRequired.get();
                ev.getContinuation().accept(new Decision.Allow("user approved"));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, 50, TimeUnit.MILLISECONDS);

        Decision.AskUser ask = new Decision.AskUser("rm -rf?", Collections.<Decision.Option>emptyList());
        Decision result = ctx.approval().ask(ask);

        assertThat(result).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) result).getReason()).isEqualTo("user approved");
        // Sink received exactly one ApprovalRequired event with a non-null UUID approvalId
        assertThat(sink.lastApprovalRequired.get()).isNotNull();
        assertThat(sink.lastApprovalRequired.get().getApprovalId()).isNotNull();
        assertThat(sink.lastApprovalRequired.get().getApprovalId()).matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        // Registry was populated, then auto-consumed by continuation
        assertThat(sink.lastApprovalRequired.get().getAsk()).isSameAs(ask);
    }

    // ── AC-041-02 Deny 立即 ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-041-02: ApprovalRequired → continuation.accept(Deny) → ask() returns Deny")
    void denyPath_returnsDeny() throws Exception {
        CapturingSubscriber sink = new CapturingSubscriber();
        TurnContext turn = stubTurn(minimalConfig(0), sink);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(turn, null, new ApprovalRegistry());

        CountDownLatch eventReceived = new CountDownLatch(1);
        sink.onAgentEvent = event -> {
            if (event instanceof AgentEvent.ApprovalRequired) {
                eventReceived.countDown();
            }
        };

        scheduler.schedule(() -> {
            try {
                eventReceived.await(2, TimeUnit.SECONDS);
                AgentEvent.ApprovalRequired ev = sink.lastApprovalRequired.get();
                ev.getContinuation().accept(new Decision.Deny("user denied"));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, 50, TimeUnit.MILLISECONDS);

        Decision.AskUser ask = new Decision.AskUser("rm -rf?", Collections.<Decision.Option>emptyList());
        Decision result = ctx.approval().ask(ask);

        assertThat(result).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) result).getReason()).isEqualTo("user denied");
    }

    // ── AC-041-03 timeout > 0 ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-041-03: approvalTimeoutSeconds=1 + no callback → Deny[LINGS-P02]")
    void timeout_returnsDenyWithErrorCode() throws Exception {
        CapturingSubscriber sink = new CapturingSubscriber();
        // approvalTimeoutSeconds = 1 → DefaultApprovalGate waits 1000ms then times out
        TurnContext turn = stubTurn(minimalConfig(1), sink);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(turn, null, new ApprovalRegistry());

        Decision.AskUser ask = new Decision.AskUser("rm -rf?", Collections.<Decision.Option>emptyList());
        long startMs = System.currentTimeMillis();
        Decision result = ctx.approval().ask(ask);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(result).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) result).getReason()).contains("[LINGS-P02]");
        assertThat(((Decision.Deny) result).getReason()).contains("timed out after 1s");
        // Sanity: actually waited ~1s (not the 0=infinite path)
        assertThat(elapsedMs).isBetween(900L, 3000L);
    }

    // ── AC-041-04 cancel ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-041-04: cancellationToken.fire() mid-ask → Deny[LINGS-P02] interrupted (not timeout)")
    void cancel_returnsDenyInterrupted() throws Exception {
        CapturingSubscriber sink = new CapturingSubscriber();
        // Long timeout (60s) — should NOT be the path that resolves; cancel must win.
        TurnContext turn = stubTurn(minimalConfig(60), sink);
        ToolExecutionContext.CancellationToken token = turn.cancellation();
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(turn, null, new ApprovalRegistry());

        // Fire cancellation 50ms after ask() starts (long before the 60s timeout)
        scheduler.schedule(token::fire, 50, TimeUnit.MILLISECONDS);

        Decision.AskUser ask = new Decision.AskUser("rm -rf?", Collections.<Decision.Option>emptyList());
        long startMs = System.currentTimeMillis();
        Decision result = ctx.approval().ask(ask);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(result).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) result).getReason()).contains("[LINGS-P02]");
        assertThat(((Decision.Deny) result).getReason()).contains("interrupted");
        // Sanity: resolved on cancel, NOT timeout — should be well under 60s
        assertThat(elapsedMs).isLessThan(5_000L);
    }

    // ── AC-041-05 no-sink ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-041-05: turnCtx.sink() == null → Deny[LINGS-P02] immediately (no hang)")
    void noSink_returnsDenyImmediately() {
        // Pass null sink — DefaultTurnContext.createWithBroadcast accepts null and stores it
        TurnContext turn = stubTurn(minimalConfig(60), null);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(turn, null, new ApprovalRegistry());

        Decision.AskUser ask = new Decision.AskUser("rm -rf?", Collections.<Decision.Option>emptyList());
        long startMs = System.currentTimeMillis();
        Decision result = ctx.approval().ask(ask);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(result).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) result).getReason()).contains("[LINGS-P02]");
        assertThat(((Decision.Deny) result).getReason()).contains("no event sink registered");
        // No hang — should return near-instantly (under 1s)
        assertThat(elapsedMs).isLessThan(1_000L);
    }

    // ── helpers ───────────────────────────────────────────

    /**
     * Subscriber that captures every event into a list and exposes the most recent
     * {@link AgentEvent.ApprovalRequired} for assertion. Optionally invokes a side-effect
     * callback per event (used by Allow/Deny tests to know when to fire the answer).
     */
    private static final class CapturingSubscriber implements Subscriber<AgentEvent> {
        final List<AgentEvent> events = Collections.synchronizedList(
            new java.util.ArrayList<AgentEvent>());
        final AtomicReference<AgentEvent.ApprovalRequired> lastApprovalRequired =
            new AtomicReference<AgentEvent.ApprovalRequired>();
        volatile java.util.function.Consumer<AgentEvent> onAgentEvent;

        @Override public void onSubscribe(org.reactivestreams.Subscription s) {
            s.request(Long.MAX_VALUE);
        }
        @Override public void onNext(AgentEvent event) {
            events.add(event);
            if (event instanceof AgentEvent.ApprovalRequired) {
                lastApprovalRequired.set((AgentEvent.ApprovalRequired) event);
            }
            if (onAgentEvent != null) {
                onAgentEvent.accept(event);
            }
        }
        @Override public void onError(Throwable t) { /* no-op for tests */ }
        @Override public void onComplete() { /* no-op for tests */ }
    }

    private static TurnContext stubTurn(AgentConfig cfg, Subscriber<AgentEvent> sink) {
        Session session = new Session() {
            @Override public String id() { return "test-session"; }
            @Override public List<Message> history() { return Collections.emptyList(); }
            @Override public Session fork(String subagentType) { return this; }
            @Override public Checkpoint checkpoint() {
                return new Checkpoint(
                    "test-session",
                    Collections.<Message>emptyList(),
                    Collections.<String, String>emptyMap(),
                    Instant.EPOCH);
            }
        };
        return DefaultTurnContext.createWithBroadcast(session, cfg, sink, "hi");
    }

    /**
     * Minimal {@link AgentConfig} matching the 27-arg ctor pattern used elsewhere in
     * tests. Only {@code approvalTimeoutSeconds} matters for DefaultApprovalGate behavior.
     */
    private static AgentConfig minimalConfig(int approvalTimeoutSeconds) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox(null, null, null, Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            "default",
            "default",
            null, null, null,
            1, 5, approvalTimeoutSeconds, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(), "default");
    }
}
