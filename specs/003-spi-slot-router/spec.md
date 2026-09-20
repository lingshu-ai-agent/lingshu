# Feature Specification: Story #003 spi-slot-router

**Feature Branch**: `story-003-spi-slot-router`

**Created**: 2026-09-20

**Status**: Draft

**Input**: User description: "Story #003 spi-slot-router — Provider.version() + SlotRouter 兼容性校验 (AC-02/08)"

**Source Design Doc**: `dsh_agent_design.md` v1.5.34 §0.4 AC-02 + AC-08 / §5.1 typed-Provider(8 行)/ §5.2 SlotRouter 父类(契约 + 同名竞争)/ §5.3 SlotResolver(8 Router 屏蔽)/ §5.3.1.0 7 隐式 Router concrete / §5.5 默认 Provider stub(Slot 1—7 9 行总表)/ §5.6.4 SPI 总表 9 行 / §10.1 13 项依赖 / §15 错误码 / §17 R-04 A2A v0.5 演进

**Constitution**: `.specify/memory/constitution.md` v1.0 — §1 #8 Provider 模式 + #9 Spring Boot SPI + #10 同名 Provider 处理 + #12 启动期校验 / §2 13 项依赖锁定 / §4 `LINGS-S01/S05` Slot 域 / §10 R-04 A2A 演进兼容 + R-13 Spring AI 误用

**对应 AC**: **AC-02**(SPI 多 Provider 兼容性校验 §0.4 L91-99)+ **AC-08**(Slot 接口版本兼容 §0.4 L122-130)

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Provider.version() 字段 + 启动期版本自描述 (Priority: P1)

作为 **Charlie(框架贡献者)**,我在写新 Provider(例如 `AnthropicLlmProvider`)时,**只需**在 `@Component public class AnthropicLlmProviderProvider implements LlmProviderProvider` 里实现一个 `String version()`,返回 `"1.0.0"`(或当前契约版本),Agent 启动时**自动**把 `name="anthropic"` + `version="1.0.0"` + `priority=10` 打到启动日志里 —— 这样企业运维(Alice)排查"线上跑哪个版本、谁覆盖了谁"时,直接 grep 日志就能定位,**无需**打开 jar 反编译 / 查 git tag。

**Why this priority**: 这是 Story #003 的**契约层**地基。Provider 自带 version() 是 §5.1 typed-Provider 列表中已有但 #001 stub 没写的字段;一旦加上,后续 SlotRouter 兼容性校验(US2)/ 跨 Provider 同名比较(US3)/ A2A AgentCard schemaVersion(Story #009 复用) 都依赖这个字段。**缺它** Story #009 A2A 的 `version()` 兼容(R-04)就找不到 single-source-of-truth,只能让 Provider 在第 9 个 Slot 临时硬编码;**等于**把版本字段强绑死在 A2A 上,后续其他 8 Slot 也只能各自维护一份重复字段,**违背** §1 #8 Provider 模式"用统一抽象管 9 Slot"的初衷。

**Independent Test**: 在 `lingshu-core` 加一个测试 JVM 启动日志断言 `AgentFactoryIntegrationTest#startup_logListsProviderNameAndVersion` —— 启动一个空 yml 的 Agent,捕获 stdout / logback,断言日志含 `✓ [LlmProvider] anthropic v1.0.0 (priority=10)` 9 行(9 Slot 默认 Provider 全部打印)。

**Acceptance Scenarios**:

1. **Given** classpath 注册 9 个默认 Provider 全部带 `version()` 返回 `"1.0.0"`(LlmProvider / ToolExecutor / Sandbox / SkillSource / SessionStore / Compactor / PromptBuilder / FlowEngine / A2aTransport)
   **And** yml 完全空(只 `spring.application.name=lsh-spi-test`)
   **When** Spring 启动
   **Then** 启动日志输出 9 行 `✓ [<SlotName>] <name> v<version> (priority=<n>)`,格式固定、字段顺序固定、字段值从 Provider 调用 3 个 getter 实时读
   **And** 每行同时打到 stdout(INFO 级)+ logback file(若配)
   **And** `factory.create(defaultConfig())` 启动期校验全部 9 行解析成功,**不**抛异常

2. **Given** 某个 Provider 没实现 `version()`(返回 null)
   **When** SlotRouter 启动期收集
   **Then** 启动 fail-fast,抛 `IllegalStateException("Provider <FQN> must implement version() returning non-null semver, got null")`,错误码 `LINGS-S05 PROVIDER_INIT_FAILED`,JVM 退出 1
   **And** stderr 输出 ERROR 级日志,带 Provider FQN 行号

3. **Given** 某个 Provider 返回 `version()` 不是合法 semver(如 `"v1"` / `"1.0"` / `"latest"`)
   **When** SlotRouter 启动期收集
   **Then** 启动 fail-fast,抛 `IllegalArgumentException("Provider <FQN> version() 'X' must be semver MAJOR.MINOR.PATCH")`,错误码 `LINGS-S05`,**不**接受前缀 v / 单段 / 多段

---

### User Story 2 — SlotRouter 启动期兼容性校验 + fail-fast (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我希望在 classpath 里同时存在 `AnthropicLlmProvider v2.5.0`(我自己编译的,基于 lingshu 1.0)+ lingshu-core 自带的 `AnthropicLlmProvider v1.0.0`(Story #003 落地的默认实现)时,Agent 启动**直接报错**并明确指出"`AnthropicLlmProvider` 自带版本 1.0.0 与 Slot `LlmProvider` 契约版本 2.0.0 不兼容(major 跨版本)",**不会**让"看起来配 yml 跑通了但运行时 NoSuchMethodError / ClassCastException" —— 这样线上事故从"运行中崩"前移到"启动期 fail",影响窗口缩到 0(部署阶段就被拦)。

**Why this priority**: 这是 Story #003 的**核心交付**(AC-02 + AC-08 双覆盖)。§0.4 L91-99 + L122-130 显式要求"启动期 SPI 兼容校验",与 §17 R-04(A2A 协议 v0.5 演进兼容)同源 —— Story #003 是 R-04 缓解措施的**前置**:版本字段统一 + 校验逻辑就位,Story #009 才能在 A2aTransport 上做"协议版本协商"。**缺它** Provider SPI 是"扁平 name 空间",任何 Provider 实现都能塞进任意 Slot,**违背** §1 #12 启动期校验硬约束,后续每次 Provider 升级都是线上"午夜惊雷"。

**Independent Test**: 在 `lingshu-core/src/test/.../impl/router/SlotRouterCompatTest` 加 3 个黑盒用例 —— (a) 同 major version 1.0.0 vs 1.5.3 视为兼容,启动成功;(b) 不同 major version 1.0.0 vs 2.0.0 视为不兼容,启动失败 LINGS-S05;(c) Provider version 缺失 → 启动失败 LINGS-S05。

**Acceptance Scenarios**:

1. **Given** lingshu-core `LlmProvider` 契约版本声明为 `"2.0.0"`(Slot 接口 `@since` 注解或 `Provider<LlmProvider, LlmProviderConfig>` 父类常量)
   **And** 用户 plugin `MyLlmProviderProvider` 实现 `version()` 返回 `"1.5.3"`
   **When** Agent 启动 + yml `agent.llm.name=my-llm`
   **Then** `SlotRouter.resolve("LlmProvider", "my-llm", cfg)` 校验通过(`1.x` 与契约 `2.x` 同 major,Provider 版本 ≤ 契约版本,允许 minor 滞后),启动日志打印 `✓ [LlmProvider] my-llm v1.5.3 compatible with slot v2.0.0`
   **And** `agent.run("hello")` 正常进入 ReAct 循环

2. **Given** 用户 plugin `MyLlmProviderProvider` 实现 `version()` 返回 `"2.0.0"`(Provider 与契约 major 一致但 minor 超前)
   **When** Agent 启动
   **Then** 启动期**报错**(Provider 版本超出契约范围 = 可能用了契约未声明的方法),抛 `IllegalStateException("LlmProvider provider 'my-llm' v2.0.0 > slot contract v2.0.0 (provider uses APIs not yet declared)")`,错误码 `LINGS-S05`
   **And** JVM 退出 1,**不**进入业务循环

3. **Given** 用户 plugin `MyLlmProviderProvider` 实现 `version()` 返回 `"3.0.0"`(major 跨版本)
   **When** Agent 启动
   **Then** 启动期**报错**,抛 `IllegalStateException("LlmProvider provider 'my-llm' v3.0.0 incompatible with slot contract v2.0.0 (major version mismatch)")`,错误码 `LINGS-S05`
   **And** stderr ERROR 级日志 + JVM 退出 1

4. **Given** 用户 plugin `MyLlmProviderProvider` 实现 `version()` 返回 `"2.0.0"`(契约 major 一致 + minor 相等)
   **When** Agent 启动
   **Then** 启动通过(精确匹配视为最高优先级兼容)

---

### User Story 3 — SlotRouter 解析时同时报告 name + version + compatible (Priority: P2)

作为 **Dave(框架 SRE)**,我在排查"`agent.llm.name=anthropic` 但线上跑出来是 OpenAI 模型"这种诡异问题时,**只需** `factory.create(cfg).description()` 返回一行 `"LlmProvider=anthropic v1.0.0 (compatible with slot v2.0.0)"` —— 因为 §5.2 同名竞争规则下,两个 Provider 可能同时声明 `name="anthropic"`(用户 plugin 覆盖默认),debug 阶段必须一眼看到**实际被选中的**那个 + 它的版本,而不只是"name"。

**Why this priority**: 与 US1 / US2 相比是"运维可见性"增强,不影响功能正确性。但 §0.4 AC-02 黑盒要求"启动日志列出所有 Provider 与冲突覆盖关系"(对应 §1 #10 同名 Provider 处理规则),**Story #003 是首次按此规则落地的窗口** —— 之前 Story #001 / #002 的启动日志只打印"resolved N provider(s)",**没**逐个列出 name + version,debug 阶段需要 grep 一堆散落的 log 行;Story #003 把"启动期 self-describe"作为标配。

**Independent Test**: 在 `AgentFactoryIntegrationTest` 加 `factory.create(defaultConfig()).description()` 返回字符串断言 —— 含 9 行 `Slot=<name> v<version>` + 头部 `AgentFactory v<X.Y.Z> for JVM <java.version>` + 末尾 `Turn=0 Session=<id>`。

**Acceptance Scenarios**:

1. **Given** 9 个默认 Provider 全部带 `version()` 返回 `"1.0.0"` + 各自 `name()`
   **When** `factory.create(defaultConfig()).description()` 调用
   **Then** 返回字符串含 9 行 `  <SlotName>: <name> v<version> (priority=<n>)`,字段顺序与启动日志一致,**便于**粘到 issue / Slack 截图
   **And** `description()` 是**只读 + 同步**方法,不抛异常,不读取文件,不调 LLM

2. **Given** yml 配置 `agent.llm.name=anthropic` + classpath 还有 `OpenAiLlmProviderProvider`(name="openai")
   **When** `factory.create(cfg).description()` 调用
   **Then** 输出 `LlmProvider: anthropic v1.0.0 (priority=10, resolved)` + 其他 8 Slot 各一行,**openai** Provider 在 9 行里以 `LlmProvider: openai v1.0.0 (priority=0, not selected)` 形式标注(说明存在但没被选)

3. **Given** 同名 Provider 冲突(`AnthropicLlmProviderProvider` 默认 + 用户 plugin 也叫 `name="anthropic"` priority=10)
   **When** 启动期 SlotRouter 收集
   **Then** 日志输出 `[LlmProvider] conflict: user-plugin v2.0.0 wins over default v1.0.0 (same priority=10, first-loaded wins)`(具体策略细节在 §5.2,**不**由 Story #003 新增规则,**仅**让日志如实打印既有规则的结果)

---

### Edge Cases

- **version() 返回 null**(US1 Scenario 2):LINGS-S05 启动 fail,**不**fallback 到 `"unknown"`
- **version() 字符串大小写**:`"1.0.0"` vs `"1.0.0-RC1"`(semver 预发布)—— v1 不支持预发布后缀,任何含 `-` / `+` 的 version 视为非法,LINGS-S05 fail-fast(semver 预发布留 v2)
- **Provider 列表为空**:9 Slot 全部无 Provider → AgentFactory 启动 fail,`LINGS-S01 SLOT_NOT_FOUND`(Story #001 已定义,本 Story 复用)
- **契约版本 vs 真实 jar 版本不一致**:用户 plugin 编译时基于 lingshu-core 1.5,但运行时装了 lingshu-core 2.0 —— 这是 `SlotResolver` 启动期反射 `Slot` 接口 `@since` 常量,与 Provider 自报 `version()` 比较 —— **不**依赖运行时 jar manifest / class 版本号,**只**看 Java 源码常量
- **Plugin A 依赖 Provider B 的 version() 字段但 B 还没升级**:Plugin A 启动 fail-fast(B 没 version() 方法,NoSuchMethodError → LINGS-S05 包装),**不**做运行时反射兜底
- **Provider version 与 Slot 契约版本完全相同**(精确匹配):视为最高优先级兼容,**不**做 minor 比较
- **Provider version minor 滞后**(契约 2.5.0 / Provider 2.0.0):视为兼容,**不**报错(Story #003 兼容策略 = backward-compat within major,即 Provider minor 可以落后于 Slot minor,但不能超前)
- **Provider version minor 超前**(契约 2.0.0 / Provider 2.5.0):视为不兼容,启动 fail(Provider 可能用了 Slot 还没声明的方法)
- **同一 Slot 多个 Provider 全部 priority=0 + name 不同**:Router 按 yml `agent.<slot>.name` 选 1 个,其余标 `not selected` 不报 error(§1 #10 + §5.2 既有规则)

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 **MUST** 在 `Provider<P, C>` 父接口增加 `String version()` 抽象方法(§5.1 typed-Provider 列表),返回 semver `MAJOR.MINOR.PATCH` 三段非负整数(如 `"1.0.0"`),**禁止**前缀 `v` / 单段 / 含 `-` / 含 `+` / 空字符串 / null
- **FR-002**: 系统 **MUST** 为 9 个 Slot 接口(`LlmProvider` / `Tool` / `ToolExecutor` / `Sandbox` / `SkillSource` / `SessionStore` / `Compactor` / `PromptBuilder` / `FlowEngine` / `A2aTransport`)各自声明契约版本常量 `String CONTRACT_VERSION = "1.0.0"`(public static final,源码常量,**不**读 jar manifest),Provider `version()` 与对应 Slot 契约版本比较时按 Slot 类型分派
- **FR-003**: 系统 **MUST** 在 `SlotRouter<P, T>` 父类构造期收集所有 Provider 时,逐个校验 (a) `version()` 非 null 且合法 semver;(b) `version()` 与 Slot 契约版本**兼容**(同 major + Provider minor ≤ 契约 minor;若 minor 相等则 patch ≤ 契约 patch);任何一项不满足 → 启动 fail-fast,错误码 `LINGS-S05 PROVIDER_INIT_FAILED`
- **FR-004**: 系统 **MUST** 在 AgentFactory 启动日志按 `[<SlotName>] <name> v<version> (priority=<n>)` 格式逐个打印 N×9 行(N = 该 Slot 实际注册的 Provider 数),并在 `factory.create(cfg).description()` 返回同步字符串(含 9 行)
- **FR-005**: 系统 **MUST** 在 SlotRouter 解析某个 yml 指定的 `name` 时,同时校验**被选中的那个 Provider 的 version() 兼容**(即使所有 Provider 收集时已校验,运行时若 yml 改了 `agent.<slot>.name` 指向未收集的 Provider → 二次校验,防止 yml 引用 classpath 不存在的 Provider)
- **FR-006**: 系统 **MUST** 在 AgentFactory 启动期 fail-fast 时,stderr 输出 ERROR 级日志 + 抛 `IllegalStateException` 带 `errorCode="LINGS-S05"` 字段 + cause chain(原 IllegalArgumentException);**不**静默吞异常
- **FR-007**: 系统 **MUST** 在 Story #001 已有的 9 个默认 Provider stub(`DefaultPromptBuilderProvider` / `AnthropicLlmProviderFactory` / `DefaultToolExecutorProvider` / `StrictPermissionPolicyProvider` / `FileSessionStoreProvider` / `TruncatingCompactorProvider` / `LinearTurnEngineProvider` / `HttpJsonRpcA2aTransportProvider` / 第 9 个 Skill Source Provider)全部补上 `version()` 返回 `"1.0.0"` + `priority()` 返回 Story #001 既定值,**不**改其他方法签名
- **FR-008**: 系统 **MUST** 提供 `Version` 工具类(`ai.lingshu.core.spi.Version`),静态方法 `parse(String) → int[3]` / `isCompatible(String providerVer, String slotContractVer) → boolean` / `format(int[3]) → String`;semver 解析失败抛 `IllegalArgumentException`,**不**返回 null / Optional
- **FR-009**: 系统 **MUST** 复用 Story #001 的 7 项 AgentFactory 启动校验清单(MemorySourceRouter 间接持有,**不**进 AgentFactory 直接校验);`SlotRouter` 父类暴露 `List<String> describe()` 方法,AgentFactory 在 create() 后调一次打日志
- **FR-010**: 系统 **MUST** 在 9 个 Slot 的 `Provider.version()` 全部声明 `"1.0.0"` 时,**不**引入任何新依赖(R-13 mitigation (d) 硬约束),`mvn dependency:tree` 与 Story #002 baseline diff = 0;`semver` 校验手写 ~30 行,**不**引 `com.github.zafarkhaja:jsemver` 等三方库

### Key Entities

- **Provider<P, C>** (§5.1 typed-Provider):Slot SPI 实现工厂接口;v1.0.0 时方法 = `name()` + `priority()` + `version()`(🆕 Story #003) + `create(C)`;返回 `P`(具体 Slot 实现)
- **Slot** (9 个接口总称):每个接口内声明 `public static final String CONTRACT_VERSION = "1.0.0"`,由 SlotRouter 父类启动期反射读取
- **Version**(ai.lingshu.core.spi.Version):semver 解析 / 比较 / 格式化工具类;不可变 `@Value`(3 int 数组);方法全部 static + 纯函数
- **SlotRouter<P, T>** (§5.3.1.0 父类):构造期调 `validateProviderVersions(providers, slotContractVersion)` 二次校验 + `startupLog(providers)` 打印;新增 `describe()` 同步返回 9 行字符串
- **ProviderInitException**(§15 错误码):`LINGS-S05 PROVIDER_INIT_FAILED`,继承 `IllegalStateException`,带 `errorCode` 字段 + `cause` chain + 可选 `hint`(人话建议,如 `"hint: implement version() returning '1.0.0' on your @Component class"`)

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: **AC-02 + AC-08 黑盒可断言**:`SlotRouterCompatTest` 覆盖 6 个场景 —— (a) Provider version 完全匹配契约 → 通过;(b) Provider version minor 滞后 → 通过;(c) Provider version minor 超前 → fail LINGS-S05;(d) Provider version major 跨版本 → fail LINGS-S05;(e) Provider version null → fail LINGS-S05;(f) Provider version 非 semver 格式(`"v1"` / `"latest"`) → fail LINGS-S05;全部断言通过
- **SC-002**: **9 个默认 Provider 全部带 version()**:`AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion` 启动空 yml Agent,断言 stdout / logback 含 9 行 `✓ [<SlotName>] <name> v1.0.0 (priority=<n>)`,顺序与 §5.5 SPI 总表一致
- **SC-003**: **`factory.create(cfg).description()` 输出格式可单元断言**:`AgentFactoryDescriptionTest` 验证 description() 返回字符串含 9 行 + 头部 `AgentFactory v` + `JVM <java.version>` 段 + 末尾 `Turn=0 Session=`,全部行可被 AssertJ `containsSubsequence` 断言
- **SC-004**: **`LINGS-S05` 错误码链路完整**:`ProviderInitExceptionTest` 验证 (a) errorCode 字段 = "LINGS-S05";(b) cause chain 长度 ≥ 2;(c) hint 字段非空且含"implement version()"字样;(d) stderr 输出 ERROR 级日志(用 logback `ListAppender` 抓)
- **SC-005**: **`Version` 工具类单元覆盖**:`VersionTest` 覆盖 10 场景 —— parse 正常 3 种 / parse 异常 7 种(null / 空 / 前缀 v / 单段 / 4 段 / 含 `-` / 含非数字字符)/ isCompatible 6 种组合(同 major minor ±/ patch ±)/ format 3 种(0.0.0 / 1.2.3 / 10.20.30)
- **SC-006**: **R-13 mitigation (d) Spring AI 误用自查**:`mvn -pl lingshu-core dependency:tree -Dverbose` 输出**不**含 banned-dependencies 列表任何条目,与 Story #002 baseline diff = 0(本 Story 不引新依赖,Version 工具类手写 ~30 行);binary size < 35MB 且相对 Story #002 delta < 10%
- **SC-007**: **mvn validate + lingshu-core compile + test 全过**:`mvn -pl lingshu-core test` exit 0,新增 `Version` + `ProviderInitException` + `SlotRouter` 兼容校验方法 + 9 个默认 Provider `version()` 实现 + 4 个新测试类 编译过 + 跑过(无 @Component 循环依赖,无 NoSuchMethodError)
- **SC-008**: **PR body 含 R-13 自查 + AC-02/08 黑盒输出**:`### R-13 dependency:tree 自查` 节贴关键子树 + `### AC-02 / AC-08` 节贴 6 场景测试输出 + LINGS-S05 错误码现场截图 / 文本

---

## Assumptions

- **semver 简化版**:v1 **只**支持严格 3 段 `MAJOR.MINOR.PATCH`(非负整数,**不**支持预发布 `-RC1` / build metadata `+sha` / 前缀 `v`);完整 semver 2.0.0 留 v2 Story
- **契约版本声明位置**:9 Slot 接口**各自**在 `public static final String CONTRACT_VERSION = "1.0.0"`;**不**用 `@since` 注解(JDK 8 `@since` 在运行时反射麻烦,**且** javadoc-only),**不**读 jar manifest(`Implementation-Version` 依赖打包配置,**不可移植**)
- **兼容策略 = backward-compat within major**:Provider minor ≤ Slot minor(允许滞后);Provider minor > Slot minor → 不兼容(可能用了未声明的方法);精确匹配 = 最高优先级兼容;major 不同 → 不兼容;此策略与 Java 自身 SPI 演进习惯一致
- **Provider version 全部声明 `"1.0.0"`**:本 Story 落地时 9 Slot 默认 Provider 全部统一报 `"1.0.0"`(因为 Slot 契约也是 1.0.0);后续 Slot 演进到 1.1.0 / 2.0.0 时,Provider 才升级 version 字段
- **校验时机**:启动期(AgentFactory.create() 内,**不**在每个 turn 校验);版本不变 = 启动一次 OK,后续运行不再校验
- **`describe()` 是同步 + 只读**:不抛异常,不读文件,不调 LLM,不持有可变状态;可在 log 抓取 / health endpoint 多次调用
- **同名 Provider 优先级**:**不**改 Story #001 既定规则(§1 #10 priority 胜出 + 同 priority first-loaded wins);本 Story **只**让日志如实打印既有规则的**结果**,**不**新增规则
- **错误码 `LINGS-S05` 是新增**(Story #001 没有);Story #003 引入 `LINGS-S05 PROVIDER_INIT_FAILED` 1 个新 ErrorCode,符合 constitution §11 #4 "≤ 3 个 ErrorCode" 约束
- **`@AutoConfiguration` 默认 Provider stub 落地**:Story #001 的 `DefaultPromptBuilderProvider` / `AnthropicLlmProviderFactory` / `DefaultToolExecutorProvider` / `StrictPermissionPolicyProvider` / `FileSessionStoreProvider` / `TruncatingCompactorProvider` 等都从"throw `UnsupportedOperationException` stub"升级为"返回真实实例 + version()=1.0.0";本 Story **不**实现具体业务逻辑(那是 #002 已做 PromptBuilder / Story #004 ToolExecutor / Story #014 SessionStore 等的活),**只**补 `version()` 方法 + 简单 `create()` body(返回 `new XxxImpl()`)
- **不引 semver 三方库**:~30 行手写解析,无 transitive 风险;Story #003 **不动** `pom.xml`(除非要新增 lingshu-examples 子模块,本 Story 也不新增)

---

## Out of Scope(Story #003 不做,留给后续 Story)

- ❌ **完整 semver 2.0.0 预发布 / build metadata 支持**(`-RC1` / `+sha.abc`)→ v2 Story(本 Story 只 strict 3 段)
- ❌ **Provider hot-reload 时重校验 version** → Story #007 yaml-hot-reload 一起做
- ❌ **Provider 版本升级迁移工具**(自动改 Provider 源码 + 提 PR)→ v2
- ❌ **A2A 协议版本协商**(`A2aTransport.submit()` 携带 protocolVersion)→ Story #009(Slot 9 复用本 Story 的 version() 字段,业务协商逻辑在 #009 落地)
- ❌ **Provider 运行时热升级版本**(`Provider.version()` 返回动态值如 Git SHA)→ v2(本 Story version() 是常量)
- ❌ **Plugin 自描述能力**(Provider 返回 `List<String> capabilities()`)→ v2
- ❌ **Provider 版本与 Maven artifact version 联动**(`Implementation-Version` manifest 读取)→ v2(本 Story 用 Java 源码常量,**不**依赖打包)
- ❌ **跨 Slot 的版本协调**(所有 9 Slot 必须同时升级)→ v2(本 Story 9 Slot 各自独立维护版本,允许 Slot 1 v1.0.0 + Slot 2 v2.0.0 同时运行)
- ❌ **`lingshu-examples/demo-spi-version` 黑盒示例**(手动造一个 v2.0.0 Provider 触发 fail)→ v2(本 Story 只用单元测试覆盖,不动 examples)

---

## 参考章节(dsh v1.5.34 + constitution v1.0)

| 主题 | 来源 |
|---|---|
| 9 Slot × 默认 Provider 总表 | dsh §5.5 L2266-2278 |
| Slot 1—7 默认 Provider AutoConfiguration stub | dsh §5.5 L2118-2128 + L2240-2262 |
| Slot 9 HttpJsonRpcA2aTransportProvider stub | dsh §5.6.3.1 |
| typed-Provider 列表 8 行 | dsh §5.1 L1463+ |
| SlotRouter 父类契约 + 同名竞争 | dsh §5.2 L1545-1620 |
| 7 隐式 Router concrete | dsh §5.3.1.0 L1730-1880 |
| Slot 9 SPI 总表 | dsh §5.6.4 L2465-2480 |
| AC-02 SPI 多 Provider 兼容性 | dsh §0.4 L91-99 |
| AC-08 Slot 接口版本兼容 | dsh §0.4 L122-130 |
| 错误码 `LINGS-S01` (复用) + `LINGS-S05` (本 Story 新增) | constitution §4 + dsh §15 L7100-7195 |
| JDK 8 硬约束 | constitution §1 #1 + CLAUDE.md §3 |
| 13 项依赖锁定 + R-13 banned-dependencies | constitution §2 + §10 + dsh §17 |
| 启动期校验硬约束 | constitution §1 #12 + dsh §7.1.2 T1 |
| Spring Boot SPI 决策(不选 Java SPI / OSGi)| dsh §5.7 |
| A2A v0.5 演进兼容风险 R-04 | constitution §10 + dsh §17 |

---

**Spec Author**:Claude Code(根据用户 2026-09-20 会话指令"开始 lingshu 工程的 Story #003",蒸馏 dsh v1.5.34 §0.4 AC-02 + AC-08 + §5.1 typed-Provider + §5.2 SlotRouter + §5.3.1.0 7 Router + §5.5 9 Slot + §15 错误码 + constitution v1.0)
**Spec Date**:2026-09-20
**Status**:Draft → Specified(待 /speckit-plan 评审)
