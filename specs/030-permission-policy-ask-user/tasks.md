# Tasks: Story #030 `permission-policy-ask-user`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 5 modify + 5 new 代码 + 8 测试新增 ≈ 18 文件,但严格按 P1—P6 拆分 commit,每个 commit 1-3 文件)
>
> **实施顺序严格按 plan §3**:`PermissionErrorCodes.LINGS_P02` → `AgentEvent.ApprovalRequired` 扩 `approvalId` → `AgentConfig.approvalTimeoutSeconds` → `AskUserPermissionPolicy` → `DefaultToolExecutionContext.approval()` 真阻塞 → `DefaultToolExecutor` 删 stub → `LinearTurnEngine.dispatchWithPolicy` AskUser 真接通 → `AskUserPermissionPolicyProvider` → `ToolsConfig.askList` → demo-product `ApprovalRegistry` → demo-product `ChatController` 端点 → `SessionRegistry.evict` → demo-product `AgentEventMapper` 扩字段 → demo yml 改 → 测试 fixture → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#030` 复用 JDK 8 `CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `Map` + `UUID.randomUUID()` + `Arrays.asList` + Lombok `@Value` + Spring `@Component` + `@Autowired` 全 JDK 8 built-in 0 新二进制,需验证 0 binary delta 第 17 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(permission): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(5 modify + 5 new)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/permission/PermissionErrorCodes.java` modify —— 加常量 `public static final String LINGS_P02 = "LINGS-P02";` + 类级 Javadoc 注释「🆕 Story #030 PERMISSION_APPROVAL_TIMEOUT,P 域段 2 号」+ 引用 dsh §15.4(预估 2min,spec §4 AC-NN-5)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/event/AgentEvent.java` modify —— `ApprovalRequired` 加 1 字段 `private final String approvalId;` + `@RequiredArgsConstructor` 自动 all-args ctor 扩为 3-arg(`Decision.AskUser ask, Consumer<Decision> continuation, String approvalId`)+ `@Getter` 自动扩 `getApprovalId()`;**back-compat 守住**:2-arg ctor 移除(所有 `new ApprovalRequired(...)` sites 必须同步改为 3-arg;grep `new ApprovalRequired` 应只有 0 stale site)(预估 5min,spec §4 AC-NN-7)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` modify —— 加顶层字段 `@Builder.Default long approvalTimeoutSeconds = 0;` + `@Value` 自动 final + 类级 Javadoc 补(1) **默认 0 = 无超时** 语义(匹配 Claude Code 隔夜审批)+ (2) `>0` = N 秒超时(LINGS-P02 触发) + (3) Story #030 引入 + (4) dsh §4.7 reference(预估 3min,spec §4 AC-NN-5 + AC-NN-8)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicy.java` 新增 —— `@Value @Getter @ToString public class AskUserPermissionPolicy implements PermissionPolicy` + 字段:`private final AgentConfig.ToolsConfig tools;` / `private final Map<String, String> nameToCategory;` / `private final long approvalTimeoutSeconds;` + 1-arg ctor `AskUserPermissionPolicy(ToolsConfig)` for test fixture(委托 3-arg + empty map)** + 3-arg primary ctor + `@Override public Decision check(ToolCall call, ToolExecutionContext ctx)` 实现 2 决策路径:(a) `for (String pattern : tools.getAskList()) if (PermissionPatterns.matches(toolName, toolCategory, pattern)) return new Decision.AskUser("Tool '" + toolName + "' requires user approval", Arrays.asList(new Decision.Option("allow", "Approve this call"), new Decision.Option("deny", "Reject this call")));`;(b) 不命中 → `return new Decision.Allow("ask policy: not in ask-list, default allow");`;类级 Javadoc 补 (1) pattern matching 复用 #031 + (2) 不命中抛 = default allow + (4) dsh §4.7 reference(预估 30min,spec §4 AC-NN-4)
- [ ] **T05** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java` modify —— (1) `approval()` 方法重写,返 `new AsyncApprovalGate(turnCtx, cancellation(), approvalTimeoutSeconds)`;(3) 新增内嵌静态 `private static final class AsyncApprovalGate implements ApprovalGate`(~40 行),字段 `final TurnContext ctx;` / `final CancellationToken token;` / `final long timeoutSec;`;`@Override public Decision ask(Decision.AskUser ask)` 实现 3 段:`CompletableFuture<Decision> fut = new CompletableFuture<>();` → `ctx.sink().onNext(new AgentEvent.ApprovalRequired(ask, decision -> fut.complete(decision), generateApprovalId(ctx)));` → `try { return fut.get(timeoutSec, TimeUnit.SECONDS); } catch (TimeoutException te) { return new Decision.Deny("[" + PermissionErrorCodes.LINGS_P02 + "] Approval timeout after " + timeoutSec + "s — default Deny for safety"); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return new Decision.Deny("[" + PermissionErrorCodes.LINGS_P01 + "] Cancelled during approval"); } catch (ExecutionException ee) { throw new RuntimeException("Approval failed", ee.getCause()); }`;`generateApprovalId(TurnContext)` = `<sessionId>-<step>-<uuid8>`(Helper 类,导 `java.util.UUID`)(预估 60min,spec §4 AC-NN-1)
- [ ] **T06** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutor.java` modify —— 删 L111-115 AskUser stub(改 comment 引用 #030「AskUser now handled in LinearTurnEngine.dispatchWithPolicy L437-455）（预估 2min,spec §4 AC-NN-2)
- [ ] **T07** `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` modify —— (1) `dispatchWithPolicy()` AskUser 分支真接通(`L437-445`):(a) `String approvalId = "<sessionId>-<step>-<uuid8>"`;`(b) ApprovalRequired emit(ApprovalRequired(ask, consumer, approvalId));`(c) `Decision resolved = toolCtx.approval().ask(ask);`;`(d) switch(resolved) → Allow / Deny / AskUser(recursion guard)`.max-iterations guard `AskUser → AskUser → AskUser 计数到 3 → ToolResult.error("AskUser recursion limited (max 3 retries)")`(预估 30min,spec §4 AC-NN-3)
- [ ] **T08** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicyProvider.java` 新增 —— `@Component public class AskUserPermissionPolicyProvider implements PermissionPolicyProvider` + 字段 `private final ToolRegistry toolRegistry;`(`@Autowired` 注入)+ `name()="ask"` + `priority()=10` + `version()="1.0.0"` + `@Override public PermissionPolicy create(AgentConfig cfg)` 实现:`Map<String, String> nameToCategory = new HashMap<>(); for (Tool t : toolRegistry.findAll()) { nameToCategory.put(t.name(), t.sourceCategory()); } return new AskUserPermissionPolicy(cfg.getTools(), nameToCategory, cfg.getApprovalTimeoutSeconds());`;类级 Javadoc 补 (1) name()="ask" + priority()=10 多 Provider 模式 + (2) Provider 复用 `#031` StrictPermissionPolicyProvider 模板 + (3) dsh §5.5 reference(预估 20min,spec §4 AC-NN-8)
- [ ] **T09** `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` modify(继续 T03)— `ToolsConfig` 子类加 1 字段 `@Builder.Default List<String> askList = new ArrayList<>();` + `@Value` 不可变;类级 Javadoc 补(1) ask-list pattern 语义复用 PermissionPatterns + (2) 默认空 list = default Allow + (4) Story #030 spec(预估 5min,spec §4 AC-NN-4)
- [ ] **T10** `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` modify —— `loadYamlAndValidate` 扩 `permission-policy: ask` 解析路径:若 `cfg.permissionPolicy == "ask"` → 调 `permissionPolicyRouter.resolve("ask", cfg)`(若 `#029` 已支持 strict / default 二选一,扩 ask 第 3 项);`ToolsConfig.askList` yml binding 走 `#031` 路径(ask-list 字面 list);`approval-timeout` yml binding 走 default 0(无超时);若 yml 显式配 N(N>0),则按 N 走 timeout → LINGS-P02(预估 30min,如果需要)
- [ ] **T11** `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/ApprovalRegistry.java` 新增 —— `@Component public class ApprovalRegistry` + `private final ConcurrentHashMap<String /* sessionId+":"+approvalId */, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();` + 3 API:`register(sessionId, approvalId, fut): String`(put + return approvalId)、`resolve(sessionId, approvalId, decision): void`(`pending.remove(key)` + `fut.complete(decision)`)、`evict(sessionId): void`(扫 `pending.keySet().stream().filter(k -> k.startsWith(sessionId+":")).forEach(k -> { CompletableFuture<Decision> f = pending.remove(k); if (f != null) f.complete(new Decision.Deny("Session evicted")); })`);类级 Javadoc 补 (1) per-session per-approval-id pending 映射 + (2) evict 释放内存 + (3) dsh §4.7 ApprovalRequired event consumer 契约(预估 30min,spec §4 AC-NN-6)
- [ ] **T12** `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/ChatController.java` modify —— (1) 注入 `ApprovalRegistry approvalRegistry` 字段;(2) 加 `@PostMapping("/api/approvals/{sessionId}/{approvalId}") public Map<String, String> resolveApproval(@PathVariable String sessionId, @PathVariable String approvalId, @RequestBody Map<String, String> body)` 端点:`String d = body.get("decision"); if (!"allow".equals(d) && !"deny".equals(d)) throw new BadRequestException("decision must be 'allow' or 'deny'"); String reason = body.getOrDefault("reason", "user " + d + "d"); Decision decision = "allow".equals(d) ? new Decision.Allow(reason) : new Decision.Deny(reason); approvalRegistry.resolve(sessionId, approvalId, decision); return Map.of("status", "resolved");`;类级 Javadoc 补 (1) SSE round-trip 模式 + (2) approval lifecycle + (3) dsh §4.7 reference(预估 30min,spec §4 AC-NN-6)
- [ ] **T13** `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/SessionRegistry.java` modify —— `evict(sessionId)` 调 `approvalRegistry.evict(sessionId)` 释放 pending approval 内存(防止 session 释放后 pending 累积);(预估 5min,间接)
- [ ] **T14** `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/AgentEventMapper.java` modify —— ApprovalRequired 序列化时加 `out.put("approvalId", e.getApprovalId());`(1 行改);(预估 2min,spec §4 AC-NN-6 + AC-NN-7)
- [ ] **T15** `lingshu-examples/demo-product/src/main/resources/application.yml` modify —— `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]` + `approval-timeout: 0`(默认,显式注释「无超时,匹配 Claude Code 隔夜审批」;用户可显式配 N 走 timeout → LINGS-P02)+ 注释引用 dsh §5.5 + §4.7 + §15.4 P 段 + 本 Story #030 spec(预估 10min,spec §4 AC-NN-8)

> **P1 总耗时**:~285 min(~4.75h)

---

## P2:测试(8 测试文件 + 13 case)

- [ ] **T16** `lingshu-core/src/test/java/ai/lingshu/core/permission/PermissionErrorCodesLINGS02Test.java` 新增 L1 Unit —— 1 case(`PermissionErrorCodes.LINGS_P02 == "LINGS-P02"`)(预估 5min)
- [ ] **T17** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContextApprovalTest.java` 新增 L1 Unit —— 4 case(AC-NN-1):
   - case 1:`approvalTimeoutSeconds = 1`(测试加速)+ subscriber 不 invoke → 阻塞 1s → `Decision.Deny("[LINGS-P02] Approval timeout after 1s — default Deny for safety")`
   - case 2:`approvalTimeoutSeconds = 60` + subscriber 在 100ms 内 invoke `accept(Decision.Deny("user denied"))` → `ask()` 返 `Decision.Deny("user denied")`
   - case 3:`approvalTimeoutSeconds = 60` + cancellation token fired → 立即返 `Decision.Deny("[LINGS-P01] Cancelled during approval")`,**不**等 timeout
   - case 4:**`approvalTimeoutSeconds = 0`(默认 无超时)** + subscriber 5s 后 invoke `accept(Decision.Allow("user approved"))` → `ask()` 阻塞 5s 后返 `Decision.Allow("user approved")`(`Long.MAX_VALUE` 等效,**不**触发 LINGS-P02)
   - (预估 75min)
- [ ] **T18** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolExecutorAskUserTest.java` 新增 L1 Unit —— 1 case(AC-NN-2):`PermissionPolicy` mock 返 `Decision.AskUser` + `DefaultToolExecutor.dispatch(call, ctx)` → **不**抛 `PermissionDeniedException("AskUser approval flow is wired in Story #005 follow-up")`(可返 Allow / ToolResult.error 任一,但**不**是 stub 拒绝消息)(预估 15min)
- [ ] **T19** `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserIT.java` 新增 L2 Slice —— 2 case(AC-NN-3):
   - case 1:Allow:`PermissionPolicy` 返 `AskUser` + `ApprovalGate` mock 立即 `Decision.Allow("user approved")` + `maxSteps=1` → sink 收到顺序事件:`ApprovalRequired(ask, consumer, approvalId)` → `ToolStarted` → `ToolCompleted(success)` → `TurnCompleted(END_TURN)`;approvalId 唯一
   - case 2:Deny:`PermissionPolicy` 返 `AskUser` + `ApprovalGate` mock 立即 `Decision.Deny("user denied")` → sink 收到顺序事件:`ApprovalRequired` → `ToolStarted` → `ToolCompleted(error content="user denied")` → `TurnCompleted(END_TURN)`
   - (预估 90min)
- [ ] **T20** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicyTest.java` 新增 L1 Unit —— 4 case(AC-NN-4):
   - case 1:`ask-list: [bash_safe, write_file]` + `nameToCategory: {bash_safe → local, write_file → local}` + `policy.check(ToolCall("bash_safe"))` → `Decision.AskUser`
   - case 2:`ask-list: [mcp:*]` + `nameToCategory: {echo → mcp, write_file → local}` + `policy.check(ToolCall("echo"))` → `Decision.AskUser`(matches `mcp:*`)
   - case 3:`ask-list: [write_file]` + `policy.check(ToolCall("bash_safe"))` → `Decision.Allow`(不在 ask-list)
   - case 4:`ask-list: [bash_safe]` + `policy.check(ToolCall("read_file"))` → `Decision.Allow`(default)
   - (预估 60min)
- [ ] **T21** `lingshu-core/src/test/java/ai/lingshu/core/permission/ApprovalTimeoutLINGS02Test.java` 新增 L1 Unit —— 2 case(AC-NN-5 双 case:触发 / 不触发):
   - case 1(触发):`AgentConfig.approvalTimeoutSeconds = 1` + `PermissionPolicy` 返 `AskUser` + `ApprovalGate` 不响应 → `LinearTurnEngine.runTurn` → `dispatchWithPolicy` AskUser 分支 → 阻塞 1s → timeout → `ToolResult` content 含 `"[LINGS-P02] Approval timeout after 1s"`
   - case 2(不触发):`AgentConfig.approvalTimeoutSeconds = 0`(默认 无超时)+ `PermissionPolicy` 返 `AskUser` + `ApprovalGate` 5s 后响应 → `ToolResult` content **不**含 `[LINGS-P02]`,验证默认 0 = 无超时永不触发 LINGS-P02
   - (预估 45min)
- [ ] **T22** `lingshu-core/src/test/java/ai/lingshu/core/event/ApprovalRequiredEventTest.java` 新增 L1 Unit —— 1 case(AC-NN-7):同一 turn 3 次 AskUser → 3 个 `ApprovalRequired` event 各有 unique `approvalId`;format `"<sessionId>-<step>-<uuid8>"`;uuid8 长度 8(预估 30min)
- [ ] **T23** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicyProviderIT.java` 新增 L2 Slice —— 1 case(`AskUserPermissionPolicyProvider.create(cfg)` 真 populate `nameToCategory` + 通过反射读 `AskUserPermissionPolicy.nameToCategory` 字段验证 N entry 正确填充;`ToolRegistry` mock 5 个 Tool(2 McpToolAdapter + 2 SkillTool + 1 本地))(预估 30min)
- [ ] **T24** `lingshu-examples/demo-product/src/test/java/.../DemoProductAskUserRoundTripIT.java` 新增 L3 黑盒 —— 1 case(AC-NN-6):启动 demo-product Spring Boot + `permission-policy: ask` + `tools.ask-list: [write_file]`;`POST /api/chat/{sessionId}` SSE 触发 `ApprovalRequired` event;客户端从 SSE 发出;前端 POST `POST /api/approvals/{sessionId}/{approvalId}` body `{decision: "deny", reason: "too dangerous"}` → `ApprovalRegistry.resolve()` → 原 SSE 流后续 `ToolCompleted(error content="too dangerous")`;turn 正常结束(不 hang)(预估 120min)
- [ ] **T25** `lingshu-examples/demo-product/src/test/java/.../DemoProductAskUserYmlIT.java` 新增 L3 黑盒 —— 1 case(AC-NN-8):启动 demo-product + yml `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]`;启动日志验证 `resolved PermissionPolicy: ask (priority=10)`;`POST /api/chat/{sessionId}` 触发;SSE 流 emit `ApprovalRequired` event with `approvalId` field;用户决策 → 后续 emit `ToolCompleted` 反映(Allow → success / Deny → error)(预估 90min)

> **P2 总耗时**:~530 min(~8.8h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=DefaultToolExecutionContextApprovalTest` 验证 3 路径(timeout / 正常 / cancel)(预估 5min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=DefaultToolExecutorAskUserTest` 验证 stub 删(预估 5min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=LinearTurnEngineAskUserIT` 验证 2 case(Allow / Deny)端到端(预估 10min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=AskUserPermissionPolicyTest` 验证 4 case pattern matching + fallback(预估 5min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=ApprovalTimeoutLINGS02Test` 验证 `LINGS-P02` 双 case(timeout 触发 / `0=无超时` 不触发)(预估 5min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductAskUserRoundTripIT` 验证 SSE round-trip 端到端(预估 15min)
- [ ] **T-validate-AC-NN-7** 跑 `mvn -pl lingshu-core test -Dtest=ApprovalRequiredEventTest` 验证 approvalId unique(预估 5min)
- [ ] **T-validate-AC-NN-8** 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductAskUserYmlIT` 验证 yml 端到端贯通(预估 15min)
- [ ] **T-validate-AC-NN-9** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose` + diff 对比 `#031` post-commit baseline 镜像,验证 0 新 Maven 坐标(R-13 第 17 次)(预估 15min)
- [ ] **T-validate-AC-NN-10** 跑 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 全模块无 fail,新增 13 case 全过,660 pre test 0 回归(预估 30min)

> **P3 总耗时**:~110 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-030-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-030-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-031-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-030-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-product package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min

---

## P5:文档同步(11 文件)

- [ ] **T-doc-1** `README.md` 顶部加 `#030` 1 段(`PermissionPolicy` AskUser 路径真接通 + `permission-policy: ask` 3rd 选项 + `LINGS-P02` 新 ErrorCode + demo-product SSE round-trip + 8 文件核心改动)(预估 10min)
- [ ] **T-doc-2** `specs/030-permission-policy-ask-user/quickstart.md` 起草(给 Alice 30min 跑通 AskUser 路径,模板对齐 `#027a` / `#027b` / `#028` / `#029` / `#031`)(预估 30min)
- [ ] **T-doc-3** `specs/030-permission-policy-ask-user/data-model.md` 起草(`AskUserPermissionPolicy` / `AgentEvent.ApprovalRequired` 扩字段 / `DefaultToolExecutionContext.approval()` 真阻塞实现 / `LinearTurnEngine.dispatchWithPolicy()` AskUser 真接通 / `LINGS-P02` ErrorCode + approval timeout 阈值对照表)(预估 30min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.51 → v1.5.52` 行(本 Story 实施记录,18 节概要对齐 `#031`)(预估 15min)
- [ ] **T-doc-5** `dsh_agent_design.md` §5.5 Slot 4 `PermissionPolicyRouter` design intent 段补「🆕 v1.5.52 Story #030 落地 AskUser 3rd Provider」段(多 Provider 模式新增 ask)(预估 10min)
- [ ] **T-doc-6** `dsh_agent_design.md` §4.4 AgentEvent.ApprovalRequired 段补「🆕 v1.5.52 Story #030 加 approvalId 字段(round-trip 路由)」(预估 5min)
- [ ] **T-doc-7** `dsh_agent_design.md` §15.4 ErrorCode 域 P 段加 LINGS-P02 行(`PERMISSION_APPROVAL_TIMEOUT` — Story #030 引入)(预估 5min)
- [ ] **T-doc-8** `constitution.md` §10 R-13 风险登记:`Story #030` 标记「已缓解」+ 第 17 次 0 binary delta 验证结果(预估 5min)
- [ ] **T-doc-9** `ROADMAP.md` 段一 ✅ 已完成表加 `#030` 行(2026-10-02,~680 pass / 0 fail / R-13 0 binary delta 第 17 次 / +1 LINGS-P02 / +13 new case)+ 段二 🟡 待补 #030 划掉 + 段五 🎯 实施节奏 统计 39 已合 / 0 待补(预估 10min)
- [ ] **T-doc-10** `lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#030` 段落(Story 推 master 后开,本 Story 内**不**强制;留 OQ-Future,预估 30min)
- [ ] **T-doc-11** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.51` → `v1.5.52`)(预估 5min)

> **P5 总耗时**:~155 min(~2.6h)

---

## P6:PR 提交与合并

- [ ] **T-pr-1** 创建分支 `feature/story-030-permission-policy-ask-user`(基于 main)(预估 2min)
- [ ] **T-pr-2** 累计 commit(P1 + P2 + P3 + P4 + P5 共 ~35 commit),每 commit 格式 `feat(permission): T-NN <一句话>`(预估 30min)
- [ ] **T-pr-3** 推送到 `origin/feature/story-030-permission-policy-ask-user`(预估 2min)
- [ ] **T-pr-4** `gh pr create --base main --head feature/story-030-permission-policy-ask-user --title "feat(permission): Story #030 permission-policy-ask-user" --body "$(cat <<'EOF'
## Summary

- 🆕 Story #030 PermissionPolicy AskUser 路径真接通(替代 3 个 stub)
- `DefaultToolExecutionContext.approval()` 真阻塞实现(`CompletableFuture<Decision>` + timeout + cancel)
- `DefaultToolExecutor` 删 L111-115 stub(`LinearTurnEngine.dispatchWithPolicy` 真接管)
- `LinearTurnEngine.dispatchWithPolicy` AskUser 分支真接通(emit `ApprovalRequired` + 阻塞 + 决策递归 guard 3 次)
- `AgentEvent.ApprovalRequired` 扩 `approvalId` 字段(round-trip 路由)
- `AskUserPermissionPolicy` 新增 3rd Provider(`permission-policy: ask` + pattern matching + 多 Provider 模式)
- `AgentConfig.approvalTimeoutSeconds` 顶层字段(默认 **0 = 无超时**,匹配 Claude Code 隔夜审批;`>0` = N 秒,`LINGS-P02` 触发阈值)
- `ToolsConfig.askList` 新字段(pattern matching 复用 #031 `PermissionPatterns`)
- +1 新 ErrorCode `LINGS-P02 PERMISSION_APPROVAL_TIMEOUT`(P 域段 2 号)
- demo-product SSE round-trip:`ApprovalRegistry` + `ChatController.continueApproval` + `AgentEventMapper` ApprovalRequired 加 `approvalId`
- demo yml `permission-policy: ask` + `ask-list: [write_file, bash_safe]` + `approval-timeout: 0`(默认,无超时,匹配 Claude Code 隔夜审批)

## Test plan

- [x] L1 Unit `PermissionErrorCodesLINGS02Test` 1 case
- [x] L1 Unit `DefaultToolExecutionContextApprovalTest` 3 case(AC-NN-1 timeout / 正常 / cancel)
- [x] L1 Unit `DefaultToolExecutorAskUserTest` 1 case(AC-NN-2 stub 删)
- [x] L1 Unit `AskUserPermissionPolicyTest` 4 case(AC-NN-4 pattern matching + fallback)
- [x] L1 Unit `ApprovalTimeoutLINGS02Test` 1 case(AC-NN-5 timeout 路径)
- [x] L1 Unit `ApprovalRequiredEventTest` 1 case(AC-NN-7 approvalId unique)
- [x] L2 Slice `LinearTurnEngineAskUserIT` 2 case(AC-NN-3 Allow / Deny 端到端)
- [x] L2 Slice `AskUserPermissionPolicyProviderIT` 1 case(`create(cfg)` populate nameToCategory)
- [x] L3 黑盒 `DemoProductAskUserRoundTripIT` 1 case(AC-NN-6 SSE round-trip)
- [x] L3 黑盒 `DemoProductAskUserYmlIT` 1 case(AC-NN-8 yml 端到端)
- [x] 全模块 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 无 fail
- [x] Story #029 + #031 back-compat 测试(`StrictPermissionPolicyTest` + `StrictPermissionPolicyPatternTest` + `StrictPermissionPolicyReasonTest`)全过(0 回归)
- [x] R-13 mitigation (d) baseline 镜像第 17 次 PASS 0 binary delta

### R-13 dependency:tree 自查

`mvn -pl lingshu-core dependency:tree -Dverbose` pre/post diff 仅时间戳差异,无新增 Maven 坐标。复用 JDK 8 `CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `Map` + `UUID.randomUUID()` + `Arrays.asList` + Spring `@Component` / `@RestController` + Lombok `@Value` 全 JDK 8 standard + 已锁 13 项依赖表内。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"`(预估 10min)
- [ ] **T-pr-5** 等 CI 全绿 + reviewer approval 后 merge(走 squash merge 保持 main commit 历史 clean)(预估 10min)

> **P6 总耗时**:~54 min

---

## 总耗时估算

| Phase | 时长 |
|---|---:|
| P1 实现 | ~285 min(~4.75h)|
| P2 测试 | ~530 min(~8.8h)|
| P3 AC 验证 | ~110 min(~1.8h)|
| P4 依赖构建 | ~50 min(~0.8h)|
| P5 文档同步 | ~155 min(~2.6h)|
| P6 PR 提交 | ~54 min(~0.9h)|
| **合计** | **~1184 min(~19.7h)** |

> **Story #030 体量**与 Story #031(`~904 min`)+ Story #028(`~1300 min`)+ Story #029(`~750 min`)同量级;核心文件 5 modify + 5 new(代码)比 #031(10)略少;测试 13 case 与 #029(18)+ #031(26)+ #028(39)+ #027a(32)同量级或更轻

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-10-02
**Tasks version**: v0.1 Draft