# Story #034 `a2a-http-domain-guard` — A2A 沙箱守卫深度(复用 MCP #033 模式)

> **状态**:🟡 待审批 → 计划 2026-10-01+
> **来源**:dsh §5.6.3.1 + §6.5 (2.1) — Story #033 落地后用户追问 "A2A 的网络连接也和 MCP 一样用了 whitelist 做校验吗" 发现 gap
> **优先级**:P1(A2A 是 LLM 远程工具的另一条真实通道,与 MCP 并列)
> **前置依赖**:Story #001 + #003 + #009a/c/d/e + #028 + #033 全部合(MCP #033 已提供 `McpHttpSupport.checkOrThrow(url, whitelist)` helper + `AccessDeniedException[LINGS-S01]` 错误语义)

---

## 1. WHY(为什么做这个 Story)

**问题**:`McpHttpSupport.checkOrThrow(String url, List<String> whitelist)`(Story #033 落地)在 MCP HTTP transport 发请求**之前**做 sandbox domain-whitelist 守卫,**MCP 12 hook 点已全接入**。但 **A2A `HttpJsonRpcA2aTransport` 完全没接入**,这是 sandbox 防护的平行盲点:

1. **A2A 是 LLM 的"远程 agent 工具"**:`RemoteAgentTool`(`name="remote_agent"`)被 LLM 调 `call_remote_agent(agentName, skill, input)` → `A2aTransport.submit(...)` → `HttpJsonRpcA2aTransport.jsonRpcCall(...)` → 真发 HTTP POST 到 `httpBaseUrl/rpc`。攻击面与 MCP 等价 —— 任意外发 HTTP,正是 sandbox 该拦截的。
2. **Story #033 spec.md §1 第 11 行**显式声明:"**MCP 是 LLM 工具的真实运行通道**" —— 同样的描述适用于 A2A。
3. **`HttpJsonRpcA2aTransport.java:97-99` 自己 new `HttpClient`** + `http.send(req, ...)` 直接发,**0 sandbox hook**。`fetchCard()`(`HttpJsonRpcA2aTransport.java:116`)和 `jsonRpcCall()`(`HttpJsonRpcA2aTransport.java:285`)是仅有的 2 处真发 HTTP 点。

**根因**:`HttpJsonRpcA2aTransport` 在 Story #009c 实现时,`McpHttpSupport` 还没扩 `checkOrThrow` helper(那是 #033 才加),也没意识到 A2A 同样是 sandbox 防护对象。`#033` 当时只覆盖了 MCP 3 transport,没回头看 A2A。

**为什么不是 Path A(走 `WhitelistedHttpClient` 整体路径)**:
- A2A `HttpJsonRpcA2aTransport` 用 JDK 11+ 内置 `java.net.http.HttpClient`(故事 #009c 已锁,与 JDK 17+ 兼容)
- `WhitelistedHttpClient`(Story #028)走 `HttpURLConnection`,**强行替换会破坏 HTTP/2 + connection pooling + 流式响应**(subscribe polling 路径)
- MCP #033 已经验证 Path B(check-only hook)是更小风险的方案 —— A2A 直接复用同一策略

**决策(用户 2026-10-01 确认)**:**Path B + 复用 MCP helper** —— `HttpJsonRpcA2aTransport` 保留 JDK `HttpClient` 不变,在 `fetchCard()` 和 `jsonRpcCall()` 发请求**之前**调 `McpHttpSupport.checkOrThrow(url, whitelist)`;**不 rename** `McpHttpSupport` → `SandboxHttpSupport`(留 OQ-Future refactor);**复用 `LINGS-S01`**(`AccessDeniedException`,Story #028 落地);**per-remote-agent list 配置粒度**(mirror MCP per-MCP-server);**严格 mode**(default empty = deny all,user 必须显式配,与 MCP #033 完全对称)。

---

## 2. WHO(谁应该关心)

- **LingShu 用户**:写 yml 配置 remote A2A agents + sandbox whitelist,期望 `https://partner-agent.example.com` 不在 whitelist 时 A2A HTTP 也失败(语义与 MCP 一致)
- **框架贡献者**:扩展 A2A transport(后续 Story 可能加 `GrpcA2aTransport` 或 `InProcessA2aTransport`),统一调 `McpHttpSupport.checkOrThrow(url, whitelist)` 即可
- **安全审计员**:sandbox 防护报告覆盖 "agent 进程 + MCP 进程 + A2A 远程调用" 三层外发 HTTP

---

## 3. WHAT(做什么)

### 3.1 文件改动(4 modify + 2 new test files)

| # | 文件 | 类型 | 关键改动 |
|---|---|---|---|
| 1 | `AgentRef.java`(`lingshu-core/runtime/`) | modify | +1 field `List<String> domainWhitelist = Collections.emptyList()`(@Value Lombok 自动 immutable) |
| 2 | `HttpJsonRpcA2aTransport.java`(`lingshu-a2a-client/`) | modify | ctor +1 参数 `List<String> domainWhitelist`(defensive copy `new ArrayList<>(domainWhitelist)`);`fetchCard()` 1 行 + `jsonRpcCall()` 1 行 = **2 hook 点**调 `McpHttpSupport.checkOrThrow(url, this.domainWhitelist)` |
| 3 | `HttpJsonRpcA2aTransportAutoConfiguration.java` | modify | `@Bean` 方法改为根据 `AgentConfig.A2a.remoteAgents` + `AgentRef.domainWhitelist` 聚合 per-baseUrl whitelist,unique baseUrl → 1 transport 实例(union 同 URL 的 AgentRef whitelist) |
| 4 | `AgentConfig.A2a`(`lingshu-core/runtime/`) | 不改 | per-remote-agent 粒度在 `AgentRef` 上,不在 A2a 顶层 |
| 5 | `HttpJsonRpcA2aTransportCheckOrThrowTest.java` | new (L1) | ~6 unit cases:`whitelistedHost_noThrow` / `nonWhitelistedHost_throws[LINGS-S01]` / `emptyWhitelist_deniesAll` / `nullUrl_throws` / `malformedUrl_throws` / `ipv4Host_extractedCorrectly` |
| 6 | `HttpJsonRpcA2aTransportDomainGuardIT.java` | new (L2) | ~3 IT cases(JDK `com.sun.net.httpserver.HttpServer` mock + 真发请求):`whitelistedHost_reachesConnected` / `nonWhitelistedHost_callToolDenied[LINGS-S01]` / `streamableHttpNotApplicable`(本 Story 不涉及 SSE/Streamable,A2A 只有 HTTP/JSON-RPC)|
| 7 | `HttpJsonRpcA2aTransportTest.java` + 其它现有 A2A 测试 | modify | 5—8 existing test files updated:`.domainWhitelist(Arrays.asList("127.0.0.1"))` + `import java.util.Arrays;` 让 IT fixture 走沙箱白名单(mirror MCP #033 「35 existing test files updated」 pattern) |

**总文件改动:7 个**(3 source modify + 2 new test + 2 modify existing tests),严格 ≤ 5 核心 source 文件改动 边界内。

### 3.2 `AgentRef.domainWhitelist` 字段契约

```java
@Value  // Lombok immutable
public class AgentRef {
    String name;
    String url;
    int priority;
    // 🆕 Story #034 — per-remote-agent sandbox domain whitelist.
    // Empty list = deny ALL outgoing HTTP from this agent's transport (strict mode).
    // Case-sensitive exact host match (mirrors WhitelistedHttpClient.check + McpHttpSupport.checkOrThrow).
    // yml example:
    //   agent:
    //     a2a:
    //       remoteAgents:
    //         - name: alice
    //           url: https://alice.example.com
    //           domain-whitelist: [alice.example.com, alice-internal.example.com]
    List<String> domainWhitelist;
}
```

**Default 值**:`Collections.emptyList()`(immutable)。yml 不写 `domain-whitelist` 时,启动期 `AgentRef` 构造走 Jackson 缺省,null → 空 list,**deny all HTTP**(与 MCP #033 `McpServerConfig.domainWhitelist` 默认行为一致)。

### 3.3 `HttpJsonRpcA2aTransport` ctor 扩展契约

```java
// 🆕 Story #034 — domain whitelist (defensive copy)
public HttpJsonRpcA2aTransport(String httpBaseUrl,
                               ObjectMapper json,
                               AgentCardCache cardCache,
                               Duration callTimeout,
                               List<String> domainWhitelist) {
    // ... existing 4-param validation unchanged ...
    if (domainWhitelist == null) {
        throw new IllegalArgumentException("domainWhitelist must not be null");
    }
    this.domainWhitelist = new ArrayList<>(domainWhitelist);  // defensive copy
    // ... rest unchanged ...
}
```

**5-arg ctor** 是新的"主 ctor";保留 4-arg ctor(默认 `domainWhitelist = emptyList()`)给 backward compat(Story #009c 测试不 regression)。

### 3.4 2 hook 点契约

#### Hook #1:`fetchCard(agentName)` 入口前

```java
@Override
public Map<String, Object> fetchCard(String agentName) {
    // ... existing arg validation ...
    URI uri = URI.create(httpBaseUrl + AGENT_CARD_PATH);
    // 🆕 Story #034 — sandbox domain guard (mirrors MCP #033)
    McpHttpSupport.checkOrThrow(uri.toString(), this.domainWhitelist);
    // ... existing HTTP send ...
}
```

#### Hook #2:`jsonRpcCall(method, params)` 入口前

```java
private JsonNode jsonRpcCall(String method, JsonNode params) {
    // ... existing body build ...
    URI uri = URI.create(httpBaseUrl + RPC_PATH);
    // 🆕 Story #034 — sandbox domain guard (mirrors MCP #033)
    McpHttpSupport.checkOrThrow(uri.toString(), this.domainWhitelist);
    // ... existing HTTP send ...
}
```

**注意**:`subscribe(taskId, onEvent)` 内部走 `get(taskId)` → `jsonRpcCall(...)`,**已经被 hook #2 覆盖**;**0 额外 hook**。`cancel(taskId)` 同理(`jsonRpcCall("tasks/cancel", ...)` 走 hook #2)。

### 3.5 错误传播契约

`McpHttpSupport.checkOrThrow()` 抛 `AccessDeniedException[LINGS-S01]`(Story #028 + #033 已落)。`HttpJsonRpcA2aTransport` **不 catch** —— 让异常向上传播到 `RemoteAgentTool.execute()`(`RemoteAgentTool.java:327-340` 已有 `catch (Exception e)` generic 处理路径),`RemoteAgentTool` 把 `AccessDeniedException` 转 `ToolResult.error("[LINGS-S01] Domain not whitelisted: <host>")`,**与 MCP 错误格式统一**(mirror `McpCallResult.error("[LINGS-S01] " + e.getMessage())`)。

### 3.6 `HttpJsonRpcA2aTransportAutoConfiguration` wiring

**当前**:`HttpJsonRpcA2aTransportAutoConfiguration` 只暴露 `HttpJsonRpcA2aTransportProvider` 1 个 `@Bean`(Story #009e 简化后);实际 `HttpJsonRpcA2aTransport` 实例由 `RemoteAgentToolAutoConfiguration#remoteAgentTool()` 间接 new。

**🆕 Story #034 改动**:
- `HttpJsonRpcA2aTransportAutoConfiguration` 加 `@Bean(name = "a2aTransport_http-jsonrpc")` 方法(`HttpJsonRpcA2aTransportFactory`),接受 `AgentConfig`,根据 `cfg.getA2a().getRemoteAgents()` 按 unique `url` 分组,每个 unique URL → 1 `HttpJsonRpcA2aTransport` 实例,whitelist = union(同 URL 的 AgentRef.domainWhitelist,fallback empty)
- `RemoteAgentToolAutoConfiguration#remoteAgentTool()` 改为通过 factory 拿 transport,传入 `Map<String, A2aTransport> agentNameToTransport`(每个 agentName → 对应 baseUrl 的 transport)
- 改动控制在 2 文件(`HttpJsonRpcA2aTransportAutoConfiguration` + `RemoteAgentToolAutoConfiguration`),**保持 `#009e` 拆分架构**(transport Bean 独立于 RemoteAgentTool Bean)

---

## 4. ErrorCode

**0 新 ErrorCode**,复用 Story #028 已落的 `AccessDeniedException` + `LINGS-S01`(Permission/Sandbox 域首条)。`McpHttpSupport.checkOrThrow()` 抛 `AccessDeniedException`,`[LINGS-S01]` 前缀由 super class 构造器自动嵌入(Story #028 已锁),A2A 直接复用,无需在 `HttpJsonRpcException.ERROR_CODE`(`LINGS-S08`)或别处加新码。

---

## 5. AC 黑盒验证(Acceptance Criteria)

### 5.1 L1 单元(`HttpJsonRpcA2aTransportCheckOrThrowTest`,6 case)

| ID | Case | 断言 |
|---|---|---|
| AC-1.1 | `whitelistedHost_noThrow` | ctor with `domainWhitelist=["example.com"]` + `fetchCard("foo")` on `http://example.com/...` → **不抛** |
| AC-1.2 | `nonWhitelistedHost_throws` | ctor with `domainWhitelist=["example.com"]` + `fetchCard("foo")` on `http://other.com/...` → 抛 `AccessDeniedException[LINGS-S01]` |
| AC-1.3 | `emptyWhitelist_deniesAll` | ctor with `domainWhitelist=[]` + 任何 host → 抛 `AccessDeniedException[LINGS-S01]` |
| AC-1.4 | `nullUrl_throws` | 直接传 `null` URL → 抛 `IllegalArgumentException`(McpHttpSupport 内部判) |
| AC-1.5 | `malformedUrl_throws` | `"not-a-url"` → 抛 `AccessDeniedException`(`URI.create` 抛 IAE → catch → rethrow) |
| AC-1.6 | `ipv4Host_extractedCorrectly` | `"http://127.0.0.1:8080/..."` + `domainWhitelist=["127.0.0.1"]` → 不抛 |

### 5.2 L2 集成(`HttpJsonRpcA2aTransportDomainGuardIT`,3 case)

| ID | Case | 断言 |
|---|---|---|
| AC-2.1 | `whitelistedHost_reachesConnected` | 起 JDK `com.sun.net.httpserver.HttpServer` mock A2A server on `127.0.0.1`,`domainWhitelist=["127.0.0.1"]` → `submit("foo", "bar", "{}")` 成功,`ToolResult.status == SUCCESS` |
| AC-2.2 | `nonWhitelistedHost_callToolDenied` | mock server on `127.0.0.1`,`domainWhitelist=["other.com"]` → `submit(...)` 抛 `AccessDeniedException[LINGS-S01]`,**真实 HTTP 请求不发起**(verify via mock server `getRequestCount() == 0`) |
| AC-2.3 | `nonWhitelistedHost_AccessDeniedThrown` | 验证 `RemoteAgentTool.execute()` 接 `AccessDeniedException` → 转 `ToolResult.error("[LINGS-S01] Domain not whitelisted: 127.0.0.1")` |

### 5.3 L1 工厂分派(`HttpJsonRpcA2aTransportAutoConfigurationTest`,+1 case)

| ID | Case | 断言 |
|---|---|---|
| AC-3.1 | `uniqueUrlPerAgent_createsNTransports` | yml with 2 remoteAgents 不同 URL → factory 创建 2 transport 实例,whitelist 分别对应 union |

### 5.4 R-13 mitigation (d) 验证

`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = **0 binary delta 第 19 次 PASS**(纯 JDK 8 `URI.create` + `List.contains` + `ArrayList` + `AccessDeniedException`(Story #028 已落)+ JDK standard,0 新 binary 引入)。

---

## 6. 关键不变项(对齐 MCP #033)

| 不变项 | 说明 |
|---|---|
| `A2aTransport` SPI 接口 | 5 方法契约不变(`fetchCard` / `submit` / `get` / `cancel` / `subscribe`),仅 `HttpJsonRpcA2aTransport` 实现层扩展 |
| `Tool` SPI | 不变,`RemoteAgentTool` 实现层扩展(`execute` 已有 generic Exception catch) |
| `ToolExecutor.dispatch()` | 5 步流水线不变(§4.10.1 硬规则 2),sandbox deny 在 `ToolResult` 层表现为 error 替代 success |
| `McpHttpSupport.checkOrThrow()` | 不变(Story #033 已落,A2A **直接 import 复用**)|
| `AccessDeniedException[LINGS-S01]` | 不变(Story #028 已落) |
| `AgentConfig` 不可变契约 | `A2a` 内层类 0 字段新增;`AgentRef` +1 字段(用户已声明的 POJO 扩展,符合 Lombok @Value immutable 风格) |
| `AgentFactory` SPI | 不变(@Autowired 6-Router ctor 不动) |
| 9 Slot 体系 | 不变 |
| JDK 8 兼容 | `URI.create` + `List.contains` + `ArrayList` + `Collections.emptyList()` 已锁,no `var` / `List.of` / sealed / records |
| **0 新 Maven 依赖** | ✅ |
| **0 新 ErrorCode** | ✅(复用 `LINGS-S01`) |
| **0 SPI 改动** | ✅(仅 `HttpJsonRpcA2aTransport` 实现层 + `AgentRef` 数据字段) |

---

## 7. 关键决策点(Q&A)

### 7.1 为何不 rename `McpHttpSupport` → `SandboxHttpSupport`?

- **理由**:`checkOrThrow` 逻辑本身与 MCP 无关,**类名 rename 是 refactor 而非 feature**。
- **决定**:留 OQ-Future。本 Story 只 import 复用,scope 最小化。
- **扳机条件**:未来若有第三方 transport(Story 计划中的 Grpc/InProcess)也想加 sandbox guard,或语义上用户反馈"类名误导",再起独立 refactor Story。

### 7.2 为何 per-remote-agent list 不放 `AgentConfig.A2a` 顶层?

- **粒度**:`AgentRef` 是 per-remote-agent 的 POJO,`url` 字段在 `AgentRef` 上,**whitelist 与 URL 同生命周期**最自然。
- **多 partner 场景**:业务方配多个 partner agent(每个域名不同),per-AgentRef whitelist 比全局 list 更精细,mirror MCP per-server pattern。
- **拓扑正确性**:`HttpJsonRpcA2aTransport` 是 per-baseUrl 单实例,wire 时按 unique URL 聚合(union 同 URL 的 AgentRef whitelist)即可。

### 7.3 为何默认 empty = deny all,而不是默认 = allow declared baseUrl?

- **沙箱语义**:`WhitelistedHttpClient`(Story #028)和 `McpServerConfig.domainWhitelist`(Story #033)都默认 deny all,**A2A 必须保持一致**。
- **YOLO back-compat 路径**:`AgentConfig.A2a.remoteAgents` 默认 `Collections.emptyList()`(#009d 已落)→ 启动期 0 transport 实例被 wire → A2A HTTP 完全不发起 → 0 deny 风险。
- **Phase 2 优化候选**:OQ-Future 表加一行 "auto-derive from declared baseUrl" —— 业务方配了 `url: https://partner.com` 后自动加 `partner.com` 到 whitelist,**减少 yml 行数**;但本 Story 不引入,避免与 MCP #033 偏离(sandbox 一致性优先)。

---

## 8. 测试 fixture update 计划(预计 5—8 文件)

mirror MCP #033 「35 existing test files updated」 pattern,A2A 所有现有 IT fixture 走 `127.0.0.1` localhost 需要手动加 whitelist:

| 文件 | 改动 |
|---|---|
| `HttpJsonRpcA2aTransportTest.java` | 现有 mock HttpServer 走 `127.0.0.1`,需 `.domainWhitelist(["127.0.0.1"])` |
| `HttpJsonRpcA2aTransportProviderTest.java` | 同上 |
| `HttpJsonRpcA2aTransportAutoConfigurationTest.java` | 同上 |
| `RemoteAgentToolTest.java` | 测试 fixture 走 localhost,需 transport 构造时配 whitelist |
| `RemoteAgentToolLifecycleTest.java` | 同上 |
| `RemoteAgentToolAutoConfigurationTest.java` | 同上 |
| `RemoteAgentSchemaBuilderTest.java` | 可能涉及(fetchCard 路径) |
| `RemoteAgentTransportWiringIT.java` | IT fixture 同上 |

**总改动**:预计 5—8 files × 1—2 lines = ~10 行 Java 改动 + 5 个 `import java.util.Arrays;`。

---

## 9. 文档同步计划(实施完成后)

- `dsh_agent_design.md` v1.5.50 → v1.5.51:
  - §0 标题 + §13 changelog 新增 v1.5.51 行记录 Story #034 完成
  - §5.6.3.1 `HttpJsonRpcA2aTransport` 段补 `domainWhitelist` 字段 + 2 hook 点
  - §15.9 ErrorCode 域 cross-ref 加 "🆕 v1.5.51 Story #034 复用 LINGS-S01"(并列 #033)
  - 「对应设计文档」版本号 v1.5.50 → v1.5.51
- `README.md`:「核心特性」段补 🛡️ A2A 沙箱守卫已上线 bullet + 「Story 路线图」段追加 #034 retrospective
- `specs/ROADMAP.md`:段一 ✅ 已完成加 #034 行 + 段二 🟡 待补 #034 划掉 + 段五 🎯 实施节奏 + 统计
- `constitution.md` §10 R-13 缓解 Story 列表补 `#034` 行 第 19 次 PASS 0 binary delta
- `CLAUDE.md`:v1.3.45 → v1.3.46 本条记录

---

**修复者**:Claude Code(根据用户 2026-10-01 会话反馈 "a2a 的网络连接也和 mcp 的网络链接一样使用的了 whitelist 做校验吗" + "走 MCP 复用模式" + "MCP 是也是要求用户显示配 domain-whitelist 吗" 确认 A2A 是 MCP #033 的平行盲点 + 严格 mirror mode)
