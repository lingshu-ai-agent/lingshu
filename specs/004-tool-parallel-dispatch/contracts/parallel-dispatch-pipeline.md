# Parallel Dispatch Pipeline Contract

**Story #004** — `LinearTurnEngine.dispatchParallel` + 5-step pipeline integration.

This contract binds:
- `LinearTurnEngine.runTurn` (FlowEngine, Slot 8) ↔ `ToolExecutor.dispatch` (Slot 2) ↔ `PermissionPolicy.check` (Slot 4)
- dsh §6.1 L3672-3709 dispatchParallel algorithm
- dsh §4.6 ToolExecutor 5-step pipeline (executed inside Slot 2, not duplicated by the engine)

---

## Sequence

```
LinearTurnEngine.runTurn                          ToolExecutor.dispatch
   │                                                    │
   │ (LLM returns tool_calls = [a, b, c, d])           │
   │                                                    │
   ├─ dispatchParallel(calls=[a,b,c,d], ctx, sink)     │
   │     │                                              │
   │     ├─ for each call:                              │
   │     │     CompletableFuture.supplyAsync({         │
   │     │       sem?.acquireUninterruptibly()          │
   │     │       try dispatchWithPolicy(call) {         │
   │     │         d = permissionPolicy.check(call) ────┼──► (Decision.Allow | Deny | AskUser)
   │     │         switch(d) {                          │
   │     │           Allow → toolExecutor.dispatch() ───┼──► 5-step pipeline
   │     │           Deny  → ToolResult.error(reason)   │     1. policy.check (already passed)
   │     │           AskUser → ToolResult.error(...)    │     2. ToolRegistry.lookup
   │     │         }                                    │     3. (Story #011) timeout wrap
   │     │       } finally sem?.release()               │     4. (Story #016) sandbox apply
   │     │     }, toolPool)                             │     5. tool.execute() + checkpoint
   │     │                                              │
   │     ├─ futures[i].get(timeoutSec, SECONDS)         │   ◄── may throw TimeoutException
   │     ├─ catch Timeout → ToolResult.error            │
   │     ├─ catch Interrupted → interrupt + error       │
   │     ├─ catch Execution → error                     │
   │     └─ return results[] (original LLM order)       │
   │                                                    │
   ├─ for (r in results) ctx.appendToolResult(r)        │
   ├─ sink.onNext(ObservationAppended(step, N))         │
   └─ continue loop / hit maxSteps                     │
```

---

## Concurrency Contract

| Aspect | Value | Source |
|---|---|---|
| Default `toolParallelism` | 8 | dsh §5489 `tool.parallelism: 8` |
| `parallelism=1` | Semaphore(1) → serial | dsh §3666 |
| `parallelism=N` (N≥2) | Semaphore(N) → max N concurrent | dsh §3667 |
| `parallelism<=0` | No Semaphore → unbounded | dsh §3668 |
| Default `toolTimeoutSeconds` | 60 | dsh §5489 `tool.timeout-seconds: 30` (config) / LinearTurnEngine uses cfg.getToolTimeoutSeconds() |
| `toolTimeoutSeconds=0` | Future.get() without timeout — wait forever | dsh §15 LINGS-T02 row note |
| Thread name prefix | `lingshu-tool-N` | FR-006 NFR-003 |
| Default pool size | `availableProcessors() * 2` | D-01 |
| Queue capacity | 256 (LinkedBlockingQueue) | D-01 |
| Rejected handler | `CallerRunsPolicy` | D-01 (back-pressure) |

---

## Result Order Contract

**FR-003**: Results are written to history **in LLM-return order**, not in completion order.

```java
// ✅ Correct — original order preserved
LlmResponse resp = llmProvider.stream(...);  // toolCalls = [a, b, c, d]
ToolResult[] results = dispatchParallel(resp.getToolCalls(), ctx, sink);
// results[0] is "a" (or error for a), results[1] is "b", ...
// Even if "b" completes before "a", results[0] still corresponds to "a".

for (int i = 0; i < results.length; i++) {
    ctx.appendToolResult(results[i]);  // history appends in [a, b, c, d] order
}
```

**Rationale**: LLM sees `tool_calls[i]` and expects `tool_results[i]` to be the response to `tool_calls[i]`. Reordering would scramble the model's reasoning context.

---

## Error Translation Contract

### Inside `ToolExecutor.dispatch` (DefaultToolExecutor)

| Original outcome | After FR-007/FR-008 translation |
|---|---|
| `ToolException.PermissionDeniedException` | `ToolResult.error(id, "Permission denied: <reason>")` |
| `ToolException.ToolNotFoundException` | `ToolResult.error(id, "Tool not registered: <name>")` |
| `ToolException.ToolTimeoutException` | `ToolResult.error(id, "Tool call exceeded timeoutSeconds=<n>")` |
| `ToolException.ToolCancelledException` | `ToolResult.error(id, "Tool call cancelled")` |
| Any other `RuntimeException` (NPE, ISE, ...) | `ToolResult.error(id, "tool error: <message>")` |
| Normal `tool.execute()` return | `ToolResult` as returned (status preserved) |

### Inside `dispatchWithPolicy` (LinearTurnEngine)

| `Decision` subtype | Outcome |
|---|---|
| `Decision.Allow` | `toolExecutor.dispatch(call, ctx)` — may return ToolResult.error per above |
| `Decision.Deny` | `ToolResult.error(id, d.getReason())` — bypasses ToolExecutor |
| `Decision.AskUser` | `ToolResult.error(id, "AskUser approval flow is wired in Story #005 follow-up")` |

### At `Future.get(timeoutSec, SECONDS)`

| Outcome | Result |
|---|---|
| Future completed with ToolResult | `results[i] = future.get()` |
| `TimeoutException` | `results[i] = ToolResult.error(call.getId(), "tool timeout after " + timeoutSec + "s")` + `future.cancel(true)` |
| `InterruptedException` | `Thread.currentThread().interrupt()` + `results[i] = ToolResult.error(call.getId(), "tool interrupted: <msg>")` |
| `ExecutionException` | `results[i] = ToolResult.error(call.getId(), "tool error: " + e.getCause().getMessage())` |

---

## Edge Behavior (from spec.md Edge Cases)

| Edge | Behavior |
|---|---|
| Empty `toolCalls` list | `dispatchParallel([])` → return empty `ToolResult[]`; no Semaphore; no future submission |
| Single tool call with parallelism=4 | Wall-clock = single_latency (no Semaphore overhead); still goes through CompletableFuture path |
| PermissionPolicy returns AskUser | Translated to ToolResult.error (Story #005 will replace this stub with ApprovalGate integration) |
| Tool throws NPE | Translated to ToolResult.error(id, "tool error: <msg>"); turn continues |
| `toolTimeoutSeconds=0` | `futures[i].get()` without timeout — wait forever; doc convention only, no runtime validation |
| `toolParallelism=0` | Documented as equivalent to ≤0 (unbounded); not a runtime error |
| Spring container missing `agentToolPool` | LinearTurnEngineProvider fails to construct → `LINGS-C02` startup fail |
| LinearTurnEngineProvider receives null ExecutorService | Constructor IAE → fail-fast |

---

## ToolPool Lifecycle Contract

```java
@Configuration
public class ToolExecutorConfig {
    @Bean(name = "agentToolPool")
    public ExecutorService agentToolPool() {
        return new ThreadPoolExecutor(
            /* corePoolSize    */ Runtime.getRuntime().availableProcessors() * 2,
            /* maxPoolSize     */ corePoolSize * 2,
            /* keepAliveTime   */ 60L, TimeUnit.SECONDS,
            /* workQueue       */ new LinkedBlockingQueue<>(256),
            /* threadFactory   */ new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "lingshu-tool-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            /* rejectedHandler */ new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    public void shutdown() { agentToolPool.shutdown(); }
}
```

**Rationale**:
- `daemon=true` ensures pool threads don't prevent JVM shutdown (Story #013 graceful shutdown uses `agentToolPool.shutdownNow()` separately)
- `CallerRunsPolicy` provides back-pressure: if queue is full, caller (LinearTurnEngine thread) runs the task inline — preserves bounded memory at cost of slower turns under heavy load
- Pool size = `cores * 2` matches dsh §14.7 "I/O-bound tool calls" rule of thumb

---

## Spring Wiring Diagram

```
@SpringBootApplication
├── @Component LinearTurnEngineProvider
│   └── @Autowired PromptBuilderRouter
│   └── @Autowired LlmProviderRouter
│   └── @Autowired ToolExecutorRouter        🆕 Story #004
│   └── @Autowired PermissionPolicyRouter    🆕 Story #004
│   └── @Autowired @Qualifier("agentToolPool") ExecutorService  🆕 Story #004
│
├── @Component DefaultToolExecutor
│   └── @Autowired PermissionPolicy (resolved via Router)
│
├── @Configuration ToolExecutorConfig      🆕 Story #004
│   └── @Bean(name = "agentToolPool") ExecutorService
│
└── @Component EchoLlmProvider (Story #001 default)
    └── @Autowired Provider lists (LlmProviderRouter, etc.)
```

---

## Invariants (do NOT change in this Story)

1. `Tool` / `ToolExecutor` / `PermissionPolicy` / `Decision` interface signatures — frozen
2. `AgentEvent.ToolCompleted` event class — frozen (still 1 field: `ToolResult result`)
3. `AgentConfig` field set — frozen (no new field; uses existing `toolParallelism` / `toolTimeoutSeconds`)
4. `ToolException` subclass tree — frozen (FR-007/FR-008 only handles at runtime, not class hierarchy)
5. `LinearTurnEngine` ReAct 5-step sequence (build / stream / dispatchParallel / appendToolResult / ObservationAppended) — frozen order
6. `dispatchWithPolicy` order: check → switch → execute — frozen
7. Result order = LLM-return order (not completion order) — frozen
8. Zero new Maven dependencies — frozen
