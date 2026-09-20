# Feature Specification: Story #006 multi-tenant

**Feature Branch**: `story-006-multi-tenant`
**Created**: 2026-09-21
**Status**: Draft
**Input**: User description: "Story #006 multi-tenant — TenantContext ThreadLocal + 配置/Session/Sandbox/Cost 四维隔离(AC-05)"

**Source Design Doc**: `dsh_agent_design.md` v1.5.34
- §0.4 AC-05 L115-119(多租户隔离 — alice/bob 独立 memory / cost / sandbox)
- §14.9 N9 L6816-6840(TenantContext ThreadLocal + 4 维隔离 + HTTP WebFilter 入口)
- §15 ErrorCode(本 Story **0 新增**;所有失败通过 LINGS-C02/C03 CONFIG_VALIDATION + LINGS-S01 SLOT_NOT_FOUND + LINGS-Z01 INTERNAL_PANIC 复用)
- §16 Glossary L7222(`TenantContext` ThreadLocal 形式,影响 memory / cost / sandbox / session)
- §17 Risk Register R-02 L7236(多租户 ThreadLocal 泄漏缓解 — (a) try-finally / (b) ThreadPoolExecutor 拒绝持有 ThreadLocal / (c) InheritableThreadLocal + clean)

**Constitution**: `.specify/memory/constitution.md` v1.0
- §1 #11 默认实现位置 lingshu-core 内置 + 按需加载(本 Story TenantContext 默认实现内置)
- §1 #12 启动时配置校验(TenantConfig validation 与 AgentConfig 一同启动期 fail-fast)
- §2 13 项依赖锁定(R-13 mitigation (d) dep-tree 自查,**0 新增** — `ThreadLocal` JDK 内置)
- §3 NFR baseline:`tenantId` 维度不影响并发 turn 数 P50 ≤ 30s / P99 ≤ 60s
- §4 错误码约定:`LINGS-C02 / C03` 配置失败复用;本 Story 0 新增 ErrorCode
- §5 7 层金字塔:L1 Unit(TenantContext) + L1 Unit(TenantResolver) + L2 Slice(4-dim isolation)+ L5 E2E(AC-05 黑盒)
- §10 R-02 多租户 ThreadLocal 泄漏缓解 — 本 Story 必须落实 (a) try-finally + (b) `currentTenant()` snapshot + (c) `TenantContext.Transferable` reject(详见 FR-005 + FR-012)

**对应 AC**: **AC-05**(多租户隔离 §0.4 L115-119)— `agent.tenants[alice]` 与 `agent.tenants[bob]` 各自有独立 memory dir + cost budget + sandbox whitelist;Alice 写 memory 切到 Bob 后**零交叉**;cost 独立计数;whitelist 各生效;§14.9 TenantContext ThreadLocal + 配置/Session/Sandbox/Cost 四维隔离验证。

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — TenantContext 基础 ThreadLocal 切换 (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者 / 单租户部署方)**,我在 application.yml 配置 `agent.tenants.alice.memory.dir=./.agent/memory/alice` + `sandbox.command-whitelist=[ls,cat]` + `session.cost-budget-usd=10`,**期望** 我业务代码入口用 `TenantContext.runAs("alice", () -> agent.runTurn(...))` 包裹后,turn 内所有 memory 写入 `./.agent/memory/alice/`、sandbox 只允许 `ls/cat`、cost 计入 alice 的 budget —— 不需要业务代码感知 tenant 维度。这样我可以**一份代码服务多个客户**而不污染数据。

**Why this priority**: 这是 **AC-05 的基础机制**。当前(Story #001—#005 已 merged)所有 turn 共用全局 single-tenant 配置,业务方无法隔离多客户数据 —— SaaS 部署硬阻塞:Alice 的 turn 写到全局 memory,Bob 读出来是 Alice 的隐私,**违反 §14.9 关键不变量**「租户 A 的 Tool 永远看不见租户 B 的 session / file / network」。**缺它** 任何多客户场景都不可行,LingShu 仅能单机开发用。

**Independent Test**: 在 `lingshu-core/src/test/.../tenant/TenantContextTest`(纯 L1 Unit,不启 Spring)加 1 个核心用例 —— 验证 `TenantContext.runAs("alice", () -> TenantContext.current())` 返 `"alice"`,try 块结束(或抛异常)后 `TenantContext.current()` 返 `null`(**不残留** — 关键 R-02 缓解 (a))。

**Acceptance Scenarios**:

1. **Given** 当前线程 `TenantContext.current() == null`(未设置)
   **When** 调 `TenantContext.runAs("alice", () -> TenantContext.current())`
   **Then** 回调内收到 `"alice"`,**且** 回调结束后 `TenantContext.current() == null`(自动清理)
   **And** 回调内抛 RuntimeException 也触发清理(R-02 (a) try-finally 兜底)

2. **Given** 主线程 set "alice"
   **When** 提交 1 个 `Runnable` 到普通 `ExecutorService`(无 InheritableThreadLocal)
   **Then** 子线程内 `TenantContext.current() == null`(ThreadLocal 不跨线程 — §3 JDK 硬约束,子线程默认不复用)
   **And** 业务必须用 `runAs(...)` 显式包裹(R-02 (b) 隐含)

3. **Given** 主线程 set "alice"
   **When** 调 `TenantContext.snapshot()` 拿 immutable 拷贝
   **Then** 之后主线程 set "bob" 不影响 snapshot 值
   **And** snapshot 传给子线程,子线程用 `TenantContext.runWithSnapshot(snap, () -> ...)` 还原(无 ThreadLocal 跨线程污染)

4. **Given** 同线程嵌套 `runAs("alice", () -> runAs("bob", () -> ...))`
   **When** 内层回调返回
   **Then** 外层回调仍看到 "alice"(栈式保存 — inner set 不污染 outer scope)
   **And** 最外层 runAs 结束后 current() == null

---

### User Story 2 — Memory 路径模板按租户分流 (Priority: P1)

作为 **Alice**,我在 yml 配 `agent.tenants.alice.memory.dir=./.agent/memory/alice`,**期望** turn 内 FileMemorySource 写入的文件**只**落在 alice 目录,bob 跑 turn 写入 `./.agent/memory/bob/`,两者**零交叉**(AC-05 明文要求)。

**Why this priority**: 这是 **AC-05 4 维隔离中的「Memory」一维**。当前(Story #002)FileMemorySource 路径模板来自 yml `agent.memory.dir`,**全局** single value —— 切租户不切换路径,alice 写的 memory bob 看得见,**严重违反** §14.9 关键不变量。**优先级 P1** 因为 AC-05 明文验证 memory 零交叉,缺它 AC-05 直接 fail。

**Independent Test**: 在 `lingshu-core/src/test/.../tenant/MemoryPathIsolationTest`(L1 Unit + 临时目录)加 1 个用例 —— 起 2 个 `FileMemorySource` 各绑 `./target/test/alice/memory` 与 `./target/test/bob/memory`,模拟 alice 写入 `"secret-alice"`,然后切换到 bob source 读,must `null`(零交叉);切回 alice 仍读到。

**Acceptance Scenarios**:

1. **Given** yml 配 `agent.tenants.alice.memory.dir=./.agent/memory/alice` + `agent.tenants.bob.memory.dir=./.agent/memory/bob`
   **When** Alice 跑 1 个 turn 写入 memory `[tag=note, content="alice-secret"]`
   **And** Bob 跑 1 个 turn 写入 memory `[tag=note, content="bob-secret"]`
   **Then** `./.agent/memory/alice/notes.jsonl` 包含 "alice-secret",**不**包含 "bob-secret"
   **And** `./.agent/memory/bob/notes.jsonl` 包含 "bob-secret",**不**包含 "alice-secret"
   **And** `./.agent/memory/shared/` 目录**不**被创建(无兜底共享路径)

2. **Given** alice turn 跑完,memory 已落盘 `./.agent/memory/alice/notes.jsonl`
   **When** Bob 跑 1 个 turn 读取 memory
   **Then** Bob 读到的是 `./.agent/memory/bob/notes.jsonl` 内容(可能为空),**绝不**读 alice 文件
   **And** 即使 alice 目录存在且可读,bob 路径解析也**不**回退到 alice(无 fallback)

3. **Given** 业务代码在 bob turn 内调 `MemorySource.write(...)`,TenantContext.current() == "bob"
   **When** MemorySource 内部解析路径
   **Then** 它必须读 `TenantContext.current()` 而不是 yml 全局 `agent.memory.dir`(否则两个 tenant 写同一个文件)
   **And** 路径模板引擎支持占位符:`${tenant}` → tenantId(便于 yml 写 `agent.tenants.alice.memory.dir=./.agent/memory/${tenant}`)

---

### User Story 3 — Cost budget 按租户独立计数 (Priority: P1)

作为 **Alice**,我在 yml 配 `agent.tenants.alice.cost.session-budget-usd=10` 与 `agent.tenants.bob.cost.session-budget-usd=100`,**期望** alice 跑 turn 用到 USD 5 后,budget 剩 USD 5;切到 bob 后 bobs budget 仍 USD 100(独立计数器),alice 的余额**不**影响 bob。这样每个客户的预算独立,Alice 跑超不会让 Bob 也无法跑。

**Why this priority**: 这是 **AC-05 4 维隔离中的「Cost」一维**。当前(Story #001—#005)成本计数器是**全局**单计数器,所有 turn 共享一个 budget —— 客户 A 跑超额,客户 B 也无法跑,**违反 SaaS 计费模型**。**优先级 P1** 因为 AC-05 明文要求 "cost budget 独立计数"。

**Independent Test**: 在 `lingshu-core/src/test/.../tenant/CostBudgetIsolationTest`(L1 Unit + Mockito Mock LlmProvider)加 1 个用例 —— 模拟 alice turn 产生 USD 5 token,bob turn 产生 USD 3 token;断言 alice counter == 5,bob counter == 3,**各自独立**;alice 跑到 USD 8(累加)仍在 alice budget 内,bob 跑到 USD 50 超 bob budget 抛 `CostBudgetExceededException(budget=100, used=50)`。

**Acceptance Scenarios**:

1. **Given** yml `agent.tenants.alice.cost.session-budget-usd=10` + `agent.tenants.bob.cost.session-budget-usd=100`
   **When** Alice 跑 1 个 turn 消耗 USD 5,Bob 跑 1 个 turn 消耗 USD 3
   **Then** Alice 的 `CostTracker.usedMicros` == 5_000_000(USD 5 = 5_000_000 micros,假设 1 USD = 1_000_000 micros)
   **And** Bob 的 `CostTracker.usedMicros` == 3_000_000
   **And** 两者**不**相互影响

2. **Given** Alice 已消耗 USD 5(budget 10 剩 5)
   **When** Alice 再跑 1 个 turn 想消耗 USD 8(总 13 > 10)
   **Then** turn 启动前 fail-fast,抛 `CostBudgetExceededException("alice", budget=10_000_000, used=5_000_000, attempted=8_000_000)`
   **And** Bob 不受任何影响,budget 仍 USD 100

3. **Given** TenantContext.current() == null(无租户上下文)
   **When** 任何 turn 想消耗 token
   **Then** 走 fallback 全局 budget(由 `agent.cost.session-budget-usd` 控制,默认无上限)
   **And** 启动期 `AgentFactory.create()` 校验:`agent.tenants` map 为空 → 不创建 TenantContext,**不**强制要求租户

---

### User Story 4 — Sandbox whitelist 按租户独立 (Priority: P1)

作为 **Alice**,我在 yml 配 `agent.tenants.alice.sandbox.command-whitelist=[ls,cat,echo]` 与 `agent.tenants.bob.sandbox.command-whitelist=[git,npm]`,**期望** alice 跑 turn 调用 sandbox 跑 `["git", "status"]` 被拒(白名单不含),bob 跑同一 `["git", "status"]` 成功。这样 Alice 的开发机 sandbox 配置不暴露给 Bob,反之亦然。

**Why this priority**: 这是 **AC-05 4 维隔离中的「Sandbox」一维**。当前(Story #001—#005)`RuntimeSandbox` 接受 yml 全局 `agent.sandbox.command-whitelist`,**全局** single list —— 切租户不切换白名单,alice 配置严格(bash 也不允许)bob 配置宽松(含 git),业务方必须妥协成最严的兜底白名单,**违反 §14.9 关键不变量**。**优先级 P1** 因为 AC-05 明文要求 "sandbox whitelist 各生效"。

**Independent Test**: 在 `lingshu-core/src/test/.../tenant/SandboxWhitelistIsolationTest`(L1 Unit + FakeCommandRunner)加 1 个用例 —— alice turn 调 sandbox 跑 `["rm", "-rf", "/"]`(alice 白名单不含 rm)→ 抛 `SandboxPermissionDeniedException("rm", tenantId="alice")`;bob 跑同一 `["rm"]` → bobs 白名单有 rm → 成功(或 bobs 白名单也无 → 同样拒)。

**Acceptance Scenarios**:

1. **Given** yml `agent.tenants.alice.sandbox.command-whitelist=[ls,cat]` + `agent.tenants.bob.sandbox.command-whitelist=[ls,cat,git,npm]`
   **When** Alice turn 调 sandbox 跑 `["git", "status"]`
   **Then** 抛 `SandboxPermissionDeniedException("git", tenantId="alice", whitelist=[ls,cat])`,turn 写 ToolResult.error
   **And** Bob turn 跑同一 `["git", "status"]` 成功

2. **Given** TenantContext.current() == null
   **When** sandbox 跑任何命令
   **Then** 走 yml 全局 `agent.sandbox.command-whitelist`(无 tenant 上下文时兜底)
   **And** 启动期 warn 日志:`WARN no tenant context, falling back to global sandbox whitelist`

3. **Given** Alice turn 内 tool 调用 sandbox 跑 `["ls"]`(alice 白名单含 ls)
   **When** sandbox 内部解析 whitelist
   **Then** 它必须读 `TenantContext.current()` + 调 `TenantConfigProvider.resolve("alice").sandbox().commandWhitelist()`
   **And** **不**读 yml 全局 `agent.sandbox.command-whitelist`(无 tenant 时才读)

---

### User Story 5 — Session key 按租户前缀(防跨租户 session 串读)(Priority: P2)

作为 **Alice**,我在 yml 配 `agent.session-store.backend=file` + `agent.tenants.alice.session.dir=./.agent/sessions/alice`,**期望** Alice 跑 1 个 turn 写 session `sess-123` 落盘 `./.agent/sessions/alice/sess-123.json`,Bob 的 `sess-123`(同 id)落 `./.agent/sessions/bob/sess-123.json`,两者**不**冲突。这样即使两个客户用同样的 sessionId,数据**不**串。

**Why this priority**: 这是 **AC-05 4 维隔离中的「Session」一维**。当前(Story #014 之前 — SessionStore 4 后端尚未实装,但 InMemorySessionStore 已存在)session key 是 `sessionId` 本身,无 tenant 前缀 —— 切租户不切换 namespace,alice 的 `sess-123` 和 bob 的 `sess-123` 内存里撞 key,**违反 §14.9 关键不变量**「租户 A 的 Tool 永远看不见租户 B 的 session」。**优先级 P2** 因为 AC-05 只说 "memory / cost / sandbox" 三维,**未**明文提 session —— 但 §14.9 4 维隔离把 session 列入,**正确性**必须补,**完整性**可以后续 Story 强化(若 Story #014 集成时再细化)。

**Independent Test**: 在 `lingshu-core/src/test/.../tenant/SessionKeyIsolationTest`(L1 Unit + InMemorySessionStore)加 1 个用例 —— alice 写 session `sess-123` content="alice-state",bob 写同一 `sess-123` content="bob-state";切回 alice 读 → "alice-state",切回 bob 读 → "bob-state",**互不可见**。

**Acceptance Scenarios**:

1. **Given** alice turn 调 `SessionStore.put("sess-123", state-alice)`,TenantContext.current() == "alice"
   **When** SessionStore 内部 build key
   **Then** 实际存储 key = `alice:sess-123`(tenantId 前缀,冒号分隔 — Redis 风格)
   **And** `SessionStore.get("sess-123")` 在 alice 上下文返 `state-alice`

2. **Given** alice 写完 `sess-123`,bob 上下文 put 同 id `sess-123`
   **When** bob 调 `SessionStore.get("sess-123")`
   **Then** 返 `state-bob`,**不**是 `state-alice`(key 已带 tenantId 前缀 → 物理隔离)
   **And** alice 调 `get("sess-123")` 仍返 `state-alice`(alice 上下文正确解析到 alice 的 key)

3. **Given** TenantContext.current() == null(无租户上下文)
   **When** SessionStore put/get
   **Then** 走全局 key(无前缀),与 Story #014 之前兼容

---

### User Story 6 — TenantConfigProvider SPI + AgentFactory 启动期校验 (Priority: P1)

作为 **Charlie(框架贡献者)**,我在 Story #006 之上扩展,需要 `TenantConfigProvider` SPI 类似 `LlmProvider` SPI,可插入不同来源(In-memory yml / Database / Vault)。同时 AgentFactory 启动期校验 `agent.tenants` map 非空时**所有** tenant 配置必须合法(每 tenant 必填 memory.dir + sandbox.command-whitelist + cost.session-budget-usd),缺一启动期 fail-fast 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED` 列出缺失字段。

**Why this priority**: 这是 **AC-05 完整实现必要条件**。当前(Story #001—#005)`AgentConfig` 是单一对象,无 tenant map —— 没有 SPI 入口,业务方无法换数据源;无启动期校验,缺字段直到运行时才报,**违反 §1 #12 启动时配置校验**。**优先级 P1** 因为 AC-05 黑盒依赖 yml 配置**合法**才可验证。

**Independent Test**: 在 `lingshu-core/src/test/.../tenant/TenantConfigValidationTest`(L1 Unit + SpringBootTest)加 1 个用例 —— 配 yml 缺 `agent.tenants.alice.cost.session-budget-usd`(漏字段),启动 AgentFactory 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED`,message 列出 `"tenants.alice.cost.session-budget-usd is required"`。

**Acceptance Scenarios**:

1. **Given** yml 配 `agent.tenants.alice` 漏 `cost.session-budget-usd`
   **When** Spring Boot 启动 AgentFactory
   **Then** 启动失败,抛 `LINGS-C02 CONFIG_VALIDATION_FAILED`,message 列出缺失字段路径
   **And** 不进入正常 Agent 主流程

2. **Given** yml 配 `agent.tenants.alice` 与 `agent.tenants.bob` 均合法
   **When** Spring Boot 启动 AgentFactory
   **Then** 启动成功,日志输出 `INFO loaded 2 tenant config(s): [alice, bob]`
   **And** 业务代码可调 `TenantConfigProvider.resolve("alice")` 拿 alice config,`resolve("bob")` 拿 bob

3. **Given** yml **不**配 `agent.tenants`(单租户模式)
   **When** Spring Boot 启动 AgentFactory
   **Then** 启动成功,TenantConfigProvider 返 fallback `Optional.empty()`
   **And** 所有 turn 走 yml 全局配置(与 Story #001—#005 完全兼容)
   **And** 日志 `INFO multi-tenant disabled, using global config`(不是 WARN,正常路径)

4. **Given** 业务代码调 `TenantConfigProvider.resolve("ghost")` 不存在的 tenant
   **When** resolve 返回
   **Then** 抛 `LINGS-S01 SLOT_NOT_FOUND`(复用,**不**新增 ErrorCode)or `IllegalArgumentException`
   **And** 日志 `WARN tenant config not found: ghost`(无静默吞错)

---

### Edge Cases

- **嵌套 `runAs`(US1 S4)**:栈式保存 — 内层 runAs 不污染外层,R-02 (a) 标准实现
- **回调抛异常**:runAs 内 Runnable 抛 RuntimeException,**仍**触发 cleanup(US1 S1 增强),避免 ThreadLocal 残留
- **无租户上下文访问 TenantConfig**:`resolve` 返空 + 日志 warn,业务代码可选择继续或抛错(由业务方决策)
- **yml 配 tenants 但 TenantContext.runAs 未包裹**:turn 启动时 `AgentFactory.create()` 校验 yml 有 tenants → 必须 runAs,**不**允许裸跑(yaml-on / code-off 配置错位)→ 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED`(runtime)
- **跨线程池任务**:ExecutorService 子线程无 TenantContext(普通 ThreadLocal 不跨线程),业务方必须用 `TenantContext.runWithSnapshot(snap, ...)` 显式还原(US1 S3) — 不引 InheritableThreadLocal(污染风险 — R-02 (c) 主动放弃)
- **yml 配 tenants 但 Turn 入口不 runAs**:`LinearTurnEngine.runTurn` 入口检查 TenantContext,缺则抛 `LINGS-C02 CONFIG_VALIDATION_FAILED` + 清晰 message(防漏包裹)
- **runAs(null, ...)**:显式清除 TenantContext(语义 = 退出当前租户),等价 `clear()`
- **runAs("", ...)** 或 `runAs("   ", ...)`:抛 `IllegalArgumentException`(tenantId 不能空字符串 — 防误用)
- **重复 set 同 tenantId**:`runAs("alice")` 在 alice 上下文再调,等价嵌套,栈式不变(无 surprise)
- **tenantId 含特殊字符(冒号/换行)**:启动期校验 reject(只允许 `[a-zA-Z0-9_-]{1,64}`),防 key 注入(US5 S1 Redis 风格 key 撞号)
- **超大 tenants map**:启动期校验通过 N ≤ 1000(防 DoS — 配置错乱导致内存爆炸),超 N 启动失败
- **yml 热更租户配置**(Story #007 范畴):本 Story **不**实现 — 后续 Story #007 AtomicReference swap 时,tenants map 也走同 swap 路径,本 Story 只定义数据结构
- **Session 跨租户**:session 4 维隔离是 P2,若 Story #014 集成时发现需要更精细(如按 user_id 而非 tenantId 二级分组),Story #006 不预判
- **成本单位**:yml 用 USD(易理解),内部 `usedMicros` 是 1 USD = 1_000_000 micros(整数算术,避浮点)
- **租户上下文切换 turn 之间**:turn N 跑完 cleanup,turn N+1 必须重新 runAs(无隐式持久化)

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**:新增 `TenantContext` 类(包 `ai.lingshu.core.tenant`),**static** ThreadLocal 形式,提供:
  - `static String current()` 返当前 tenantId 或 null
  - `static void set(String tenantId)` 设置(tenantId 必须非空 + 匹配 `[a-zA-Z0-9_-]{1,64}` 否则 `IllegalArgumentException`)
  - `static void clear()` 清空(等价 set(null))
  - `static String snapshot()` 拿当前 tenantId immutable copy(返回 String 是不可变,等同)
  - `static <T> T runAs(String tenantId, Supplier<T> work)` 包裹 + try-finally 自动 clear
  - `static void runAs(String tenantId, Runnable work)` void 版(FR-005 配套)
  - `static <T> T runWithSnapshot(String snapshot, Supplier<T> work)` 跨线程传递用,内部 set + try-finally(FR-006)
  - 内部用 `Deque<String>` 维护嵌套栈(US1 S4 嵌套不污染)而非单一 ThreadLocal<String>
- **FR-002**:`RuntimeSandbox` 在解析 `command-whitelist` 时优先读 `TenantConfigProvider.resolve(TenantContext.current()).sandbox().commandWhitelist()`;无 tenant 上下文兜底读 yml 全局 `agent.sandbox.command-whitelist`(US4 S2/S3);找不到命令抛 `LINGS-S01 SLOT_NOT_FOUND`(或现有 `SandboxPermissionDeniedException` 复用,具体异常类型由 plan.md 决定)
- **FR-003**:`FileMemorySource` 路径模板支持 `${tenant}` 占位符(US2 S3),实际路径 = yml 模板替换 `TenantContext.current()` 后值;无 tenant 上下文时占位符替换失败抛 `LINGS-C02 CONFIG_VALIDATION_FAILED`
- **FR-004**:`InMemorySessionStore`(Story #014 之前 stub)build key 时拼接 `tenantId + ":" + sessionId`(US5 S1);无 tenant 上下文时只返 sessionId(全局兼容)
- **FR-005**:`TenantContext.runAs(...)` 内部 try-finally 兜底,即使回调抛 RuntimeException 也触发 `clear()`(R-02 (a) 显式约束 — Edge Case "回调抛异常")
- **FR-006**:跨线程场景:`TenantContext.snapshot()` 拿当前 tenantId 字符串,子线程用 `runWithSnapshot(snap, () -> ...)` 还原(US1 S3 + R-02 (b) 显式约束 — 主动放弃 InheritableThreadLocal 防污染)
- **FR-007**:新增 `TenantConfig` 配置类(Lombok `@Value` + `@Builder`),字段:
  - `String tenantId`(必填,匹配 `[a-zA-Z0-9_-]{1,64}`)
  - `TenantMemoryConfig memory`(嵌套:`String dir` 必填)
  - `TenantSandboxConfig sandbox`(嵌套:`List<String> commandWhitelist` 必填,可空 list 表示全拒)
  - `TenantCostConfig cost`(嵌套:`long sessionBudgetMicros` 必填,正数)
  - 用 Lombok `@Value` 不可变,深拷贝 List 引用防外部 mutation(US2-5 配套)
- **FR-008**:新增 `TenantConfigProvider` SPI(类似 `LlmProvider` SPI):
  - `interface TenantConfigProvider { String name(); int priority(); Optional<TenantConfig> resolve(String tenantId); List<String> listTenantIds(); }`
  - 默认实现 `YamlTenantConfigProvider`(`@Component`,从 `AgentConfig.tenants` map 解析)
  - 多 Provider 共存:`@Bean(name = "tenantConfigProvider_yaml")` + `name="yaml", priority=10`(Story #003 多 Provider 模式)
  - 替代实现(留待用户扩展):`DatabaseTenantConfigProvider`(从 DB 读)/ `VaultTenantConfigProvider`(从 Vault 拉密钥)
- **FR-009**:`AgentConfig` 新增字段 `Map<String, TenantConfig> tenants`(US6 配套),Lombok `@Value` 兼容(用 `@Builder.Default` 给空 map 默认值),yaml binding `agent.tenants.<tenantId>.<...>`
- **FR-010**:`AgentFactory.create(config)` 启动期校验:
  - 若 `config.getTenants()` 非空,每个 TenantConfig 字段必填校验(US6 S1),缺字段抛 `LINGS-C02 CONFIG_VALIDATION_FAILED` + 字段路径列表
  - 若 `config.getTenants()` 非空,总数 ≤ 1000(Edge Case "超大 tenants map"),超 N 抛 `LINGS-C02`
  - 若 `config.getTenants()` 非空,**且** turn 入口未 runAs,`LinearTurnEngine.runTurn` 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED` + message "tenants configured but turn started without TenantContext.runAs"(Edge Case "yml 配 tenants 但 Turn 入口不 runAs")
- **FR-011**:`LinearTurnEngine.runTurn` 入口(在 §14.12 cancellation 检查之后)新增 `if (config.getTenants() != null && !config.getTenants().isEmpty() && TenantContext.current() == null) throw new LingsConfigException("C02", ...)`(FR-010 第三条细化)
- **FR-012**:`CostTracker` 接受 `String tenantId` 参数(US3 配套),turn 启动时 `new CostTracker(TenantConfigProvider.resolve(TenantContext.current()).map(c -> c.cost().sessionBudgetMicros()).orElse(globalBudget))`,累加时按 tenantId 分桶(全局 Map<tenantId, Long> + atomic add)
- **FR-013**:`RuntimeSandbox` 创建时持有 `TenantConfigProvider`(FR-008 配套);turn 内首次调用 sandbox 时 `TenantContext.current()` 变化 → 重新解析 whitelist(每调用前 snapshot 一次 — 防止 turn 内半路切租户的隐蔽漏洞,R-02 加强)
- **FR-014**:新增 `TenantConfigValidationTest`(L1) + `MemoryPathIsolationTest`(L1) + `CostBudgetIsolationTest`(L1) + `SandboxWhitelistIsolationTest`(L1) + `SessionKeyIsolationTest`(L1) + `TenantIsolationIT`(L2/L5 E2E 集成,对应 AC-05 黑盒)
- **FR-015**:`TenantContext` 类 Javadoc 明确「ThreadLocal 跨线程不传递 — 跨线程必须用 snapshot + runWithSnapshot;不引 InheritableThreadLocal 防污染」(对应 R-02 (c) 主动放弃 + 设计决策记录)
- **FR-016**:`LINGS-Z01 INTERNAL_PANIC` 复用:tenant 配置存在但 `TenantConfig` 对象为空(null in map)→ 抛 `LINGS-Z01 INTERNAL_PANIC`(启动期 fail-fast)

### Non-Functional Requirements

- **NFR-001**:AC-05 黑盒验证通过 — `TenantIsolationIT#aliceAndBobIsolated_across4Dims` 测试通过,断言 4 维(配置 / Memory / Cost / Sandbox)零交叉
- **NFR-002**:JDK 8 兼容 — `ThreadLocal` / `Deque<ArrayDeque>` / `Supplier` / `Collections.unmodifiableMap()` 全部 JDK 8 内置,**不**引 Reactor / RxJava / Guava
- **NFR-003**:无新增 Maven 依赖 — `ThreadLocal` + `Deque` + `Supplier` JDK 内置,**0 新增**(constitution §2 R-13 mitigation (d) dep-tree 自查)
- **NFR-004**:**0 新增** ErrorCode(本 Story 复用 `LINGS-C02 / C03 / S01 / Z01`)
- **NFR-005**:`TenantContext` 静态方法无锁(ThreadLocal 自带 per-thread 隔离),`runAs` 嵌套栈用 `ArrayDeque`(非并发场景,无 CAS 必要)
- **NFR-006**:`TenantContext.runAs` 性能:`runAs(null, noop)` 内部 1 次 set + 1 次 clear,纳秒级,无明显 overhead(US1 性能 baseline)
- **NFR-007**:`TenantConfigProvider.resolve(tenantId)` 是 O(1) Map 查找,**不**遍历 list(N ≤ 1000 保证线性可接受)
- **NFR-008**:R-02 缓解 3 件套全部落地 — (a) try-finally(FR-005) + (b) snapshot+runWithSnapshot(FR-006) + (c) 主动放弃 InheritableThreadLocal(FR-015 Javadoc 记录决策)
- **NFR-009**:Story #001—#005 全部已有测试用例仍 green(无回归 — 多租户是 additive,不影响 single-tenant 路径)

### Key Entities

- `TenantContext` (new):static utility,ThreadLocal + Deque 嵌套栈
- `TenantConfig` (new):@Value + @Builder,4 维配置(tenantId / memory / sandbox / cost)
- `TenantMemoryConfig` (new):nested @Value,`String dir`
- `TenantSandboxConfig` (new):nested @Value,`List<String> commandWhitelist`
- `TenantCostConfig` (new):nested @Value,`long sessionBudgetMicros`
- `TenantConfigProvider` (new):SPI interface + 默认 `YamlTenantConfigProvider` 实现
- `AgentConfig` (modify):加 `Map<String, TenantConfig> tenants` 字段
- `RuntimeSandbox` (modify):持有 TenantConfigProvider + 每次解析时读 TenantContext
- `FileMemorySource` (modify):路径模板支持 `${tenant}` 占位符
- `InMemorySessionStore` (modify):key 前缀 tenantId(若 tenant 上下文存在)
- `CostTracker` (modify):按 tenantId 分桶累加
- `LinearTurnEngine` (modify):runTurn 入口加 tenant 上下文校验
- `AgentFactory` (modify):create() 启动期校验 tenants 配置合法性

**Total**:6 new + 6 modified = **12 文件改动**(轻度超 CLAUDE.md §11 #4 "≤ 5 核心文件改动" 软上限,Story #005 precedent 也是 7 → 8 文件)

**超上限原因**:
1. Story #006 by nature 触及 4 维隔离(Memory / Cost / Sandbox / Session)+ 1 个新 SPI + 2 个新配置类 + 1 个新 utility,12 文件是 4 维隔离最少集
2. **不**触 A2A(A2A 不属于本 Story 范围)
3. **不**触 AuditLogger(Audit 单独 Story #016)
4. **不**触 CancellationToken(Story #005 已固化,本 Story 不修改)
5. **不**触 LlmProvider(LLM 不属于本 Story 范围,LLM Provider 协议与 tenant 解耦)

如果想严格 ≤ 5:把 Session 4 维隔离(US5 P2)推迟到 Story #014 — 整体可降到 11 文件。但本 Story 范围已包,推荐保留(完整性 > 严格 ≤ 5)。

---

## Success Criteria *(mandatory)*

- **SC-001**:`mvn -pl lingshu-core test` 全绿(L1 Unit + L2 Slice + L5 E2E 全部通过,预计 ≥ 25 测试用例)
- **SC-002**:`TenantIsolationIT#aliceAndBobIsolated_across4Dims` 测试通过(AC-05 黑盒核心断言)— alice 写 memory 后 bob 看不到,alice cost 累加不影响 bob budget,alice sandbox 拒 git 时 bob sandbox 允许 git,alice session key 不与 bob 撞号
- **SC-003**:`TenantContextTest#nestedRunAs_stackBasedPreservation` 测试通过(US1 S4)— 内层 runAs(bob) 不污染外层 runAs(alice)
- **SC-004**:`TenantContextTest#cleanupOnException` 测试通过(US1 S1 + Edge Case "回调抛异常")— runAs 内 Runnable 抛 RuntimeException 后 current() == null
- **SC-005**:`TenantConfigValidationTest#missingCostField_failsFast` 测试通过(US6 S1 + FR-010)— yml 缺 `cost.session-budget-usd` 时 AgentFactory.create() 抛 `LINGS-C02`
- **SC-006**:`mvn dependency:tree` 输出与 Story #005 baseline 一致 — 0 行新增依赖(满足 §2 R-13 mitigation (d))
- **SC-007**:PR body 末尾有 `### R-13 dependency:tree 自查` 节,贴关键子树(对比 Story #005 baseline)
- **SC-008**:无回归 —— Story #001—#005 全部 19 测试用例仍 green(US6 S3 兼容路径保留)
