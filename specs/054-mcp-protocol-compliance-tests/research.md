# Phase 0 Research: Story #054 MCP 协议合规测试收口

**Date**: 2026-10-22 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

本文解决 plan 的 Technical Context 里全部 unknowns,并对 checklist 显式延后的两个决策点拍板。
每条决策格式:**Decision / Rationale / Alternatives considered**。

---

## 决策点 1:FR-010 fixture 形态 —— 合并为单份规范形状 fixture

**Decision**: **合并**。把 `TestMcpHttpServer` 重写成**单份**规范形状 fixture,同时服务 SSE 与
Streamable HTTP 两种 transport;`TestMcpSseServer` fork 退役(其 handler 逐条并入
`TestMcpHttpServer`),测试侧调用点改指向合并后的 fixture。

**Rationale**:

1. **两套 fork 正是本次漂移的根因**。`TestMcpSseServer` 是 `TestMcpHttpServer` 的**复制**而非
   子类(`writeJsonRpc(ex, id, result)` 在 fork 内独立重写于 L231-242),fork 的 `/sse` handler
   (L154-209)**从头到尾不写任何 `event:` 行**,因此 `a417464` 之后
   `SseMcpServerConnection.doConnect()`(`await(endpointLatch, hbTimeoutMs)` 失败 → 抛
   `IOException("no 'endpoint' event from SSE server within Nms")`)**必然失败**。两个 fork 各改各的,
   SSE 那份从没跟上协议重写 —— 这就是「测试绿着但真实 server 连不上」的具体机制。
2. **SC-006 直接要求收敛**:*"两套 HTTP 测试 fixture 最终收敛为单份规范形状实现"*。
   保留两套则 SC-006 无法判定通过。
3. 两种 transport 的**协议差异只在帧格式**,JSON-RPC 的 method/id/params 语义完全相同。
   合并后 id 回显、ping、404 会话过期三套逻辑只有**一份实现**,不会出现「A 改了 B 忘了」。
4. 合并后 fixture 对 transport 而言扮演「**一个规范 MCP server**」,契约面收窄到
   `contracts/fixture-protocol.md` 一份文档。

**Alternatives considered**:

| 方案 | 否决理由 |
|---|---|
| 多 fixture 并存、各自证明同一套规范协议(checklist 允许的另一条路径) | 允许但**不选**:SC-006 要求收敛;且 fork 正是缺陷 B 的成因,留 fork 等于把同一个坑留给下一个 PR |
| 让 `TestMcpSseServer extends TestMcpHttpServer` | 共享 handler 需要 refactor 整个 fixture(2 个 context 绑不同),改动面 ≥ 合并重写,且仍留下 2 个类型名要人记 |
| 换真实 MCP server(`npx @modelcontextprotocol/server-everything`)当 fixture | spec Assumptions 第 3 条已明确**外部真实 server 连通性不作为通过判据**;会引入 CI 网络依赖 + npx 冷启动,违反「每用例 ≤ 2 s」预算 |

---

## 决策点 2:是否采纳可注入时间参数构造器 —— 采纳

**Decision**: **采纳**。SSE / Streamable HTTP 两个 transport 上**当前无任何测试使用**的
4 参可注入时间构造器正式启用,取代旧 fixture 的 `delayMs` 系统属性开关。

```java
// SseMcpServerConnection L146-149
public SseMcpServerConnection(McpServerConfig cfg) { this(cfg, -1L, -1L, -1L); }
public SseMcpServerConnection(McpServerConfig cfg,
        long hbIntervalMs, long hbTimeoutMs, long reconnectCapMs) { ... }
// L158-160:sentinel < 0 → 回退 cfg 值
```

**Rationale**:

1. **0 行生产代码改动**。构造器已存在,只是没人调。`McpServerConnectionFactory` 只用 1 参
   构造器,所以生产路径行为 100% 不变 —— 本 Story 的「生产代码 0 改动」约束守住。
2. 把现有 `Thread.sleep(8_000L)`(SSE Reconnect L104)/ `Thread.sleep(6_000L)`
   (Streamable Reconnect L93)/ `delayMs=2000`(Heartbeat L119)压到 **50–200 ms** 量级,
   单文件测试从分钟级降到秒级,满足「每用例 ≤ 2 s」预算。
3. **比 sysprop 开关更可控**:sysprop 是 JVM 全局(`System.setProperty`)且**跨用例污染风险**
   ——并行执行时一个用例设了 `delayMs=2000`,另一个用例读到同一个值。当前 fixture 全部靠
   sysprop 切换行为,这是 flake 的次要来源。构造函数参数是**每实例**的。
4. Assumptions 第 8 条(已定稿的 spec 假设)明确建议优先启用。

**Alternatives considered**:

| 方案 | 否决理由 |
|---|---|
| 继续用 sysprop 开关 | 全局状态 + 跨用例污染;且无法表达「每用例不同预算」 |
| 改 `computeBackoffMs` 的 `base` 让它可注入 | 需要**改生产代码**(本 Story 承诺 0 行),且 `base = 1_000L` 是 R-13 baseline 镜像保护的行为 |
| 引入虚拟时钟 / mock scheduler | 需 Mockito 定时器模拟,复杂度远超收益;Awaitility 已能异步等待 |

### 两个必须记录的下限约束

采纳可注入时间构造器后,**以下两项无法压到 0**,测试预算必须为其留出余量:

| 约束 | 位置 | 事实 | 对测试的影响 |
|---|---|---|---|
| **重连退避首跳 ≥ 1 s** | `SseMcpServerConnection.computeBackoffMs(int)` L574-584,`base = 1_000L` 硬编码 | `1s → 2s → 4s → … → 60s(cap)`,**首个重连延迟不可注入** | 重连类用例(Reconnect / 404 会话恢复)wall clock **≥ 1 s**,是唯一会触碰 2 s 预算边界的类别。其余用例全部 < 1 s |
| **`await()` 有 1 ms 下限** | `SseMcpServerConnection.await(...)` 内 `Math.max(1L, timeoutMs)` | 传 `0` **不等于「立即返回」**,而是「至少等 1 ms」 | 端点公告缺失类用例可以做到 ~1 ms 失败,**不存在 0 ms 极速路径**;不要写「传 0 断言立即抛」的用例 |

> **实现期提醒**:`McpServerConnectionFactory.create(cfg)` 只用 1 参构造器,所以
> **不可注入的用例仍要经 factory**。需要注入时间的用例必须**直接 new connection**,不能走
> factory —— 测试里对这一点要显式注释,否则后人会以为 factory 也支持注入而误用。

---

## 决策点 3:`postJsonRpc` 死代码处置 —— 原样保留 + 补 `postJsonRpcSpec` 覆盖

**Decision**: **不动** `postJsonRpc` 及其 4 个既有测试;**新增** `postJsonRpcSpec` 的测试覆盖
(新文件 `McpHttpSupportPostJsonRpcSpecTest.java`)。

**Rationale**:

1. 死代码确认(**已 grep 全量核对**):

   | 方法 | 位置 | 生产调用方 | 测试调用方 |
   |---|---|---|---|
   | `postJsonRpc`(legacy 非规范) | `McpHttpSupport.java` L115 | **0** | 4(全在 `McpHttpSupportTest`) |
   | `postJsonRpcSpec`(规范) | `McpHttpSupport.java` L419-481 | 3(`StreamableHttpMcpServerConnection` L275 / L290 / L321) | **0** |

   这是**与本 Story 要修的缺陷完全同型的一处病灶**:测试覆盖了生产路径不走的那个方法,
   生产路径真正走的那个方法零覆盖。

2. **删生产死代码 = scope creep**。本 Story 的定位是补测试;顺带清理 `postJsonRpc`
   会让 diff 从「0 生产改动」变成「-1 生产方法」,破坏 plan 里已承诺的 R-13 baseline
   0 binary delta 叙事(`postJsonRpc` 的删除本身**不**改 dep-tree,但会让 reviewers
   无法一眼分辨本 Story 改了什么)。
3. **删它的测试会撞 FR-025**。FR-025 明文:*"不得以删除断言的方式修复"*。删掉
   `McpHttpSupportTest` 的 4 个用例,在 diff 上一眼就是「删断言」,即使理由正当。
4. 4 个 `postJsonRpc` 用例**本身仍然有效**(它测的 legacy 方法行为未变),不是假绿。

**Alternatives considered**:

| 方案 | 否决理由 |
|---|---|
| 删 `postJsonRpc` + 删 4 个测试 | scope creep + 撞 FR-025 |
| 删 `postJsonRpc` + 把 4 个测试改写为 `postJsonRpcSpec` 测试 | 改动面大且违背 plan「新增独立测试文件」的最小路径 |
| 加 `@Deprecated` 标注 | 本 Story 不改生产代码;且 `@Deprecated` 同样是生产改动 |

**遗留(记入 ROADMAP 待办,不阻塞本 Story)**:清理 `McpHttpSupport.postJsonRpc`
+ `McpHttpSupportTest` 的 4 个 legacy 用例。单独一个 cleanup commit。

---

## 决策点 4:请求记录器(request recorder)—— 新增 fixture 抽象

**Decision**: 合并后的 fixture 内置一个**请求记录器**,记录每次收到的
`(method, id, contentType, sessionId 头, body)`,供断言查询。

**Rationale**:

1. **SC-003 是硬要求**:*每条 AC 用例在其缺陷被重新引入时必须失败*。要证伪
   「transport 到底发了什么 JSON-RPC 包」,必须有可观察的记账。
   现有 fixture **只回包不记账** —— 这是 SC-003 无法达成的结构原因。
2. FR-013(id 关联,含乱序 / 并发)、FR-018 / FR-019(session id + protocol version
   记录与回放)、FR-020(404 后同 id 重试一次)三条 FR **全部**要求断言「发出去的信封长什么样」,
   只靠「回包结果对不对」测不出来。
3. **FR-020 是最典型的**:「同 id 重试且仅重试一次」需要断言**同一个 id 出现了两次**,
   而现有 fixture 连收到过什么都看不到。

**Alternatives considered**:

| 方案 | 否决理由 |
|---|---|
| 每个用例各自 `AtomicReference<String> lastBody` | plan Complexity Tracking 已判定:散落 20+ 处、无法复用、id 关联断言仍写不出来 |
| 在 transport 侧 mock `HttpURLConnection` | 测的是 mock 不是协议;且绕开了真实 socket,无法覆盖 SSE 长连接 |
| 用 `Mockito.mock(HttpURLConnection.class)` | 同上 + Spring Boot 3.2.5 下的 JDK 17 `HttpURLConnection` mock 已知脆弱 |

**记录器的最小形状**(完整契约见 [contracts/fixture-protocol.md](./contracts/fixture-protocol.md)):

```text
RecordedRequest { method, id(Long, 可为 null=notification), contentType, sessionIdHeader, body(String) }
fixture.recorded()            → List<RecordedRequest>   # 按到达序
fixture.clearRecorded()
```

JDK 8 兼容:`AtomicLong` 序号 + `Collections.synchronizedList(new ArrayList<>())`,
**不用** `List.of` / `record` / `var`。

---

## 决策点 5:fixture 行为开关 —— sysprop 保留但收窄

**Decision**: 保留 sysprop 机制(不引入新的依赖注入通道),但把**开关集合收窄**到规范协议需要的几项,
并把被规范淘汰的开关**删除**。

| 现有 sysprop | 现状 | 处置 | 理由 |
|---|---|---|---|
| `port` | 绑定端口 | **保留** | 子进程 fixture 用;父进程从 stdout `PORT=<n>` 读端口 |
| `delayMs` | handler 前 `Thread.sleep` | **降级为辅助** | 不再用来表达「心跳超时」这类语义,只保留给「刻意制造延迟」的一条用例 |
| `exitAfter` | 计数到 N 后 `System.exit(0)` | **保留** | 制造服务端进程消失,驱动重连 |
| `dontReplyHealth` | `/health` 返回 500 | **删除** | FR-007:fixture **不得**依赖厂商 `/health`。该端点整条退役 |
| `pushIntervalMs` | SSE 推送间隔 | **保留** | SSE `event: message` 的节奏控制 |
| `closeSseAfter` | SSE 流关闭时机 | **保留** | 驱动「流早断」边界用例 |
| `malformedRatio` | 注入畸形帧 | **保留** | 边界用例:非 JSON data 帧 |

**新增开关**(替代 `dontReplyHealth` 承担的「会话失效」语义,FR-008):

| 新 sysprop | 默认 | 作用 |
|---|---|---|
| `expireSession` | `false` | `true` 时所有 POST 返回 **404**,驱动 FR-020 会话恢复链路 |
| `noEndpointAnnounce` | `false` | `true` 时 `/sse` **不发** `event: endpoint`,驱动 FR-011 / FR-016 端点缺失链路 |

**Rationale**: FR-007 明确「fixture 不得依赖厂商 `/health`」,`/health` 端点与
`dontReplyHealth` 开关必须随协议重写一起退役;FR-008 / FR-009 / FR-011 / FR-016 要求
「会话过期」「无端点公告」两个**规范协议里的真实场景**成为可控开关。

---

## 决策点 6:78 个既有 `@Test` 的 break 判定全表

扫描范围:`lingshu-core/src/test/java/ai/lingshu/core/mcp/` 下 28 个顶层测试类 +
`fixture/` 3 个,**78 个 `@Test`(27 个含断言的方法文件)**。

### 6.1 WILL BREAK —— 必须就地更新(FR-025)

| # | 测试 | 位置 | 失效根因 |
|---|---|---|---|
| 1 | `SseMcpServerConnectionStartTest.start_fakeServer_5stepsReachesConnected` | L55 | `TestMcpSseServer` 不发 `event: endpoint` → `doConnect()` 必抛 `IOException` |
| 2 | `SseMcpServerConnectionStartTest.start_alreadyConnected_noop` | L116 | 同上,连不上就没「already connected」可言 |
| 3 | `SseMcpServerConnectionListenerTest` ×3 | 全类 | 同上,状态迁移起点就断了 |
| 4 | `SseMcpServerConnectionReconnectTest.sseStreamBroken_reconnects` | L117 | 同上 + `Thread.sleep(8_000L)` 预算爆炸 |
| 5 | `SseMcpServerConnectionCloseAndCallTest.close_idempotent` | L82 | 同上 |
| 6 | `SseMcpServerConnectionCloseAndCallTest.callTool_afterDisconnect_returnsError` | L126 | 同上 |
| 7 | `StreamableHttpMcpServerConnectionStartTest.start_fakeServer_5stepsReachesConnected` | L61 | `TestMcpHttpServer` 是 5 端点方案,Streamable HTTP 规范要求**单端点**,现 fixture 不符 |
| 8 | `StreamableHttpMcpServerConnectionStartTest.start_alreadyConnected_noop` | L120 | 同上 |
| 9 | `StreamableHttpMcpServerConnectionHeartbeatTest` ×3 | 全类 | 同上 |
| 10 | `StreamableHttpMcpServerConnectionReconnectTest.reconnect_healthyServer_staysConnected` | L100 | 同上 |
| 11 | `StreamableHttpMcpServerConnectionCloseAndCallTest.close_idempotent` | L75 | 同上 |
| 12 | `StreamableHttpMcpServerConnectionCloseAndCallTest.callTool_afterDisconnect_returnsError` | L119 | 同上 |
| 13 | `McpHttpDomainGuardIT.sseWhitelisted_reachesConnected` | L76 | 白名单放行后必须**真的握手成功**,旧 fixture 握手不上,「正向放行」断言无意义 |

**陈旧 per-method URL**(不改会在新协议下变成「连一个不存在的端点」的假断言):

| 文件 | 行 | 现值 |
|---|---|---|
| `StreamableHttpMcpServerConnectionCloseAndCallTest` | L62 / L102 / L151 | `http://127.0.0.1:1/initialize` / `.../tools/call` |
| `StreamableHttpMcpServerConnectionStartTest` | L59 / L87 | `http://127.0.0.1:1/initialize` / `.../tools/call` |
| SSE 对应 3 个文件 | — | `http://127.0.0.1:1/sse` |

→ 全部改为 `cfg.getUrl()` 指向的**规范单端点**(Streamable HTTP)与 `resolveSseUrl()`(SSE)。

**已知 stub**:

| 文件 | 行 | 问题 |
|---|---|---|
| `SseMcpServerConnectionReconnectTest.pickLikelyClosedPort()` | L109-113 | 注释声称「挑一个大概率关闭的端口」,实现**恒返回 `1`**,是 stub |

### 6.2 PASSES VACUOUSLY —— 假绿,一并就地更新

这些用例**当前能过**,但过得很勉强:断言的负路径由「端口本来就不通」兜底,而不是由
**协议行为**驱动。它们的自身注释已经承认这一点。

| 测试 | 位置 | 备注 |
|---|---|---|
| `SseMcpServerConnectionStartTest.start_invalidUrl` / `start_missingUrl` | — | 连不上就报错,与协议无关 |
| `StreamableHttpMcpServerConnectionStartTest.start_duringReconnecting_noop` | — | 同上 |
| `StreamableHttpMcpServerConnectionReconnectTest.reconnect_unbounded` | — | 同上 |
| `McpHttpDomainGuardIT` 负向 3 例 | 自身注释 L118-120 / L160-163 明确 concede | 白名单拒绝发生在**握手之前**,握手根本跑不到 |

### 6.3 语义失效但仍绿 —— 重点(4 个)

`SseMcpServerConnectionHeartbeatTest`(162 行,4 个用例)是缺陷 B 的**教科书案例**:

| 用例 | 行 | 失效分析 |
|---|---|---|
| `probe_healthOk` | L63 | 测 `/health` 返回 200;新协议**没有** `/health`。最强断言是 L86 对时间戳的 `isGreaterThanOrEqualTo` —— **时间戳永不推进也能过** |
| `probe_health5xx_disconnects` | L91 | `dontReplyHealth=true` L93;该开关随 `/health` 一起退役 |
| `probe_healthTimeout_disconnects` | L116 | `delayMs=2000` L119;新语义是 JSON-RPC `ping`(FR-015) |
| `probe_doubleCheck_recoveredHeartbeat` | L142 | 同上 |

→ 4 个全部改写为 **JSON-RPC `ping` 语义**(FR-015),并借可注入时间构造器把
`delayMs=2000` + Awaitility `atMost(5s)` 压到 50–200 ms 预算内。

### 6.4 WON'T BREAK —— 0 改动

| 组 | 用例数 | 说明 |
|---|---|---|
| `McpServerConnectionFactoryTest` | 6 | 只测 factory 按 `transport` 分派,不起真端点 |
| `McpToolAdapterTest` | 9 | 纯 adapter 逻辑 |
| `McpToolAdapterIT` | 2 | stdio 路径 |
| `McpHttpSupportTest` | 4 | 测死方法 `postJsonRpc`,方法本身行为未变(见决策点 3) |
| `McpHttpSupportCheckOrThrowTest` | 8 | 纯 `checkOrThrow` 单测 |
| `McpServerConnectionContractTest` | 1 | 反射校验 SPI 方法签名 |
| `McpServerConfigTest` / `ConnectionStateTest` / `McpTransportExceptionTest` | — | 配置 / 枚举 / 异常 |
| `StdioMcpServerConnection*` ×7 文件 | 19 | stdio 不走 HTTP,协议重写不触及 |
| `impl/mcp/McpTransportTest` / demo-mcp `BlackBoxVerificationTest` | 5 | 非 MCP HTTP 包路径 |

### 6.5 汇总

| 分类 | 用例数 | 处置 |
|---|---|---|
| WILL BREAK | 约 20 | 就地更新(**禁止**删断言,FR-025) |
| PASSES VACUOUSLY | 约 8 | 就地更新为协议驱动的负路径断言 |
| 语义失效但仍绿 | 4 | 改写为 ping 语义 |
| WON'T BREAK | 约 46 | 0 改动 |

---

## 决策点 7:测试基础设施缺口 —— 补哪些

| 缺口 | 现状 | 处置 |
|---|---|---|
| 无请求记录器 | fixture 只回包不记账 | **新增**(决策点 4) |
| 无 latch / 注入时钟 | 全靠 `Thread.sleep(6000/8000)` | **启用可注入时间构造器**(决策点 2) |
| 两套 fixture 启动方式 | 进程内 `@BeforeEach` 绑 `127.0.0.1:0` vs 子进程经 `McpHttpTestSupport` 读 `PORT=<n>` | **保留两种**:进程内用于纯编解码 L1(快),子进程用于协议 L3(真实 socket) |
| 无 fixture 基类 | `TestMcpHttpServer` 与 `TestMcpSseServer` 各写一遍工具方法 | **合并**(决策点 1)后自然消除 |
| 端口选择器是 stub | `pickLikelyClosedPort()` 恒返回 1 | 改为显式关闭 fixture 后取其端口 |

**IPv6 陷阱(踩了会 flake)**:fixture 一律绑 **`127.0.0.1`**(IPv4 loopback)并在断言里用
`127.0.0.1`,**不用** `localhost` 字面量 —— Windows WSL2 的解析顺序会让 `localhost`
先命中 `::1`,而 fixture 只听 IPv4。这是 constitution §6 兼容矩阵的直接要求。

---

## 决策点 8:`application.yml` 现值与用户描述不符 —— 记录在案

用户在 Story 描述里说 L215 是「贡献者的 Windows 绝对路径 `d:\lingshu\...\echo-stdio.py`」。
**实测工作树现值是**:

```yaml
# lingshu-examples/demo-product/src/main/resources/application.yml L211-215
- name: echo-stdio
  transport: stdio
  command: python3
  args:
    - /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu/lingshu-examples/demo-product/src/main/resources/application.yml
```

即:**macOS 绝对路径,指向本仓自己的 `application.yml`**,既不是 Windows 路径,
也不是 `.py` 文件。工作树相对已提交状态发生了漂移。

**为什么这条重要(不只是格式问题)**:`python3 <application.yml>` 会因 Python 语法错误
立即退出 —— 所以这**不只是路径不可移植**,而是**stdio server 起不来**,demo 的
「real, working out of the box」承诺(L210 注释原话)直接失效。

**目标终态(与 `a417464^` 一致,来自 Story #025)**:

```yaml
        args:
          - src/main/resources/mcp-servers/echo-stdio.py
```

**相对路径可行的根因**(`StdioMcpServerConnection.spawn()` L282-302):

```java
ProcessBuilder pb = new ProcessBuilder(buildCommandLine());
Map<String, String> env = pb.environment();
if (cfg.getEnv() != null) { env.putAll(cfg.getEnv()); }
pb.redirectError(ProcessBuilder.Redirect.PIPE);
return pb.start();
```

**没有 `.directory(...)`** —— 子进程继承 JVM 的 CWD。标准启动方式
`mvn -pl lingshu-examples/demo-product spring-boot:run` 的 CWD = module basedir,
所以 `src/main/resources/mcp-servers/echo-stdio.py` 正好解析到
`lingshu-examples/demo-product/src/main/resources/mcp-servers/echo-stdio.py`。

另:`McpServerConfig` 10 个字段里**没有任何 cwd 字段**,所以 FR-003
(不得依赖子进程 cwd 的未声明假设)天然满足 —— 只需注释说明「CWD 继承」是
**已知且被依赖的行为**,不是隐式契约。

---

## 决策点 9:测试预算与 flake 控制

| 项 | 决策 |
|---|---|
| 单用例 wall clock | **≤ 2 s**;唯一例外是重连类(退避首跳 ≥ 1 s,见决策点 2) |
| `Thread.sleep` 用于「等某事发生」 | **禁用**,改 Awaitility |
| `Thread.sleep` 用于「让某事来不及发生」 | 可用,但必须 ≤ 200 ms 且配合可注入时间构造器 |
| 端口冲突 | fixture 用 `HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)` 让 OS 分配;`@AfterEach` `stop(0)` |
| 子进程泄漏 | fixture 必须注册 shutdown hook / `finally stop(0)`,否则 `mvn test` 挂住 |
| 并行执行 | 不依赖任何 JVM 全局 sysprop 表达**每用例**差异;仅 fixture 端行为开关走 sysprop |

---

## 决策点 10:`echo-stdio.py` 的 line-JSON framing 不在本 Story 动

`echo-stdio.py` L60-62 的 Javadoc 已自陈 line-delimited JSON 是**有意偏离**规范
`Content-Length` framing。这是**既有的、已被记录的**偏离,且 stdio transport
（`StdioMcpServerConnection`）按 line 读取,行为自洽。

**Decision**: 本 Story **不改** `echo-stdio.py`。FR-001 只要求「仓内相对路径可解析」,
不要求 stdio framing 也规范化。改 framing 会连带改 `StdioMcpServerConnection` 的
读循环 = 生产代码改动,打破「0 生产改动」承诺。

---

## Phase 0 收尾:全部 NEEDS CLARIFICATION 已消解

| 原 unknown | 消解于 |
|---|---|
| FR-010 fixture 是否合并 | 决策点 1 —— **合并** |
| 是否采纳可注入时间构造器 | 决策点 2 —— **采纳**,含 2 个下限约束 |
| `postJsonRpc` 死代码怎么处置 | 决策点 3 —— **保留 + 补覆盖**,记 ROADMAP 待办 |
| 断言如何观察 transport 实际发包 | 决策点 4 —— **新增请求记录器** |
| fixture 需要哪些行为开关 | 决策点 5 —— 收窄 + 新增 2 个 |
| 哪些既有测试会坏 | 决策点 6 —— 全表 78 判定完毕 |
| 测试基建缺什么 | 决策点 7 —— 4 项缺口逐项给处置 |
| application.yml 实际现值 | 决策点 8 —— 记录偏差 + 目标终态 + 根因 |
| 时间预算 / flake 风险 | 决策点 9 —— 全部有对策 |
| stdio framing 要不要一并规范化 | 决策点 10 —— **不**,记为有意偏离 |

**全部 unknowns 已消解,Phase 0 完成,可进入 Phase 1(设计 + 契约)。**