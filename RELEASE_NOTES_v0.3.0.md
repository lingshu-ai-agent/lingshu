# Release Notes — LingShu v0.3.0

> **发布日期**:2026-10-26
> **代号**:DSH Agent Engine v0.3.0 — **Protocol-Correctness Hardening**(MCP 协议合规可证 + Compaction 协议安全)
> **配套文档**:[`CHANGELOG.md`](./CHANGELOG.md)(结构化变更日志)/ [`README.md`](./README.md)(项目主页)/ [`dsh_agent_design.md`](./dsh_agent_design.md) v1.5.65(设计真理)

---

## 🎉 What's New — TL;DR

LingShu v0.3.0 is a **minor version increment** over v0.2.0 dedicated to **protocol correctness**. Two headlines: **MCP protocol compliance goes from "documented claim" to "black-box provable"** (Story #054 — spec-rewritten fixtures, 22 net new cases, full-regression Errors 18 → 0), and a **production bug that broke the Anthropic Messages API after chat-triggered compaction is root-caused and fixed** (orphan `tool_result` → HTTP 400). Compaction telemetry is now honest — the `compacted` SSE event carries the real before/after payload instead of a hardcoded workaround. **Zero SPI breakings** — D4 `STABLE` consumers can upgrade without recompilation.

- **1 Story + 2 production fixes / 5 PRs since v0.2.0** — including **1 external contribution** (PR #90, `xiaobaibaiaaa`)
- **Anthropic HTTP 400 root-cause fix** — `TruncatingCompactor` pair-safe window cut + anchor-User re-insertion + protocol-boundary orphan guard (`AnthropicLlmProvider`)
- **`AgentEvent.Compacted` real payload** — `beforeSize` / `afterSize` / `approxTokensFreed` measured by the engine; demo-product's R-1 hardcoded workaround (`lastHistorySize`/`resetBaseline`) deleted
- **MCP compliance black-box suite** (Story #054) — SSE 4-step handshake + `ping` keepalive + Streamable HTTP session recovery + `postJsonRpcSpec` codec, **8 test/fixture files / 0 production files**
- **831 lingshu-core tests** (up from 802), **R-13 mitigation (d) 37 consecutive PASSes** (up from 36), **0 new Maven dependencies**
- **Apache-2.0** licensed, **JDK 8 source / JDK 17+ runtime**, **GitHub Packages** distribution — unchanged from v0.2.0

---

## 🏛 Architecture at a Glance

LingShu ships **9 pluggable Slot SPIs**. v0.2.0 made every Slot production-wired; v0.3.0 makes the protocol boundaries **provably correct**.

| # | Slot | Default Implementation | v0.3.0 Status |
|---|---|---|---|
| 1 | **LlmProvider** | `AnthropicLlmProvider` (SSE streaming, bounded I/O pool) | ✓ + orphan `tool_result` boundary guard |
| 2 | **Tool** + **ToolExecutor** | 5 built-in Tools (`Read`/`Write`/`Edit`/`Bash`/`WebFetch`) + `DefaultToolExecutor` | ✓ Production |
| 3 | **RuntimeSandbox** | JVM chroot + whitelist HTTP/process/fs | ✓ Production |
| 4 | **Skill** + **SkillSource** | SKILL.md (classpath + directory) + `@Component` Skill | ✓ Production |
| 5 | **SessionStore** | `InMemorySessionStore` (default) + `FileSessionStore` (atomic write) | ✓ Production |
| 6 | **Compactor** | `TruncatingCompactor` (tool-result truncation + sliding window) | ✓ **pair-safe cut + anchor-User (bug fix)** |
| 7 | **PromptBuilder** | `DefaultPromptBuilder` (5-section assembly + anti-verify-loop clause) | ✓ Production |
| 8 | **FlowEngine** | `LinearTurnEngine` (ReAct loop, max-steps guard) | ✓ + real compaction measurement |
| 9 | **A2aTransport** | `HttpJsonRpcA2aTransport` (per-remote-agent domain whitelist) | ✓ Production |

**Plus 3 Helper SPIs** (also `@PublicApi(STABLE)` since v0.1.0):

- **`PermissionPolicy`** — `AllowAll` / `StrictPermissionPolicy` / `AskUserPermissionPolicy`
- **`MemorySource`** — `Identity` / `ProjectTree` / `Conversation` memory tiers
- **`AuditLogger`** — Pluggable event sink (default `NoOpAuditLogger`)

---

## 🛡 Compatibility Promise (D4, since v0.1.0)

| Level | What it means | Who can rely on it |
|---|---|---|
| **`STABLE`** | Backward-compatible across minor releases; breaking changes require major version bump + 2-version deprecation cycle | All **14 annotated SPIs** |
| **`INCUBATING`** | May change in minor releases | Types marked `@PublicApi(INCUBATING)` |
| **`INTERNAL`** | No stability guarantees | Default for all unmarked types |

### The 14 stable SPIs (since v0.1.0)

**9 Slot SPIs (11 annotated interfaces):**
- `LlmProvider` / `Tool` / `ToolExecutor` / `RuntimeSandbox` / `Skill` / `SkillSource` / `SessionStore` / `Compactor` / `PromptBuilder` / `FlowEngine` / `A2aTransport`

**3 Helper SPIs:**
- `PermissionPolicy` (and its `Decision` types: `Allow` / `Deny` / `AskUser`)
- `MemorySource`
- `AuditLogger`

### v0.3.0 Compatibility Status

- **0 breaking changes** vs. v0.2.0 across all 14 `STABLE` interfaces
- `AgentEvent.Compacted` — new 3-arg ctor + 3 fields; **no-arg back-compat ctor preserved** (defaults to zeros)
- `TruncatingCompactor.estimateTokens` — visibility widened to `public` (purely additive; one token-estimate source shared with `shouldCompact()`)
- `AgentConfig` 26 fields / `Message` 4-subclass / `Checkpoint` JSON round-trip contracts unchanged
- `ToolExecutor.dispatch()` 5-step pipeline unchanged; Spring AI `ChatClient.tools().call()` auto-tool-execution **still forbidden**

---

## 🚀 Quick Start

### Add to your `pom.xml`

```xml
<dependency>
    <groupId>ai.lingshu</groupId>
    <artifactId>lingshu-core</artifactId>
    <version>0.3.0</version>
</dependency>
```

### Minimal "hello world" agent

```java
package com.example;

import ai.lingshu.core.event.AgentEvent;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public class App {
    public static void main(String[] args) throws Exception {
        // 1. Build agent (YAML-driven or programmatic — see README)
        // 2. Run blocking
        //   String reply = agent.runBlocking("Write a haiku about ReAct loops.");

        // 3. Or stream events (Publisher<AgentEvent>)
        //   Publisher<AgentEvent> events = agent.run(prompt);
        //
        // 4. NEW in v0.3.0 — the `compacted` event now carries real numbers:
        //   onEvent(AgentEvent.Compacted c) → c.getBeforeSize(), c.getAfterSize(),
        //   c.getApproxTokensFreed()
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

## 📦 Migration from 0.2.0

LingShu v0.3.0 is **drop-in API-compatible** with v0.2.0 — the changes are purely additive:

1. **All 14 SPIs preserved** — same method signatures as v0.2.0. Existing consumers compile and link unchanged.
2. **`AgentEvent.Compacted` payload** — New `beforeSize` / `afterSize` / `approxTokensFreed` fields (3-arg ctor). SSE consumers may start reading the new `compacted` JSON fields; the no-arg ctor still exists for in-process callers.
3. **demo-product `AgentEventMapper` is stateless again** — `lastHistorySize` / `resetBaseline` removed (only affects demo-product internals / forks that copied the R-1 workaround).
4. **Compaction window cuts are stricter (the bug fix)** — histories that previously produced orphan `tool_result`s are now cut pair-safely. No configuration change; if anything, fewer boundary messages are retained.

No `pom.xml` version bumps required for transitive consumers — `lingshu-core` 0.3.0 is API-compatible with all v0.2.0 builds.

---

## ⚠️ Known Issues

Carried from v0.1.0 / v0.2.0, **none of which block the v0.3.0 release**:

1. **`ServeHandlerTest`** hang in `lingshu-cli` under JDK 21+ (pre-existing). Workaround: `mvn -pl lingshu-cli test -Dtest='!ServeHandlerTest'`.
2. **`AnthropicToolReActIT`** standalone flake — 2 cases fail in isolation, pass in the full suite. Pre-existing.
3. **`McpServerConnectionIT`** heartbeat flake — occasional 60-second-cap timeouts under heavily loaded CI runners (<0.5% of runs). Timing-sensitive.

Newly observed in the 2026-10-26 full-reactor verification run:

4. **MCP transport reconnect tests** — intermittent timing flakes under heavy suite load (`StreamableHttpMcpServerConnectionReconnectTest.reconnect_afterSessionExpiry_reRunsHandshake`, `SseMcpServerConnectionReconnectTest.sseStreamBroken_reconnects`): the state timeline can observe `DISCONNECTED` after the reconnect `CONNECTED`, so the "reconnected" lookup misses. Both pass in isolation and the suite passed 831/831 in low-load runs of the same cycle.
5. **demo-engineer / demo-spi / demo-parallel `BlackBoxVerificationTest`** — Spring circular-reference context startup failures (`YamlTenantConfigProvider` ↔ application bean). Pre-existing; those modules are untouched since v0.1.0.

Also fixed in this release (previously a main-branch failure): the `lingshu-a2a-client` `HttpJsonRpcA2aTransportAutoConfigurationTest` stale `@Bean` count assertion (2 → 3, `agentCardCache`) — see CHANGELOG Hygiene.

---

## 🛣 What's Next — v0.4.0 Roadmap Preview

Carried forward from the v0.3.0 plan (user scope choices deferred):

- **Story #016 audit-log** — Production-grade `AuditLogger` implementation (PostgreSQL + OpenTelemetry exporters)
- **§14 N6 graceful shutdown** — Drain in-flight turns on `SIGTERM`; the `LINGS-L03` reserved ErrorCode slot activates
- **§14 N4 CostBudget** — Per-session token/cost caps with structured `AgentEvent.CostExceeded` event
- **`SummaryCompactor`** — LLM-driven summarization compactor joining the default `TruncatingCompactor`
- **Spring Boot starter** — Single `ai-lingshu-spring-boot-starter` artifact wrapping `lingshu-core` + opinionated auto-config
- **Story #055 `mcp-http-legacy-postjsonrpc-cleanup`** — dead legacy helper discovered during Story #054

For the full Story pipeline and RFC queue, see [`specs/ROADMAP.md`](./specs/ROADMAP.md).

---

## 🙏 Acknowledgments

Special thanks to **`xiaobaibaiaaa`** for the external contribution (PR #90) — the MCP protocol compliance rewrite + whitelist configuration fix that motivated Story #054's black-box compliance suite.

v0.3.0 is a focused **protocol-correctness** cycle: **1 Story + 2 production fixes**, **5 PRs**, **831 lingshu-core tests**, **37 R-13 mitigation (d) 0-binary-delta PASSes**, and the same D4 stability promise.

**LingShu · 灵枢** — *The Pivot of Agent Orchestration*

— The LingShu Authors, 2026-10-26
