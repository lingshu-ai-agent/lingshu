# Story #029 `permission-policy-impl` — Spec

> **Status**: Draft 2026-09-30
> **Source**: dsh v1.5.46 §4.7 PermissionPolicy(`PermissionPolicy.check(ToolCall, ToolExecutionContext) → Decision` 契约就位 + `Decision` 4 子类 `Allow/Deny/AskUser/Option` 已落地)+ §5.5 Slot 4 `StrictPermissionPolicyProvider` 模板(L2168-2189 design intent:"集成 c.getSandbox().getCommandWhitelist() + domainWhitelist 默认白名单")+ §4.10.1 硬规则 2(`ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 PermissionPolicy.check 当前**空跑** —— AllowAllPermissionPolicy 直返 `Decision.Allow` 任何 tool call 都过)+ §15.4 ErrorCode 域(本期启用 **P 段 1 号** = `LINGS-P01 PERMISSION_DENIED`,**新 ErrorCode 域启用** = dsh §15 域字母表 **9 → 10** `C/S/L/T/X/R/A/M/Z` + **`P` = Permission**,对齐 `#028 LINGS-S01` precedent)+ §5.3.1.0 `PermissionPolicyRouter`(SlotResolver 内 6 Router 之一)+ §5.5 多 Provider 模式(v1.5.28 plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)+ §13 changelog v1.5.46 行 + constitution v1.0 + ROADMAP §6 主链漏项补救(2026-09-30 加 #029 行)
> **前置依赖**:`#001` zero-config-bootstrap(`Agent` 4 final 字段 + `DefaultAgent.buildContext` 冻结语义)+ `#003` spi-slot-router(`SlotRouter<P,T>` 父类 + 多 Provider 模式)+ `#004` tool-parallel-dispatch(`ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 PermissionPolicy.check 当前 stub)+ `#005` cancellation-token(CancellationToken 三层贯通)+ `#007` yaml-hot-reload(`AgentConfigRegistry` AtomicReference + hot-reload 期间 cfg 冻结)+ `#019` built-in-tools(`AgentConfig.ToolsConfig` 3 字段:`enabled` / `maxReadBytes` / `maxWriteBytes` — 本期**新增 2 字段**:`allowList` + `denyList`)+ `#028` sandbox-runtime-impl(`SandboxErrorCodes` precedent + `AccessDeniedException extends RuntimeException` 模式 + `ErrorCode 嵌入 message 模式 "[LINGS-XXX] reason"` + `banned-dependencies` enforcer R-13 兜底) —— **8 个 Story 已合**
> **同 Story 拆解**:无。`PermissionPolicy` Slot 4 模型层(`PermissionPolicy.check()` 接口 0 改动)是 §4.7 唯一未真接通的子模块 —— `AllowAllPermissionPolicyProvider` 自 #001 默认实现 stub 阶段(`name="default"` + `priority=0`)从未被真替换;本 Story 一次性把 1 新默认 Provider + 1 新 strict 实现 + 1 新 Permission 域 ErrorCode + 2 新 AgentConfig 字段 + 1 demo 切换 + 文档同步全部落地

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.46 §4.7 PermissionPolicy + §5.5 Slot 4 `StrictPermissionPolicyProvider` L2168-2189 + §4.10.1 硬规则 2 + §15.4 ErrorCode 域 + §15.4 域字母表 + §5.3.1.0 `PermissionPolicyRouter` + §5.5 多 Provider 模式 + §13 changelog v1.5.46 行
- **实测发现**:2026-09-30 审 `AllowAllPermissionPolicy.java` L14-20 时发现:`@Override public Decision check(ToolCall call, ToolExecutionContext ctx) { return new Decision.Allow("default policy: allow all"); }` —— **直返 Allow,任何 tool call 都过**。整个 §4.7 PermissionPolicy 真逻辑(`Decision.Allow` / `Decision.Deny` / `Decision.AskUser` 3 选 1 决策)**0 落地**。配合 Story #028 sandbox-runtime-impl 真接通后,§6 安全栈双层防线(sandbox runtime 限制 fs / http / process / PermissionPolicy 决策 tool 调)单层失守
- **业务后果**(当前状态):
  - `application.yml` 配 `policy: strict` 后,`PermissionPolicyRouter.resolve("strict", cfg)` 走 `name` 路由找不到 `strict` 这个 `PermissionPolicyProvider`(只有 `AllowAllPermissionPolicyProvider.name()="default"`)—— 启动期 **fallback 到 `AllowAllPermissionPolicy`**(§5.2 SlotRouter fallback 默认到 `priority=0` 那个)
  - 用户**实际**无法用 `policy: strict` 真换掉 AllowAll;demo-product / demo-empty / demo-delegate 等全部 `policy: default` —— **所有 tool call 默认全过**,`LINGS-P01` 永不触发
  - §4.10.1 硬规则 2 `ToolExecutor.dispatch()` 5 步流水线第 1 步 `PermissionPolicy.check()` 当前 stub 直接 `Decision.Allow`,后面 4 步(sandbox / registry lookup / timeout / execute / checkpoint)的"前置防线"形同虚设
  - §15.4 ErrorCode 域字母表 9 域(`C/S/L/T/X/R/A/M/Z`),**Permission** 域空缺(虽然 `PermissionPolicy` Slot 4 早落地但 0 实施)
- **对应风险**:**R-04**(privilege escalation — 分值 8)+ **R-13** mitigation (d)(R-13 第 15 次 PASS 强制)+ §15.4 ErrorCode 域 **P**(Permission)段 1 号启用(域字母表 **9 → 10**,新增 `P` 域段)
- **涉及 ErrorCode**:**1 新 ErrorCode** —— `LINGS-P01 PERMISSION_DENIED`(Permission 域 **P 段 1 号**,**新 ErrorCode 域启用** = dsh §15 域字母表 9 → 10 `C/S/L/T/X/R/A/M/Z` + `P` = 10 域,对齐 `#028 LINGS-S01` precedent + `#023 LINGS-D01` precedent + `#022 LINGS-T08` precedent + `#027b LINGS-L03 reserved` precedent)

---

## 1. WHY(为什么做这个 Story)

**核心问题**:LingShu §4.7 PermissionPolicy 设计模板在 dsh §4.7 L678-696 + §5.5 L2168-2189 已完整给出,但 0 实施 —— `AllowAllPermissionPolicyProvider` 自 #001 默认实现 stub 阶段(`name="default"` + `priority=0`)从未被真替换。

具体 4 个 gap:

1. **`PermissionPolicy.check()` 直返 Allow** —— `AllowAllPermissionPolicy.L17-19` `return new Decision.Allow("default policy: allow all");` **任何** `ToolCall` 都过;`Decision.Deny` / `Decision.AskUser` 2 分支**永不**触
2. **`StrictPermissionPolicy` 未实现** —— dsh §5.5 L2168-2189 design intent 写「Story #001 创建 StrictPermissionPolicy + 集成 c.getSandbox().getCommandWhitelist() + domainWhitelist 默认白名单」,**实际未落地**;`StrictPermissionPolicyAutoConfiguration` 类**不存在**
3. **`ToolsConfig` 缺白/黑名单字段** —— `AgentConfig.ToolsConfig` 仅 3 字段(`enabled` / `maxReadBytes` / `maxWriteBytes`),**无 `allowList` / `denyList`**;`StrictPermissionPolicy` 决策无数据源
4. **`PermissionPolicyRouter.resolve("strict", cfg)` 永远 fallback** —— `AllowAllPermissionPolicyProvider` `name="default"` + `priority=0`,用户 yml 写 `agent.sandbox.policy: strict`(注:`sandbox.policy` 当前仅占位文档字符串,实际 `PermissionPolicy` 选择路径需新增 `agent.policy: strict` 顶层字段)→ SlotRouter 找不到 `strict` → fallback 到 `priority=0` 那个 AllowAll,**字面 strict 实际 allow-all**

**业务后果**:
- Demo-product(`#025`)+ Demo-delegate(`#023`)+ Demo-empty 全 `policy: default`(实际 `AllowAllPermissionPolicy`)—— **任何** Tool 调过 policy 关,sandbox 是唯一防线(Story #028 之前 sandbox 也失守,Story #028 后双层防线单层 sandbox,Permission 仍 0)
- §14.9 多租户隔离的"各租户 tool policy 独立"承诺落空(Tenant Alice 配 `allowList: [read_file]` 但实际 AllowAll 全过)
- §14.10 N10 audit-log 设计时 Permission 事件源是空集,本 Story 合入后 `LINGS-P01` 可接入审计流(已在 ROADMAP §14 N10 cross-ref 登记)
- §15.4 ErrorCode 域 `P`(Permission)空缺,新错误码无域可入
- §4.10.1 硬规则 2 `ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 `PermissionPolicy.check()` 当前 stub 直返 Allow,**防线 0**

**Story #029 业务价值**:
- 把 dsh §4.7 L678-696 + §5.5 L2168-2189 模板真落地:`StrictPermissionPolicy implements PermissionPolicy` 真查 `AgentConfig.ToolsConfig.allowList` + `denyList` 决策
- `StrictPermissionPolicyProvider`(`@Bean(name="permissionPolicyProvider_strict-1.0.0")` + `name()="strict"` + `priority()=10`)—— 多 Provider 模式对齐 v1.5.28 §5.5
- `LINGS-P01 PERMISSION_DENIED` ErrorCode 落地(Permission 域 P 段 1 号,**新 ErrorCode 域启用** = dsh §15 域字母表 9 → 10)
- `AgentConfig.ToolsConfig` 扩 2 字段 `allowList` + `denyList`(`@Value` 不可变契约扩展,需 `tools` 默认值工厂同步)
- demo-product / demo-empty 切换 `policy: strict` **真生效**(`AgentConfig` 顶层新增 `permissionPolicy` 字段 = `StrictPermissionPolicyProvider.name()="strict"`)
- §4.10.1 硬规则 2 §4.7 第 1 步 `PermissionPolicy.check()` 从 stub 变真实现,ToolExecutor 5 步流水线防线 0 → 真双层

**关键不变项**:
- `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)+ `ToolCall` + `ToolExecutionContext` —— **0 改动**
- `PermissionPolicyRouter`(§5.3.1.0 SlotResolver 内 6 Router 之一,`SlotRouter<PermissionPolicyProvider, PermissionPolicy>` 父类已落)—— **0 改动**(只是 `PermissionPolicyRouter.resolve("strict", cfg)` 当前 fallback,本 Story 加 strict 后 fallback 路径不变,只是真命中 strict)
- `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider`(`name="default"` + `priority=0`)—— **保留**(作为默认 fallback,与 strict 并存,符合 §5.5 多 Provider 模式)
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 PermissionPolicy.check() —— **0 改动**(只是 check() 内从 stub 直返 Allow 变成 strict 真查表)
- `Agent` 4 final 字段(T1→T4 不变)/ `AgentFactory.create()` 7 项校验 不变
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 已是 SlotResolver 6 Router 之一,**不**作隐式 Router)
- `LINGS-P01` ErrorCode 嵌入 message 模式 `"[LINGS-P01] " + reason`(对齐 #028 LINGS-S01 + #022 LINGS-T08 + #023 LINGS-D01 precedent)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),Permission 真实现通过 `ToolExecutor.dispatch()` §4.7 第 1 步触发,**不走 ChatClient 自动执行**
- **0 新 Maven 依赖**(`List.contains` + `Collections.emptyList()` + `Arrays.asList` + `Lombok @Value` + `Spring @Component` / `@AutoConfiguration` + `AssertJ` / `Mockito` 全 JDK 8 standard + 已锁 13 项依赖表内)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 给 Agent 配 `agent.policy: strict` + `agent.tools.allow-list: [read_file, list_dir, bash_safe]` + `agent.tools.deny-list: [rm_rf]` —— `read_file` / `list_dir` / `bash_safe` 真过,**未在白名单**的 tool(如 `write_file`)真被拒抛 `LINGS-P01`;**在 deny-list** 的 tool 也真被拒 |
| **多租户平台搭建者** | Tenant Alice 配 `allow-list: [read_file, list_dir]` + `deny-list: [bash_safe]`,Tenant Bob 配 `allow-list: [read_file, write_file, edit_file, bash_safe]` —— 各生效(§14.9 AC-05 Permission 部分真隔离) |
| **运维稳定性关注者(Eve 类)** | `application.yml` 加 `deny-list: [dangerous_tool]` 后,**不重启 Agent** 下一个 turn 生效(§14.8 N8 hot-reload 已合,Permission 本 Story 合入后端到端通);`LINGS-P01` 拒绝事件可接 §14.10 N10 audit-log(已在 ROADMAP cross-ref 登记) |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `StrictPermissionPolicy.check()` 5 case(allow-list 含 / allow-list 不含 deny / deny-list 含 / 两表都含 / 都空 全过)+ L3 黑盒跑 `agent.runBlocking("调 read_file")` → `Decision.Allow` 真发 + `agent.runBlocking("调未声明 tool")` → `Decision.Deny` 含 LINGS-P01 |
| **框架贡献者 / plugin 作者(Bob 类)** | `PermissionPolicyProvider` SPI 接口对齐 v1.5.28 多 Provider 模式(§5.5)—— 未来可加 `TrustlessPermissionPolicyProvider`(用户每次都确认,无白名单,dsh §5.5 L2187 precedent)等替代实现,`agent.policy: trustless` 切换无需改 classpath |
| **安全审计员(Diana 类)** | 配置白/黑名单后,`AuditLogger`(`#016` 已落,可选引入)记录每次 `LINGS-P01` 拒绝事件 —— 审计 log 可查「谁 / 何时 / 调哪个 tool / 拒绝原因」,合规场景下必备 |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**:

1. **`StrictPermissionPolicy implements PermissionPolicy`**(新,Slot 4 strict 实现):
   - `@Component` 类(不 `@Value`,因为持有 `ToolsConfig` 字段状态)
   - 构造器 `StrictPermissionPolicy(AgentConfig.ToolsConfig tools)` —— 读 `tools.allowList` + `tools.denyList`(`@Value` 不可变契约)
   - `@Override public Decision check(ToolCall call, ToolExecutionContext ctx)`:
     - `toolName = call.name()` 提取 tool name(`ToolCall.name()` 字段)
     - **allow-list 决策**(优先级最高):如果 `allowList` 非空且 `toolName` 不在 `allowList` → `return new Decision.Deny("[LINGS-P01] Tool '" + toolName + "' not in allow-list")`(deny 路径)
     - **deny-list 决策**:如果 `denyList` 非空且 `toolName` 在 `denyList` → `return new Decision.Deny("[LINGS-P01] Tool '" + toolName + "' in deny-list")`(deny 路径)
     - **default allow**:两表都空 → `return new Decision.Allow("default policy: allow (no allow-list / deny-list configured)")`
   - Javadoc 覆盖 (1) §4.10.1 硬规则 2 流水线串联(§4.7 第 1 步) + (2) 3 决策路径 + (3) ErrorCode 嵌入 message 模式

2. **`StrictPermissionPolicyProvider implements Providers.PermissionPolicyProvider`**(新,Slot 4 strict Provider):
   - `@Component`
   - `@Override public String name() { return "strict"; }` —— **必须唯一**(§5.2 同名竞争,`AllowAllPermissionPolicyProvider.name()="default"` 已占)
   - `@Override public int priority() { return 10; }` —— 高于 `AllowAllPermissionPolicyProvider.priority=0`,§5.2 同名时 priority 最大胜出(本期不同名,留作安全垫)
   - `@Override public String version() { return "1.0.0"; }` —— 契约版本对齐 #003 SPI
   - `@Override public PermissionPolicy create(AgentConfig config) { return new StrictPermissionPolicy(config.getTools()); }`

3. **`PermissionPolicyAutoConfiguration`**(新,Slot 4 strict Provider 注册):
   - `@AutoConfiguration` 类
   - `@Bean(name="permissionPolicyProvider_strict-1.0.0") public PermissionPolicyProvider strictPermissionPolicyProvider()` —— 返 `new StrictPermissionPolicyProvider()`
   - 对齐 §5.5 多 Provider 模式(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)

4. **`PermissionErrorCodes` 常量类**(新):
   - `public static final String LINGS_P01 = "LINGS-P01";`(`PERMISSION_DENIED`)
   - 注释引用 §15.4 ErrorCode 域 **P** 段 1 号 + §4.7 PermissionPolicy + 对齐 #028 LINGS-S01 模式

5. **`AgentConfig.ToolsConfig` 扩 2 字段**(modify):
   - `List<String> allowList` —— tool 白名单(优先级最高,非空时强制要求 tool 在表内)
   - `List<String> denyList` —— tool 黑名单(非空时 tool 在表内即 deny)
   - `@Value` 不可变契约扩字段 —— 字段位置:`enabled` → `allowList` → `denyList` → `maxReadBytes` → `maxWriteBytes`(allow-list / deny-list 紧贴 `enabled` 之后,语义聚合)
   - `defaults()` 工厂方法同步扩 —— `new ToolsConfig(true, Collections.emptyList(), Collections.emptyList(), 200_000, 1_000_000)`(allow-list / deny-list 默认空 → default allow,行为等价于 AllowAll)
   - `validate()` 方法**不**扩(`allowList` / `denyList` 字符串内容不做格式校验 —— 留作 §6.5 OQ-Future,本期只校验「`enabled` + byte caps > 0」)

6. **`AgentConfig` 顶层扩 1 字段**(modify):
   - `String permissionPolicy` —— 类似 `toolExecutor` / `compactor` / `sessionStore` / `a2aTransport` 顶层字段,默认 `"default"`
   - 解析路径:`MinimalYamlParser` `agent.permission-policy: strict` → `AgentConfig.permissionPolicy = "strict"`
   - `SlotResolver`(`PermissionPolicyRouter` 内)自动按 `cfg.permissionPolicy` 路由到 `StrictPermissionPolicyProvider`(`name="strict"`)或 fallback 到 `AllowAllPermissionPolicyProvider`(`name="default"`)

7. **`demo-product` / `demo-empty` 切换 policy: strict 真生效**(modify):
   - `demo-product/src/main/resources/application.yml` 顶层加 `permission-policy: strict` + `agent.tools.allow-list: [read_file, write_file, list_dir, bash_safe]`(`ProductTools` 4 个 `@Component` Tools)
   - `demo-empty/src/main/resources/application.yml` 顶层加 `permission-policy: strict`(无 tool 注册,任何 tool 调都拒 —— 演示 deny 路径)
   - 注释引用 dsh §5.5 L2168-2189 `StrictPermissionPolicyProvider` + §4.7 PermissionPolicy

8. **`AllowAllPermissionPolicyProvider` 保留**(不变):
   - 作为默认 fallback(`name="default"` + `priority=0`),与 strict 并存
   - yml 不配 `permission-policy` 字段 → fallback 到 default(允许所有,行为不变,back-compat)

**新配置参数**:`AgentConfig` 扩 2 字段 + `AgentConfig.permissionPolicy` 1 字段,合计 **3 字段**

**用户会看到的错误码**:

| ErrorCode | 抛出位置 | 触发条件 | 用户响应 |
|---|---|---|---|
| **`LINGS-P01` `PERMISSION_DENIED`** | `StrictPermissionPolicy.check()` 2 决策路径(`allow-list` 不含 / `deny-list` 含)| (a) yml 配 `permission-policy: strict` + `tools.allow-list: [read_file, list_dir]` → 调 `write_file` 抛;(b) yml 配 `permission-policy: strict` + `tools.deny-list: [bash_safe]` → 调 `bash_safe` 抛;(c) 两表都配,allow-list 不含 OR deny-list 含(任一触发即 deny)| (a) 加 `write_file` 到 `allow-list`;(b) 从 `deny-list` 删 `bash_safe`;(c) 调 yml 让 allow-list / deny-list 一致 |

**关键不变量**(不变项):
- `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)+ `ToolCall` + `ToolExecutionContext` —— **0 改动**
- `PermissionPolicyRouter`(§5.3.1.0 `SlotRouter<PermissionPolicyProvider, PermissionPolicy>` 父类已落)—— **0 改动**(只是 `PermissionPolicyRouter.resolve(name, cfg)` 当前 fallback,本 Story 加 strict 后**多 Provider 模式**自然命中 strict)
- `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider` —— **0 改动**(默认 fallback 保留)
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 `PermissionPolicy.check()` —— **0 改动**(只是 check() 内从 stub 直返 Allow 变成 strict 真查表 —— `ToolExecutor` 不感知)
- `Agent` 4 final 字段(T1→T4 不变)/ `AgentFactory.create()` 7 项校验 —— **0 改动**
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 是 SlotResolver 6 Router 之一)
- `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**(Sandbox 字段是 runtime sandbox 关心,Permission 字段是 Tool-level 决策,两表正交)
- `LINGS-P01` ErrorCode 嵌入 `Decision.Deny.reason` message(`getReason() = "[LINGS-P01] Tool 'xxx' not in allow-list"`),ToolExecutor §4.7 第 1 步收到 Deny 后**转** `ToolResult.error` 兜底(对齐 §4.10.1 硬规则 2 ToolExecutor execute 永不抛)
- JDK 8 兼容(`List.contains` + `Collections.emptyList()` + `Arrays.asList` + `Lombok @Value` + `Spring @Component` / `@AutoConfiguration` + `AssertJ` / `Mockito` 全 JDK 8 standard + 已锁 13 项依赖表内)
- §15.4 ErrorCode 域字母表新增 `P = Permission` 域段(本期启用 P01,域字母表 **9 → 10** = `C/S/L/T/X/R/A/M/Z` 9 域 + **`P` = 10 域**)
- `AgentConfig` 24 字段 schema 扩 **3 字段** = 27 字段(`permissionPolicy` 1 + `ToolsConfig.allowList` + `ToolsConfig.denyList` 2);注意 dsh §4.12 L1104 `AgentConfig` 顶层字段 **未锁数量**,本 Story 扩字段合规(CLAUDE.md §7 零配置原则:空 yml 仍能启动,所有新字段有默认值)

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — `StrictPermissionPolicy` 真查 allow-list + deny-list

**Given** `AgentConfig.ToolsConfig` 实例 `tools = new ToolsConfig(true, Arrays.asList("read_file", "list_dir"), Arrays.asList("bash_safe"), 200_000, 1_000_000)`,`policy = new StrictPermissionPolicy(tools)`
**When** `policy.check(new ToolCall("write_file", "{}"), ctx)`(不在 allow-list)
**Then** `return Decision.Deny`,`deny.getReason()` 含 `[LINGS-P01]` + `"Tool 'write_file' not in allow-list"`
**When** `policy.check(new ToolCall("read_file", "{}"), ctx)`(在 allow-list)
**Then** `return Decision.Allow`,`allow.getReason()` 含 `"default policy: allow"` 或类似
**When** `policy.check(new ToolCall("bash_safe", "{}"), ctx)`(在 deny-list)
**Then** `return Decision.Deny`,`deny.getReason()` 含 `[LINGS-P01]` + `"Tool 'bash_safe' in deny-list"`
**断言方式**:L1 Unit 4 case,`assertThatThrownBy` + `hasMessageContaining("LINGS-P01")` + `instanceof Decision.Deny` + `getKind() == "deny"`

### AC-NN-2 — 两表都空 → default allow(等价 AllowAll)

**Given** `AgentConfig.ToolsConfig tools = ToolsConfig.defaults()`(`allowList = []`,`denyList = []`)
**When** `policy.check(new ToolCall("any_tool", "{}"), ctx)`
**Then** `return Decision.Allow`(default allow,与 AllowAll 行为一致)
**断言方式**:L1 Unit 1 case,`assertThat(decision).isInstanceOf(Decision.Allow.class)`

### AC-NN-3 — `StrictPermissionPolicyProvider` 多 Provider 模式命名唯一

**Given** `StrictPermissionPolicyProvider` SPI 实现
**When** `provider.name()` 返回 `"strict"` + `provider.priority()` 返回 `10`
**Then** `name()` 不与 `AllowAllPermissionPolicyProvider.name()="default"` 冲突(§5.2 同名竞争);`@Bean(name="permissionPolicyProvider_strict-1.0.0")` 多 Provider 模式对齐 v1.5.28 §5.5
**断言方式**:L1 Unit 2 case,`assertThat(provider.name()).isEqualTo("strict")` + `assertThat(provider.priority()).isEqualTo(10)` + `@SpringBootTest` 或 `AnnotationConfigApplicationContext` 验证 `permissionPolicyProvider_strict-1.0.0` Bean 注册存在

### AC-NN-4 — `LINGS-P01` ErrorCode 嵌入 Decision.Deny.reason 模式

**Given** `ai.lingshu.core.permission.PermissionErrorCodes` 常量类
**When** `LINGS_P01 = "LINGS-P01"` + `PermissionErrorCodes.LINGS_P01`
**Then** `StrictPermissionPolicy.check()` 2 决策路径(allow-list 不含 / deny-list 含)返 `Decision.Deny.reason` 都含 `[LINGS-P01]` 前缀,让 AssertJ `hasMessageContaining("LINGS-P01")` 工作
**断言方式**:L1 Unit 2 case,验证 2 决策路径 reason 字符串前缀 `[LINGS-P01]`

### AC-NN-5 — `PermissionPolicyRouter.resolve("strict", cfg)` 真命中 strict

**Given** 完整 Spring 上下文装配(`PermissionPolicyAutoConfiguration` 注册 `permissionPolicyProvider_strict-1.0.0` Bean + `AllowAllPermissionPolicyProvider` `default` Bean)
**When** `router.resolve("strict", cfg)`
**Then** 返 `StrictPermissionPolicy` 实例(不是 `AllowAllPermissionPolicy`)
**When** `router.resolve("default", cfg)`
**Then** 返 `AllowAllPermissionPolicy` 实例(back-compat 默认 fallback 不变)
**当 `cfg.permissionPolicy = "strict"`**(yml 顶层 `permission-policy: strict`)时,`PermissionPolicyRouter` 自动按 cfg 路由到 strict
**断言方式**:L2 Slice 测试,`AnnotationConfigApplicationContext` 装配 + `router.resolve(name, cfg)` 验证

### AC-NN-6 — `AgentConfig.ToolsConfig` 扩 2 字段 + `defaults()` 兼容

**Given** `AgentConfig.ToolsConfig` `@Value` 不可变契约
**When** 新构造器签名 `ToolsConfig(boolean enabled, List<String> allowList, List<String> denyList, int maxReadBytes, int maxWriteBytes)`
**Then** `ToolsConfig.defaults()` 返 `(true, [], [], 200_000, 1_000_000)` —— **新字段默认值空** — `StrictPermissionPolicy(tools).check(any, ctx)` 仍 `Decision.Allow`(back-compat 默认 allow)
**断言方式**:L1 Unit 1 case,`assertThat(ToolsConfig.defaults().getAllowList()).isEmpty()` + `assertThat(ToolsConfig.defaults().getDenyList()).isEmpty()`

### AC-NN-7 — `AgentConfig.permissionPolicy` 顶层字段 + yml 顶层解析

**Given** `AgentConfig` `@Value` 不可变契约
**When** 新字段 `String permissionPolicy`(默认 `"default"`)
**Then** `AgentConfig.defaults()` 返 `permissionPolicy = "default"`(back-compat 默认 fallback AllowAll);yml 顶层 `agent.permission-policy: strict` 解析后 `cfg.permissionPolicy = "strict"` —— `PermissionPolicyRouter.resolve(cfg.permissionPolicy, cfg)` 自动命中 strict
**断言方式**:L1 Unit 1 case + L2 Slice 验证 `MinimalYamlParser` 解析 `permission-policy: strict` 后 `cfg.permissionPolicy == "strict"`

### AC-NN-8 — demo-product / demo-empty `policy: strict` 真生效

**Given** `demo-product/src/main/resources/application.yml` 顶层加 `permission-policy: strict` + `agent.tools.allow-list: [read_file, write_file, list_dir, bash_safe]`(`ProductTools` 4 个 `@Component` Tools)
**When** Spring Boot 启动 demo-product
**Then** `PermissionPolicyRouter.resolve("strict", cfg)` 返 `StrictPermissionPolicy`;Agent 调 `read_file` / `write_file` / `list_dir` / `bash_safe` 真过(`Decision.Allow`);调未声明 tool(如 `unknown_tool`)真被拒抛 `Decision.Deny` 含 `LINGS-P01`
**断言方式**:L3 黑盒 + 单元断言,验证 yml `permission-policy: strict` 真生效

### AC-NN-9 — `AllowAllPermissionPolicyProvider` 保留 + 默认 fallback 不变

**Given** `AllowAllPermissionPolicyProvider` `name="default"` + `priority=0`
**When** yml 不配 `permission-policy` 字段(或配 `permission-policy: default`)
**Then** `PermissionPolicyRouter.resolve("default", cfg)` 返 `AllowAllPermissionPolicy` —— **任何** tool call 仍 `Decision.Allow`(back-compat 默认行为不变,AC-01-2 零配置 Story #001 兼容)
**断言方式**:L1 Unit 2 case,`AllowAllPermissionPolicy` 行为不变 + `ApplicationContext` 验证 `permissionPolicyProvider_default` Bean 仍存在

### AC-NN-10 — R-13 mitigation (d) 依赖零增量

**Given** Story #029 引入 `StrictPermissionPolicy` + `StrictPermissionPolicyProvider` + `PermissionPolicyAutoConfiguration` + `PermissionErrorCodes` + `AgentConfig.ToolsConfig` 扩 2 字段 + `AgentConfig.permissionPolicy` 1 字段 + demo yml 切换
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#028` post-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;`banned-dependencies` enforcer 不 fail
**断言方式**:对照 `specs/028-sandbox-runtime-impl/` PR body 末尾的 `### R-13 dependency:tree 自查` 节(diff 只允许 timestamp 差异)
**预期 R-13 第 15 次 PASS 0 binary delta**

### AC-NN-11 — 全 600+ 测试 0 回归

**Given** Story #029 改造 `AgentConfig.ToolsConfig` 构造器签名(2 新字段)+ `AgentConfig` 顶层 `permissionPolicy` 1 新字段 + demo yml 切换
**When** 跑 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test`
**Then** 587 pre test + #029 新 case + demo-product 集成测试全过(0 fail / 0 error / 0 skipped);`AllowAllPermissionPolicyProvider` 0 改动 back-compat 守住;`PermissionPolicyRouter` 行为不变(只是多 Provider 模式 strict 命中)
**断言方式**:`mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 全模块无 fail

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| `PermissionPolicy.check()` 改成可配置 dynamic classloader 加载 | KISS,本期只落 `StrictPermissionPolicy` + `AllowAllPermissionPolicy` 2 实现;`PermissionPolicyProvider` SPI 已就位,plugin 可后续加 |
| `Decision.AskUser` 分支真接通用户交互 UI | dsh §4.7 L681-684 `Decision` 3 子类已落地(`AskUser` 含 `prompt` + `options`),但 `ApprovalGate` 真交互 UI(CLI / WebSocket / 其它)留 OQ-Future;`StrictPermissionPolicy` 本期只返 Allow / Deny 2 分支(简化 §4.7 真实现)|
| `ToolsConfig.allowList` / `denyList` 通配符 / 正则支持 | KISS,本期只支持精确字符串匹配(`List.contains`);wildcard / regex 留 OQ-Future §6.5 |
| `ToolsConfig.allowList` / `denyList` yml 解析 schema 校验(空字符串 / 含通配符字符报错)| KISS,本期只校验 `enabled` + byte caps > 0(§#019 已落 `validate()`),allow-list / deny-list 内容不做格式校验 |
| 多档 policy:`policy: trustless`(用户每次都确认) / `policy: read-only`(全局只读)| KISS,本期只落 `default`(allow-all) + `strict`(allow-list + deny-list);其他 policy 档 OQ-Future,plugin 可后续加 |
| `Sandbox.commandWhitelist` / `domainWhitelist` 集成进 `StrictPermissionPolicy` | dsh §5.5 L2168-2189 design intent 提了「集成 c.getSandbox().getCommandWhitelist() + domainWhitelist」,**但实际** Sandbox.commandWhitelist / domainWhitelist 是 runtime sandbox 关心(Story #028 已落地),与 Permission 决策**正交**;两者数据源不同(Sandbox 关心 fs / http / process 资源访问,Permission 关心 tool name),强行集成反而耦合 —— **本 Story 不集成**,留 OQ-Future §6.5 |
| `AgentConfig.Sandbox.policy` 字段真接通(目前仅文档字符串占位)| `AgentConfig.Sandbox.policy` 字段当前语义 = "PermissionPolicy name"(dsh §5622-5627 + demo-product yml L93 `policy: default`)—— **本 Story 顺手接通**:把 `AgentConfig.Sandbox.policy` 字段值 sync 到 `AgentConfig.permissionPolicy` 顶层字段,或在 `PermissionPolicyRouter.resolve()` 内 fallback 到 `cfg.getSandbox().getPolicy()` —— **实现选型**:选**新增顶层字段**(back-compat 不动 `Sandbox.policy` 文档说明 + 让 §5.2 SlotResolver 顶层字段对齐 6 Router 模式)|
| `LINGS-P02+` ErrorCode 启用 | 本期只开 P01;P02+ 留后续 Story(如 `LINGS-P02 PERMISSION_ASK_USER_TIMEOUT` / `LINGS-P03 PERMISSION_CONFIG_INVALID` 等) |
| 真实 `AskUser` 用户交互 UI(WebSocket / REST polling / CLI interactive)| OQ-Future,留 §14.6 graceful shutdown / §14.10 audit-log 后续 Story;本期 `Decision.AskUser` 类型就位但 `StrictPermissionPolicy` 不返 |
| `PermissionPolicyProvider` 多版本并存(`strict-1.0.0` + `strict-2.0.0`)| §5.2 多版本兼容已由 `SlotRouter.version()` 处理(§5.3.1.0),本期只落 `strict-1.0.0` |
| 删 `AllowAllPermissionPolicyProvider` | **保留**(`name="default"` + `priority=0`),作为默认 fallback + back-compat(§11 #3 宪章稳定性约束 + AC-01-2 零配置 Story #001 兼容)|

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#001` zero-config-bootstrap — `Agent` 4 final 字段 + `DefaultAgent.buildContext` 冻结语义 + `AllowAllPermissionPolicy` 默认 stub 落地
  - `#003` spi-slot-router — `SlotRouter<P,T>` 父类 + 多 Provider 模式 + `PermissionPolicyRouter` 隐式 Router 样板(§5.3.1.0)
  - `#004` tool-parallel-dispatch — `ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 `PermissionPolicy.check()` stub 当前直返 Allow(本 Story 真接通)
  - `#005` cancellation-token — `CancellationToken` 三层贯通(`ToolExecutionContext.cancellation()` 已通,本 Story 不动 cancellation)
  - `#007` yaml-hot-reload — `AgentConfigRegistry` AtomicReference + hot-reload 期间 cfg 冻结(本 Story 让 allow-list / deny-list hot-reload 真生效)
  - `#019` built-in-tools — `AgentConfig.ToolsConfig` 3 字段落地(`enabled` / `maxReadBytes` / `maxWriteBytes`,本 Story **扩 2 字段**:`allowList` + `denyList`)
  - **`#028` sandbox-runtime-impl** — `SandboxErrorCodes` precedent + `AccessDeniedException extends RuntimeException` 模式 + `ErrorCode 嵌入 message 模式 "[LINGS-S01] reason"` + `banned-dependencies` enforcer R-13 兜底 + 双层防线(sandbox runtime 限制 fs/http/process / Permission 限制 tool)单层失守到双层真生效
- **后续 Story(本 Story 是其前置)**:
  - `#016` audit-log(§14.10 N10)—— Permission 拒绝事件(`LINGS-P01`)接入审计流(已在 ROADMAP §14 N10 cross-ref 登记)
  - §14.2 RetryPolicy + §14.3 CircuitBreaker — Permission deny 后 retry / circuit breaker 触发
  - §14.6 graceful shutdown — Permission 真生效后,shutdown 钩子释放 audit 资源
  - §14.9 TenantContext ThreadLocal 多租户 Permission 路由 — allow-list / deny-list Tenant 隔离细粒度增强(AC-05 Permission 部分真生效)
  - OQ-Future `TrustlessPermissionPolicyProvider`(用户每次都确认,无白名单)—— `permission-policy: trustless` 切换

---

**Spec writer**: Claude Code
**Spec date**: 2026-09-30
**Spec version**: v0.1 Draft