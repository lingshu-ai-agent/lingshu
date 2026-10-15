# Tasks — Story #047 ask-policy end-to-end wiring

**Story #**: #047
**Slug**: `ask-policy-end-to-end-wiring`
**Spec**: `specs/047-ask-policy-end-to-end-wiring/spec.md`
**Plan**: `specs/047-ask-policy-end-to-end-wiring/plan.md`
**dsh §**: §6.1 LinearTurnEngine + §4.7 PermissionPolicy ask-mode + ChatController yml bypass

---

## Phase 0 — pre-flight R-13 baseline(~2 min)

### Task 0.1 — 记录 R-13 mitigation (d) baseline

- [ ] **T-R13-0.1.1**: 跑 `mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -DoutputType=text > /tmp/r13-pre.txt`
- [ ] **T-R13-0.1.2**: `md5sum /tmp/r13-pre.txt` 记录 baseline md5
- [ ] **T-R13-0.1.3**: grep 确认 13 项依赖表无超出(已有 spring-boot / spring-ai / lombok / jackson / reactive-streams / opentelemetry 等)

### Task 0.2 — 跑 baseline test suite 确认 PASS 状态

- [ ] **T-R13-0.2.1**: `mvn -pl lingshu-core test -Dtest=AgentFactoryYamlPermissionPolicyIT` 确认 3 case 0 fail
- [ ] **T-R13-0.2.2**: `mvn -pl lingshu-core test -Dtest=AskUserPermissionPolicyTest` 确认 5 case 0 fail
- [ ] **T-R13-0.2.3**: `mvn -pl lingshu-core test -Dtest=DefaultApprovalGateTest` 确认 5 case 0 fail
- [ ] **T-R13-0.2.4**: `mvn -pl lingshu-core test -Dtest=AgentFactoryYamlSessionStoreSaveIT` 确认 3 case 0 fail(守护 Story #046)

---

## Phase 1 — Bug A fix `AgentFactory.java:280`(~1 min)

### Task 1.1 — 1-line typo fix

- [ ] **T-A-1.1.1**: 读 `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java:278-282` 确认当前错误:`policyRouter.resolve(config.getSandbox().getPolicy(), config);`
- [ ] **T-A-1.1.2**: Edit L280 改 `config.getSandbox().getPolicy()` → `config.getPermissionPolicy()`
- [ ] **T-A-1.1.3**: 验证同文件 L278/279/281/282/295 已经是正确写法(L278 `config.getLlm().getProvider()` / L279 `config.getToolExecutor()` / L281 `config.getPrompt().getBuilder()` / L282 `config.getFlowEngine()` / L295 `config.getSessionStore()` —— 全部顶层 Slot 名,只有 L280 错)

### Task 1.2 — 编译验证

- [ ] **T-A-1.2.1**: `mvn -pl lingshu-core compile` 确认 0 编译错误
- [ ] **T-A-1.2.2**: `git diff lingshu-core/.../AgentFactory.java` 确认只改了 L280 一处

---

## Phase 2 — Bug B step 1 `DemoProductApplication.readTools()`(~2 min)

### Task 2.1 — 1-line hardcode fix

- [ ] **T-B1-2.1.1**: 读 `lingshu-examples/demo-product/.../DemoProductApplication.java:370-384` 确认当前硬编码
- [ ] **T-B1-2.1.2**: Edit L382 改 `java.util.Collections.<String>emptyList()` → `readToolsList(env, "agent.tools.ask-list", fallback.getAskList())`
- [ ] **T-B1-2.1.3**: 确认 `readToolsList` helper 在 L393 已经是 private static(已存在)

### Task 2.2 — 编译验证

- [ ] **T-B1-2.2.1**: `mvn -pl lingshu-examples/demo-product compile` 确认 0 编译错误
- [ ] **T-B1-2.2.2**: `git diff DemoProductApplication.java` 确认只改了 L382 一处

---

## Phase 3 — Bug B step 2 `ChatController.createSession()` 改用 @Bean(~10 min)

### Task 3.1 — ChatController 改造

- [ ] **T-B2-3.1.1**: 读 `lingshu-examples/demo-product/.../ChatController.java:106-116` 看现有 ctor
- [ ] **T-B2-3.1.2**: ctor 加 `AgentConfig agentConfigTemplate` 参数(从 `agentConfig(Environment)` @Bean 注入)
- [ ] **T-B2-3.1.3**: `createSession()` L120-162 整段重写,移除 hand-roll 28-arg `AgentConfig` 构造器
- [ ] **T-B2-3.1.4**: SessionRegistry factory lambda 简化:`return agentFactory.create(agentConfigTemplate);`
- [ ] **T-B2-3.1.5**: 移除 `env` 字段(若不再需要;否则保留作为 fallback)
- [ ] **T-B2-3.1.6**: 移除 `import AgentConfigDefaults` 不再使用

### Task 3.2 — 编译验证

- [ ] **T-B2-3.2.1**: `mvn -pl lingshu-examples/demo-product compile` 确认 0 编译错误
- [ ] **T-B2-3.2.2**: `mvn -pl lingshu-examples/demo-product test` 跑既有 `ChatControllerTest`(若存在)

---

## Phase 4 — 写测试(覆盖 AC-1 到 AC-10,~30 min)

### Task 4.1 — `AgentFactoryAskPolicyRoutingIT`(3 case L1/L2)

- [ ] **T-T-4.1.1**: 新建 `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryAskPolicyRoutingIT.java`
- [ ] **T-T-4.1.2**: Case 1:yml `permission-policy: ask` → `policyRouter.resolve("ask", cfg)` 被调用,实例 instanceof `AskUserPermissionPolicy`(覆盖 AC-1 + AC-2)
- [ ] **T-T-4.1.3**: Case 2:yml `permission-policy: default` → `policyRouter.resolve("default", cfg)` 走 `AllowAllPermissionPolicy`(覆盖 AC-3)
- [ ] **T-T-4.1.4**: Case 3:yml `permission-policy: strict` → 走 `StrictPermissionPolicy`(覆盖 AC-10 反向 AC)

### Task 4.2 — `AgentFactoryAskPolicyYamlIT`(2 case L2)

- [ ] **T-T-4.2.1**: 新建 `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryAskPolicyYamlIT.java`
- [ ] **T-T-4.2.2**: Case 1:`@SpringBootTest` + Environment 注入,yml `tools.ask-list: [bash_safe, write_file]` → 验证 `cfg.getTools().getAskList().size() == 2`(覆盖 AC-4)
- [ ] **T-T-4.2.3**: Case 2:yml 不配 `tools.ask-list` → `cfg.getTools().getAskList().isEmpty()`(覆盖 AC-8 back-compat)

### Task 4.3 — `DemoProductAskPolicyEndToEndIT`(1 case L3 e2e)

- [ ] **T-T-4.3.1**: 新建 `lingshu-examples/demo-product/src/test/java/ai/lingshu/examples/demoproduct/DemoProductAskPolicyEndToEndIT.java`
- [ ] **T-T-4.3.2**: `@SpringBootTest` + mock `LlmProvider` + mock `bash_safe` Tool
- [ ] **T-T-4.3.3**: 跑 `agentFactory.create(agentConfigTemplate)` + `agent.run(prompt)` → 验证 `subscriber` 收到 `AgentEvent.ApprovalRequired`(覆盖 AC-6)
- [ ] **T-T-4.3.4**: 验证 `approvalId` 字段非 null + `ask` 字段非 null(覆盖 AC-2 间接)

### Task 4.4 — 反向 AC 守护回归(必跑,0 改动全过)

- [ ] **T-RAC-4.4.1**: `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyTest` 0 改动全过 8 case
- [ ] **T-RAC-4.4.2**: `mvn -pl lingshu-core test -Dtest=PermissionPolicyRouterStrictIT` 0 改动全过 4 case
- [ ] **T-RAC-4.4.3**: `mvn -pl lingshu-core test -Dtest=AskUserPermissionPolicyTest` 0 改动全过 5 case
- [ ] **T-RAC-4.4.4**: `mvn -pl lingshu-core test -Dtest=LinearTurnEngineAskUserTest` 0 改动全过 3 case
- [ ] **T-RAC-4.4.5**: `mvn -pl lingshu-core test -Dtest=DefaultApprovalGateTest` 0 改动全过 5 case
- [ ] **T-RAC-4.4.6**: `mvn -pl lingshu-core test -Dtest=AgentFactoryYamlPermissionPolicyIT` 0 改动全过 3 case
- [ ] **T-RAC-4.4.7**: `mvn -pl lingshu-core test -Dtest=AgentFactoryYamlSessionStoreSaveIT` 0 改动全过 3 case(Story #046 守护)

---

## Phase 5 — 跑全 suite + R-13 dep-tree post-flight(~5 min)

### Task 5.1 — 跑 lingshu-core 全 suite

- [ ] **T-R13-5.1.1**: `mvn -pl lingshu-core test` 期望 0 fail(2 MCP heartbeat flake pre-existing 可接受)
- [ ] **T-R13-5.1.2**: 记录新 test 数:759 (747 pre-#047 + 12 新)

### Task 5.2 — 跑 demo-product 全 suite

- [ ] **T-R13-5.2.1**: `mvn -pl lingshu-examples/demo-product test` 期望 0 fail
- [ ] **T-R13-5.2.2**: 验证 `DemoProductAskPolicyEndToEndIT` 1 case PASS

### Task 5.3 — R-13 dep-tree post-flight

- [ ] **T-R13-5.3.1**: `mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -DoutputType=text > /tmp/r13-post.txt`
- [ ] **T-R13-5.3.2**: `diff /tmp/r13-pre.txt /tmp/r13-post.txt` 期望只时间戳差异
- [ ] **T-R13-5.3.3**: 记录 R-13 mitigation (d) **第 31 次 PASS** 0 binary delta

### Task 5.4 — 静态检查

- [ ] **T-SC-5.4.1**: `grep -r "LINGS-P" lingshu-core/src/main/java/` 确认 0 新 ErrorCode(只 P01 + P02 已存在)
- [ ] **T-SC-5.4.2**: `mvn dependency:tree -DincludeScope=runtime | grep -E '(new|REPLACE)'` 确认 0 新传递依赖
- [ ] **T-SC-5.4.3**: `git diff --stat` 确认 6 文件改动(3 prod + 3 test),无意外文件

---

## Phase 6 — 文档同步(~10 min)

### Task 6.1 — 仓内制品

- [ ] **T-DOC-6.1.1**: `README.md` 「更新日期」段加 🆕 v1.5.60 Story #047 顶部 blockquote
- [ ] **T-DOC-6.1.2**: `README.md` 「核心特性」段补 🆕 ask-policy 端到端 wiring 闭环 bullet
- [ ] **T-DOC-6.1.3**: `README.md` 「Story 路线图」段追加 #047 retrospective
- [ ] **T-DOC-6.1.4**: `specs/ROADMAP.md` 段一 ✅ 已完成加 #047 行(2026-10-14)
- [ ] **T-DOC-6.1.5**: `specs/ROADMAP.md` 段二 🟡 待补 #047 划掉
- [ ] **T-DOC-6.1.6**: `specs/ROADMAP.md` 段五 🎯 实施节奏 累计计数 47 → 48
- [ ] **T-DOC-6.1.7**: `constitution.md` §10 R-13 缓解 Story 列表补 `#047` 行**第 31 次** PASS 0 binary delta + 累计计数 30 → 31
- [ ] **T-DOC-6.1.8**: `constitution.md` §4 ErrorCode 域字母表不变(P / X / M / D / L / T / S / C / R / A / Z 11 域 + 新 X / M / D / P 启用历史)

### Task 6.2 — 设计文档(本仓外的真理源)

- [ ] **T-DOC-6.2.1**: dsh §13 v1.5.60 行新增 11 节记录 Story #047 完成
- [ ] **T-DOC-6.2.2**: dsh §6.1 LinearTurnEngine 段补 `tryAskUserPolicy` 守卫(若需要,本 Story 0 改 LinearTurnEngine,本条跳过)
- [ ] **T-DOC-6.2.3**: dsh §5.5 Slot 4 PermissionPolicy 段补 Story #047 marker
- [ ] **T-DOC-6.2.4**: dsh §5.6.4 SPI 总表 Slot 4 PermissionPolicy row 补「AgentFactory.create() L280 wiring 已修」marker
- [ ] **T-DOC-6.2.5**: `MyDSHAgentDesign/dsh_agent_design.md` v1.5.59 → v1.5.60
- [ ] **T-DOC-6.2.6**: `MyDSHAgentDesign/CLAUDE.md` 「对应设计文档」版本号 v1.5.59 → v1.5.60

### Task 6.3 — 配套文档版本号同步

- [ ] **T-DOC-6.3.1**: `CLAUDE.md`(本仓)`CLAUDE.md`(MyDSHAgentDesign)版本号同步 v1.3.56 → v1.3.57
- [ ] **T-DOC-6.3.2**: SKILL v1.0.28 → v1.0.29(若本仓 .claude/skills/ 存在)
- [ ] **T-DOC-6.3.3**: SOP v1.18 → v1.19
- [ ] **T-DOC-6.3.4**: prompts v1.0.16 → v1.0.17

---

## Phase 7 — commit(~2 min)

### Task 7.1 — git 提交

- [ ] **T-GIT-7.1.1**: `git status` 确认改动文件清单
- [ ] **T-GIT-7.1.2**: `git diff --stat` 确认 6 文件改动(3 prod + 3 test) + 8 docs 同步
- [ ] **T-GIT-7.1.3**: `git log -1 --format='%s'` 看上一条 commit 格式对齐
- [ ] **T-GIT-7.1.4**: `git add` 具体文件(不用 `-A`,避免 `.env` 之类误提交)
- [ ] **T-GIT-7.1.5**: `git commit -m "$(cat <<'EOF'\nfix(permission): Story #047 ask-policy end-to-end wiring — AgentFactory L280 wrong Slot field + ChatController yml bypass\n\nStory #029/#030 落地后端 ask-mode 完整链路(AskUserPermissionPolicy + ApprovalRegistry + ApprovalGate SPI + frontend button 闭环),\n但 AgentFactory.create() L280 把 policyRouter.resolve 的 key 写成 config.getSandbox().getPolicy()(Slot 3 子字段 默认 \"default\"),\n正解是 config.getPermissionPolicy()(Slot 4 顶层字段 默认 \"default\",可被 yml permission-policy: ask 覆盖)。\n\n外加 ChatController.createSession() hand-roll 28-arg AgentConfig 构造器完全绕过 yml 加载,\n只覆盖 2 个 Slot 名(5 + 6),permission-policy: ask 配了也读不到。\nDemoProductApplication.agentConfig(Environment) @Bean 早就 read 了 permissionPolicy + tools.allow-list/deny-list,\n但 readTools() L382 硬编码空 askList,yml tools.ask-list 配了也读不到。\n\n3 prod file 改动:\n  - AgentFactory.java:280 1-line typo fix (config.getSandbox().getPolicy() → config.getPermissionPolicy())\n  - DemoProductApplication.readTools() L382 1-line hardcode fix (Collections.emptyList() → readToolsList(env, \"agent.tools.ask-list\", fallback.getAskList()))\n  - ChatController.createSession() 改用现有 agentConfig(Environment) @Bean(不再 hand-roll 28-arg ctor)\n\n3 test files 改动(覆盖 AC-1 到 AC-10):\n  - AgentFactoryAskPolicyRoutingIT 3 L1/L2 case\n  - AgentFactoryAskPolicyYamlIT 2 L2 case\n  - DemoProductAskPolicyEndToEndIT 1 L3 e2e case\n\nR-13 mitigation (d) baseline 镜像 pre/post mvn dependency:tree diff 仅时间戳差异 =\n0 binary delta 第 31 次 PASS(纯 Java 1-line fix + Lombok @Value + Spring @Autowired 已锁 0 新 binary 引入)。\n\n0 新 Maven 依赖 / 0 新 ErrorCode(复用 LINGS-P01 + LINGS-P02 from Story #029 + #030)。\n\n修复者: Claude Code(根据用户 2026-10-14 会话反馈「demo-product 配 permission-policy: ask 后 0 触发 ApprovalRequired」,\n确认 Bug A(framework typo) + Bug B(demo-product bypass)双重根因,要求「开 Story #047 全部修复(最小修复 = A + B 都改)」,\n触发本 Story Phase 1-5 实施 + 6 case AC 黑盒验证 + R-13 mitigation (d) baseline 镜像 第 31 次 PASS 0 binary delta)。\n\n累计 48 个 Story 合入。\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>\nEOF\n)"`
- [ ] **T-GIT-7.1.6**: `git log -1 --stat` 确认 commit 落地
- [ ] **T-GIT-7.1.7**: `git status` 确认 working tree clean

---

## AC ↔ task mapping(交叉验证)

| AC | 覆盖 task | 验证方式 |
|---|---|---|
| AC-1 (AgentFactory reads `getPermissionPolicy()`) | T-A-1.1.2 + T-T-4.1.2 | L1 unit `AgentFactoryAskPolicyRoutingIT.case1` |
| AC-2 (resolve AskUserPermissionPolicy when "ask") | T-A-1.1.2 + T-T-4.1.2 + T-T-4.3.3 | L1 unit + L3 e2e |
| AC-3 (yolo back-compat "default" 走 AllowAll) | T-RAC-4.4.6 + T-T-4.1.3 | L1 unit + reverse AC |
| AC-4 (`readTools()` 读 yml ask-list) | T-B1-2.1.2 + T-T-4.2.2 | L2 IT `AgentFactoryAskPolicyYamlIT.case1` |
| AC-5 (ChatController 改用 @Bean) | T-B2-3.1.3 + T-B2-3.1.4 | code review + L3 e2e 间接 |
| AC-6 (e2e emit ApprovalRequired) | T-T-4.3.2 + T-T-4.3.3 | L3 e2e `DemoProductAskPolicyEndToEndIT` |
| AC-7 (R-13 dep-tree 0 binary delta) | T-R13-0.1.1 + T-R13-5.3.2 | `diff /tmp/r13-pre.txt /tmp/r13-post.txt` |
| AC-8 (0 新 ErrorCode + 0 新依赖) | T-SC-5.4.1 + T-SC-5.4.2 | static check |
| AC-9 (reverse AC: default fixture 0 改动) | T-RAC-4.4.6 | reverse AC regression |
| AC-10 (reverse AC: strict fixture 0 改动) | T-RAC-4.4.1 + T-RAC-4.4.2 + T-T-4.1.4 | reverse AC regression |

---

## Story budget guards

- [x] **3 核心 prod 文件改动**(AgentFactory L280 + DemoProductApplication.readTools L382 + ChatController 改造)≤ 5 ✅
- [x] **0 新 ErrorCode** ≤ 3 ✅
- [x] **0 新 Maven 依赖** ≤ 0 ✅
- [x] **0 改 SPI 公开方法签名**(`PermissionPolicy` / `Decision` / `AgentEvent.ApprovalRequired` 不动)✅
- [x] **0 改 LinearTurnEngine / ApprovalRegistry / AskUserPermissionPolicy** ✅

---

## R-13 第 31 次 PASS checklist

- [x] 0 新 Maven 依赖
- [x] 0 新传递依赖(spring-boot / spring-ai / lombok / jackson / reactive-streams / opentelemetry 等 13 项全 lock)
- [x] 0 新 binary(纯 Java 1-line fix + Lombok + Spring)
- [x] 0 新 ErrorCode 域字母
- [x] 0 新公开方法签名
- [x] 0 改 ReAct Loop
- [x] 0 改 Spring AI ChatClient 调用(§4.10.1 硬规则 2 守住)

---

**修复者**: Claude Code(根据用户 2026-10-14 会话反馈,触发本 Story spec/plan/tasks 落地 + Phase 1-5 实际代码改动 + 6 case AC 黑盒验证 + R-13 mitigation (d) baseline 镜像 第 31 次 PASS 0 binary delta;累计 48 个 Story 合入)
