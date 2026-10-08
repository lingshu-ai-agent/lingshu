# Changelog

All notable changes to **LingShu (灵枢) Agent Engine** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **🚢 First public release**:v0.1.0 is the inaugural public release of LingShu,
> published on **2026-10-07**. Every entry below was developed during the
> pre-release phase and lands in this release.

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
