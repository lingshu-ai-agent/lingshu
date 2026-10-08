# Tasks: Story #029 `permission-policy-impl`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 4 核心文件新增 + 3 modify + 1 ErrorCode + 7 测试文件 ≈ 11 文件,但严格按 P1—P6 拆分 commit,每个 commit 1-3 文件)
>
> **实施顺序严格按 plan §3**:`PermissionErrorCodes` 基础常量 → `StrictPermissionPolicy` 实现 → `StrictPermissionPolicyProvider` SPI → `PermissionPolicyAutoConfiguration` 注册 → `AgentConfig.ToolsConfig` 扩字段 → `AgentConfig.permissionPolicy` 顶层字段 → demo yml 切换 → 测试 fixture → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#029` 复用 Jackson + Lombok + Spring 已锁 + `List.contains` + `Collections.emptyList()` + `Arrays.asList` 全 JDK 8 built-in 0 新二进制,需验证 0 binary delta 第 15 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(permission): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(4 新增 + 3 modify)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/permission/PermissionErrorCodes.java` 新增常量类 —— `public final class PermissionErrorCodes` + `public static final String LINGS_P01 = "LINGS-P01";` + 行内注释 `// PERMISSION_DENIED — see dsh §15.4 P 段 1 号 + §4.7 PermissionPolicy,2026-09-30 #029 spec 锁定`(预估 5min)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicy.java` 新增实现 —— `public final class StrictPermissionPolicy implements PermissionPolicy` + 注解 `@Component` + 字段 `private final AgentConfig.ToolsConfig tools;` + 构造器 `public StrictPermissionPolicy(AgentConfig.ToolsConfig tools)` + `@Override public Decision check(ToolCall call, ToolExecutionContext ctx)` 3 决策路径:`toolName = call.name()` → (a) `tools.getAllowList()` 非空且不含 `toolName` → `return new Decision.Deny("[LINGS-P01] Tool '" + toolName + "' not in allow-list")`;(b) `tools.getDenyList()` 非空且含 `toolName` → `return new Decision.Deny("[LINGS-P01] Tool '" + toolName + "' in deny-list")`;(c) 默认 → `return new Decision.Allow("strict policy: allow")`;类级 Javadoc 引用 §4.7 PermissionPolicy + §4.10.1 硬规则 2(预估 30min,spec §4 AC-NN-1 + AC-NN-2 + AC-NN-4)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProvider.java` 新增 Provider —— `public class StrictPermissionPolicyProvider implements Providers.PermissionPolicyProvider` + 注解 `@Component` + `@Override public String name() { return "strict"; }` + `@Override public int priority() { return 10; }` + `@Override public String version() { return "1.0.0"; }` + `@Override public PermissionPolicy create(AgentConfig c) { return new StrictPermissionPolicy(c.getTools()); }`;Javadoc 对齐 §5.5 多 Provider 模式 + 唯一 name() 约束(预估 15min)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPolicyAutoConfiguration.java` 新增 AutoConfiguration —— `public class PermissionPolicyAutoConfiguration` + 注解 `@AutoConfiguration` + `@Bean(name="permissionPolicyProvider_strict-1.0.0") public PermissionPolicyProvider strictPermissionPolicyProvider()` 返 `new StrictPermissionPolicyProvider()`;对齐 §5.5 多 Provider 模式(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)(预估 10min)
- [ ] **T05** `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` modify —— (1) `ToolsConfig` 扩 2 字段 `List<String> allowList` + `List<String> denyList`(字段顺序:`enabled` → `allowList` → `denyList` → `maxReadBytes` → `maxWriteBytes`);(2) `defaults()` 工厂方法同步扩:`return new ToolsConfig(true, Collections.emptyList(), Collections.emptyList(), 200_000, 1_000_000)`;(3) `AgentConfig` 顶层扩 1 字段 `String permissionPolicy`(默认 `"default"`,字段位置:**最末**);(4) `AgentConfig.defaults()` 同步扩(若已存在);(预估 20min,spec §4 AC-NN-6 + AC-NN-7)
- [ ] **T06** `lingshu-examples/demo-product/src/main/resources/application.yml` modify —— 顶层加 `permission-policy: strict` + `agent.tools.allow-list: [read_file, write_file, list_dir, bash_safe]`(`ProductTools` 4 个 `@Component` Tools) + 注释引用 dsh §5.5 L2168-2189 + §4.7 PermissionPolicy + §4.10.1 硬规则 2(预估 5min,spec §4 AC-NN-8)
- [ ] **T07** `lingshu-examples/demo-empty/src/main/resources/application.yml` modify —— 顶层加 `permission-policy: strict`(无 tool 注册,演示任何 tool 调 deny)+ 注释引用 dsh §5.5 L2168-2189(预估 3min,spec §4 AC-NN-8)

> **P1 总耗时**:~88 min(~1.5h)

---

## P2:测试(7 测试文件 + 17 case)

- [ ] **T08** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyTest.java` 新增 L1 Unit —— 5 case(AC-NN-1 4 case + AC-NN-2 1 case):
   - case 1:allow-list 非空 `[read_file, list_dir]` + 调 `write_file` → `Decision.Deny` + `getReason()` 含 `[LINGS-P01]` + `"Tool 'write_file' not in allow-list"`
   - case 2:同上 + 调 `read_file` → `Decision.Allow`
   - case 3:deny-list 非空 `[bash_safe]` + 调 `bash_safe` → `Decision.Deny` + `getReason()` 含 `[LINGS-P01]` + `"Tool 'bash_safe' in deny-list"`
   - case 4:两表都配 `allow-list: [a, b]` + `deny-list: [c]` + 调 `c` → `Decision.Deny` + `getReason()` 含 `"in deny-list"`
   - case 5:两表都空 `ToolsConfig.defaults()` → 任何 tool 调 → `Decision.Allow`(预估 60min,涵盖 spec §4 AC-NN-1 + AC-NN-2 + AC-NN-4)
- [ ] **T09** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProviderTest.java` 新增 L1 Unit —— 2 case(AC-NN-3):
   - case 1:`provider.name() == "strict"` + `provider.priority() == 10` + `provider.version() == "1.0.0"`
   - case 2:`AnnotationConfigApplicationContext` 装配 `PermissionPolicyAutoConfiguration` + `AllowAllPermissionPolicyProvider`,验证 `permissionPolicyProvider_strict-1.0.0` Bean 注册存在 + Bean class = `StrictPermissionPolicyProvider`(预估 30min)
- [ ] **T10** `lingshu-core/src/test/java/ai/lingshu/core/permission/PermissionErrorCodesTest.java` 新增 L1 Unit —— 1 case(AC-NN-4):`assertThat(PermissionErrorCodes.LINGS_P01).isEqualTo("LINGS-P01")`(预估 5min)
- [ ] **T11** `lingshu-core/src/test/java/ai/lingshu/core/runtime/ToolsConfigAllowDenyListTest.java` 新增 L1 Unit —— 2 case(AC-NN-6):
   - case 1:`ToolsConfig.defaults().getAllowList()` 空 + `getDenyList()` 空 —— back-compat 默认 allow
   - case 2:`new ToolsConfig(true, Arrays.asList("a", "b"), Arrays.asList("c"), 100, 200)` 构造 → `getAllowList() == [a, b]` + `getDenyList() == [c]` + `getMaxReadBytes() == 100` + `getMaxWriteBytes() == 200`(预估 15min)
- [ ] **T12** `lingshu-core/src/test/java/ai/lingshu/core/spi/PermissionPolicyRouterStrictIT.java` 新增 L2 Slice —— 4 case(AC-NN-5):
   - case 1:`AnnotationConfigApplicationContext` 装配 `PermissionPolicyAutoConfiguration` + `AllowAllPermissionPolicyProvider` + `PermissionPolicyRouter`(构造器传 `List<PermissionPolicyProvider>`);`router.resolve("strict", cfg)` → `instanceof StrictPermissionPolicy`
   - case 2:`router.resolve("default", cfg)` → `instanceof AllowAllPermissionPolicy`(back-compat 不变)
   - case 3:cfg `permissionPolicy = "strict"` → `router.resolve(cfg.getPermissionPolicy(), cfg)` → `StrictPermissionPolicy`(自动按 cfg 路由)
   - case 4:cfg `permissionPolicy = "unknown"` → `router.resolve("unknown", cfg)` → fallback 到 `AllowAllPermissionPolicy`(§5.2 fallback 行为不变)(预估 60min)
- [ ] **T13** `lingshu-examples/demo-product/src/test/java/.../DemoProductPermissionStrictIT.java` 新增 L3 黑盒 —— 2 case(AC-NN-8 + AC-NN-9):
   - case 1:启动 `demo-product` Spring Boot + yml `permission-policy: strict` + `tools.allow-list: [read_file, write_file, list_dir, bash_safe]`;`PermissionPolicyRouter.resolve("strict", cfg)` 返 `StrictPermissionPolicy`;模拟调 `read_file` → `Decision.Allow`;模拟调未声明 tool → `Decision.Deny` 含 `[LINGS-P01]`
   - case 2:同 demo 但 yml 切 `permission-policy: default` → `PermissionPolicyRouter.resolve("default", cfg)` 返 `AllowAllPermissionPolicy` → 任何 tool 调 → `Decision.Allow`(back-compat 不变)(预估 90min)
- [ ] **T14** `lingshu-examples/demo-empty/src/test/java/.../DemoEmptyPermissionStrictIT.java` 新增 L3 黑盒 —— 1 case(AC-NN-8):启动 `demo-empty` + yml `permission-policy: strict` + 无 tool 注册;模拟调 `any_tool` → `Decision.Deny` 含 `[LINGS-P01] Tool 'any_tool' not in allow-list`(预估 30min)

> **P2 总耗时**:~290 min(~4.83h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyTest` 验证 allow-list + deny-list 真查(4 case + 1 default allow)(预估 5min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyTest#twoEmptyTablesPass` 验证两表都空 default allow(预估 5min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyProviderTest` 验证 name="strict" + priority=10 + Spring Bean name(预估 5min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=PermissionErrorCodesTest` 验证 LINGS_P01 常量值(预估 3min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=PermissionPolicyRouterStrictIT` 验证 `PermissionPolicyRouter.resolve("strict", cfg)` 真命中 strict + back-compat default fallback(预估 10min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-core test -Dtest=ToolsConfigAllowDenyListTest` 验证 `ToolsConfig.defaults()` 兼容 + 新字段 accessors(预估 5min)
- [ ] **T-validate-AC-NN-7** 跑 `mvn -pl lingshu-core test -Dtest=AgentConfigTest#permissionPolicyFieldDefault` 验证 `AgentConfig.permissionPolicy` 顶层字段 + yml 解析(预估 5min)
- [ ] **T-validate-AC-NN-8** 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductPermissionStrictIT` 验证 demo-product 切 strict + allow-list 真过 / 未声明 tool 真拒(预估 15min)
- [ ] **T-validate-AC-NN-9** 跑 `mvn -pl lingshu-examples/demo-empty test -Dtest=DemoEmptyPermissionStrictIT` 验证 demo-empty 切 strict 无 tool 注册 → 任何 tool 调 deny(预估 10min)
- [ ] **T-validate-AC-NN-10** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose` + diff 对比 `#028` post-commit baseline 镜像,验证 0 新 Maven 坐标(R-13 第 15 次)(预估 15min)
- [ ] **T-validate-AC-NN-11** 跑 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 全模块无 fail,新增 17 case 全过,587 pre test 0 回归(预估 30min)

> **P3 总耗时**:~108 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-029-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-029-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-028-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-029-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-product package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min
> **PR body 末尾必有 `### R-13 dependency:tree 自查` 节**,贴 T-dep-tree-2 输出 + (name, version, slot) 三元组表

---

## P5:文档同步(提交完成闭环)

- [ ] **T-doc-1** `README.md` 顶部加 `#029` 1 段(PermissionPolicy 真实现,`StrictPermissionPolicy` + `StrictPermissionPolicyProvider` 落地 + `LINGS-P01 PERMISSION_DENIED` 启用 + §4.7 PermissionPolicy 模板真接通 + §15.4 域字母表 9 → 10 新增 P 域)(预估 15min)
- [ ] **T-doc-2** `specs/029-permission-policy-impl/quickstart.md` 起草 Alice 30min 教程(对齐 `#027a` / `#027b` / `#028` 模板:Hello world strict policy 配置 + allow-list / deny-list 真过 / 真拒验证)(预估 30min)
- [ ] **T-doc-3** `specs/029-permission-policy-impl/data-model.md` 起草(4 核心类型对照表 + `LINGS-P01` ErrorCode 域表 + allow-list × deny-list × 3 决策路径映射表)(预估 30min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.46 → v1.5.47` 行(本 Story 实施记录)(预估 5min)
- [ ] **T-doc-5** `dsh_agent_design.md` §15.4 ErrorCode 域字母表加 **`P = Permission 域 LINGS-P01`** 行(新域段启用,域字母 **9 → 10**)+ §15.11 P 段序号表 1 号 `LINGS-P01 PERMISSION_DENIED`;§5.5 Slot 4 `StrictPermissionPolicyProvider` 模板 L2168-2189 design intent 兑现注释补「🆕 v1.5.47 Story #029 落地」(预估 5min)
- [ ] **T-doc-6** `constitution.md` §4 域字母表加 `P = Permission` 行 + `LINGS-P01 PERMISSION_DENIED` + §10 R-13 风险登记:`Story #029` 标记「已缓解」+ 第 15 次 0 binary delta 验证结果(预估 10min)
- [ ] **T-doc-7** `ROADMAP.md` 段一 ✅ 已完成表加 `#029` 行(预估 2min)
- [ ] **T-doc-8** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.46` → `v1.5.47`)(预估 2min)
- [ ] **T-doc-9**(可选)`lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#029` 段落(Story 推 master 后开)(预估 60min)

> **P5 总耗时**:~160 min

---

## P6:PR + 合入(闭环)

- [ ] **T-PR-1** `git add -A && git commit -m "feat(permission): Story #029 permission-policy-impl — StrictPermissionPolicy + StrictPermissionPolicyProvider + LINGS-P01 PERMISSION_DENIED"`(Co-Authored-By Claude 标记)(预估 5min)
- [ ] **T-PR-2** `gh pr create --base main --head story-029-permission-policy-impl --title "feat(permission): Story #029 permission-policy-impl — StrictPermissionPolicy + StrictPermissionPolicyProvider + LINGS-P01 PERMISSION_DENIED" --body "$(cat /tmp/pr-body-029.md)"`(PR body 模板贴 spec.md + plan.md + tasks.md 摘要 + AC 验证输出 + R-13 dep-tree 自查)(预估 10min)
- [ ] **T-PR-3** 等 CI 绿 + review approve,`gh pr merge --squash --auto`(预估 5min)
- [ ] **T-PR-4** 合入后 `git pull` + 触发 docs 同步任务 T-doc-1—T-doc-9(预估 160min)

---

## 总耗时估算

| 阶段 | 时间 | 说明 |
|---|---|---|
| P1(实现)| ~88min | 4 新增 + 3 modify |
| P2(测试)| ~290min | 7 测试文件 + 17 case |
| P3(AC 验证)| ~108min | 11 验证跑 |
| P4(dep-tree)| ~50min | R-13 强制 |
| P5(文档)| ~160min | 9 同步项 |
| P6(PR)| ~180min | commit + PR + merge + docs |
| **合计** | **~14.6 h** | 与 `#028`(25h)/ `#027a`(29h)/ `#027b`(29h)/ `#022`(32h) 同量级(本 Story 略小,因 5 核心文件 + 1 ErrorCode 边界内) |

---

## 强制不变项检查清单(PR review 时必勾)

- [ ] `PermissionPolicy` interface **0 改动**(`git diff lingshu-core/src/main/java/ai/lingshu/core/slot/PermissionPolicy.java` 应为空)
- [ ] `Decision` 4 子类 **0 改动**(`Allow` / `Deny` / `AskUser` / `Option` 全部 `@Value` 不可变契约不变)
- [ ] `PermissionPolicyRouter` **0 改动**(只是多 Provider 模式 strict 命中,SlotRouter.resolve(name, cfg) 行为不变)
- [ ] `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider` **0 改动**(默认 fallback 保留,back-compat 守住 AC-01-2 零配置 Story #001 兼容)
- [ ] `Tool` interface **0 改动**(`git diff lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java` 应为空)
- [ ] `ToolRegistry` interface **0 改动**(`#029` 不引新方法)
- [ ] `ToolExecutor.dispatch()` 5 步流水线 **0 改动**(只是 §4.7 第 1 步 `PermissionPolicy.check()` 从 stub 直返 Allow 变成 strict 真查表 —— `ToolExecutor` 不感知内部策略)
- [ ] `ToolExecutionContext` interface 6 方法契约 **0 改动**(只 default 实现 `DefaultToolExecutionContext` 不变)
- [ ] `Message` 4 子类(`System` / `User` / `Assistant` / `ToolResult`,§0.5.46 refactor 后)**0 改动**
- [ ] `Prompt.tools` 契约 **0 改动**
- [ ] `LlmResponse` 5 字段契约 **0 改动**
- [ ] `AgentEvent` 12 子类契约 **0 改动**
- [ ] `LinearTurnEngine` ReAct 主循环结构 **0 改动**
- [ ] `AnthropicLlmProvider` 6-arg ctor + `buildRequestBody` + `parseResponse` fallback **0 改动**(接续 #027a / #027b 已落契约)
- [ ] `AgentConfig.Sandbox` 5 字段 **0 改动**(Sandbox 字段是 runtime sandbox 关心,Permission 字段是 Tool-level 决策,两表正交)
- [ ] 9 Slot 顶层体系 **0 改动**(Slot 4 PermissionPolicy 是 SlotResolver 6 Router 之一)
- [ ] `AccessDeniedException extends RuntimeException` **0 改动**(`#028` 已落,`S` 域段 1 号 `LINGS-S01`)
- [ ] dsh §15.4 域字母表新增 **`P = Permission`** 域段(域字母表 **9 → 10**:`C/S/L/T/X/R/A/M/Z` 9 + `P` = 10)
- [ ] `mvn -pl lingshu-core dependency:tree` 0 新 Maven 坐标(banned list 强制)
- [ ] `mvn -pl lingshu-core verify` enforcer **不 fail**(跑 `banned-dependencies`)
- [ ] 17 test case 全过,`grep "BUILD FAIL" /tmp/mvn-test.log || echo PASS`
- [ ] PR body 末尾有 `### R-13 dependency:tree 自查` 节(T-dep-tree-2 输出贴上)
- [ ] dsh §13 changelog + constitution §4 + §10 R-13 缓解 + ROADMAP 段一已合表 四件套同步
- [ ] **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),Permission 真实现通过 `ToolExecutor.dispatch()` §4.7 第 1 步触发,**不走 ChatClient 自动执行**

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-09-30
**Tasks version**: v0.1 Draft
**Story #029 slug**: `permission-policy-impl`
**对应 spec**: `specs/029-permission-policy-impl/spec.md`
**对应 plan**: `specs/029-permission-policy-impl/plan.md**