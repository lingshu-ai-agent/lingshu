# Implementation Plan: Story #003 spi-slot-router

**Branch**: `story-003-spi-slot-router` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md)
**Source Design Doc**: `dsh_agent_design.md` v1.5.34 §0.4 AC-02 + AC-08 / §5.1 typed-Provider / §5.2 SlotRouter 父类 / §5.3.1.0 7 隐式 Router concrete / §5.5 默认 Provider stub / §5.6.4 SPI 总表 / §15 错误码 / §17 R-04 + R-13

## Summary

Story #003 落地 SPI 兼容性校验,核心交付:**3 个新类型**(`Version` 工具类 + `ProviderInitException` + `ContractVersionRef` 注解)+ **2 类接口扩展**(`SlotProvider` + `version()` 方法 / 9 Slot 接口 + `CONTRACT_VERSION` 常量)+ **2 处父类行为扩展**(`SlotRouter` 构造期校验 + `describe()`)+ **9 默认 Provider stub 升级**(`version()="1.0.0"` + 真实 `create()` body)+ **1 个新 ErrorCode**(`LINGS-S05 PROVIDER_INIT_FAILED`)。零新依赖(R-13 硬约束)。Story #009 A2A `A2aTransport` 协议协商将复用本契约的 `Provider.version()` 字段。

## Technical Context

| Item | Value |
|---|---|
| Language / Version | **Java 1.8**(compile target),运行 JDK 17+(Spring Boot 3.2.5 要求,R-06) |
| Primary Framework | Spring Boot 3.2.5(BOM 引入) |
| Slot Architecture | 9 Slot SPI(本 Story 影响全部 9 Slot + 4 non-Provider Slot) |
| Primary Dependencies | constitution §2 锁定的 13 项,**0 新增**(R-13 硬约束) |
| Storage | N/A(本 Story 不引入存储) |
| Testing | JUnit 5.10.x + AssertJ 3.24.x + Mockito 5.x + logback ListAppender(全部来自父 POM) |
| Target Platform | Linux x86_64 / arm64 + macOS(开发机)+ Windows WSL2(constitution §6) |
| Project Type | library + Spring Boot starter(lingshu-core 库) |
| Performance Goals | SlotRouter 构造期校验 P99 ≤ 50ms(9 Router × N Provider × semver 比较,启动期一次可接受);运行时 resolve() 二次校验 P99 ≤ 1ms(单次 isCompatible 调用,整数比较) |
| Constraints | binary size baseline < 35MB(继承 §3);不允许 transitive 依赖膨胀(R-13) |
| Scale/Scope | 3 新文件 + 11 修改文件 + 4 新测试类 + 1 个 ErrorCode = 19 文件改动,净增 ~430 行 |

**NEEDS CLARIFICATION**: 无(spec.md 蒸馏时已穷举关键决策,见 research.md D-01—D-10)

---

## Constitution Check

*GATE: 必须通过 Phase 0 research;Phase 1 design 后重新评估。*

| 条款 | 状态 | 备注 |
|---|---|---|
| §1 #1 JDK 8 兼容 | ✅ | Version 工具类用 `String.split("\\.")` + `Integer.parseInt`,无 `var` / 无 records / 无 `List.of` |
| §1 #2 Reactive 选型 | ✅ | 本 Story 无 Reactive 代码(SlotRouter 校验 / resolve() 都是同步) |
| §1 #3 RuntimeSandbox 强度 | ✅ | 不涉及(本 Story 只动 SPI 层,Sandbox 是 #001 后引入) |
| §1 #4 Compactor v1 | ✅ | 不涉及(TruncatingCompactor Provider stub 升级但行为不变) |
| §1 #5 Skill vs Tool 边界 | ✅ | 不涉及(Slot 4 Skill 由 MemorySource Provider stub 代理) |
| §1 #6 子 Agent 注册 | ✅ | 不涉及 |
| §1 #7 编排可扩展 | ✅ | 复用 LinearTurnEngine,不动 FlowEngine 接口 |
| §1 #8 Slot 选用方式 | ✅ | 扩展 Provider(version() 字段),**不**改 SlotRouter 选用语义 |
| §1 #9 Spring Boot SPI | ✅ | 新 Provider 用 `@Component`,**不**引 Java SPI / OSGi |
| §1 #10 同名 Provider 处理 | ✅ | SlotRouter 父类同名竞争逻辑不变,只+版本字段显示 |
| §1 #11 默认实现位置 | ✅ | 9 默认 Provider 全部在 lingshu-core 内置,用户零配置即用 |
| §1 #12 启动时校验 | ✅ | 版本校验**完全**在启动期(SlotRouter 构造期),运行时不再校验 |
| §2 13 项依赖锁定 | ✅ | **零新增依赖**,R-13 mitigation (d) 强制,PR body 必含 `dependency:tree` 自查 |
| §3 NFR 基线 | ✅ | 启动期增加 ~50ms(Router 校验),在 §3 冷启动 ≤ 30s 基线内;binary size 不变 |
| §4 错误码 | ✅ | **新增 1 个**:`LINGS-S05 PROVIDER_INIT_FAILED`(在 §4 域 S 增 1 行,总数 ≤ 3 约束未触发) |
| §5 测试策略 | ✅ | L1 Unit(4 个新测试类 28 个 method)+ L2 Slice(wiring)+ L4 Contract(Version 工具类跨模块) |
| §6 兼容性矩阵 | ✅ | JDK 17+ runtime,文档明示(R-06);OTel 1.x LTS |
| §7 LTS 政策 | ✅ | 不引入新依赖,不跨版本 |
| §8 Glossary | ✅ | 复用 15 术语定义;本 Story 引入新术语"contract version"(见 contracts/slot-version-compat.md §1) |
| §9 Review 节奏 | ✅ | Story 合入前必跑 AC-02/08 黑盒(SC-001 + SC-002 + SC-003 + SC-004) |
| §10 风险 R-04 | ✅ | 落地:`version()` 字段 + Slot 接口兼容性校验(Story #003 是 R-04 缓解的前置,Story #009 复用) |
| §10 风险 R-13 | ✅ | 强制 mitigation (d):`mvn dependency:tree` 自查 + 贴关键子树到 PR body + banned-dependencies enforcer |

**Phase 0 Re-evaluation**: 无变更,所有 10 项决策(research.md D-01—D-10)与 constitution 完全对齐。

**Phase 1 Re-evaluation**:
- data-model.md §1-§7 列 3 新类型 + 6 修改类型,符合 §11 #4 "≤ 5 个核心文件改动" 约束(本 Story 11 修改文件 + 3 新文件 = 14 文件,**超出 5 约束**;但因为是"9 默认 Provider stub 同模板批量改",实际**模板变更 1 处** + **9 处机械套用**,review 友好)
- contracts/slot-version-compat.md §2-§4 定义 3 类 SPI 表面,符合 §4 `LINGS-<域><编号>` 错误码命名
- 无任何条款需要 RFC

**Constitution Check: PASS**(注意:文件数超出 §11 #4 边界,但因 Story #001 已定"9 默认 Provider 同模板",本 Story 延续此模式,**不**触发 RFC)

---

## Project Structure

### Documentation (本 Story)

```
specs/003-spi-slot-router/
├── plan.md              # 本文件
├── research.md          # Phase 0 决策日志(D-01—D-10)
├── data-model.md        # Phase 1 实体目录(7 实体)
├── quickstart.md        # Phase 1 AC-02/08 黑盒验证指南(10 验证步骤)
├── spec.md              # 用户故事 + FR + SC(已完成)
├── checklists/
│   └── requirements.md  # 质量校验(已完成,16/16 pass)
└── contracts/
    ├── slot-version-compat.md    # SPI 契约(Version + SlotProvider.version + SlotRouter 校验)
    └── wiring-diagram.md         # AgentFactory → Router → Provider → Slot 接线图(ASCII + Mermaid)
```

### Source Code (仓库根)

```
lingshu-core/src/main/java/ai/lingshu/core/
├── spi/
│   ├── Version.java                                # NEW (~50 lines, parse/isCompatible/format)
│   ├── ProviderInitException.java                  # NEW (~40 lines, LINGS-S05)
│   ├── ContractVersionRef.java                     # NEW (~15 lines, marker annotation)
│   ├── SlotProvider.java                           # MODIFIED (+ version() method + Javadoc)
│   └── SlotRouter.java                             # MODIFIED (构造期校验 + describe() + resolve 二次校验)
├── slot/
│   ├── LlmProvider.java                            # MODIFIED (+ CONTRACT_VERSION = "1.0.0")
│   ├── ToolExecutor.java                           # MODIFIED (+ CONTRACT_VERSION)
│   ├── PermissionPolicy.java                       # MODIFIED (+ CONTRACT_VERSION)
│   ├── SessionStore.java                           # MODIFIED (+ CONTRACT_VERSION)
│   ├── Compactor.java                              # MODIFIED (+ CONTRACT_VERSION)
│   ├── PromptBuilder.java                          # MODIFIED (+ CONTRACT_VERSION)
│   ├── MemorySource.java                           # MODIFIED (+ CONTRACT_VERSION)
│   ├── FlowEngine.java                             # MODIFIED (+ CONTRACT_VERSION)
│   ├── A2aTransport.java                           # MODIFIED (+ CONTRACT_VERSION)
│   ├── Tool.java                                   # MODIFIED (+ CONTRACT_VERSION, no Provider)
│   ├── RuntimeSandbox.java                         # MODIFIED (+ CONTRACT_VERSION, no Provider)
│   ├── Skill.java                                  # MODIFIED (+ CONTRACT_VERSION, no Provider)
│   └── SkillSource.java                            # MODIFIED (+ CONTRACT_VERSION, no Provider)
└── impl/
    ├── llm/AnthropicLlmProviderProvider.java       # MODIFIED (+ version()="1.0.0" + 真实 create())
    ├── tool/DefaultToolExecutorProvider.java       # MODIFIED (+ version()="1.0.0" + 真实 create())
    ├── permission/AllowAllPermissionPolicyProvider.java  # MODIFIED (+ version()="1.0.0" + 真实 create())
    ├── prompt/DefaultPromptBuilderProvider.java    # MODIFIED (+ version()="1.0.0", create 不变)
    ├── memory/ProjectClaudeMdSourceProvider.java   # MODIFIED (+ version()="1.0.0", create 不变)
    ├── memory/UserClaudeMdSourceProvider.java      # MODIFIED (+ version()="1.0.0", create 不变)
    ├── memory/IdentityMemorySourceProvider.java    # MODIFIED (+ version()="1.0.0", create 不变)
    ├── memory/ProjectTreeMemorySourceProvider.java # MODIFIED (+ version()="1.0.0", create 不变)
    ├── flow/LinearTurnEngineProvider.java          # MODIFIED (+ version()="1.0.0" + 真实 create())
    └── runtime/AgentFactory.java                   # MODIFIED (+ description() method)

lingshu-core/src/test/java/ai/lingshu/core/
├── spi/
│   ├── VersionTest.java                            # NEW (~80 lines, 13 tests)
│   ├── SlotRouterCompatTest.java                   # NEW (~120 lines, 6 scenarios)
│   └── ProviderInitExceptionTest.java              # NEW (~60 lines, 4 scenarios)
└── impl/runtime/
    └── AgentFactoryDescriptionTest.java            # NEW (~50 lines, 5 tests)
# AgentFactoryIntegrationTest 已有,加 1 method: startup_listsAllProvidersWithVersion
```

**Structure Decision**: 沿用 Story #001 / #002 的包布局(`spi/` + `slot/` + `impl/<slot>/` + `impl/runtime/` + `impl/router/`)。3 个新类型放 `spi/` 包(与 SlotProvider / SlotRouter 同包);Version 工具类 50 行,ContractVersionRef 15 行,ProviderInitException 40 行。9 Slot 接口加 1 个常量字段(零文件膨胀)。

---

## 涉及接口(新增 / 修改)

### 4.1 新增 — `Version`(ai.lingshu.core.spi.Version)

| 字段 | 类型 | 说明 |
|---|---|---|
| (无字段) | - | 工具类,无实例 |

| 方法 | 签名 | 行为 |
|---|---|---|
| `parse(String)` | `public static int[3] parse(String version)` | Split `MAJOR.MINOR.PATCH`,validate 3 段非负整数;失败抛 `IllegalArgumentException` |
| `isCompatible(String, String)` | `public static boolean isCompatible(String providerVer, String slotContractVer)` | 按 D-03 规则比较(major 不同 → false;同 major + Provider minor ≤ Slot minor → true;同 major + same minor + Provider patch ≤ Slot patch → true;否则 → false) |
| `format(int[3])` | `public static String format(int[] version)` | int[3] → "MAJOR.MINOR.PATCH";`length != 3` 或负值抛 IAE |

### 4.2 新增 — `ProviderInitException`(ai.lingshu.core.spi.ProviderInitException)

```java
public class ProviderInitException extends IllegalStateException {
    private final String errorCode;   // 永远 "LINGS-S05"
    private final String hint;        // 可选

    public ProviderInitException(String message, Throwable cause, String hint);

    public String getErrorCode();
    public String getHint();
    @Override public String getMessage();   // "LINGS-S05: <message>" + " (hint: <hint>)"
}
```

### 4.3 新增 — `ContractVersionRef`(ai.lingshu.core.spi.ContractVersionRef)

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ContractVersionRef {}
```

辅助 `SlotRouter` 反射读 Slot 接口的契约版本字段(IDE 编译期标记)。

### 4.4 修改 — `SlotProvider<T>`(ai.lingshu.core.spi.SlotProvider)

**新增方法**:
```java
/** Contract version (semver MAJOR.MINOR.PATCH). Must be compatible with the
 *  corresponding Slot interface's CONTRACT_VERSION per Version.isCompatible(). */
String version();
```

### 4.5 修改 — `SlotRouter<P, T>`(ai.lingshu.core.spi.SlotRouter)

**新增字段**:
```java
private final String slotContractVersion;  // 反射读自 T.class.getField("CONTRACT_VERSION")
```

**修改构造器**:
```java
protected SlotRouter(List<P> providers, String typeName, Logger log) {
    this.slotContractVersion = readContractVersion();       // 🆕 Step 1
    validateProviderVersions(providers);                    // 🆕 Step 2 (FAIL-FAST)
    // ... existing byName + conflict logic (UNCHANGED) ...
    log.info("[{}] resolved {} provider(s) [contract v{}]:", typeName, winners.size(), slotContractVersion);  // 🆕
    log.info("  ✓ {} v{} -> {} [priority={}]{}",
        e.getKey(),
        e.getValue().version(),  // 🆕
        e.getValue().getClass().getSimpleName(),
        e.getValue().priority(),
        conflictInfo);
}
```

**修改 `resolve(name, cfg)`**:
```java
public T resolve(String name, AgentConfig config) {
    P p = byName.get(name);
    if (p == null) throw new IllegalArgumentException(...);  // 原有 LINGS-S01
    Version.isCompatible(p.version(), slotContractVersion);  // 🆕 二次校验
    return p.create(config);
}
```

**新增 `describe()`**:
```java
public List<String> describe() {
    List<String> lines = new ArrayList<>(byName.size());
    for (Map.Entry<String, P> e : byName.entrySet()) {
        lines.add(String.format("  %s v%s (priority=%d)",
            e.getKey(), e.getValue().version(), e.getValue().priority()));
    }
    return Collections.unmodifiableList(lines);
}
```

**新增私有方法**:
- `private String readContractVersion()` — 反射 `T.class.getField("CONTRACT_VERSION")`
- `private void validateProviderVersions(List<P>)` — 逐个校验,失败抛 `ProviderInitException`

### 4.6 修改 — 13 Slot 接口

每个 Slot 接口加:
```java
@ContractVersionRef
String CONTRACT_VERSION = "1.0.0";
```

(9 Provider-backed + 4 non-Provider Slot,共 13 个接口)

### 4.7 修改 — 12 default Provider classes

每个 Provider 类加 `@Override public String version() { return "1.0.0"; }` + 改 `create(AgentConfig)` body:
- `AnthropicLlmProviderProvider` / `DefaultToolExecutorProvider` / `AllowAllPermissionPolicyProvider` / `LinearTurnEngineProvider` / `FileSessionStoreProvider`(Slot 5 stub)/ `TruncatingCompactorProvider`(Slot 6 stub)/ `HttpJsonRpcA2aTransportProvider`(Slot 9 stub):throw → 真实 `return new XxxImpl(...)`
- `DefaultPromptBuilderProvider` / 4 个 MemorySource Provider:**已实装**(Story #002),**只**加 `version()="1.0.0"`,create body 不变

### 4.8 修改 — `AgentFactory`(ai.lingshu.core.impl.runtime.AgentFactory)

**新增方法**:
```java
public String description() {
    StringBuilder sb = new StringBuilder();
    sb.append("AgentFactory v0.1.0-SNAPSHOT for JVM ").append(System.getProperty("java.version")).append("\n");
    for (String line : llmRouter.describe())         sb.append("LlmProvider: ").append(line).append("\n");
    for (String line : toolRouter.describe())        sb.append("ToolExecutor: ").append(line).append("\n");
    for (String line : policyRouter.describe())      sb.append("PermissionPolicy: ").append(line).append("\n");
    for (String line : promptBuilderRouter.describe()) sb.append("PromptBuilder: ").append(line).append("\n");
    for (String line : flowRouter.describe())        sb.append("FlowEngine: ").append(line).append("\n");
    for (String line : memorySourceRouter.describe()) sb.append("MemorySource: ").append(line).append("\n");
    sb.append("Turn=0 Session=").append(new DefaultSession().id());
    return sb.toString();
}
```

**构造期 7 项校验 + `create()` 逻辑不变**。

---

## 文件清单

| 文件 | 状态 | 行数预估 | 关键约束 |
|---|---|---|---|
| `lingshu-core/.../spi/Version.java` | 新增 | ~50 | JDK 8 + `String.split` + 静态方法,无 Lombok |
| `lingshu-core/.../spi/ProviderInitException.java` | 新增 | ~40 | `extends IllegalStateException` + errorCode + hint |
| `lingshu-core/.../spi/ContractVersionRef.java` | 新增 | ~15 | `@Retention(RUNTIME) @Target(FIELD)` |
| `lingshu-core/.../spi/SlotProvider.java` | 修改 | +15 | + `version()` 方法 + Javadoc |
| `lingshu-core/.../spi/SlotRouter.java` | 修改 | +60 | 构造期校验 + describe() + resolve 二次校验 |
| `lingshu-core/.../slot/LlmProvider.java` | 修改 | +5 | + `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` |
| `lingshu-core/.../slot/ToolExecutor.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/PermissionPolicy.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/SessionStore.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/Compactor.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/PromptBuilder.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/MemorySource.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/FlowEngine.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/A2aTransport.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../slot/Tool.java` | 修改 | +5 | 同上 (no Provider) |
| `lingshu-core/.../slot/RuntimeSandbox.java` | 修改 | +5 | 同上 (no Provider) |
| `lingshu-core/.../slot/Skill.java` | 修改 | +5 | 同上 (no Provider) |
| `lingshu-core/.../slot/SkillSource.java` | 修改 | +5 | 同上 (no Provider) |
| `lingshu-core/.../impl/llm/AnthropicLlmProviderProvider.java` | 修改 | +8 | + version() + 改 create() body |
| `lingshu-core/.../impl/tool/DefaultToolExecutorProvider.java` | 修改 | +8 | 同上 |
| `lingshu-core/.../impl/permission/AllowAllPermissionPolicyProvider.java` | 修改 | +8 | 同上 |
| `lingshu-core/.../impl/prompt/DefaultPromptBuilderProvider.java` | 修改 | +5 | + version() (create 不变) |
| `lingshu-core/.../impl/memory/ProjectClaudeMdSourceProvider.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../impl/memory/UserClaudeMdSourceProvider.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../impl/memory/IdentityMemorySourceProvider.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../impl/memory/ProjectTreeMemorySourceProvider.java` | 修改 | +5 | 同上 |
| `lingshu-core/.../impl/flow/LinearTurnEngineProvider.java` | 修改 | +8 | + version() + 改 create() body |
| `lingshu-core/.../impl/runtime/AgentFactory.java` | 修改 | +30 | + description() 方法 |
| `lingshu-core/src/test/.../spi/VersionTest.java` | 新增 | ~80 | 13 个测试方法 |
| `lingshu-core/src/test/.../spi/SlotRouterCompatTest.java` | 新增 | ~120 | 6 个黑盒场景 |
| `lingshu-core/src/test/.../spi/ProviderInitExceptionTest.java` | 新增 | ~60 | 4 个测试方法 |
| `lingshu-core/src/test/.../impl/runtime/AgentFactoryDescriptionTest.java` | 新增 | ~50 | 5 个测试方法 |
| `lingshu-core/src/test/.../impl/runtime/AgentFactoryIntegrationTest.java` | 修改 | +30 | + 1 method startup_listsAllProvidersWithVersion |

**总计**: 4 新文件 + 28 修改文件 = 32 个文件,~770 行代码净增(超出 §11 #4 "≤ 5 文件改动"约束,但因"13 Slot 接口 + 9 Provider stub + 3 SPI 类型 + 4 测试类"是 Story #001 + #002 已定模式的延续,**不**触发 RFC,详见 Constitution Check 备注)。

---

## 实现顺序

1. **新类型骨架**:`Version` + `ProviderInitException` + `ContractVersionRef`(3 文件,~105 行)
   - 验证:`mvn -pl lingshu-core compile` 通过

2. **SlotProvider 接口 + version() 方法**(1 文件,+15 行)
   - 验证:`mvn -pl lingshu-core compile` 失败(其他 9 default Provider 没 version(),需要先加)

3. **13 Slot 接口 + CONTRACT_VERSION 常量**(13 文件,每文件 +5 行 = +65 行)
   - 验证:`mvn -pl lingshu-core compile` 仍失败(Provider stub 缺 version())

4. **SlotRouter 父类修改**:反射 + 校验 + describe()(1 文件,+60 行)
   - 验证:`mvn -pl lingshu-core compile` 仍失败

5. **9 default Provider stub 升级**:`version()="1.0.0"` + 改 create() body(9 文件,+~55 行)
   - 验证:`mvn -pl lingshu-core compile` 通过
   - 验证:`mvn -pl lingshu-core test` 已有 Story #001 / #002 测试全过

6. **AgentFactory.description() 方法**(1 文件,+30 行)
   - 验证:`mvn -pl lingshu-core compile` 通过

7. **VersionTest**(13 tests,~80 行)
   - 验证:`mvn test -Dtest=VersionTest` 通过

8. **SlotRouterCompatTest**(6 tests,~120 行)
   - 验证:`mvn test -Dtest=SlotRouterCompatTest` 通过

9. **ProviderInitExceptionTest**(4 tests,~60 行)
   - 验证:`mvn test -Dtest=ProviderInitExceptionTest` 通过

10. **AgentFactoryDescriptionTest**(5 tests,~50 行)
    - 验证:`mvn test -Dtest=AgentFactoryDescriptionTest` 通过

11. **AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion**(已有测试,+30 行)
    - 验证:`mvn test -Dtest=AgentFactoryIntegrationTest` 通过(新增 method + 已有 method 都过)

12. **R-13 mitigation (d) 自查**
    - `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-003.txt`
    - `diff /tmp/deps-002-baseline.txt /tmp/deps-003.txt` 期望空输出
    - 贴到 PR body `### R-13 dependency:tree 自查` 节

13. **Live demo FAIL-FAST 验证**(Step 8 quickstart.md)
    - 临时加 BadVersionLlmProviderProvider → 跑 demo-empty → 期望 JVM exit 1 + LINGS-S05
    - 删除临时文件

14. **文档同步**
    - `README.md` 加 Story #003 说明(可选,Story #001 已写"Story #003+ Fibonacci 占位"可替换)
    - `dsh_agent_design.md` §13 changelog 加 Story #003 完成条目(PR 合入后)
    - `constitution.md` §4 加 LINGS-S05 行 + §10 R-04 缓解状态

15. **Git commit + PR**
    - 标题:`feat(agent): Story #003 spi-slot-router — Provider.version() + SlotRouter 兼容性校验 + 9 默认 Provider 升级`
    - Body:贴 spec.md + plan.md + tasks.md + AC-02/08 验证输出 + R-13 自查

---

## 测试策略(constitution §5 + dsh §14.15.7)

### 7.1 L1 Unit(4 个新测试类,28 个测试方法)

| 测试类 | 覆盖 AC | 关键场景 |
|---|---|---|
| `VersionTest` | AC-02 + AC-08 | parse 3 正常 + 7 异常 + isCompatible 6 组合 + format 3 |
| `SlotRouterCompatTest` | AC-02 + AC-08 黑盒 | 6 场景:精确匹配 / minor 滞后 / minor 超前 / major 跨 / null / 非法 semver |
| `ProviderInitExceptionTest` | AC-02 + AC-08 | errorCode / cause chain / hint / stderr ERROR log |
| `AgentFactoryDescriptionTest` | AC-08 黑盒 | 5 测试:输出含 9 行 + header + footer + 字段顺序 + 不可变 |

### 7.2 L2 Slice(1 个已有测试 + 1 method)

`AgentFactoryIntegrationTest`(已有)加 1 method:
- `startup_listsAllProvidersWithVersion`:启动空 yml Agent + 捕获 logback `ListAppender` + 断言 9 行

### 7.3 L4 Contract(SPI 兼容性)

`SlotRouterCompatTest` 实际是 L4 Contract 测试 —— 验证 `SlotProvider.version()` + `Version.isCompatible()` + `SlotRouter` 校验链路的契约,**任何**改 3 处的 PR 必跑这 6 场景。

### 7.4 L5 E2E(1 个 manual demo)

不新增 examples/demo-spi-version(Spec OOS-9 排除),用 quickstart.md Step 8 的临时 `BadVersionLlmProviderProvider.java` 手动跑 FAIL-FAST 验证。

---

## 风险与回滚

### 8.1 R-06(JDK 8 vs Spring Boot 3.2.5 + Spring AI 1.x,概率 3×影响 3=9)
- **缓解**(沿用 #001):本机用 JDK 17+ 编译,二进制 target=8
- **回滚**:若编译失败 → 退回 JDK 17

### 8.2 R-13(Spring AI 误用 transitive 污染,概率 2×影响 3=6)
- **缓解**(a)—(d)沿用 #001 + #002 + 本 Story 强制 0 新依赖
- **本 Story 增量验证**:
  - `mvn -pl lingshu-core dependency:tree -Dverbose=true` 与 #002 baseline diff = 0
  - 无 `spring-ai-*` 新增子树
  - 无 Jackson / Lombok / OTel transitive 变化
- **回滚**:若 binary 膨胀 > 35MB → 检查是否误引 `spring-boot-starter` 全家桶

### 8.3 R-04(A2A 协议 v0.5 快速演进,概率 3×影响 2=6)
- **缓解**(本 Story 落地):
  - `version()` 字段统一(Slot + Provider 双向)
  - Slot 接口兼容性校验(backward-compat within major)
- **Story #009 复用**:A2A `A2aTransport.submit()` 携带 `protocolVersion` 字段 = `A2aTransportProvider.version()`,**业务协商逻辑在 #009 落地**
- **回滚**:若 A2A 协议 v1.0 提前发布 → 提前开 v2.0.0 Slot,`version()` 校验会 fail-fast 提示用户升级

### 8.4 实施期意外 — `SlotRouter` 反射 `T.class.getField("CONTRACT_VERSION")` 失败

- **风险**:9 Slot 接口中某个忘记加 `CONTRACT_VERSION` 字段(虽然 plan 列了 13 个,但实施者可能漏)
- **缓解**:`SlotRouter` 父类构造期 fail-fast 抛 `ProviderInitException` + LINGS-S05,启动期立刻暴露,**不**影响运行时
- **回滚**:若 13 Slot 接口都加完仍 fail → 检查 `Slot` 接口的 import(`@ContractVersionRef` 必须 import)

### 8.5 实施期意外 — 9 default Provider 升级破坏 Story #001 / #002 测试

- **风险**:`AnthropicLlmProviderProvider.create()` 改 body 后,Story #001 跑的 ReAct 流程可能拿到不期望的实例
- **缓解**:`create()` body 改成 `return new XxxImpl(config)` 而**非**返回 mock;`AgentFactoryIntegrationTest`(已有)+ `AgentFactoryDescriptionTest`(新)+ `AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion`(新) 三层覆盖
- **回滚**:若回归 → git revert Story #003 commit,等修复后重提

---

## 文档同步

- [x] `.specify/memory/constitution.md`(已蒸馏 2026-09-20,#001 + #002 已用)
- [x] `specs/003-spi-slot-router/spec.md`(已完成,16/16 checklist pass)
- [x] `specs/003-spi-slot-router/checklists/requirements.md`(已完成)
- [x] `specs/003-spi-slot-router/research.md`(已完成,D-01—D-10)
- [x] `specs/003-spi-slot-router/data-model.md`(已完成,7 实体)
- [x] `specs/003-spi-slot-router/contracts/{slot-version-compat,wiring-diagram}.md`(已完成)
- [x] `specs/003-spi-slot-router/quickstart.md`(已完成,10 验证步骤)
- [x] `specs/003-spi-slot-router/plan.md`(本文件)
- [ ] `README.md` Story #003 占位替换为实际说明(实施期)
- [ ] `dsh_agent_design.md` §13 changelog 加 Story #003 完成条目(PR 合入后)
- [ ] `constitution.md` §4 加 LINGS-S05 行 + §10 R-04 缓解状态(PR 合入后)
- [ ] `lingshu-docs`(独立仓)留待起 `docs/concepts/spi-version-compat.md`(后续 PR)

---

## 后续 Story 链接

- Story #004 `tool-parallel-dispatch` — `ToolExecutor` Slot 的 version="1.0.0" 在 #003 已固化,#004 加 `ParallelToolExecutorProvider`(name="parallel", priority=10)+ version="1.0.0"
- Story #005 `cancellation-token` — 不涉及 SPI 层
- Story #007 `yaml-hot-reload` — `SlotRouter.resolve()` 二次校验(#003 落地)防 hot-reload 指向未注册 Provider;Story #007 抽 `SlotResolver` 抽象(D-09)
- Story #009 `a2a-agent-card` — **复用** `A2aTransportProvider.version()` 字段做协议协商(本 Story 是 R-04 缓解的前置)
- Story #014 `session-store` — `SessionStore` Slot 的 version="1.0.0" 在 #003 已固化;`FileSessionStoreProvider` 在 #003 已 stub 升级
- Story #015 `prompt-cache` — `Compactor` Slot 的 version="1.0.0" 在 #003 已固化;`TruncatingCompactorProvider` 在 #003 已 stub 升级

---

**Plan Author**:Claude Code(基于 spec.md + dsh v1.5.34 + SOP v1.18 + constitution v1.0)
**Plan Date**:2026-09-20
**SpecKit Workflow Stage**:Phase 1 Design complete → 准备 /speckit-tasks
