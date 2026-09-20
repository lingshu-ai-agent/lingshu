# Plan: Story 002 identity-instructions-memory

**Branch**: `story-002-identity-instructions-memory` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md)
**Source Design Doc**: dsh_agent_design.md v1.5.34 §0.4 AC-09 / §4.5 PromptBuilder / §4.5.1 5 段装配 / §4.12.2 Identity/Instructions/Memory / §5.3.1.1 MemorySourceRouter / §5.5 Slot 1+Slot 7 默认 Provider / §8.1 业务三件套 YAML Schema

## 约束(从 constitution 继承)

- **JDK 8 only** —— 二进制 target=1.8,运行需 JDK 17+(R-06,constitution §6)
- **Lombok `@Value` / `@Builder` 不可变** —— 无 setter,配置类全部 `@Value`(constitution §1 #1)
- **所有新 ErrorCode 走 `LINGS-<域><编号>`** —— 本 Story 引入 `LINGS-S01` (已有 from #001,复用)
- **性能预算 §3 NFR 不退化** —— 单 turn history ≤ 100K tokens / Tool 调用 P99 ≤ toolTimeoutSec(本 Story 无 Tool 涉及)
- **禁用** `record` / `sealed` / `var` / `List.of` / text blocks(constitution §1 #1)
- **禁用** Spring AI 自动 tool 执行(constitution §1,本 Story 不涉及)
- **Provider 必须显式映射** —— MemorySourceRouter 显式 `Map<String, MemorySourceProvider>`(constitution §1,dsh §5.2)
- **R-13 mitigation (d) 强制** —— `mvn dependency:tree` 自查 + zero new deps + banned-dependencies enforcer(constitution §10)
- **5 段装配顺序硬约束** —— `[ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY] / [CONVERSATION HISTORY] / [USER MESSAGE]`(dsh §4.5.1)
- **多 Provider 模式** —— `@Bean(name = "memorySourceProvider_<name>")` 显式 Bean 名(§5.5 v1.5.28)
- **缺失文件静默跳过** —— 不抛 IOException,DEBUG 日志即可(spec US3 + dsh §4.5)

---

## 1. Technical Context

| 项 | 值 |
|---|---|
| Language / Version | **Java 1.8**(编译 target),运行 JDK 17+(Spring Boot 3.2.5 要求,R-06) |
| Primary Framework | Spring Boot 3.2.5(BOM 引入) |
| Slot Architecture | 9 Slot SPI(本 Story 涉及 Slot 7 PromptBuilder + 新增 Slot 7.x MemorySource 子接口) |
| Primary Dependencies | constitution §2 锁定的 13 项,**0 新增**(R-13 硬约束) |
| Storage | N/A(本 Story 不引入存储,Story #014 才加 SessionStore) |
| Testing | JUnit 5.10.x + AssertJ 3.24.x + Mockito 5.x(全部来自父 POM) |
| Target Platform | Linux x86_64 / arm64 + macOS(开发机)+ Windows WSL2(constitution §6) |
| Project Type | library + Spring Boot starter(lingShu-core 库 + lingshu-examples/demo-engineer 示例) |
| Performance Goals | 单 turn PromptBuilder.build() P99 ≤ 50ms(本地 4 MemorySource,无 IO 时)/ ≤ 200ms(含 4 个文件 IO);LLM 流式首 token P50 ≤ 1.5s / P99 ≤ 3.0s(继承 §3 NFR) |
| Constraints | binary size baseline < 35MB(继承 §3);不允许 transitive 依赖膨胀(R-13) |
| Scale/Scope | 4 个新 MemorySource Provider + 1 个新 Router + 1 个修改 Builder + 1 个新示例(demo-engineer),共 16 新文件 + 3 修改 = 19 文件,净增 ~860 行 |

**NEEDS CLARIFICATION**: 无(spec.md 蒸馏时已穷举关键决策,见 research.md D-01—D-08)

---

## 2. Constitution Check

*GATE: 必须通过 Phase 0 research;Phase 1 design 后重新评估。*

| 条款 | 状态 | 备注 |
|---|---|---|
| §1 #1 JDK 8 兼容 | ✅ | 所有新代码用 `Collections.emptyList()` / `@Value` / 无 `var` / 无 records |
| §1 #2 Reactive 选型 | ✅ | 本 Story 无 Reactive 代码(MemorySource 同步 string-return) |
| §1 #4 Compactor v1 | ✅ | 不涉及 |
| §1 #5 Skill vs Tool 边界 | ✅ | 不涉及 |
| §1 #7 编排可扩展 | ✅ | 复用 LinearTurnEngine,不动 FlowEngine 接口 |
| §1 #8 Slot 选用方式 | ✅ | 多 Provider 模式,新 MemorySourceRouter 复用 SlotRouter 抽象 |
| §1 #9 Spring Boot SPI | ✅ | 4 个 MemorySourceProvider 用 `@Component`,无 Java SPI / OSGi |
| §1 #10 同名 Provider 处理 | ✅ | MemorySourceRouter extends SlotRouter,同名冲突由 priority 解决 |
| §1 #12 启动时校验 | ✅ | AgentFactory.validate() 不变 + MemorySourceRouter.resolveAll() 启动期校验 memorySources 列表 |
| §2 13 项依赖锁定 | ✅ | **零新增依赖**,R-13 mitigation (d) 强制 |
| §3 NFR 基线 | ✅ | binary < 35MB / P50 turn ≤ 30s / 冷启动 ≤ 30s |
| §4 错误码 | ✅ | 复用 `LINGS-S01`(Slot name 不在 Router),不新增 |
| §5 测试策略 | ✅ | L1(4 个新测试类 25 个 method)+ L2(wiring)+ L5(demo-engineer E2E) |
| §6 兼容性矩阵 | ✅ | JDK 17+ runtime,文档明示(R-06) |
| §7 LTS 政策 | ✅ | 不引入新依赖,不跨版本 |
| §8 Glossary | ✅ | 复用 15 术语定义 |
| §9 Review 节奏 | ✅ | Story 合入前必跑 AC-09 |
| §10 风险 R-13 | ✅ | mitigation (d) 强制,PR body 必含 dependency:tree 自查节 |

**Phase 0 Re-evaluation**: 无变更,所有 8 项决策(research.md D-01—D-08)与 constitution 完全对齐。

**Phase 1 Re-evaluation**: 
- contracts/prompt-builder.md §2 明确 5 段硬约束,符合 §1 #1 + dsh §4.5.1
- data-model.md §3 列出 4 个新 MemorySource 全部 `null`-returning on missing files,符合 US3
- 无任何条款需要 RFC

**Constitution Check: PASS**

---

## 3. Project Structure

### 3.1 Documentation (本 Story)

```
specs/002-identity-instructions-memory/
├── plan.md              # 本文件
├── research.md          # Phase 0 决策日志(D-01—D-08)
├── data-model.md        # Phase 1 实体目录
├── quickstart.md        # Phase 1 AC-09 黑盒验证指南
├── spec.md              # 用户故事 + FR + SC
├── checklists/
│   └── requirements.md  # 质量校验(已完成,16/16 pass)
└── contracts/
    ├── prompt-builder.md    # PromptBuilder SPI 契约 + 5 段装配顺序
    ├── memory-source.md     # MemorySource SPI 契约 + 4 个实现细节
    └── wiring-diagram.md    # AgentFactory → PromptBuilder → MemorySource 接线图
```

### 3.2 Source Code (仓库根)

```
lingshu-core/src/main/java/ai/lingshu/core/
├── impl/
│   ├── memory/                                          # NEW package for Story #002
│   │   ├── ProjectClaudeMdSource.java                   # NEW (~30 lines)
│   │   ├── UserClaudeMdSource.java                      # NEW (~30 lines)
│   │   ├── IdentityMemorySource.java                    # NEW (~40 lines)
│   │   ├── ProjectTreeMemorySource.java                 # NEW (~60 lines)
│   │   ├── ProjectClaudeMdSourceProvider.java           # NEW (~25 lines)
│   │   ├── UserClaudeMdSourceProvider.java              # NEW (~25 lines)
│   │   ├── IdentityMemorySourceProvider.java            # NEW (~25 lines)
│   │   └── ProjectTreeMemorySourceProvider.java         # NEW (~25 lines)
│   ├── prompt/
│   │   ├── DefaultPromptBuilder.java                    # MODIFIED (constructor + 5-segment refactor)
│   │   └── DefaultPromptBuilderProvider.java            # MODIFIED (+@Autowired MemorySourceRouter)
│   └── router/
│       └── Routers.java                                 # MODIFIED (+1 inner @Component class)
│
lingshu-examples/
└── demo-engineer/                                        # NEW module for AC-09 E2E
    ├── pom.xml
    └── src/main/
        ├── java/ai/lingshu/examples/demoengineer/
        │   └── DemoEngineerApplication.java
        └── resources/
            ├── application.yml                          # identity + instructions + memory config
            ├── prompts/system-engineer.md               # [INSTRUCTIONS] source
            └── CLAUDE.md                                # [PROJECT MEMORY] source
```

**Structure Decision**: 沿用 Story #001 的 `impl/<slot>/` 包布局,在 `impl/` 下新增 `memory/` 子包(放 MemorySource 4 个实现 + 4 个 Provider)。`demo-engineer` 镜像 `demo-empty` 的布局,只是 module 名 + 应用类名不同。

### 3.3 Test Code

```
lingshu-core/src/test/java/ai/lingshu/core/
├── impl/memory/                                          # NEW test package
│   ├── IdentityMemorySourceTest.java                    # NEW (~50 lines, 3 tests)
│   └── ProjectTreeMemorySourceTest.java                 # NEW (~80 lines, 7 tests)
├── impl/prompt/
│   └── DefaultPromptBuilderTest.java                    # NEW (~150 lines, 10 tests)
├── impl/router/
│   └── MemorySourceRouterTest.java                      # NEW (~80 lines, 7 tests)
└── (existing from #001) AgentFactoryIntegrationTest, SlotRouterTest, AgentConfigDefaultsTest
```

---

## 4. 涉及接口(新增 / 修改)

### 4.1 新增 — 4 个 MemorySource concrete impl

| 类 | name() | priority() | load(ctx) 行为 |
|---|---|---|---|
| `ProjectClaudeMdSource` | `"project-claude-md"` | 10 | 读 `cfg.memory.claudeMd.project`,缺失返 null |
| `UserClaudeMdSource` | `"user-claude-md"` | 20 | 读 `cfg.memory.claudeMd.user`,缺失返 null |
| `IdentityMemorySource` | `"identity"` | 30 | Jackson 序列化 `cfg.identity` → JSON string |
| `ProjectTreeMemorySource` | `"project-tree"` | 40 | 深度 1 列出 `*.md` 文件,按文件名排序,内容拼装(用 `── separator ──`) |

### 4.2 新增 — 4 个 MemorySourceProvider concrete

每个对应一个 Source,`create(AgentConfig)` 返回对应 Source 实例;全部 `@Component`。

### 4.3 新增 — 1 个 Router(MemorySourceRouter)

```java
@Component
public static class MemorySourceRouter
        extends SlotRouter<Providers.MemorySourceProvider, MemorySource> {
    public MemorySourceRouter(List<Providers.MemorySourceProvider> providers) {
        super(providers, "MemorySource", LoggerFactory.getLogger(MemorySourceRouter.class));
    }
    public List<MemorySource> resolveAll(List<String> names, AgentConfig cfg) { ... }
}
```

加到现有 `Routers.java`(作为第 6 个 inner `@Component`)。

### 4.4 修改 — `DefaultPromptBuilderProvider`

加 `@Autowired MemorySourceRouter memorySourceRouter`;`create(config)` 改为先 `memorySourceRouter.resolveAll(config.getPrompt().getMemorySources(), config)` 再 `new DefaultPromptBuilder(memorySources)`。

### 4.5 修改 — `DefaultPromptBuilder`

- 构造函数签名:`DefaultPromptBuilder(List<MemorySource> memorySources)`
- `[PROJECT MEMORY]` 段改为遍历 `memorySources` 列表,调用 `load(ctx)`,用 `\n\n── separator ──\n\n` 拼接非 null 结果
- 新增 mustache 模板渲染:`renderTemplate(String, Map<String,String>)` 私有方法(若 `instructions.templateEngine == "mustache"`)

### 4.6 不变 — `AgentConfig` 字段 / `AgentFactory` 校验 / 5 个现有 Router

- `AgentConfig` 字段定义(#001 已固化)不变;`defaults()` 工厂方法需确认 5 字段值(若已正确则 0 行变更)
- `AgentFactory` 不动 7 项校验清单(MemorySourceRouter 由 PromptBuilderProvider 间接持有,不进 AgentFactory)
- 5 个 #001 Router(LlmProvider/ToolExecutor/PermissionPolicy/PromptBuilder/FlowEngine)不变

---

## 5. 文件清单

| 文件 | 状态 | 行数预估 | 关键约束 |
|---|---|---|---|
| `lingshu-core/.../impl/memory/ProjectClaudeMdSource.java` | 新增 | ~30 | JDK 8 + Lombok `@Value`,异常静默 |
| `lingshu-core/.../impl/memory/UserClaudeMdSource.java` | 新增 | ~30 | 同上 |
| `lingshu-core/.../impl/memory/IdentityMemorySource.java` | 新增 | ~40 | Jackson ObjectMapper |
| `lingshu-core/.../impl/memory/ProjectTreeMemorySource.java` | 新增 | ~60 | 深度 1 + `*.md` 过滤 + 字母排序 |
| `lingshu-core/.../impl/memory/ProjectClaudeMdSourceProvider.java` | 新增 | ~25 | `@Component` + name 唯一 |
| `lingshu-core/.../impl/memory/UserClaudeMdSourceProvider.java` | 新增 | ~25 | 同上 |
| `lingshu-core/.../impl/memory/IdentityMemorySourceProvider.java` | 新增 | ~25 | 同上 |
| `lingshu-core/.../impl/memory/ProjectTreeMemorySourceProvider.java` | 新增 | ~25 | 同上 |
| `lingshu-core/.../impl/router/Routers.java` | 修改 | +20 | 加 1 个 inner @Component |
| `lingshu-core/.../impl/prompt/DefaultPromptBuilderProvider.java` | 修改 | +10 | @Autowired + create 改造 |
| `lingshu-core/.../impl/prompt/DefaultPromptBuilder.java` | 修改 | +30, -20 | 构造函数 + 5 段重构 + 模板渲染 |
| `lingshu-core/src/test/.../impl/memory/IdentityMemorySourceTest.java` | 新增 | ~50 | 3 个测试方法 |
| `lingshu-core/src/test/.../impl/memory/ProjectTreeMemorySourceTest.java` | 新增 | ~80 | 7 个测试方法 |
| `lingshu-core/src/test/.../impl/prompt/DefaultPromptBuilderTest.java` | 新增 | ~150 | 10 个测试方法 |
| `lingshu-core/src/test/.../impl/router/MemorySourceRouterTest.java` | 新增 | ~80 | 7 个测试方法 |
| `lingshu-examples/demo-engineer/pom.xml` | 新增 | ~30 | 依赖 lingshu-core + spring-boot-starter |
| `lingshu-examples/demo-engineer/src/main/java/.../DemoEngineerApplication.java` | 新增 | ~80 | Spring Boot main + 跑 1 turn |
| `lingshu-examples/demo-engineer/src/main/resources/application.yml` | 新增 | ~25 | identity + instructions + memory 配置 |
| `lingshu-examples/demo-engineer/src/main/resources/prompts/system-engineer.md` | 新增 | ~20 | [INSTRUCTIONS] 内容 |
| `lingshu-examples/demo-engineer/src/main/resources/CLAUDE.md` | 新增 | ~30 | [PROJECT MEMORY] 内容 |
| `lingshu-examples/demo-engineer/src/main/resources/docs/architecture.md` | 新增 | ~15 | 测试 ProjectTree 抓 `*.md` |
| `lingshu-examples/demo-engineer/src/main/resources/docs/conventions.md` | 新增 | ~15 | 同上 |
| `lingshu-examples/demo-engineer/src/main/resources/README.md` | 新增 | ~30 | 同上(确认非 `.md` 文件被跳过) |
| `README.md` | 修改 | +20 | 加 demo-engineer Quick Start |

**总计**: 19 新文件 + 3 修改 = 22 个文件,~860 行代码净增。

---

## 6. 实现顺序

1. **新包骨架**:`lingshu-core/.../impl/memory/` 包目录创建 + `package-info.java`(可选)
   - 验证:`mvn -pl lingshu-core compile` 通过(空包)

2. **4 个 MemorySource concrete impl**(先 impl 后 Provider,因为 Provider 只是 `new` 一下)
   - `ProjectClaudeMdSource` → `UserClaudeMdSource` → `IdentityMemorySource` → `ProjectTreeMemorySource`
   - 验证:每个类单独 `mvn compile` 通过

3. **4 个 MemorySourceProvider** + Spring 组件扫描自动注册
   - 验证:`mvn -pl lingshu-core test -Dtest=MemorySourceRouterTest#constructor_resolvesAllFourDefaults` 通过(预先写好测试)

4. **`MemorySourceRouter` concrete 类**(加到 `Routers.java`)
   - 加 `resolveAll(List<String>, AgentConfig)` 方法
   - 验证:启动日志输出 4 行 `✓ <name> -> ...`

5. **`DefaultPromptBuilderProvider` 修改**
   - 加 `@Autowired MemorySourceRouter`
   - 改 `create(config)` 调用 `resolveAll(...)` 后传 `new DefaultPromptBuilder(sources)`
   - 验证:`mvn compile` 通过

6. **`DefaultPromptBuilder` 重构**
   - 构造函数改 `DefaultPromptBuilder(List<MemorySource> memorySources)`
   - `[PROJECT MEMORY]` 段改为遍历 sources
   - 加 `renderTemplate(...)` 私有方法
   - 验证:`DefaultPromptBuilderTest` 全部通过

7. **4 个新单元测试**
   - `MemorySourceRouterTest`(7 tests)
   - `DefaultPromptBuilderTest`(10 tests)
   - `IdentityMemorySourceTest`(3 tests)
   - `ProjectTreeMemorySourceTest`(7 tests)
   - 验证:`mvn -pl lingshu-core test` 全绿(除 #001 已有的测试)

8. **`demo-engineer` 示例**(AC-09 黑盒)
   - pom.xml + Application + application.yml + 4 个 markdown 文件
   - 验证:`mvn -pl lingshu-examples/demo-engineer -am package && java -jar ... "你是做什么的"` 30s 内拿到 token

9. **R-13 mitigation (d) 自查**
   - `mvn -pl lingshu-core dependency:tree -Dverbose=true` vs Story #001 baseline
   - 期望:零 diff
   - 贴到 PR body `### R-13 dependency:tree 自查` 节

10. **文档同步**
    - `README.md` 加 demo-engineer Quick Start 段
    - `dsh_agent_design.md` §13 changelog 加 Story #002 完成条目
    - `constitution.md` §10 R-13 mitigation 状态(若已缓解 → 标记)

11. **Git commit + PR**
    - 标题:`feat(agent): Story #002 identity-instructions-memory — 业务三件套 + 5 段装配 + 4 MemorySource Provider`
    - Body:贴 spec.md + plan.md + tasks.md + AC-09 验证输出 + R-13 自查

---

## 7. 测试策略(constitution §5 + dsh §14.15.7)

### 7.1 L1 Unit(4 个新测试类,~27 个测试方法)

| 测试类 | 覆盖 AC | 关键场景 |
|---|---|---|
| `MemorySourceRouterTest` | US2 Scenario 3 + 同名竞争 | resolveAll 4 种 list(空/null/单/多/未知)+ 启动日志格式 |
| `DefaultPromptBuilderTest` | US1 Scenario 1-3 + US3 + FR-005 | 5 段装配各段 + 模板渲染 + 缺失文件 + history 在 system 与 user 之间 |
| `IdentityMemorySourceTest` | US1 Scenario 1 + FR-007 | JSON 序列化正确性 + null fallback + 确定性 |
| `ProjectTreeMemorySourceTest` | US3 + spec OOS-6 | 深度 1 + `*.md` 过滤 + 字母排序 + 缺失目录 + symlink |

### 7.2 L2 Slice(1 个已有测试,加 2 个新断言)

`AgentFactoryIntegrationTest`(已有)加 2 个新断言:
- `defaultConfig().prompt.builder = "default"` → resolve 成功
- `defaultConfig().prompt.memorySources` 全部解析为 4 个 MemorySource 实例

### 7.3 L5 E2E(1 个新示例:demo-engineer)

| 场景 | 命令 | 期望 |
|---|---|---|
| AC-09 US1 Scenario 1 | `time java -jar demo-engineer.jar "你是做什么的"` | 30s 内首个 token,stderr 含 `[MemorySource] resolved 4 provider(s):` |
| AC-09 US1 Scenario 2 | 同一 jar + 空 yml | system message 仅含 `"你是 lingShu-agent"` |
| AC-09 US3 Scenario 1 | 删除 `CLAUDE.md` 重跑 | stderr 零 ERROR,turn 仍完成 |
| AC-09 US4 | yml 设 `instructions.variables.{name: "Alice"}` + inline `"Hi {{name}}"` | system message 含 `"Hi Alice"` 不含 `"{{name}}"` |

---

## 8. 风险与回滚

### 8.1 R-06(JDK 8 vs Spring Boot 3.2.5 + Spring AI 1.x,概率 3×影响 3=9)
- **缓解**(沿用 #001):本机用 JDK 17+ 编译,二进制 target=8;文档明示"完整 Spring AI 体验需 JDK 17+ runtime"
- **回滚**:若编译失败 → 退回 JDK 17

### 8.2 R-13(Spring AI 误用 transitive 污染,概率 2×影响 3=6)
- **缓解**(a)—(d)沿用 #001 + 本 Story 强制 0 新依赖
- **本 Story 增量验证**:
  - `mvn dependency:tree -pl lingshu-core -Dverbose=true` 与 #001 baseline diff = 0
  - 无 `spring-ai-*` 新增子树
  - 无 Jackson / Lombok / OTel transitive 变化
- **回滚**:若 binary 膨胀 > 35MB → 检查是否误引 `spring-boot-starter` 全家桶

### 8.3 R-09(第三方 Provider transitive 污染,概率 2×影响 3=6)
- **缓解**:`spring-ai-anthropic` 标 `<scope>compile</scope>`,其他 provided(沿用 #001)
- **回滚**:若冲突 → 升级 / 降级到兼容版本

### 8.4 实施期意外 — `DefaultPromptBuilder` 重构破坏 #001 测试
- **风险**:`DefaultPromptBuilder` 现有代码从 #001 已工作,Story #002 改构造函数 + 改 5 段装配可能引入回归
- **缓解**:`DefaultPromptBuilderTest`(新)覆盖所有 5 段 + `AgentFactoryIntegrationTest`(已有)覆盖 wiring
- **回滚**:若回归 → git revert Story #002 commit,等修复后重提

### 8.5 实施期意外 — 4 个新 MemorySource Provider 同名冲突
- **风险**:若有重复 name() 注册,SlotRouter 启动期 priority 选择可能误选
- **缓解**:4 个默认 name 全部唯一 + 启动日志 assertion(已有 `MemorySourceRouterTest#constructor_resolvesAllFourDefaults`)
- **回滚**:若冲突 → 检查 spring component scan 包范围

---

## 9. 文档同步

- [x] `.specify/memory/constitution.md`(已蒸馏 2026-09-20,#001 已用)
- [x] `specs/002-identity-instructions-memory/spec.md`(已完成)
- [x] `specs/002-identity-instructions-memory/checklists/requirements.md`(已完成,16/16 pass)
- [x] `specs/002-identity-instructions-memory/research.md`(已完成,D-01—D-08)
- [x] `specs/002-identity-instructions-memory/data-model.md`(已完成,8 实体 + 关系)
- [x] `specs/002-identity-instructions-memory/contracts/{prompt-builder,memory-source,wiring-diagram}.md`(已完成)
- [x] `specs/002-identity-instructions-memory/quickstart.md`(已完成,AC-09 黑盒指南)
- [x] `specs/002-identity-instructions-memory/plan.md`(本文件)
- [ ] `README.md` 加 demo-engineer Quick Start(实施期)
- [ ] `dsh_agent_design.md` §13 changelog 加 Story #002 完成条目(PR 合入后)
- [ ] `lingshu-docs`(独立仓)留待起 `docs/concepts/identity-and-memory.md`(后续 PR)

---

## 10. 后续 Story 链接

- Story #003 `spi-slot-router` — `Provider.version()` + Slot 兼容性校验,可能扩展 `MemorySourceRouter.resolveAll` 加版本过滤
- Story #004 `tool-parallel-dispatch` — `[TOOL SCHEMAS]` 段填 `Prompt.tools` 字段(`DefaultPromptBuilder.build()` 已预留空 list)
- Story #009 `a2a-agent-card` — `Identity` 字段(`avatar` 等)会被消费生成 AgentCard
- Story #014 `session-store` — Session 持久化,`[CONVERSATION HISTORY]` 段会从 SessionStore 加载
- Story #015 `prompt-cache` — `[PROJECT MEMORY]` 段是稳定 cache key 的主要候选

---

**Plan Author**:Claude Code(基于 spec.md + dsh v1.5.34 + SOP v1.18 + constitution v1.0)
**Plan Date**:2026-09-20
**SpecKit Workflow Stage**:Phase 1 Design complete → 准备 /speckit-tasks
