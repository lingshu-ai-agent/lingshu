# Plan: Story #029 `permission-policy-impl`

> **Spec anchors**: specs/029-permission-policy-impl/spec.md
> **Design anchors**: dsh v1.5.46 §4.7 PermissionPolicy(`PermissionPolicy.check()` + `Decision` 4 子类)+ §5.5 Slot 4 `StrictPermissionPolicyProvider` L2168-2189 design intent("集成 c.getSandbox().getCommandWhitelist() + domainWhitelist 默认白名单")+ §4.10.1 硬规则 2(`ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 PermissionPolicy.check 当前 stub)+ §15.4 ErrorCode 域(本期启用 **P 段 1 号** = `LINGS-P01`)+ §5.3.1.0 `PermissionPolicyRouter`(SlotResolver 内 6 Router 之一)+ §5.5 多 Provider 模式(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`,v1.5.28)+ §13 changelog v1.5.46 行 + constitution v1.0 + ROADMAP §6 主链漏项补救(2026-09-30 加 #029 行)
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `Set.of` / `record` / `sealed`(constitution §1 第 1 项 + §6 兼容性矩阵);本 Story `StrictPermissionPolicy` 用 `Collections.emptyList()` + `Arrays.asList(...)` 替代 `List.of(...)`
- **Lombok `@Value` 不可变优先** — `StrictPermissionPolicy`(`tools` 字段 final `ToolsConfig`)+ `StrictPermissionPolicyProvider`(`name()` / `priority()` / `version()` final 隐式);`PermissionErrorCodes` 常量类(`String LINGS_P01` final)
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — 本期 **1 新抛 ErrorCode** `LINGS-P01 PERMISSION_DENIED`(P = Permission 域 **1 号**,**新 ErrorCode 域启用** = dsh §15 域字母表 **9 → 10** `C/S/L/T/X/R/A/M/Z` 9 + **`P` = 10**,对齐 `#028 LINGS-S01` + `#023 LINGS-D01` + `#022 LINGS-T08` + `#027b LINGS-L03 reserved` precedent);错误码嵌入 `Decision.Deny.reason` 模式 `"[LINGS-P01] " + reason`(注意:`Decision.Deny` 是 `@Value` 不可变,reason 是 String,**不**抛异常,§4.10.1 硬规则 2 ToolExecutor.execute 永不抛,Deny 走 `ToolResult.error` 兜底)
- **性能预算 §14.15.1 不退化** — PermissionPolicy.check() 真实现开销 ≤ 1ms/tool-call(2 次 `List.contains` O(n),n=白/黑名单长度 ≤ 100 实际);turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化
- **`ToolExecutor.dispatch()` 5 步流水线不变** — 本 Story 只把 §4.7 第 1 步 `PermissionPolicy.check()` 从 stub 直返 Allow 变成 strict 真查表;`PermissionPolicy.check() §4.7 本 Story 真实现` → `ToolRegistry.lookup(name)` → `TimeoutWrap` → `SandboxApply(fs / http / process) §4.7 第 4 步`(`#028` 真实现)→ `tool.execute()` → `Checkpoint` 5 步流水线其余 4 步 **0 改动**
- **0 新 Maven 依赖** — `List.contains` + `Collections.emptyList()` + `Arrays.asList(...)` + `Lombok @Value` + `Spring @Component` / `@AutoConfiguration` + `AssertJ` / `Mockito` 全 JDK 8 standard + 已锁 13 项依赖表内
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock**(沿用 `#028` 模式) — 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue)
- **`PermissionPolicy.check()` 永不抛异常** — 返 `Decision.Allow` / `Decision.Deny` / `Decision.AskUser` 三态枚举,**不**抛 `RuntimeException`(对齐 §4.10.1 硬规则 2 ToolExecutor.execute 永不抛);`Decision.Deny.reason` 嵌入 `[LINGS-P01]` 错误码前缀让 `assertThat(decision.getReason()).contains("LINGS-P01")` 工作
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),Permission 真实现通过 `ToolExecutor.dispatch()` §4.7 第 1 步触发,**不走 ChatClient 自动执行**

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 接口 / 常量类 | 路径 | 角色 |
|---|---|---|
| `StrictPermissionPolicy implements PermissionPolicy`(`@Component`)| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicy.java` | Slot 4 strict 实现;构造器 `StrictPermissionPolicy(AgentConfig.ToolsConfig tools)` 读 `tools.getAllowList()` + `tools.getDenyList()`(`@Value` 不可变契约字段);`check()` 3 决策路径(allow-list 不含 → Deny / deny-list 含 → Deny / 都空或 allow-list 含且 deny-list 不含 → Allow);Javadoc 覆盖 (1) §4.10.1 硬规则 2 流水线串联 + (2) 3 决策路径 + (3) ErrorCode 嵌入 message 模式 |
| `StrictPermissionPolicyProvider implements Providers.PermissionPolicyProvider`(`@Component`)| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProvider.java` | Slot 4 strict Provider;`name()="strict"` + `priority()=10` + `version()="1.0.0"`(对齐 #003 SPI 契约);`create(AgentConfig c)` 返 `new StrictPermissionPolicy(c.getTools())` |
| `PermissionPolicyAutoConfiguration`(`@AutoConfiguration`)| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPolicyAutoConfiguration.java` | Slot 4 strict Provider 注册;`@Bean(name="permissionPolicyProvider_strict-1.0.0") public PermissionPolicyProvider strictPermissionPolicyProvider()` 返 `new StrictPermissionPolicyProvider()`;对齐 §5.5 多 Provider 模式(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)|
| `PermissionErrorCodes` 常量类 | `lingshu-core/src/main/java/ai/lingshu/core/permission/PermissionErrorCodes.java` | `public static final String LINGS_P01 = "LINGS-P01";` + 行内注释引用 §15.4 ErrorCode 域 **P 段 1 号** + §4.7 PermissionPolicy + 对齐 #028 LINGS-S01 模式 |

### 修改

| 接口 / 类 | 修改 |
|---|---|
| `AgentConfig.ToolsConfig`(`@Value` 不可变)| (1) 扩 2 字段 `List<String> allowList` + `List<String> denyList`(字段位置:`enabled` → `allowList` → `denyList` → `maxReadBytes` → `maxWriteBytes`,allow-list / deny-list 紧贴 `enabled` 之后语义聚合);(2) `defaults()` 工厂方法同步扩:`new ToolsConfig(true, Collections.emptyList(), Collections.emptyList(), 200_000, 1_000_000)`;(3) `validate()` 方法**不**扩(allow-list / deny-list 字符串内容不做格式校验 —— 留作 §6.5 OQ-Future,本期只校验 `enabled` + byte caps > 0)|
| `AgentConfig`(`@Value` 不可变)| 扩 1 顶层字段 `String permissionPolicy`(默认 `"default"`);字段位置:对齐 `toolExecutor` / `compactor` / `sessionStore` / `a2aTransport` 顶层字段模式;`@Value` 构造器位置:**最末**(避免破坏现有 `ToolsConfig` 字段顺序)|
| `lingshu-examples/demo-product/src/main/resources/application.yml` | 顶层加 `permission-policy: strict` + `agent.tools.allow-list: [read_file, write_file, list_dir, bash_safe]`(`ProductTools` 4 个 `@Component` Tools);注释引用 dsh §5.5 L2168-2189 + §4.7 PermissionPolicy |
| `lingshu-examples/demo-empty/src/main/resources/application.yml` | 顶层加 `permission-policy: strict`(无 tool 注册,演示 deny 路径)|

### 不变(back-compat 守住)

- `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)+ `ToolCall` + `ToolExecutionContext` —— **0 改动**
- `PermissionPolicyRouter`(`SlotRouter<PermissionPolicyProvider, PermissionPolicy>` 父类已落,§5.3.1.0)—— **0 改动**(只是多 Provider 模式 strict 命中,SlotRouter.resolve(name, cfg) 行为不变)
- `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider`(`name="default"` + `priority=0`)—— **0 改动**(默认 fallback 保留,back-compat 守住 AC-01-2 零配置 Story #001 兼容)
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 `PermissionPolicy.check()` —— **0 改动**(只是 check() 内从 stub 直返 Allow 变成 strict 真查表 —— `ToolExecutor` 不感知内部策略)
- `Agent` 4 final 字段(T1→T4 不变)/ `AgentFactory.create()` 7 项校验 —— **0 改动**
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 是 SlotResolver 6 Router 之一)
- `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**(Sandbox 字段是 runtime sandbox 关心,Permission 字段是 Tool-level 决策,两表正交)
- `SandboxErrorCodes.LINGS_S01` + `AccessDeniedException extends RuntimeException`(`#028` 已落)—— **0 改动**

**新增 + 修改严格遵循 dsh §4.7 + §5.5 字面落地**,不引入新接口契约(只新增 `StrictPermissionPolicy` 1 个实现 + `StrictPermissionPolicyProvider` 1 个 SPI + `PermissionErrorCodes` 1 个常量类 + `PermissionPolicyAutoConfiguration` 1 个注册类)

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicy.java` | 新增 | ~60(`@Component` + 构造器 + `check()` 3 决策路径 + Javadoc)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProvider.java` | 新增 | ~30(`@Component` + 4 方法(name / priority / version / create)实现 + Javadoc 对齐 #003 SPI 契约)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPolicyAutoConfiguration.java` | 新增 | ~30(`@AutoConfiguration` + `@Bean(name="permissionPolicyProvider_strict-1.0.0")` 返 `new StrictPermissionPolicyProvider()`)|
| `lingshu-core/src/main/java/ai/lingshu/core/permission/PermissionErrorCodes.java` | 新增 | ~10(`LINGS_P01` 常量 + 注释引用 §15.4 P 段 1 号)|
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | modify | `ToolsConfig` 扩 2 字段 + `defaults()` 同步 + 顶层 `permissionPolicy` 1 字段 + 构造器位置调整 = ~10 行 modify(60 处引用方不破,因为新字段有默认值)|
| `lingshu-examples/demo-product/src/main/resources/application.yml` | modify | 顶层 `permission-policy: strict` + `agent.tools.allow-list: [...]` = ~10 行 modify(注释引用 dsh §5.5 + §4.7)|
| `lingshu-examples/demo-empty/src/main/resources/application.yml` | modify | 顶层 `permission-policy: strict` = ~2 行 modify(演示 deny 路径)|

**4 新增 + 3 modify(必需)**,合计 **7 文件改动**,严格 ≤ 5 核心文件边界 **超 2 文件**(demo yml 算配置变更不算核心 + AgentConfig 算 1 核心)— 实际核心文件 = 4 新增 + 1 modify(AgentConfig)= **5 核心**,严格在 §11.4 Story 边界 ≤ 5 核心文件 + ≤ 3 ErrorCode 约束内(本期 1 新抛 ErrorCode)

> **R-13 mitigation (d) 强制** — 4 new files + 3 modify,**0 新 Maven 依赖**(`List.contains` + `Collections.emptyList()` + `Arrays.asList` + `Lombok @Value` + `Spring @Component` / `@AutoConfiguration` 全 JDK 8 standard + 已锁 13 项依赖表内)

---

## 3. 实现顺序

> **原则**:依赖方向 core 内部 `PermissionErrorCodes` 基础常量 → `StrictPermissionPolicy` 实现 → `StrictPermissionPolicyProvider` SPI → `PermissionPolicyAutoConfiguration` 注册 → `AgentConfig.ToolsConfig` 扩字段 → `AgentConfig.permissionPolicy` 顶层字段 → demo yml 切换 → 测试 fixture → 测试 → AC 验证 → 文档同步

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `PermissionErrorCodes` 常量类 + `LINGS_P01` + 注释 | 无 | 1 文件可编译 |
| 2 | `StrictPermissionPolicy implements PermissionPolicy`(`@Component`)+ 构造器 + `check()` 3 决策路径 + Javadoc | `PermissionErrorCodes` + `PermissionPolicy` + `Decision` + `ToolCall` + `ToolExecutionContext` + `AgentConfig.ToolsConfig` | 1 文件可编译 |
| 3 | `StrictPermissionPolicyProvider implements Providers.PermissionPolicyProvider`(`@Component`)+ 4 方法 + Javadoc 对齐 #003 SPI 契约 | `StrictPermissionPolicy` + `AgentConfig` | 1 文件可编译 |
| 4 | `PermissionPolicyAutoConfiguration`(`@AutoConfiguration`)+ `@Bean(name="permissionPolicyProvider_strict-1.0.0")` 返 `new StrictPermissionPolicyProvider()` | `StrictPermissionPolicyProvider` | 1 文件可编译 |
| 5 | `AgentConfig.ToolsConfig` modify — 扩 2 字段 `allowList` + `denyList` + `defaults()` 同步 | `ToolsConfig` 当前 `@Value` 不可变契约 | 1 文件 modify |
| 6 | `AgentConfig` modify — 顶层扩 1 字段 `permissionPolicy`(默认 `"default"`)+ 构造器位置最末 | `ToolsConfig` 修改完成 | 1 文件 modify |
| 7 | `demo-product/src/main/resources/application.yml` modify — 顶层 `permission-policy: strict` + `agent.tools.allow-list: [...]` + 注释引用 dsh §5.5 + §4.7 | `AgentConfig.permissionPolicy` 顶层字段就位 + `ProductTools` 4 个 `@Component` Tools 已知 | 1 yml 文件 modify |
| 8 | `demo-empty/src/main/resources/application.yml` modify — 顶层 `permission-policy: strict`(演示 deny 路径)| `AgentConfig.permissionPolicy` 顶层字段就位 | 1 yml 文件 modify |
| 9 | L1 Unit 测试 `StrictPermissionPolicyTest` 5 case(AC-NN-1 4 case + AC-NN-2 1 case)| `StrictPermissionPolicy` | 1 test 文件 |
| 10 | L1 Unit 测试 `StrictPermissionPolicyProviderTest` 2 case(AC-NN-3 name + priority + version + Spring Bean name)| `StrictPermissionPolicyProvider` | 1 test 文件 |
| 11 | L1 Unit 测试 `PermissionErrorCodesTest` 1 case(AC-NN-4 LINGS_P01 常量值)| `PermissionErrorCodes` | 1 test 文件 |
| 12 | L2 Slice 测试 `PermissionPolicyRouterStrictIT` 4 case(AC-NN-5 `PermissionPolicyRouter.resolve("strict", cfg)` 真命中 strict + `resolve("default", cfg)` back-compat 返 AllowAll + `cfg.permissionPolicy="strict"` 自动按 cfg 路由 + `cfg.permissionPolicy="unknown"` fallback 到 default)| `PermissionPolicyAutoConfiguration` + `AllowAllPermissionPolicyProvider` + `AnnotationConfigApplicationContext` 装配 | 1 IT 文件 |
| 13 | L1 Unit 测试 `ToolsConfigAllowDenyListTest` 2 case(AC-NN-6 `ToolsConfig.defaults()` 兼容 + 新字段 accessors)| `AgentConfig.ToolsConfig` 修改后 | 1 test 文件 |
| 14 | L3 黑盒 `DemoProductPermissionStrictIT` 2 case(AC-NN-8 demo-product 切 strict + allow-list 真过 / 未声明 tool 真拒;AC-NN-9 back-compat `permission-policy: default` AllowAll 不变)| `demo-product/application.yml` 修改 + `ProductTools` | 1 IT 文件 |
| 15 | L3 黑盒 `DemoEmptyPermissionStrictIT` 1 case(AC-NN-8 demo-empty 切 strict 无 tool 注册 → 任何 tool 调 deny) | `demo-empty/application.yml` 修改 | 1 IT 文件 |

**每步独立 commit**(`feat(permission): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 `#028` / `#027a` / `#022` / `#009d` 风格)
**绝对禁止一次性 commit 7+ 文件**(`#029` 必须离散 commit)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 10 | `StrictPermissionPolicyTest` 5 case(AC-NN-1 4 case allow-list 不含 deny / allow-list 含 allow / deny-list 含 deny / 两表都含 deny / AC-NN-2 1 case 两表都空 default allow)+ `StrictPermissionPolicyProviderTest` 2 case(AC-NN-3 name + priority + Spring Bean name 验证)+ `PermissionErrorCodesTest` 1 case(AC-NN-4 LINGS_P01 常量值)+ `ToolsConfigAllowDenyListTest` 2 case(AC-NN-6 `ToolsConfig.defaults()` 兼容 + 新字段 accessors)| 4 test 文件 |
| **L2 Slice** | 4 | `PermissionPolicyRouterStrictIT` 4 case(AC-NN-5 `PermissionPolicyRouter.resolve("strict", cfg)` 真命中 strict + `resolve("default", cfg)` back-compat 返 AllowAll + `cfg.permissionPolicy="strict"` 自动按 cfg 路由 + `cfg.permissionPolicy="unknown"` fallback 到 default — `AnnotationConfigApplicationContext` 装配 `PermissionPolicyAutoConfiguration` + `AllowAllPermissionPolicyProvider`)| 1 IT 文件 |
| **L3 Component** | 3 | `DemoProductPermissionStrictIT` 2 case(AC-NN-8 demo-product 切 strict + allow-list 真过 / 未声明 tool 真拒)+ AC-NN-9 back-compat `permission-policy: default` AllowAll 不变 + `DemoEmptyPermissionStrictIT` 1 case(AC-NN-8 demo-empty 切 strict 无 tool 注册 → 任何 tool 调 deny)| 2 IT 文件 |
| **L4 Contract** | 0(无接口契约变更)| `#029` 新增 `StrictPermissionPolicy` 1 个**新**实现 + `StrictPermissionPolicyProvider` 1 个**新**Provider;`PermissionPolicy` / `Decision` / `ToolCall` / `ToolExecutionContext` 0 改动;`PermissionPolicyRouter` 0 改动;**无破坏性签名变更**;但 `AgentConfig.ToolsConfig` 构造器签名 +2 字段 + `AgentConfig` 顶层 `permissionPolicy` 1 字段(破环性构造器扩展,所有构造方需补默认值 —— 由 `@Value` Lombok 自动生成 all-args ctor 统一管理,back-compat 通过新字段默认值守住)| — |
| **L5 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过 + `mvn -pl lingshu-examples/demo-product test` 集成过,**AC-NN-1—AC-NN-11 + AC-NN-deps-1 + AC-NN-deps-2** 全跑通 | CI |
| **L6 Performance** | 不跑(Story 体量不达 NFR 阈值)| `#029` PermissionPolicy.check() 真实现开销 ≤ 1ms/tool-call(2 次 `List.contains` O(n),n=白/黑名单长度 ≤ 100 实际)— §14.15.1 性能预算不退化;**留** §14.15.1 全链路性能压测 Story #010(N1) 验证 | — |
| **L7 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 + `banned-dependencies` enforcer 不 fail | CI |

**New Case 计数**:**17 test cases** 跨 7 文件(L1 10 + L2 4 + L3 3 = 17)
**ROADMAP 估算**:表 #029 行「7 文件 + 1 ErrorCode」 → 17 cases 与 `#028`(17 cases)/ `#027a`(22 cases)/ `#027b`(13 cases)/ `#022`(32 cases)/ `#023`(23 cases) 同量级

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `StrictPermissionPolicy.check()` allow-list / deny-list 检查绕过(`toolName.toLowerCase()` 等大小写绕过)| 2×3=6 | AC-NN-1 显式覆盖 allow-list 不含 deny + deny-list 含 deny + allow-list 含 allow 4 case;`toolName` 用 `ToolCall.name()` 原样比对(`List.contains` 严格 equals,**不**做 toLowerCase / trim 转换);KISS,大小写敏感 | revert PR;旧 `AllowAllPermissionPolicy` 全过兜底,功能无安全 |
| **R-B** `AgentConfig.ToolsConfig` 扩 2 字段破环性构造器扩展(所有构造方需补默认值)| 3×2=6 | `ToolsConfig` 当前唯一构造方是 `AgentConfig.@Value all-args ctor`(Lombok 自动生成),Lombok 编译期自动处理新字段;`defaults()` 工厂方法同步扩 → 默认空列表 → back-compat 允许所有;AC-NN-6 + AC-NN-9 显式验证 back-compat;AC-NN-11 全 587 pre test 0 回归 | revert PR;旧 ToolsConfig 3 字段兜底,功能完整 |
| **R-C** `AgentConfig.permissionPolicy` 顶层字段破环性构造器扩展 + `MinimalYamlParser` 未解析 yml 顶层 `permission-policy:` → 报 yml schema 错| 2×3=6 | `MinimalYamlParser` 当前 yml 解析路径已支持 `agent.<kebab-case>` → `AgentConfig.<camelCase>` 字段映射;新增 `permissionPolicy` 字段对齐 `toolExecutor` / `compactor` / `sessionStore` / `a2aTransport` 顶层字段模式;AC-NN-7 显式验证 yml 解析;AC-NN-11 demo-product / demo-empty 集成测试 0 回归 | revert PR;旧无 `permissionPolicy` 字段兜底,功能完整 |
| **R-D** `PermissionPolicyRouter.resolve("strict", cfg)` SlotRouter 行为破环(多 Provider 模式 strict 命中失败)| 2×3=6 | `PermissionPolicyRouter.resolve(name, cfg)` 父类 `SlotRouter.resolve` 已支持多 Provider 模式(§5.3.1.0);新增 `permissionPolicyProvider_strict-1.0.0` Bean 后,§5.2 SlotRouter 按 `name()` 路由自然命中 strict;AC-NN-5 L2 Slice 验证 `router.resolve("strict", cfg)` 真返 `StrictPermissionPolicy`;AC-NN-11 全 587 pre test 0 回归 | revert PR;旧 `AllowAllPermissionPolicyProvider` 单 Provider 兜底,功能完整 |
| **R-E** `LINGS-P01` ErrorCode 嵌入 `Decision.Deny.reason` 模式破环现有 Decision.Deny 序列化 / 反序列化 | 1×2=2 | `Decision.Deny` `@Value` 不可变 + `reason` String 字段,本期新增 `[LINGS-P01] reason` 字符串内容前缀(语义聚合);`Decision` 4 子类构造器签名不变;AC-NN-4 显式验证 reason 字符串前缀;AC-NN-11 全 587 pre test 0 回归 | revert PR;旧 `Decision.Deny` 构造器签名兜底,功能完整 |
| **R-F** R-13 mitigation (d) banned list 触发(`#029` 引入 `StrictPermissionPolicy` + `StrictPermissionPolicyProvider` 等 JDK built-in)| 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-10 + tasks.md T-dep-tree-1—T-dep-tree-4 + PR body `### R-13 dependency:tree 自查` 节(`#028` baseline 镜像已存,`#029` 第 15 次验证 0 binary delta) | revert PR;旧 `AllowAllPermissionPolicyProvider` 单 Provider 兜底,功能无安全 |
| **R-G** demo-product / demo-empty yml 切换 strict 后现有测试 fail(`StrictPermissionPolicy` 默认 fallback `priority=10` 胜出覆盖 `AllowAllPermissionPolicyProvider.priority=0` 默认行为)| 2×3=6 | AC-NN-9 back-compat 显式覆盖:`permission-policy: default` 仍 `AllowAllPermissionPolicy`,只 `permission-policy: strict` 才 strict;demo yml 切换显式标注 `permission-policy: strict`(用户主动切换,back-compat 路径 `permission-policy: default` 不动);AC-NN-11 全 587 pre test 0 回归 + demo-product 集成测试 0 回归 | revert PR;demo yml 改回 `permission-policy: default` 兜底,功能完整 |

**等级**:R-A / R-D / R-E / R-F / R-G ≥ 6 必缓解(AC 强制 + enforcer build fail);R-B / R-C ≤ 6 监控即可(Lombok + MinimalYamlParser 已成熟,back-compat 默认值兜底)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `#029` 1 段(PermissionPolicy 真实现,`StrictPermissionPolicy` + `StrictPermissionPolicyProvider` 落地 + `LINGS-P01 PERMISSION_DENIED` 启用 + §4.7 PermissionPolicy 模板真接通 + §15.4 域字母表 9 → 10 新增 P 域)
- [ ] `specs/029-permission-policy-impl/quickstart.md`(本 PR 内;给 Alice 30min 跑通 hello world strict policy,模板对齐 `#027a`/`#027b`/`#028`)
- [ ] `specs/029-permission-policy-impl/data-model.md`(`StrictPermissionPolicy` / `StrictPermissionPolicyProvider` / `PermissionPolicyAutoConfiguration` / `PermissionErrorCodes` 4 核心类型对照表 + `LINGS-P01` ErrorCode 域表 + allow-list × deny-list × 3 决策路径映射表,模板对齐 `#027a`/`#027b`/`#028`)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.46 → v1.5.47` 行(本 Story 实施记录)
- [ ] `dsh_agent_design.md` §15.4 ErrorCode 域字母表加 **`P = Permission 域 LINGS-P01`** 行(新域段启用,域字母 **9 → 10**:原 `C/S/L/T/X/R/A/M/Z` 9 + `P` = 10)+ §15.11 P 段序号表 1 号 `LINGS-P01 PERMISSION_DENIED`;§5.5 Slot 4 `StrictPermissionPolicyProvider` 模板 L2168-2189 design intent 兑现注释补「🆕 v1.5.47 Story #029 落地」
- [ ] `constitution.md` §4 域字母表加 `P = Permission` 行 + `LINGS-P01 PERMISSION_DENIED` + §10 R-13 风险登记:`Story #029` 标记「已缓解」+ 第 15 次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#029` 行(2026-09-30,600+ pass / 0 fail / R-13 0 binary delta 第 15 次 / +LINGS-P01 / +17 case)
- [ ] `ROADMAP.md` §15.4 ErrorCode 域表同步 `LINGS-P01` + `P = Permission` 域
- [ ] `lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#029` 段落(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.46` → `v1.5.47`)

---

## 7. 关键不变项(冻结)

1. `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)+ `ToolCall` + `ToolExecutionContext` —— **0 改动**
2. `PermissionPolicyRouter`(`SlotRouter<PermissionPolicyProvider, PermissionPolicy>` 父类已落,§5.3.1.0)—— **0 改动**(只是多 Provider 模式 strict 命中,SlotRouter.resolve(name, cfg) 行为不变)
3. `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider`(`name="default"` + `priority=0`)—— **0 改动**(默认 fallback 保留,back-compat 守住 AC-01-2 零配置 Story #001 兼容)
4. `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 §4.7 第 1 步 `PermissionPolicy.check()` —— **0 改动**(只是 check() 内从 stub 直返 Allow 变成 strict 真查表 —— `ToolExecutor` 不感知内部策略)
5. `Agent` 4 final 字段(T1→T4 不变)/ `AgentFactory.create()` 7 项校验 —— **0 改动**
6. `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
7. `LlmProvider` SPI / `LlmErrorCodes.L01/L02/L03 reserved`(`#027a` / `#027b` 已落)/ `AnthropicLlmProvider` 6-arg ctor + `buildRequestBody` 协议转换 + `parseResponse` fallback — **0 改动**
8. `AccessDeniedException extends RuntimeException`(`#028` 已落,`S` 域段 1 号 `LINGS-S01`)—— **0 改动**(注意:`#029` Permission 域 P 段 1 号 与 Sandbox 域 S 段 1 号 **不冲突**,两段独立命名空间)
9. 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 是 SlotResolver 6 Router 之一,**不**作隐式 Router)
10. `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**(Sandbox 字段是 runtime sandbox 关心,Permission 字段是 Tool-level 决策,两表正交;Story #028 runtime sandbox 与 Story #029 Permission policy 双层防线各管各)
11. `AgentConfig.ToolsConfig` 当前 3 字段(`enabled` / `maxReadBytes` / `maxWriteBytes`)—— **扩 2 字段**(`allowList` + `denyList`);字段位置:`enabled` → `allowList` → `denyList` → `maxReadBytes` → `maxWriteBytes`(allow-list / deny-list 紧贴 `enabled` 之后,语义聚合);`defaults()` 同步扩 → back-compat 默认空列表允许所有
12. `AgentConfig` 顶层扩 1 字段 `String permissionPolicy`(默认 `"default"`);字段位置:**最末**(对齐 `toolExecutor` / `compactor` / `sessionStore` / `a2aTransport` 顶层字段模式)
13. `StrictPermissionPolicy` / `StrictPermissionPolicyProvider` / `PermissionPolicyAutoConfiguration` / `PermissionErrorCodes` 4 文件都是**新增**(不修改任何现有契约,只新增契约 + 默认实现)
14. dsh §15.4 域字母表新增 **`P = Permission`** 域段(本期启用 P01,域字母表 **9 → 10**:`C/S/L/T/X/R/A/M/Z` 9 + `P` = 10);P01 = `PERMISSION_DENIED`;P02+ reserved 占位(留后续 Story)
15. dsh §5.5 L2168-2189 `StrictPermissionPolicyProvider` design intent「集成 c.getSandbox().getCommandWhitelist() + domainWhitelist」**本期不集成**(Sandbox.commandWhitelist / domainWhitelist 是 runtime sandbox 关心,与 Permission 决策正交;留 OQ-Future §6.5)
16. constitution v1.0 §1—§10 全部不变,只 §4 P 域段启用 + §10 R-13 风险状态更新
17. **0 新 Maven 依赖**(R-13 mitigation (d) 第 15 次验证)
18. **5 处核心修改 + 4 处核心新增** = **9 文件总改动**(7 文件清单已列:4 new + 3 modify;但 AgentConfig 2 处 modify(ToolsConfig + 顶层 permissionPolicy)+ 2 demo yml 修改;实际 #029 主要改动文件 7 个:4 new + 3 modify)— 符合 §11.4 Story 边界 ≤ 5 核心文件 + ≤ 3 ErrorCode(本 Story 1 新抛 ErrorCode,严格 ≤ 3)
19. **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),Permission 真实现通过 `ToolExecutor.dispatch()` §4.7 第 1 步触发,**不走 ChatClient 自动执行**

---

**Plan writer**: Claude Code
**Plan date**: 2026-09-30
**Plan version**: v0.1 Draft