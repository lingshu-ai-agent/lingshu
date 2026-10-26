# Changelog

All notable changes to **LingShu (灵枢) Agent Engine** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **🚢 First public release**:v0.1.0 is the inaugural public release of LingShu,
> published on **2026-10-07**. Every entry below was developed during the
> pre-release phase and lands in this release.

---

## [0.3.0] - 2026-10-26

### Summary

**LingShu 0.3.0** is the third stable release of the LingShu Agent Engine —
a **protocol-correctness hardening** cycle over v0.2.0 on two fronts:
(1) **MCP protocol compliance becomes black-box provable** — Story #054
rewrites the MCP test fixtures against the spec and closes the two review
defects left by external PR #90, and (2) **a production chat-compaction bug
that broke the Anthropic Messages API is root-caused and fixed** — the
`TruncatingCompactor` sliding window could drop an `Assistant` while keeping
its trailing `ToolResult`, and the next turn failed with HTTP 400
("Each `tool_result` block must have a corresponding `tool_use` block").
Compaction telemetry is now honest: `AgentEvent.Compacted` carries the real
before/after payload instead of a hardcoded workaround that made every
compaction look like a no-op. **Zero SPI breakings** vs. v0.2.0.

This release ships **1 Story + 2 production fixes** via 5 PRs since v0.2.0
(including 1 external contribution), introduces **0 new Maven dependencies**,
and holds the **R-13 mitigation (d) 0 binary delta** streak at **37
consecutive PASSes**.

### Headline Numbers

| Metric | v0.2.0 | **v0.3.0** | Delta |
|---|---|---|---|
| **Stories shipped (cumulative)** | 55 | **56** | +1 (Story #054) |
| **PRs since v0.2.0** | — | **5** (1 external) | +5 |
| **Tests passing (lingshu-core)** | 802 | **831** | +29 |
| **Tests passing (demo-product)** | 6 | **11** | +5 |
| **Maven dependencies introduced** | 0 | **0** | 0 (R-13 (d) maintained) |
| **R-13 mitigation (d) PASS streak** | 36 | **37** | +1 consecutive |
| **`@PublicApi(STABLE)` SPI count** | 14 | **14** | unchanged |
| **Breaking changes** | — | **0** | D4 promise honored |
| **External contributors (this cycle)** | — | **1** (`xiaobaibaiaaa`) | +1 |
| **License** | Apache-2.0 | **Apache-2.0** | unchanged |
| **Java source level** | 1.8 | **1.8** | unchanged |
| **Java runtime** | JDK 17+ | **JDK 17+** | unchanged |
| **Distribution channel** | GitHub Packages | **GitHub Packages** | unchanged |

### Fixed — Anthropic HTTP 400 after chat-triggered compaction (2026-10-25 production bug)

- **Root cause** — `TruncatingCompactor.applySlidingWindow`'s cut point
  dropped an `Assistant` message while keeping its trailing `ToolResult`s.
  Anthropic requires every `tool_result` block to pair with a preceding
  `tool_use` in the same request, so the first chat turn after a
  compact-triggered window cut failed with HTTP 400.
- **Pair-safe cut** — The cut index now advances past all `ToolResult`
  messages that follow the first dropped `Assistant`, so `tool_use` /
  `tool_result` pairs never straddle the window boundary.
- **Anchor User** — After a mid-turn cut the window could open with role
  `assistant`; the nearest preceding `User` message is re-inserted at the
  window head (unless the window already opens with one) to keep the
  first-message-must-be-`user` invariant.
- **Defense in depth** — `AnthropicLlmProvider.buildRequestBody` now tracks
  seen `tool_use` ids and drops (WARN) any `ToolResult` whose `toolUseId`
  has no surviving `Assistant` call, so a future pairing bug cannot surface
  as a raw HTTP 400 again. Empty/missing `tool_use_id` still fails hard
  with `LINGS-L02`.
- **Regression tests** — sliding-window pair safety, anchor-User insertion,
  no duplicated anchor User, and protocol-boundary orphan drop (PR #94).

### Fixed — MCP stdio `args` hardcoded contributor machine path (PR #90 defect, Story #054)

- External PR #90 hardcoded a Windows absolute path (`d:\lingshu\...`) into
  demo-product's stdio MCP server `args:`, breaking any other machine / CI.
  Reverted to the Story #025 relative path (1-line yml) plus a guard test.
  `StdioMcpServerConnection.spawn()` never sets `.directory(...)`, so the
  child process inherits the JVM working directory and relative paths were
  always the portable choice.

### Added — Honest compaction telemetry (`AgentEvent.Compacted` real payload)

- `AgentEvent.Compacted` gains `beforeSize` / `afterSize` /
  `approxTokensFreed`, with a no-arg back-compat constructor defaulting to
  zeros (same pattern as `ApprovalRequired`). `LinearTurnEngine` measures
  the real history-size and token-estimate delta around
  `compactor.compact()` (clamped `>= 0`).
- **demo-product R-1 workaround deleted** — `AgentEventMapper.lastHistorySize`
  + `resetBaseline` hardcoded `afterSize=beforeSize` and
  `approxTokensFreed=0`, which made every compaction look like a no-op on
  the wire and misled the 2026-10-25 debugging session. The mapper is
  stateless again and forwards the payload verbatim.
- The demo-product chat UI's `compacted` bubble now shows
  `beforeSize → afterSize` alongside `approxTokensFreed`.
- `TruncatingCompactor.estimateTokens` widened package-private → `public`
  so the engine and `shouldCompact()` share one token-estimate source.

### Added — MCP protocol compliance black-box suite (Story #054)

- **MCP compliance goes from "documented claim" to "black-box provable".**
  PR #90's ~821-line MCP protocol rewrite shipped with zero acceptance
  tests and a fixture suite (`TestMcpHttpServer` 5-endpoint scheme, fixed
  JSON-RPC id `1L`, a `/sse` handler that never announced the `endpoint`
  event) that had been superseded by the single-endpoint semantics — unit
  tests stayed green while real MCP servers could not connect.
- **Fixture rewrite per the MCP spec** — `TestMcpHttpServer` (1,249 lines)
  rewritten + 2 new test files (`McpHttpSupportPostJsonRpcSpecTest` 12 cases
  + `StreamableHttpMcpServerConnectionSessionRecoveryTest` 4 cases) + 5
  existing test files updated in place: **8 test/fixture files, 0
  production files** (protocol behavior is carried by the existing
  implementation; this Story only proves it).
- **Coverage** — SSE 4-step handshake (endpoint announcement / announcement
  address resolution / id-correlated replies / `ping` keepalive), Streamable
  HTTP single-endpoint + session replay + `tools/list` re-fetch (FR-029),
  404 recovery **once** / **not twice**, no-session no-recovery, dual
  content-type equivalence (`application/json` vs `text/event-stream`),
  and `McpHttpSupport.postJsonRpcSpec` bidirectional codec.
- **Full regression Errors 18 → 0** (`lingshu-core` tests 802 → 824,
  +22 net new cases). SC-003 / SC-004 counter-proof shipped (flipping
  "retry once" to "retry 3x" turns exactly the 4 contract-guard cases red,
  then reverted).
- **R-13 mitigation (d) 0 binary delta 37th PASS** (pre/post dependency-tree
  mirror identical). 0 new Maven dependencies, 0 new ErrorCodes.

### Hygiene / Refactor (v0.3.0)

- **`lingshu-a2a-client` stale `@Bean` count assertion fixed** —
  `HttpJsonRpcA2aTransportAutoConfigurationTest` still expected 2 `@Bean`
  methods after the `agentCardCache` bean landed (commit `5200a84` / #75),
  failing `mvn test` on main with "expected: 2 but was: 3". The expected
  count is updated to 3 and the comment documents the intentionally shipped
  surface (provider + `agentCardCache` + factory). Test-only; the counting
  assertion itself is kept.
- **Docs sync** — MCP SSE + Streamable HTTP protocol-compliance
  documentation rewritten alongside PR #90's implementation (PR #90
  follow-up).
- **Constitution R-13 accounting drift fixed** — the "mitigated" Story list
  had stalled at `#049`; `#050`–`#054` backfilled per user ruling
  (accounting only, no charter principle changes).

### Compatibility

#### API Compatibility Promise (D4)

- **Zero breaking changes vs. v0.2.0.** All 14 `@PublicApi(STABLE)`
  interfaces preserve their public method signatures:
  - `AgentEvent.Compacted` — new 3-arg constructor + 3 fields; the no-arg
    constructor is preserved and defaults to zeros (existing `instanceof`
    filters and callers compile unchanged).
  - `TruncatingCompactor.estimateTokens` — visibility widened to `public`
    (purely additive).
  - MCP fixes are internal to transport/test layers; no SPI signature moves.
- **`@PublicApi(STABLE)` count** — 14 (unchanged).

#### Dependency Compatibility (R-13 mitigation (d))

**13 locked dependencies** — **unchanged** from v0.1.0 / v0.2.0. **0 new
Maven dependencies** in the v0.3.0 cycle. **R-13 mitigation (d) 0 binary
delta streak: 37 consecutive PASSes** (Story #043 through Story #054).
The `banned-dependencies` enforcer rule keeps rejecting the same 5 banned
artifacts.

### Security

v0.3.0 inherits all v0.2.0 security properties unchanged (sandbox
isolation, permission policy + ask-mode approval round-trip, MCP/A2A HTTP
domain guards, `ToolExecutor.dispatch()` single-entry rule, anti-verify-loop
default), and adds:

- **Anthropic protocol boundary guard** — orphan `tool_result` blocks are
  dropped with a WARN before they reach the API, hardening the Messages API
  request path against future pairing regressions.

### Known Issues / Limitations

Carried from v0.1.0 / v0.2.0:

- **`ServeHandlerTest`** hang in `lingshu-cli` under JDK 21+ (pre-existing).
  Workaround: `mvn -pl lingshu-cli test -Dtest='!ServeHandlerTest'`.
- **`AnthropicToolReActIT`** 2 cases fail in standalone mode (pre-existing,
  passes in full-suite mode).
- **`McpServerConnectionIT`** heartbeat flake — occasional 60-second-cap
  timeouts under heavily loaded runners (<0.5% of runs).

Newly observed (2026-10-26 full-reactor run):

- **MCP transport reconnect tests** — intermittent timing flakes under
  heavy suite load: `StreamableHttpMcpServerConnectionReconnectTest.reconnect_afterSessionExpiry_reRunsHandshake`
  and `SseMcpServerConnectionReconnectTest.sseStreamBroken_reconnects` can
  observe the state timeline out of order (a `DISCONNECTED` mark landing
  after the reconnect `CONNECTED`). Pass in isolation and passed 831/831
  in low-load runs; same class of timing sensitivity as the
  `McpServerConnectionIT` flake.
- **demo-engineer / demo-spi / demo-parallel `BlackBoxVerificationTest`**
  Spring context circular-reference startup failures
  (`YamlTenantConfigProvider` ↔ application bean) — pre-existing; those
  modules are untouched since v0.1.0.

Deferred to v0.4.0 (carried forward from the v0.2.0 roadmap):

- **Story #016 `AuditLogger` production implementation** (PostgreSQL +
  OpenTelemetry exporters)
- **§14 N6 graceful shutdown** (`LINGS-L03` slot stays dormant)
- **§14 N4 `CostBudget`** + structured `AgentEvent.CostExceeded`
- **`SummaryCompactor`** alongside `TruncatingCompactor`
- **Spring Boot starter** artifact
- **Story #055 `mcp-http-legacy-postjsonrpc-cleanup`** (dead legacy helper
  found during Story #054)

### Upgrade / Migration Notes

For v0.2.0 consumers upgrading to v0.3.0:

1. **Drop-in compatible at the API surface.** All 14 `@PublicApi(STABLE)`
   interfaces unchanged; `lingshu-core` v0.3.0 is API-compatible with
   v0.2.0 binaries.
2. **`AgentEvent.Compacted` payload** — New fields `beforeSize` /
   `afterSize` / `approxTokensFreed`. SSE consumers may read the new
   `compacted` JSON fields; the no-arg constructor still exists for
   in-process callers.
3. **demo-product `AgentEventMapper`** — Now stateless;
   `lastHistorySize` / `resetBaseline` removed. Only affects demo-product
   internals (and forks that copied the R-1 workaround).
4. **Compaction window cuts are stricter** — histories that previously
   produced orphan `tool_result`s are now cut pair-safely (fewer messages
   retained at the boundary). This is the bug fix; no configuration change.

No `pom.xml` version bumps required for transitive consumers.

### Acknowledgments

- **`xiaobaibaiaaa`** — External contribution (PR #90) of the MCP protocol
  compliance rewrite + whitelist configuration fix that this cycle's
  Story #054 hardened into black-box-provable compliance.

---

## [0.2.0] - 2026-10-19

### Summary

**LingShu 0.2.0** is the second stable release of the LingShu Agent Engine —
a minor version increment over v0.1.0 that delivers the **9 Slot × 9 Plugin
100% production-wired milestone** (every SPI is now end-to-end callable, not
just stubbed), plus a production-grade live-streaming API and a Claude-Code-parity
anti-verification-loop default. **Zero SPI breakings** vs. v0.1.0: all changes
are additive features behind existing contracts, so D4 `STABLE` consumers
can upgrade without recompilation.

This release ships **10 Stories** via 9 PRs since v0.1.0 (~830 tests passing),
maintains the **Apache-2.0 licensing** with full SPDX coverage, and extends
the **R-13 mitigation (d) 0 binary delta** streak to **36 consecutive PASSes**
(no new Maven dependencies introduced in the 0.2.0 cycle).

### Headline Numbers

| Metric | v0.1.0 | **v0.2.0** | Delta |
|---|---|---|---|
| **Stories shipped (cumulative)** | 45 | **55** | +10 |
| **PRs since v0.1.0** | — | **9** | +9 |
| **Tests passing** | 766 | **~830** | +64 |
| **Maven dependencies introduced** | 0 | **0** | 0 (R-13 (d) maintained) |
| **R-13 mitigation (d) PASS streak** | 29 | **36** | +7 consecutive |
| **`@PublicApi(STABLE)` SPI count** | 14 | **14** | unchanged |
| **Breaking changes** | — | **0** | D4 promise honored |
| **License** | Apache-2.0 | **Apache-2.0** | unchanged |
| **Java source level** | 1.8 | **1.8** | unchanged |
| **Java runtime** | JDK 17+ | **JDK 17+** | unchanged |
| **Distribution channel** | GitHub Packages | **GitHub Packages** | unchanged |

### Added — Slot 5 SessionStore save-after-turn (Story #014 + Story #046)

- **Story #014 — session-store-multi-backend**:Two backend implementations
  (`InMemorySessionStore` default + `FileSessionStore` atomic write protocol)
  via the v1.5.28 multi-Provider pattern. `Checkpoint.@Value` gains Jackson
  `@JsonCreator` / `@JsonProperty` 4-arg ctor + `Message.@JsonTypeInfo` /
  `@JsonSubTypes` for round-trip serialization across all 4 `Message`
  subclasses. Multi-tenant `resolveBaseDir()` via `TenantContext.current()` +
  `_default` sentinel + regex-validated session IDs
  (`[a-zA-Z0-9._-]{1,128}`) blocking path traversal. `IOException` /
  `JsonProcessingException` wrapped as `LingsSessionStoreException[LINGS-X01]`.
- **Story #046 — session-store-save-after-turn**:Closes the `SessionStoreRouter`
  Javadoc-stated "follow-up Story" gap. `LinearTurnEngine` 7→24-field, then 8
  args, adding `SessionStore sessionStore` final field + 5 turn-completion
  hook points (`trySave(ctx)` swallow-failure guard). `NullSessionStore`
  sentinel protects Story #001–Story #014 historical fixtures (no `sessionStore`
  wiring = no-op). `LinearTurnEngineProvider` 8-arg ctor adds
  `SessionStoreRouter` injection using the **Spring 6 dual-ctor `@Autowired`
  pattern** (legacy 7-arg ctor delegates to 8-arg with `null`; primary 8-arg
  ctor carries `@Autowired`). `AgentFactory` ctor expands 8→9 Router slots
  (`@Autowired SessionStoreRouter`). `agent.session-store: file` YAML key
  now actually resolves + persists to disk. End-to-end test:
  `Files.list(sessionStoreDir).count() == 1` after a full turn + history round-trip.

### Added — Slot 6 Compactor turn-trigger (Story #045)

- **Story #045 — compactor-turn-trigger**:**9-Slot × 9-Plugin 100% real-wired
  milestone.** `LinearTurnEngine` 6→7 args, adding `Compactor compactor`
  final field with 4 invocation points (per-step ToolResult, maxSteps guard,
  cancel, exception). `NullCompactor` sentinel for historical fixture back-compat.
  `LinearTurnEngineProvider` 7-arg ctor + Spring 6 dual-ctor `@Autowired`
  pattern (second deployment of this pattern — now a project member). `TruncatingCompactor`
  default `priority=10` registers via `CompactorRouter.resolve("truncating", cfg)`
  driven by `agent.config.compactor: truncating` top-level YAML key.
  `AgentEvent.Compaction` 4-segment event subclass family
  (`Started` / `Progress` / `Completed` / `Failed`).

### Added — Ask-Mode End-to-End + Sentinel ToolResult (Stories #047 + #048)

- **Story #047 — ask-policy-end-to-end-wiring**:Two latent silent-ignored
  wiring gaps closed. (1) `AgentFactory.java:280` typo
  `getSandbox().getPolicy()` → `getPermissionPolicy()` (Slot 3 `RuntimeSandbox.getPolicy()`
  vs Slot 4 `getPermissionPolicy()` name drift). (2) `demo-product`
  `ChatController.createSession` 28-arg `AgentConfig.builder()` hand-roll
  bypassed `application.yml`'s `permission-policy: ask` + `tools.ask-list: [bash]`.
  Refactored to `agentFactory.create(loadYaml)` — `@Bean`-injected `AgentConfig`
  is now actually consulted, so `PermissionPolicyRouter.resolve("ask", cfg)`
  picks the right Provider. `PermissionPolicyRouter` internal `byName` Map
  now contains 3 entries (default + strict + ask).
- **Story #048 — ask-user-sentinel-tool-result (hang-safety)**:**Anthropic
  protocol invariant fix.** `LinearTurnEngine.dispatchWithPolicy` AskUser
  branch now commits a `[PENDING_APPROVAL] Awaiting user decision for tool: <name>`
  sentinel `ToolResult.error(...)` to history via `turnContext.appendToolResult()`
  **before** blocking on `future.get()`. Without this, hang scenarios
  (overnight approval, browser close, demo-product crash) left history stuck
  at `Assistant(tool_use) → ???` with no matching `tool_result`, breaking
  Anthropic's protocol hard constraint on the next turn (HTTP 400 "messages.X:
  tool_use ids were found without tool_result blocks"). `DefaultTurnContext.appendToolResult`
  gains upsert-by-toolUseId semantics: scans history tail for an
  `Assistant(toolCalls[i].id == toolResult.toolUseId)` and in-place replaces /
  appends if not found — preventing sentinel duplication on retry.

### Added — Live Reactive Streaming API (Story #049)

- **Story #049 — agent-run-live-streaming**:**Reactive Streams 1.0.4 hot-lazy
  `Publisher<AgentEvent>` real-wired on `DefaultAgent.run(Prompt)`.**
  Story #001's drain-and-replay synchronous path remains 100% preserved on
  `runBlocking(Prompt) → LlmResponse` (4-case reverse AC suite). Strict spec
  compliance: §1.3 cancel-terminal-state / §1.9 null-check / §2.2 single-subscriber
  (`AtomicBoolean subscribed` guard) / §2.7 serial onNext / §3.9 non-positive
  `request(long n)` → `IllegalArgumentException`. `PARK_NANOS=50ms` back-pressure
  + `PARK_TIMEOUT_NANOS=1s` cancel-safe wake. First-event latency drops from
  O(turn) ≈ hundreds-of-ms to O(emit) ≈ single-digit-ms — Claude-Code-grade
  UX parity.

### Added — Ask-Mode Hardening + Verification-Loop Guard (Stories #050 + #051)

- **Story #050 — ask-loop-guard**:Two production bugs fixed. (1) SSE step
  counter reset on each `message_start` (Anthropic emits `message_start` per
  tool_use round — engine‑level `ReasoningStarted(step, maxSteps)` was being
  overwritten). New `AgentEvent.LlmStreamStarted(streamId, maxOutputTokens)`
  nested class as the correct translation; engine step counter now stays
  stable 1/10 → 2/10 → … → 10/10. (2) Ask-mode infinite hang: human Deny
  → same `toolUseId` re-issued → blocks forever on re-approval. New
  `attemptedDeny: Set<String>` field tracked in `LinearTurnEngine` (turn-scoped
  reset at `runTurn()` head). AskUser-branch guard substitutes a
  `[LINGS-P02] tool previously denied: <name>` sentinel on second occurrence.
- **Story #051 — duplicate-tool-call-detector (Option A prompt-fix)**:
  LLM semantic verification-loop behavior observed in 33-step SSE traces
  (LLM writes → reads to verify → reads again to re-verify → …). Pre-Option-A
  4-file engine-guard plan **withdrawn** by user choice (engine guards risk
  false-positives on legitimate retry / atomic-edit / append-by-position).
  Replaced with opt-in prompt engineering: `DefaultPromptBuilder.ANTI_VERIFY_LOOP_CLAUSE`
  static constant appended in INSTRUCTIONS segment when
  `instructions.anti-verify-loop: true`. `AgentConfig.Instructions` 4→5 args
  adding `boolean antiVerifyLoop` (Jackson `@JsonProperty("anti-verify-loop")`
  kebab-case). Off-by-default preserves 100% back-compat for Story #002—Story #050
  historical fixtures.

### Added — Anti-Verify-Loop Default Flip (Story #053)

- **Story #053 — anti-verify-loop-default-on**:Default flip of
  `Instructions.empty()` factory last arg `false → true` + `AgentFactory.toAgentConfig`
  YAML `booleanOr` default → `true`. Demo-product `application.yml` adds
  explicit opt-out comment (`# anti-verify-loop: false`). **LLM verification-loop
  fix is now zero-config out-of-the-box** — business side operators no longer
  need to remember the opt-in. Engine paths in `LinearTurnEngine` /
  `DefaultAgent` / `DefaultToolExecutor` remain untouched (Story #051
  pre-Option-A 4-file engine-guard plan stays withdrawn). Sentinel helper
  reused from Story #051.

### Added — Parallel Dispatch Protocol-Safety (Story #052)

- **Story #052 — dispatch-parallel-protocol-safety**:**Anthropic protocol
  invariant boundary guard for `LinearTurnEngine.dispatchParallel` multi-tool-call
  path.** Two result-shape gaps that would break "every `tool_use` must have
  a matching `tool_result` by `toolUseId`": (1) downstream SPI returns `null`
  (MCP timeout, nack); (2) downstream SPI returns `ToolResult` with wrong
  `toolUseId` (port conflict, prompt cache reuse staleness). New
  `LINGS_L04 = "LINGS-L04"` (`PARALLEL_DISPATCH_RACE`) + private static
  `dispatchCancelledResult(toolUseId, content)` 5-line helper substitutes a
  `[LINGS-L04] …` sentinel `ToolResult` matching the original call's
  `toolUseId`. Five guard × five-race prevention coverage:
  cancel-before-dispatch / exception / null-result / wrong-id / cancel-during-dispatch.

### Hygiene / Refactor (v0.2.0)

- **`UPDATEINFO.md` split from README** (#78) — Verbose per-Story changelog
  blocks moved out of README into a dedicated `UPDATEINFO.md` to keep README
  scannable.
- **`README.md` + `specs/ROADMAP.md` sync** (Story #053 doc sync, commit
  `9a4c537`) — Top-block "更新日期" + "Story 路线图" updated with
  Story #046 / Story #049 / Story #050 / Story #052 / Story #053 retrospectives.

### Bug Fixes (v0.2.0)

- **`demo-product` AgentCardCache missing-bean startup failure** (#75) —
  `RemoteAgentSchemaBuilder` autowire chain added the missing `AgentCardCache`
  bean so demo-product-a2a-server starts cleanly.
- **Windows backslash path tool-name normalization** (#80 + regression #81) —
  `ClasspathSkillSource` description parser failed on Windows-style paths;
  normalization + regression case shipped.
- **`demo-product` yml `session-store` / `compactor` field silent-ignored**
  (#82) — Field names didn't bind to runtime config; now resolves via
  `agentFactory.create(loadYaml)` round-trip.
- **`release.yml` `mvn verify deploy` → `mvn clean deploy`** (carried in
  v0.1.0 release, but worth re-documenting) — Two-pass lifecycle fires
  `source:jar-no-fork` twice, producing "duplicated artifacts" error.

### Compatibility

#### API Compatibility Promise (D4)

- **Zero breaking changes vs. v0.1.0.** All 14 `@PublicApi(STABLE)` interfaces
  preserve their public method signatures. New features are:
  - new constructor parameters (Lombok `@Value` / `@AllArgsConstructor` adds
    new fields; existing call sites continue to compile via the
    `@AllArgsConstructor` regen pattern)
  - new fields on `AgentConfig` (24 → 26 fields added over the v0.1.0 cycle;
    all default-valued)
  - new SPI sub-cases (`SlotRouter` continue to work; new Providers register
    alongside existing ones)
- **`@PublicApi(STABLE)` count** — 14 (unchanged). New entries planned for
  v0.3.0 candidate SPIs are tagged `INCUBATING` first.

#### Java Compatibility

- **Source level**: Java 1.8 (`<source>1.8</source>`). Code continues to
  avoid JDK 9+ features: `record` / `sealed` / `var` / `List.of(...)` /
  pattern-switch / text blocks. Reactive Streams 1.0.4 stays the canonical
  reactive interface (JDK 9+ `Flow` not used for cross-library compat).
- **Runtime**: JDK 17+ required (Spring Boot 3.2.5 hard requirement).
  Tested against Temurin / Zulu / Alibaba Dragonwell / IBM Semeru.

#### Dependency Compatibility (R-13 mitigation (d))

**13 locked dependencies** — **unchanged** from v0.1.0. **0 new Maven
dependencies** in the entire 10-Story v0.2.0 cycle. **R-13 mitigation (d)
0 binary delta streak: 36 consecutive Story merges** (Story #043 through
Story #053).

The Maven Enforcer plugin's `banned-dependencies` rule still rejects the
same 5 banned artifacts (Spring AI starters / `jtokkit`). Build-time failure
on any new dependency introduction continues to enforce the dsh §10.1
lock table.

### Security

v0.2.0 inherits all v0.1.0 security properties unchanged:

- **Sandbox isolation (Slot 3)** — All Tool filesystem / HTTP / process
  calls flow through `RuntimeSandbox`. `AccessDeniedException[LINGS-S01]`
  on out-of-bounds access.
- **Permission policy** — `StrictPermissionPolicy` pattern matching +
  `AskUserPermissionPolicy` 5-segment decision + `ApprovalRegistry` SSE
  round-trip + `attemptedDeny` turn-scoped guard (Story #050).
- **MCP HTTP domain guard (Story #033)** — 12 hook points in SSE /
  streamable HTTP transports reject off-whitelist hosts.
- **A2A HTTP domain guard (Story #034)** — Per-remote-agent domain whitelist
  in `HttpJsonRpcA2aTransport`.
- **No `Spring AI ChatClient.tools().call()`** — `ToolExecutor.dispatch()`
  5-step pipeline is the single entry point. Auto-tool-execution via Spring
  AI is **forbidden** (dsh §4.10.1 硬规则 2).
- **Anti-verification-loop default (Story #053)** — Defends against LLM
  semantic traps that could otherwise induce runaway loops consuming tokens
  without making progress (lower-cost, lower-impact, no new code-execution
  surface).

### Known Issues / Limitations

Carried from v0.1.0:

- **`ServeHandlerTest`** hang in `lingshu-cli` standalone mode (pre-existing,
  unrelated to v0.2.0 changes). Workaround: `mvn -pl lingshu-cli test
  -Dtest='!ServeHandlerTest'`.
- **`AnthropicToolReActIT`** 2 cases fail in standalone mode (pre-existing,
  passes in full-suite mode). Documented in Story #043 retrospective.
- **`McpServerConnectionIT`** heartbeat flake — 2 of the MCP heartbeat-recovery
  tests occasionally time out at the 60-second cap under heavily loaded CI
  runners. Timing-sensitive, not deterministic. Affects <0.5% of CI runs.

Deferred to v0.3.0 (announced in v0.1.0 release notes; explicitly **not**
shipped in v0.2.0 — see "What's Next"):

- **`AuditLogger` concrete production implementation** (PostgreSQL + OpenTelemetry
  exporters). v0.1.0 shipped the SPI stub + `NoOpAuditLogger` default;
  v0.2.0 adds no concrete logger (Story #016 reserved).
- **§14 N6 graceful shutdown** — Drain in-flight turns on `SIGTERM`. The
  `LINGS-L03` reserved ErrorCode slot stays dormant.
- **§14 N4 CostBudget** — Per-session token/cost caps with structured
  `AgentEvent.CostExceeded` event.
- **`SummaryCompactor`** — LLM-driven summarization compactor alongside the
  default `TruncatingCompactor`.

### Upgrade / Migration Notes

For v0.1.0 consumers upgrading to v0.2.0:

1. **Drop-in compatible at the API surface.** All 14 `@PublicApi(STABLE)`
   interfaces are unchanged. `lingshu-core` v0.2.0 is API-compatible with
   all v0.1.0 binaries.
2. **`AgentConfig` schema** — 26 fields (unchanged from v0.1.0; the
   `maxConcurrentTurns` + `maxConcurrentQueueDepth` additions were already
   in v0.1.0). No new fields added in v0.2.0.
3. **`AgentConfig.Instructions` schema** — 4 → 5 fields. New
   `boolean antiVerifyLoop` field **defaults to `true`** (Story #053).
   To restore v0.1.0 behavior: add `agent.instructions.anti-verify-loop: false`
   to your `application.yml`.
4. **`LinearTurnEngine` ctor arity** — 6→7 (Story #045) → 8 (Story #046).
   Custom subclassers must add the new ctor parameters; Spring autowiring
   handles the wiring transparently for the supplied `LinearTurnEngineProvider`.
5. **`AgentFactory` ctor arity** — 8-Router → 9-Router (Story #046 added
   `SessionStoreRouter`). Custom `AgentFactory` subclassers must add the
   new Router parameter.
6. **Plugin authors** — Continue implementing `SlotProvider`. v0.2.0
   introduces no new Slot, but the 9-Slot × 9-Plugin 100% real-wired
   milestone means every `agent.<slot>.name` YAML key now actually
   resolves + takes effect (no silent-ignored fields).
7. **`AgentEvent.LlmStreamStarted`** — New event subclass; SSE consumers
   may need to handle the new `llm.stream.started` event type for stable
   step counters.

### Acknowledgments

- **Apache DSH / Dubbo SPI** — LingShu's design draws inspiration from
  Apache DSH (Dynamic Service Hosting) and Apache Dubbo's plugin / SPI
  architecture.
- **Spring Boot 3.2.5** — Provides the DI container, configuration
  properties, and `@AutoConfiguration` machinery.
- **Spring AI 1.0.0-M6** — Used **only** for LLM protocol conversion
  + `@Tool` JSON Schema generation (NOT for tool execution).
- **Reactive Streams 1.0.4** — Story #049 `DefaultAgent.run()` `Publisher`
  implementation strictly conforms to the spec.

---

## [0.1.0] - 2026-10-07

### Summary

**LingShu 0.1.0** is the first public release of the LingShu Agent Engine —
a JDK 8+ Java Agent engine, Spring Boot SPI, ReAct Loop, **9 pluggable Slots**.
This release bundles **45 Stories** (~720 tests passing), an **Apache-2.0 licensed**
codebase with SPDX headers across all 435 Java files, and **14 `@PublicApi(stable)`
SPI interfaces** that form the locked backwards-compatibility contract.

### Headline Numbers

| Metric | Value |
|---|---|
| **Stories shipped** | 45 (incl. 1 hygiene refactor) |
| **Source files** | 435 Java files with Apache-2.0 SPDX headers |
| **Tests passing** | 766 (lingshu-core module; 2 documented standalone flakes) |
| **Stable SPI interfaces** | 14 `@PublicApi(stable)` @since 0.1.0 |
| **R-13 mitigation (d) PASS** | 29 consecutive Story merges, **0 binary delta** |
| **New Maven dependencies introduced** | **0** (locked to 13 — dsh §10.1) |
| **License** | Apache-2.0 |
| **Java source level** | 1.8 (`<source>1.8</source>`) |
| **Java runtime** | JDK 17+ (Spring Boot 3.2.5 hard requirement) |
| **Distribution channel** | GitHub Packages |

### Added — 9 Slot SPI (11 interfaces)

- **`LlmProvider`** (Slot 1) — Stream a prompt to an LLM with two-channel output:
  incremental `AgentEvent` deltas for UI/log subscribers + final `LlmResponse`
  for engine. Reactive Streams `Subscriber` interface.
- **`Tool`** (Slot 2) — Single callable tool with `name()`, `description()`,
  `inputSchema()` JSON Schema (draft 2020-12), and `execute(call, ctx)`.
  Default `sourceCategory()` returns `"local"`; override returns one of
  `local` / `mcp` / `skill` / `a2a` / `delegate` (or a custom string).
- **`ToolExecutor`** (Slot 2) — Single dispatch entry point. Five-step pipeline
  (permission → registry lookup → timeout → sandbox → execute → checkpoint) is
  mandatory and serializes all Tool calls through one chokepoint (dsh §4.10.1 硬规则 2).
- **`RuntimeSandbox`** (Slot 3) — Bounded filesystem, HTTP client, and process runner.
  Out-of-bounds access throws `AccessDeniedException` with `LINGS-S01`.
- **`Skill`** (Slot 4) — Marker interface (`extends Tool`). Skills auto-discover
  from classpath SKILL.md files, directory scans, or hard-coded `@Component` beans.
- **`SkillSource`** (Slot 4 sub-SPI) — Per-source skill discovery contract;
  `CompositeSkillLoader` orchestrates multiple sources (one source failure
  does not block others).
- **`SessionStore`** (Slot 5) — Persists `Checkpoint` snapshots. Backends:
  in-memory (default), file, Redis, JDBC.
- **`Compactor`** (Slot 6) — Two-phase compaction: `shouldCompact(prompt)` pure
  predicate + `compact(ctx)` mutator. Default `TruncatingCompactor` drops oldest
  tool results when prompts exceed `compactAtTokens`.
- **`PromptBuilder`** (Slot 7) — Assembles the 5-section prompt
  (`[ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY] / [CONVERSATION HISTORY] / [USER MESSAGE]`)
  + a separate `Prompt.tools` field for cache-stable tool schemas.
- **`FlowEngine`** (Slot 8) — Turn execution topology. Default `LinearTurnEngine`
  runs ReAct Loop with bounded `maxSteps`; alternative engines (DAG, Google ADK,
  Alibaba Graph, LangGraph4j) can replace it.
- **`A2aTransport`** (Slot 9) — Agent-to-Agent bridge. Five-method contract:
  `fetchCard`, `submit`, `get`, `cancel`, `subscribe`. Concrete implementations
  ship for HTTP/JSON-RPC (default), gRPC, and in-process.

### Added — 3 Helper SPI

- **`PermissionPolicy`** — Three-outcome gate (`Allow` / `Deny` / `AskUser`)
  consulted on every `ToolExecutor.dispatch`. Built-in implementations:
  `AllowAllPermissionPolicy` (default, yolo back-compat),
  `StrictPermissionPolicy` (allow/deny-list with `*` / `<name>` / `<category>:*`
  pattern matching), `AskUserPermissionPolicy` (delegates dangerous calls to
  `ApprovalRegistry` + human channel). `approvalTimeoutSeconds=0` means
  "wait indefinitely" (Claude Code overnight parity).
- **`MemorySource`** — Single-block `[PROJECT MEMORY]` contributor; ordered by
  ascending `priority()` at prompt assembly.
- **`AuditLogger`** — Structured audit-event sink (DSH 4.10 + §16). Implementations
  must NOT throw, must NOT block on I/O. Default `NoOpAuditLogger` for production;
  `ConsoleAuditLogger` for local dev (planned follow-up Story).

### Added — Public API Stability Marker

- **`@PublicApi(PublicApi.Level.{STABLE,INCUBATING,INTERNAL})`** annotation
  (`ai.lingshu.core.spi.PublicApi`). Carries the compatibility contract for
  v0.1.0: `STABLE` is backwards-compatible across minor releases (additive only);
  `INCUBATING` may change in a future minor; `INTERNAL` is not part of the public
  contract. Every `STABLE`-marked interface declares a `CONTRACT_VERSION`
  string field; `SlotRouter` reflects it at startup.

### Added — Core Implementation Features

#### Agent Runtime (Stories #001–#008)

- **Story #001 — zero-config-bootstrap**:Empty `application.yml` starts the engine.
  5 Maven modules (lingshu-core / lingshu-a2a-client / lingshu-a2a-server /
  lingshu-examples / lingshu-cli). `AgentFactory` Spring `@Component` singleton
  + 7 `SlotRouter` instances + 6 default Providers. `demo-empty` walks an
  agent turn end-to-end on first startup.
- **Story #002 — identity-instructions-memory**:Business triumvirate (Identity /
  Instructions / Memory). 4 `MemorySource` providers (`IdentityMemorySource` /
  `ProjectClaudeMdSource` / `UserClaudeMdSource` / `ProjectTreeMemorySource`)
  + Mustache template rendering + 5-section prompt assembly.
- **Story #003 — spi-slot-router**:`SlotRouter` validates `Provider.version()`
  semver compatibility at startup. Compatible with `CONTRACT_VERSION` field.
- **Story #004 — tool-parallel-dispatch**:`LinearTurnEngine.dispatchParallel`
  with Semaphore-bounded `CompletableFuture` pool. `config.tool.parallelism`
  controls concurrency; results backfill in LLM-return order.
- **Story #005 — cancellation-token**:Three-layer cancellation (Ctrl+C /
  `FlowEngine.markDone()` / 60s timeout) + JVM shutdown hook for in-flight
  turn cleanup.
- **Story #006 — multi-tenant**:`TenantContext` `ThreadLocal` with config /
  session / sandbox / cost 4-dimensional isolation.
- **Story #007 — yaml-hot-reload**:`AgentConfigRegistry` `AtomicReference` +
  `Files.getLastModifiedTime` 5s polling + `DefaultAgent.run()` freeze-on-entry.
  Old turns hold their config reference; new turns see new config. Hot-reload
  rolls back on validation failure.
- **Story #008 — react-max-steps**:`LinearTurnEngine.runTurn` `maxStepsHit`
  guard emits `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` to prevent
  LLM runaway token consumption.

#### A2A Client Stack (Stories #009 + #009a—#009e)

- **Story #009 — a2a-agent-card**:`LocalAgentCardGenerator` + JDK `com.sun.net.httpserver.HttpServer`
  exposing `GET /.well-known/agent.json` aligned with A2A v1.0 §2.1.
- **Story #009a — a2a-grpc-transport**:`GrpcA2aTransport` 3-piece (Provider +
  AutoConfiguration + concrete Transport) + `AgentCardCache` TTL+negative+FIFO
  + `A2aTransportRouter` named-Provider dispatch.
- **Story #009b — a2a-inprocess-transport**:`InProcessA2aTransport` for
  same-JVM calls via `InProcessA2aRegistry` singleton. Zero external deps.
- **Story #009c — a2a-httpjsonrpc-and-remote-tool**:`HttpJsonRpcA2aTransport`
  (5-method complete) + `RemoteAgentTool` (downgrades from v1.5.30's
  `@AutoService(ToolProvider.class)` misuse — v1.5.31 fix). New ErrorCode
  `LINGS-S08`.
- **Story #009d — a2a-remote-schema-builder**:`RemoteAgentSchemaBuilder` scans
  `AgentCard.skills[]` at startup → `ToolSpec` list. Stable prompt cache hits
  via sorted `(agentName, skillId)` ordering.
- **Story #009e — a2a-remote-tool-wiring**:Extracted `RemoteAgentToolAutoConfiguration`
  + `RemoteAgentToolLifecycle` `SmartLifecycle`. All 3 transports
  (HTTP/JSON-RPC, gRPC, in-process) share independent wiring; switching
  transports requires no Spring config change.

#### CLI, Session, Compaction, Tools, Skills, MCP (Stories #017–#021)

- **Story #017 — cli-entrypoint**:5 subcommands (`run` / `resume` / `serve` /
  `doctor` / `config`). Hand-rolled argv parser; zero new dependencies.
- **Story #018 — truncating-compactor**:`TruncatingCompactor` two-phase
  compaction (truncate tool results + sliding window). `Session.compact(List)`
  atomic replacement. Default config `(100_000 / 50_000 / 20)`.
- **Story #019 — built-in-tools**:5 hand-written Tools (`Read` / `Write` /
  `Edit` / `Bash` / `WebFetch`). `LocalToolsAutoConfiguration` auto-registers.
  Bash reuses `RuntimeSandbox.process()` against tenant whitelist.
  Includes **Story #032 WebFetch local HTTP/HTTPS fetcher** (Claude Code parity;
  coexists with MCP fetch servers — built-in + MCP not mutually exclusive).
  New ErrorCode `LINGS-T08`.
- **Story #020a — skill-foundation**:`SkillTool` concrete class + `fromMarkdown`
  static factory (parses SKILL.md first line `# title` as description) +
  `@Component CommitSkill` hard-coded example. `ToolRegistry` 4 new methods.
- **Story #020b — skill-source-discovery**:`SkillSource` SPI (4 methods) +
  `SkillSourceProvider` (2 methods) + 2 v1 implementations (classpath /
  directory). `CompositeSkillLoader` orchestrates; per-source failure isolation.
- **Story #020c — cli-skill-trigger**:`SkillCommandDispatcher` + CLI `/xxx`
  intercept. `Agent.continueWithUserMessageBlocking` synchronous variant.
- **Story #021a — mcp-stdio-transport**:`McpServerConnection` interface
  (8 methods) + 6-state state machine + `StdioMcpServerConnection` complete
  implementation (heartbeat + exponential-backoff reconnect). New ErrorCode
  `LINGS-M01`.
- **Story #021b — mcp-tool-adapter**:`McpTransport` + `McpToolAdapter` +
  `register` / `unregister` hook for tool discovery. New ErrorCode `LINGS-M02`.
- **Story #021c — mcp-sse-and-http-transport**:`SseMcpServerConnection` +
  `StreamableHttpMcpServerConnection` + `McpServerConnectionFactory`
  transport dispatch. New ErrorCode `LINGS-M03`.

#### Tool Integration & Examples (Stories #022–#026)

- **Story #022 — spring-ai-annotation-tool**:`@AgentTool` annotation +
  `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter`. Schema
  generation uses Spring AI but execution goes through `ToolExecutor`
  (no `ChatClient.tools().call()` — dsh §4.10.1 硬规则 2).
- **Story #023 — delegate-sub-agent**:SubAgent types `EXPLORE` / `ENGINEER` /
  `REVIEWER`. `DelegateTool` Task-tool entry. `SubAgentInheritance` 24-field
  field-level merge (no parent mutation). New ErrorCode `LINGS-D01`.
- **Story #024 — tool-schemas-integration**:`DefaultPromptBuilder` injects
  `ToolRegistry`, `Prompt.tools = toolRegistry.modelVisibleSpecs()`.
- **Story #025 — demo-product + A2A cross-JVM demo**:HTTP SSE chat product +
  `demo-product-a2a-server` translate demo (9090 ↔ 8080 via
  `RemoteAgentTool` + `HttpJsonRpcA2aTransport`).
- **Story #025 follow-up — demo-product-sandbox-wiring**:Top-level `agent.sandbox:`
  YAML 5 fields (`policy` / `runtime` / `working-directory` / `command-whitelist` /
  `domain-whitelist`) wired into `DemoProductApplication` Spring context.
- **Story #026 — yaml-placeholder-resolution**:`PlaceholderResolver` 4-form
  grammar (`${X}` / `${X:default}` / `${X:${Y}}` / `$${literal}`) +
  32-layer recursion + cycle detection. `AgentFactory.loadYamlAndValidate`
  hooks it between `parseMinimalYaml` and `toAgentConfig`. New ErrorCodes
  `LINGS-C03` / `LINGS-C04`.

#### Anthropic Protocol & Sandbox (Stories #027–#028)

- **Story #027a — anthropic-tool-protocol-conversion**:`AnthropicLlmProvider`
  4-segment protocol chain fully wired: `Prompt.tools` → top-level `tools:[]`
  + `messages[].content` blocks + consecutive `Message.ToolResult` merge to
  single user message (Anthropic protocol hard constraint). New ErrorCodes
  `LINGS-L01` / `LINGS-L02`.
- **Story #027b — anthropic-stream-tool-sse**:True SSE streaming with
  `AnthropicStreamParser` 6-state event machine. **NFR: LLM first-token
  P50 ≤ 1.5s met**. New reserved `LINGS-L03` (graceful-shutdown Story #046).
- **Story #028 — sandbox-runtime-impl**:`ChrootRuntimeSandbox` real impl
  using `java.nio.file.FileSystems.newFileSystem` + path filter. Bounded fs /
  http / process. New ErrorCode `LINGS-S01` (domain whitelist denial).

#### Permission Policy (Stories #029–#031, #037, #041–#042)

- **Story #029 — permission-policy-impl**:`StrictPermissionPolicy` real impl
  + `StrictPermissionPolicyProvider` SPI + 3-segment decision logic.
  New ErrorCode `LINGS-P01` (11th error-code domain letter: P = Permission).
- **Story #030 — permission-policy-ask-user**:`AskUserPermissionPolicy` 5-segment
  decision + `AskUserPermissionPolicyProvider` SPI + `ApprovalRegistry`
  `ConcurrentMap` sink + `AgentEvent.ApprovalRequired` 3-arg ctor
  (UUID approvalId). `LinearTurnEngine.dispatchWithPolicy` AskUser branch
  `CompletableFuture<Decision>` blocking-resume round-trip. New ErrorCode
  `LINGS-P02` (`approvalTimeoutSeconds > 0` only).
- **Story #031 — permission-policy-pattern-matching**:`PermissionPatterns`
  3-form pattern matching (`*` / `<name>` / `<category>:*`) + `Tool.sourceCategory()`
  default method (5 reserved categories). Replaces Story #029's brittle static
  enumeration.
- **Story #037 — permission-policy-multi-provider-alignment**:3 PermissionPolicy
  Providers registered via `v1.5.28` multi-Provider pattern (consistent
  `@Bean(name = "permissionPolicyProvider_<name>-<version>")`).
- **Story #041 — approval-gate-wiring-cleanup**:`ApprovalGate` SPI extracted
  + `DefaultApprovalGate` private inner class + 2 latent bugs fixed
  (cancel callback replay, explicitSink mismatch) + 2 pre-existing compile
  errors fixed (`AgentConfig` 24→25-arg ctor drift).
- **Story #042 — demo-product-frontend-approval-ui**:Frontend Allow/Deny
  button UI in demo-product (`app.js` + `index.html`, 50 lines vanilla JS).
  End-to-end ask-mode loop closes for the first time.

#### Transport Guards & Concurrency (Stories #033–#034, #043–#044)

- **Story #033 — mcp-http-domain-guard**:Path B + Mitigation 1 — 12 hook points
  in MCP HTTP transports call `McpHttpSupport.checkOrThrow(url, whitelist)`
  before each request. Reuses `LINGS-S01` (no new ErrorCode).
- **Story #034 — a2a-http-domain-guard**:`AgentRef.@Value` adds
  `domainWhitelist` field + per-remote-agent configuration. 2 hook points in
  `HttpJsonRpcA2aTransport` (`fetchCard` + `jsonRpcCall`).
- **Story #043 — anthropic-llm-io-bounded-pool**:`AnthropicLlmProvider.ioExecutor`
  replaced with bounded `ThreadPoolExecutor` mirroring `ToolExecutorConfig.agentToolPool`
  shape (`corePoolSize=cores*2`, `maxPoolSize=cores*4`, `keepAliveTime=60s`,
  `LinkedBlockingQueue(256)`, `CallerRunsPolicy`, daemon=true). **Eliminates the
  only `Integer.MAX_VALUE` upper-bound pool in production code.**
- **Story #044 — agent-turn-concurrency-cap**:`AgentConfig.@Value` 24 → 26
  fields. New `maxConcurrentTurns` (default 16) + `maxConcurrentQueueDepth`
  (default 32) + top-level `validate()` aggregating 3 field checks.
  **Lands dsh §10 NFR row 4: "default 16, queue ≤ 32".**

### Added — L1 Release Gate (Apache-2.0 Licensing)

- **`LICENSE`** — Apache-2.0 full text with `Copyright 2026 The LingShu Authors`.
- **`NOTICE`** — 12 third-party dependency attributions (Spring Boot,
  Spring AI, Lombok, OpenTelemetry, reactive-streams, Jackson, JUnit 5,
  AssertJ, Mockito, Awaitility).
- **`license-maven-plugin` 4.2** bound to Maven `validate` phase with
  `strictCheck=true`. Every Java file is checked against
  `src/main/resources/license-header.txt` on every build.
- **SPDX Apache-2.0 headers** prepended to all 435 Java source files.

### Added — Story #045 D4 Compatibility Contract

- **`@PublicApi(PublicApi.Level.STABLE)`** annotation on 14 interfaces
  (11 Slot SPI + PermissionPolicy + MemorySource + AuditLogger). Each
  interface declares `@since 0.1.0` in its Javadoc.
- **`AuditLogger`** SPI stub created with the `STABLE` marker (production
  implementations land in a follow-up Story).

### Hygiene / Refactor

- **`Message.ToolUse` removal** (v1.5.46 refactor) — Dead nested class
  removed from `Message.java`. `Message` 5 → 4 subclasses (System / User /
  Assistant / ToolResult). 0 functionality change; 0 tests affected.

### Bug Fixes

- **`javax.annotation.PreDestroy` unavailable** — Switched to Spring's
  `destroyMethod="close"` (no `javax.annotation` dependency needed at runtime).
- **`InitializingBean` over `@PostConstruct`** (Story #022 follow-up) — Spring 6
  standard practice.
- **`AgentConfig` ctor arity drift** (Story #041) — 2 call sites missed the
  24 → 25-arg bump from Story #029; recompiled cleanly.
- **`DefaultSession.checkpoint()` subagent metadata** — Stamps
  `SubAgentType.configKey()` instead of an empty string.
- **`AnthropicLlmProvider.buildRequestBody` blank line** after `stream:true`
  (Cosmetic; JSON serializer tolerance).

### Compatibility

#### API Compatibility Promise (D4)

- **`STABLE`** — Backwards-compatible across minor releases; breaking changes
  require a major version bump and a 2-release deprecation cycle.
- **`INCUBATING`** — Provisional; the contract may change in a future minor release.
- **`INTERNAL`** — Not part of the public contract; renames/signature changes/
  removals may happen in any release without notice.

#### Java Compatibility

- **Source level**: Java 1.8 (`<source>1.8</source>`). Code avoids JDK 9+
  features: `record` / `sealed` / `var` / `List.of(...)` / pattern-switch /
  text blocks. Uses `Arrays.asList(...)` / `Collections.unmodifiableList(...)`
  instead of `List.of(...)`.
- **Runtime**: JDK 17+ required (Spring Boot 3.2.5 hard requirement).
  Tested against Temurin / Zulu / Alibaba Dragonwell / IBM Semeru.

#### Dependency Compatibility (R-13 mitigation (d))

**13 locked dependencies** (no new dependencies in any Story):

| Group | Artifact | Version |
|---|---|---|
| `org.springframework.boot` | `spring-boot-starter-parent` | 3.2.5 |
| `org.springframework.ai` | `spring-ai-bom` | 1.0.0-M6 |
| `org.projectlombok` | `lombok` | 1.18.38 |
| `io.opentelemetry` | `opentelemetry-api` | 1.32.0 |
| `org.reactivestreams` | `reactive-streams` | 1.0.4 |
| `org.junit.jupiter` | `junit-jupiter` | 5.10.1 |
| `org.assertj` | `assertj-core` | 3.24.2 |
| `org.mockito` | `mockito-core` | 5.8.0 |
| `org.awaitility` | `awaitility-core` | 4.2.0 |
| `com.fasterxml.jackson.core` | `jackson-databind` | (Spring Boot managed) |
| `com.fasterxml.jackson.core` | `jackson-core` | (Spring Boot managed) |
| `com.fasterxml.jackson.core` | `jackson-annotations` | (Spring Boot managed) |
| `org.apache.maven.plugins` | `maven-enforcer-plugin` | 3.4.1 |

The Maven Enforcer plugin's `banned-dependencies` rule rejects:

- `org.springframework.ai:spring-ai-spring-boot-starter`
- `org.springframework.ai:spring-ai-vector-store-*`
- `org.springframework.ai:spring-ai-etl-*`
- `org.springframework.ai:spring-ai-unstructured-*`
- `com.knuddels:jtokkit`

Build-time failure on any new dependency introduction.

### Security

- **Sandbox isolation (Slot 3)** — All Tool filesystem / HTTP / process calls
  flow through `RuntimeSandbox`. Path filter rejects out-of-bounds reads/writes;
  HTTP client rejects non-whitelisted domains; process runner rejects
  non-whitelisted binaries. New ErrorCode `LINGS-S01`.
- **Permission policy (Story #029)** — Strict allow/deny-list with pattern
  matching (`*` / `<name>` / `<category>:*`). Default `AllowAll` is opt-out via
  `permission-policy: strict` configuration.
- **Ask-mode approval gate (Story #030)** — High-risk Tools (`ask-list`)
  block on user approval via `ApprovalRegistry`. `approvalTimeoutSeconds=0`
  default = wait indefinitely (overnight batch jobs).
- **MCP HTTP domain guard (Story #033)** — 12 hook points in SSE / streamable
  HTTP transports reject off-whitelist hosts before any request is sent.
- **A2A HTTP domain guard (Story #034)** — Per-remote-agent domain whitelist
  enforced in `HttpJsonRpcA2aTransport`.
- **No `Spring AI ChatClient.tools().call()`** — The `ToolExecutor.dispatch()`
  5-step pipeline is the single entry point. Auto-tool-execution via Spring
  AI would bypass permission / sandbox / checkpoint and is **forbidden**
  (dsh §4.10.1 硬规则 2).

### Known Issues / Limitations

- **`ServeHandlerTest`** hang in `lingshu-cli` standalone mode (pre-existing,
  unrelated to v0.1.0 changes). Workaround: `mvn -pl lingshu-cli test
  -Dtest='!ServeHandlerTest'`.
- **`AnthropicToolReActIT`** 2 cases fail in standalone mode (pre-existing,
  passes in full-suite mode). Documented in Story #043 retrospective.
- **`MemorySource` `Session.compact()` long-content compaction** — Current
  `TruncatingCompactor` is text-truncation only; LLM-summarization compactor
  is planned for a follow-up Story.

### Upgrade / Migration Notes

**This is the first public release** — no upgrade path from a prior version.

For developers tracking `main`/`0.1.0-SNAPSHOT`:

1. **Java runtime** — JDK 17+ is now mandatory at runtime. JDK 8 source
   compilation is preserved.
2. **`AgentConfig` schema** — 26 fields (was 24 in earlier snapshots).
   `maxConcurrentTurns` + `maxConcurrentQueueDepth` are new.
3. **`AgentConfig.validate()`** — Top-level validation is now invoked at
   startup; configurations that previously silently failed will now error
   with `LINGS-C02` immediately.
4. **`AuditLogger` SPI** — If you previously implemented a custom audit
   sink, implement `AuditLogger` and register as a Spring bean.
5. **Plugin authors** — Implement `SlotProvider` (not Java SPI / OSGi).
   See README "Stable SPI (since 0.1.0)" section for the multi-Provider
   registration pattern.

### Acknowledgments

- **Apache DSH / Dubbo SPI** — LingShu's design draws inspiration from
  Apache DSH (Dynamic Service Hosting) and Apache Dubbo's plugin / SPI
  architecture.
- **Spring Boot 3.2.5** — Provides the DI container, configuration
  properties, and `@AutoConfiguration` machinery.
- **Spring AI 1.0.0-M6** — Used **only** for LLM protocol conversion
  + `@Tool` JSON Schema generation (NOT for tool execution).

---

[0.1.0]: https://github.com/lingshu-ai-agent/lingshu/releases/tag/v0.1.0
