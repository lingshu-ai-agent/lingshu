# Tasks: Story 001 zero-config-bootstrap

> 每个任务 = 一个 commit;每完成一组相关任务提一个 PR。
> 总预估:~12 小时工作量(1 个工程师 1.5 个工作日)。

---

## Phase 0:Step -1 Maven Skeleton Init(冷启动)

- [x] **T00a** 创建仓根 + 5 子模块目录树(已 ls 验证 `MISSING_POM` + `MISSING_LINGSHU_CORE`)(5min)
- [ ] **T00b** 写父 `pom.xml`(SOP §8.3 + §10.1 13 项依赖 + R-13 `banned-dependencies` enforcer)(30min)
  - ⚠️ **关键**:不引 `spring-ai-spring-boot-starter` 全家桶,只引 `spring-ai-bom` + `spring-ai-core` + `spring-ai-anthropic` starter
- [ ] **T00c** 写 `lingshu-core/pom.xml`(SOP §8.4 + lombok/reactive-streams/jackson-databind/JUnit 5/AssertJ/Mockito 5/Awaitility)(15min)
- [ ] **T00d** 写 `lingshu-a2a-client/pom.xml` + `lingshu-a2a-server/pom.xml` + `lingshu-cli/pom.xml` 空骨架 + `lingshu-examples/pom.xml`(15min)
- [ ] **T00e** 写 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(空文件,Story #001 实施期填)(5min)
- [ ] **T00f** 跑 `mvn validate -N` + `mvn -pl lingshu-core -am compile`(两者过即可)(10min)
- [ ] **T00g** `git commit -m "feat: Story #000 — Maven skeleton + 5 模块骨架"`(SOP §8.7 模板)(5min)

> **完成标志**:`mvn validate -N` exit 0 + `mvn -pl lingshu-core -am compile` exit 0

---

## Phase 1:9 Slot 接口 + Core Runtime Types

- [ ] **T01** 写 `ai.lingshu.core.api` 包 9 个 Slot 接口(LlmProvider / Tool / ToolExecutor / Sandbox / Skill / SkillSource / SessionStore / Compactor / PromptBuilder / FlowEngine / A2aTransport / Agent / RunResult)(dsh §4 全章)(60min)
- [ ] **T02** 写 `ai.lingshu.core.event` 包(Message + AgentEvent + Decision + ToolCall + Usage + ToolSpec + Prompt 等,dsh §4.1—§4.4)(45min)
- [ ] **T03** 写 `ai.lingshu.core.config.AgentConfig`(27 字段 + 7 嵌套类 + `defaults()`,dsh §4.12.2)(60min)
- [ ] **T04** 写 `SmokeTest.java`(空测试,验证编译 + 测试框架接通)(10min)
- [ ] **T05** 跑 `mvn -pl lingshu-core test` 过(5min)
- [ ] **T06** `git commit -m "feat(core): 9 Slot 接口 + Core Runtime Types"`(5min)

> **关键约束**:所有 `@Value` 用 Lombok,不用 `record`;空集合用 `Collections.emptyList()`,不用 `List.of`

---

## Phase 2:SPI 体系(SlotRouter + 4 Router + 4 Provider)

- [ ] **T07** 写 `ai.lingshu.core.spi.SlotProvider` 通用接口 + 9 typed 子接口(dsh §5.1)(15min)
- [ ] **T08** 写 `ai.lingshu.core.spi.SlotRouter<P,T>` 父类(dsh §5.2 同名竞争 + 启动日志)(30min)
- [ ] **T09** 写 4 个 Router concrete 类(`PermissionPolicyRouter` / `ToolExecutorRouter` / `FlowEngineRouter` / `LlmProviderRouter`,dsh §5.3.1.0 模板)(20min)
- [ ] **T10** 写 `StrictPermissionPolicy` + `StrictPermissionPolicyProvider`(`@AutoConfiguration` + `@Bean(name="permissionPolicyProvider_strict")` + 匿名 inner class,dsh §5.5 L2169-2189)(30min)
- [ ] **T11** 写 `DefaultToolExecutor` + `DefaultToolExecutorProvider`(5 步流水线占位,dsh §5.5 L2192-2213)(45min)
- [ ] **T12** 写 `LinearTurnEngine` + `LinearTurnEngineProvider`(ReAct Loop 自实现,~ 数十行,dsh §6.1 + §4.10.1 硬规则 1 — **不调** Spring AI `ChatClient.prompt().call()`)(90min)
- [ ] **T13** 写 `AnthropicLlmProvider` + `AutoConfiguration`(Spring AI ChatModel 包装,`providerMap` 显式映射,dsh §4.10.1 硬规则 3)(60min)
- [ ] **T14** 跑 `mvn -pl lingshu-core -am compile` 过(5min)
- [ ] **T15** `git commit -m "feat(spi): SlotRouter + 4 Router + 4 Default Provider"`(5min)

> **关键约束**:ReAct Loop 自实现,核心 ~ 数十行;Spring AI 只用 `ChatModel.call()` 拿响应,Tool 调度自己跑

---

## Phase 3:AgentFactory + Agent 主链路

- [ ] **T16** 写 `DefaultTurnContext`(dsh §4.12.1,AtomicBoolean done + synchronized history)(20min)
- [ ] **T17** 写 `DefaultAgent` 4 final 字段构造(dsh §4.12.3 L1441-1477 + §7.1.2 不变量)(30min)
- [ ] **T18** 写 `AgentFactory`(`@Component` + `@Autowired 4 Router` + 7 项 fail-fast 校验 + `create(cfg)` + `defaultConfig()`,dsh §7.1)(60min)
- [ ] **T19** 写 `RunResult`(dsh §4.12.3 L1420)(10min)
- [ ] **T20** 跑 `mvn -pl lingshu-core -am compile` 过(5min)
- [ ] **T21** `git commit -m "feat(runtime): AgentFactory + DefaultAgent + 7 项校验"`(5min)

> **关键约束**:`AgentFactory` 是 Spring `@Component` 单例(无状态,持 4 Router);`Agent` 是 factory 产品(prototype-like,带 session/config/engine 状态,Spring 不持有引用,§7.1.1 5 维度对比)

---

## Phase 4:demo-empty 示例(AC-01-1 黑盒验证)

- [ ] **T22** 写 `lingshu-examples/demo-empty/pom.xml`(依赖 `lingshu-core` + `spring-boot-starter`)(15min)
- [ ] **T23** 写 `DemoEmptyApplication.java`(Spring Boot main + `AgentFactory factory = ...` + `agent.runBlocking("你好,介绍下自己")`)(30min)
- [ ] **T24** 写 `application.yml`(只有 `spring.application.name=lsh-empty` 一行,空 yml)(5min)
- [ ] **T25** `git commit -m "feat(examples): demo-empty — 空 yml 启动验证 AC-01"`(5min)

> **关键约束**:从 Claude Code 配置文件读 `ANTHROPIC_API_KEY`(用户 2026-09-20 会话允许)

---

## Phase 5:单元测试(L1 + L2)

- [ ] **T26** `AgentConfigDefaultsTest`(AC-01-2 全部 27 字段断言,JUnit 5 + AssertJ)(30min)
- [ ] **T27** `SlotRouterTest`(AC-01-4 同名竞争 3 场景 + 启动日志格式)(45min)
- [ ] **T28** `AgentFactoryTest`(AC-01-3 7 项 fail-fast,每项 1 个 test)(45min)
- [ ] **T29** `LinearTurnEngineTest`(AC-01-5 ReAct 5 路径,mock LlmProvider)(45min)
- [ ] **T30** `AnthropicLlmProviderTest`(mock Spring AI ChatModel,不调真实 API)(30min)
- [ ] **T31** 跑 `mvn -pl lingshu-core test` 全过(5min)
- [ ] **T32** `git commit -m "test(core): L1+L2 unit tests for AC-01-2—AC-01-5"`(5min)

> **覆盖率门槛**:新增 Slot 接口 100% / 其他 ≥ 80%(constitution §5)

---

## Phase 6:R-13 mitigation (d) 强制 + 集成验证

- [ ] **T-dep-tree-1** 跑 `mvn dependency:tree -pl lingshu-core -Dverbose`,筛 `spring-ai-*` / `com.knuddels:*` / `io.netty:*` / `com.fasterxml.jackson.*` 子树(10min)
- [ ] **T-dep-tree-2** 把关键子树贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节,标注 `(name, version, slot)` 三元组(5min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`,确认 `banned-dependencies` 规则不 fail(15min)
- [ ] **T-dep-tree-4** `mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,binary < 35MB 且相对 main HEAD delta < 10%(10min)
- [ ] **T-validate-1** 跑 AC-01-1:`time java -jar lingshu-examples/demo-empty/target/demo-empty-1.0.0.jar "你好"` ≤ 30s,stderr 零 ERROR(15min)
- [ ] **T-validate-2** 跑 AC-01-2:`factory.defaultConfig()` 27 字段全非 null 断言(SmokeTest 已含,这里跑 verify 重验)(5min)
- [ ] **T-validate-3** 跑 AC-01-3:7 项 fail-fast,故意构造 7 个错误配置 → 7 个对应 ErrorCode(15min)
- [ ] **T-validate-4** 跑 AC-01-4:启动日志格式 grep 验证(SlotRouter 日志)(5min)
- [ ] **T-validate-5** 跑 AC-01-5:LinearTurnEngine 单轮 ReAct mock test(已含在 T29)(5min)
- [ ] **T-validate-6** 跑 AC-01-6:`mvn validate -N` + `mvn -pl lingshu-core -am compile` 全过(5min)
- [ ] **T-validate-7** `git commit -m "test(integration): AC-01 黑盒全过 + R-13 dep-tree 自查"`(5min)

> **完成标志**:所有 AC-01-1—AC-01-6 全过 + R-13 mitigation (d) 4 步全跑

---

## Phase 7:文档同步

- [ ] **T-doc-sync-1** 扩 `README.md` 加 Quick Start(空 yml 启动命令)+ 工程结构图 + AC-01 验证命令(30min)
- [ ] **T-doc-sync-2** `dsh_agent_design.md` §13 changelog 加 `v0.1.0-001 (Story #001) — ...` 条目(15min)
- [ ] **T-doc-sync-3** `CLAUDE.md` §11 #6 引用 §10.1 锁定 13 项(已对齐,无需改)+ §1.3 版本号同步(若 plan 改了)(10min)
- [ ] **T-doc-sync-4** `lingshu-spec-driven-dev` SKILL.md 更新"Story #001 实施完成"注记(可选,留待 Story #002 实施工程师读)(10min)
- [ ] **T-doc-sync-5** `git commit -m "docs: Story #001 README + dsh §13 changelog"`(5min)

---

## Phase 8:PR + 合入

- [ ] **T-PR-1** `git push origin feat/001-zero-config-bootstrap`(2min)
- [ ] **T-PR-2** `gh pr create --title "feat(agent): Story #001 zero-config-bootstrap — AgentFactory + 9 Slot 骨架 + 27 字段默认"` `--body` 贴 spec.md + plan.md + tasks.md + AC-01 验证输出(10min)
- [ ] **T-PR-3** 等 CI matrix(ubuntu + JDK 8 / 17 / 21)全过 + 1 个 reviewer approve(异步,等通知)(0min)
- [ ] **T-PR-4** `gh pr merge --squash`(2min)
- [ ] **T-PR-5** `git checkout main && git pull`(1min)
- [ ] **T-PR-6** Story #001 完成 → 触发 Story #002 实施(下个窗口)

---

## 任务总览(时间预估)

| Phase | 任务数 | 预估 |
|---|---|---|
| Phase 0(Skeleton)| 7 | ~1.5h |
| Phase 1(9 Slot + Runtime Types)| 6 | ~3h |
| Phase 2(SPI + Provider)| 9 | ~5h |
| Phase 3(AgentFactory)| 6 | ~2h |
| Phase 4(demo-empty)| 4 | ~1h |
| Phase 5(单元测试)| 7 | ~3.5h |
| Phase 6(R-13 + 集成验证)| 11 | ~1.5h |
| Phase 7(文档同步)| 5 | ~1h |
| Phase 8(PR)| 6 | ~0.5h |
| **合计** | **61** | **~18.5h** |

> 注:Story #001 是冷启动 + 骨架 + 验证,工作量大于后续 Story 的 ~ 5h 平均;后续 Story 可复用本 Story 沉淀的 pom.xml + 9 Slot 接口 + AgentConfig。

---

## 🟡 R-13 mitigation (d) 强制任务清单(本 Story 涉及,必跑)

> 出处:SOP §3.4 + dsh §17 R-13 mitigation (d)

- [x] **T-dep-tree-1** 跑 `mvn dependency:tree -pl lingshu-core -Dverbose`
- [ ] **T-dep-tree-2** 关键子树贴 PR body `### R-13 dependency:tree 自查` 节
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`,enforcer 不 fail
- [ ] **T-dep-tree-4**(binary size)`ls -lh target/*.jar` < 35MB

---

## 输出检查清单(合入前自检)

- [ ] specs/001-zero-config-bootstrap/spec.md 完整(WHO/WHAT/WHY/AC/反向 AC)
- [ ] specs/001-zero-config-bootstrap/plan.md 含接口 / 文件 / 测试策略
- [ ] specs/001-zero-config-bootstrap/tasks.md 全 T-NN 勾完
- [ ] AC-01-1—AC-01-6 全过(贴验证输出)
- [ ] README.md / dsh §13 changelog / CLAUDE.md 三同步
- [ ] constitution.md §10 风险更新(本 Story 缓解 R-06 / R-13 一部分)
- [ ] PR 标题 + body 符合模板
- [ ] R-13 dependency:tree 自查节在 PR body

---

**Tasks Author**:Claude Code(基于 plan.md + SOP §3.4 + dsh §17 R-13)
**Tasks Date**:2026-09-20