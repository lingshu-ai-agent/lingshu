# Research: Story #006 multi-tenant

**Branch**: `story-006-multi-tenant` | **Date**: 2026-09-21
**Purpose**: Resolve all NEEDS CLARIFICATION from spec.md + design choices for multi-tenant isolation

---

## R-01: ThreadLocal vs InheritableThreadLocal vs snapshot+runWithSnapshot

**Question**: 跨线程 tenant 上下文如何传递?

**Decision**: **`snapshot()` + `runWithSnapshot(snap, ...)` 显式传递**,**主动放弃** `InheritableThreadLocal`

**Rationale**:
- `ThreadLocal`:子线程不继承 — 子线程拿不到 tenant,安全但需要显式传递
- `InheritableThreadLocal`:子线程**自动继承**父线程 ThreadLocal — 看似方便但**隐蔽污染风险**:
  - 父线程 `set("alice")` → 子线程 Runnable 自动看见 "alice"
  - 业务方**无意识**地把 tenantId 蔓延到所有子线程
  - `ThreadPoolExecutor` 任务复用 worker 线程 → worker 线程上次的 tenantId 残留给下次任务 → **跨任务污染**
- `snapshot() + runWithSnapshot`:子线程必须**主动**调 `runWithSnapshot(snap, ...)` 才能拿到 tenant → 漏写就降级为 "no tenant"(安全降级)+ 代码评审可见

**Alternatives considered**:
- `TransmittableThreadLocal`(Alibaba 库):功能强大但**违反** §2 R-13 mitigation (d) 0 新增依赖
- `Reactor Context`(响应式场景):lingShu 当前用 Reactive Streams 但 FlowEngine 是同步,Reactor Context 桥接复杂度高,留 v2

**Sources**:
- dsh §17 R-02 mitigation (c):「跨线程传递用 InheritableThreadLocal + clean」 — **本决策改进此缓解**:用 snapshot + 主动调用更安全
- Dsh §14.9 「入口 HTTP WebFilter ... try/finally 清理」— 明确 try-finally 语义为本项目基线

---

## R-02: 嵌套 runAs 用单值 ThreadLocal 还是 Deque 栈

**Question**: `runAs(alice → bob)` 嵌套场景,内层 runAs 是否污染外层?

**Decision**: **`Deque<String>` 嵌套栈**

**Rationale**:
- US1 S4 明确要求「嵌套不污染」
- 单值 `ThreadLocal<String>`:内层 `set("bob")` → 外层 set("alice") 被覆盖,内层结束后 bob 残留 → 外层看不见 alice
- `Deque<ArrayDeque>`:`set` 时 `deque.addLast(value)`;`clear` 时 `deque.pollLast()`;`current` 返 `deque.peekLast()`
- 嵌套深度 100 层 ArrayDeque 默认扩容 16 → 32 → ... O(1) amortized
- 性能:`runAs(null, noop)` 仅 1 次 `addLast` + 1 次 `pollLast` + 2 次 `ThreadLocal.set/get`,纳秒级(NFR-006)

**Alternatives considered**:
- `Stack<String>`(JDK 内置):**synchronized** 开销大,NFR-006 不达标
- 嵌套计数器 + 数组:复杂度高,无明显优势

**Sources**:
- JDK 8 `ArrayDeque` 文档:「This class is likely to be faster than Stack when used as a stack」

---

## R-03: TenantConfig 字段放 AgentConfig 顶层 vs 嵌套 TenantsConfig

**Question**: `AgentConfig.tenants` 用 `Map<String, TenantConfig>` 还是嵌套 `TenantsConfig`?

**Decision**: **嵌套 `TenantsConfig`(Lombok `@Value`)+ 内部 `Map<String, TenantConfig>` + `enabled` flag**

**Rationale**:
- `TenantsConfig.enabled` 显式标志位:`cfg.getTenants().isEnabled()` 一行判断多租户模式开关,语义清晰
- `TenantsConfig.validate()` 内聚所有 tenant 字段校验逻辑,AgentFactory.create() 只需调一次,易于扩展
- Story #007 热更时,`TenantsConfig` 可独立加 `version` / `revision` 字段,不影响 `AgentConfig` 顶层
- Story #014 SessionStore 4 后端集成时,`TenantsConfig` 可加 `sessionStorePerTenant` 标志

**Alternatives considered**:
- `AgentConfig.tenants: Map<String, TenantConfig>` 直接放顶层:AgentConfig 字段数 +1,且"是否启用多租户"判断用 `map.isEmpty()` 语义模糊(空 map 是"未配置"还是"故意配空 tenants"?)
- `AgentConfig.multiTenantEnabled: boolean` + `Map`:双字段冗余,容易状态不一致

**Sources**:
- dsh §14.9 「位置:`TenantContext`(`ThreadLocal` + Reactor `Context`)贯穿」— 没有强制结构,plan 选择清晰可扩展方案
- Story #005 precedent:`AgentConfig` 已用 Lombok `@Value` + 嵌套配置类(`Identity` / `Instructions` / `Memory` / `ClaudeMd`),本 Story 沿用此模式

---

## R-04: RuntimeSandbox per-tenant 重建 vs 单实例 + 每次解析

**Question**: 每租户独立 `RuntimeSandbox` 实例 vs 单实例 + 每次调用前 snapshot tenant?

**Decision**: **单 sandbox 实例 + 每次调用前 snapshot tenant + 重新解析 whitelist**

**Rationale**:
- `RuntimeSandbox` 可能持有 chroot FileSystem(Story #001 stub 提及) — 重建成本高(创建 FileSystem 涉及 OS 系统调用)
- `TenantConfigProvider` 是 SPI 入口(可换 DB/Vault 来源),sandbox 不直接依赖 `AgentConfig.tenants` 内存结构 → 与 Story #003 SPI 多 Provider 模式对齐
- 每次调用前 `TenantContext.current()` 拿 tenantId + `provider.resolve(tenantId).sandbox().commandWhitelist()` 是 O(1) Map 查找(NFR-007),纳秒级
- 防「turn 内半路切租户」(R-02 加强):每次调用前 snapshot,**不**缓存解析结果(若 turn 内 set tenant 改了,sandbox 立即跟随)

**Alternatives considered**:
- 每租户独立 sandbox 实例:**违反** Story #001 zero-config-bootstrap 「空 yml 必须能启动」(单租户模式无 sandbox 实例化开销)
- sandbox 内部维护 `Map<tenantId, Whitelist>` cache:增加内存 + 失效逻辑复杂,不如直接每次解析

**Sources**:
- dsh §14.9 「Sandbox:每租户独立 `RuntimeSandbox`(避免 chroot 路径污染)」 — 设计意图是避免 chroot 路径污染,**不等于**必须每租户独立 RuntimeSandbox 实例
- Story #003 §5.5 「多 Provider 模式」:注入 Provider 而非具体 config 数据 — D-04 遵循此模式

---

## R-05: Session key 前缀用什么分隔符

**Question**: `InMemorySessionStore` key 加 tenantId 前缀,用什么分隔符?

**Decision**: **冒号 `:`(Redis 风格)**

**Rationale**:
- 运维习惯:Redis key namespace 广泛用冒号(`agent:session:tenant:sessionId`),运维 dashboard 直观
- `tenantId` 校验 `[a-zA-Z0-9_-]{1,64}` 防含冒号 → 反向解析 `key.split(":", 2)` 安全
- `sessionId` 校验同样 `[a-zA-Z0-9_-]{1,64}` 防撞号
- 反向解析 O(1) `String.split(":", 2)`,无 escape 复杂度

**Alternatives considered**:
- 下划线 `_`:`agent_session_tenant_sessionId` 难区分 tenant / sessionId 边界(多 tenant_id 含 _ 时撞号)
- 斜杠 `/`:`agent/session/tenant/sessionId` 文件系统路径分隔符,易撞目录名
- 双下划线 `__`:自定义,运维不习惯

**Sources**:
- dsh §14.9 「Redis:`agent:session:{tenantId}:{sessionId}`」 — **明确**冒号分隔符
- Story #014 SessionStore Redis backend 集成时直接对齐

---

## R-06: 0 新增 ErrorCode 是否可行

**Question**: tenant 配置失败 / tenant 不存在 / tenant 内部 invariant 违例,如何归类?

**Decision**: **复用 `LINGS-C02 / C03 / S01 / Z01`,0 新增**

**Rationale**:
- tenant 配置缺失字段 → Config 域(`LINGS-C02 CONFIG_VALIDATION_FAILED`)— 配置错位
- tenant 配置类型不符 → Config 域(`LINGS-C03 CONFIG_TYPE_MISMATCH`)
- tenant 不存在 / slot 找不到 → Slot 域(`LINGS-S01 SLOT_NOT_FOUND`)
- tenant 内存 map null / invariant 违例 → 其他域(`LINGS-Z01 INTERNAL_PANIC`)
- tenant 是横切关注点而非独立域 — 跨域复用体现此设计意图
- NFR-004 显式约束「0 新增 ErrorCode」

**Alternatives considered**:
- 新增 `LINGS-Tnn`(Tenant 域):§15 Error Catalog 8 域(C/S/L/T/X/R/A/Z)没 Tenant 域,新增需扩 §15 + 加 R-XX,超出 Story 范围

**Sources**:
- dsh §15 「`LINGS-<域><编号>` 编码约定」 — 域字母 `C/S/L/T/X/R/A/Z`(C=Config, S=Slot, Z=其他)
- dsh §15 LINGS-C02 / LINGS-C03 / LINGS-S01 / LINGS-Z01 已存在定义

---

## R-07: tenants map 数上限多少

**Question**: 启动期 `TenantsConfig` 校验,`map.size()` 上限多少合理?

**Decision**: **≤ 1000**

**Rationale**:
- 1000 个 tenant 在典型 SaaS 场景(企业内部多 BU / 中型 SaaS)已属大型部署
- 1000 + `TenantConfig.validate()` 是 O(N) 启动期校验,< 10ms
- > 1000 多为配置错乱(写脚本批量生成) → fail-fast 提示用户检查 yml
- 内存:`TenantConfig` 4 个字段,每 tenant 约 200 bytes,1000 tenant = 200KB,可忽略

**Alternatives considered**:
- 无上限:DoS 风险(恶意 yml 配 100w tenant 内存爆)
- 100:太严,中型 SaaS 不够用
- 10000:太宽,失去 fail-fast 意义

**Sources**:
- dsh §14.15.7 「测试策略 7 层金字塔」 — 边界值测试应在合理量级
- Story #005 precedent:无显式上限,但 `BROADCAST_REGISTRY` 实际受 in-flight turn 数 ≤ 32 限制

---

## R-08: Memory path 隔离用占位符还是 per-tenant workingDirectory

**Question**: US2 提到的 `${tenant}` 占位符路径模板是否必要?

**Decision**: **per-tenant `Sandbox.workingDirectory` 决定 path**,**简化**占位符为 future scope

**Rationale**:
- 当前 `MemorySource` 是 **read-only**(返 string 给 PromptBuilder),无 `write()` 方法
- AC-05 "memory 文件零交叉" 指 read path 上隔离 — Alice turn 读到 alice CLAUDE.md,Bob turn 读到 bob CLAUDE.md
- per-tenant `Sandbox.workingDirectory` 已经天然实现 file-level isolation:
  - alice AgentConfig.sandbox.workingDirectory = `./alice/`
  - bob AgentConfig.sandbox.workingDirectory = `./bob/`
  - `ProjectClaudeMdSource.load(ctx)` 读 `cfg.sandbox.workingDirectory/CLAUDE.md` → 各读各的
- 占位符引擎(Mustache `${tenant}` 替换)增加复杂度,且当前 MemorySource 是 read-only 不写文件 — 无写入路径占位符需求

**Alternatives considered**:
- `${tenant}` 占位符:`SimpleTemplate.render("./memory/${tenant}/CLAUDE.md", tenantId)` — 需引入模板引擎或手写 string replacement,Story #006 范围外
- tenant-prefixed 路径自动拼接:`./.agent/memory/{tenantId}/CLAUDE.md` 系统自动加 prefix — 侵入性强,业务方失去路径控制权

**Sources**:
- 当前 MemorySource 实现:`ProjectClaudeMdSource` / `ProjectTreeMemorySource` / `IdentityMemorySource` / `UserClaudeMdSource` 4 个实现,**全部 read-only**
- dsh §4.5 「MemorySource: Static / dynamic memory source feeding `[PROJECT MEMORY]`」— 明确是 read-only prompt 上下文

---

## Summary

8 个 research 决策,**全部 resolved**(0 个 NEEDS CLARIFICATION 遗留):

| # | Decision | Status |
|---|---|---|
| R-01 | snapshot + runWithSnapshot(主动放弃 InheritableThreadLocal)| ✅ resolved |
| R-02 | Deque 嵌套栈 | ✅ resolved |
| R-03 | TenantsConfig 嵌套 AgentConfig | ✅ resolved |
| R-04 | 单 sandbox + 每次解析 | ✅ resolved |
| R-05 | 冒号分隔 Redis 风格 | ✅ resolved |
| R-06 | 0 新增 ErrorCode,复用 C/S/Z 域 | ✅ resolved |
| R-07 | tenants map ≤ 1000 | ✅ resolved |
| R-08 | per-tenant workingDirectory,占位符降级 future scope | ✅ resolved |

**进入 Phase 1** — 生成 data-model.md + contracts/ + quickstart.md。
