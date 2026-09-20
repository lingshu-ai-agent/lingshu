# Cancellation Pipeline Contract

**Story #005** — `CancellationToken` interface + `CancellationTokens` impl + 3-layer wiring + JVM shutdown hook.

This contract binds:
- `TurnContext.cancellation()` ↔ `ToolExecutionContext.cancellation()` (shared token identity)
- `CancellationTokens.create()` factory ↔ `SimpleCancellationToken` impl
- `AgentFactory.broadcastCancel()` ↔ JVM shutdown hook ↔ all in-flight `TurnContext.cancellation()`
- `LinearTurnEngine.runTurn` (FlowEngine, Slot 8) ↔ `LinearTurnEngine.dispatchParallel` (Action step) ↔ `LlmProvider.stream()` future (Story #005b defer)

---

## Sequence — Ctrl-C Happy Path

```
┌─ User (Ctrl+C) ─┐
│  SIGTERM         │
└────────┬────────┘
         │
         ▼
┌─ JVM shutdown hook "lingshu-shutdown-cancel" ──────────────────────┐
│   AgentFactory.broadcastCancel()                                     │
│     for (CancellationToken t : broadcastRegistry) {                 │
│         t.fire()  ← SimpleCancellationToken: AtomicBoolean CAS +   │
│                          sync iterate CopyOnWriteArrayList callbacks│
│     }                                                               │
└────────┬────────────────────────────────────────────────────────────┘
         │
         ▼ (sync)
┌─ All in-flight TurnContext.cancellation().isCancelled() == true ──┐
│                                                                      │
│   Thread A: LinearTurnEngine.runTurn loop head (next iteration):    │
│       if (ctx.cancellation().isCancelled()) {                        │
│           ctx.markDone();                                             │
│           sink.onNext(new TurnCompleted(CANCELLED, totalUsage));    │
│           break;                                                     │
│       }                                                              │
│                                                                      │
│   Thread A: LinearTurnEngine.runTurn LLM future wait:               │
│       try { resp = fut.get(200, MILLISECONDS); }                    │
│       catch (TimeoutException te) {                                  │
│           if (ctx.cancellation().isCancelled()) {                    │
│               fut.cancel(true);                                      │
│               last = null;                                           │
│               sink.onNext(new TurnCompleted(CANCELLED, totalUsage));│
│               break;                                                 │
│           } else { throw te; }                                       │
│       }                                                              │
│                                                                      │
│   Thread B/C/D/E (tool pool): LinearTurnEngine.dispatchParallel:    │
│       if (ctx.cancellation().isCancelled()) futures[i].cancel(true); │
│       catch (CancellationException ce) → ToolResult.error(cancelled)│
└────────┬────────────────────────────────────────────────────────────┘
         │
         ▼
┌─ Turn ends within 200ms ──────────────────────────────────────────┐
│   - session.history preserves partial assistant messages          │
│   - AuditLog receives TurnCompleted(CANCELLED, usage)             │
│   - JVM continues shutdown (no hang, since broadcast is sync)     │
└───────────────────────────────────────────────────────────────────┘
```

---

## Token Sharing Contract

| Aspect | Value | Source |
|---|---|---|
| **Identity** | `TurnContext.cancellation() == ToolExecutionContext.cancellation()` (same reference) | US2 S1 + FR-004 |
| **Lifecycle** | Created in `DefaultTurnContext.createWithBroadcast()` via `CancellationTokens.create()` | FR-003 + FR-005 |
| **Auto-register** | `createWithBroadcast()` calls `AgentFactory.registerCancellation(token)` on construction | FR-002 + FR-009 |
| **Auto-deregister** | `DefaultAgent.@PreDestroy` calls `AgentFactory.unregisterCancellation(token)`(US4 边界) | future Story |
| **Fire sources** | (a) `AgentFactory.broadcastCancel()` from JVM hook;(b) `DefaultTurnContext.markDone()` cascade;(c) session/turn timeout(Story #005b) | US4 + FR-006 |
| **Fire idempotency** | `AtomicBoolean.compareAndSet(false, true)` guarantees second `fire()` is no-op | NFR-005 + FR-005 |

---

## Cancellation Wires (Layered View)

| Layer | Component | Cancellation Code |
|---|---|---|
| **0 — Token** | `SimpleCancellationToken` (inner of `CancellationTokens`) | AtomicBoolean + CopyOnWriteArrayList<Runnable> |
| **0 — Token interface** | `ToolExecutionContext.CancellationToken` (nested interface) | `isCancelled()` + `onCancel(Runnable) → Runnable` + `default void fire()` |
| **1 — Turn context** | `TurnContext.cancellation()` (interface method, FR-001) | returns `DefaultTurnContext.cancellation` |
| **1 — Turn context impl** | `DefaultTurnContext.cancellation` (final field, FR-002) | holds token created by `createWithBroadcast` |
| **2 — Tool context** | `DefaultToolExecutionContext.cancellation()` (FR-004) | returns `turnCtx.cancellation()` (shared) |
| **3 — FlowEngine** | `LinearTurnEngine.runTurn` (FR-006 + FR-007) | polls at loop head + 200ms timeout fallback |
| **3 — FlowEngine** | `LinearTurnEngine.dispatchParallel` (FR-008) | polls before `futures[i].get(timeout, SECONDS)` |
| **4 — Broadcast** | `AgentFactory.broadcastCancel()` (FR-009) | for-each `broadcastRegistry` + `token.fire()` |
| **4 — Broadcast trigger** | JVM shutdown hook `Thread("lingshu-shutdown-cancel")` (FR-009) | @PostConstruct registers via `Runtime.getRuntime().addShutdownHook` |

---

## Edge Case Behavior

| Case | Expected Behavior |
|---|---|
| Empty registry + `broadcastCancel()` | no-op, INFO log "broadcast cancel: 0 active turn(s)" (US4 S1) |
| Second `fire()` after first | no-op, callbacks not re-fired (NFR-005) |
| Callback throws RuntimeException | `try-catch` per-callback, others continue (Edge Case) |
| `onCancel(callback)` then `unregister.run()` | callback removed, not fired |
| Ctrl-C in JVM startup (before AgentFactory init) | Hook fires → AgentFactory may not exist → JVM exits with hook error logged (out of scope) |
| Cancel after turn already `done()` | Token fires, but `LinearTurnEngine.runTurn` already exited — no-op (Edge Case) |

---

## Performance Contract

| Operation | Target | Source |
|---|---|---|
| `broadcastCancel()` for 100 in-flight turns | < 50ms total | NFR-001 + NFR-006 sync |
| `fut.get(200, MILLISECONDS)` fallback | Exactly 200ms after cancel | FR-007 |
| `Token.fire()` for empty callback list | < 1µs (single AtomicBoolean CAS) | FR-005 impl |
| `Token.onCancel(callback)` | < 10µs (CopyOnWriteArrayList.add) | FR-005 impl |

---

## Backward Compatibility

| Existing Code | Change Required |
|---|---|
| `DefaultTurnContext` existing constructor (Story #001) | Still works — delegates to new constructor with `CancellationTokens.create()` |
| `DefaultToolExecutionContext.cancellation()` anonymous no-op (Story #004) | Replaced with `turnCtx.cancellation()` — but anonymous's `fire()` was no-op so existing tools that ignore cancel are unaffected |
| `CancellationToken` interface (dsh §4.6 + Story #004 nested impl) | New `default void fire()` is back-compat — existing impls without fire() override use default empty |
| `TurnContext` interface | New `cancellation()` method → all impls must implement — only `DefaultTurnContext` exists in Story #005 scope |
| Tests in Story #001—#004 | Pass unchanged — no test code modification required (16 cases stay green) |