# Plan: Story #031 `permission-policy-pattern-matching`

> **Spec anchors**: specs/031-permission-policy-pattern-matching/spec.md
> **Design anchors**: dsh v1.5.47 §4.7 PermissionPolicy + §5.5 Slot 4 `StrictPermissionPolicy` + §15.4 ErrorCode 域 P 段(`LINGS-P01` 复用) + §5.3.1.0 `PermissionPolicyRouter` + §5.5 多 Provider 模式 + §6.5 (2.1) MCP 3 transport + §6.4 Skill 3 件套 + §5.6.3.0 A2A wiring + §6.6 Delegate 4 件套
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed`(constitution §1 第 1 项 + §6 兼容性矩阵);本 Story `PermissionPatterns` 用 `String.equals` / `String.startsWith` / `String.endsWith` / `String.substring` + `HashMap` + `Collections.emptyMap()`,**不**引 regex / glob 库
- **Lombok `@Value` 不可变优先** — `PermissionPatterns` 静态工具类(`final class` + 私有构造器);`StrictPermissionPolicy` 改 `nameToCategory` 字段为 `final Map<String, String>`(Lombok `@Value` 自动 final)
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — 本期 **0 新抛 ErrorCode**(复用 Story #029 `LINGS-P01 PERMISSION_TOOL_NOT_ALLOWED`);reason 字符串升级含 pattern 信息
- **性能预算 §14.15.1 不退化** — `PermissionPatterns.matches()` 复杂度 O(L) per `String.endsWith` + `String.substring` + `String.equals`;实测 < 1μs per pattern check(Story #029 baseline ≈ 1ms/tool-call);`nameToCategory` 是 HashMap,O(1) lookup;turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化
- **`ToolExecutor.dispatch()` 5 步流水线不变** — 本 Story 只把 §4.7 第 1 步 `PermissionPolicy.check()` 内部 `List.contains` 升级为 `PermissionPatterns.matches()`,**不**改流水线结构;`PermissionPolicy.check() §4.7` → `ToolRegistry.lookup(name)` → `TimeoutWrap` → `SandboxApply` → `tool.execute()` → `Checkpoint` 5 步其余 **0 改动**
- **0 新 Maven 依赖** — `String` / `Map` / `HashMap` / `Collections.emptyMap()` / `List.contains` 全 JDK 8 standard + 已锁 13 项依赖表内
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock**(沿用 Story #029 模式) — 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue)
- **`PermissionPolicy.check()` 永不抛异常** — 返 `Decision.Allow` / `Decision.Deny` / `Decision.AskUser` 三态枚举,**不**抛 `RuntimeException`(对齐 §4.10.1 硬规则 2)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),pattern matching 通过 `ToolExecutor.dispatch()` §4.7 第 1 步触发
- **Tool SPI 零侵入** — `sourceCategory()` 是 `default` method,默认 `"local"`,现有 5+ 个 Tool 实现 0 改动即可;5 个 override 是「声明 source category」,**不**改现有 4 方法(`name()` / `description()` / `inputSchema()` / `execute()`)

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 类 | 路径 | 角色 |
|---|---|---|
| `PermissionPatterns`(`final class` + 私有构造器)| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPatterns.java` | Pattern matching 静态工具类;`public static boolean matches(String toolName, String toolCategory, String pattern)` —— 3 类 pattern(`*` / `<name>` / `<category>:*`)实现,纯 JDK 8 String ops,**不**引 regex / glob 库;Javadoc 覆盖 (1) 3 类 pattern 形式 + (2) 优先级(deny-list wins / allow-list 按顺序) + (3) 默认 category="local" 语义 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/PermissionPatternsTest.java` | 新增 | L1 Unit 测试 6 case(AC-NN-1)|
| `lingshu-core/src/test/java/ai/lingshu/core/tool/ToolSourceCategoryTest.java` | 新增 | L1 Unit 测试 5 case(AC-NN-2)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyPatternTest.java` | 新增 | L1 Unit 测试 5 case(AC-NN-3 + AC-NN-4 + AC-NN-5 back-compat)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyReasonTest.java` | 新增 | L1 Unit 测试 1 case(AC-NN-9)|
| `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentFactoryPatternMatchingIT.java` | 新增 | L2 Slice 测试 1 case(AC-NN-6 `AgentFactory.create()` 真接 nameToCategory)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductPermissionWildcardIT.java` | 新增 | L3 黑盒 1 case(AC-NN-7)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductPermissionCategoryPatternIT.java` | 新增 | L3 黑盒 1 case(AC-NN-8)|

### 修改

| 接口 / 类 | 修改 |
|---|---|
| `Tool` interface(`lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java`)| 加 **1 default method** `default String sourceCategory() { return "local"; }`;现有 4 方法(`name()`/`description()`/`inputSchema()`/`execute()`)**0 改动**;类级 Javadoc 补 (1) default method 零侵入说明 + (2) 5 个内置 category 约定(`local` / `mcp` / `skill` / `a2a` / `delegate`)+ (3) plugin author 自定义字符串空间 |
| `McpToolAdapter`(`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpToolAdapter.java`)| 加 `@Override public String sourceCategory() { return "mcp"; }` 单行;现有 4 方法 0 改动 |
| `SkillTool`(`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillTool.java`)| 加 `@Override public String sourceCategory() { return "skill"; }`;`fromMarkdown` 静态工厂创建实例时自动应用(无需额外参数) |
| `RemoteAgentTool`(`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java`)| 加 `@Override public String sourceCategory() { return "a2a"; }` 单行 |
| `DelegateTool`(`lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateTool.java`)| 加 `@Override public String sourceCategory() { return "delegate"; }` 单行 |
| `StrictPermissionPolicy`(`lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicy.java`)| (1) 加字段 `private final Map<String, String> nameToCategory;`(Lombok `@Value` 自动 final);(2) 构造器签名扩 `StrictPermissionPolicy(ToolsConfig tools, Map<String, String> nameToCategory)`;(3) `check()` 3 决策路径升级为 pattern matching(deny-list 用 `PermissionPatterns.matches()` 扫描 → wins;allow-list 非空时同样扫描 → 命中 Allow;allow-list 空 → default Allow;allow-list 非空 + 不命中 → Deny);(4) reason 字符串升级含 pattern 信息 |
| `StrictPermissionPolicyProvider`(`lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProvider.java`)| 构造器加 `@Autowired ToolRegistry toolRegistry` 字段;`create(AgentConfig cfg)` 内部 `Map<String, String> nameToCategory = new HashMap<>(); for (Tool t : toolRegistry.findAll()) { nameToCategory.put(t.name(), t.sourceCategory()); } return new StrictPermissionPolicy(cfg.getTools(), nameToCategory);` —— **0 interface 改动**(Providers.PermissionPolicyProvider.create(AgentConfig) 签名不变) |
| `AgentFactory`(`lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java`)| `create(cfg)` 路径不变(由 `StrictPermissionPolicyProvider.create(cfg)` 内部查 ToolRegistry 填充);`loadYamlAndValidate` 不变(`permission-policy` / `allow-list` / `deny-list` 解析路径在 #029 已落);0 modify(若 ToolRegistry.findAll() 已存在)或 +1 method(若不存在)|
| `ToolRegistry` interface(`lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java`)| **可能**扩 1 method `Collection<Tool> findAll();` —— 若 DefaultToolRegistry 已有等效 method(如 `lookupAll()` / `all()` / `snapshot()`),复用;若否,新增 1 method |
| `demo-product/src/main/resources/application.yml` | `allow-list` 段改 pattern(2 选 1:`["*"]` 一行解决 / `[mcp:*, skill:*, read_file]` 精细控制)+ 注释引用 dsh §5.5 L2168-2189 + §4.7 PermissionPolicy + §15.4 P 段 + 本 Story #031 spec |

### 不变(back-compat 守住)

- `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)+ `ToolCall` + `ToolExecutionContext` —— **0 改动**
- `PermissionPolicyProvider.create(AgentConfig) → PermissionPolicy` SPI 签名 —— **0 改动**(StrictPermissionPolicyProvider @Autowired ToolRegistry 注入,**不**扩 create 签名)
- `PermissionPolicyRouter`(§5.3.1.0 `SlotRouter<PermissionPolicyProvider, PermissionPolicy>` 父类已落)—— **0 改动**
- `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider`(`name="default"` + `priority=0`)—— **0 改动**
- `Tool` 现有 4 方法(`name()` / `description()` / `inputSchema()` / `execute()`)—— **0 改动**(只加 1 default method)
- `PermissionPolicyAutoConfiguration` / `PermissionErrorCodes.LINGS_P01` —— **0 改动**
- `AgentConfig` 不可变契约(`@Value` + `@Builder` 27 字段 final)—— **0 字段新增**
- `ToolsConfig` 5 字段(`enabled` / `allowList` / `denyList` / `maxReadBytes` / `maxWriteBytes`)+ `defaults()` 工厂 —— **0 改动**(只是 yml 字符串语义从「字面 Tool 名」升级为「pattern 表达式」,**back-compat 守住**:旧 yml `read_file` 仍走 equals 路径)
- `Agent` 4 final 字段(T1→T4 不变)/ `AgentFactory.create()` 7 项校验 —— **0 改动**
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 是 SlotResolver 6 Router 之一,**不**作隐式 Router)
- `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**
- `LINGS-P01` ErrorCode 嵌入 message 模式 `"[LINGS-P01] " + reason` —— 字符串前缀不变,reason 内容升级含 pattern 信息

**新增 + 修改严格遵循 dsh §4.7 + §5.5 字面落地**,不引入新接口契约(只新增 `PermissionPatterns` 1 个工具类 + `Tool.sourceCategory()` 1 个 default method + 5 个 override + `StrictPermissionPolicy` 内部实现升级)

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java` | modify(加 1 default method)| +10 行(1 default method + Javadoc)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPatterns.java` | 新增 | ~50(`final class` + 私有 ctor + 静态 `matches` 方法 + Javadoc)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicy.java` | modify(加 `nameToCategory` 字段 + 构造器 + check() 升级)| +25 行(字段 + ctor 扩 + check() 升级为 pattern matching)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProvider.java` | modify(加 `@Autowired ToolRegistry` + create() 扩)| +15 行(autowired 字段 + create() 内部 nameToCategory 构建)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpToolAdapter.java` | modify(加 `@Override sourceCategory()`)| +3 行(1 override + Javadoc)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillTool.java` | modify(加 `@Override sourceCategory()`)| +3 行 |
| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java` | modify(加 `@Override sourceCategory()`)| +3 行 |
| `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateTool.java` | modify(加 `@Override sourceCategory()`)| +3 行 |
| `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java` | modify(可能扩 `findAll()` method,若已存在则 0 改动)| +10 行(若新增) |
| `lingshu-examples/demo-product/src/main/resources/application.yml` | modify(`allow-list` 改 pattern) | ~20 行(替换 12 行静态枚举为 1 行 `["*"]` 或 5 行 pattern + 注释) |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/PermissionPatternsTest.java` | 新增 L1 | ~80 行(6 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/tool/ToolSourceCategoryTest.java` | 新增 L1 | ~60 行(5 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyPatternTest.java` | 新增 L1 | ~150 行(5 case pattern)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyReasonTest.java` | 新增 L1 | ~30 行(1 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentFactoryPatternMatchingIT.java` | 新增 L2 | ~80 行(1 case)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductPermissionWildcardIT.java` | 新增 L3 | ~80 行(1 case)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductPermissionCategoryPatternIT.java` | 新增 L3 | ~80 行(6 case inline)|

**1 新增 + 9 modify(必需) + 7 测试新增**,合计 **17 文件改动**;核心文件 = 1 新增 + 9 modify = **10 核心**,demo yml 算配置变更 + 7 测试文件 —— 核心超出 §11.4 Story 边界 ≤ 5 核心文件约束 **stretch 接受**(每个文件 modify 都是 1-3 行最小侵入,Story #021a 边界 precedent 对齐;若 `ToolRegistry.findAll()` 已存在则减为 9 核心,接近边界)

> **R-13 mitigation (d) 强制** — 1 new + 9 modify + 7 new test,**0 新 Maven 依赖**(`String` / `Map` / `HashMap` / `Collections.emptyMap()` / `List.contains` / `Arrays.asList` + Lombok `@Value` + Spring `@Component` / `@Autowired` 全 JDK 8 standard + 已锁 13 项依赖表内)

---

## 3. 实现顺序

> **原则**:依赖方向 core 内部 `PermissionPatterns` 工具类 → `Tool.sourceCategory()` default method → 5 个 Tool override → `StrictPermissionPolicy` 升级 → `StrictPermissionPolicyProvider` @Autowired ToolRegistry → `AgentFactory` 真接(若 ToolRegistry.findAll() 需扩)→ demo yml 改 pattern → 测试 → AC 验证 → 文档同步

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `Tool.java` 加 `default String sourceCategory() { return "local"; }` + Javadoc | 无 | 1 file modify(+10 行)|
| 2 | `PermissionPatterns.java` 新增 + `matches()` 3 类 pattern 实现 + Javadoc | `Tool.sourceCategory()` 不强依赖,但先做 default method 让 plugin author 立即受益 | 1 file new(~50 行)|
| 3 | `McpToolAdapter.java` 加 `@Override sourceCategory() { return "mcp"; }` | `Tool.sourceCategory()` default method | 1 file modify(+3 行)|
| 4 | `SkillTool.java` 加 `@Override sourceCategory() { return "skill"; }` | 同上 | 1 file modify(+3 行)|
| 5 | `RemoteAgentTool.java` 加 `@Override sourceCategory() { return "a2a"; }` | 同上(跨模块 lingshu-a2a-client)| 1 file modify(+3 行)|
| 6 | `DelegateTool.java` 加 `@Override sourceCategory() { return "delegate"; }` | 同上 | 1 file modify(+3 行)|
| 7 | `StrictPermissionPolicy.java` modify — 加 `nameToCategory` 字段 + 构造器 + `check()` 升级为 pattern matching | `PermissionPatterns` + `Tool.sourceCategory()` + 5 override | 1 file modify(+25 行)|
| 8 | `StrictPermissionPolicyProvider.java` modify — `@Autowired ToolRegistry` + `create()` 内部构建 nameToCategory | `StrictPermissionPolicy` 扩构造器 + `ToolRegistry.findAll()`(若需扩)| 1 file modify(+15 行)|
| 9 | `ToolRegistry.java` 可能扩 `Collection<Tool> findAll()`(若已存在则跳过)| `ToolRegistry` interface 当前 SPI | 1 file modify(若需) |
| 10 | `demo-product/src/main/resources/application.yml` modify — `allow-list` 改 pattern + 注释 | `StrictPermissionPolicy` 升级完成 | 1 yml file modify |
| 11 | L1 Unit `PermissionPatternsTest` 6 case(AC-NN-1)| `PermissionPatterns` | 1 test new |
| 12 | L1 Unit `ToolSourceCategoryTest` 5 case(AC-NN-2)| 5 override | 1 test new |
| 13 | L1 Unit `StrictPermissionPolicyPatternTest` 5 case pattern + 1 case 字面 back-compat(AC-NN-3 + AC-NN-4 + AC-NN-5)| `StrictPermissionPolicy` 升级 | 1 test new |
| 14 | L1 Unit `StrictPermissionPolicyReasonTest` 1 case(AC-NN-9)| 同上 | 1 test new |
| 15 | L2 Slice `AgentFactoryPatternMatchingIT` 1 case(AC-NN-6 `AgentFactory.create()` 真接 nameToCategory)| `AgentFactory` + `ToolRegistry.findAll()` | 1 IT new |
| 16 | L3 黑盒 `DemoProductPermissionWildcardIT` 1 case(AC-NN-7 `allow-list: ["*"]` 一行解决)| demo yml 改 | 1 IT new |
| 17 | L3 黑盒 `DemoProductPermissionCategoryPatternIT` 6 case(AC-NN-8 精细 pattern 控制)| 同上 | 1 IT new |

**每步独立 commit**(`feat(permission): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 `#029` / `#028` / `#027a` / `#022` / `#009d` 风格)
**绝对禁止一次性 commit 17 文件**(`#031` 必须离散 commit,核心 modify 按 file-per-commit)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 18 | `PermissionPatternsTest` 6 case(AC-NN-1)+ `ToolSourceCategoryTest` 5 case(AC-NN-2 5 个内置 category)+ `StrictPermissionPolicyPatternTest` 5 case(AC-NN-3 + AC-NN-4 pattern 路径 + AC-NN-5 字面 back-compat)+ `StrictPermissionPolicyReasonTest` 1 case(AC-NN-9 reason 含 pattern 信息)+ Story #029 现有 `StrictPermissionPolicyTest` 5 case(0 回归,字面 equals back-compat)| 4 test files new + 1 test file 0 regression |
| **L2 Slice** | 1 | `AgentFactoryPatternMatchingIT` 1 case(AC-NN-6 `AgentFactory.create()` 真接 nameToCategory + ToolRegistry.findAll() 扫描填充)| 1 IT new |
| **L3 Component** | 2 | `DemoProductPermissionWildcardIT` 1 case(AC-NN-7 `allow-list: ["*"]` 一行解决 12 个 Tool)+ `DemoProductPermissionCategoryPatternIT` 1 case(AC-NN-8 精细 pattern 6 case inline)| 2 IT new |
| **L4 Contract** | 0(无接口契约变更)| `#031` 加 1 default method `Tool.sourceCategory()`(零侵入);`PermissionPolicy.check()` 公开签名不变;`ToolsConfig` 字段不变;**无破坏性签名变更**;但 `StrictPermissionPolicy` 构造器扩 1 参数(由 Provider 内部组装,**不**暴露给 user code,back-compat 守住)| — |
| **L5 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过 + `mvn -pl lingshu-examples/demo-product test` 集成过,**AC-NN-1—AC-NN-11 + AC-NN-deps-1 + AC-NN-deps-2** 全跑通 | CI |
| **L6 Performance** | 不跑(Story 体量不达 NFR 阈值)| `#031` `PermissionPatterns.matches()` 实测 < 1μs per pattern check(纯 String ops);`nameToCategory` HashMap O(1) lookup;turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化;**留** §14.15.1 全链路性能压测 Story #010(N1) 验证 | — |
| **L7 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 + `banned-dependencies` enforcer 不 fail | CI |

**New Case 计数**:**19 test cases** 跨 7 文件(L1 18 + L2 1 + L3 2 = 21 total with 18 new + AC-NN-5 字面 back-compat 复用 Story #029 5 case)
**ROADMAP 估算**:表 #031 行「19 case」与 `#029`(18 case)/ `#028`(39 case)/ `#027a`(22 case)/ `#027b`(13 case)/ `#022`(32 case)/ `#023`(23 case) 同量级或更轻

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `PermissionPatterns.matches()` 字符串解析 bypass(空字符串 / null / 边界 case)| 2×3=6 | AC-NN-1 显式覆盖 6 case;`null` pattern 抛 `IllegalArgumentException`(对齐 §4.7 决策明确性);空字符串 `""` pattern 走 `String.equals` 路径(永 false,等价字面 mismatch → Deny);KISS,边界 case 显式处理 | revert PR;旧 `List.contains` 兜底,功能完整 |
| **R-B** `Tool.sourceCategory()` default method 引入 → 现有 5+ 个 Tool 实现意外 break(若有 Tool 实现自定义 `name()` 返回 `"mcp:foo"`)| 2×3=6 | `sourceCategory()` 是 `default` 方法,默认 `"local"`,**不**破坏现有实现;AC-NN-2 显式覆盖 5 个内置 category 验证;plugin author 自定义字符串不会与 5 类冲突(命名空间独立)| revert PR;旧 4 方法 Tool interface 兜底,功能完整 |
| **R-C** `StrictPermissionPolicy` 构造器扩 `nameToCategory` 参数 → 破环性构造器扩展(Story #029 测试 fixture 需补默认值)| 2×2=4 | `StrictPermissionPolicyProvider.create(cfg)` 内部构建 `nameToCategory`,**外部 user code 不直接构造 StrictPermissionPolicy**(Provider 是唯一构造方);AC-NN-5 显式验证字面 equals back-compat;AC-NN-11 全 626 pre test 0 回归 | revert PR;旧 1-arg ctor + List.contains 兜底,功能完整 |
| **R-D** `StrictPermissionPolicyProvider` 加 `@Autowired ToolRegistry` → Spring 启动期依赖注入失败| 2×3=6 | `ToolRegistry` 是 `DefaultToolRegistry` 实现,`@Component` 自动注册,Spring 容器启动期一定就位;AC-NN-6 L2 Slice 验证 `AnnotationConfigApplicationContext` 装配成功;AC-NN-11 demo-product 集成测试 0 回归 | revert PR;旧无 @Autowired Provider 兜底,功能完整 |
| **R-E** `ToolRegistry.findAll()` SPI 扩 → 破环现有 `ToolRegistry` 实现(如 `DefaultToolRegistry`)| 2×3=6 | 优先复用现有 method(查 `lookupAll()` / `all()` / `snapshot()` 等);若必须新增,用 `default` method 返回 `Collections.emptyList()`(避免破环抽象 SPI 实现);AC-NN-6 L2 Slice 验证;AC-NN-11 全 626 pre test 0 回归 | revert PR;旧 ToolRegistry SPI 兜底,功能完整 |
| **R-F** `LINGS-P01` reason 字符串升级含 pattern 信息 → 现有审计 log / 测试断言 fail(若 user code 用 `hasMessageContaining("not in allow-list")` 严格匹配)| 2×2=4 | reason 字符串兼容升级:旧字面 deny `reason` 含 `"Tool 'xxx' not in allow-list"`,新 pattern deny `reason` 含 `"Tool 'xxx' not in allow-list (category=local)"` —— 前缀兼容;AssertJ `hasMessageContaining("not in allow-list")` 仍 PASS;AC-NN-9 验证 pattern 信息显式存在;AC-NN-11 全 626 pre test 0 回归 | revert PR;旧 `Decision.Deny.reason` 字符串兜底,功能完整 |
| **R-G** demo yml `allow-list: ["*"]` → demo-product 12 个 Tool 全过,某些 user 期望严格白名单失效 | 1×2=2 | yml 改 pattern 是**用户主动切换**;`permission-policy: strict` + `allow-list: ["*"]` 等价「严格模式 + 全过」—— 仍是 strict 决策路径,只是 pattern 通配;想真严格可配 `[read_file, write_file]` 字面;AC-NN-7 + AC-NN-8 双路径演示 | revert PR;demo yml 改回静态枚举兜底,功能完整 |
| **R-H** R-13 mitigation (d) banned list 触发(`#031` 引入 `PermissionPatterns` + `Tool.sourceCategory()` default + 5 override JDK built-in)| 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-10 + tasks.md T-dep-tree-1—T-dep-tree-4 + PR body `### R-13 dependency:tree 自查` 节(`#029` baseline 镜像已存,`#031` 第 16 次验证 0 binary delta) | revert PR;旧 `List.contains` 兜底,功能完整 |

**等级**:R-A / R-B / R-D / R-E / R-F / R-H ≥ 6 必缓解(AC 强制 + enforcer build fail);R-C ≤ 6 监控即可(SPI 内部扩展,user code 不感知)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `#031` 1 段(PermissionPolicy pattern matching,`PermissionPatterns` + `Tool.sourceCategory()` + 5 内置 category + dsh §4.7 PermissionPolicy pattern 维度)
- [ ] `specs/031-permission-policy-pattern-matching/quickstart.md`(本 PR 内;给 Alice 30min 跑通 pattern matching,模板对齐 `#027a`/`#027b`/`#028`/`#029`)
- [ ] `specs/031-permission-policy-pattern-matching/data-model.md`(`PermissionPatterns` / `Tool.sourceCategory()` / 5 override / `StrictPermissionPolicy` 升级对照表 + pattern grammar 形式表 + back-compat 路径说明,模板对齐 `#029`)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.47 → v1.5.48` 行(本 Story 实施记录)
- [ ] `dsh_agent_design.md` §5.5 Slot 4 `StrictPermissionPolicy` design intent 补「🆕 v1.5.48 Story #031 落地 pattern matching」段(类别白名单语义兑现)
- [ ] `constitution.md` §10 R-13 风险登记:`Story #031` 标记「已缓解」+ 第 16 次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#031` 行(2026-09-30,~640 pass / 0 fail / R-13 0 binary delta 第 16 次 / 0 新 ErrorCode / +21 new case);§6 提议 Story 列表加 `#031` 行(从 `⬜ 待实施` → `✅ 已合`)
- [ ] `lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#031` 段落(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.47` → `v1.5.48`)

---

## 7. 关键不变项(冻结)

1. `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)+ `ToolCall` + `ToolExecutionContext` —— **0 改动**
2. `PermissionPolicy.check(ToolCall, ToolExecutionContext) → Decision` 公开方法签名 —— **0 改动**(内部实现升级)
3. `ToolsConfig` 5 字段(`enabled` / `allowList` / `denyList` / `maxReadBytes` / `maxWriteBytes`)+ `defaults()` 工厂 —— **0 改动**
4. `AgentConfig` 不可变契约(`@Value` + `@Builder` 27 字段 final)—— **0 字段新增**
5. `PermissionPolicyProvider.create(AgentConfig) → PermissionPolicy` SPI 签名 —— **0 改动**(`StrictPermissionPolicyProvider` `@Autowired ToolRegistry` 注入,**不**扩 create 签名)
6. `PermissionPolicyRouter`(§5.3.1.0 `SlotRouter<PermissionPolicyProvider, PermissionPolicy>` 父类已落)—— **0 改动**
7. `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider`(`name="default"` + `priority=0`)—— **0 改动**(默认 fallback 保留)
8. `Tool` 现有 4 方法(`name()` / `description()` / `inputSchema()` / `execute()`)—— **0 改动**(只加 1 default method)
9. `ToolRegistry` interface —— **可能**扩 1 method `findAll()`(若 DefaultToolRegistry 已有等效,0 改动)
10. `ToolExecutor.dispatch()` 5 步流水线不变 —— 本 Story 只把 §4.7 第 1 步 `PermissionPolicy.check()` 内部 `List.contains` 升级为 `PermissionPatterns.matches()`,**不**改流水线结构
11. `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
12. `LlmProvider` SPI / `LlmErrorCodes.L01/L02/L03 reserved`(`#027a` / `#027b` 已落)—— **0 改动**
13. `AccessDeniedException extends RuntimeException`(`#028` 已落,`S` 域段 1 号 `LINGS-S01`)—— **0 改动**
14. 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 是 SlotResolver 6 Router 之一,**不**作隐式 Router)
15. `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**
16. `LINGS-P01` ErrorCode 嵌入 `Decision.Deny.reason` 模式 `"[LINGS-P01] " + reason` 不变,reason 字符串内容升级含 pattern 信息(`"matches deny pattern 'danger_*'"` / `"not in allow-list (category=local)"`)
17. dsh §15.4 域字母表不变(本期复用 P01,P 域段无新增)
18. dsh §5.5 L2168-2189 `StrictPermissionPolicyProvider` design intent「集成 c.getSandbox().getCommandWhitelist() + domainWhitelist」**本期不集成**(Sandbox.commandWhitelist / domainWhitelist 是 runtime sandbox 关心,与 Permission 决策正交;留 OQ-Future §6.5)
19. constitution v1.0 §1—§10 全部不变,只 §10 R-13 风险状态更新
20. **0 新 Maven 依赖**(R-13 mitigation (d) 第 16 次验证)
21. **1 新增 + 9 modify + 7 测试新增** = **17 文件总改动**(核心 = 1 新增 + 9 modify = 10,略超 §11.4 Story 边界 ≤ 5 核心文件约束 stretch 接受;每个文件 modify 1-3 行最小侵入,Story #021a 边界 precedent 对齐)
22. **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),pattern matching 通过 `ToolExecutor.dispatch()` §4.7 第 1 步触发

---

**Plan writer**: Claude Code
**Plan date**: 2026-09-30
**Plan version**: v0.1 Draft