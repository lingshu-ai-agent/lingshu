# Story #030 `permission-policy-ask-user` — Spec

> **Status**: Draft 2026-10-02
> **Source**: dsh v1.5.51 §4.7 PermissionPolicy + §4.4 AgentEvent.ApprovalRequired + §9.3 Tool+Approval 时序图 + §15.4 ErrorCode 域 P 段(`LINGS-P02` 新增,`LINGS-P01` 复用) + **CLAUDE.md §13 v1.3.42 标注**「Story #030 permission-policy-ask-user 涉及 Spring WebSocket 长连接 UI 弹窗,依赖 Story #025 demo-product HTTP SSE chat 模式扩展,**高优先级**但 Story 边界需独立」
> **前置依赖**:`#001` + `#003` + `#004` + `#005` + `#019` + `#028` + `#029` permission-policy-impl(2026-09-30 已合,`LINGS-P01` 启用)+ **`#031` permission-policy-pattern-matching**(2026-09-30 已合,`PermissionPatterns` + `Tool.sourceCategory()` + 5 override + 0 binary delta 第 16 次)
> **同 Story 拆解**:无。本 Story 是 Story #029 + #031 的 `AskUser` 决策路径落地。`Decision.AskUser` 已存在但 3 个 stub(`DefaultToolExecutionContext.approval()` / `DefaultToolExecutor` L112-115 / `LinearTurnEngine.dispatchWithPolicy` L437-445)都是 safe-failure Deny / ErrorToolResult,AskUser 路径**永远不触发**。本 Story 真接通 AskUser outcome path。

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.51 §4.7 PermissionPolicy + §4.4 AgentEvent.ApprovalRequired + §9.3 Tool+Approval 时序图 + §15.4 ErrorCode 域 P 段
- **实测发现**(2026-10-02 用户问「这是不是又是一个实现缺失」):
  - `DefaultToolExecutionContext.approval()` 返回匿名 inner class,`ask()` 永远 `new Decision.Deny("AskUser approval flow is wired in Story #005 follow-up")`
  - `DefaultToolExecutor.dispatchInternal()` L112-115 `decision instanceof Decision.AskUser` 抛 `PermissionDeniedException("AskUser approval flow is wired in Story #005 follow-up")`
  - `LinearTurnEngine.dispatchWithPolicy()` L437-445 `d instanceof Decision.AskUser` 返回 ERROR `ToolResult("AskUser approval flow is wired in Story #005 follow-up")`
  - 3 处注释都引用「Story #005」(历史未跟进,Story #005 是 cancellation token,本应是 **#030 AskUser**)
  - **`AgentEvent.ApprovalRequired` 已存在**(§4.4 L371-376,`@RequiredArgsConstructor @Getter` 持 `Decision.AskUser ask + Consumer<Decision> continuation`);`AgentEventMapper.toJson()` L72-75 已经在 demo-product 把 ApprovalRequired 映射为 SSE `{type:"approval", ask:{...}}` —— **但 ChatController 没有 `POST /api/approvals/{sessionId}/{approvalId}` round-trip 端点,continuation 永远不会被 invoke,turn 永久 hang**
- **业务后果**(当前状态):
  - `permission-policy: strict` + `deny-list`/`allow-list` 真接通(#029 + #031 已落),但**没有 AskUser 路径**
  - 用户无法配「`bash_safe` 调 `rm -rf` 前需用户确认」类高敏感策略 —— 只能全 Allow 或全 Deny 二选一
  - `StrictPermissionPolicy.check()` 永远不返 `Decision.AskUser`(实际代码路径已分析,只 Allow / Deny 二态);`Decision.AskUser` 类是 dead code(grep 生产代码 `return.*AskUser` 0 hit)
  - demo-product ApprovalRequired SSE 事件发出去后**无 consumer** 调 accept(decision),turn 永久 hang,直到 cancellation token 触发(故事 #005 已有 `cancel()` 兜底)
- **对应风险**:**R-04**(privilege escalation — 分值 8)+ **R-13** mitigation (d)(R-13 第 17 次 PASS 强制)
- **涉及 ErrorCode**:**+1 新 ErrorCode**(`LINGS-P02 PERMISSION_APPROVAL_TIMEOUT`,P 域 2 号);复用 `#029` `LINGS-P01`(`"Tool 'xxx' requires user approval"`);**LINGS-P02 仅在 `approval-timeout > 0` 且达到 N 秒时触发**,`approval-timeout: 0`(默认)永不触发

---

## 1. WHY(为什么做这个 Story)

**核心问题**:Story #029 + #031 已落地 strict-mode PermissionPolicy(`Allow` / `Deny` 二态),但 `Decision.AskUser` 3rd outcome 是 dead code —— `DefaultToolExecutionContext.approval()` / `DefaultToolExecutor` L112-115 / `LinearTurnEngine.dispatchWithPolicy` L437-445 3 处 stub 把 AskUser 路径 safe-fail 成 Deny/ErrorToolResult;同时 `AgentEvent.ApprovalRequired` 已暴露给 demo-product SSE front-end,但 `ChatController` 无 round-trip 端点处理用户**回前端的** `{prompt: "rm -rf -rf /tmp/build", options: [...], user_choice: "deny"}` 请求。决策树最底层的「人类决策」,只能由用户主动回前端才能落地 —— 而当前**永远等不到**。

具体 5 个 gap:

1. **3 个 AskUser stub 仍在** —— `grep "new Decision.AskUser" lingshu-core/src/main 2>/dev/null` 0 hit(生产代码)但 grep "AskUser approval flow is wired in Story #005 follow-up" 3 hit(stub)
2. **`AgentEvent.ApprovalRequired` 已落但 continuation 永远 hang** —— `Decision.AskUser` 子类 + `ApprovalRequired` 事件 + `AgentEventMapper.toJson` SSE 映射全做完了,**缺消费者端 invoke continuation 的回路**
3. **`Decision.AskUser` 3rd outcome 真接通不可能** —— `StrictPermissionPolicy.check()` 只返 Allow / Deny,**无法返 AskUser**(story #029 + #031 都没改 check 路径);用户无法表达「这个 Tool 调之前必须人工审批」
4. **`ApprovalGate.ask()` 阻塞语义不明** —— 当前 stub 是同步立即 Deny 返回;真阻塞需要 subscriber 解析 `ApprovalRequired` 事件 + invoke `continuation.accept(decision)` 才能 unblock engine
5. **审批超时策略不合理** —— 当前 spec 默认 60s 超时,但企业场景中,审批人可能隔夜(下一个工作日)再决定,Claude Code 实际行为是「无超时」/「等用户回来再决定」;LingShu 需要支持 `approval-timeout: 0` = **无超时**(默认,匹配 Claude Code 隔夜审批)+ `>0` = N 秒(`LINGS-P02` 触发阈值),由用户显式选择
6. **R-04 privilege escalation 风险未缓解** —— 当前若配置 `allow-list: ["*"]`(#031 一行解),Agent 调任何 Tool(包括 `bash_safe`/`rm -rf`)都 Allow,无外提权限的人工核对门 —— 大企业部署必备能力

**业务后果**:
- 用户**实际**无法做「危险 Tool 人工审批」策略,只能全 Allow(`*`)或全 Deny(空 `allow-list`)二选一
- demo-product SSE ApprovalRequired 事件发出去后**永久 hang**,turn 永远不结束(只能靠 cancellation token 或 OOM)
- dsh §9.3 时序图描绘的「POL → AskUser → User 答复 → 继续 dispatch」流程**永远不会发生**
- §14.3 (N3) circuit breaker / §14.10 (N10) audit-log 设计假设 `Decision.AskUser` 真接通;不接通,这两条 NFR 路径断链

**Story #066 业务价值**:
- **3 stub 替换** —— `DefaultToolExecutionContext.approval()` 真返回 `ApprovalGate` 阻塞实现,阻塞直到 subscriber 通过 `Consumer<Decision>` invoke;`DefaultToolExecutor` L112-115 删 stub(`LinearTurnEngine.dispatchWithPolicy` 处理 AskUser,DefaultToolExecutor 只管 Deny);`LinearTurnEngine.dispatchWithPolicy` L437-445 AskUser 分支真接通 `ApprovalGate.ask(decision)`,把 AskUser 决策一并入 dispatch 结果
- **新增 `AskUserPermissionPolicy`** —— `permission-policy: ask` 第 3 种类型(已有 `default` / `strict`),总返 `Decision.AskUser` 对配置的 Tool 集(yml `tools.ask-list: [bash_safe, write_file, web_fetch]`)
- **新增 yml 字段** —— `AgentConfig.approvalTimeoutSeconds`(默认 **0 = 无超时** —— 阻塞直到用户响应/cancellation/OOM;`>0` = N 秒超时 → Deny + `LINGS-P02`;企业场景中审批人可能隔夜(下一个工作日)再决定,Claude Code 实际行为是「等用户回来再决定」,**默认 0 匹配 Claude Code 隔夜审批**;用户显式配 N 才走超时路径)
- **`AgentEvent.ApprovalRequired` 真接通** —— engine 调 `ctx.approval().ask(ask)` 阻塞;返回 `Decision` Allow / Deny / AskUser(subscriber 可追问)
- **demo-product SSE round-trip** —— `ChatController` 新增 `POST /api/approvals/{sessionId}/{approvalId}` 端点,body `{decision: "allow"|"deny", reason: "..."}`,端点调 `ApprovalRegistry.resolve(sessionId, approvalId, decision)`,unblock engine
- **`ApprovalRegistry`** —— per-session per-approval-id 持有 `Consumer<Decision> continuation`;`CompletionStage<Decision>` 桥接 ChatController 端点 ↔ LinearTurnEngine.runTurn 阻塞调用
- **+1 新 ErrorCode** `LINGS-P02 PERMISSION_APPROVAL_TIMEOUT`(P 域 2 号,自 #029 后首次启用 P 域段位);reason `"[LINGS-P02] Approval timeout after <N>s — default Deny for safety"`(**仅在 `approval-timeout > 0` 时触发**;`approval-timeout: 0` 永不触发 LINGS-P02)

**关键不变项**:
- `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)—— **0 改动**
- `Decision.AskUser` 类 — **0 改动**(沿用 §4.3 L350-355 既有契约)
- `AgentEvent.ApprovalRequired` 契约 — **0 改动**(沿用 §4.4 L371-376 既有契约)
- `AgentEventMapper.toJson()` ApprovalRequired → SSE `{type:"approval"}` 映射 — **0 改动**
- `StrictPermissionPolicy`(#029 + #031 已落)+ `PermissionPatterns`(#031 已落)+ `Tool.sourceCategory()`(#031 已落)—— **0 改动**
- `PermissionPolicy.check(ToolCall, ToolExecutionContext) → Decision` 公开方法签名 — **0 改动**
- `ToolsConfig.allowList` / `denyList`(#031 pattern matching 已落)—— **0 改动**
- `PermissionPolicyRouter` / `PermissionPolicyAutoConfiguration` / `PermissionErrorCodes.LINGS_P01` / `LINGS-P02`(本 Story 新增)
- `AgentConfig` 不可变契约(`@Value` + `@Builder` 27 → **28** 字段 final)—— **+1 字段新增** `approvalTimeoutSeconds`(顶层;默认 0 = 无超时,匹配 Claude Code 隔夜审批 UX)
- `ToolsConfig` 5 字段 → **6 字段**(新 `askList: List<String>`,`defaultList` 空 list,back-compat)
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**(只改 `dispatchWithPolicy()` AskUser 分支约 30 行)
- 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 仍是 SlotResolver 6 Router 之一)
- JDK 8 兼容(`CompletableFuture<Decision>` + `Consumer<Decision>` + `Map<String, Consumer<Decision>>` + `ConcurrentHashMap` + `String` + `List` JDK 8 standard,**不**引 reactive-streams 库外的新二进制)
- **0 新 Maven 依赖**(`CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `Map` JDK 8 standard + Spring `@Component` + Lombok `@Value` 已锁 13 项依赖表内)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 给 Agent 配 `permission-policy: ask` + `tools.ask-list: [bash_safe, write_file]` —— 调高敏感 Tool 前 SSE 端弹窗,用户**回前端**点 Allow/Deny;调任何 production Tool 后立即 dispatch 或拒决(无需重启) |
| **多租户平台搭建者** | Tenant Alice 配 `permission-policy: ask` + `tools.ask-list: ["delegate:Task"]` —— 子 Agent 派发前必经审批,tenant 级别人工 gate |
| **运维稳定性关注者(Eve 类)** | `approval-timeout: 0`(默认,无超时,匹配 Claude Code 隔夜审批)或显式 `approval-timeout: 3600s`(1 小时,白天办公时段)+ `LINGS-P02` 触发后 → 默认 Deny,**不挂 turn**;与 §4.7 安全默认(deny)对齐 |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 3 stub 替换(`DefaultToolExecutionContext.approval()` 真阻塞 / `DefaultToolExecutor` 删 stub / `LinearTurnEngine.dispatchWithPolicy` AskUser 分支真接通) + L1 测试覆盖 `AskUserPermissionPolicy.check()` 4 case + L1 测试覆盖 timeout 路径(`LINGS-P02` 触发) + L2 端到端 `ApprovalRequired` event → continuation invoke → ToolResult |
| **框架贡献者 / plugin 作者(Bob 类)** | 自定义 `ApprovalGate` 替换默认 StdinApprovalGate(CLI 路径)或 SseApprovalGate(demo-product 路径)—— `@Autowired ApprovalGate myCustomApproval` 即可 |
| **安全审计员(Diana 类)** | 配置 `permission-policy: ask` + `tools.ask-list: [bash_safe]` + yml `audit-log: enabled` —— 所有 ApprovalRequired 事件 + 用户决策写入审计 log(§14.10 N10) |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**:

1. **`DefaultToolExecutionContext.approval()` 真实现**(modify):
   ```java
   @Override
   public ApprovalGate approval() {
       return new AsyncApprovalGate(turnCtx, cancellation(), approvalTimeoutSeconds);
   }

   /**
    * Story #030 — ApprovalGate blocks until the subscriber invokes the
    * continuation Consumer<Decision> stored on AgentEvent.ApprovalRequired.
    *
    * <p>Blocks for at most {@code approvalTimeoutSeconds}:
    * <ul>
    *   <li>{@code 0} = wait indefinitely until subscriber invokes continuation
    *       or cancellation token fires (matches Claude Code overnight behavior)</li>
    *   <li>{@code N>0} = wait at most N seconds; on timeout returns
    *       {@code Decision.Deny("[LINGS-P02] Approval timeout after <N>s")}</li>
    * </ul>
    * On cancellation token fired, returns Deny immediately (no hang).
    */
   private static final class AsyncApprovalGate implements ApprovalGate {
       // Holds a CompletableFuture<Decision> per-call; engine awaits via get(timeout).
       // Subscriber side: ApprovalRegistry.resolve(sessionId, approvalId, decision) calls
       // CompletableFuture.complete(decision).
   }
   ```

2. **`DefaultToolExecutor.dispatchInternal()` 删 stub**(modify,L112-115):
   - 删除 `if (decision instanceof Decision.AskUser)` 段(LinearTurnEngine.dispatchWithPolicy 先 handle AskUser,**DefaultToolExecutor 永远不收 AskUser**)
   - 公开方法签名不变 `ToolResult dispatch(ToolCall, ToolExecutionContext)`
   - 异常路径不变(只在 Deny / Allow 时抛 / 不抛)

3. **`LinearTurnEngine.dispatchWithPolicy()` AskUser 真接通**(modify,L437-445):
   ```java
   if (d instanceof Decision.AskUser) {
       // Story #030 — emit ApprovalRequired event and invoke ApprovalGate.ask() blockingly
       CompletableFuture<Decision> fut = new CompletableFuture<>();
       String approvalId = UUID.randomUUID().toString();
       sink.onNext(new AgentEvent.ApprovalRequired(
           (Decision.AskUser) d,
           decision -> fut.complete(decision)));
       Decision resolved;
       try {
           resolved = toolCtx.approval().ask((Decision.AskUser) d);
       } catch (RuntimeException ex) {
           // ApprovalGate.ask threw (e.g. timeout via LINGS-P02) — wrap as ToolResult.error
           return ToolResult.error(ex.getMessage());
       }
       // Recursively dispatch the resolved decision (Allow → execute; Deny → error; AskUser → re-ask or limit)
       if (resolved instanceof Decision.Allow) {
           return toolExecutor.dispatch(call, toolCtx);
       } else if (resolved instanceof Decision.Deny) {
           return ToolResult.builder()
               .status(ToolResult.Status.ERROR)
               .toolUseId(call.getId())
               .content(((Decision.Deny) resolved).getReason())
               .isError(true)
               .build();
       } else {
           // AskUser → AskUser recursion — apply max-iterations guard
           return ToolResult.error("AskUser recursion limited (max 3 retries)");
       }
   }
   ```

4. **新增 `AskUserPermissionPolicy`**(new)```
   `permission-policy: ask` 第 3 种 Provider,`@Component` 注册到 `PermissionPolicyRouter`:
   ```java
   public class AskUserPermissionPolicy implements PermissionPolicy {
       private final AgentConfig.ToolsConfig tools;
       private final Map<String, String> nameToCategory;
       private final long approvalTimeoutSeconds;

       @Override
       public Decision check(ToolCall call, ToolExecutionContext ctx) {
           String toolName = call.getName();
           String toolCategory = nameToCategory.getOrDefault(toolName, "local");
           // Pattern matching (复用 PermissionPatterns)
           for (String pattern : tools.getAskList()) {
               if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
                   return new Decision.AskUser(
                       "Tool '" + toolName + "' requires user approval",
                       Arrays.asList(
                           new Decision.Option("allow", "Approve this call"),
                           new Decision.Option("deny", "Reject this call")));
               }
           }
           return new Decision.Allow("ask policy: not in ask-list, default allow");
       }
   }
   ```

5. **新增 `AskUserPermissionPolicyProvider`**(new)```
   `name()="ask"` + `priority()=10` + `version()="1.0.0"` + `create(AgentConfig, ToolRegistry)`:
   ```java
   @Component
   public class AskUserPermissionPolicyProvider implements PermissionPolicyProvider {
       private final ToolRegistry toolRegistry;
       public PermissionPolicy create(AgentConfig cfg) {
           Map<String, String> nameToCategory = new HashMap<>();
           for (Tool t : toolRegistry.findAll()) {
               nameToCategory.put(t.name(), t.sourceCategory());
           }
           return new AskUserPermissionPolicy(cfg.getTools(), nameToCategory, cfg.getApprovalTimeoutSeconds());
       }
   }
   ```

6. **`PermissionErrorCodes.LINGS_P02` 新增**(modify):
   ```java
   public final class PermissionErrorCodes {
       public static final String LINGS_P01 = "LINGS-P01"; // Story #029
       public static final String LINGS_P02 = "LINGS-P02"; // 🆕 Story #030 — PERMISSION_APPROVAL_TIMEOUT
   }
   ```

7. **`ToolsConfig` 加 `askList` 字段**(modify):
   ```java
   @Value @Builder
   public static class ToolsConfig {
       boolean enabled;
       List<String> allowList;   // story #029
       List<String> denyList;    // story #029
       @Builder.Default List<String> askList = new ArrayList<>();   // 🆕 story #030
       long maxReadBytes;
       long maxWriteBytes;
   }
   ```

8. **`AgentConfig` 加 `approvalTimeoutSeconds` 字段**(modify,扩 1 字段):
   ```java
   @Value @Builder
   public class AgentConfig {
       // ... 27 字段 existing
       @Builder.Default long approvalTimeoutSeconds = 0;    // 🆕 story #030
       // 0 = wait indefinitely (default; matches Claude Code overnight approval)
       // >0 = wait at most N seconds; on timeout returns Decision.Deny("[LINGS-P02] ...")
       // ... 剩余字段
   }
   ```

9. **demo-product SSE round-trip**:
   - **新增 `ApprovalRegistry`**(`@Component` in demo-product)```
     `Map<String /* approvalKey = sessionId + ":" + approvalId */, CompletableFuture<Decision>> pending`:
     ```java
     @Component
     public class ApprovalRegistry {
         private final ConcurrentHashMap<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();
         public String register(String sessionId, String approvalId, CompletableFuture<Decision> fut) {
             pending.put(sessionId + ":" + approvalId, fut);
             return approvalId;
         }
         public void resolve(String sessionId, String approvalId, Decision decision) {
             CompletableFuture<Decision> fut = pending.remove(sessionId + ":" + approvalId);
             if (fut != null) fut.complete(decision);
         }
     }
     ```
   - **`ChatController` 新增 `POST /api/approvals/{sessionId}/{approvalId}` 端点**(modify):
     ```java
     @PostMapping("/api/approvals/{sessionId}/{approvalId}")
     public Map<String, String> resolveApproval(@PathVariable String sessionId,
                                                @PathVariable String approvalId,
                                                @RequestBody Map<String, String> body) {
         String decisionStr = body.get("decision");
         String reason = body.get("reason");
         if (!"allow".equals(decisionStr) && !"deny".equals(decisionStr)) {
             throw new BadRequestException("decision must be 'allow' or 'deny'");
         }
         Decision d = "allow".equals(decisionStr)
             ? new Decision.Allow(reason != null ? reason : "user approved")
             : new Decision.Deny(reason != null ? reason : "user denied");
         approvalRegistry.resolve(sessionId, approvalId, d);
         return Map.of("status", "resolved");
     }
     ```
   - **`LinearTurnEngine` `sink.onNext` 透传 `approvalId`** —— 实装 `AgentEvent.ApprovalRequired` 派发时,从 sessionId+step 派生 `approvalId = sessionId + "-" + step + "-" + UUID.randomUUID().toString().substring(0,8)`,放入 event payload(扩 1 final String field)
     - **注:AgentEvent.ApprovalRequired 契约改动?** —— 现有 `@RequiredArgsConstructor` 是 `Decision.AskUser ask + Consumer<Decision> continuation` 2 fields;扩 `approvalId` 字段需要 +1 constructor param,back-compat(2-arg ctor 仍可用? **no** —— `@RequiredArgsConstructor` 强制 all args,扩 1 field → 必须改所有 3 个 ctor sites;Story 边界 stretch 但可接受)

10. **demo yml 演示 AskUser 路径**(modify):
    ```yaml
    permission-policy: ask
    tools:
      ask-list:
        - write_file   # 写文件前需用户批准
        - bash_safe    # shell 命令执行前需用户批准
      # approval-timeout: 0 (default — wait indefinitely; matches Claude Code
      # overnight approval behavior — user can come back next morning and
      # approve via POST /api/approvals/{sessionId}/{approvalId})
      # approval-timeout: 60  # explicit N-second timeout → LINGS-P02 on overrun
    ```

**新配置参数**:
- `AgentConfig.approvalTimeoutSeconds`(顶层,**默认 0 = 无超时**,匹配 Claude Code 隔夜审批;`>0` = N 秒,触发 `LINGS-P02`)
- `ToolsConfig.askList`(子层,List<String>,默认空 → ask policy 等价默认 allow)

**`approval-timeout` 语义对照表**:

| yml 值 | Java 值 | 行为 | 触发 LINGS-P02? |
|---|---|---|---|
| `0`(默认) | `0L` | 阻塞直到 subscriber 调 `continuation.accept(decision)` 或 cancellation token fired | **否** |
| `60` | `60L` | 阻塞最多 60 秒;超时返 `Decision.Deny("[LINGS-P02] ...")`,engine 正常结束 | 是 |
| `3600` | `3600L` | 阻塞最多 1 小时(适合白天办公时段审批)| 是 |
| `<未配置>` | `0L`(走 `@Builder.Default`) | 同 `0` | 否 |

> **企业 rationale**:Claude Code 实际行为是「用户回来再决定」,LingShu 默认 0 匹配;若用户**显式**配 `60` 或 `3600`,则按 N 秒 timeout 走(LINGS-P02 触发)。决策完全交给用户配置。

**用户会看到的错误码**:

| ErrorCode | 触发条件 | reason 字符串样例 |
|---|---|---|
| **`LINGS-P02`** | Approval 用户回复超时(`approvalTimeoutSeconds > 0` 且达到 N 秒) | `"[LINGS-P02] Approval timeout after 60s — default Deny for safety"` |
| **`LINGS-P01`(复用)** | `permission-policy: ask` + `tools.ask-list: [write_file]` + 调 `write_file`(理论无,LINGS-P01 是 strict deny reason;ask policy 永远不返 Deny 直接 `LINGS-P01`,而是 AskUser)| 复用 #029 |

**关键不变量**(不变项):
- `PermissionPolicy` interface + `Decision` 4 子类 — **0 改动**
- `Decision.AskUser` 类 — **0 改动**(沿用 §4.3 L350-355)
- `AgentEvent.ApprovalRequired` 类 — **扩 1 `approvalId` field**(`@RequiredArgsConstructor` 自动 all-args,back-compat 由构造器 signature 守住)
- `AgentEventMapper.toJson()` ApprovalRequired → SSE 映射 — **0 改动**(扩 1 字段后 SSE payload 加 `approvalId` 字段;前端 back-compat)
- `StrictPermissionPolicy` + `PermissionPatterns` + `Tool.sourceCategory()`(#031) — 0 改动
- `LinearTurnEngine` ReAct 主循环结构 — **0 改动**(只改 `dispatchWithPolicy()` AskUser 分支约 30 行)
- `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — 全部 0 改动
- 9 Slot 顶层体系不变
- `AgentConfig` 不可变契约(28 字段 final)— **0 字段新增 / 0 字段移除**(只扩 1 字段 `approvalTimeoutSeconds`)
- `ToolsConfig` 字段集(5 → 6,扩 `askList` 默认空)
- `approvalTimeoutSeconds` 默认 = 0(无超时);`>0` = N 秒(LINGS-P02 触发阈值)
- `banned-dependencies` enforcer 不 fail(R-13 mitigation (d) 第 17 次 PASS 强制)
- JDK 8 兼容(`CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `Map` + `UUID.randomUUID()` 全 JDK 8 standard)
- **0 新 Maven 依赖**

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — `DefaultToolExecutionContext.approval()` 真阻塞

**Given** `AgentConfig` mock + `TurnContext` mock + `CancellationToken` mock
**When** 测以下 4 case:
1. `approvalTimeoutSeconds = 1`(1 秒,加速测试)+ 无 subscriber invoke → 阻塞 1 秒 → 返 `Decision.Deny("[LINGS-P02] Approval timeout after 1s — default Deny for safety")`
2. `approvalTimeoutSeconds = 60`(任意 N>0,本例 60s)+ subscriber 通过 `continuation` 字段 invoke `accept(Decision.Deny("user denied"))` → `approval().ask(ask)` 返 `Decision.Deny("user denied")`(timeout 内响应)
3. `approvalTimeoutSeconds = 60` + cancellation token 触发 → 立即返 `Decision.Deny("[LINGS-P01] Cancelled during approval")`,**不**等 timeout
4. `approvalTimeoutSeconds = 0`(无超时,默认)+ subscriber 5 秒后 invoke `accept(Decision.Allow("user approved"))` → `approval().ask(ask)` 阻塞 5 秒后返 `Decision.Allow("user approved")`(timeout 不触发)

**Then** 4 case 全过(实测 `CompletableFuture.get(timeout, TimeUnit)` 监听到 invoke / timeout / cancel / indefinite 4 路径)
**断言方式**:L1 Unit `DefaultToolExecutionContextApprovalTest` 4 case

### AC-NN-2 — `DefaultToolExecutor.dispatchInternal()` 删 stub

**Given** `PermissionPolicy` mock 返 `Decision.AskUser`(`tools.ask-list` 命中模式)
**When** `DefaultToolExecutor.dispatch(call, ctx)`
**Then** **不**抛 `PermissionDeniedException("AskUser approval flow is wired in Story #005 follow-up")` —— 删 L112-115 stub 后不再处理 AskUser(`LinearTurnEngine.dispatchWithPolicy` 已 handle)
**断言方式**:L1 Unit `DefaultToolExecutorAskUserTest` 1 case(确保决策已废弃)

### AC-NN-3 — `LinearTurnEngine.dispatchWithPolicy()` AskUser 真接通

**Given** `PermissionPolicy` 返 `Decision.AskUser` + `ApprovalGate` mock `ask()` 立即返 `Decision.Allow("user approved")` + `AgentEvent.ApprovalRequired` sink listener
**When** `LinearTurnEngine.runTurn()` 跑 1 turn(`maxSteps=1`)→ LLM stream 返 `ToolCall(bash_safe)` → engine 调 `dispatchParallel` → `dispatchWithPolicy` 走 AskUser 分支
**Then** 顺序事件流:sink.onNext(`ApprovalRequired(ask, consumer)`);inside=sink.onNext(`ToolStarted`);inside=`toolExecutor.dispatch()`;sink.onNext(`ToolCompleted(result)`);sink.onNext(`TurnCompleted(END_TURN)`)
**Critical**:() → Allow 返回 `Decision.Deny("user denied")` → sink.onNext(`ToolCompleted` status=ERROR)
**断言方式**:L2 Slice `LinearTurnEngineAskUserIT` 2 case(Allow / Deny)

### AC-NN-4 — `AskUserPermissionPolicy.check()` pattern matching + fallback

**Given** `ToolsConfig.askList = [bash_safe, write_file, mcp:*, skill:*]` + `nameToCategory = {bash_safe → local, write_file → local, echo → mcp, agent → skill}`
**When** 测 4 case:
1. `policy.check(ToolCall("bash_safe"))` → `Decision.AskUser`(matches 字面)
3. `policy.check(ToolCall("echo"))` → `Decision.AskUser`(matches `mcp:*`)
4. `policy.check(ToolCall("read_file"))` → `Decision.Allow`(不在 ask-list)

**Then** 4 case 全过
**断言方式**:L1 Unit `AskUserPermissionPolicyTest` 4 case(extends Letter #000 `PermissionPolicyTest` 模式)

### AC-NN-5 — `LINGS-P02` PERMISSION_APPROVAL_TIMEOUT 触发(双 case:触发 / 不触发)

**Given** `AgentConfig.approvalTimeoutSeconds = 1`(1 秒超时,测试加速)+ `PermissionPolicy` 返 `AskUser` + `ApprovalGate` 不响应(无 subscriber invoke)
**When** `LinearTurnEngine.runTurn()` 跑 → `dispatchWithPolicy` AskUser 分支 → `toolCtx.approval().ask(ask)` 阻塞 1s → timeout
**Then** 2 case 全过:
1. **`approvalTimeoutSeconds = 1`**:ToolResult content 含 `"[LINGS-P02] Approval timeout after 1s"`;engine 不抛 `LINGS-P01`(PermissionDeniedException 路径不触发)
2. **`approvalTimeoutSeconds = 0`(默认 无超时)**:ToolResult content **不**含 `[LINGS-P02]`;即使 5 秒后无响应,engine 仍阻塞(测试用 5 秒短超时 + `Thread.sleep` 加速,实际生产场景就是「用户隔夜再决定」)

**断言**:L1 Unit `ApprovalTimeoutLINGS02Test` 2 case(检查 `timeUnit` 加速 + 默认 0 不触发)

### AC-NN-6 — demo-product SSE round-trip `POST /api/approvals/{sessionId}/{approvalId}`

**Given** 启动 demo-product Spring Boot + `permission-policy: ask` + `tools.ask-list: [write_file]` + 用户调 `POST /api/chat/{sessionId}` SSE → 触发 `ApprovalRequired` event
**When** 前端 POST `POST /api/approvals/{sessionId}/{approvalId}` body `{decision: "deny", reason: "too dangerous"}` → `ApprovalRegistry.resolve()` 调 `continuation.accept(Decision.Deny("too dangerous"))`
**Then** 原 SSE 流后续 emit `ToolCompleted` status=ERROR content 含 `"too dangerous"`;turn 正常结束(不 hang);`ToolCall` 名称辨识准确(`call_<id>` 对应 `write_file`)
**断言**:L3 黑盒 `DemoProductAskUserRoundTripIT` 1 case(端到端 Spring Boot 启动 + MockMvc 双端点)

### AC-NN-7 — `AgentEvent.ApprovalRequired` 扩 `approvalId` 字段

**Given** `LinearTurnEngine.runTurn()` AskUser 分支 emit `ApprovalRequired`
**When** 测试 listener 收到 event
**Then** `event.getApprovalId()` 非 null 且 unique(同一 turn 多 AskUser 各不同);format `"<sessionId>-<step>-<uuid8>"`
**断言**:L1 Unit `ApprovalRequiredEventTest` 1 case(同一 turn 3 次 AskUser → 3 个 approvalId unique)

### AC-NN-8 — `permission-policy: ask` yml 端到端贯通

**Given** `application.yml` `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]` + 调 `write_file`(`ProductTools.writeFile` 真接通)
**When** Spring Boot 启动 + chat `POST /api/chat/{sessionId}` 触发
**Then** 启动日志显示 `resolved PermissionPolicy: ask (priority=10)`;SSE 流 emit `ApprovalRequired` event;`approvalId` 出现在 payload;用户 POST `POST /api/approvals/{...}` 决策后,SSE 中后续 `ToolCompleted` 反映决策(Allow → success / Deny → error)
**断言**:L3 黑盒 `DemoProductAskUserYmlIT` 1 case(端到端 yml binding)

### AC-NN-9 — R-13 mitigation (d) 依赖零增量

**Given** Story #030 引入 `AskUserPermissionPolicy` + `AskUserPermissionPolicyProvider` + `ApprovalRegistry` + `ChatController` 端点 + `DefaultToolExecutionContext.approval()` 阻塞实现 + `LinearTurnEngine.dispatchWithPolicy()` AskUser 分支 + `LINGS-P02` ErrorCode + `ToolsConfig.askList` 字段
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#031` post-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;`banned-dependencies` enforcer 不 fail
**断言方式**:对照 `specs/031-permission-policy-pattern-matching/` PR body 末尾的 `### R-13 dependency:tree 自查` 节
**预期 R-13 第 17 次 PASS 0 binary delta**

### AC-NN-10 — 全 660+ 测试 0 回归

**Given** Story #030 改 5 个 modify(主安全相关,触及 DefaultToolExecutionContext / DefaultToolExecutor / LinearTurnEngine / ToolsConfig / AgentConfig + 3 个 new Policy/Provider/Registry)+ demo ChatController 端点扩
**When** 跑 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test`
**Then** Story #029 现有 18 case + Story #031 现有 26 case + Story #030 新 case + 660 pre test 全过(0 fail / 0 error / 0 skipped);Story #029 back-compat 测试(`StrictPermissionPolicyTest` + `StrictPermissionPolicyPatternTest` + `StrictPermissionPolicyReasonTest`)全过;`AllowAllPermissionPolicy` + `StrictPermissionPolicy` 行为不变
**断言方式**:`mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 全模块无 fail

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| 完整 WebSocket 长连接(替代 SSE round-trip) | Story 边界已拖,SSE 已有 round-trip 模式(`approval` event + `POST /api/approvals`),WebSocket 留 OQ-Future §14.10 后续 Story |
| 多 user 协作审批(多 reviewer 同时响应,任一 Allow 即放行)| KISS,本期 1 user 1 decision;多 reviewer 留 OQ-Future |
| Approval decision 持久化(session 重启后恢复 pending approval)| KISS,SessionRegistry 当前 eviction 即丢 pending;持久化留 §14.7 N7 SessionStore 多后端 后续 Story |
| AskUser 递归(用户 Deny 后追问 「为什么?」)| AskUser → AskUser 路径加 max-iterations guard(3 次)直接 error,无递归展开;留 OQ-Future |
| `Decision.AskUser` 字段扩(`timeoutSeconds` / `severity`)| KISS,只用 prompt + options 2 field(沿用 §4.3 L350-355 既有契约) |
| `PermissionPolicy.check()` 返 AskUser 时携带 `approvalId` | approvalId 由 engine 派发时生成(per-call unique),不入 Policy.check() 契约 |
| `AgentEvent.ApprovalRequired` 扩 `approverRoles` / `severity` field | KISS,approvalId 1 字段足够 round-trip 路由 |
| `permission-policy: ask` yml 改 default ask policy(让所有 Tool 都 AskUser) | yml `ask-list` 空等价 default Allow;全 ask 是 user 主动配(配 `ask-list: ["*"]` 等价) |
| `LINGS-P02` 启用除 timeout 之外的 trigger(用户 cancel / 系统 OOM)| KISS,timeout = 用户 cancel + LINGS-P01(复用);LINGS-P02 仅 timeout 路径 |
| `permission-policy: strict 强制对 ask 决策 Deny`(strict supersedes ask)` | yml `permission-policy: ask` 显式声明;混配是 user 责任;Policy router 按 `permission-policy` top-level key 选 Provider,无歧义 |
| `ApprovalRequired` event `continuation` 直接给 `Consumer<Decision>` 而非 `CompletionStage<Decision>` | engine → subscriber 异步路径 `Consumer<Decision>` 已够(§4.4 L371-376 既有契约);subscriber 内部用 `CompletableFuture<Decision>` 桥接 SSE → ChatController 端点 |
| `ApprovalRegistry` 用 reactive-streams `Sinks.Many` 而非 `ConcurrentHashMap<String, CompletableFuture<Decision>>` | KISS,reactive-streams 1.0.4 已锁但 `Sinks.Many` 是 JDK 9+ `Flow.Subscription` 等价物,本 Story 复用 `CompletableFuture` 异步语义 |
| 删 `StrictPermissionPolicyProvider` / `AllowAllPermissionPolicyProvider` | **保留**(back-compat + 多 Provider 模式)|
| 改 `LinearTurnEngine` ReAct 主循环结构 | §6.1 LinearTurnEngine L3611-3686 main loop 0 改动,只改 `dispatchWithPolicy()` AskUser 分支约 30 行 |

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#001` zero-config-bootstrap — `Agent` 4 final 字段 + `DefaultAgent.buildContext` 冻结语义
  - `#003` spi-slot-router — `SlotRouter<P,T>` 父类 + 多 Provider 模式 + `PermissionPolicyRouter`
  - `#004` tool-parallel-dispatch — `ToolExecutor.dispatch()` 5 步流水线 + `dispatchWithPolicy`
  - `#005` cancellation-token — `CancellationToken.fire()` + cooperative cancellation semantics(本 Story AskUser timeout 路径复用)
  - `#019` built-in-tools — `AgentConfig.ToolsConfig` 5 字段 + `ProductTools` 4 件套
  - `#025` demo-product — SSE chat round-trip 模式 + `SessionRegistry` evicts
  - `#028` sandbox-runtime-impl — `SandboxErrorCodes` precedent + `AccessDeniedException` 模式(LINGS-P01 precedent)
  - `#029` permission-policy-impl — `StrictPermissionPolicy` 真接通 + `LINGS-P01` + `ToolsConfig.allowList` / `denyList` + `AgentConfig.permissionPolicy` + `PermissionPolicyRouter` 多 Provider 模式 + demo yml 切 strict
  - **`#031` permission-policy-pattern-matching** — `PermissionPatterns` + `Tool.sourceCategory()` + 5 override + 0 binary delta 第 16 次(本 Story `ask-list` 复用 pattern matching)
- **后续 Story(本 Story 是其前置)**:
  - `#030b ask-user-websocket`(可选 follow-up — 完整 WebSocket UI 替代 SSE approval)留 OQ-Future
  - `#029 follow-up` permission-policy-cli-debug(CLI `--list-permissions` 启动 banner 列出 pattern 解析结果)—— 本 Story 让 pattern + ask 路径同时暴露
  - §14.10 N10 audit-log — `Decision.AskUser` event + `Decision.Allow` / `Decision.Deny` 用户决策写入审计 log
  - §14.3 N3 CircuitBreaker — ApprovalRequired 失败率高(用户频繁 deny)触发 circuit breaker 熔断
  - §14.9 TenantContext 多租户 — `approval-list` Tenant 隔离可叠加 pattern(per-tenant ask-list override)
  - OQ-Future 完整 AskUser 递归展开 / 多 reviewer 协作 / Approval decision 持久化 —— 视用户实际诉求触发

---

**Spec writer**: Claude Code
**Spec date**: 2026-10-02
**Spec version**: v0.1 Draft