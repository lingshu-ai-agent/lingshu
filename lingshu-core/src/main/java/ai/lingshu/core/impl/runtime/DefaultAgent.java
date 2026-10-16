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
import ai.lingshu.core.slot.RuntimeSandbox;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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
 *
 * <p>🆕 Story #049 — {@code DefaultAgent.run()} 实时流式(Plan A)—— 替换 Story #001 的
 * 同步 drain-and-replay 路径。{@link #run(String)} 现在返回 {@link LiveRunPublisher},
 * 一个 hot lazy {@link Publisher}{@code <AgentEvent>}:{@code run()} 调用立刻返回,
 * 真正的 engine {@code runTurn} 在第一次 {@code subscribe(...)} 时被提交到一个 daemon
 * {@code cachedThreadPool}(线程名前缀 {@code lingshu-agent-live-N})异步执行,
 * engine 通过 subscriber bridge 实时推 {@link AgentEvent} 给下游 Subscriber。
 * {@link #runBlocking(String)} / {@link #continueWithUserMessage(String)} /
 * {@link #continueWithUserMessageBlocking(String)} 行为字节级不变(走 {@link BufferedPublisher}
 * 同步路径),由 Phase 2b 反向 AC 测试守护。完全合规 Reactive Streams 1.0.4 spec
 * (§2.2 single-subscriber / §2.7 serial signals / §3.9 non-positive request),取消通过
 * Story #005 AC-04 200ms 预算的 {@code ctx.isCancelled()} polling 传播。详见
 * {@link LiveRunPublisher} Javadoc。
 */
public class DefaultAgent implements Agent {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgent.class);

    private final Session session;
    private final AgentConfig config;
    private final FlowEngine engine;
    /** 🆕 Story #007 — optional hot-reload source. {@code null} = static (legacy) mode. */
    private final AgentConfigRegistry registry;
    /** 🆕 Story #028 — resolved Slot 3 Sandbox instance (chroot by default). */
    private final RuntimeSandbox runtimeSandbox;
    /** 🆕 Story #049 — daemon {@code cachedThreadPool} (per-Agent) for {@link #run(String)}
     *  async fan-out: {@code subscribe(s)} submits {@code engine.runTurn} here so the
     * engine emits events live without blocking the caller thread. Threads named
     * {@code lingshu-agent-live-N} for log diagnostics. Daemon=true so JVM exit is not
     * blocked by an idle live publisher. */
    private final ExecutorService liveExecutor;

    /** Legacy 3-arg constructor — static mode, no hot-reload, no sandbox (pre-#028 callers). */
    public DefaultAgent(Session session, AgentConfig config, FlowEngine engine) {
        this(session, config, engine, null, null);
    }

    /** Legacy 4-arg constructor — hot-reload mode without sandbox. */
    public DefaultAgent(Session session, AgentConfig config, FlowEngine engine,
                        AgentConfigRegistry registry) {
        this(session, config, engine, registry, null);
    }

    /**
     * 🆕 Story #028 — registry + sandbox aware constructor. When {@code registry != null},
     * every turn freezes {@code registry.current()} at {@code run} entry (AC-06 freeze
     * semantics). The {@code runtimeSandbox} is propagated to
     * {@code DefaultToolExecutionContext} so the sandbox boundary actually fires per
     * {@code Tool.execute(...)} call (dsh §6.3 + §4.10.1 hard rule 2).
     */
    public DefaultAgent(Session session, AgentConfig config, FlowEngine engine,
                        AgentConfigRegistry registry, RuntimeSandbox runtimeSandbox) {
        this.session = session;
        this.config = config;
        this.engine = engine;
        this.registry = registry;
        this.runtimeSandbox = runtimeSandbox;
        // 🆕 Story #049 — per-Agent daemon executor (daemonFactory prefix "lingshu-agent-live").
        // The 3-arg / 4-arg legacy ctors above delegate here, so all construction paths
        // initialize the executor. Single-agent-per-turn (dsh §7.1) means this executor
        // is short-lived: the cachedThreadPool tears down naturally with the Agent
        // (the daemon threads do not block JVM exit).
        this.liveExecutor = Executors.newCachedThreadPool(daemonFactory("lingshu-agent-live"));
    }

    /**
     * 🆕 Story #049 — daemon {@link ThreadFactory} for {@link #liveExecutor}. Threads
     * are named {@code <prefix>-N} (N starting at 1, per-instance {@link AtomicInteger})
     * for log diagnostics, and set {@code daemon=true} so the JVM can exit even if an
     * idle live publisher thread is still parked (e.g. SSE disconnect with no
     * {@code request(0)} wakeup within the 1s park timeout).
     */
    private static ThreadFactory daemonFactory(String prefix) {
        final AtomicInteger seq = new AtomicInteger(0);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    @Override public Session session() { return session; }
    @Override public AgentConfig config() { return config; }

    @Override
    public Publisher<AgentEvent> run(String userInput) {
        // 🆕 Story #049 — replaces the Story #001 in-line drain-and-replay pattern.
        // LiveRunPublisher is a hot lazy Publisher: subscribe(...) is the trigger that
        // actually starts engine.runTurn on the daemon executor. Until subscribe, the
        // engine is NOT invoked (AC-049-01 lazy contract). After subscribe, events
        // stream live to the downstream Subscriber (AC-049-03 no buffering).
        // runBlocking() continues to use the BufferedPublisher synchronous path below.
        return new LiveRunPublisher(liveExecutor, engine, buildContext(userInput));
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
     * <p>Mirrors the {@link #runBlocking} template literally. The turn's events
     * flow synchronously through {@link FlowEngine#runTurn(TurnContext, Subscriber)}
     * because the engine and the drain live on the same thread (the caller's
     * thread) — no {@code Publisher.subscribe(...)} handoff to a daemon
     * executor, no {@code CountDownLatch.await(...)}.
     *
     * <p><b>Why a new method vs re-using {@code runBlocking} or extracting a
     * shared {@code drainToResult} helper:</b> that would refactor existing
     * tested code (Story #001) for minimal benefit. Inlining keeps this Story
     * strictly additive — any regression to {@link #runBlocking} tests is impossible
     * by construction.
     *
     * <p><b>🆕 Story #049 — async path is forbidden for this method.</b>
     * Calling {@link #continueWithUserMessage} returns a {@link Publisher} that
     * would invoke {@code engine.runTurn} on the daemon executor. To collect its
     * events into a {@code RunResult} we'd need a {@code CountDownLatch}, but the
     * runtime contract {@code turnTimeoutSeconds = 0} means "no timeout" — and
     * {@code CountDownLatch.await(0, …)} returns {@code false} immediately, leaving
     * the captured list empty. AC-049-09 (b) explicitly forbids the reactive path.
     * The mirror of {@code runBlocking} below avoids both pitfalls.
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
            // Mirror Story #020c's `continueWithUserMessage(content)` side effect:
            // append the user message to the session BEFORE the engine runs. The
            // returned Publisher (LiveRunPublisher) is intentionally NOT subscribed —
            // we drive engine.runTurn synchronously below, exactly like runBlocking.
            if (session instanceof DefaultSession) {
                ((DefaultSession) session).append(new Message.User(content));
            }
            // buildContext appends the user message a SECOND time (Story #007 +
            // Story #020c double-append contract that regression tests verify —
            // see DefaultAgentTest.continueWithUserMessage_writesThroughToHistory).
            TurnContext ctx = buildContext(content);
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
        // 🆕 Story #028 — pass the resolved Slot 3 Sandbox into the TurnContext so
        // LinearTurnEngine.dispatchWithPolicy can wire it into DefaultToolExecutionContext
        // per Tool.execute(...) call. Without this, fs() / http() / process() would never
        // enforce the chroot / whitelist boundaries (dsh §4.10.1 hard rule 2 pipeline
        // step 4).
        return DefaultTurnContext.createWithBroadcast(session, frozen, null, userInput, runtimeSandbox);
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

    // ── LiveRunPublisher — hot lazy reactive publisher (Story #049) ────────────
    //
    // Replaces Story #001's "drain-then-replay" pattern with true streaming.
    // Contract:
    //   • run() returns the Publisher immediately — engine NOT invoked (AC-049-01 lazy).
    //   • subscribe(s) submits engine.runTurn(ctx, bridge) to the daemon executor;
    //     events stream live through bridge.onNext() to the downstream Subscriber
    //     (AC-049-03 no buffering, AC-049-02 main-thread not blocked).
    //   • Reactive Streams 1.0.4 spec compliance:
    //       §2.2 single-subscriber — enforced via AtomicBoolean.compareAndSet on
    //             subscribed; second subscribe() delivers onSubscribe(noop) +
    //             onError(IllegalStateException) per §3.4 (AC-049-04).
    //       §2.7 serial onNext/onError/onComplete — all signals are delivered on the
    //             same executor thread; subscriber throws cancel the subscription but
    //             do NOT propagate out of the executor (we catch Throwable).
    //       §3.9 non-positive request signal — request(n<=0) → onError(IAE).
    //   • Cancellation: cancel() flips the cancelled flag AND unpark()s the parked
    //     executor thread (which is reading requested.get() in bridge.onNext). The
    //     engine itself sees ctx.isCancelled()==true within Story #005 AC-04 200ms
    //     budget (AC-049-05 cancel propagates to engine).
    //   • Back-pressure: requested counter (AtomicLong) tracks demand. bridge.onNext
    //     parks the executor thread via LockSupport.parkNanos(50ms) while
    //     requested.get() <= 0 (AC-049-06 zero-request blocks until requested).
    //
    // Threading model:
    //   • All subscriber-facing signals (onSubscribe/onNext/onError/onComplete) fire
    //     on the executor thread. request(n) may be invoked from any thread (caller's);
    //     we AtomicLong-add the demand and unpark() the executor thread.
    //   • LinearTurnEngine.runTurn never calls sink.onSubscribe/onComplete/onError —
    //     it only emits sink.onNext(...). We synthesize terminal signals ourselves
    //     when engine.runTurn returns (onComplete) or throws (onError).

    private static final class LiveRunPublisher implements Publisher<AgentEvent>, Subscription {

        private final ExecutorService executor;
        private final FlowEngine engine;
        private final TurnContext ctx;

        /** 🆕 Reactive §2.2 — single-subscriber gate. compareAndSet(false,true) on first subscribe. */
        private final AtomicBoolean subscribed = new AtomicBoolean(false);
        /** 🆕 Downstream Subscriber. {@code null} until first successful subscribe; set atomically. */
        private final AtomicReference<Subscriber<? super AgentEvent>> sub = new AtomicReference<>();
        /** 🆕 Outstanding demand counter. AtomicLong + LockSupport-park implements §3.9 / §3.16. */
        private final AtomicLong requested = new AtomicLong(0);
        /** 🆕 Cancellation flag. Flipped by cancel(); checked by bridge.onNext park loop
         * and by subscriber delivery. Volatile because cancel() may be called from
         * any thread (typically the downstream's thread). */
        private volatile boolean cancelled;
        /** 🆕 Captures the executor thread so request(n) can unpark() it. Cleared on
         * engine completion to avoid leaking the reference post-turn. */
        private final AtomicReference<Thread> engineThread = new AtomicReference<>();
        /** 🆕 Monotonic timestamp of when {@code engine.runTurn} actually started.
         * Used by the bridge to detect "subscriber dropped without request" within 1s
         * (park timeout). Set by bridge entry; read by park loop. */
        private static final long PARK_NANOS = 50_000_000L;       // 50 ms per park slice
        private static final long PARK_TIMEOUT_NANOS = 1_000_000_000L; // 1 s — kill the turn

        LiveRunPublisher(ExecutorService executor, FlowEngine engine, TurnContext ctx) {
            this.executor = executor;
            this.engine = engine;
            this.ctx = ctx;
        }

        // ── Publisher contract ────────────────────────────────────────────────

        @Override
        public void subscribe(Subscriber<? super AgentEvent> s) {
            // Reactive §2.13 — null subscriber MUST trigger NPE.
            if (s == null) {
                throw new NullPointerException(
                    "LiveRunPublisher.subscribe(null) — reactive-streams-1.0.4 §2.13 violated");
            }
            // Reactive §2.2 — single-subscriber enforcement.
            if (!subscribed.compareAndSet(false, true)) {
                // Per spec §3.4: deliver an onSubscribe(no-op Subscription) followed by
                // onError(IllegalStateException). We MUST NOT throw out of subscribe()
                // (that would bypass the spec's signal protocol) — we route via signals.
                try {
                    s.onSubscribe(new Subscription() {
                        @Override public void request(long n) { /* no-op — already terminated */ }
                        @Override public void cancel()    { /* no-op */ }
                    });
                    s.onError(new IllegalStateException(
                        "LiveRunPublisher: single subscriber only — reactive-streams §2.2 violated"));
                } catch (Throwable ignored) {
                    // Subscriber misbehaved on onSubscribe/onError (e.g. threw NPE).
                    // We MUST NOT propagate — that would let the caller bypass the
                    // signal protocol. Best we can do is drop and GC.
                }
                return;
            }
            // Bind the subscriber, then hand out this Subscription.
            sub.set(s);
            s.onSubscribe(this);
            // Do NOT start the engine if the caller cancelled between
            // AtomicBoolean.compareAndSet and here.
            if (cancelled) {
                return;
            }
            // 🆕 AC-049-02 — executor offload. Caller thread returns from subscribe()
            // immediately; engine.runTurn runs on the daemon pool.
            executor.execute(this::runTurnOnExecutor);
        }

        // ── Subscription contract ─────────────────────────────────────────────

        @Override
        public void request(long n) {
            // Reactive §3.9 — non-positive request signal MUST trigger onError.
            if (n <= 0) {
                Subscriber<? super AgentEvent> downstream = sub.get();
                if (downstream != null) {
                    try {
                        downstream.onError(new IllegalArgumentException(
                            "LiveRunPublisher: non-positive request signal n=" + n
                                + " — reactive-streams §3.9 violated"));
                    } catch (Throwable ignored) {
                        // Subscriber threw on onError. Per §2.13 we MUST NOT propagate;
                        // the subscription is now in a poisoned state but the executor
                        // thread will see cancelled=true via the flag set below.
                    }
                }
                // Mark cancelled so the bridge stops emitting.
                cancelled = true;
                // Wake any parked bridge.
                Thread t = engineThread.get();
                if (t != null) {
                    LockSupport.unpark(t);
                }
                return;
            }
            // Reactive §3.16 — addAndGet saturating at Long.MAX_VALUE. Long.MAX_VALUE
            // demand is the standard "unbounded" signal; we treat it as a one-shot.
            long prev;
            while (true) {
                prev = requested.get();
                if (prev == Long.MAX_VALUE) {
                    // Already unbounded — no need to update.
                    break;
                }
                long next = prev + n;
                if (next < 0) { // overflow
                    next = Long.MAX_VALUE;
                }
                if (requested.compareAndSet(prev, next)) {
                    break;
                }
                // CAS lost — retry.
            }
            // 🆕 Wake the parked bridge so it can deliver newly-allowed events.
            Thread t = engineThread.get();
            if (t != null) {
                LockSupport.unpark(t);
            }
        }

        @Override
        public void cancel() {
            // 🆕 AC-049-05 — cancellation propagates via ctx.isCancelled() polling
            // (Story #005 AC-04 200ms budget). We flip the flag (volatile write,
            // visible to the bridge thread) and unpark the bridge so it can exit
            // its park loop immediately rather than wait for the 50ms slice.
            cancelled = true;
            Thread t = engineThread.get();
            if (t != null) {
                LockSupport.unpark(t);
            }
        }

        // ── Executor thread body ──────────────────────────────────────────────

        private void runTurnOnExecutor() {
            // Capture ourselves so request(n) / cancel() can unpark us.
            engineThread.set(Thread.currentThread());
            long startNs = System.nanoTime();
            try {
                // LinearTurnEngine.runTurn only emits sink.onNext(...); it never calls
                // sink.onSubscribe/onComplete/onError. We bridge those into the
                // downstream Subscriber ourselves.
                engine.runTurn(ctx, new Subscriber<AgentEvent>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        // The engine treats `sink` as a fire-and-forget observer.
                        // We demand unbounded at the engine layer (the engine is
                        // already push-only and ignores back-pressure) and gate at
                        // the downstream boundary below.
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(AgentEvent e) {
                        if (cancelled) {
                            return;
                        }
                        Subscriber<? super AgentEvent> downstream = sub.get();
                        if (downstream == null) {
                            return;
                        }
                        // 🆕 AC-049-06 — back-pressure gate. While requested <= 0 and
                        // we have not yet exceeded the 1s park timeout, park this
                        // executor thread. request(n) wakes us up via unpark().
                        // 50ms slices so a request from another thread is observed
                        // within 50ms even if no one calls unpark.
                        while (!cancelled && requested.get() <= 0) {
                            long elapsed = System.nanoTime() - startNs;
                            if (elapsed >= PARK_TIMEOUT_NANOS) {
                                // Subscriber hasn't requested for 1s. Per Reactive §3.12
                                // we treat this as effectively cancelled: flip the flag
                                // and exit the bridge.
                                cancelled = true;
                                LOG.warn(
                                    "LiveRunPublisher: bridge parked > 1s with no demand — "
                                        + "auto-cancelling turn (event dropped: {}).",
                                    e.getClass().getSimpleName());
                                return;
                            }
                            LockSupport.parkNanos(PARK_NANOS);
                            // Re-check the flag after wakeup.
                            if (cancelled) {
                                return;
                            }
                        }
                        // Consume one demand. CAS-loop to handle concurrent request().
                        long current;
                        do {
                            current = requested.get();
                            if (current <= 0) {
                                // Lost the race to a concurrent cancel/timeout. Drop.
                                return;
                            }
                            if (current == Long.MAX_VALUE) {
                                current = Long.MAX_VALUE;
                                break;
                            }
                        } while (!requested.compareAndSet(current, current - 1));
                        // Deliver to the downstream Subscriber. Per Reactive §2.13 we
                        // MUST NOT propagate subscriber throws out of this method; if
                        // the downstream throws, cancel the subscription.
                        try {
                            downstream.onNext(e);
                        } catch (Throwable t) {
                            cancelled = true;
                            // Do NOT re-throw — would terminate the executor thread
                            // and lose all remaining events / terminal signal.
                            LOG.warn(
                                "LiveRunPublisher: downstream Subscriber.onNext threw — "
                                    + "cancelling (event={}, throwable suppressed: {})",
                                e.getClass().getSimpleName(), t.toString());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        // The engine doesn't currently emit onError, but if a future
                        // engine contract adds it, route to downstream.
                        if (cancelled) {
                            return;
                        }
                        Subscriber<? super AgentEvent> downstream = sub.get();
                        if (downstream != null) {
                            try {
                                downstream.onError(t);
                            } catch (Throwable ignored) {
                                // Subscriber.onError threw — §2.13 forbids propagate.
                            }
                        }
                    }

                    @Override
                    public void onComplete() {
                        // The engine doesn't currently emit onComplete; we synthesize
                        // it after engine.runTurn returns normally (see below). Kept
                        // here for future engine contracts.
                        if (cancelled) {
                            return;
                        }
                        Subscriber<? super AgentEvent> downstream = sub.get();
                        if (downstream != null) {
                            try {
                                downstream.onComplete();
                            } catch (Throwable ignored) {
                                // §2.13 forbids propagate.
                            }
                        }
                    }
                });
                // 🆕 Engine returned normally — synthesize onComplete per Reactive §2.7
                // ordering: all onNext have fired (engine is synchronous within the
                // executor thread), so terminal onComplete is valid here.
                if (!cancelled) {
                    Subscriber<? super AgentEvent> downstream = sub.get();
                    if (downstream != null) {
                        try {
                            downstream.onComplete();
                        } catch (Throwable ignored) {
                            // §2.13 forbids propagate.
                        }
                    }
                }
            } catch (Throwable t) {
                // 🆕 Engine threw — synthesize onError. Reactive §2.4 says after onError
                // no further signals are allowed; we set cancelled=true so any late
                // engine events are dropped (the engine caught the exception already).
                if (!cancelled) {
                    Subscriber<? super AgentEvent> downstream = sub.get();
                    if (downstream != null) {
                        try {
                            downstream.onError(t);
                        } catch (Throwable ignored) {
                            // §2.13 forbids propagate.
                        }
                    } else {
                        // No subscriber bound — at minimum surface to LOG so the
                        // executor thread doesn't silently swallow the engine failure.
                        LOG.error(
                            "LiveRunPublisher: engine.runTurn threw but no downstream "
                                + "subscriber was bound — error dropped: {}",
                            t.toString(), t);
                    }
                }
            } finally {
                // Release the thread reference so request()/cancel() don't unpark a
                // dead thread.
                engineThread.set(null);
            }
        }
    }
}