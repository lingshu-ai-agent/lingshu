# Release Notes — LingShu v0.2.0

> **发布日期**:2026-10-19
> **代号**:DSH Agent Engine v0.2.0 — **9-Slot 100% Real-Wired Milestone**
> **配套文档**:`CHANGELOG.md`(结构化变更日志)/ `README.md`(项目主页)/ [`dsh_agent_design.md`](./dsh_agent_design.md) v1.5.65(设计真理)

---

## 🎉 What's New — TL;DR

LingShu v0.2.0 is a **minor version increment** over v0.1.0 that flips the framework from "9-Slot SPI stubbed" to **"9 Slot × 9 Plugin 100% production-wired"**. Every `agent.<slot>.name` YAML key now actually resolves + takes effect — no more silent-ignored fields. **Zero SPI breakings** — D4 `STABLE` consumers can upgrade without recompilation.

- **10 Stories / 9 PRs since v0.1.0** — additive features behind existing contracts
- **9 Slot × 9 Plugin 100% real-wired** — every SPI is end-to-end callable, not just stubbed (Story #045 closes the last gap)
- **Live Reactive Streaming API** — `DefaultAgent.run(Prompt) → Publisher<AgentEvent>` per Reactive Streams 1.0.4 spec (Story #049)
- **Anti-verification-loop default-on** — LLM verification-loop fix now ships zero-config out-of-the-box, replacing Story #051's opt-in with Story #053's default flip
- **Anthropic protocol safety nets** — ask-mode hang-safety sentinel (Story #048) + parallel-dispatch protocol boundary guard (Story #052, `LINGS-L04`)
- **~830 tests passing** (up from 766), **R-13 mitigation (d) 36 consecutive PASSes** (up from 29), **0 new Maven dependencies**
- **Apache-2.0** licensed, **JDK 8 source / JDK 17+ runtime**, **GitHub Packages** distribution — unchanged from v0.1.0

---

## 🏛 Architecture at a Glance

LingShu ships **9 pluggable Slot SPIs**. By v0.2.0, every Slot is wired end-to-end through the Agent loop — neither `LinearTurnEngine` nor the demo products can hit a "stub" code path under any `application.yml` combination.

| # | Slot | Default Implementation | v0.2.0 Status |
|---|---|---|---|
| 1 | **LlmProvider** | `AnthropicLlmProvider` (SSE streaming, bounded I/O pool) | ✓ Production |
| 2 | **Tool** + **ToolExecutor** | 5 built-in Tools (`Read`/`Write`/`Edit`/`Bash`/`WebFetch`) + `DefaultToolExecutor` | ✓ Production |
| 3 | **RuntimeSandbox** | JVM chroot + whitelist HTTP/process/fs | ✓ Production |
| 4 | **Skill** + **SkillSource** | SKILL.md (classpath + directory) + `@Component` Skill | ✓ Production |
| 5 | **SessionStore** | `InMemorySessionStore` (default) + `FileSessionStore` (atomic write) | ✓ **Real-wired (Story #046)** |
| 6 | **Compactor** | `TruncatingCompactor` (tool-result truncation + sliding window) | ✓ **Real-wired (Story #045)** |
| 7 | **PromptBuilder** | `DefaultPromptBuilder` (5-section assembly + opt-in anti-verify-loop clause) | ✓ Production (Story #053 default flip) |
| 8 | **FlowEngine** | `LinearTurnEngine` (ReAct loop, max-steps guard) | ✓ Production |
| 9 | **A2aTransport** | `HttpJsonRpcA2aTransport` (per-remote-agent domain whitelist) | ✓ Production |

**Plus 3 Helper SPIs** (also `@PublicApi(STABLE)` since v0.1.0):

- **`PermissionPolicy`** — `AllowAll` (default) / `StrictPermissionPolicy` (allow/deny/ask-list, 3-form patterns) / `AskUserPermissionPolicy` (Claude Code overnight parity — `timeout=0` = never expire)
- **`MemorySource`** — `Identity` / `ProjectTree` / `Conversation` memory tiers
- **`AuditLogger`** — Pluggable event sink (Story #016 reserved; default `NoOpAuditLogger` shipped)

---

## 🛡 Compatibility Promise (D4, since v0.1.0)

We commit to the following stability tiers, marked by `@PublicApi(PublicApi.Level.X)`:

| Level | What it means | Who can rely on it |
|---|---|---|
| **`STABLE`** | Backward-compatible across minor releases; breaking changes require major version bump + 2-version deprecation cycle | All **14 annotated SPIs** (see below) |
| **`INCUBATING`** | May change in minor releases | Types marked `@PublicApi(INCUBATING)` |
| **`INTERNAL`** | No stability guarantees | Default for all unmarked types |

### The 14 stable SPIs (since v0.1.0)

**9 Slot SPIs (11 annotated interfaces):**
- `LlmProvider` / `Tool` / `ToolExecutor` / `RuntimeSandbox` / `Skill` / `SkillSource` / `SessionStore` / `Compactor` / `PromptBuilder` / `FlowEngine` / `A2aTransport`

**3 Helper SPIs:**
- `PermissionPolicy` (and its `Decision` types: `Allow` / `Deny` / `AskUser`)
- `MemorySource`
- `AuditLogger`

### v0.2.0 Compatibility Status

- **0 breaking changes** vs. v0.1.0 across all 14 `STABLE` interfaces
- `AgentConfig` schema remains 26 fields (all default-valued)
- `Message` 4-subclass contract unchanged
- `Checkpoint` JSON round-trip contract unchanged
- `ToolExecutor.dispatch()` 5-step pipeline unchanged (dsh §4.10.1 硬规则 2)
- ReAct Loop `LinearTurnEngine` self-implementation unchanged (dsh §4.10.1 硬规则 1)
- `Spring AI ChatClient.tools().call()` auto-tool-execution **still forbidden**

**Plugin authors**:Implement any of the 14 interfaces as `@Component` and Spring Boot SPI auto-discovers them. No SDK registration, no META-INF/services files, no manual wiring. See [README §🛡️ Stable SPI](./README.md#-stable-spi-since-010) for the plugin authoring guide.

---

## 🚀 Quick Start

### Add to your `pom.xml`

```xml
<dependency>
    <groupId>ai.lingshu</groupId>
    <artifactId>lingshu-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

> **Authentication required**:Published to GitHub Packages. Configure `~/.m2/settings.xml` with a GitHub PAT — see [README §Installation](./README.md#installation) for the snippet.

### Minimal "hello world" agent

```java
@SpringBootApplication
public class HelloAgent {
    public static void main(String[] args) {
        // 1. Load YAML (or skip — empty config still works, all 26 fields have defaults)
        AgentConfig cfg = AgentFactory.loadYamlAndValidate("application.yml");

        // 2. Build the engine factory (Spring singleton, holds 9 Routers since v0.2.0)
        AgentFactory factory = new AgentFactory(routers);

        // 3. Create an Agent (per-session, per-conversation)
        Agent agent = factory.create(cfg);

        // 4a. Synchronous one-shot (v0.1.0 path — 100% preserved)
        String reply = agent.runBlocking("Write a haiku about ReAct loops.");
        System.out.println(reply);

        // 4b. NEW in v0.2.0 — Reactive Streams Publisher for live streaming
        Publisher<AgentEvent> events = agent.run(prompt);
        events.subscribe(new Subscriber<AgentEvent>() {
            // ... Reactive Streams 1.0.4 spec-compliant handler
        });
    }
}
```

### Run the demo product

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
mvn -pl lingshu-examples/demo-empty spring-boot:run    # zero-config, should boot in <30s
# OR
mvn -pl lingshu-examples/demo-product spring-boot:run # SSE chat UI with AskUser approval flow
```

---

## 📦 Migration from 0.1.0

LingShu v0.2.0 is **drop-in API-compatible** with v0.1.0 — same `lingshu-core` 0.2.0 binary works for consumers built against any v0.1.0 release. The changes are purely additive:

1. **All 14 SPIs preserved** — same method signatures as v0.1.0. Existing consumers compile and link unchanged.
2. **`AgentConfig` schema** — 26 fields (unchanged from v0.1.0; `maxConcurrentTurns` + `maxConcurrentQueueDepth` were already in v0.1.0).
3. **`AgentConfig.Instructions` schema** — 4 → 5 fields. New `boolean antiVerifyLoop` field **defaults to `true`** (Story #053). To restore v0.1.0 behavior: add `agent.instructions.anti-verify-loop: false` to your `application.yml`.
4. **`LinearTurnEngine` ctor arity** — 6→7 (Story #045) → 8 (Story #046). Custom subclassers must add the new ctor parameters; Spring autowiring handles the wiring transparently for the supplied `LinearTurnEngineProvider`.
5. **`AgentFactory` ctor arity** — 8-Router → 9-Router (Story #046 added `SessionStoreRouter`). Custom `AgentFactory` subclassers must add the new Router parameter.
6. **Plugin authors** — Continue implementing `SlotProvider`. v0.2.0 introduces no new Slot, but the **9-Slot × 9-Plugin 100% real-wired milestone** means every `agent.<slot>.name` YAML key now actually resolves + takes effect (no silent-ignored fields). Migration step for plugin authors: **opt-in audit** — re-read your YAML config against the v0.2.0 schema to confirm previously-silent fields now resolve.
7. **`AgentEvent.LlmStreamStarted`** — New event subclass; SSE consumers may need to handle the new `llm.stream.started` event type for stable step counters.

No `pom.xml` version bumps required for transitive consumers — `lingshu-core` 0.2.0 is API-compatible with all v0.1.0 builds.

---

## ⚠️ Known Issues

Three documented flakes carried from v0.1.0, **none of which block the v0.2.0 release**:

1. **`AnthropicToolReActIT` standalone flake** — 2 of the Anthropic LLM round-trip integration tests fail when run in isolation (`mvn test -Dtest=AnthropicToolReActIT`) with "Expected size: 2 but was: 1". The same suite passes when run as part of the full `mvn test` cycle. **Confirmed pre-existing**, unrelated to any Story in the v0.2.0 cycle.

2. **`McpServerConnectionIT` heartbeat flake** — 2 of the MCP heartbeat-recovery tests occasionally time out at the 60-second cap under heavily loaded CI runners. The flake is timing-sensitive, not deterministic. Affects <0.5% of CI runs. **Pre-existing**, documented in `CLAUDE.md`.

3. **`ServeHandlerTest` hang** — One specific CLI serve-mode integration test deadlocks when run in the full suite under JDK 21 (does not reproduce on JDK 17). We track this as a known regression pending investigation. Workaround:run with JDK 17 (`mvn test -pl lingshu-cli -Djvm.target=17`).

---

## 🛣 What's Next — v0.3.0 Roadmap Preview

We have a public roadmap. The following **user-facing features** are on deck for v0.3.0 (target: late 2026 / Q4):

> **Note from v0.1.0 release notes**:Items previously previewed for "v0.2.0" (`AuditLogger` production impl / graceful shutdown / `CostBudget` / `SummaryCompactor`) are **explicitly deferred to v0.3.0** by user scope choice. They were not part of the v0.2.0 cycle.

- **Story #016 audit-log** — Production-grade `AuditLogger` implementation (PostgreSQL + OpenTelemetry exporters); concrete Provider under `@PublicApi(INCUBATING)` first, graduating to `STABLE` in v0.4.0
- **§14 N6 graceful shutdown** — Drain in-flight turns on `SIGTERM`; the `LINGS-L03` reserved ErrorCode slot activates
- **§14 N4 CostBudget** — Per-session token/cost caps with structured `AgentEvent.CostExceeded` event
- **`SummaryCompactor`** — LLM-driven summarization compactor joining the default `TruncatingCompactor`
- **Spring Boot starter** — Single `ai-lingshu-spring-boot-starter` artifact wrapping `lingshu-core` + opinionated auto-config

For the full Story pipeline and RFC queue, see [`specs/ROADMAP.md`](./specs/ROADMAP.md).

---

## 🙏 Acknowledgments

v0.2.0 is the result of 12 months of iterative development since v0.1.0,
**10 additional Stories merged** (55 total), **36 R-13 mitigation (d) 0-binary-delta
PASSes** (up from 29), and **~830 tests** covering the entire stable surface.

Thanks to everyone who filed issues, contributed patterns, and tested snapshots
during the v0.1.0 → v0.2.0 cycle.

**LingShu · 灵枢** — *The Pivot of Agent Orchestration*

— The LingShu Authors, 2026-10-19
