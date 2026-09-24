# Plan: Story #023 `delegate-sub-agent`

> **Spec anchors**: specs/023-delegate-sub-agent/spec.md
> **Design anchors**: dsh v1.5.40 §6.6 L5030-5113 + §6.6.1 L5116-5149 + §4.10.1 硬规则 2 + §15 域字母 D + constitution v1.0
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed` / `var`(constitution §1 第 1 项 + §6 兼容性矩阵)
- **Lombok `@Value` 不可变优先** — `SubAgentType` 是 enum / `DelegateErrorCodes` 是传统常量类 / `SubAgentInheritance` 静态工具方法(不持有状态)/ `DelegateTool` 用经典 final field + 显式 ctor,避免 `record`
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — LINGS-D01(constitution §4 + spec §1 新增 D=Delegate 域)
- **性能预算 §14.15.1 不退化** — `DelegateTool.execute()` 同步调子 Agent turn(走 `Agent.runBlocking` + `done.await(turnTimeoutSeconds, SECONDS)`),父 turn 子 turn 串行(子 turn 内自己跑 ReAct),不退化 LLM 流式首 token / tool 调用 P99
- **`ToolExecutor.dispatch()` 5 步流水线不变** — `DelegateTool.execute()` 在第 5 步被调,不绕过任何一步(§4.10.1 硬规则 2)
- **0 新 Maven 依赖** — `EnumMap` + `Arrays.stream` + `Collections.emptySet()` JDK 8 内置,Jackson `JsonNode` 已锁(constitution §2 + R-13 mitigation (d) 强制)
- **`AgentConfig` 嵌套 `Delegate` + `TypeConfig` 8 字段直接复用** — 已就位于 `AgentConfig.java` L104-116(`#001` baseline),**0 改动**
- **`AgentFactory.create(AgentConfig)` 单参入口复用** — 子 Agent 走 fresh session,符合 dsh §7.1 不变项,**0 改动**
- **`Agent.runBlocking(String)` 复用替代 `AgentCollectors.collectBlocking`** — dsh §6.6 L5107 是 design-time 表达,实际 `DefaultAgent.runBlocking` 已实现同步收集,**0 新类**
- **测试用裸 `AnnotationConfigApplicationContext`,不引 `@SpringBootTest`**(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue,沿用 `#007` / `#021b` / `#022` 经验)

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 接口 / 注解 / 类 | 路径 | 角色 |
|---|---|---|
| `SubAgentType` enum | `lingshu-core/src/main/java/ai/lingshu/core/agent/SubAgentType.java` | `EXPLORE / ENGINEER / REVIEWER` 3 值 enum + `configKey` / `promptFile` + `key()` / `promptFile()` / `fromKey(String)` / `allKeys()`(dsh §6.6 L5033-5052 字面落地) |
| `DelegateErrorCodes` 静态常量类 | `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateErrorCodes.java` | `LINGS_D01 = "LINGS-D01"` 常量集中(对齐 `#021b` `McpErrorCodes` + `#022` `ToolErrorCodes` 模式) |
| `SubAgentInheritance` 静态工具类 | `lingshu-core/src/main/java/ai/lingshu/core/agent/SubAgentInheritance.java` | `inheritFromParent(AgentConfig parent, AgentConfig child, SubAgentType type) → AgentConfig`(dsh §6.6.1 L5131-5146 字面落地,**手工**拼接 28 字段避免 `toBuilder()` —— 因 `AgentConfig` 是 `@Value`,未启用 `@Builder(toBuilder=true)`) |
| `DelegateTool implements Tool` | `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateTool.java` | 包装 `AgentFactory.create(childConfig)` + `Agent.runBlocking(prompt)`(dsh §6.6 L5054-5113 字面落地,`execute()` 内**不走** `AgentCollectors.collectBlocking`,直接复用 `Agent.runBlocking`) |
| `DelegateAutoConfiguration` | `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateAutoConfiguration.java` | `@Configuration implements InitializingBean`(对齐 `#019` `LocalToolsAutoConfiguration` 样板)+ `afterPropertiesSet()` 内构造 + `toolRegistry.register(delegateTool)`(yml 配置存在时) |

### 修改

| 接口 / 类 | 修改 |
|---|---|
| 无 | **`#023` 不修改任何已有接口** —— `AgentConfig` / `AgentConfig.Delegate` / `AgentConfig.TypeConfig` / `AgentFactory` / `Agent` / `Tool` / `ToolRegistry` / `Session` / `DefaultAgent` 全部 0 改动(spec §5 反向 AC 明确) |

**新增接口严格遵循 dsh §6.6 / §6.6.1 字面落地**,不引入新接口契约;`SubAgentInheritance` 是 `AgentConfig` 字段级继承的纯函数 helper(无状态、无 Bean),与 `#021b` `McpTransport` / `#022` `SpringAiToolAdapter` 模式对齐(同属 Tool SPI,但走 `ToolRegistry.register` 而非反射或 MCP 协议)

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/agent/SubAgentType.java` | 新增 | ~50 |
| `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateErrorCodes.java` | 新增 | ~30 |
| `lingshu-core/src/main/java/ai/lingshu/core/agent/SubAgentInheritance.java` | 新增 | ~150(28 字段手工拼接 + Identity/Instructions/Memory 三件套合并) |
| `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateTool.java` | 新增 | ~180(`loadConfigs` + `validate` + `execute` 三段) |
| `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateAutoConfiguration.java` | 新增 | ~100(`@Configuration implements InitializingBean` + `afterPropertiesSet` + `toolRegistry.register`) |
| `lingshu-core/src/test/java/ai/lingshu/core/agent/SubAgentTypeTest.java` | 新增 | ~100(L1 Unit,3 case:`allKeys()` 集合不变量 + `fromKey` happy + `fromKey` 未知 key 抛 IAE) |
| `lingshu-core/src/test/java/ai/lingshu/core/agent/SubAgentInheritanceTest.java` | 新增 | ~250(L1 Unit,8 case:`identity` 完全替换 + name 后缀 + 3 enum 各 1 + `instructions` 完全替换 + `memory` 完全替换 + 三件套 parent null 回退 defaults/empty/defaults) |
| `lingshu-core/src/test/java/ai/lingshu/core/agent/DelegateToolTest.java` | 新增 | ~280(L1+L2,6 case:`name()` 返回 "Task" + `loadConfigs` happy + `validate` 缺 key 抛 LINGS-D01 + `execute` happy mock AgentFactory + `execute` subagent_type 未知抛 IllegalArgumentException + `description` 包含 3 enum key) |
| `lingshu-core/src/test/java/ai/lingshu/core/agent/DelegateAutoConfigurationTest.java` | 新增 | ~150(L2,3 case:`afterPropertiesSet` 配置存在时注册 DelegateTool + 配置 null 时跳过 + LINGS-D01 启动期 fail-fast) |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | **不修改** —— `DelegateAutoConfiguration` 用 `@Configuration`(同 `LocalToolsAutoConfiguration` 样板,**不**依赖 spring-boot-autoconfigure,R-13 mitigation 13 项依赖预算) | 0 |

**5 核心新实现 + 1 ErrorCode 常量 + 4 测试 = 9 文件**,**0 modify**,与 ROADMAP 表第 8 行「5–6 文件」对齐(枚举 + ErrorCode + 继承 helper + Tool + AutoConfiguration)

---

## 3. 实现顺序

> **原则**:依赖方向 core 内部:`SubAgentType` enum → `DelegateErrorCodes` 常量 → `SubAgentInheritance` 纯函数(依赖 `AgentConfig` + `SubAgentType` + `Identity.defaults()` / `Instructions.empty()` / `Memory.defaults()`)→ `DelegateTool`(依赖 `SubAgentType` + `SubAgentInheritance` + `AgentFactory` + `Tool` interface)→ `DelegateAutoConfiguration`(依赖 `DelegateTool` + `ToolRegistry` + `AgentFactory`)→ 测试

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `SubAgentType` enum + `DelegateErrorCodes` 常量 | 无 | 2 文件可编译 |
| 2 | `SubAgentInheritance.inheritFromParent(...)` 纯函数 | `SubAgentType` + `AgentConfig` 28 字段手工拼接 | 1 文件 + L1 Unit 测试 |
| 3 | `DelegateTool implements Tool` | `SubAgentType` + `SubAgentInheritance` + `AgentFactory` + `Tool` interface + `ToolResult` builder + `RunResult.getFinalText()` + `Agent` interface | 1 文件 + L1 测试 |
| 4 | `DelegateAutoConfiguration implements InitializingBean` | `DelegateTool` + `AgentFactory` + `ToolRegistry` + `AgentConfig.Delegate`(从环境拿 / 编程式拿)| 1 文件 + L2 测试 |
| 5 | L2 slice / 端到端 | 全部 | `DelegateToolTest` + `DelegateAutoConfigurationTest` 跑 `AnnotationConfigApplicationContext` + 手装 |

**每步独立 commit**(`feat(agent): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 `#009d` / `#021b` / `#022` 风格)
**绝对禁止一次性 commit 5 文件**(`#021b` 反面教材,`#023` 严格离散)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 14 | `SubAgentType` 3 case(`allKeys()` 集合不变量 + `fromKey` happy + `fromKey` 未知 key 抛 IAE);`SubAgentInheritance` 8 case(identity 完全替换 + name 后缀 + 3 enum 各 1 + instructions 完全替换 + memory 完全替换 + 三件套 parent null 回退);`DelegateTool` 3 case(name 返回 "Task" + loadConfigs happy + description 包含 3 enum key) | `SubAgentTypeTest` / `SubAgentInheritanceTest` / `DelegateToolTest` |
| **L2 Slice** | 6 | `DelegateTool.execute()` happy mock AgentFactory + subagent_type 未知抛 IllegalArgumentException + validate 缺 key 抛 LINGS-D01;`DelegateAutoConfiguration` 3 case(afterPropertiesSet 注册 + 配置 null 跳过 + LINGS-D01 fail-fast) | `DelegateToolTest` / `DelegateAutoConfigurationTest` |
| **L3 Component** | —(并入 L2)| L2 已覆盖「多 Bean 协作」| — |
| **L4 Contract** | 0(无接口契约变更)| `#023` 不改 `Tool` / `ToolRegistry` interface,**无 L4** | — |
| **L5 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过,**AC-NN-1—NN-7 + AC-NN-deps-1—NN-2** 全跑通 | CI |
| **L6 Performance** | 不跑(Story 体量不达 NFR 阈值)| `DelegateTool.execute()` 串行调子 Agent turn,父 turn 子 turn 总耗时 ≤ 父 turn budget,**L6 不强制** | — |
| **L7 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 | CI |

**New Case 计数**:**20 test cases** 跨 4 文件(L1 14 + L2 6 = 20)
**ROADMAP 估算**:表第 8 行「5–6 文件」+ `delegate 领域 ≤ 80%` 覆盖率门槛(spec §4 AC-NN + plan §4)→ 20 cases 与 `#022` 同 Story 量级(32 cases)

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `AgentConfig` 字段级拼接遗漏字段(28 字段手工 `new AgentConfig(...)` 易错) | 2×2=4 | `SubAgentInheritanceTest` 8 case 覆盖 6 个 Identity 字段 + 4 个 Instructions 字段 + 2 个 Memory 字段;每个 case 断言 inherited config 与 parent 在未指定字段完全相等;compile-time `@Value` 全参构造器签名校验兜底 | revert PR;父-子继承退回「`AgentFactory.create(childConfig)` 裸调,无 Identity/Instructions/Memory 继承」 |
| **R-B** 子 Agent turn 内 Skill `/xxx` 拦截(未实现)| 2×1=2 | `#023` spec §5 反向 AC 明确**不**做子 Agent Skill 拦截;子 Agent 走 `Agent.runBlocking` 自动复用 `#020c` `continueWithUserMessageBlocking` 路径,LLM 视角下 Skill 仍可见(子 Agent 自己的 Skill registry) | 后续 OQ-Future;当前 Story 关闭 Skill 路径 |
| **R-C** `LINGS-D01` 错误码在 `validate()` 抛点路径不一致(可能抛 IllegalStateException,可能在 `Tool.execute` 抛)| 2×2=4 | spec §3 明确 `LINGS-D01` 只在 `DelegateTool.loadConfigs()` 或 `validate()` 启动期抛,**不**走 `ToolResult.error()`(`ToolResult` 无 errorCode 字段);L2 slice 测试严格断言 `IllegalStateException` message 含 `"LINGS-D01"` | 改 PR body 文档 + 后续 consolidation Story |
| **R-D** `Agent.runBlocking` 内 `done.await(turnTimeoutSeconds, SECONDS)` 0 秒时死锁 | 2×2=4 | `turnTimeoutSeconds` 默认 120(Story #001 baseline),`#023` 不引入 `timeoutSeconds=0` 路径;若用户 yml 显式设 0 → `await(0, SECONDS)` 立即返回 false,`err.get()` 为 null + `buffer` 可能空 → 抛 `RuntimeException("Agent run failed")` —— 测试覆盖 0 秒场景 | revert PR |
| **R-E** 子 Agent 复用父 Agent `ToolRegistry`(父-子共享 tool set)| 2×2=4 | 子 Agent 走 `AgentFactory.create(childConfig)` 全新 ctx,`ToolRegistry` 是 Spring 单例 Bean,父-子**共享**同一 `ToolRegistry` —— 子 Agent 通过 `TypeConfig.tools: List<String]` 过滤可见 tool;`#023` **不**做 per-子-Agent 子 ToolRegistry(over-engineering),依赖 TypeConfig.tools 显式白名单 | 后续 OQ-Future 改 per-子-Agent registry |
| **R-F** `DelegateTool.execute()` 内同步调 `child.runBlocking` 阻塞 LLM stream(`#004` `ToolExecutor` 默认同步串行)| 2×2=4 | `#023` spec §3 明确子 Agent turn 串行(不并发),符合 §4.10.1 硬规则 2 + `ToolExecutor` 默认串行 dispatch 路径;若未来要并发,改 `DelegateToolProvider` SPI(默认 priority=0 + name="default",并行版 priority=10 name="parallel") | revert PR;旧 `DelegateTool` 默认串行行为不变 |
| **R-13(已有)** `spring-ai-bom` 误用 / binary 膨胀 | 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-deps-1 + AC-NN-deps-2 + tasks.md T-dep-tree-1—T-dep-tree-4 + PR body `### R-13 dependency:tree 自查` 节(`#022` 已验证 0 binary delta,`#023` 第 8 次验证) | revert PR;旧 lingshu-core 无 DelegateTool,功能完整 |

**等级**:R-A / R-B / R-C / R-D / R-E / R-F ≤ 4 监控即可;**R-13 ≥ 6 必缓解**(mitigation (d) 8 次验证 + enforcer build fail)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `delegate.types` 示例 yaml 片段(对齐 `#019` built-in-tools 同款行文)
- [ ] `specs/023-delegate-sub-agent/quickstart.md`(本 PR 内;给 Alice 30min 跑通 delegate sub-agent hello world,模板对齐 `#009d`)
- [ ] `specs/023-delegate-sub-agent/data-model.md`(`SubAgentType` enum 字面 + `SubAgentInheritance` 字段级合并矩阵 + `AgentConfig.Delegate` 数据结构表;对齐 `#009d`)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.40 → v1.5.41` 行(本 Story 实施记录)
- [ ] `constitution.md` §4 域字母表加 `D = Delegate(子 Agent)`(8 域变 9 域)
- [ ] `constitution.md` §10 R-13 风险登记:`Story #023` 标记「已缓解」+ 第 8 次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#023` 行
- [ ] `lingshu-docs` 仓 `docs/concepts/delegate-sub-agent.md`(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.40` 维持)

---

## 7. 关键不变项(冻结)

1. `AgentConfig` 嵌套 `Delegate` + `TypeConfig` 8 字段**0 改动**(`#001` baseline 已就位)
2. `AgentFactory.create(AgentConfig)` 单参入口**0 改动**(子 Agent 走 fresh session,符合 dsh §7.1 不变项)
3. `Agent.runBlocking(String)` + `DefaultAgent` 模板**0 改动**(替代 dsh §6.6 L5107 引用 `AgentCollectors.collectBlocking`)
4. `Tool` interface 4 方法 + `ToolRegistry.register(Tool)` SPI(**0 改动**)
5. `ToolExecutor.dispatch()` 5 流水线(**第 4.10.1 硬规则 2**)**0 改动**
6. dsh §6.6 L5054-5113 代码块(`SubAgentType` enum + `DelegateTool` 字面落地,**无新接口引入**)
7. dsh §6.6.1 L5131-5146 字面(`inheritFromParent` 字段级合并 + Identity default 回退,**手工**拼接非 `toBuilder()`)
8. constitution v1.0 §1—§9 全部不变,**只** §4 域字母表 + §10 R-13 风险状态更新
9. dsh §15 域字母表:新增 `D = Delegate`(8 域变 9 域),**只**新增 `LINGS-D01 DELEGATE_CONFIG_INVALID`,**C/S/L/T/X/R/A/Z 域编号全部不动**
10. **0 新 Maven 依赖**(R-13 mitigation (d) 第 8 次验证)
11. **0 modify**(纯新增;`AgentConfig` / `AgentFactory` / `Agent` / `Tool` / `ToolRegistry` / `DefaultAgent` 全部不变;接口契约向后兼容)

---

**Plan writer**: Claude Code
**Plan date**: 2026-09-24
**Plan version**: v0.1 Draft