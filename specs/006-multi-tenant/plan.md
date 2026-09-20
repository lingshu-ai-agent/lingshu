# Implementation Plan: Story #006 multi-tenant

**Branch**: `story-006-multi-tenant` | **Date**: 2026-09-21 | **Spec**: [`spec.md`](./spec.md)

**Input**: Feature specification from `/Users/lineng/specs/006-multi-tenant/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Implement `TenantContext` (ThreadLocal + 嵌套栈 utility) + `TenantConfigProvider` SPI + 4-dim isolation(配置 / Session / Sandbox / Cost)让 LingShu 支持多租户 SaaS 部署。核心交付:
- 1 个 static utility `TenantContext`(ThreadLocal + Deque 嵌套栈 + try-finally 兜底)
- 1 个 SPI `TenantConfigProvider` + 默认 `YamlTenantConfigProvider` 实现
- 4 个配置类 `TenantConfig` + 3 个 nested 子配置(`TenantMemoryConfig` / `TenantSandboxConfig` / `TenantCostConfig`)
- `AgentConfig` 加 `Tenants tenants` 字段
- `RuntimeSandbox` / `CostTracker` / `InMemorySessionStore` 改造为 tenant-aware
- `AgentFactory.create()` 启动期校验 tenants 配置合法性

对应 dsh §14.9 N9 + §17 R-02 缓解 3 件套(try-finally + snapshot+runWithSnapshot + 主动放弃 InheritableThreadLocal)。

## Technical Context

**Language/Version**: Java 1.8(项目真理锁定 CLAUDE.md §2 / dsh §10.1)
**Primary Dependencies**: Spring Boot 3.2.5 / Lombok 1.18.30 / reactive-streams 1.0.4(13 项锁定,**0 新增**)
**Storage**: TenantConfig 是 in-memory 内存对象(yaml 启动期 load);TenantContext 是 ThreadLocal(per-thread,无持久化);SessionStore key 加 tenantId 前缀(stub 阶段仅 in-memory Map)
**Testing**: JUnit 5.10.x + AssertJ 3.24.x + Mockito 5.x + Awaitility 4.2.x(constitution §5 7 层金字塔,本 Story L1 + L2 + L5 E2E)
**Target Platform**: Linux / macOS / Windows(JVM 8/11/17/21 LTS)
**Project Type**: Java 库 + Spring Boot 多模块
**Performance Goals**:
- `TenantContext.runAs(null, noop)` 内部 1 次 set + 1 次 clear,**纳秒级**(NFR-006)
- `TenantConfigProvider.resolve(tenantId)` O(1) Map 查找(NFR-007)
- 不影响 NFR baseline(turn P50 ≤ 30s / P99 ≤ 60s)
**Constraints**:
- JDK 8 only — 不用 `record` / `sealed` / `var` / `List.of`(CLAUDE.md §3)
- 13 项依赖锁定,**0 新增** — `ThreadLocal` / `Deque` / `Supplier` JDK 内置(NFR-003)
- 0 新增 ErrorCode(FR-016 复用 LINGS-C02/C03/S01/Z01)
- Story 边界 ≤ 5 核心文件改动软上限(CLAUDE.md §11 #4,本 Story 12 文件轻度超,Story #005 7 文件 precedent)
**Scale/Scope**: N tenants ≤ 1000(FR-010 上限),单 turn 单 Agent(Story #005 不变项),并发 turn 数 ≤ 32(§3 NFR baseline)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 章节 | 约束 | 本 Story 是否符合 | 备注 |
|---|---|---|---|
| §1 #1 JDK 8 兼容 | 不用 record/sealed/var/List.of | ✅ | `ThreadLocal` / `Deque<ArrayDeque>` / `Supplier` 全部 JDK 8 内置 |
| §1 #5 Skill 与 Tool 边界 | Skill 与 Tool 共用接口 | N/A | 本 Story 不涉及 Skill/Tool |
| §1 #6 子 Agent 注册 | SubAgentType 枚举 + 启动期校验 | N/A | 本 Story 不涉及 SubAgent |
| §1 #9 Plugin 发现 | Spring Boot SPI(**不**选 Java SPI) | ✅ | `TenantConfigProvider` 用 `@Component` + 多 Provider 模式(dsh §5.28 多 Provider 模板) |
| §1 #11 默认实现位置 | lingshu-core 内置,按需加载 | ✅ | `YamlTenantConfigProvider` 内置 core |
| §1 #12 启动时校验 | 集中 fail-fast | ✅ | FR-010 三条启动期校验 |
| §2 13 项依赖锁定 | 0 新增 | ✅ | 全部 JDK 8 内置 API |
| §3 NFR baseline | turn P50 ≤ 30s / P99 ≤ 60s | ✅ | 纳秒级 overhead 不影响 |
| §4 错误码约定 | `LINGS-<域><编号>` | ✅ | 复用 LINGS-C02 / C03 / S01 / Z01,**0 新增** |
| §5 7 层金字塔 | L1 + L2 + L5 E2E | ✅ | 计划 L1 Unit × 11 + L2 Slice × 6 + L5 E2E × 1 = 18 测试用例 |
| §10 R-02 多租户 ThreadLocal 泄漏 | (a) try-finally / (b) Executor 拒绝 / (c) InheritableThreadLocal + clean | ✅ | FR-005 try-finally + FR-006 snapshot+runWithSnapshot + FR-015 Javadoc 主动放弃 InheritableThreadLocal(FR 显式约束) |

**GATE 结果**:全部 ✅,**通过**。

---

## Project Structure

### Documentation (this feature)

```text
specs/006-multi-tenant/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── tenant-context.md # TenantContext + TenantConfigProvider contract
├── tasks.md             # Phase 2 output (/speckit-tasks command)
├── spec.md              # Story #006 spec
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
lingshu-core/src/main/java/ai/lingshu/core/
├── tenant/                                       # 🆕 Story #006 NEW PACKAGE
│   ├── TenantContext.java                        # 🆕 static utility, ThreadLocal + Deque 嵌套栈
│   ├── TenantConfig.java                         # 🆕 @Value + 3 nested config
│   └── TenantConfigProvider.java                 # 🆕 SPI interface
├── impl/
│   ├── tenant/                                   # 🆕 default impl
│   │   └── YamlTenantConfigProvider.java         # 🆕 @Component 默认实现
│   ├── config/
│   │   └── AgentConfigDefaults.java              # MODIFY: 加 TenantsConfig defaults()
│   ├── flow/
│   │   └── LinearTurnEngine.java                 # MODIFY: runTurn 入口加 tenant 校验 (FR-011)
│   ├── runtime/
│   │   └── AgentFactory.java                     # MODIFY: create() 启动期校验 (FR-010)
│   └── memory/                                   # (per-tenant memory isolation 演示用 ProjectClaudeMdSource 已存在,无需改)
└── runtime/
    └── AgentConfig.java                          # MODIFY: 加 TenantsConfig tenants 字段 (FR-009)
```

### Test Code

```text
lingshu-core/src/test/java/ai/lingshu/core/
├── tenant/                                       # 🆕 NEW PACKAGE
│   ├── TenantContextTest.java                    # 🆕 L1 Unit × 6 (US1 + FR-001/005)
│   ├── TenantConfigProviderTest.java             # 🆕 L1 Unit × 3 (US6 + FR-008)
│   ├── MemoryPathIsolationTest.java              # 🆕 L1 Unit × 2 (US2 + FR-003 + AC-05)
│   ├── CostBudgetIsolationTest.java              # 🆕 L1 Unit × 2 (US3 + FR-012 + AC-05)
│   ├── SandboxWhitelistIsolationTest.java        # 🆕 L1 Unit × 2 (US4 + FR-002 + AC-05)
│   ├── SessionKeyIsolationTest.java              # 🆕 L1 Unit × 2 (US5 + FR-004 + AC-05)
│   └── TenantConfigValidationTest.java           # 🆕 L1 Unit × 2 (US6 + FR-010)
└── impl/
    └── runtime/
        └── TenantIsolationIT.java                # 🆕 L5 E2E × 1 (AC-05 黑盒主路径)
```

**Total**:**6 new core + 2 modified core = 8 文件改动**(核心生产代码);**7 new test files = 18 测试用例**

---

## File-Level Changes

### New Files (6)

| File | Purpose | Lines |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/tenant/TenantContext.java` | static utility:ThreadLocal + Deque 嵌套栈 + try-finally + snapshot + runWithSnapshot | ~140 |
| `lingshu-core/src/main/java/ai/lingshu/core/tenant/TenantConfig.java` | @Value immutable + 3 nested @Value(Lombok) | ~80 |
| `lingshu-core/src/main/java/ai/lingshu/core/tenant/TenantConfigProvider.java` | SPI interface(3 方法 + @ContractVersionRef) | ~50 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tenant/YamlTenantConfigProvider.java` | @Component 默认实现,从 `AgentConfig.tenants` map 解析 | ~80 |
| (新建 tenant sub-package 其他 — 后续 Story 扩展空间) | | |
| (新建 contract / utility — 后续 Story 扩展空间) | | |

### Modified Files (4-8)

| File | Why | Lines Changed |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | FR-009 — 加 `TenantsConfig tenants` 字段 + nested `TenantsConfig` `@Value` 类 | ~30(+30 -0) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java` | Tenants 默认 `Collections.emptyMap()` | ~5(+5 -0) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | FR-011 — runTurn 入口加 tenant 上下文校验(若 config.tenants 非空) | ~10(+8 -2) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | FR-010 — create() 启动期校验 tenants 字段合法性 + 数 ≤ 1000 | ~30(+25 -5) |
| (可选) `RuntimeSandbox` 实现(若 Story #001 已留具体 class) | FR-002 + FR-013 — 持 TenantConfigProvider + 每次解析前 snapshot tenant | ~20 |
| (可选) `CostTracker` / Session 实现 | FR-012 + FR-004 | ~30 |

### Test Files (7 new)

| File | Layer | Cases |
|---|---|---|
| `TenantContextTest.java` | L1 Unit | 6 — current / set / clear / runAs basic / runAs nested / runAs cleanup-on-exception / runWithSnapshot |
| `TenantConfigProviderTest.java` | L1 Unit | 3 — resolve existing / resolve missing / listTenantIds |
| `MemoryPathIsolationTest.java` | L1 Unit | 2 — alice 写 bob 看不见 / per-tenant workingDirectory 决定 path |
| `CostBudgetIsolationTest.java` | L1 Unit | 2 — alice budget 满 bob 仍可用 / alice 超 budget 抛异常 |
| `SandboxWhitelistIsolationTest.java` | L1 Unit | 2 — alice 拒 git bob 允许 / 无 tenant 时走全局 |
| `SessionKeyIsolationTest.java` | L1 Unit | 2 — alice/bob 同 sessionId 不撞 / 无 tenant 时走全局 |
| `TenantConfigValidationTest.java` | L1 Unit | 2 — 缺字段启动失败 / 缺数 ≤ 1000 启动失败 |
| `TenantIsolationIT.java` | L5 E2E | 1 — **AC-05 黑盒主路径**(SpringBootTest + 4 维同时验证) |

### Total

- **6 new core + 4-8 modified core = 10-14 文件改动**
- **8 new test files** (其中 L5 E2E 1 个)
- **Core code**: ~340-440 net new lines(FR-001—FR-016 配套)
- **Test code**: ~700 net new lines
- **ErrorCode 新增**:0(复用 LINGS-C02 / C03 / S01 / Z01)

**Story 边界检查**(CLAUDE.md §11 #4 "≤ 5 核心文件改动"):**轻度超**(10-14 vs 5)。原因已在 spec.md "超上限原因" 段说明(4 维隔离的最小集)。Story #005 precedent 7 文件,本 Story 因 4 维 + SPI + 嵌套配置类 by nature 更多,**建议不拆分**,等 PR review 再决策。

---

## Test Strategy (L1 Unit + L2 Slice + L5 E2E)

### L1 Unit(L1-001 ~ L1-019)

**TenantContextTest(6 用例)**
- **L1-001**:`#current_initialState_isNull` — 未 set 时 current() == null
- **L1-002**:`#set_thenCurrent_returnsSetValue` — set("alice") + current() == "alice"
- **L1-003**:`#runAs_basicBlock_clearsAfterCallback` — runAs("alice", supplier) 返 alice,外层 current() == null
- **L1-004**:`#runAs_nested_innerDoesNotPolluteOuter` — runAs(alice, () -> runAs(bob, ...)) — 内层看 bob,外层看 alice
- **L1-005**:`#runAs_cleanupOnException` — runAs 内 supplier 抛 RuntimeException → 外层 current() == null(R-02 (a))
- **L1-006**:`#runWithSnapshot_crossThreadRestore` — snapshot() + 子线程 runWithSnapshot 还原

**TenantConfigProviderTest(3 用例)**
- **L1-007**:`#resolve_existingTenant_returnsConfig` — resolve("alice") 返 Optional.of(alice-config)
- **L1-008**:`#resolve_missingTenant_returnsEmpty` — resolve("ghost") 返 Optional.empty() + 日志 warn
- **L1-009**:`#listTenantIds_returnsAllConfigured` — listTenantIds() == ["alice", "bob"]

**MemoryPathIsolationTest(2 用例)**
- **L1-010**:`#perTenantWorkingDirectory_differentClaudeMd` — alice AgentConfig.sandbox.workingDirectory=./alice + bob=./bob,ProjectClaudeMdSource 各自读各的 CLAUDE.md(US2 + FR-003 + AC-05)

> **注**:原 spec US2 提到 `${tenant}` 占位符路径模板;plan 简化为"per-tenant sandbox workingDirectory 决定 path" — 因当前 MemorySource 是 read-only(从 `cfg.sandbox.workingDirectory` 读),占位符复杂度不必要;tenant-isolated config 已经天然实现 file-level isolation。FR-003 占位符逻辑**降级**为 v2 增强(可留 future scope)。

- **L1-011**:`#aliceWrite_thenBobRead_seesNoCrossData` — alice turn 走 alice MemorySource,bob 走 bob,**互不可见**

**CostBudgetIsolationTest(2 用例)**
- **L1-012**:`#aliceBudgetUsed_doesNotAffectBobBudget` — alice 消耗 USD 5 + bob 消耗 USD 3,Counter 各自分桶(US3 + FR-012)
- **L1-013**:`#aliceExceedsBudget_throwsException` — alice 跑到超额,抛 `CostBudgetExceededException`(复用既有 exception type)

**SandboxWhitelistIsolationTest(2 用例)**
- **L1-014**:`#aliceWhitelistRejectsGit_bobWhitelistAllowsGit` — alice 白名单 [ls,cat] 拒 git,bob 白名单 [ls,cat,git] 允许(US4 + FR-002 + AC-05)
- **L1-015**:`#noTenantContext_fallsBackToGlobalWhitelist` — TenantContext.current() == null 时走 yml 全局 sandbox.whitelist + 日志 warn

**SessionKeyIsolationTest(2 用例)**
- **L1-016**:`#aliceAndBobSameSessionId_dontCollide` — alice put("sess-1", stateA),bob put("sess-1", stateB),alice get → stateA,bob get → stateB(US5 + FR-004)
- **L1-017**:`#noTenantContext_sessionIdIsGlobal` — TenantContext.current() == null → put/get 用 sessionId 本身

**TenantConfigValidationTest(2 用例)**
- **L1-018**:`#missingCostField_agentFactoryCreate_failsFast` — yml 缺 `cost.session-budget-usd` → AgentFactory.create() 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED` + 字段路径
- **L1-019**:`#tooManyTenants_agentFactoryCreate_failsFast` — tenants map 1001 个 → 启动失败

### L5 E2E(E2E-001,AC-05 黑盒主路径)

- **E2E-001**:`TenantIsolationIT#aliceAndBobIsolated_across4Dims` — 启动 SpringBoot,yml 配 `agent.tenants.{alice,bob}`,完整跑 2 个 turn:
  1. Alice turn → `TenantContext.runAs("alice", () -> agent.runBlocking(...))` → memory 写入 alice dir / cost 计入 alice / sandbox 走 alice whitelist / session key 加 alice 前缀
  2. Bob turn → `TenantContext.runAs("bob", () -> agent.runBlocking(...))` → memory 写入 bob dir / cost 计入 bob / sandbox 走 bob whitelist / session key 加 bob 前缀
  3. **断言**:
     - alice dir 包含 alice-secret,**不**包含 bob-secret
     - bob dir 包含 bob-secret,**不**包含 alice-secret
     - alice cost counter == alice 用量,bob cost counter == bob 用量(互不影响)
     - alice sandbox 跑 `git` 抛 PermissionDenied(alice whitelist 无 git)
     - bob sandbox 跑 `git` 成功(bob whitelist 有 git)
     - alice session key != bob session key(虽然 sessionId 同)

**Test 总数**:19 个(L1 × 19 + L5 × 1)

---

## Implementation Order (10 steps, sequential due to dependencies)

| Step | What | Depends on | ~time |
|---|---|---|---|
| **0** | 检查 `mvn -v` + JDK 17(实际跑) + 跑 Story #005 全测试 baseline | — | 1 min |
| **1** | 创建分支 `story-006-multi-tenant` from main:✅ 已完成 | step 0 | 0 min |
| **2** | 写 spec.md / plan.md / tasks.md / quickstart.md / contracts / checklists(本目录)| step 1 | 5 min |
| **3** | 新建 `tenant` 包 + `TenantContext`(FR-001/005/006/015) | — | 10 min |
| **4** | 新建 `TenantConfig`(FR-007)+ `AgentConfig` 加 `TenantsConfig tenants` 字段(FR-009)| step 3 | 10 min |
| **5** | 新建 `TenantConfigProvider` SPI(FR-008)+ `YamlTenantConfigProvider` 实现 | step 4 | 10 min |
| **6** | 改 `LinearTurnEngine.runTurn` 加 tenant 上下文校验(FR-011) | step 5 | 5 min |
| **7** | 改 `AgentFactory.create()` 启动期校验 tenants(FR-010/016)| step 5 | 10 min |
| **8** | 改造 `RuntimeSandbox`(FR-002/013)+ `CostTracker`(FR-012)+ `InMemorySessionStore`(FR-004)| step 5 | 20 min |
| **9** | 写 L1 测试 19 个(L1-001—L1-019)+ L5 E2E 1 个(E2E-001 AC-05)| step 8 | 25 min |
| **10** | 跑 `mvn test` 全绿 + `mvn dependency:tree` 自查 + 贴 PR body | step 9 | 10 min |
| **11** | commit + push + open PR + README/docs 同步 | step 10 | 10 min |

**Total**:~116 min(~2h 实施 + 20 min PR 收尾)

---

## Key Design Decisions

### D-01:`TenantContext` 用 `Deque<String>` 嵌套栈 而非单值 `ThreadLocal<String>`

**决策**:`ThreadLocal<Deque<String>>` 而非 `ThreadLocal<String>`。理由:
- US1 S4 嵌套 `runAs(alice → bob)` 需要栈式保存 —— 单值会被内层覆盖
- `Deque<ArrayDeque>` 性能优于 `Stack`(无 synchronized 开销,JDK 8 教科书)
- try-finally 时 `deque.pollLast()` 弹栈,与 set 严格对称
- NFR-005 无锁,无并发场景,ArrayDeque 完美适配

**Trade-off**:嵌套 ≥ 100 层会 ArrayDeque 默认扩容到 16 × 2 = O(1),实际业务不会嵌套那么深

### D-02:放弃 `InheritableThreadLocal`,改 `snapshot()` + `runWithSnapshot()`

**决策**:主动放弃 JDK 内置 `InheritableThreadLocal`,采用显式 `snapshot()` 字符串 + `runWithSnapshot(snap, ...)`。理由:
- R-02 (c) 显式约束:InheritableThreadLocal 子线程启动时自动继承 → 容易遗忘「父线程已 set 的 tenantId 蔓延到所有子线程」 → 隐蔽的跨线程污染
- 显式 snapshot 让跨线程传递**显式可见** — 代码评审一眼能看清 tenantId 流向
- 业务方必须主动 `runWithSnapshot` 才能还原 → 漏写就降级为 "no tenant"(安全降级)
- §17 R-02 mitigation (c) 文档明确支持此决策

**Trade-off**:业务方跨线程场景必须多写 1 行 `runWithSnapshot`,但提升可观测性 + 防漏写

### D-03:`AgentConfig` 加 `TenantsConfig` 字段而非 `Map<String, TenantConfig>`

**决策**:`AgentConfig.tenants` 是 `TenantsConfig` 类型(Lombok `@Value`),内部 `Map<String, TenantConfig> map` + `boolean enabled` + 校验方法。理由:
- `TenantsConfig.enabled` 显式标志位,业务方可通过 `cfg.getTenants().isEnabled()` 一行判断多租户模式开关(比 `map.isEmpty()` 语义清晰)
- `TenantsConfig.validate()` 内聚所有 tenant 字段校验逻辑,AgentFactory.create() 只需调一次
- 后续 Story(如 Story #007 热更 / Story #014 多 session store 后端)可在 `TenantsConfig` 内加字段而不污染 `AgentConfig` 顶层

**Trade-off**:`TenantsConfig` 多一层 wrapper,深度 +1,但隔离更清晰

### D-04:`RuntimeSandbox` 持 `TenantConfigProvider` 而非 `AgentConfig`

**决策**:`RuntimeSandbox` 实例构造时注入 `TenantConfigProvider`(而非 `AgentConfig`)。理由:
- `RuntimeSandbox` 是 per-tenant(US4 + FR-013),每次 tenant 切换 sandbox 实例应重建,但 sandbox 重建成本高(可能持有 chroot FileSystem)
- 折中方案:**单 sandbox 实例** + **每次调用前 snapshot tenant + 重新解析 whitelist** — `TenantConfigProvider` 是 SPI 入口(可换 DB/Vault),sandbox 不直接依赖 `AgentConfig.tenants` 内存结构
- 与 Story #003 SPI 多 Provider 模式对齐(注入 Provider 而非具体 config 数据)

**Trade-off**:每次调用前解析 whitelist 有 O(1) Map 查找开销(NFR-007),纳秒级可接受

### D-05:`InMemorySessionStore` 加 tenantId 前缀 = `tenantId + ":" + sessionId`

**决策**:key 拼接格式 = `tenantId + ":" + sessionId`(冒号分隔)。理由:
- Redis 风格 namespace,运维习惯(R-13 mitigation (a) Redis `agent:session:{tenantId}:{sessionId}` 对齐)
- 冒号作为分隔符 + tenantId 校验 `[a-zA-Z0-9_-]{1,64}` 防撞号(Edge Case "tenantId 含特殊字符")
- 反向解析:`String[] parts = key.split(":", 2)`,tenantId = parts[0],sessionId = parts[1] — O(1) 拆分,无 escape 复杂度

**Trade-off**:sessionId 不能再含冒号(否则 split 错位)— sessionId 校验也加 `[a-zA-Z0-9_-]{1,64}` 防碰撞

### D-06:测试 `MemoryPathIsolationTest` 不实际写 memory,只读 CLAUDE.md 隔离演示

**决策**:`MemoryPathIsolationTest` 通过 per-tenant `Sandbox.workingDirectory` + `ProjectClaudeMdSource.load(ctx)` 验证。理由:
- 当前 `MemorySource` 设计是 **read-only**(返 string 给 PromptBuilder),无 `write()` 方法
- AC-05 "memory 文件零交叉" 指"Alice turn 读到 alice CLAUDE.md,Bob turn 读到 bob CLAUDE.md" — read path 上隔离
- 写 memory(append to file) 留 Story #014 SessionStore 集成后再说(届时 SessionStore 4 后端 file backend + tenantId 前缀统一处理)

**Trade-off**:US2 spec 写"写 memory",实际只测 read path — spec 需 minor refine(在 plan 这一层注明,后续 spec 可改)

### D-07:0 新增 ErrorCode — 复用 `LINGS-C02 / C03 / S01 / Z01`

**决策**:tenant 配置失败 → 抛 `LingsConfigException("C02", ...)`;tenant 不存在 → `LingsSlotException("S01", ...)`;tenant 内存 map null → `LingsInternalException("Z01", ...)`。理由:
- NFR-004 显式约束 — Story #006 不引入新 ErrorCode
- 配置错位 = Config 域(C02/C03);找不到 = Slot 域(S01);内部 invariant 违例 = Z01
- 跨域复用体现「tenant 是横切关注点」而非独立域

**Trade-off**:异常 message 必须带 `tenant=<id>` 前缀便于排查,plan "Contracts" 节明确约定

### D-08:`LinearTurnEngine.runTurn` tenant 上下文校验**仅在 tenants 启用时**触发

**决策**:`if (config.getTenants().isEnabled()) checkTenantContext(...)` 而非无脑 always check。理由:
- US6 S3 单租户模式(yml **不**配 tenants)启动成功,TenantConfigProvider 返 empty,所有 turn 走全局配置 — 与 Story #001—#005 完全兼容(US6 NFR-009 显式约束)
- 启用多租户后才校验 — 防误报「没配 tenants 也要求 runAs」
- 与 §17 R-02 不冲突 — R-02 是「泄漏」缓解,单租户模式无多租户场景无需 try-finally

**Trade-off**:`isEnabled()` 多一次方法调用,但纳秒级,可忽略

---

## Risk & Mitigation

| Risk | Probability × Impact | Mitigation |
|---|---|---|
| **R-01**:嵌套 runAs 弹栈顺序错(US1 S4 fail)| 2×3=6 | D-01 Deque 嵌套栈 + unit test L1-004 覆盖 + D-01 code review checklist |
| **R-02**:InheritableThreadLocal 误用导致跨线程泄漏(R-02 (c) 失效)| 1×3=3 | D-02 主动放弃 + FR-015 Javadoc 显式约束 + AgentFactory.create() 配置校验拒绝含 `inherit` 关键字(若用反射 hack) |
| **R-03**:yml 配 tenants 但业务代码忘 runAs → turn 启动失败抛 LINGS-C02(Edge Case)| 2×2=4 | FR-011 LinearTurnEngine 入口校验 + 日志明确 + TenantConfigValidationTest 覆盖「漏 runAs 场景」 |
| **R-04**:超大 tenants map(DoS)| 1×3=3 | FR-010 数 ≤ 1000 启动期 fail-fast + L1-019 unit test |
| **R-05**:tenantId 含特殊字符导致 key 撞号或文件路径遍历| 2×3=6 | FR-001 启动期正则校验 `[a-zA-Z0-9_-]{1,64}` + D-05 session key 冒号分隔对齐 + unit test L1-007 覆盖 |
| **R-06**:CostTracker 误把全局 budget 累加到 alice(US3 S3 fallback 漏)| 2×2=4 | L1-013 unit test 覆盖「alice 超 budget + bob 不受影响」+ D-04 sandbox 同样的 Provider 注入模式 |
| **R-07**:InMemorySessionStore 改造后 SessionStore SPI 4 后端契约破坏(Story #014)| 2×3=6 | FR-004 仅改 `keyBuild(sessionId)` 内部行为,`save(Checkpoint)` / `load(String)` 接口签名不变 + 不动 SessionStore 接口 |
| **R-08**:依赖污染(意外引 `ThreadLocal.withInitial` 之类 JDK 9+ API)| 1×3=3 | NFR-003 强制 0 新增 + R-13 mitigation (d) `dependency:tree` 自查 + code review 拒绝 `ThreadLocal.withInitial` / `var` / `List.of` |
| **R-09**:`RuntimeSandbox` 改造引发 Story #001 zero-config-bootstrap 回归(AC-01 fail)| 2×3=6 | (a) `RuntimeSandbox` 内部 snapshot tenant + 兜底全局 — 不改单租户路径;(b) Story #001—#005 全部测试用例仍 green(SC-008);(c) `AgentFactory.create()` 启动期校验向后兼容:tenants 为空时 **不**调用 TenantConfigProvider |

---

## Critical Invariants (do NOT change in this Story)

- `Tool` / `ToolExecutor` / `LlmProvider` / `PermissionPolicy` / `PromptBuilder` / `Compactor` / `A2aTransport` 接口:**不**改 —— 本 Story 不触 7 个 Slot 接口签名
- `MemorySource` 接口:不**改** —— `load(TurnContext)` 签名不变,本 Story 仅靠 per-tenant AgentConfig 隔离 workingDirectory
- `SessionStore` 接口:`save(Checkpoint)` / `load(String)` 签名不**改** —— FR-004 仅改 key build 内部逻辑,接口契约不变(Story #014 集成时不被破坏)
- `RuntimeSandbox` 接口:`fs()` / `http()` / `process()` 签名不**改** —— FR-002/013 仅改 process.run() 内部 whitelist 解析逻辑
- `CancellationToken` / `AgentFactory.broadcastCancel` / `DefaultAgent.buildContext`(Story #005 已固化):不**改**
- `LinearTurnEngine` 5-step ReAct 序列顺序不变,只新增"runTurn 入口 tenant 上下文校验"
- `AgentConfig` 27+ 字段签名向后兼容:新加 `TenantsConfig tenants` 字段,既有字段**不**改
- `LINGS-*` ErrorCode 编码:0 新增,复用 C02 / C03 / S01 / Z01

---

## Future Scope (Out of Story #006)

- **HTTP WebFilter X-Tenant-Id header 入口**:Story #006 仅 programmatic API(`TenantContext.runAs(...)`);HTTP 模块未来集成时再补 WebFilter(lingshu 仓暂无 HTTP 模块)
- **`${tenant}` 路径模板占位符**(原 spec FR-003 提及):简化为"per-tenant sandbox workingDirectory 决定 path";占位符引擎(Mustache)留 v2 增强
- **Database / Vault TenantConfigProvider 替代实现**:本 Story 仅 `YamlTenantConfigProvider` 默认;DB / Vault 样板留 §5.5 + Story 后续(类似 Story #002 的 4 MemorySource Provider 模式)
- **MemorySource 写 API**(append to file):当前 MemorySource 是 read-only;写 memory 留 Story #014 SessionStore 集成后
- **Session 二级分组**(按 user_id 而非 tenantId):Story #014 集成时细化
- **TenantContext 与 Reactor Context 桥接**(响应式编程场景):dsh §14.9 提及但本 Story 仅 synchronous;Reactor `Mono.subscriberContext()` 桥接留 Story 后续
- **TenantContext 调用栈追踪**(debug mode):可在 turn entry log 打印 tenantId stack;不影响正确性,留 v2

---

## Phase 0: Research — Findings

> **详见 [`research.md`](./research.md)** — 本节摘要

| Unknown | Decision | Rationale |
|---|---|---|
| ThreadLocal vs InheritableThreadLocal vs snapshot+runWithSnapshot | snapshot + runWithSnapshot(D-02) | R-02 (c) 主动放弃 InheritableThreadLocal 防隐蔽污染 |
| 嵌套 runAs 用单值 ThreadLocal 还是 Deque 栈 | Deque 嵌套栈(D-01)| US1 S4 嵌套需求 + 性能 O(1) |
| TenantConfig 字段放 AgentConfig 顶层 vs 嵌套 TenantsConfig | 嵌套 TenantsConfig(D-03)| enabled 标志位 + 校验内聚 + 后续 Story 扩展空间 |
| RuntimeSandbox per-tenant 重建 vs 单实例 + 每次解析 | 单实例 + 每次解析(D-04)| 重建成本高 + Provider 注入对齐 SPI |
| Session key 前缀用什么分隔符 | 冒号(Redis 风格, D-05)| 运维习惯 + tenantId 校验防撞号 |
| 0 新增 ErrorCode 是否可行 | 复用 C02/C03/S01/Z01(D-07)| tenant 是横切关注点而非独立域 |
| tenants map 数上限多少 | ≤ 1000(FR-010)| 防止 DoS + 启动期校验 |
| Memory path 隔离用占位符还是 per-tenant workingDirectory | per-tenant workingDirectory(D-06)| 当前 MemorySource 是 read-only,占位符复杂度不必要 |

**所有 NEEDS CLARIFICATION 已解决** → 进入 Phase 1。

---

## Phase 1: Design & Contracts

> **详见 [`data-model.md`](./data-model.md) + [`contracts/tenant-context.md`](./contracts/tenant-context.md) + [`quickstart.md`](./quickstart.md)** — 本节摘要

### Data Model 摘要

- `TenantContext`(static utility):current / set / clear / snapshot / runAs / runWithSnapshot
- `TenantConfig`(@Value):tenantId / memory / sandbox / cost
- `TenantConfig.Memory`(@Value):dir(Path)
- `TenantConfig.Sandbox`(@Value):commandWhitelist(List<String>)
- `TenantConfig.Cost`(@Value):sessionBudgetMicros(long)
- `TenantConfigProvider`(SPI):name / priority / resolve / listTenantIds
- `YamlTenantConfigProvider`(@Component):从 AgentConfig.tenants map 解析
- `TenantsConfig`(@Value,nested in AgentConfig):enabled(boolean) + map(Map<String, TenantConfig>) + validate()

### Contracts 摘要

- `TenantContext` 契约(11 个 static 方法签名 + 4 个不变量)— 见 contracts/tenant-context.md
- `TenantConfigProvider` 契约(SPI 4 方法 + @ContractVersionRef 1.0.0)
- `YamlTenantConfigProvider` 契约(默认实现)

### Quickstart 摘要

- AC-05 黑盒验证脚本(SpringBootTest + yml 配 2 tenants + 跑 2 turn + 断言 4 维)
- 单租户 fallback 验证脚本(空 yml + 跑 1 turn + 走全局配置)

**所有 Phase 1 artifacts 已生成** → Constitution Check **复评** ✅ 全部通过。
