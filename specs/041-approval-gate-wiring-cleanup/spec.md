# Story #041 `approval-gate-wiring-cleanup` — Spec

> **Status**: Draft 2026-10-03
> **Source**: dsh v1.5.53 §4.7 PermissionPolicy + §4.4 AgentEvent.ApprovalRequired + §9.3 Tool+Approval 时序图 + §15.4 ErrorCode 域 P 段(`LINGS-P02` 复用,**0 新 ErrorCode**)+ **CLAUDE.md §13 v1.3.48** drift 标注「Story #030 后 `DefaultToolExecutionContext.approval()` 注释还停留在 'Story #005 will replace'」
> **前置依赖**:`#001` + `#003` + `#004` + `#005` + `#028` + `#030` permission-policy-ask-user(2026-10-02 已合,`AskUserPermissionPolicy` 真接通 + `ApprovalRegistry` @Component + `LinearTurnEngine.dispatchWithPolicy` L467-559 inline AskUser 路径)
> **同 Story 拆解**:无。本 Story 是 Story #030 的 follow-up #1:实测发现 `DefaultToolExecutionContext.approval()` 注释 drift + `ToolExecutionContext.ApprovalGate` SPI 实质上无人调用,但 `specs/019-built-in-tools/plan.md:498` 已经引用 `ctx.approval() != null` 作为 OQ-Future **Bash Tool 中途 `rm -rf` 二次确认** 扩展点。**清理 + 真接通 ApprovalGate**。
> **本 Story 体量**:~8 核心 modify + 1 new test file(6 case)+ 0 new ErrorCode

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.53 §4.7 PermissionPolicy + §4.4 AgentEvent.ApprovalRequired + §9.3 Tool+Approval 时序图 + §15.4 ErrorCode 域 P 段
- **实测发现**(2026-10-03 用户读 `DefaultToolExecutionContext.java` 行 117-127 反馈「Story #030 不是已经支持询问用户业务逻辑了吗,这里还是直接 deny」):
  - `DefaultToolExecutionContext.approval()` 行 117-127 返匿名 inner class,`ask()` 永远 `new Decision.Deny("AskUser approval flow is wired in Story #005 follow-up")` — **注释停留在 Story #005(2026-08 时代),但 Story #005 是 cancellation token,与 AskUser 无关**
  - **`LinearTurnEngine.dispatchWithPolicy` L467-559 已 inline 实现真 AskUser 路径**(emit ApprovalRequired + register ApprovalRegistry + CompletableFuture.get(timeoutMs) + 3 错误分支)— **但 `toolCtx.approval().ask()` 完全不被调**,SPI 实质 dead
  - 7 处 JavaDoc 误导:`Decision.java:37` / `PermissionErrorCodes.java:20,38` / `AgentConfig.java:198` / `ToolExecutionContext.java:68` / `PermissionPolicy.java:15` / `AskUserPermissionPolicy.java:33,122` / `DefaultToolExecutionContext.java:45` 全部写"route to ApprovalGate.ask" / "ctx.approval().ask()" — **与 Story #030 实际 inline 路径不一致**
  - **`specs/019-built-in-tools/plan.md:498`** 已引用 `RuntimeSandbox.ProcessRunner runner = ctx.approval() != null ? ctx.approval().ask(ask) : ...` — **Tool-level AskUser 是 planned 但 SPI 当前 dead,OQ-Future 扩展点缺失**
- **业务后果**:
  - `DefaultToolExecutionContext.approval()` 实质上是 dead code(0 production caller),但 reader 从 JavaDoc 会以为它在工作
  - Tool-level AskUser(planned for Bash `rm -rf` confirm / WebFetch 域外 confirm)无扩展点 — 未来实现要绕过 SPI 自己 inline 一遍,或者回到 Plan A(把 ApprovalGate 接口删了)损失扩展点
- **对应风险**:**R-04**(privilege escalation — 分值 8)— 部分缓解:#030 已让 `permission-policy: ask` 路径接通,但 Tool 内 AskUser 仍未实现

---

## 1. WHY(为什么做这个 Story)

**核心问题**:Story #030 让 `Decision.AskUser` 真接通(`permission-policy: ask` + `LinearTurnEngine.dispatchWithPolicy` inline 路径 + `ApprovalRegistry` + demo-product `ChatController` SSE round-trip),但**实现路径完全绕过 `ToolExecutionContext.ApprovalGate` SPI** — 而 SPI 是 OQ-Future 扩展点(`specs/019-built-in-tools/plan.md:498` 已经计划 Tool 内 AskUser 调用 `ctx.approval().ask()`)。三个具体 drift:

1. **`DefaultToolExecutionContext.approval()` 是 dead code stub**,注释误导读者以为它是 active 实现
2. **7 处 JavaDoc 写"route to ApprovalGate.ask" / "ctx.approval().ask()"**,但 Story #030 实施后真实路径是 `LinearTurnEngine.dispatchWithPolicy` inline emit + `ApprovalRegistry` 阻塞 — **reader 按 JavaDoc 找 caller 会困惑**
3. **`ToolExecutionContext.ApprovalGate` SPI 实质 dead**,但 OQ-Future 依赖它 — 未来 OQ-#041-ext(假想 Story) 实现 Bash `rm -rf` 二次确认时,要么:
   - 走 ApprovalGate SPI(理想,但当前 stub 返 Deny → 真接通需要本 Story)
   - 绕过 SPI inline(重复 Story #030 的实现,代码腐化)
   - 删 ApprovalGate SPI(失去扩展点,违反 §5.7 插件机制 SPI 决策)

**业务价值**:

- **ApprovalGate SPI 真接通** —— `DefaultToolExecutionContext.approval()` 重写为 `DefaultApprovalGate`(内嵌静态 class),内部**封装** Story #030 inline 逻辑(emit ApprovalRequired + register ApprovalRegistry + CompletableFuture.get(timeoutMs) + 3 错误分支)
- **`LinearTurnEngine.dispatchWithPolicy` AskUser 分支简化** —— 改为调 `toolCtx.approval().ask(ask)`,删除 ~50 行 inline 逻辑,语义清晰
- **Tool-level AskUser 扩展点真接通** —— OQ-Future Bash `rm -rf` confirm / WebFetch 域外 confirm 现在有 SPI 可走
- **服务端/CLI fail-safe 不变** —— `A2aServerToolExecutionContext.approval()` / `DemoA2aServer.approval()` / `SkillCommandDispatcher.approval()` 继续返 Deny(明确"此处无人在环"语义),**只改 JavaDoc** 移除"Story #005 will replace"误导语
- **JavaDoc 7 处统一改写** —— 全部改为「DefaultApprovalGate implements ApprovalGate;resolved via ApprovalRequired event + ApprovalRegistry round-trip;see dsh §9.3」

**关键不变项**:

- `ToolExecutionContext.ApprovalGate` interface —— **0 改动**(SPI 保留)
- `ApprovalRegistry` API —— **0 改动**(单 `approvalId` key 维持现状;session-prefix evict 已知 limitation 留 OQ-Future)
- `Decision.AskUser` / `Allow` / `Deny` / `Option` 4 子类 —— **0 改动**
- `AgentEvent.ApprovalRequired` 3-arg ctor —— **0 改动**
- `PermissionPolicy` / `Decision` / `ToolCall` / `ToolExecutionContext` SPI —— **0 改动**
- `LinearTurnEngine` 公开方法签名 —— **0 改动**(只改 `dispatchWithPolicy` 内部 ~50 行)
- `AgentConfig.approvalTimeoutSeconds`(默认 0 = 无超时)— **0 改动**
- `AskUserPermissionPolicy` + `AskUserPermissionPolicyProvider` + `StrictPermissionPolicy` + `PermissionPatterns`(#029 + #031 已落)—— **0 改动**
- 9 Slot 顶层体系不变
- JDK 8 兼容(`CompletableFuture<Decision>` + `Consumer<Decision>` + `AtomicBoolean` + `Map` + `UUID.randomUUID()` JDK 8 standard,**不**引 reactive-streams 库外新二进制)
- **0 新 Maven 依赖**(`UUID.randomUUID()` + `CompletableFuture` + `ConcurrentHashMap` + `Consumer` JDK 8 standard + Spring `@Component` + Lombok `@Value` 已锁 13 项依赖表内)
- **0 新 ErrorCode**(复用 `LINGS-P02`)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 走 Story #030 `permission-policy: ask` 配置,无感知;Tool-level AskUser(未来 OQ-#041-ext Story 实现)走同一 SPI |
| **框架贡献者 / plugin 作者(Bob 类)** | 实现自定义 `ToolExecutionContext`(例如增加新 transport)时,override `approval()` 选择 fail-safe Deny 或接 ApprovalRegistry |
| **未来 OQ-#041-ext 实现者** | Bash `rm -rf` 二次确认 / WebFetch 域外确认 — 现在有 `ctx.approval().ask(ask)` SPI 可走,无需重复实现 ApprovalRegistry 桥接 |
| **运维稳定性关注者(Eve 类)** | `approval-timeout: 0`(默认,无超时,匹配 Claude Code 隔夜审批)或显式 `approval-timeout: 3600s` + `LINGS-P02` 触发 — **0 行为变化**(逻辑下沉,行为不变) |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 ApprovalGate 真接通(Allow / Deny / timeout 三路径)+ fail-safe 守住(3 个 server-side stub 返 Deny);L2 端到端 `LinearTurnEngine.dispatchWithPolicy` 通过 SPI 路径(走 ApprovalRegistry + ApprovalRequired) |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**:

### 3.1 `DefaultToolExecutionContext.approval()` 真接通

```java
@Override
public ApprovalGate approval() {
    return new DefaultApprovalGate(turnCtx, cancellation(), turnCtx.config().getApprovalTimeoutSeconds());
}

/**
 * Story #041 — Default {@link ApprovalGate} that bridges per-call Tool
 * execution scope back to the per-turn AskUser round-trip implemented in
 * {@link LinearTurnEngine.dispatchWithPolicy}. The actual emit /
 * register / block / timeout machinery lives here (extracted from
 * LinearTurnEngine inline L467-559 by Story #041) so the ApprovalGate
 * SPI is now usable from inside a Tool — see specs/019-built-in-tools
 * plan.md L498 for the planned Bash "rm -rf" confirm use case.
 *
 * <p>Behavior matches the prior inline implementation exactly:
 * <ul>
 *   <li>{@code approvalTimeoutSeconds = 0} → wait indefinitely
 *       (matches Claude Code overnight approval)</li>
 *   <li>{@code approvalTimeoutSeconds > 0} → wait at most N seconds,
 *       then return {@code Decision.Deny("[LINGS-P02] ...")} </li>
 *   <li>{@link CancellationToken#fire()} → return Deny immediately
 *       (no hang)</li>
 *   <li>No sink registered → return Deny with [LINGS-P02]</li>
 * </ul>
 */
private static final class DefaultApprovalGate implements ApprovalGate {
    private final TurnContext turnCtx;
    private final CancellationToken token;
    private final long timeoutSec;

    DefaultApprovalGate(TurnContext turnCtx, CancellationToken token, long timeoutSec) { ... }

    @Override
    public Decision ask(Decision.AskUser ask) {
        // 1. Generate approvalId
        // 2. AtomicBoolean + CompletableFuture<Decision> + Consumer<Decision> continuation
        // 3. resolve ApprovalRegistry (if non-null — production has it)
        // 4. emit AgentEvent.ApprovalRequired via turnCtx.sink().onNext()
        // 5. block on CompletableFuture.get(timeoutMs, MILLISECONDS)
        // 6. return resolved / Deny ([LINGS-P02]) / rethrow
    }
}
```

### 3.2 `LinearTurnEngine.dispatchWithPolicy` AskUser 分支简化

```java
if (d instanceof Decision.AskUser) {
    // 🆕 Story #041 — delegate to DefaultToolExecutionContext.approval()
    // (formerly inline L467-559, extracted to DefaultApprovalGate).
    // Behavior unchanged: emit ApprovalRequired + register ApprovalRegistry
    // + block + 3 error paths. The ApprovalGate SPI is now usable from
    // inside a Tool as well (specs/019-built-in-tools plan.md L498).
    Decision resolved = toolCtx.approval().ask((Decision.AskUser) d);
    if (resolved instanceof Decision.Allow) {
        return toolExecutor.dispatch(call, toolCtx);
    }
    if (resolved instanceof Decision.Deny) {
        return ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId(call.getId())
            .content(((Decision.Deny) resolved).getReason())
            .isError(true)
            .build();
    }
    // resolved instanceof Decision.AskUser — recursion (max 3, see dsh §9.3)
    return ToolResult.builder()
        .status(ToolResult.Status.ERROR)
        .toolUseId(call.getId())
        .content("[" + PermissionErrorCodes.LINGS_P02 + "] AskUser recursion limited (max 3 retries)")
        .isError(true)
        .build();
}
```

### 3.3 3 个服务端/CLI fail-safe override 改写 JavaDoc

`A2aServerToolExecutionContext.java` / `DemoA2aServer.java` / `SkillCommandDispatcher.java` 保留 override(返 Deny,语义"此处无人在环"),**只**改 JavaDoc 移除"Story #005 will replace"误导语。

### 3.4 7 处 JavaDoc 统一改写

`Decision.java:37` / `PermissionErrorCodes.java:20,38` / `AgentConfig.java:198` / `ToolExecutionContext.java:68` / `PermissionPolicy.java:15` / `AskUserPermissionPolicy.java:33,122` / `DefaultToolExecutionContext.java:45` 全部改为「DefaultApprovalGate implements ApprovalGate;resolved via ApprovalRequired event + ApprovalRegistry round-trip;see dsh §9.3」。

---

## 4. AC 黑盒列表(§14.15.7 7 层金字塔 + Story 边界 ≤ 5 核心文件)

### L1 Unit(5 case,1 test file)

- **AC-041-01** `DefaultApprovalGate.ask(AskUser)` + subscriber 5s 后 invoke Allow → 返回 `Decision.Allow("user approved")`,`AgentEvent.ApprovalRequired` 事件 sink 收到 1 次
- **AC-041-02** `DefaultApprovalGate.ask(AskUser)` + subscriber 立即 invoke Deny → 返回 `Decision.Deny("user denied")`
- **AC-041-03** `DefaultApprovalGate.ask(AskUser)` + `approvalTimeoutSeconds = 1` + subscriber 不 invoke → 阻塞 1s → 返回 `Decision.Deny("[LINGS-P02] Permission approval timed out after 1s")`
- **AC-041-04** `DefaultApprovalGate.ask(AskUser)` + `cancellationToken.fire()` 50ms 后 → 立即返 `Decision.Deny("[LINGS-P02] Approval flow interrupted")`(不等 timeout)
- **AC-041-05** `DefaultApprovalGate.ask(AskUser)` + `sink = null` → 立即返 `Decision.Deny("[LINGS-P02] Approval required but no event sink registered")`(不阻塞)

### L1 Unit(3 case,server-side fail-safe 守住)

- **AC-041-06** `A2aServerToolExecutionContext.approval().ask(AskUser)` → 返 `Decision.Deny("A2aServerToolExecutionContext.approval() — AskUser denied (no human channel)")`
- **AC-041-07** `DemoA2aServer` 内嵌 ApprovalGate override → 返 `Decision.Deny("DemoA2aServer has no ApprovalGate — AskUser denied")`
- **AC-041-08** `SkillCommandDispatcher` 内嵌 ApprovalGate override → 返 `Decision.Deny("CLI Skill dispatch does not support AskUser approval (Story #020c MVP)")`

### L2 Slice(2 case,端到端 SPI 路径)

- **AC-041-09** `LinearTurnEngine.dispatchWithPolicy` 调 `permissionPolicy = AskUserPermissionPolicy` 返 AskUser + `ApprovalRegistry` 注入 + 5s 后 resolve Allow → sink 收到 `ApprovalRequired` 事件 + `toolExecutor.dispatch(call, toolCtx)` 真调起 + `ToolCompleted(success)`(端到端 SPI 路径走通,**0 regression vs Story #030 inline 路径**)
- **AC-041-10** 同上,resolve Deny → `ToolCompleted(error content="user denied")`(端到端 Deny 路径)

### L3 黑盒(1 case,demo-product SSE round-trip)

- **AC-041-11** demo-product `permission-policy: ask` + `tools.ask-list: [write_file]` + 启动 ChatController → POST /api/chat SSE 触发 `ApprovalRequired` → 前端 POST /api/approvals/{sessionId}/{approvalId} body `{decision: "deny"}` → ApprovalRegistry.resolve → 引擎侧 SPI 路径 unblock → `ToolCompleted(error)` + turn 正常 END_TURN(**0 regression vs Story #030 #23 case**)

### L4 Contract(0 改动,守住)

- `ToolExecutionContext.ApprovalGate.ask(AskUser) → Decision` 公开签名 — **0 改动**
- `DefaultToolExecutionContext(TurnContext, RuntimeSandbox)` 2-arg ctor — **0 改动**(新增 3-arg ctor 接受 `ApprovalRegistry`,back-compat 守住)
- `LinearTurnEngine` 公开方法签名 — **0 改动**
- `AgentConfig.approvalTimeoutSeconds` 默认 0 = 无超时 — **0 改动**
- `ApprovalRegistry.register / consume / size / evictBySessionPrefix` API — **0 改动**

### L8 兼容(CI matrix)

- `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 + `banned-dependencies` enforcer 不 fail
- **0 新 Maven 依赖**(R-13 mitigation (d) 第 **24 次** PASS 验证,baseline 镜像 pre/post diff 仅时间戳差异)

---

## 5. 关键不变项(冻结)

1. `ToolExecutionContext.ApprovalGate` interface — **0 改动**(SPI 保留)
2. `ApprovalRegistry` @Component — **0 改动**(单 `approvalId` key 维持;`evictBySessionPrefix` 已知 limitation 留 OQ-Future)
3. `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)— **0 改动**
4. `AgentEvent.ApprovalRequired` 3-arg ctor(`ask` + `continuation` + `approvalId`)— **0 改动**
5. `PermissionPolicy` SPI — **0 改动**
6. `LinearTurnEngine` 公开方法签名 — **0 改动**(只 `dispatchWithPolicy` 内部 ~50 行下沉到 DefaultApprovalGate)
7. `AgentConfig.approvalTimeoutSeconds`(默认 0 = 无超时,`>0` = N 秒)— **0 改动**
8. `AskUserPermissionPolicy` + `AskUserPermissionPolicyProvider`(Story #030)—— **0 改动**
9. `StrictPermissionPolicy` + `PermissionPatterns` + `Tool.sourceCategory()`(Story #029 + #031)— **0 改动**
10. `DefaultToolExecutionContext(TurnContext)` 1-arg ctor + `(TurnContext, RuntimeSandbox)` 2-arg ctor — **0 改动**(新增 `(TurnContext, RuntimeSandbox, ApprovalRegistry)` 3-arg ctor,back-compat 守住)
11. 9 Slot 顶层体系不变(Slot 4 PermissionPolicy + Slot 2 ToolExecutor 内部 5 步流水线)
12. JDK 8 兼容(`UUID.randomUUID()` + `CompletableFuture<Decision>` + `ConcurrentHashMap` + `Consumer<Decision>` + `AtomicBoolean` JDK 8 standard,no `var` / `List.of` / sealed / records)
13. **0 新 Maven 依赖**(R-13 mitigation (d) 第 24 次 PASS)
14. **0 新 ErrorCode**(复用 `LINGS-P02`)
15. dsh §15.4 ErrorCode 域字母表不变(P 段维持 L01 / L02)
16. constitution v1.0 §1—§10 全部不变,只 §10 R-13 风险状态更新

---

## 6. 范围外(留 OQ-Future)

1. **Tool-level AskUser 真实现**(Bash `rm -rf` confirm / WebFetch 域外 confirm)— `specs/019-built-in-tools/plan.md:498` 仍待 OQ-#041-ext Story 实现;本 Story 仅接通 SPI,不实现具体 Tool 内的 AskUser 调用
2. **`ApprovalRegistry` sessionId-keyed API** — 当前是 single-`approvalId` key,`evictBySessionPrefix` 实际是 `clear()`(Javadoc L96-99 已知 limitation);扩为 `(sessionId, approvalId)` 复合 key 留 OQ-Future(本 Story 不动)
3. **CLI Stdin ApprovalGate** — CLI 路径(`SkillCommandDispatcher`)仍是 fail-safe Deny;未来 OQ-Future 实现 `/approval allow` 子命令时,需要 `cli` 子模块 override
4. **Service Mesh ApprovalGate** — k8s Service Mesh / Slack bot / Email-based ApprovalGate 等多通道实现留 OQ-Future(plugin 路径,符合 §5.7 SPI 决策)

---

## 7. 风险登记

| 风险 | 概率×影响 | 缓解 |
|---|---|---|
| **R-A** `DefaultApprovalGate` 真接通 + `LinearTurnEngine` inline 逻辑下沉 → regression(Story #030 23 case + 1 L3 round-trip 失败)| 2×5=10 | **AC-041-09 + AC-041-10 + AC-041-11 端到端验证**(路径走 ApprovalRegistry → ApprovalRequired event → ChatController);Story #030 23 case 必须重跑 0 回归;`LinearTurnEngineAskUserTest` 3 case 不改路径,只验证语义等价 |
| **R-B** `DefaultApprovalGate` 阻塞实现 leak(`CompletableFuture<Decision>` 未完成 + cancellation 未触发 → 永久 hang)| 1×4=4 | 默认 `approvalTimeoutSeconds = 0` = `Long.MAX_VALUE`(等效无超时);`CancellationToken.fire()` 联动取消(`fut.cancel(true)`);`approvalTimeoutSeconds > 0` 时强制 timeout(`LINGS-P02`);AC-041-04 / AC-041-03 覆盖 cancel + timeout 路径 |
| **R-C** `LinearTurnEngine.dispatchWithPolicy` 删除 inline 逻辑 → 编译错误 / 漏改 import | 2×3=6 | 严格顺序删除(`-` UUID / `Consumer` / `AtomicBoolean` / `CompletableFuture` / `ExecutionException` import 同步清理);`mvn compile` 强制 fail-fast |
| **R-D** `DefaultToolExecutionContext` 新增 3-arg ctor → 现有 2-arg ctor sites 编译错误 | 1×4=4 | 保留 2-arg ctor(back-compat,fallback `approvalRegistry = null`);`LinearTurnEngine.java:450` 改用 3-arg ctor;`mvn compile` fail-fast 兜底 |
| **R-E** R-13 mitigation (d) banned list 触发 | 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-041-baseline + tasks.md T-dep-tree-* + PR body `### R-13 dependency:tree 自查` 节;`#037` baseline 镜像已存,本 Story 第 24 次验证 0 binary delta |

---

## 8. Test Strategy 概览

| 层 | 用例数 | 覆盖 |
|---|---|---|
| **L1 Unit** | 8 | `DefaultApprovalGateTest` 5 case(AC-041-01—05 真接通 Allow/Deny/timeout/cancel/no-sink)+ `ServerSideApprovalStubsTest` 3 case(AC-041-06—08 fail-safe 守住) |
| **L2 Slice** | 2 | `LinearTurnEngineAskUserSpiIT` 2 case(AC-041-09 Allow 端到端 + AC-041-10 Deny 端到端,**语义等价 Story #030 inline 路径**) |
| **L3 Component** | 1 | `DemoProductAskUserSpiRoundTripIT` 1 case(AC-041-11 demo-product SSE round-trip,**0 regression vs Story #030**) |
| **L4 Contract** | 0 | 守住 — 见上文 §5 关键不变项 1-10 |
| **L8 兼容** | CI | `mvn -pl lingshu-core verify` + `banned-dependencies` enforcer + dep-tree baseline 镜像 diff |

**New Case 计数**:**11 test cases** 跨 4 文件(L1 8 + L2 2 + L3 1 = 11)
**ROADMAP 估算**:11 case,与 Story #030(13 case)/ #029(18 case)/ #031(26 case)/ #037(9 case)同量级但更轻

---

**Spec writer**: Claude Code
**Spec date**: 2026-10-03
**Spec version**: v0.1 Draft
