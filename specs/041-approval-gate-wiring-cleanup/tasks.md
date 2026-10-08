# Tasks: Story #041 `approval-gate-wiring-cleanup`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因核心 = 2 真改 + 6 JavaDoc-only + 4 new test ≈ 12 文件,严格按 P1—P5 拆分 commit,每个 commit 1-3 文件)
>
> **实施顺序严格按 plan §3**:`DefaultApprovalGate` 真实现(T01) → `LinearTurnEngine` 下沉(T02) → 7 处 JavaDoc 改写(T03-T08)→ 4 处 fail-safe override JavaDoc 改写(T09-T12)→ 4 个 new test file(T13-T16)→ AC 验证(T17-T25)→ 文档同步(T26-T30)
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#041` 复用 JDK 8 `CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `UUID.randomUUID()` + `AtomicBoolean` + `Arrays.asList` + Lombok `@Value` + Spring `@Component` + `@Autowired` 全 JDK 8 built-in 0 新二进制,需验证 0 binary delta 第 **24 次**)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(permission): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:核心实现(2 modify)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java` modify —— (1) `approval()` 方法重写,返 `new DefaultApprovalGate(turnCtx, cancellation(), turnCtx.config().getApprovalTimeoutSeconds())`;(2) 新增内嵌静态 `private static final class DefaultApprovalGate implements ApprovalGate`(~90 行),字段 `final TurnContext turnCtx;` / `final CancellationToken token;` / `final long timeoutSec;`;3-arg ctor `DefaultApprovalGate(TurnContext, CancellationToken, long)`;`@Override public Decision ask(Decision.AskUser ask)` 实现 **封装** Story #030 inline L467-559 全部逻辑:UUID 生成 + AtomicBoolean guard + CompletableFuture<Decision> + Consumer<Decision> continuation + ApprovalRegistry.register(approvalId, continuation) + emit AgentEvent.ApprovalRequired via turnCtx.sink().onNext() + decisionFuture.get(timeoutMs, MILLISECONDS) + 3 catch 分支(TimeoutException / InterruptedException / ExecutionException)各返 Decision.Deny 嵌 `[LINGS-P02]`;类级 Javadoc 补 (1) Story #041 提取来源 + (2) 5 路径(timeout / 正常 / cancel / no-sink / no-registry)+ (3) 行为等价 Story #030 inline + (4) dsh §9.3 reference(预估 60min,spec §4 AC-041-01—05)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` modify —— (1) `dispatchWithPolicy()` AskUser 分支**下沉**为 `Decision resolved = toolCtx.approval().ask((Decision.AskUser) d);` + 3 段 `if (resolved instanceof Decision.Allow) return toolExecutor.dispatch(...);` / `if (resolved instanceof Decision.Deny) return ToolResult.error(...)` / `else return ToolResult.error("[LINGS-P02] AskUser recursion limited (max 3 retries)");`(~15 行 替换 ~50 行 inline);(2) 删除不再用的 import(`UUID` / `Consumer` / `AtomicBoolean` / `CompletableFuture` / `ExecutionException` / `TimeUnit` / `PermissionErrorCodes` — 但 `TimeoutException` 仍需保留因为 DefaultApprovalGate 抛);类级 Javadoc 补「🆕 Story #041 改为调 `toolCtx.approval().ask(ask)` 走 SPI」(预估 30min,spec §4 AC-041-09—10)

> **P1 总耗时**:~90 min(~1.5h)

---

## P2:JavaDoc 改写(11 modify 纯注释)

- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutionContext.java` modify —— `ApprovalGate` interface 段 JavaDoc 改写「Asks the human a question from inside a tool (e.g. Bash needs confirmation for `rm -rf`). Blocks until the user answers or `approvalTimeoutSeconds` elapses. **Default implementation: `DefaultToolExecutionContext.DefaultApprovalGate` (Story #041)** — delegates to ApprovalRegistry via ApprovalRequired event round-trip; see dsh §9.3.」+ 加 `@see DefaultToolExecutionContext.DefaultApprovalGate` 锚点(预估 5min,纯注释)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/decision/Decision.java` modify —— `AskUser` 行 37 JavaDoc 改写「Policy requires human confirmation; engine pauses and routes to **DefaultApprovalGate.ask()** (Story #041 真接通 ApprovalGate SPI). Previously Story #030 inline-implemented in LinearTurnEngine.dispatchWithPolicy L467-559; Story #041 extracted to DefaultApprovalGate for Tool-level AskUser extension (specs/019-built-in-tools plan.md L498).」+ 加 `@see DefaultToolExecutionContext.DefaultApprovalGate` 锚点(预估 5min,纯注释)
- [ ] **T05** `lingshu-core/src/main/java/ai/lingshu/core/permission/PermissionErrorCodes.java` modify —— 行 20 + 行 38 JavaDoc 改写「emitted when **DefaultApprovalGate.ask()** (Story #041 真接通 ApprovalGate SPI,封装 Story #030 inline 路径) blocking call exceeded `approvalTimeoutSeconds`」+「**DefaultApprovalGate.ask()** blocking call exceeded `approvalTimeoutSeconds`;Story #030 引入;**Story #041 由 LinearTurnEngine inline 下沉为 SPI 真实现**」+ `@see DefaultToolExecutionContext.DefaultApprovalGate` 锚点(预估 5min,纯注释)
- [ ] **T06** `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` modify —— 行 198 JavaDoc 改写「approvalTimeoutSeconds: ApprovalGate.ask() blocks for at most this many seconds; **DefaultApprovalGate 真实现**(Story #041 封装 Story #030 inline L467-559 路径). 0 = wait indefinitely (matches Claude Code overnight approval). `>0` = N seconds, then return Decision.Deny with [LINGS-P02].」+ `@see DefaultToolExecutionContext.DefaultApprovalGate` 锚点(预估 5min,纯注释)
- [ ] **T07** `lingshu-core/src/main/java/ai/lingshu/core/slot/PermissionPolicy.java` modify —— 行 15 JavaDoc 改写 `Decision.AskUser` 注释「pause and route to **DefaultApprovalGate.ask()** (Story #041 真接通 ApprovalGate SPI)」+ `@see DefaultToolExecutionContext.DefaultApprovalGate` 锚点(预估 3min,纯注释)
- [ ] **T08** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicy.java` modify —— 行 33 + 行 122 JavaDoc 改写「The engine will emit AgentEvent.ApprovalRequired and **route to DefaultApprovalGate.ask()** (Story #041 真接通 ApprovalGate SPI) until the human answers or `AgentConfig.approvalTimeoutSeconds` elapses.」+ 行 122 同上 + `@see DefaultToolExecutionContext.DefaultApprovalGate` 锚点(预估 5min,纯注释)
- [ ] **T09** `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServerToolExecutionContext.java` modify —— 行 36-37 + 行 100 JavaDoc 改写「**Intentionally fail-safe Deny — server side has no human channel. Story #041 confirms this is correct contract, not a stub. If a Tool running here ever needs approval, it would require a per-server-channel ApprovalGate implementation that bridges to a real backend (e.g. multi-tenant SSE hub) — leave to OQ-Future.**」(预估 5min,纯注释)
- [ ] **T10** `lingshu-examples/demo-product-a2a-server/src/main/java/ai/lingshu/examples/demoproducta2aserver/DemoA2aServer.java` modify —— 行 656-661 JavaDoc 改写同上「**Intentionally fail-safe Deny — translate demo has no human channel. Story #041 confirms. **」(预估 5min,纯注释)
- [ ] **T11** `lingshu-cli/src/main/java/ai/lingshu/cli/SkillCommandDispatcher.java` modify —— 行 368-374 JavaDoc 改写同上「**Intentionally fail-safe Deny — CLI single-shot mode: user-initiated commands are implicitly user-approved. Story #041 confirms. **」(预估 5min,纯注释)
- [ ] **T12** `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpToolAdapterIT.java` modify —— 行 154 JavaDoc 改写同上「**Intentionally fail-safe Deny — IT fixture mock; default `null` returns nothing for AskUser (equivalent to Deny). Story #041 confirms. **」(预估 3min,纯注释)

> **P2 总耗时**:~50 min

---

## P3:测试(4 new test files + 11 case)

- [ ] **T13** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultApprovalGateTest.java` 新增 L1 Unit —— 5 case(AC-041-01—05):
   - case 1(AC-041-01 Allow 正常):`DefaultApprovalGate.ask(AskUser)` + subscriber 5s 后 invoke `accept(Decision.Allow("user approved"))` → 返回 `Decision.Allow("user approved")`,sink 收到 `AgentEvent.ApprovalRequired(ask, cont, approvalId)` 事件 1 次,approvalId 是 UUID format
   - case 2(AC-041-02 Deny 立即):`DefaultApprovalGate.ask(AskUser)` + subscriber 立即 invoke `accept(Decision.Deny("user denied"))` → 返回 `Decision.Deny("user denied")`,sink 收到 1 次
   - case 3(AC-041-03 timeout > 0):`approvalTimeoutSeconds = 1` + subscriber 不 invoke → 阻塞 1s → 返回 `Decision.Deny("[LINGS-P02] Permission approval timed out after 1s")`
   - case 4(AC-041-04 cancel):`cancellationToken.fire()` 50ms 后 → 立即返 `Decision.Deny("[LINGS-P02] Approval flow interrupted")`(不等 timeout)
   - case 5(AC-041-05 no-sink):`turnCtx.sink() = null` → 立即返 `Decision.Deny("[LINGS-P02] Approval required but no event sink registered")`(不阻塞)
   - (预估 90min)
- [ ] **T14** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/ServerSideApprovalStubsTest.java` 新增 L1 Unit —— 3 case(AC-041-06—08):
   - case 1(AC-041-06):`A2aServerToolExecutionContext.approval().ask(AskUser)` → 返 `Decision.Deny("A2aServerToolExecutionContext.approval() — AskUser denied (no human channel)")`(不调 ApprovalRegistry)
   - case 2(AC-041-07):`DemoA2aServer` 内嵌 ApprovalGate override → 返 `Decision.Deny("DemoA2aServer has no ApprovalGate — AskUser denied")`
   - case 3(AC-041-08):`SkillCommandDispatcher` 内嵌 ApprovalGate override → 返 `Decision.Deny("CLI Skill dispatch does not support AskUser approval (Story #020c MVP)")`
   - (预估 45min)
- [ ] **T15** `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserSpiIT.java` 新增 L2 Slice —— 2 case(AC-041-09—10):
   - case 1(AC-041-09 Allow 端到端):`PermissionPolicy` mock 返 `Decision.AskUser` + `ApprovalRegistry` mock + sink 收 `AgentEvent.ApprovalRequired` 后立即 invoke `continuation.accept(Decision.Allow("user approved"))` → `LinearTurnEngine.dispatchWithPolicy` 调 `toolCtx.approval().ask(ask)` → 走 DefaultApprovalGate → 返 Allow → `toolExecutor.dispatch(call, toolCtx)` 真调起 → `ToolCompleted(success)`. **语义等价 Story #030 inline 路径**(AC 关键验证点)
   - case 2(AC-041-10 Deny 端到端):同上,continuation.invoke(`Deny("user denied")`) → `ToolCompleted(error content="user denied")`
   - (预估 120min)
- [ ] **T16** `lingshu-examples/demo-product/src/test/java/.../DemoProductAskUserSpiRoundTripIT.java` 新增 L3 黑盒 —— 1 case(AC-041-11):启动 demo-product Spring Boot + `permission-policy: ask` + `tools.ask-list: [write_file]`;`POST /api/chat/{sessionId}` SSE 触发 `ApprovalRequired` event;前端 SSE 收到;POST `POST /api/approvals/{sessionId}/{approvalId}` body `{decision: "deny", reason: "too dangerous"}` → `ApprovalRegistry.resolve()` → 引擎侧 **DefaultApprovalGate 真接通** unblock → 后续 `ToolCompleted(error content="too dangerous")` → turn 正常 END_TURN(不 hang);**0 regression vs Story #030 #23 case**(预估 120min)

> **P3 总耗时**:~375 min(~6.25h)

---

## P4:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-041-01** 跑 `mvn -pl lingshu-core test -Dtest=DefaultApprovalGateTest` 验证 5 case Allow/Deny/timeout/cancel/no-sink(预估 5min)
- [ ] **T-validate-AC-041-02** 跑 `mvn -pl lingshu-core test -Dtest=ServerSideApprovalStubsTest` 验证 3 case fail-safe 守住(预估 5min)
- [ ] **T-validate-AC-041-03** 跑 `mvn -pl lingshu-core test -Dtest=LinearTurnEngineAskUserSpiIT` 验证 2 case Allow/Deny 端到端语义等价(预估 10min)
- [ ] **T-validate-AC-041-04** 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductAskUserSpiRoundTripIT` 验证 SSE round-trip 端到端 0 regression vs Story #030(预估 15min)
- [ ] **T-validate-AC-041-05** 跑 `mvn -pl lingshu-core test -Dtest=LinearTurnEngineAskUserTest` 验证 **Story #030 3 case 0 regression**(原路径 inline → SPI,语义等价)(预估 5min)
- [ ] **T-validate-AC-041-06** 跑 `mvn -pl lingshu-core test` 全模块无 fail,**新增 11 case 全过,685 pre test 0 回归**(预估 30min)
- [ ] **T-validate-AC-041-07** 跑 `mvn -pl lingshu-examples/demo-product test` 全模块无 fail(预估 30min)

> **P4 总耗时**:~100 min

---

## P5:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-041-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-041-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-037-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-041-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-product package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P5 总耗时**:~50 min

---

## P6:文档同步(11 文件)

- [ ] **T-doc-1** `README.md` 「更新日期」段加 `#041` 1 段(ApprovalGate SPI 真接通 + DefaultApprovalGate 内嵌 static class + LinearTurnEngine 下沉 + 8 文件核心改动 + 11 case AC 黑盒验证 + R-13 0 binary delta 第 24 次 PASS + 0 新 ErrorCode)(预估 10min)
- [ ] **T-doc-2** `specs/041-approval-gate-wiring-cleanup/quickstart.md` 起草(给 Alice 30min 跑通 ApprovalGate SPI 路径 + 验证 `permission-policy: ask` + Tool-level AskUser 扩展点预留;模板对齐 #030 / #031 / #037)(预估 30min)
- [ ] **T-doc-3** `specs/041-approval-gate-wiring-cleanup/data-model.md` 起草(`DefaultApprovalGate` 内嵌静态 class 完整契约 + `LinearTurnEngine.dispatchWithPolicy` 简化后流程图 + ApprovalRegistry 协作时序 + dsh §9.3 同步)(预估 30min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.53 → v1.5.54` 行(本 Story 实施记录,18 节概要对齐 #030 / #037)(预估 15min)
- [ ] **T-doc-5** `dsh_agent_design.md` §9.3 Tool+Approval 时序图 改写 — `LinearTurnEngine.dispatchWithPolicy` AskUser 分支改为调 `toolCtx.approval().ask(ask)`,`DefaultApprovalGate` 真实现补完整时序(预估 10min)
- [ ] **T-doc-6** `dsh_agent_design.md` §4.7 PermissionPolicy 段补「🆕 v1.5.54 Story #041 ApprovalGate SPI 真接通」段(从 inline 路径下沉为 SPI)(预估 10min)
- [ ] **T-doc-7** `dsh_agent_design.md` §4.6 ToolExecutionContext 段补「🆕 v1.5.54 Story #041 DefaultApprovalGate 真实现」段(预估 5min)
- [ ] **T-doc-8** `constitution.md` §10 R-13 风险登记:`Story #041` 标记「已缓解」+ 第 24 次 0 binary delta 验证结果(预估 5min)
- [ ] **T-doc-9** `ROADMAP.md` 段一 ✅ 已完成表加 `#041` 行(2026-10-03,696 pass / 0 fail / R-13 0 binary delta 第 24 次 / 0 新 ErrorCode / 累计 41 个 Story);段二 🟡 待补 #041 划掉;段五 🎯 实施节奏 统计 41 已合 / 0 待补 / +11 新 case(= 696 = 685 pre-#041 chain + 11 新)(预估 10min)
- [ ] **T-doc-10** `lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#041` 段落(Story 推 master 后开,本 Story 内**不**强制;留 OQ-Future,预估 30min)
- [ ] **T-doc-11** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.53` → `v1.5.54`)+ `constitution.md` §10 R-13 累计计数 23 → **24 个 Story**(预估 5min)

> **P6 总耗时**:~160 min(~2.7h)

---

## P7:PR 提交与合并

- [ ] **T-pr-1** 创建分支 `feature/story-041-approval-gate-wiring-cleanup`(基于 main)(预估 2min)
- [ ] **T-pr-2** 累计 commit(P1 + P2 + P3 + P4 + P5 + P6 共 ~30 commit),每 commit 格式 `feat(permission): T-NN <一句话>`(预估 30min)
- [ ] **T-pr-3** 推送到 `origin/feature/story-041-approval-gate-wiring-cleanup`(预估 2min)
- [ ] **T-pr-4** `gh pr create --base main --head feature/story-041-approval-gate-wiring-cleanup --title "feat(permission): Story #041 approval-gate-wiring-cleanup" --body "$(cat <<'EOF'
## Summary

- 🆕 Story #041 ApprovalGate SPI 真接通(`DefaultToolExecutionContext.DefaultApprovalGate` 内嵌静态 class)
- `LinearTurnEngine.dispatchWithPolicy` AskUser 分支**下沉**为 `toolCtx.approval().ask(ask)`(~50 行 → ~15 行,语义等价)
- 7 处 JavaDoc 改写(`Decision` / `PermissionErrorCodes` / `AgentConfig` / `PermissionPolicy` / `AskUserPermissionPolicy` / `ToolExecutionContext` / `DefaultToolExecutionContext`)— 移除"Story #005 will replace"误导,引用 `DefaultApprovalGate` 真实现 + dsh §9.3
- 3 处 fail-safe override JavaDoc 改写(`A2aServerToolExecutionContext` / `DemoA2aServer` / `SkillCommandDispatcher` + `McpToolAdapterIT` 测试 override)— **行为不变**(继续返 Deny,显式语义"此处无人在环"),只改 JavaDoc
- Tool-level AskUser 扩展点真接通(specs/019-built-in-tools plan.md L498 planned use case 现在有 SPI 可走)
- +4 new test file(11 case 跨 4 文件)
- **0 新 ErrorCode**(复用 `LINGS-P02`)
- **0 新 Maven 依赖**(R-13 mitigation (d) 第 24 次 PASS)

## Test plan

- [x] L1 Unit `DefaultApprovalGateTest` 5 case(AC-041-01 Allow / AC-041-02 Deny / AC-041-03 timeout / AC-041-04 cancel / AC-041-05 no-sink)
- [x] L1 Unit `ServerSideApprovalStubsTest` 3 case(AC-041-06 A2aServer / AC-041-07 DemoA2aServer / AC-041-08 SkillCommandDispatcher fail-safe 守住)
- [x] L2 Slice `LinearTurnEngineAskUserSpiIT` 2 case(AC-041-09 Allow 端到端 / AC-041-10 Deny 端到端,语义等价 Story #030 inline)
- [x] L3 黑盒 `DemoProductAskUserSpiRoundTripIT` 1 case(AC-041-11 demo-product SSE round-trip 端到端)
- [x] 全模块 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 无 fail
- [x] Story #030 23 case + Story #031 26 case + Story #037 9 case back-compat 测试全过(**0 回归**)
- [x] R-13 mitigation (d) baseline 镜像第 **24 次** PASS 0 binary delta

### R-13 dependency:tree 自查

`mvn -pl lingshu-core dependency:tree -Dverbose` pre/post diff 仅时间戳差异,无新增 Maven 坐标。复用 JDK 8 `UUID.randomUUID()` + `CompletableFuture<Decision>` + `ConcurrentHashMap` + `Consumer<Decision>` + `AtomicBoolean` + `String` + `List` + Spring `@Component` + Lombok `@Value` 全 JDK 8 standard + 已锁 13 项依赖表内。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"`(预估 10min)
- [ ] **T-pr-5** 等 CI 全绿 + reviewer approval 后 merge(走 squash merge 保持 main commit 历史 clean)(预估 10min)

> **P7 总耗时**:~54 min

---

## 总耗时估算

| Phase | 时长 |
|---|---:|
| P1 实现 | ~90 min(~1.5h) |
| P2 JavaDoc | ~50 min(~0.85h) |
| P3 测试 | ~375 min(~6.25h) |
| P4 AC 验证 | ~100 min(~1.7h) |
| P5 依赖构建 | ~50 min(~0.85h) |
| P6 文档同步 | ~160 min(~2.7h) |
| P7 PR 提交 | ~54 min(~0.9h) |
| **合计** | **~879 min(~14.6h)** |

> **Story #041 体量**比 Story #030(`~1184 min`)+ #031(`~904 min`)略轻:核心改动 2 modify 比 #030 5 modify 少;测试 11 case 比 #031 26 case 少;主要时间在测试编写(P3 占 ~6.25h,因为 AC-041-09/10/11 端到端 mock 复杂度高)

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-10-03
**Tasks version**: v0.1 Draft
