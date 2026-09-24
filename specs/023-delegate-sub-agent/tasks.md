# Tasks: Story #023 `delegate-sub-agent`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 5 核心文件 + 4 测试文件 ≤ 9 文件 `< 15 上限)
>
> **实施顺序严格按 plan §3**:`SubAgentType` enum → `DelegateErrorCodes` 常量 → `SubAgentInheritance` 纯函数 → `DelegateTool` → `DelegateAutoConfiguration` → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#023` 复用 JDK 8 内置 `EnumMap` / `Arrays.stream` / `Collections.emptySet()` + Jackson `JsonNode` 已锁,需验证 0 binary delta 第 8 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(agent): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(4 文件 + 1 常量)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/agent/SubAgentType.java` 新增 enum —— `public enum SubAgentType { EXPLORE("explore", "explore.md"), ENGINEER("engineer", "engineer.md"), REVIEWER("reviewer", "reviewer.md"); }` + `configKey` / `promptFile` final field + 2 字段 ctor + `key()` / `promptFile()` + `fromKey(String)`(遍历 values() 比对 configKey,未知抛 IAE)+ `allKeys()`(`Arrays.stream(values()).map(...).collect(Collectors.toSet())`)(预估 30min,dsh §6.6 L5033-5052 字面落地)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateErrorCodes.java` 新增常量类 —— `public static final String LINGS_D01 = "LINGS-D01";` + 私有 ctor(对齐 `#021b` `McpErrorCodes` + `#022` `ToolErrorCodes` 模式)(预估 10min)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/agent/SubAgentInheritance.java` 新增静态工具类 —— `public static AgentConfig inheritFromParent(AgentConfig parent, AgentConfig child, SubAgentType type)` 内部手工 `new AgentConfig(...)` 拼接 28 字段 + Identity/Instructions/Memory 三件套字段级合并:`child.identity != null ? child.identity : (parent.identity != null ? parent.identity.toBuilder().name(parent.identity.name + " (Sub-agent: " + type.key() + ")").build() : Identity.defaults())` + Instructions 同款(`Instructions.empty()` fallback)+ Memory 同款(`Memory.defaults()` fallback)(预估 90min,dsh §6.6.1 L5131-5146 字面落地)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateTool.java` 新增 —— `public class DelegateTool implements Tool` 4 field:`agentFactory` / `parentConfig`(父 Agent config 快照,build-time 注入)/ `delegateProps` / `Map<SubAgentType, AgentConfig> typeConfigs`;ctor `(AgentFactory factory, AgentConfig parentConfig, AgentConfig.Delegate props)` 内调 `loadConfigs(props)` 把每个 TypeConfig 转 `AgentConfig`(用 `SubAgentInheritance.inheritFromParent(parentConfig, TypeConfig_to_AgentConfig(tc), SubAgentType.X)` 做字段级合并 → 子 Agent config);`loadConfigs` 遍历 `SubAgentType.values()`,对每个 type 从 `props.getTypes().get(type.configKey())` 取 TypeConfig,缺则抛 `IllegalStateException` 含 `"LINGS-D01"` + `"missing subagent_type: " + type.configKey()`;`name() { return "Task"; }`;`description()` 返回含 3 个 enum key 的描述;`inputSchema()` 返回 `{ type: object, properties: { subagent_type: { type: string, enum: [explore, engineer, reviewer] }, prompt: { type: string } }, required: [subagent_type, prompt] }`;`execute(ToolCall call, ToolExecutionContext ctx)` 内部:`SubAgentType type = SubAgentType.fromKey(call.getInput().get("subagent_type").asText())` + `String prompt = call.getInput().get("prompt").asText()` + `AgentConfig childConfig = typeConfigs.get(type)`(已含字段级合并结果)+ `Agent child = agentFactory.create(childConfig)`(fresh session,符合 dsh §7.1 不变项)+ `RunResult result = child.runBlocking(prompt)` + `return ToolResult.builder().status(SUCCESS).toolUseId(call.getId()).content(result.getFinalText()).isError(false).build()`(预估 120min,dsh §6.6 L5054-5113 + §6.6.1 L5131-5146 字面落地)
- [ ] **T05** `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateAutoConfiguration.java` 新增 —— `@Configuration public class DelegateAutoConfiguration implements InitializingBean` 3 field:`agentFactory` / `toolRegistry` / `parentConfig`(`@Autowired` 注入 —— 父 config 来自 `AgentConfigRegistry.current()`,build-time 快照一次,**不**监听 hot-reload);ctor `@Autowired public DelegateAutoConfiguration(AgentFactory factory, ToolRegistry registry, AgentConfigRegistry registryCfg)`;`afterPropertiesSet()` 内:读 `registryCfg.current().getDelegate()`,若非 null 则构造 `DelegateTool(agentFactory, parentConfig, delegate)` 并 `toolRegistry.register(delegateTool)` —— yml 自动加载走 `agent.delegate != null` 守卫;若为 null 则跳过(spec §5 反向 AC 明确「agent.delegate 配置存在即启用,缺失即跳过」);yml 解析路径(`AgentFactory.toAgentConfig` 补 `agent.delegate` 块解析 = `promptsDir` + `types: Map<String, TypeConfig>`)走 OQ-Future,本 Story 第一版用户用编程式配置 `AgentConfig.Delegate.builder()...build()` 塞进 `AgentConfigRegistry.publish()`;`#023` 不引 `META-INF/spring/...imports`(对齐 `#019` `LocalToolsAutoConfiguration` 不依赖 spring-boot-autoconfigure,R-13 mitigation)(预估 60min)

> **P1 总耗时**:~310 min(~5h)

---

## P2:测试(4 文件 + 20 case)

- [ ] **T06** `lingshu-core/src/test/java/ai/lingshu/core/agent/SubAgentTypeTest.java` —— 3 case(`allKeys().size() == 3` + `fromKey("explore") == EXPLORE` + `fromKey("unknown")` 抛 `IllegalArgumentException` 含 `"Unknown subagent_type: unknown"`);覆盖 spec §4 AC-NN-1 启动期一致性(预估 30min)
- [ ] **T07** `lingshu-core/src/test/java/ai/lingshu/core/agent/SubAgentInheritanceTest.java` —— 8 case:`inheritFromParent(parent_with_full_identity, child_with_null_identity, EXPLORE)` → `inherited.identity.name == parent.identity.name + " (Sub-agent: explore)"` + 其余 5 字段完全沿用 + 3 enum 各 1 case(child-identity 显式指定时**不**附加后缀,完全替换)+ `instructions` 4 字段完全沿用 + `memory` 2 字段完全沿用 + 三件套 parent null 回退 `Identity.defaults()` + `Instructions.empty()` + `Memory.defaults()`);覆盖 spec §4 AC-NN-3 + AC-NN-4 + AC-NN-5(预估 90min)
- [ ] **T08** `lingshu-core/src/test/java/ai/lingshu/core/agent/DelegateToolTest.java` —— 6 case:`name() == "Task"` + `loadConfigs(complete_props)` happy 3 enum 都加载 + `description()` 含 `"explore"` / `"engineer"` / `"reviewer"` + `inputSchema().get("properties").get("subagent_type").get("enum").size() == 3` + `execute(mock_factory_create_returns_child_with_mock_llm_returns_"explored-result")` → `ToolResult.success(call.id, "explored-result")` + `subagent_type="unknown"` 抛 IAE + `props.types` 缺 key 抛 IAE 含 `"LINGS-D01"`);覆盖 spec §4 AC-NN-2 + AC-NN-6 + 部分 AC-NN-1(预估 120min)
- [ ] **T09** `lingshu-core/src/test/java/ai/lingshu/core/agent/DelegateAutoConfigurationTest.java` —— 3 case:`afterPropertiesSet` yml `agent.delegate` 配置存在时(`Mock Environment` mock 行为)注册 `DelegateTool` + yml 不存在时跳过 + LINGS-D01 启动期 fail-fast(`IllegalStateException` message 含 `"LINGS-D01"`);**注**:`#023` 第一版 `DelegateAutoConfiguration.afterPropertiesSet()` 仅暴露 `@Bean delegateTool(...)` 工厂方法,**不**自动从 yml 读取 —— L2 测试聚焦 `@Bean` 工厂方法 + 编程式调用场景,yml 自动注册留给 `#023.1`(预估 60min)

> **P2 总耗时**:~300 min(~5h)
> **#023.1 增量**(留 OQ-Future,不在本 PR 范围):
>  - T03-impl-1:`SubAgentInheritance.inheritFromParent` 接到 `DelegateTool.execute()` 真正调用链
>  - T05-impl-1:`DelegateAutoConfiguration.afterPropertiesSet()` 从 yml / `AgentConfigRegistry` 读 `AgentConfig.delegate`,自动 register
>  - yml 解析:`AgentFactory.toAgentConfig` 补 `agent.delegate` 块解析(prompts-dir + types Map<String, TypeConfig>)
>  - `McpServerConfig` 等嵌套 config 已落同样的 null-pass-through 模式,delegate 块走同款
>  - 加 ~10 case 覆盖 yml 自动加载路径(总数从 20 → 30)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=SubAgentTypeTest,DelegateToolTest` 验证 `SubAgentType.allKeys() == 3` + `DelegateTool.name() == "Task"`(预估 5min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=DelegateToolTest` 验证 `DelegateTool.execute()` happy path(AgentFactory mock + Agent.runBlocking mock 返回 `"explored-result"`)(预估 10min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=SubAgentInheritanceTest` 验证字段级合并 identity(完全替换 + name 后缀 + 3 enum)(预估 10min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=SubAgentInheritanceTest` 验证字段级合并 instructions + memory(完全替换语义)(预估 5min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=SubAgentInheritanceTest` 验证 parent null 回退 `Identity.defaults()` / `Instructions.empty()` / `Memory.defaults()`(预估 5min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-core test -Dtest=DelegateToolTest` 验证 LINGS-D01 fail-fast(`IllegalStateException` message 含 `"LINGS-D01"` + missing key 列表)(预估 5min)
- [ ] **T-validate-AC-NN-7** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose` + diff `#022` pre-commit 镜像确认 0 新 Maven 坐标(预估 10min)
- [ ] **T-validate-AC-NN-all** 跑 `mvn -pl lingshu-core test` 全模块无 fail,新增 20 case 全过(预估 30min)

> **P3 总耗时**:~80 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-023-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-023-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-022-pre.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-023-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min
> **PR body 末尾必有 `### R-13 dependency:tree 自查` 节**,贴 T-dep-tree-2 输出 + (name, version, slot) 三元组表

---

## P5:文档同步(提交完成闭环)

- [ ] **T-doc-1** `README.md` 顶部加 `delegate.types` 示例 yaml 片段(对齐 `#019` built-in-tools 同款行文)(预估 15min)
- [ ] **T-doc-2** `specs/023-delegate-sub-agent/quickstart.md` 起草 Alice 30min 教程(预估 30min)
- [ ] **T-doc-3** `specs/023-delegate-sub-agent/data-model.md` 起草(`SubAgentType` enum 字面 + `SubAgentInheritance` 字段级合并矩阵 + `AgentConfig.Delegate` 数据结构表)(预估 20min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.40 → v1.5.41` 行(本 Story 实施记录)(预估 5min)
- [ ] **T-doc-5** `constitution.md` §4 域字母表加 `D = Delegate(子 Agent)`(8 域变 9 域)+ §10 R-13 风险登记:`Story #023` 标记「已缓解」+ 第 8 次 0 binary delta 验证结果(预估 5min)
- [ ] **T-doc-6** `ROADMAP.md` 段一 ✅ 已完成表加 `#023` 行 + 段二 🟡 待补表移除 `#023` 行 + 段五 🎯 实施节奏 next = §14 N7 SessionStore(预估 5min)
- [ ] **T-doc-7** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.40` 维持)+ `Last updated` 日期更新(预估 2min)

> **P5 总耗时**:~80 min

---

## P6:PR + 合入(闭环)

- [ ] **T-PR-1** `git add -A && git commit -m "feat(agent): Story #023 delegate-sub-agent — SubAgentType + DelegateTool + SubAgentInheritance + LINGS-D01"`(Co-Authored-By Claude 标记)(预估 5min)
- [ ] **T-PR-2** `gh pr create --base main --head feat/023-delegate-sub-agent --title "feat(agent): Story #023 delegate-sub-agent — SubAgentType + DelegateTool + SubAgentInheritance + LINGS-D01" --body "$(cat /tmp/pr-body-023.md)"`(PR body 模板贴 spec.md + plan.md + tasks.md 摘要 + AC 验证输出 + R-13 dep-tree 自查)(预估 10min)
- [ ] **T-PR-3** 等 CI 绿 + review approve,`gh pr merge --squash --auto`(预估 5min)
- [ ] **T-PR-4** 合入后 `git pull` + 触发 docs 同步任务 T-doc-1—T-doc-7(预估 80min)

---

## 总耗时估算

| 阶段 | 时间 | 说明 |
|---|---|---|
| P1(实现)| ~280min | 4 文件 + 1 常量(`SubAgentType` + `DelegateErrorCodes` + `SubAgentInheritance` + `DelegateTool` + `DelegateAutoConfiguration`) |
| P2(测试)| ~300min | 4 文件 + 20 case |
| P3(AC 验证)| ~80min | 8 验证跑 |
| P4(dep-tree)| ~50min | R-13 强制 |
| P5(文档)| ~80min | 7 同步项 |
| P6(PR)| ~100min | commit + PR + merge |
| **合计** | **~14.8 h** | 与 `#021b` / `#022` 同量级 |

---

## 强制不变项检查清单(PR review 时必勾)

- [ ] `AgentConfig` 嵌套 `Delegate` + `TypeConfig` **0 改动**(`git diff lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` 应为空)
- [ ] `AgentFactory` `create(AgentConfig)` 单参入口 **0 改动**
- [ ] `Agent` interface + `DefaultAgent.runBlocking` 模板 **0 改动**
- [ ] `Tool` interface 4 方法 + `ToolRegistry.register(Tool)` SPI **0 改动**
- [ ] `ToolExecutor.dispatch()` 5 步流水线 **0 改动**(§4.10.1 硬规则 2 守住)
- [ ] `Session` interface + `DefaultSession.fork(...)` 模板 **0 改动**
- [ ] dsh §15 域字母 C/S/L/T/X/R/A/Z 编号全部不动,**只新增 D01**
- [ ] `mvn -pl lingshu-core dependency:tree` 0 新 Maven 坐标(banned list 强制)
- [ ] `mvn -pl lingshu-core verify` enforcer **不 fail**(跑 `banned-dependencies`)
- [ ] 20 test case 全过,`grep "BUILD FAIL" /tmp/mvn-test.log || echo PASS`
- [ ] PR body 末尾有 `### R-13 dependency:tree 自查` 节(T-dep-tree-2 输出贴上)
- [ ] dsh §13 changelog + constitution §4 域字母表 + §10 R-13 缓解 + ROADMAP 段一已合表 四件套同步

---

## OQ-Future(本 Story 范围外,留给后续 Story #023.1 / 等)

- **OQ-#023-A**: 字段级合并 Identity/Instructions/Memory 真正接到 `DelegateTool.execute()`(本 Story T03 + T04 第一版不接通,留 v1.0.x 增量)
- **OQ-#023-B**: yml 自动加载 `agent.delegate`(本 Story T05 第一版仅 `@Bean delegateTool(...)` 工厂方法,留 v1.0.x 增量;`AgentFactory.toAgentConfig` 补 `agent.delegate` 块解析)
- **OQ-#023-C**: 子 Agent 嵌套(子-子 Agent,`Task` Tool 递归调)
- **OQ-#023-D**: 子 Agent 并发调度(目前串行调 `child.runBlocking` 阻塞父 Agent turn)
- **OQ-#023-E**: 子 Agent turn 内 Skill `/xxx` 拦截
- **OQ-#023-F**: 用户自定义 `SubAgentType` enum(目前闭合 3 值)

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-09-24
**Tasks version**: v0.1 Draft
**Story #023 slug**: `delegate-sub-agent`
**对应 spec**: `specs/023-delegate-sub-agent/spec.md`
**对应 plan**: `specs/023-delegate-sub-agent/plan.md`