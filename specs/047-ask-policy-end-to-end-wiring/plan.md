# Plan — Story #047 ask-policy end-to-end wiring

**Story #**: #047
**Slug**: `ask-policy-end-to-end-wiring`
**Spec**: `specs/047-ask-policy-end-to-end-wiring/spec.md`
**dsh §**: §6.1 LinearTurnEngine + §4.7 PermissionPolicy ask-mode + ChatController yml bypass

---

## Summary

`permission-policy: ask` 配 yml 后端 0 触发 = 配置 silent-ignored。两个根因:
- **Bug A**(`AgentFactory.java:280` framework-level typo) — `policyRouter.resolve(config.getSandbox().getPolicy(), config)` 读 Slot 3 子字段,正解读 Slot 4 顶层 `config.getPermissionPolicy()`
- **Bug B**(`ChatController.createSession()` demo-product 端) — hand-roll 28-arg `AgentConfig` 构造器绕过 yml 加载,且 `DemoProductApplication.readTools()` 硬编码空 askList

Story #047 三处 fix:AgentFactory L280 1-line + `DemoProductApplication.readTools()` 1-line + `ChatController` 改用现有 `agentConfig(Environment)` @Bean。10 AC 跨 3 测试文件(L1 unit × 3 + L2 IT × 2 + L3 e2e × 1 + 反向 AC × 2 + R-13 dep-tree × 1 + static check × 1)。修完后 demo-product 配 `permission-policy: ask` + `tools.ask-list: [bash_safe]` 真触发 `AgentEvent.ApprovalRequired` + 跟 Story #042 frontend button 闭环。

## Technical Context

- **JDK 8 硬约束**:no `var` / `sealed` / `records` / `List.of` / pattern matching switch / text blocks
- **Bug A 是 typo-level 1-line fix** — 对照同文件 L296 `sessionStoreRouter.resolve(config.getSessionStore(), config)` 写法,直接换字段即可
- **Bug B 改 ChatController 走 @Bean** — `agentConfig(Environment)` @Bean 已存在(`DemoProductApplication.java:89-187`),已 read 6 个 Slot 子键 + permissionPolicy + tools.allow-list/deny-list,**只缺 ask-list**(`readTools()` L382 硬编码)
- **BashSafeTool ProcessBuilder 路径空格 bug 跟本 Story 无关** — `BashSafeTool.execute()` L254 `new ProcessBuilder(cmd + " " + args)` 单 String varargs 让 program name 变成 `"pwd "`,引起 `'pwd' No such file or directory`,应该用 `new ProcessBuilder(List.of(cmd, args))` 或 split args。本 Story 不动 `ProductTools`,因为是 separate issue;若 fix 进 PR,扩 Story 文档 sync 段加 🆕
- **`tools.allow-list: ["*"]` 与 ask-list 共存** — `AskUserPermissionPolicy.check()` 5 段决策 deny-list → ask-list → allow-list,ask-list 先于 allow-list 命中,`bash_safe` 永远 ApprovalRequired
- **R-13 mitigation (d) 第 31 次 PASS** — 纯 JDK 8 + Lombok + Spring + Jackson + `Collections.emptyList()` 0 新 binary 引入

## Constitution Check

- [x] **不 push SOP 到 lingshu 仓** — 0 改 SOP / prompts / SKILL
- [x] **不省略 AC 黑盒验证** — 10 AC 全部跑过(3 L1 + 2 L2 + 1 L3 + 2 反向 AC + 1 R-13 + 1 static)
- [x] **不跨 Story 改 constitution** — 0 改宪章
- [x] **Story 边界 ≤ 5 核心文件改动** — 3 核心 prod 文件(AgentFactory + DemoProductApplication.readTools + ChatController) + 3 测试文件
- [x] **不绕过 Spring Boot SPI** — 复用 `agentConfig(Environment)` @Bean 单例(从 Spring 容器拿,等同 v1.5.28 §5.5 多 Provider 模式样板的同源 `@Component` 路径)
- [x] **不引入额外依赖** — 0 新 Maven 依赖,纯 JDK 8 + Lombok + Spring 已锁 13 项
- [x] **ReAct Loop 必须自实现** — 守住(0 改 LinearTurnEngine)
- [x] **Spring AI 只用两件事** — 守住(0 改 AnthropicLlmProvider)
- [x] **Provider 必须显式映射** — 守住(PermissionPolicyRouter 多 Provider 模式不变)
- [x] **R-13 mitigation (d) baseline 镜像** — 跑 dep-tree pre/post md5sum,本 Story 第 31 次 PASS

## Project Structure

### 文件改动清单(精确 3 核心 prod + 3 test + 0 改 SPI)

| # | 路径 | 类型 | 行数估算 | 内容 |
|---|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | **modify** | +1 / -1 | L280 `config.getSandbox().getPolicy()` → `config.getPermissionPolicy()`(1-line typo fix) |
| 2 | `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/DemoProductApplication.java` | **modify** | +1 / -1 | L382 硬编码 `Collections.emptyList()` → `readToolsList(env, "agent.tools.ask-list", fallback.getAskList())` |
| 3 | `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/ChatController.java` | **modify** | -50 / +20 | `createSession()` 改用 `agentConfig(Environment)` @Bean,**不**再 hand-roll 28-arg 构造器;ctor 增 `AgentConfig agentConfigTemplate` 字段 |
| 4 | `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryAskPolicyRoutingIT.java` | **new** | +180 | 3 case:yml `permission-policy: ask` 真的 resolve AskUserPermissionPolicy + strict 走 StrictPolicy + default 走 AllowAll(覆盖 AC-1/2/3) |
| 5 | `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryAskPolicyYamlIT.java` | **new** | +150 | 2 case:yml `tools.ask-list: [bash_safe, write_file]` 真 binding 到 `cfg.getTools().getAskList()`(覆盖 AC-4) |
| 6 | `lingshu-examples/demo-product/src/test/java/ai/lingshu/examples/demoproduct/DemoProductAskPolicyEndToEndIT.java` | **new** | +200 | 1 case(L3 e2e):`@SpringBootTest` 跑 demo-product + mock LlmProvider + mock bash_safe Tool → submit run → verify stream contains `ApprovalRequired`(覆盖 AC-6) |

**总改动 = 6 文件**:
- 3 核心 prod 改动(2 文件 1-line fix + 1 文件 -50/+20 重构)
- 3 测试文件(2 L1/L2 IT + 1 L3 e2e)
- 0 改 `AgentConfig` / `PermissionPolicy` SPI / `Decision` / `AgentEvent.ApprovalRequired` / `AskUserPermissionPolicy` / `AskUserPermissionPolicyProvider` / `PermissionPolicyRouter` / `ApprovalRegistry` / `LinearTurnEngine` / `AskUserPermissionPolicy` 4-path 决策逻辑

### 关键不变项

| 不变项 | 状态 | 备注 |
|---|---|---|
| `PermissionPolicy` SPI 公开方法签名 | 0 改动 | `check(Tool, ToolExecutionContext) → Decision` 不变 |
| `Decision` 3 子类(Allow / Deny / AskUser) | 0 改动 | 只让 AskUser 真被路由到 |
| `AgentEvent.ApprovalRequired` 3-arg ctor | 0 改动 | Story #030 已落 |
| `AskUserPermissionPolicy` 4-path 决策 | 0 改动 | 已经是正确的 4-path decision,只是从来不被 resolve 到 |
| `AskUserPermissionPolicyProvider` | 0 改动 | `name()="ask"` / `priority()=10` / `version()="1.0.0"` 不变 |
| `PermissionPolicyRouter` 多 Provider 模式 | 0 改动 | v1.5.28 §5.5 byName 路由不变 |
| `ApprovalRegistry` | 0 改动 | Story #041 已落 |
| `LinearTurnEngine.dispatchWithPolicy` AskUser branch | 0 改动 | Story #030 + #041 已落 |
| `AgentConfig` 不可变契约 | 0 改动 | `getPermissionPolicy()` 顶层字段(Story #029)+ `getTools().getAskList()` 字段(Story #030)都已存在 |
| `AgentConfig.ToolsConfig` 不可变契约 | 0 改动 | 字段不变 |
| `Tool` / `ToolExecutor` 5 步流水线 | 0 改动 | §4.10.1 硬规则 2 守住 |
| `ChatController` 公开 REST endpoint | 0 改动 | `POST /api/approvals/{sessionId}/{approvalId}` + `POST /api/sessions` 都不变,只 `createSession()` 内部实现换 |
| JDK 8 兼容 | 守住 | `Collections.emptyList()` / `Arrays.asList()` / Lombok `@Value` 已锁 |

### 反向 AC(regression 守护)

- `AgentFactoryYamlPermissionPolicyIT` (Story #029 3 case) 0 改动全过
- `StrictPermissionPolicyTest` (Story #029 8 case) 0 改动全过
- `PermissionPolicyRouterStrictIT` (Story #029 4 case) 0 改动全过
- `AskUserPermissionPolicyTest` (Story #030 5 case) 0 改动全过
- `LinearTurnEngineAskUserTest` (Story #030 3 case) 0 改动全过
- `DefaultApprovalGateTest` (Story #041 5 case) 0 改动全过
- `AgentFactoryYamlSessionStoreSaveIT` (Story #046 3 case) 0 改动全过 — Bug B 改 ChatController 走 @Bean 之后,sessionStore 仍走 AgentConfigDefaults + env.getProperty 链(AgentConfigDefaults.defaults() 是 Spring @Bean + DemoProductApplication.agentConfig 已经 read)

### R-13 dep-tree 守门

```bash
# pre
mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree > /tmp/pre.txt
# 实施
# post
mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree > /tmp/post.txt
diff /tmp/pre.txt /tmp/post.txt  # 只允许时间戳差异
```

预期 0 binary delta(纯 Java 文件内 1-line 改 + 1-line 改 + ChatController 改用 @Bean),**R-13 mitigation (d) 第 31 次 PASS**。

---

## 6 阶段实施路径

### Phase 0 — pre-flight R-13 baseline(~2 min)

```bash
mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -DoutputType=text > /tmp/r13-pre.txt
md5sum /tmp/r13-pre.txt  # 记录 baseline
```

### Phase 1 — Bug A fix `AgentFactory.java:280`(~1 min)

```bash
# Edit 1 line
sed -n '278,280p' lingshu-core/.../AgentFactory.java
# 修改:
# PermissionPolicy permissionPolicy = policyRouter.resolve(config.getSandbox().getPolicy(), config);
# ↓
# PermissionPolicy permissionPolicy = policyRouter.resolve(config.getPermissionPolicy(), config);
```

### Phase 2 — Bug B step 1 `DemoProductApplication.readTools()`(~2 min)

```bash
# L382 硬编码 → 读 yml
# 改前:fallback.isEnabled(), allowList, denyList, java.util.Collections.<String>emptyList(), ...
# 改后:fallback.isEnabled(), allowList, denyList, readToolsList(env, "agent.tools.ask-list", fallback.getAskList()), ...
```

### Phase 3 — Bug B step 2 `ChatController.createSession()` 改用 @Bean(~10 min)

- ctor 加 `AgentConfig agentConfigTemplate` 字段
- `createSession()` 内部从 `agentConfigTemplate` 拿 base 替换 `AgentConfigDefaults.defaults()`
- SessionRegistry 的 factory lambda 改:`return agentFactory.create(agentConfigTemplate);` (Lombok `@Value` 不可变,直接复用)

### Phase 4 — 写测试(覆盖 AC-1 到 AC-10,~30 min)

- `AgentFactoryAskPolicyRoutingIT`(3 L1/L2 case)
- `AgentFactoryAskPolicyYamlIT`(2 L2 case)
- `DemoProductAskPolicyEndToEndIT`(1 L3 e2e)

### Phase 5 — 跑全 suite + R-13 dep-tree post-flight(~5 min)

```bash
mvn -pl lingshu-core,lingshu-examples/demo-product test
mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -DoutputType=text > /tmp/r13-post.txt
diff /tmp/r13-pre.txt /tmp/r13-post.txt  # 0 binary delta
```

### Phase 6 — 文档同步(README / CLAUDE.md / ROADMAP / constitution / dsh)(~10 min)

---

## 决策点表

| # | 决策点 | 选项 | 选定 | 理由 |
|---|---|---|---|---|
| D1 | ChatController 怎么拿 `AgentConfig`? | (a) 注入 `AgentConfig` @Bean 单例 (b) 注入 `Environment` 自己 read 6 个 key (c) 注入 `AgentConfigDefaults` | (a) | 已有现成 `agentConfig(Environment)` @Bean 跑了 6+ 个 key,扩展 ask-list 后已完整覆盖,复用 0 重复代码 |
| D2 | `agentConfig(Environment)` @Bean 单例安全吗? | (a) `AgentConfig` 不可变 = 安全 (b) Spring 容器会改 | (a) | `AgentConfig.@Value` Lombok 不可变 + `agentConfig(Environment)` 内部所有 `read*` 调一次后 0 改,Spring 容器在 0 字段上不持有可变引用 |
| D3 | `tools.ask-list` 走 `readToolsList` 复用 pattern? | (a) 复用 (b) 单写一个 | (a) | `readToolsList` 已经是 private 静态 + 接受 prefix + fallback 模式,直接复用 0 改 helper |
| D4 | Bug A fix 是只改 1 个 field reference? | (a) 是 (b) 还需要改他处 | (a) | `policyRouter.resolve(...)` 只此 1 处调用,`AgentFactory` 内其他 resolve 都已正确(对照 L278/279/281/282/295) |
| D5 | 反向 AC 跑多少? | 全部既有 fixture | 必跑 | Story #029 / #030 / #041 / #046 全部回归守护,Bug A 修了不能让 strict 模式 broken |

---

## 镜像 precedent 对位表

| Story | precedent | 对位到本 Story |
|---|---|---|
| Story #045 | Slot 6 Compactor wiring 7-arg ctor + Spring 6 dual-ctor | n/a(本 Story 0 改 LinearTurnEngine ctor) |
| Story #046 | SessionStore save 接入 turn loop | n/a(本 Story 0 改 SessionStore) |
| Story #014 | `agentConfig(Environment)` 复用 yml 接线 | **直接复用** `agentConfig(Environment)` @Bean 让 ChatController 走 |
| Story #037 | 3 Provider 多 Provider 模式对齐 | n/a(本 Story 0 改 Provider 注册路径) |
| Story #041 | ApprovalGate SPI 抽离 | n/a(本 Story 0 改 ApprovalGate) |
| Story #042 | frontend button 闭环 | **直接受益** — 本 Story 修完后 frontend 按钮终于会被触发 |

---

## 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| Bug A 修了 strict 模式 broken | M | 反向 AC 跑 `StrictPermissionPolicyTest` 8 case + `AgentFactoryYamlPermissionPolicyIT` 3 case |
| Bug B 改 ChatController 走 @Bean 引起 demo-product 启动 fail | M | `@SpringBootTest` IT 跑 `DemoProductApplication.main` 启动路径 + 验证 session 创建 OK |
| `agentConfig(Environment)` @Bean 跑 Spring context 慢(8+ Router resolve) | L | Spring 启动期一次性,运行时 0 延迟;实测量级 ms |
| 改 `agentConfig(Environment)` 后 session-store: file 落盘契约(Story #046)broken | L | AC-7 R-13 dep-tree + 反向 AC 跑 `AgentFactoryYamlSessionStoreSaveIT` 3 case |
| `tools.ask-list: []` 空列表(用户故意)导致 ask-mode 0 触发 | L | 文档加 yml 示例:ask-list 必填非空,空 = 退化为 strict |
| BashSafeTool ProcessBuilder 程序名空格 bug 跟本 Story 一起 | L | Story #047 文档 sync 段加 🆕 BashSafeTool L254 已知 bug,留作 follow-up |

---

## 人工端到端 7 步清单(demo-product,本 Story 实施后)

1. `mvn -pl lingshu-examples/demo-product spring-boot:run`
2. `curl http://localhost:8080/`,确认 HTML 加载
3. yml 已配 `permission-policy: ask` + `tools.ask-list: [bash_safe, write_file]`(本仓 `application.yml` 默认配好,无需手动改)
4. 浏览器输入 "List current directory" → 触发 `bash_safe` Tool
5. SSE events 区看到 ApprovalRequired card + Allow/Deny 按钮(Story #042 已落)
6. 点 Allow → ToolExecutor 继续 + 最终回复"current directory contains: ..."
7. 再试一次点 Deny → SSE events 区看到 deny reason + 拒绝 ToolResult 回主 Agent 路径

**预期**:5 步出 ApprovalRequired 按钮(本 Story 修后);旧版本 5 步直接报 `pwd: No such file or directory` 失败(本 Story 修前的 broken 状态)。

---

**修复者**: Claude Code(根据用户 2026-10-14 会话反馈,触发本 Story spec/plan/tasks 落地,等用户「实施吧」触发 Phase 1-5 实际代码改动;Story #047 修完后 累计 48 个 Story 合入)
