# Plan: Story 001 zero-config-bootstrap

## 约束(从 constitution 继承)

- **JDK 8 only**(constitution §2 #1 + dsh §10.1 L6309)
- **Lombok `@Value` / `@Builder` 不可变**(constitution §1 #1 + dsh §1 #1)
- 所有新 ErrorCode 走 `LINGS-<域><编号>` 命名(constitution §4 + dsh §15.9)
- 性能预算 §3 NFR 不退化(constitution §3 + dsh §14.15.1)
- **禁用** `record` / `sealed` / `var` / `List.of` / text blocks(constitution §1 #1)
- **禁用** Spring AI 自动 tool 执行(constitution §1 + dsh §4.10.1 硬规则 2)
- **Provider 必须显式映射**(constitution §1 + dsh §4.10.1 硬规则 3)
- **R-13 mitigation (d) 强制**:`mvn dependency:tree` 自查 + banned-dependencies enforcer(constitution §10)

---

## 1. 涉及接口(新增 / 修改)

### 新增 — 9 Slot 接口(包 `ai.lingshu.core.api`)

| 接口 | 来源章节 | 字段数 |
|---|---|---|
| `LlmProvider` | dsh §4.10 | `name()` / `priority()` / `stream(Prompt, TurnContext, Subscriber) → CompletableFuture<LlmResponse>` |
| `Tool` + `ToolExecutor` | dsh §4.6 | `name()` / `description()` / `inputSchema()` / `execute(ToolCall, ToolExecutionContext)` / `dispatch(...)` |
| `Sandbox` | dsh §4.7 | `name()` / `priority()` / `exec(SandboxContext, command)` / `fetch(SandboxContext, url)` |
| `Skill` + `SkillSource` | dsh §4.6 / §1.5.2 | `discover(SkillContext) → List<Skill>` / `Skill: name / description / instructions / isUserInvoked` |
| `SessionStore` | dsh §4.8 / §14.7 | `save(Session)` / `load(sessionId)` / `delete(sessionId)` |
| `Compactor` | dsh §4.9 / §14.11 | `shouldCompact(Prompt)` / `compact(TurnContext)` |
| `PromptBuilder` | dsh §4.5 / §4.5.1 | `build(TurnContext) → Prompt` |
| `FlowEngine` | dsh §4.11 / §4.10.1 硬规则 1 | `runTurn(TurnContext, Subscriber<AgentEvent>)` |
| `A2aTransport` | dsh §5.6 | `name()` / `priority()` / `publishAgentCard(AgentConfig)` / `sendTask(Task)` |

### 新增 — 3 个 Router(包 `ai.lingshu.core.spi`,dsh §5.3.1.0)

| Router | 用途 | Story 后续 |
|---|---|---|
| `PermissionPolicyRouter` | Slot 3 子接口 | #001 落地 |
| `ToolExecutorRouter` | Slot 2 | #001 落地 |
| `FlowEngineRouter` | Slot 8 | #001 落地(注入 `AgentFactory`,**不在** SlotResolver 字段里) |
| `LlmProviderRouter` | Slot 1 | #001 落地(为 AC-01-1 黑盒验证需要) |

> 其他 4 个 Router(PromptBuilderRouter / CompactorRouter / SessionStoreRouter / MemorySourceRouter / A2aTransportRouter)由各自 Story 落地。

### 新增 — 4 个默认 Provider(包 `ai.lingshu.core.impl.<slot>`)

| Provider | name | priority | 返回类型 | 复杂度 |
|---|---|---|---|---|
| `StrictPermissionPolicyProvider` | `"strict"` | 10 | `StrictPermissionPolicy`(白名单 allow + 其余 deny)| 中 |
| `DefaultToolExecutorProvider` | `"default"` | 0 | `DefaultToolExecutor`(同步串行 dispatch)| 中 |
| `LinearTurnEngineProvider` | `"linear"` | 0 | `LinearTurnEngine`(ReAct Loop 自实现)| **重**(AC-01-5) |
| `AnthropicLlmProviderFactory`(本 Story 最小版,Story #003 扩)| `"anthropic"` | 10 | `AnthropicLlmProvider`(Spring AI ChatModel 包装,显式 providerMap)| 中 |

### 新增 — Agent 主类(包 `ai.lingshu.core.api` + `ai.lingshu.core.impl.runtime`)

- `Agent` 接口(dsh §4.12.3)— `run(input) / runBlocking(input) / session() / config()`
- `DefaultAgent`(dsh §4.12.3)— 4 final 字段(config / session / engine / toolPool)
- `DefaultTurnContext`(dsh §4.12.1)— AtomicBoolean done + synchronized history
- `AgentFactory`(dsh §7.1)— `@Component` + `@Autowired 4 Router` + 7 项校验 + `create(cfg)` + `defaultConfig()`
- `RunResult`(dsh §4.12.3)— finalText / turns / usage / stopReason / elapsedMillis

### 新增 — AgentConfig @Value 主类(包 `ai.lingshu.core.config`)

- `AgentConfig`(dsh §4.12.2,本 Story 27 字段 + Identity/Instructions/Memory 业务三件套)
- 内部:`Llm` / `Prompt` / `Sandbox` / `Identity` / `Instructions` / `Memory` / `ClaudeMd` 7 个 `@Value` 嵌套类
- `AgentConfig.defaults()` 静态工厂方法(AC-01-2)

### 修改 — 父 POM(仓根 `pom.xml`)

- 加 `spring-ai-bom` 1.0.0-M6 到 `<dependencyManagement>`(R-13 控制)
- 加 `maven-enforcer-plugin` 到 `<build><plugins>`,强制 JDK 8 + Lombok 1.18.30

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 | 关键约束 |
|---|---|---|---|
| **根 `pom.xml`** | 新增 | ~90 | 父 POM + 5 子模块 + 13 项依赖 |
| `lingshu-core/pom.xml` | 新增 | ~50 | 复用父 BOM + Lombok + reactive-streams + jackson + 4 测试依赖 |
| `lingshu-a2a-client/pom.xml` | 新增 | ~15 | 空骨架(Story #009 填) |
| `lingshu-a2a-server/pom.xml` | 新增 | ~15 | 空骨架(Story #009 填) |
| `lingshu-examples/pom.xml` | 新增 | ~20 | 父 POM + placeholder |
| `lingshu-cli/pom.xml` | 新增 | ~15 | 空骨架 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/LlmProvider.java` | 新增 | ~15 | Slot 1 接口 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/Tool.java` | 新增 | ~10 | Slot 2 接口 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/ToolExecutor.java` | 新增 | ~10 | Slot 2 接口 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/Sandbox.java` | 新增 | ~10 | Slot 3 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/Skill.java` + `SkillSource.java` | 新增 | ~15 | Slot 4 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/SessionStore.java` | 新增 | ~10 | Slot 5 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/Compactor.java` | 新增 | ~10 | Slot 6 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/PromptBuilder.java` | 新增 | ~10 | Slot 7 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/FlowEngine.java` | 新增 | ~10 | Slot 8 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/A2aTransport.java` | 新增 | ~10 | Slot 9 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/Agent.java` | 新增 | ~10 | dsh §4.12.3 |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/SlotProvider.java` | 新增 | ~15 | dsh §5.1 通用 Provider |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/SlotRouter.java` | 新增 | ~70 | dsh §5.2 同名竞争 + 启动日志 |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/PermissionPolicyRouter.java` | 新增 | ~15 | dsh §5.3.1.0 |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/ToolExecutorRouter.java` | 新增 | ~15 | dsh §5.3.1.0 |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/FlowEngineRouter.java` | 新增 | ~15 | dsh §5.3.1.0 |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/LlmProviderRouter.java` | 新增 | ~15 | dsh §5.3.1.0 |
| `lingshu-core/src/main/java/ai/lingshu/core/config/AgentConfig.java` | 新增 | ~180 | dsh §4.12.2,27 字段 + 7 嵌套类 |
| `lingshu-core/src/main/java/ai/lingshu/core/event/AgentEvent.java` | 新增 | ~50 | dsh §4.4 |
| `lingshu-core/src/main/java/ai/lingshu/core/event/Message.java` | 新增 | ~70 | dsh §4.1 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicy.java` | 新增 | ~50 | 白名单 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProvider.java` | 新增 | ~30 | dsh §5.5 模板 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutor.java` | 新增 | ~60 | 5 步流水线 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutorProvider.java` | 新增 | ~30 | dsh §5.5 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | 新增 | ~120 | ReAct 自实现(dsh §6.1 + §4.10.1 硬规则 1)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngineProvider.java` | 新增 | ~30 | dsh §5.5 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` | 新增 | ~100 | Spring AI ChatModel + 显式 providerMap(dsh §4.10.1 硬规则 3)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderAutoConfiguration.java` | 新增 | ~30 | @Bean(name="llmProviderProvider_anthropic") |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java` | 新增 | ~50 | dsh §4.12.3 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java` | 新增 | ~50 | dsh §4.12.1 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | 新增 | ~120 | dsh §7.1 + 7 项校验 |
| `lingshu-core/src/main/java/ai/lingshu/core/api/RunResult.java` | 新增 | ~15 | dsh §4.12.3 |
| `lingshu-examples/demo-empty/pom.xml` | 新增 | ~30 | 依赖 lingshu-core + spring-boot-starter |
| `lingshu-examples/demo-empty/src/main/java/ai/lingshu/examples/demoempty/DemoEmptyApplication.java` | 新增 | ~60 | Spring Boot main |
| `lingshu-examples/demo-empty/src/main/resources/application.yml` | 新增 | ~5 | 空 yml |
| `lingshu-core/src/test/java/ai/lingshu/core/SmokeTest.java` | 新增 | ~15 | 编译验证 |
| `lingshu-core/src/test/java/ai/lingshu/core/config/AgentConfigDefaultsTest.java` | 新增 | ~80 | AC-01-2 验证 |
| `lingshu-core/src/test/java/ai/lingshu/core/spi/SlotRouterTest.java` | 新增 | ~100 | AC-01-4 验证 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryTest.java` | 新增 | ~120 | AC-01-3 验证 |
| `README.md` | 新增/大幅扩展 | ~150 | 加 Quick Start + AC-01 命令 |

**总计**:~50 个文件,~2000 行代码。

---

## 3. 实现顺序

1. **父 POM + 5 子模块骨架**(Step -1,Maven Skeleton Init)
   - `pom.xml`(父)+ 5 子模块 `pom.xml`
   - `mvn validate -N` 通过
2. **9 Slot 接口骨架**(dsh §4 全章 + SOP §8.5)
   - `ai.lingshu.core.api.*` 9 个接口(方法体可空,签名按 dsh §4.6 §4.7 §4.8 §4.9 §4.10 §4.11 §5.6)
   - `mvn -pl lingshu-core compile` 通过
3. **AgentConfig @Value 主类**(dsh §4.12.2 + 业务三件套)
   - 27 字段 + `defaults()` 静态方法
4. **SlotRouter 通用父类**(dsh §5.2)+ SlotProvider 接口(dsh §5.1)
   - 同名竞争 + 启动日志
5. **4 个 Router concrete 类**(dsh §5.3.1.0 模板)
   - `PermissionPolicyRouter` / `ToolExecutorRouter` / `FlowEngineRouter` / `LlmProviderRouter`
6. **3 个默认 Provider + 实现**(dsh §5.5 模板)
   - `StrictPermissionPolicyProvider` + `StrictPermissionPolicy`(白名单: cat/head/grep/ls/find)
   - `DefaultToolExecutorProvider` + `DefaultToolExecutor`(5 步流水线占位,无 Tool 时 noop)
   - `LinearTurnEngineProvider` + `LinearTurnEngine`(ReAct Loop 自实现,~ 数十行)
7. **AnthropicLlmProvider**(dsh §4.10.1 硬规则 3)
   - `AnthropicLlmProvider` + `AutoConfiguration` + `LlmProvider` 包装 Spring AI ChatModel
   - `providerMap` 显式映射(目前只有 anthropic 一项)
8. **AgentFactory + DefaultAgent + DefaultTurnContext + RunResult**(dsh §4.12 + §7.1)
   - 7 项 fail-fast 校验
   - 4 final 字段构造
9. **demo-empty 示例**(AC-01-1 黑盒验证用)
   - Spring Boot main + 空 yml + 读 `ANTHROPIC_API_KEY` 环境变量
10. **单元测试**(L1 + L2)
    - `AgentConfigDefaultsTest`(AC-01-2 全部 27 字段断言)
    - `SlotRouterTest`(AC-01-4 同名竞争 + 启动日志)
    - `AgentFactoryTest`(AC-01-3 7 项 fail-fast)
    - `LinearTurnEngineTest`(AC-01-5 mock LlmProvider 跑一轮)
11. **集成测试**(L5 E2E)
    - `demo-empty` 包 jar + `time java -jar` 跑 AC-01-1
    - 读 Claude Code 配置取 `ANTHROPIC_API_KEY`
12. **R-13 mitigation (d) 自查**
    - `mvn dependency:tree -pl lingshu-core -Dverbose`
    - 筛 `spring-ai-*` / `io.netty:*` / `com.fasterxml.jackson.*` 子树
    - 贴关键子树到 PR body `### R-13 dependency:tree 自查` 节
13. **文档同步**
    - `README.md` 加 Quick Start + AC-01 命令
    - `dsh_agent_design.md` §13 changelog 加 Story #001 完成条目(CLAUDE.md / SKILL 同步)
14. **Git commit + PR**
    - 标题:`feat(agent): Story #001 zero-config-bootstrap — AgentFactory + 9 Slot 骨架 + 27 字段默认`
    - Body 贴 spec.md + plan.md + tasks.md + AC-01 验证输出

---

## 4. 测试策略(constitution §5 + dsh §14.15.7)

- **L1 Unit**:
  - `AgentConfigDefaultsTest` — 27 字段默认值覆盖率 100%
  - `SlotRouterTest` — 同名竞争 3 场景(无冲突 / priority 胜出 / 同 priority tie)覆盖率 100%
  - `AgentFactoryTest` — 7 项 fail-fast 7 场景(每项各 1 个 test)
  - `LinearTurnEngineTest` — ReAct 5 路径(empty tools / 1 tool call / max steps / cancel / error)
  - `AnthropicLlmProviderTest` — Mock Spring AI ChatModel(不调真实 API)
- **L2 Slice**:
  - `AgentFactoryIntegrationTest` — Spring Boot 上下文起来,4 Router 都注入 0+ Provider,启动日志格式
- **L5 E2E**:
  - AC-01-1:`time java -jar lingshu-examples/demo-empty.jar` ≤ 30s,stderr 零 ERROR
  - AC-01-2:`factory.defaultConfig()` 27 字段断言
  - AC-01-3:7 项 fail-fast
  - AC-01-4:SlotRouter 启动日志
  - AC-01-5:LinearTurnEngine 单轮 ReAct
  - AC-01-6:`mvn validate` + `mvn -pl lingshu-core -am compile` 通过

---

## 5. 风险与回滚

- **风险 R-06**(JDK 8 vs Spring Boot 3.2.5 + Spring AI 1.x 矛盾,概率 3×影响 3=9)
  - 缓解:本机用 JDK 23 编译通过 + binary target=8;文档明示"完整 Spring AI 体验需 JDK 17+ runtime"
  - 回滚:若编译失败 → 退回 JDK 17
- **风险 R-13**(Spring AI 误用 transitive 污染,概率 2×影响 3=6)
  - 缓解(a)只引 `spring-ai-core` + `spring-ai-anthropic` starter,**不**引 `spring-ai-spring-boot-starter` 全家桶
  - 缓解(b) `maven-enforcer-plugin` + banned-dependencies 规则在 build 阶段 fail
  - 缓解(c) binary size baseline < 35MB(本 Story 期望 ~25MB)
  - 缓解(d) `mvn dependency:tree` 自查 + 贴 PR body
  - 回滚:若 binary 膨胀 > 35MB → 改用手写 HttpClient(Story #003 DeepSeek 模式)
- **风险 R-09**(第三方 Provider transitive 污染,概率 2×影响 3=6)
  - 缓解:`spring-ai-anthropic` 标 `<scope>compile</scope>`(必须编译期可用),其他依赖 `provided`
  - 回滚:若冲突 → 升级 / 降级到兼容版本
- **风险 实施期意外**:Spring AI ChatModel API 在 1.0.0-M6 vs 1.0.0-GA 可能 drift
  - 缓解:锁 BOM 1.0.0-M6(本 Story 用)+ Story #003 升级评估

---

## 6. 文档同步

- [x] `.specify/memory/constitution.md`(已蒸馏 2026-09-20)
- [ ] `README.md` 加 Quick Start + AC-01 命令 + 工程结构图
- [ ] `dsh_agent_design.md` §13 changelog 加 Story #001 完成条目
  - 条目格式:`v0.1.0-001 (Story #001) — AgentFactory + 9 Slot 骨架 + 27 字段默认 + 空 yml 启动 + AC-01 黑盒过`
- [ ] `CLAUDE.md` §1.3 版本号同步(若 plan 改了 package 命名)
- [ ] `lingshu-docs`(独立仓)留待后续 Story #001 完成时起 `docs/concepts/agent-factory.md`

---

**Plan Author**:Claude Code(基于 spec.md + dsh v1.5.34 + SOP v1.18)
**Plan Date**:2026-09-20