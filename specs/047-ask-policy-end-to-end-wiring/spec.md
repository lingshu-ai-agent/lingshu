# Story #047 — ask-policy end-to-end wiring(`permission-policy: ask` 真接通 demo-product)

**Story #**: #047
**Slug**: `ask-policy-end-to-end-wiring`
**dsh §**: §6.1 LinearTurnEngine + §4.7 PermissionPolicy ask-mode + ChatController yml bypass
**类型**: Bug-fix(close two wiring gaps left by Story #029 / #030 / #031 / #041 / #042)
**预算**: 3 核心文件改动 / 0 ErrorCode / 0 新 Maven 依赖
**前置依赖**: Story #029(strict policy 落地)+ Story #030(ask-mode 落地)+ Story #031(pattern 落地)+ Story #041(ApprovalGate SPI 抽离)+ Story #042(frontend button 落地)+ Story #037(3 Provider 多 Provider 模式对齐)
**挡板**: demo-product `application.yml` 配 `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]`,实际跑 `bash_safe` 不发出 `AgentEvent.ApprovalRequired`,**ask-mode 配置看似通了但 0 触发** = 配置 silent-ignored

---

## WHY(根因 + 业务价值)

### 问题

demo-product 用户按 README 配 `agent.permission-policy: ask` + `agent.tools.ask-list: [bash_safe]`,实测 `bash_safe` 被调了多次,**从未触发** `AgentEvent.ApprovalRequired` 事件,前端 Allow/Deny 按钮从来不会出现。ask-mode privilege escalation 防护是 Story #030 落地了的(后端 ApprovalRegistry + ChatController `POST /api/approvals` 端点都有),但**接线到 demo-product 这一条路没全通**。

### 根因(2 处 bug)

**Bug A — `AgentFactory.create()` L280 拿错 Slot 字段**(framework bug,影响所有 caller)

```java
// lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java:280
PermissionPolicy permissionPolicy = policyRouter.resolve(
    config.getSandbox().getPolicy(),   // ← BUG: Slot 3 sub-slot(默认 "default")
    config);
```

`getSandbox().getPolicy()` 是 Slot 3 沙箱策略子字段,默认值是 `"default"`,对应 `AllowAllPermissionPolicy` —— **永远** 不会解析出 `AskUserPermissionPolicy`(yml `permission-policy: ask` 配了也无效)。**正解**应读 Slot 4 顶层 `config.getPermissionPolicy()`,默认值也是 `"default"`(Story #029 加的字段)。

对照同文件 L296(`sessionStoreRouter.resolve(config.getSessionStore(), config)`)写法,可见 L280 是 typo-level 的「看错字段」,后跟者没注意到。

**Bug B — `ChatController.createSession()` hand-roll 28-arg `AgentConfig` 构造器,完全绕过 yml 加载**(demo-product 端 bug)

```java
// lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/ChatController.java:120-160
String sessionStore = env.getProperty("agent.session-store", base.getSessionStore());
String compactorName = env.getProperty("agent.compactor.name", base.getCompactor());
AgentConfig cfg = new AgentConfig(
    base.getFlowEngine(),
    base.getLlm(),
    base.getPrompt(),
    base.getToolExecutor(),
    base.getSandbox(),
    compactorName,                  // ← 只覆盖 Slot 6
    sessionStore,                   // ← 只覆盖 Slot 5
    base.getDelegate(),
    base.getMcp(),
    base.getSkills(),
    ...(继续 18 个 base 字段));
return agentFactory.create(cfg);
```

即便 Bug A 修了,ChatController 也只覆盖 2 个 Slot 名(5 + 6),`permission-policy: ask` 永远读不到。`DemoProductApplication.agentConfig(Environment)` 这个 @Bean **已经存在** 且 read 了 `permission-policy` / `tools.allow-list` / `tools.deny-list` 等,但:

1. ChatController **不**用这个 @Bean(注 L84-87 明确说「does **not** use this bean — each session builds its own config」)
2. 即使 ChatController 改去用这个 @Bean,`readTools()` L382 硬编码 `Collections.<String>emptyList()` 给 askList 字段(Story #030 加的),yml `tools.ask-list: [bash_safe]` 配了也读不到

**业务价值**

- **demo-product ask-mode 端到端真接通** — 配 `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]` → Agent 调这些 Tool → 后端 emit `AgentEvent.ApprovalRequired` → 前端看到 Allow/Deny 按钮 → 用户点 allow → ToolExecutor 继续执行(Story #042 闭环)。当前实测:配置 silent-ignored,ask-mode 防护 = 0 触发 = **R-04 privilege escalation 风险实际暴露**
- **Story #030 + #031 + #041 + #042 后端真接通链路收口** — 后端 4 件套全落了,就差 demo-product 这一段 wiring 没接上
- **`AskUserPermissionPolicyProvider` 实际被路由到** — 当前 `PermissionPolicyRouter` 内部 `byName` Map 里有这个 Provider,但因为 Bug A 永远解析 `config.getSandbox().getPolicy() = "default"`,`AskUserPermissionPolicy` 永远是 0 调用
- **doraemon-style parity** — 配 yml 一个 key 就能让 ask-mode 工作,符合 "零配置原则" 的扩展方向(business 配 1 行,框架接住)

### 为什么不是 RFC / 宪章改 / 跨 Story 改 constitution

- **0 新接口契约** — `PermissionPolicy` / `Decision` / `AgentEvent.ApprovalRequired` / `ApprovalRegistry` 全部 0 改动
- **0 新 ErrorCode** — 复用 Story #030 `LINGS-P02` (审批超时) + `LINGS-P01` (allow-list 不匹配)
- **0 新 Maven 依赖** — 纯 JDK + Lombok + Spring 已锁
- **≤ 5 核心文件改动** — 2 核心 prod 文件(AgentFactory L280 / ChatController) + 1 demo-product helper(DemoProductApplication.readTools()) = 3 改动
- **0 改 AgentConfig schema** — `getPermissionPolicy()` 顶层字段已存在(Story #029),`tools.ask-list` 字段已存在(Story #030)
- **0 改 AskUserPermissionPolicy / AskUserPermissionPolicyProvider** — 已经有 4-path 决策逻辑,只需能被路由到

---

## WHO(参与者 + 边界)

| 角色 | 改动范围 | 行为 |
|---|---|---|
| **AgentFactory.create() L280** | **modify**(1-line fix) | `config.getSandbox().getPolicy()` → `config.getPermissionPolicy()` |
| **DemoProductApplication.readTools() L382** | **modify**(1-line fix) | 硬编码 `Collections.emptyList()` → `readToolsList(env, "agent.tools.ask-list", fallback.getAskList())` |
| **ChatController.createSession() L119-163** | **modify**(整段重写) | 不再手 new `AgentConfig`,改用 `agentConfig(Environment)` @Bean |
| **AgentConfig** | 0 改动 | `getPermissionPolicy()` 顶层字段已存在(Story #029) |
| **AskUserPermissionPolicy** | 0 改动 | 4-path 决策逻辑不变 |
| **AskUserPermissionPolicyProvider** | 0 改动 | `name() = "ask"` 不变 |
| **PermissionPolicyRouter** | 0 改动 | v1.5.28 多 Provider 模式 byName 解析不变 |
| **ApprovalRegistry** | 0 改动 | Story #041 落地不变 |
| **LinearTurnEngine** | 0 改动 | AskUser branch 阻塞-解阻塞回路不变 |
| **demo-product `application.yml`** | 0 改动 | 用户的 yml 配置不变,本 Story 只让 yml 真的生效 |

---

## WHAT(用户故事 + 验收)

### User Story 1 — yml `permission-policy: ask` 真接通 demo-product(P0,主线)

**描述**:demo-product 用户按 yml 配 `agent.permission-policy: ask` + `agent.tools.ask-list: [write_file, bash_safe]`,**完整 turn** 后:
- Agent 调 `write_file` → 后端 emit `AgentEvent.ApprovalRequired` → SSE 推 `{type:"approval", approvalId, ask}` → 前端显示 Allow/Deny 按钮(Story #042 已落)
- 用户点 Allow → POST `/api/approvals/{sessionId}/{approvalId}` body `{decision:"allow"}` → 后端 `ApprovalRegistry.consume` → 阻塞回合解 → ToolExecutor 继续 → 最终回复

### User Story 2 — yml `permission-policy: strict` 也跟着接通(回归)

**描述**:用户 yml 配 `agent.permission-policy: strict` + `agent.tools.allow-list: [read_file, list_dir]`,Agent 调 `write_file` → 后端 emit `Decision.Deny` + `[LINGS-P01]` reason,不 emit ApprovalRequired(strict 模式没 ask 语义)。**回归**测试 Bug A 修了不能让 strict 模式 broken。

### 验收标准(AC,跨 3 文件)

| AC 编号 | 描述 | 验证方式 |
|---|---|---|
| **AC-1** | `AgentFactory.create()` resolve `config.getPermissionPolicy()` 字段,不是 `config.getSandbox().getPolicy()` | L1 unit:mock AgentConfig with `permissionPolicy="ask"` → 验证 `policyRouter.resolve("ask", ...)` 被调用,**不**是 `resolve("default", ...)` |
| **AC-2** | `AgentFactory.create()` 处理 `permissionPolicy="ask"` 成功 resolve `AskUserPermissionPolicy` 实例 | L1 unit:reflection 验证 DefaultAgent 持有引用链可走到 ask policy |
| **AC-3** | `permissionPolicy="default"` 走 `AllowAllPermissionPolicy` 退路(yolo back-compat) | L1 unit:验证回归,所有历史 fixture 0 改动 |
| **AC-4** | `DemoProductApplication.readTools()` 真的读 `agent.tools.ask-list` yml key | L2 IT:`@SpringBootTest` + `Environment` 注入,yml 配 `tools.ask-list: [bash_safe, write_file]` → 验证 `cfg.getTools().getAskList().size() == 2` |
| **AC-5** | `ChatController.createSession()` 不再手 new 28-arg `AgentConfig` | L1 unit:反射 `ChatController` 字段,确认 `agentConfig` @Bean 字段被注入,**不**存在 `agentFactory` 内 hand-roll 路径 |
| **AC-6** | yml `permission-policy: ask` + `tools.ask-list: [bash_safe]` 端到端 e2e 跑通 | L3 IT:`@SpringBootTest` 跑 demo-product + mock LlmProvider + mock bash_safe Tool → submit agent.run → 验证 stream 包含 `ApprovalRequired` event |
| **AC-7** | R-13 mitigation (d) baseline 镜像 pre/post dep-tree md5sum 相同 | `mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree` pre + post diff |
| **AC-8** | 全部 0 新 ErrorCode / 0 新 Maven 依赖 / 0 改 `PermissionPolicy` SPI 公开方法签名 | static check + `mvn dependency:tree` diff |
| **AC-9** | 反向 AC:`permission-policy: default` 走 AllowAllPermissionPolicy(Story #001 fixture 不退步) | L1 unit 跑既有 `AgentFactoryYamlPermissionPolicyIT` 3 case 0 改动全过 |
| **AC-10** | 反向 AC:`permission-policy: strict` 走 StrictPermissionPolicy(Story #029 fixture 不退步) | L1 unit 跑既有 `StrictPermissionPolicyTest` 8 case 0 改动全过 |

---

## 关键不变项

- `PermissionPolicy` SPI 公开方法签名 0 改动
- `Decision` 3 子类(Allow / Deny / AskUser)0 改动
- `AgentEvent.ApprovalRequired` 3-arg ctor 0 改动
- `AskUserPermissionPolicy` 4-path 决策逻辑 0 改动
- `AskUserPermissionPolicyProvider` 0 改动
- `PermissionPolicyRouter` v1.5.28 多 Provider 模式 0 改动
- `ApprovalRegistry` 0 改动
- `LinearTurnEngine.dispatchWithPolicy` AskUser branch 0 改动
- `AgentConfig` 不可变契约 0 改动
- `AgentConfig.ToolsConfig` 不可变契约 0 改动
- `Tool` / `ToolExecutor` 5 步流水线 0 改动
- JDK 8 兼容(纯 `Collections.emptyList()` / `Arrays.asList()` 已锁)

---

## 风险 + 缓解

| 风险 | 缓解 |
|---|---|
| Bug A 改了之后,strict mode 的历史 fixture 可能 broken(如果 fixture 之前误用了 `sandbox.policy`) | AC-9 + AC-10 反向 AC 验证 strict/default 双模式 0 改动全过 |
| Bug B 改了 ChatController 走 @Bean,可能影响 `agent.session-store: file` 落盘契约(Story #046) | `agentConfig(Environment)` @Bean 内部已经覆盖 `sessionStoreName` + `compactorName` (DemoProductApplication L105-108),迁移到 @Bean 之后这两个字段继续工作 |
| Bug B 改 ChatController 后,memory leak 风险(singleton 共享可变 state) | `AgentConfig` 不可变(`@Value`),`agentConfig(Environment)` @Bean 单例安全(0 字段被 Spring 在运行时改) |
| `permission-policy: ask` 改完后,`tools.ask-list: []` 空列表会怎样 | `AskUserPermissionPolicy` 4-path 决策:空 ask-list = 全走 allow-list 检查,fallback 默认 = allow,等价于不限制。无需额外处理 |
| `tools.allow-list: ["*"]` + `tools.ask-list: [bash_safe]` 顺序问题 | `AskUserPermissionPolicy.check()` 5 段决策:deny-list → **ask-list** → allow-list,ask-list 先于 allow-list 命中,**bash_safe 永远会触发 ApprovalRequired**(正是用户预期) |

---

**修复者**: Claude Code(根据用户 2026-10-14 会话反馈「demo-product 配 `permission-policy: ask` + `tools.ask-list: [bash_safe]` 后跑 turn,工具被执行多次但 AskUser 0 触发,`bash` 后端报 'No such file or directory'」,确认 Bug A(framework-level typo) + Bug B(demo-product bypass)双重根因,要求「开 Story #047 全部修复(最小修复 = A + B 都改)」)
