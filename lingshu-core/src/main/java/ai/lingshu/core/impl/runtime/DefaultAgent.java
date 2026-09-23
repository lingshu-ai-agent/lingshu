package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.reload.AgentConfigRegistry;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.RunResult;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concrete {@link Agent} implementation (Story #001 default).
 *
 * <p>Three execution paths (see {@link Agent}):
 * <ul>
 *   <li>{@link #run(String)} — returns a lazy {@code Publisher<AgentEvent>} backed by the
 *       underlying {@link FlowEngine}; the engine itself is non-reactive in v1</li>
 *   <li>{@link #runBlocking(String)} — drains the publisher synchronously and returns a
 *       {@link RunResult}; the canonical demo path</li>
 *   <li>{@link #continueWithUserMessage(String)} — appends a synthetic User message and runs
 *       another iteration on the same session</li>
 * </ul>
 *
 * <p>dsh §7.1: each Agent is created fresh per turn by {@code AgentFactory.create(cfg)}.
 * The {@link Session} it holds may be reused across multiple turns of one conversation,
 * but a new {@code Agent} instance is built for each turn.
 *
 * <p>🆕 Story #007 (AC-06 / spec §FR-006) — if constructed with a non-null
 * {@link AgentConfigRegistry}, the Agent reads {@code registry.current()} once at the
 * entry of every {@code run} / {@code runBlocking} call and freezes that snapshot into
 * the {@link TurnContext} for the duration of the turn. This delivers AC-06
 * "T1 freezes old config, T2 sees new config after a successful YAML reload" semantics:
 * a turn that is already executing is unaffected by a concurrent reload (its @Value
 * snapshot is final + Java reference freeze), while the next call to
 * {@code run(...)} on the same Agent observes the published new config.
 */
public class DefaultAgent implements Agent {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgent.class);

    private final Session session;
    private final AgentConfig config;
    private final FlowEngine engine;
    /** 🆕 Story #007 — optional hot-reload source. {@code null} = static (legacy) mode. */
    private final AgentConfigRegistry registry;

    /** Legacy 3-arg constructor — static mode, no hot-reload. */
    public DefaultAgent(Session session, AgentConfig config, FlowEngine engine) {
        this(session, config, engine, null);
    }

    /**
     * 🆕 Story #007 — registry-aware constructor. When {@code registry != null}, every
     * turn freezes {@code registry.current()} at {@code run} entry (AC-06 freeze semantics).
     */
    public DefaultAgent(Session session, AgentConfig config, FlowEngine engine,
                        AgentConfigRegistry registry) {
        this.session = session;
        this.config = config;
        this.engine = engine;
        this.registry = registry;
    }

    @Override public Session session() { return session; }
    @Override public AgentConfig config() { return config; }

    @Override
    public Publisher<AgentEvent> run(String userInput) {
        TurnContext ctx = buildContext(userInput);
        // Story #001: drain events to a buffer synchronously, then replay on subscribe.
        List<AgentEvent> buffer = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Subscriber<AgentEvent> tap = new Subscriber<AgentEvent>() {
            @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent e) { buffer.add(e); }
            @Override public void onError(Throwable t) { /* surfaced via ErrorEvent in buffer */ }
            @Override public void onComplete() { done.countDown(); }
        };
        engine.runTurn(ctx, tap);
        return new BufferedPublisher(buffer);
    }

    @Override
    public RunResult runBlocking(String userInput) {
        long t0 = System.currentTimeMillis();
        List<AgentEvent> captured = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        Subscriber<AgentEvent> drain = new Subscriber<AgentEvent>() {
            @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent e) { captured.add(e); }
            @Override public void onError(Throwable t) { err.set(t); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        };

        try {
            TurnContext ctx = buildContext(userInput);
            engine.runTurn(ctx, drain);
        } catch (Throwable t) {
            err.set(t);
        } finally {
            done.countDown();
        }

        if (err.get() != null) {
            throw new RuntimeException("Agent run failed", err.get());
        }

        String finalText = "";
        StopReason reason = StopReason.END_TURN;
        Usage usage = Usage.zero();
        int turns = 0;
        for (AgentEvent e : captured) {
            if (e instanceof AgentEvent.TextDelta) {
                finalText += ((AgentEvent.TextDelta) e).getText();
            } else if (e instanceof AgentEvent.TurnCompleted) {
                AgentEvent.TurnCompleted tc = (AgentEvent.TurnCompleted) e;
                reason = tc.getReason();
                usage = tc.getUsage();
                turns++;
            } else if (e instanceof AgentEvent.ErrorEvent) {
                AgentEvent.ErrorEvent ee = (AgentEvent.ErrorEvent) e;
                throw new RuntimeException("Agent emitted ErrorEvent: " + ee.getError().getMessage(), ee.getError());
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("DefaultAgent.runBlocking done in {}ms, finalText.len={}, turns={}, reason={}",
            elapsed, finalText.length(), turns, reason);
        return new RunResult(finalText, turns, usage, reason, elapsed);
    }

    @Override
    public Publisher<AgentEvent> continueWithUserMessage(String content) {
        if (session instanceof DefaultSession) {
            ((DefaultSession) session).append(new Message.User(content));
        }
        return run(content);
    }

    /**
     * 🆕 Story #020c — Sync wrapper for {@link #continueWithUserMessage}.
     *
     * <p>Mirrors the {@link #runBlocking} template literally: subscribe to the
     * reactive path, drain events into a {@code List}, block on a
     * {@code CountDownLatch} with {@code turnTimeoutSeconds}.
     *
     * <p><b>Why a new method vs re-using {@code runBlocking} or extracting a
     * shared {@code drainToResult} helper:</b> that would refactor existing
     * tested code (Story #001) for minimal benefit. Inlining keeps this Story
     * strictly additive — any regression to {@link #runBlocking} tests is impossible
     * by construction.
     */
    @Override
    public RunResult continueWithUserMessageBlocking(String content) {
        long t0 = System.currentTimeMillis();
        List<AgentEvent> captured = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        Subscriber<AgentEvent> drain = new Subscriber<AgentEvent>() {
            @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent e) { captured.add(e); }
            @Override public void onError(Throwable t) { err.set(t); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        };

        try {
            continueWithUserMessage(content).subscribe(drain);
        } catch (Throwable t) {
            err.set(t);
        } finally {
            done.countDown();
        }

        try {
            done.await(config.getTurnTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (err.get() != null) {
            throw new RuntimeException("Agent run failed", err.get());
        }

        String finalText = "";
        StopReason reason = StopReason.END_TURN;
        Usage usage = Usage.zero();
        int turns = 0;
        for (AgentEvent e : captured) {
            if (e instanceof AgentEvent.TextDelta) {
                finalText += ((AgentEvent.TextDelta) e).getText();
            } else if (e instanceof AgentEvent.TurnCompleted) {
                AgentEvent.TurnCompleted tc = (AgentEvent.TurnCompleted) e;
                reason = tc.getReason();
                usage = tc.getUsage();
                turns++;
            } else if (e instanceof AgentEvent.ErrorEvent) {
                AgentEvent.ErrorEvent ee = (AgentEvent.ErrorEvent) e;
                throw new RuntimeException("Agent emitted ErrorEvent: " + ee.getError().getMessage(), ee.getError());
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("DefaultAgent.continueWithUserMessageBlocking done in {}ms, finalText.len={}, turns={}, reason={}",
            elapsed, finalText.length(), turns, reason);
        return new RunResult(finalText, turns, usage, reason, elapsed);
    }

    private TurnContext buildContext(String userInput) {
        if (session instanceof DefaultSession) {
            ((DefaultSession) session).append(new Message.User(userInput));
        }
        // 🆕 Story #007 (AC-06) — if a registry is wired, freeze the current config at
        // turn entry. Otherwise fall back to the Agent's held config (legacy 3-arg ctor).
        AgentConfig frozen = (registry != null) ? registry.current() : config;
        // 🆕 Story #005 (FR-011) — use createWithBroadcast so the turn's cancellation token
        // auto-registers with AgentFactory's static broadcast registry. Ctrl-C reaches the
        // JVM shutdown hook → AgentFactory.broadcastCancel() → all in-flight turns see
        // isCancelled() == true within the AC-04 200ms budget.
        return DefaultTurnContext.createWithBroadcast(session, frozen, null, userInput);
    }

    // ── BufferedPublisher — synchronous engine replay for lazy subscribers ──

    private static final class BufferedPublisher implements Publisher<AgentEvent> {

        private final List<AgentEvent> buffer;

        BufferedPublisher(List<AgentEvent> buffer) { this.buffer = buffer; }

        @Override
        public void subscribe(Subscriber<? super AgentEvent> s) {
            s.onSubscribe(new Subscription() {
                private long cursor = 0;
                @Override public void request(long n) {
                    long delivered = 0;
                    while (delivered < n && cursor < buffer.size()) {
                        s.onNext(buffer.get((int) cursor++));
                        delivered++;
                    }
                    if (cursor >= buffer.size()) s.onComplete();
                }
                @Override public void cancel() { /* no-op — engine ran synchronously */ }
            });
        }
    }
}