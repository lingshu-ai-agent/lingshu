# Tasks: Story #022 `spring-ai-annotation-tool`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 6 核心文件 + 5 测试文件 + 1 SPI 装载 ≤ 12 文件 `< 15` 上限)
>
> **实施顺序严格按 plan §3**:`@AgentTool` 注解 → `JsonArgsConverter` → `SpringAiToolAdapter` → `AgentToolScanner` → `AgentToolAutoConfiguration` → SPI 装载 → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#022` 复用 spring-ai-bom,需验证 0 binary delta 第 7 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(agent): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(5 文件 + 1 常量)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/tool/annotation/AgentTool.java` 新增注解类 —— `@Target(METHOD) @Retention(RUNTIME) public @interface AgentTool { String name(); String description(); String[] capabilities() default {}; }`(预估 15min,dsh §6.5 (3) L4883-4889 字面落地)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/tool/ToolErrorCodes.java` 新增常量类 —— `public static final String LINGS_T08 = "LINGS-T08";`(预估 5min)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/tool/converter/JsonArgsConverter.java` 静态工具类 —— `public static Object[] convert(JsonNode input, Parameter[] params)`,`ObjectMapper.convertValue(input, Object.class)` 解析 + 按 parameter type 分发到 `String.valueOf` / `Integer.parseInt` / `Boolean.parseBoolean` 等 helper,未知 type → `IllegalArgumentException`(预估 60min,dsh §6.5 (3) L4918 + spec §4 AC-NN-5)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/tool/adapter/SpringAiToolAdapter.java` 实现 —— `public class SpringAiToolAdapter implements Tool` 4 field:bean + method + annotation + inputSchema;`name()` / `description()` / `inputSchema()` 委托 annotation;`execute(call, ctx)` 走 `JsonArgsConverter.convert` + `method.invoke(bean, args)` + `InvocationTargetException` catch 转 LINGS-T08 + catch-all `Exception` 转 LINGS-T08(预估 90min,dsh §6.5 (3) L4896-4956 字面 + spec §4 AC-NN-3)
- [ ] **T05** `lingshu-core/src/main/java/ai/lingshu/core/tool/scanner/AgentToolScanner.java` 实现 —— `@Component public class AgentToolScanner implements ApplicationContextAware { ToolRegistry registry; setApplicationContext(ctx) { for (Object bean : ctx.getBeansWithAnnotation(Component.class).values()) { for (Method m : bean.getClass().getMethods()) { AgentTool at = m.getAnnotation(AgentTool.class); if (at != null) registry.register(new SpringAiToolAdapter(bean, m, at)); } } } }`(预估 60min,dsh §6.5 (3) L4961-4979 + spec §4 AC-NN-1)
- [ ] **T06** `lingshu-core/src/main/java/ai/lingshu/core/tool/AgentToolAutoConfiguration.java` 实现 —— `@AutoConfiguration @Bean public AgentToolScanner agentToolScanner(ToolRegistry registry) { return new AgentToolScanner(registry); }`(预估 30min)
- [ ] **T07** SPI 装载 `lingshu-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 新增一行 —— `ai.lingshu.core.tool.AgentToolAutoConfiguration`(预估 5min)

> **P1 总耗时**:~265 min(~4.5h)

---

## P2:测试(5 文件 + 22 case)

- [ ] **T08** `lingshu-core/src/test/java/ai/lingshu/core/tool/converter/JsonArgsConverterTest.java` —— 7 case(String / int / long / boolean / double / Integer nullable + 1 unknown type fail);覆盖 spec §4 AC-NN-5(预估 90min)
- [ ] **T09** `lingshu-core/src/test/java/ai/lingshu/core/tool/adapter/SpringAiToolAdapterTest.java` —— 5 case(name/desc/schema 委托 + `execute` happy + `InvocationTargetException` → LINGS-T08 + catch-all → LINGS-T08 + 不抛异常);覆盖 spec §4 AC-NN-2 / AC-NN-3 / AC-NN-6(预估 90min)
- [ ] **T10** `lingshu-core/src/test/java/ai/lingshu/core/tool/scanner/AgentToolScannerTest.java` —— 5 case(单 bean 单方法 / 单 bean 多方法 / null ctx skip / dup name first-wins + log warn / no `@Component` 不扫);用 `AnnotationConfigApplicationContext` 跑;覆盖 spec §4 AC-NN-1(预估 90min)
- [ ] **T11** `lingshu-core/src/test/java/ai/lingshu/core/tool/AgentToolAutoConfigurationTest.java` —— 2 case(`AgentToolScanner` Bean 存在 / 不存在时 fallback `agentToolScanner()` 返回正确实例);覆盖 spec §4 AC-NN-1 wiring(预估 45min)
- [ ] **T12** `lingshu-core/src/test/java/ai/lingshu/core/tool/integration/AgentToolIntegrationTest.java` L2 slice —— 3 case(`ToolRegistry.lookup("@AgentTool name") != null` 在 Spring 容器刷新后 + `modelVisibleSpecs()` 包含 + 完整链路 ToolExecutor.dispatch → method.invoke → ToolResult.success);覆盖 spec §4 AC-NN-1 + AC-NN-2 端到端(预估 120min)

> **P2 总耗时**:~435 min(~7.5h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=AgentToolScannerTest,AgentToolIntegrationTest` 验证注解扫描 + 注册完整路径(预估 15min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=AgentToolIntegrationTest,SpringAiToolAdapterTest` 验证反射调用 + ToolResult.success 包装(预估 15min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=SpringAiToolAdapterTest` 验证反射失败 → LINGS-T08 ToolResult.error(**显式断言 errorCode 字段 == "LINGS-T08"**)(预估 15min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=JsonArgsConverterTest` 验证 primitive 类型转换表(预估 15min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=SpringAiToolAdapterTest` 验证 `capabilities()` 字段保留但不消费(预估 5min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-core test` 全模块无 fail,新增 22 case 全过(预估 30min)

> **P3 总耗时**:~95 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-022-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-022-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-021b-pre.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-022-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min
> **PR body 末尾必有 `### R-13 dependency:tree 自查` 节**,贴 T-dep-tree-2 输出 + (name, version, slot) 三元组表

---

## P5:文档同步(提交完成闭环)

- [ ] **T-doc-1** `README.md` 顶部加 `@AgentTool` 示例代码 1 段(对齐 `#019` built-in-tools 同款行文)(预估 15min)
- [ ] **T-doc-2** `specs/022-spring-ai-annotation-tool/quickstart.md` 起草 Alice 30min 教程(预估 30min)
- [ ] **T-doc-3** `specs/022-spring-ai-annotation-tool/data-model.md` 起草(`@AgentTool` annotation source + `SpringAiToolAdapter` 字段表,模板对齐 `#009d`)(预估 20min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.40 → v1.5.41` 行(本 Story 实施记录)(预估 5min)
- [ ] **T-doc-5** `constitution.md` §10 R-13 风险登记:`Story #022` 标记「已缓解」+ 第 7 次 0 binary delta 验证结果(预估 5min)
- [ ] **T-doc-6** `ROADMAP.md` 段一 ✅ 已完成表加 `#022` 行(预估 2min)
- [ ] **T-doc-7** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.40` → `v1.5.41`)(预估 2min)

> **P5 总耗时**:~80 min

---

## P6:PR + 合入(闭环)

- [ ] **T-PR-1** `git add -A && git commit -m "feat(agent): Story #022 spring-ai-annotation-tool — @AgentTool + SpringAiToolAdapter + AgentToolScanner + JsonArgsConverter + LINGS-T08"`(Co-Authored-By Claude 标记)(预估 5min)
- [ ] **T-PR-2** `gh pr create --base main --head story-022-spring-ai-annotation-tool --title "feat(agent): Story #022 spring-ai-annotation-tool — @AgentTool + SpringAiToolAdapter + AgentToolScanner + JsonArgsConverter + LINGS-T08" --body "$(cat /tmp/pr-body-022.md)"`(PR body 模板贴 spec.md + plan.md + tasks.md 摘要 + AC 验证输出 + R-13 dep-tree 自查)(预估 10min)
- [ ] **T-PR-3** 等 CI 绿 + review approve,`gh pr merge --squash --auto`(预估 5min)
- [ ] **T-PR-4** 合入后 `git pull` + 触发 docs 同步任务 T-doc-1—T-doc-7(预估 80min)

---

## 总耗时估算

| 阶段 | 时间 | 说明 |
|---|---|---|
| P1(实现)| ~265min | 5 文件 + 1 常量 |
| P2(测试)| ~435min | 5 文件 + 22 case |
| P3(AC 验证)| ~95min | 6 验证跑 |
| P4(dep-tree)| ~50min | R-13 强制 |
| P5(文档)| ~80min | 7 同步项 |
| P6(PR)| ~100min | commit + PR + merge |
| **合计** | **~17.3 h** | 与 `#009d` / `#021b` 同量级 |

---

## 强制不变项检查清单(PR review 时必勾)

- [ ] `Tool` interface **0 改动**(`git diff lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java` 应为空)
- [ ] `ToolRegistry` interface **0 改动**(`#022` 不引新方法)
- [ ] `ToolExecutor.dispatch()` 5 步流水线 **0 改动**
- [ ] `DefaultToolRegistry.register()` first-wins + log warn 行为 **不变**
- [ ] dsh §15.4 域字母 T01—T07 **编号全部不动**,**只新增 T08**
- [ ] `mvn -pl lingshu-core dependency:tree` 0 新 Maven 坐标(banned list 强制)
- [ ] `mvn -pl lingshu-core verify` enforcer **不 fail**(跑 `banned-dependencies`)
- [ ] 22 test case 全过,`grep "BUILD FAIL" /tmp/mvn-test.log || echo PASS`
- [ ] PR body 末尾有 `### R-13 dependency:tree 自查` 节(T-dep-tree-2 输出贴上)
- [ ] dsh §13 changelog + constitution §10 R-13 缓解 + ROADMAP 段一已合表 三件套同步

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-09-24
**Tasks version**: v0.1 Draft
**Story #022 slug**: `spring-ai-annotation-tool`
**对应 spec**: `specs/022-spring-ai-annotation-tool/spec.md`
**对应 plan**: `specs/022-spring-ai-annotation-tool/plan.md`
