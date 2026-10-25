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
    - <ABSOLUTE_PATH>/lingshu-examples/demo-product/src/main/resources/application.yml
```

> ⚠️ **Phase 3 write-back —— 机器相关前缀已打码**:原始实测值是一条完整的 macOS
> 绝对路径(以 `/Users/<user>/` 开头的个人机器目录)。为避免个人目录结构随仓泄漏,
> 前缀已替换为 `<ABSOLUTE_PATH>` 占位符。
> **「它是绝对路径」这个事实本身没有变**,下面的结论不受影响。

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

### 8.1 Phase 3 write-back:T023 已实施(相对路径落地)

**实证 > 文档** —— 本决策点记录的「目标终态」已在 Phase 3 / US1 落地:

| 项 | 值 |
|---|---|
| 任务 | **T023**(US1 缺陷 A 修复) |
| 分支 | `054-mcp-protocol-compliance-tests` |
| 改动文件 | `lingshu-examples/demo-product/src/main/resources/application.yml` —— **仅 1 行**,yml 其余 0 改动 |
| 旧值 | `- <ABSOLUTE_PATH>/.../application.yml`(指向 yml 自己 ⇒ `python3` 立即语法错误退出 ⇒ stdio server **从来没起来过**) |
| 新值 | `- src/main/resources/mcp-servers/echo-stdio.py` |
| 守护测试 | `DemoProductStdioArgsPathTest`(T022 新建,3 case,0 新 Maven 依赖) |
| 实测 | T026 全绿(3/3)+ T025 端到端日志 `[MCP:echo-stdio] (re)connected, 2 tools registered`,无 `LINGS-M03` |

**FR-003 仍天然满足**:`McpServerConfig` 10 个字段**没有 cwd 字段**,
「CWD 继承」是**已知且被依赖**的行为(论证见本决策点上半段的 `spawn()` 代码),
不是未声明的隐式契约。

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

---

### 8.2 Phase 4 write-back:US2(SSE)已实施

> 追加于 2026-10-24。本节登记 Phase 4(T027—T036)实施期间的**实测现实**,
> 以及 `tasks.md` 原始文字与现实冲突之处。**冲突一律以现实为准**(continuation
> prompt §0 evidence-first 规则),差异在此逐条登记。

#### 8.2.1 T027—T036 落地清单

| 任务 | 落地位置 | 用例名 |
|---|---|---|
| T027 | `SseMcpServerConnectionStartTest:114` | `start_fakeServer_5stepsReachesConnected` |
| T028 | `SseMcpServerConnectionStartTest:213` | `handshake_relativeAnnouncedUri_resolvesAgainstSseOrigin` |
| T029 | `SseMcpServerConnectionStartTest:268` | `handshake_concurrentCalls_correlateByIdNotArrivalOrder` |
| T030 | `SseMcpServerConnectionStartTest:333` | `handshake_noEndpointAnnounce_neverConnects` |
| T031 | `SseMcpServerConnectionStartTest:377` | `heartbeat_usesJsonRpcPing_neverCallsHealthEndpoint` |
| T032 | `SseMcpServerConnectionHeartbeatTest:85/129/167/204` | `probe_pingOk` / `probe_pingServerGone_disconnects` / `probe_pingTimeout_disconnects` / `probe_doubleCheck_recoveredHeartbeat` |
| T033 | `SseMcpServerConnectionHeartbeatTest`(类级 Javadoc)+ `McpStateLog` | 见 §8.2.4 |
| T034 | `SseMcpServerConnectionReconnectTest:142` | `sseStreamBroken_reconnects`(另 2 例 :84 / :111 同步更新) |
| T035 | `SseMcpServerConnectionCloseAndCallTest:94/120/178/202/242` | 5 例全部重写 |
| T036 | 反证抽查(见 §8.2.6) | — |

**Phase 4 产出文件(9 改 + 1 新增,0 生产代码改动)**:

| 文件 | 性质 | 行数变化 |
|---|---|---|
| `SseMcpServerConnectionStartTest.java` | 改 | 148 → **684** |
| `SseMcpServerConnectionHeartbeatTest.java` | 改 | 162 → **249** |
| `SseMcpServerConnectionReconnectTest.java` | 改 | → **211** |
| `SseMcpServerConnectionCloseAndCallTest.java` | 改 | → **269** |
| `McpStateLog.java` | **新增**(package-private 测试 helper) | **137** |
| `McpHttpTestSupport.java` | 改 | +18 行(`pickClosedBaseUrl()`) |
| `fixture/TestMcpHttpServer.java` | 改 | → **1146**(4 个新开关 + 10 处新内部) |
| `SseMcpServerConnectionListenerTest.java` | 改(T033) | — |
| `RawSseObserver.java` | 改(T027/T028/T029 观测端) | — |

`lingshu-core/src/main/java/ai/lingshu/core/mcp/**` — **0 行改动**(硬约束守住)。

#### 8.2.2 任务文字与现实的冲突(3 处,逐条更正)

| # | tasks.md 原文 | 实测现实 | 处置 |
|---|---|---|---|
| A | T030:「断言 `start()` 抛 `IOException` 且消息含 `no 'endpoint' event` 语义」 | **`start()` 不抛异常**。`SseMcpServerConnection.start()` 是异步的,握手失败走 `handleDisconnect` → `RECONNECTING` 状态机,异常只在**后台线程**被吞掉。`IOException` 从来到不了调用方。 | 用例改为断言**可观测的**语义:① 状态进入 `RECONNECTING` ② 在 600 ms 窗口内**永不**进入 `CONNECTED` ③ fixture 侧**零** JSON-RPC 请求被发出(`recorded()` 里每条 `id == null` 且无 `notifications/` 前缀)。这是「`no 'endpoint' event` 语义」在生产实现下**唯一**可黑盒验证的形式。 |
| B | T029:「连发 2 个 JSON-RPC 请求(如 `initialize` 与 `tools/list`),fixture 按相反顺序推 2 个 `event: message`」 | **握手是串行的**。`initialize` 必须先完成才能发 `tools/list`,两个请求不可能并发 ⇒ 「相反顺序推送」在握手阶段无法构造。 | 改为:握手完成后**并发**发 2 个 `tools/call`(线程池 2 线程),fixture 用 `outOfOrder=true` 缓存 2 个应答并**按相反顺序**推。断言 ① 两个 `callTool` 结果各自拿到**自己的** input(`alpha` / `beta`,fixture 回显 input)② wire 上 2 个 answer id 的顺序**确实是请求到达顺序的反序**。 |
| C | T032:「重写 `SseMcpServerConnectionHeartbeatTest.java` 全部 4 个死代码用例(该文件 162 行)」 | Phase 4 结束后该文件为 **249 行**(重写 + Javadoc 扩写)。 | 行数在 quickstart.md 中同步更正(见 §4.3.2 write-back)。 |

#### 8.2.3 生产代码的「notification ≠ transition」 wart(本 Phase 最重要的发现)

`SseMcpServerConnection` 有两处**绕过**状态机守卫的公告路径:

```java
// L768 —— relistTools() 内,直接调 notifyListeners,绕过 transition()
notifyListeners(ConnectionState.CONNECTED);

// L779 —— transition() 内,有 prev != next 守卫
private void transition(ConnectionState next) {
    ConnectionState prev = state.getAndSet(next);
    if (prev != next) { notifyListeners(next); }
}
```

**第一层(重复公告)**:`relistTools()` 在收到 `notifications/tools/list_changed` push 后重拉一次
`tools/list`,然后**无条件**再公告一次 `CONNECTED` —— 状态根本没变。⇒ 任何「数 CONNECTED
通知个数」的测试都会把**每一次 server push** 误计成一次连接。

**第二层(陈旧公告)**:`relistTools()` 入口检查 `state.get() == CONNECTED`,然后做一次**阻塞**的
`tools/list` POST,再重新公告。这中间连接可能已经掉了(reader 线程走
`DISCONNECTED → RECONNECTING`),此时 pending 的 relist 仍然公告 `CONNECTED` —— 描述的是一个
**已经不存在**的连接。

**实测触发点**:`SseMcpServerConnectionCloseAndCallTest.close_idempotent` 曾报
`expected: 1 but was: 2`。根因是第二层(陈旧公告),不是「提前重连」——
**判别证据**:同一断言块里的 `initializesAtClose == 1` 成立 ⇒ fixture 侧只收到过 1 次
`initialize`,握手没有跑过第二次 ⇒ 连接数虚高只可能来自通知流里的陈旧 `CONNECTED`。

**处置(0 生产代码改动)**:新增测试 helper `McpStateLog`,在**测试侧**做两道过滤:

1. 「重复公告」过滤 —— 通知与上一条相同 ⇒ 不是 transition;
2. 「陈旧 CONNECTED」过滤 —— `state == CONNECTED` 但 `conn.state() != CONNECTED`
   ⇒ transport 自己的 `state()` 才是真相,没有活连接可数。

同时 `sseStreamBroken_reconnects` 由「await 完立刻断言」改为「await → 再 await 第二个
CONNECTED → 才断言」,避免 fixture 记录 `initialize` 与 transport 状态迁移之间的竞态。

#### 8.2.4 T033(Listener 测试)—— 落在 `McpStateLog` 上

T033 原文要求「更新 `SseMcpServerConnectionListenerTest.java` 的 3 个用例」。实测该文件原本
记录的是**原始 listener 流**(含 `relistTools()` 的重复/陈旧公告),与 §8.2.3 的 wart 直接冲突。
处置:**新增 `McpStateLog` 作为唯一的 transition 观察入口**(原始流仍保留在
`notifications()` 里,便于诊断),3 个 listener 用例改为经 `McpStateLog` 断言 —— 这样
「断言的是真 transition」这一条**在结构上**成立,而不是靠每个用例自己记得过滤。

#### 8.2.5 Phase 4 新增的 fixture 开关

`fixture/TestMcpHttpServer.java` 本 Phase 新增 4 个开关(全部经 `strProp` / `intProp` /
`boolProp` 读取,`McpHttpTestSupport` 原样转发为 `-D<key>=<value>`):

| 开关 | 类型/默认 | 用途 |
|---|---|---|
| `pushNotifications` | boolean / **true** | `false` 时,把周期性 push 换成一条 SSE **注释** keepalive(仍产出字节,客户端不会卡在一个永不到来的 chunk 上,但不再被诱导去重发 `tools/list`)。心跳类用例**必须**关:否则每次 push 触发一次 `tools/list` 重拉,`exitAfter=4` 的槽位会被 `tools/list` 抢走,心跳 ping 反而打不出去 ⇒ 请求日志计数不再确定。 |
| `delayAfter` | int / -1 | 从第 N 个请求**开始**才延迟。配 `delayMs` 用,让「握手快、探活慢」可分离 —— 全进程延迟会让握手本身先超时,「心跳超时」就永远无法被单独观测到。 |
| `announceUri` | String / `SSE_POST_URI` | 公告帧 `event: endpoint` 的 data 内容(可写相对 URI)⇒ T028 相对 URI 解析用例。 |
| `outOfOrder` | boolean / false | 缓存 2 个应答并**按相反顺序**推 `event: message` ⇒ T029 id 关联用例。 |

配套内部改动:`pathOf(HttpExchange)` / `writeComment(String)` / `writeRaw(String)` /
`SsePostHandler(Config, AtomicInteger, boolean jsonRpcOnly)` /
`RecordedRequest.getHttpMethod()/getPath()/getHost()` / `OUT_OF_ORDER_BUFFER` /
`recordAndBufferOutOfOrder(...)` / `startOutOfOrderFlusher()` / `applyDelay(...)` /
`echoedInput(JsonNode)`。

> **注意**:`noEndpointAnnounce` 开关**不是** Phase 4 新增的 —— 它是 Phase 2 就有的,
> Phase 4 的 T030 只是在**使用**它。

**T029 的一次 fixture race(已修)**:`recordAndBufferOutOfOrder` 的「先记录、再入 buffer」
两步**不原子** —— 两个并发请求可能交错,导致 fixture 记录的顺序与 buffer 的顺序不一致,
应答按 buffer 序回、记录却是乱的。修法:把「记录 + 入 buffer」合并进**同一个同步方法**。

#### 8.2.6 T036 反证抽查(SC-003 交付证据)

**RED 半边** —— 临时把 fixture 的 `event: endpoint` 首帧注释掉:

```
Tests run: 24, Failures: 2, Errors: 13 — BUILD FAILURE   (1:44 min)
```

**15 / 24 变红**,含 T027(`StartTest:128`)/ T028(`:221`)/ T029(`:279`)/ T031(`:395`)。

**9 个保持绿**,分两类:

- **T030 保持绿 —— 这是**正确**结果**。T030 本身就是「服务端不公告 endpoint」的用例:它以
  `noEndpointAnnounce=true` 起 fixture,断言连接**永不**进入 `CONNECTED`。把 fixture 首帧
  注释掉,**不改变 T030 的前提** ⇒ 它当然还绿。
  ⇒ **`tasks.md` 的字面要求「确认 T027—T030 的用例变红」不可能成立** ——
  T030 与「首帧被注释」这两个条件是**互斥**的:破坏首帧不可能让「断言首帧缺失导致失败」的
  用例变红。SC-003 要的「缺陷被重新引入时必失败」在 T030 上**天然成立**(它本来就要求
  缺陷存在),不需要、也不应该通过破坏 fixture 来验证。
- 其余 8 个与握手无关,因此保持绿:`close_beforeStart_noException` /
  `callTool_beforeStart_returnsError` / `ctor_wrongTransport_throws` / `backoff_sequence`
  (纯函数)/ `reconnect_unbounded` / `start_invalidUrl_immediateFailureSchedulesReconnect` /
  `start_missingUrl_illegalArgumentOrReconnect` / `start_duringReconnecting_noop`。

**GREEN 半边** —— 改回后 fixture md5 = `2837b222686baff7556af9a2a294fbb5`,与破坏前
**逐字节相同**;`git diff --stat` 与破坏前一致 ⇒ **无残留**:

```
Tests run: 24, Failures: 0, Errors: 0 — BUILD SUCCESS   (40.133 s)
```

#### 8.2.7 Phase 4 收尾自查

| 项 | 结果 |
|---|---|
| `mvn -pl lingshu-core test -Dtest='ai.lingshu.core.mcp.**'` | **24 run / 0 fail / 0 error** |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/**` 改动 | **0 行** |
| 新增 ErrorCode | **0** |
| 新增 Maven 依赖 | **0** |
| 新增测试 helper | 1(`McpStateLog.java`,package-private) |
| JDK 8 兼容 | ✅ 无 `var` / `List.of` / `record` / text block;用 `Arrays.asList` / `Collections.synchronizedList(new ArrayList<>())` |

---

### 8.3 Phase 5 write-back:US3(Streamable HTTP)已实施

> 追加于 2026-10-25。本节登记 Phase 5(T037—T047)实施期间的**实测现实**,
> 以及 `tasks.md` 原始文字与现实冲突之处。**冲突一律以现实为准**(continuation
> prompt §0 evidence-first 规则),差异在此逐条登记(与 §8.2 同规格)。

#### 8.3.1 T037—T047 落地清单

| 任务 | 落地位置 | 用例名 |
|---|---|---|
| T037 | `StreamableHttpMcpServerConnectionSessionRecoveryTest`(新文件) | `t037_oneShot404_clearsSessionAndRetriesWithSameId` |
| T038 | 同文件 | `t038_persistent404_retriesOnceThenSurfacesError` |
| T039 | 同文件 | `t039_404WithoutLocalSession_doesNotRehandshake` |
| T040 | 同文件 | `t040_transport_readsSseShapedAnswers` / `t040_sameRequestTwoContentTypes_parseToTheSameResult` |
| T041 | `McpHttpSupportPostJsonRpcSpecTest`(新文件,12 例) | — |
| T042 | `StreamableHttpMcpServerConnectionStartTest`(4 例) | 单端点握手 + `Mcp-Session-Id` 回放 |
| T043 | `StreamableHttpMcpServerConnectionHeartbeatTest`(3 例) | JSON-RPC `ping` + session 记录与回放 |
| T044 | `StreamableHttpMcpServerConnectionReconnectTest`(4 例) | 含**去 flake** 的 `reconnect_afterSessionExpiry_reRunsHandshake` |
| T045 | `StreamableHttpMcpServerConnectionCloseAndCallTest`(5 例) | 全部重写(见 §8.3.3) |
| T046 | `McpHttpDomainGuardIT`(4 例) | 见 §8.3.4 |
| T047 | 反证抽查(见 §8.3.5) | — |

**Phase 5 产出文件(6 改 + 2 新增,0 生产代码改动)**:

| 文件 | 性质 | 行数(实测) |
|---|---|---|
| `StreamableHttpMcpServerConnectionSessionRecoveryTest.java` | **新增** | **373** |
| `McpHttpSupportPostJsonRpcSpecTest.java` | **新增** | **474** |
| `StreamableHttpMcpServerConnectionStartTest.java` | 改 | → **221** |
| `StreamableHttpMcpServerConnectionHeartbeatTest.java` | 改 | → **196** |
| `StreamableHttpMcpServerConnectionReconnectTest.java` | 改 | → **420** |
| `StreamableHttpMcpServerConnectionCloseAndCallTest.java` | 改 | → **345** |
| `McpHttpDomainGuardIT.java` | 改 | → **300** |
| `fixture/TestMcpHttpServer.java` | 改 | 1146 → **1249**(4 个新开关 + 方法作用域过期) |
| `McpHttpTestSupport.java` | 改 | → **195** |
| `McpStateLog.java` | 沿用 Phase 4 | **136** |

`lingshu-core/src/main/java/ai/lingshu/core/mcp/**` — **0 行改动**(硬约束守住;
T047 的临时改坏已逐字节还原,见 §8.3.5)。

#### 8.3.2 任务文字与现实的冲突(7 处,逐条更正)

与 `tasks.md` 的偏差 D—J 一一对应(此表是**根因**版,tasks.md 是**摘要**版):

| # | tasks.md 原文 | 实测现实 | 处置 |
|---|---|---|---|
| D/E | T037/T038「以 `expireSession=true` 起 fixture」/「过期后重握手拿到的新 session 也立刻过期」 | `expireSession` 是**带 session 头的请求**的全局单调阈值(`sessionCount.incrementAndGet() >= expireSession`)。而 404 触发的**重握手**里,`initialize` 之后的 `notifications/initialized` **通知**是**带 session 头**的(它骑在刚拿到的新 session 上)⇒ 重握手自身就会先 404 ⇒「同一个 id 出现 2 次:首次 404、第二次成功」**不可达** | fixture 加**方法作用域**开关 `expireMethod=<method>` + `expireMethodCount=<n>`:只让被点名的 JSON-RPC 方法 404,**握手流量完全健康**。`expireSession` 语义**一字未改** |
| F | T040「同一请求分别以 `Accept: application/json` 与 `Accept: text/event-stream` 发起」 | `McpHttpSupport.postJsonRpcSpec` 把 `Accept` 写死为 `application/json, text/event-stream`,**收窄的 Accept 头无法经 transport 驱动** | 拆 2 例:① transport 侧用 fixture `respondSse=true`(让**服务端**以 SSE 形状应答)② 线缆侧用裸 HTTP 发 2 个收窄 Accept 的 POST,断言两种 content-type 解出**同一** JSON-RPC 结果 |
| G | T039「本地本来就没有活跃 sessionId」 | 需要服务端**从不签发** `Mcp-Session-Id` | fixture 加 `omitSessionId=true`(`initialize` 应答不带 session 头) |
| H | T044「降掉 `Thread.sleep`,改 Awaitility」 | **只换 Awaitility 不够**。原闸门 `await(CONNECTED)` 用 Awaitility **默认 100 ms 轮询**,首次求值落在 ~200 ms —— **晚于**第 1 次心跳(~190 ms)把状态翻成 `RECONNECTING`;闸门于是阻塞到重连完成,那时 `initialize` 已是 3 ⇒ 两个测量点同时看到 3 ⇒ 距离塌成 ~20 ms。**实测 8 次跑出 4 红** | 3 件事同时做:① **把抖动做成确定性的**(`expireMethodCount=2`,只抖一次,请求序列固定)② **删掉有竞态的 `await(CONNECTED)` 闸门**,改为同步到 `initialize` 计数 ③ **时间锚点换成 transport 自己的 `onStateChange` 时间线**(`System.nanoTime()` 在 transport 线程内取)⇒ 客户端轮询停顿**结构上不可能**污染测量。**断言未被弱化**:仍是 `>= 900 ms` + `< 5000 ms` |
| I | T045「陈旧 URL 统一换 `cfg.getUrl()`」 | 这 3 条用例要的是**连不上的端点**;`cfg.getUrl()` 是**活着的** fixture 地址,语义相反 | 统一换 `McpHttpTestSupport.pickClosedBaseUrl() + "/mcp"`(Phase 4 已落地的同一写法)。**"换 `cfg.getUrl()`" 作废** |
| J | T046「补成正向断言」/「补上『真实 HTTP 请求未曾发出』的断言」 | ① 正向用例**本来就**断言 `CONNECTED`,但只验了**状态机的自述**,没验「握手真的发生了」;② 3 条负向用例的**旧名承诺了代码给不出的东西**:`callToolDenied` 从不调用 `callTool`(且永远看不到 `[LINGS-S01]` —— `callTool` 在状态检查处短路,到不了 guard);`accessDeniedThrown` 从不观察异常(`start()` 吞掉) | ① 补「fixture 侧确实收到握手」+「一次 `tools/call` 真的往返」② 每条负向用例补「server 侧记录到 **0 条**请求」③ **改名** 2 条(登记进 T049 清单;**未删任何断言**) |

#### 8.3.3 T045:为什么「真实断连」必须靠 fixture 的**方法作用域**过期

Streamable HTTP 没有 SSE 那种「服务端可以主动断流」的长连接 —— 它的连接生命周期**只**由
**心跳 `ping`** 决定。所以要让连接「真的掉线」,唯一可控的手段就是让服务端对 `ping` 返 404。

`expireMethod=ping` + `expireMethodCount=2` 产生的**确定性**序列:

```
ping#1         -> 404 -> 就地重握手(initialize#2)
retried ping#1 -> 404 -> 连接掉线 -> DISCONNECTED -> RECONNECTING(1000 ms 退避)
reconnect      -> initialize#3 + tools/list#2 -> CONNECTED
```

⇒ `initialize` 恰好 3 次、`tools/list` 恰好 2 次、两条抖动 ping **共用同一个 JSON-RPC id**。
这让「重试**恰好一次**」成为**可精确计数**的断言,而不是「等到第 N 个 ping」的时间竞速。

**`close_idempotent` 的时间预算处理**:该用例要在 `close()` 之后**等一段窗口**,证明那个
已排期的重连**没有**发生。默认退避地板是 1000 ms,窗口就要 1.2 s,单用例 wall clock
**2.165 s —— 超了 2 s 预算**。处置:`reconnectCapMs` 的语义是 `min(delay, cap)`
(`computeBackoffMs` **只缩短、不延长**),故把 cap 降到 **400 ms**、窗口降到 **700 ms**:
被测契约(「关掉的连接永不回来」)与退避长度**无关**,缩短后单用例 **1.642 s**。
`callTool_afterDisconnect` 反过来**保留 1000 ms 地板** —— 那里要求调用发生时连接**仍**处于
`RECONNECTING`,窗口越宽越稳。

**`close_duringConnecting` 的一个观察(不在本次修复范围)**:transport 的 `start()` 是
**同步**的(`doConnect()` 在调用线程里跑完),所以「close 发生在 CONNECTING 期间」在
生产实现里**无法**用一个同步 `start()` 构造出来。代码阅读还发现:`doConnect()` 结尾
**没有** `closing` 检查 ⇒ 若 `close()` 落在 `doConnect()` 的 HTTP 往返**之间**,那个在途握手
结束后仍会 `transition(CONNECTED)`,把 `FAILED` 覆盖掉。这是**生产侧的小 wart**,
但本次**不动生产代码**(0 行改动硬约束),故只在用例里保持「close 之后状态必须精确是 `FAILED`」
这一层断言,并在此登记,留给后续 Story 裁决。

#### 8.3.4 T046:3 条 vacuous 负向用例的补强方式

负向用例原本只断言「状态是 `RECONNECTING`」—— 而 `RECONNECTING` 恰恰是「URL 校验**短路**了」
的结果,所以这些断言**什么都没证明**。

补强手段是 fixture 的**记录器**(`GET /__recorded`):负向用例里 server 是**真的且可达**的,
所以任何漏过 guard 的请求都**一定**会被记录 ⇒ 断言「记录到 **0 条**请求」**不可能空过**。
正负两侧构成对偶:同一个 fixture、同一个 server,白名单放行 ⇒ 记录到 `initialize` + SSE 开启
哨兵 + 一次真的 `tools/call` 往返;白名单拦截 ⇒ 记录到 **0** 条。

#### 8.3.5 T047 反证抽查(SC-004 交付证据)

**任务文字的两半,只有一半需要「临时改坏」**。T038 的 fixture 本来就是**持续** 404
(`expireMethodCount=-1`),真正要改坏的是「允许 transport 再重试」——「恰好重试一次」是
**transport 侧**契约,改 fixture 不可能让它变红(包的数量由 transport 的重试次数决定)。

**RED 半边** —— 临时把 `StreamableHttpMcpServerConnection.request()` 的「重试一次」改成
「重试 3 次」:

```
Tests run: 49, Failures: 2, Errors: 2 — BUILD FAILURE
```

T038 的原始失败信息(逐字):

```
=== t038_persistent404_retriesOnceThenSurfacesError
[one original attempt + exactly one retry — the retry must not loop]
Expected size: 2 but was: 4 in:
[#4  tools/call id=3 session=session-1 POST /mcp,
 #7  tools/call id=3 session=session-2 POST /mcp,
 #10 tools/call id=3 session=session-3 POST /mcp,
 #13 tools/call id=3 session=session-4 POST /mcp]
```

同一个 JSON-RPC id=3 骑了 **4 个**不同的 session —— 「恰好重试一次」被破坏,用例如实变红。

另外 3 条变红的是同一契约的其它守护者:`reconnect_afterSessionExpiry_reRunsHandshake`
(`[the failed heartbeat must have knocked the connection down]`)、
`close_idempotent` 与 `callTool_afterDisconnect_returnsError`(等不到 `RECONNECTING`)。
**T037 / T039 / T040 保持绿是正确的**(T037 的 404 是一次性的、T039 没有本地 session、
T040 与重试次数无关)。

**GREEN 半边** —— 改回后该生产文件 md5 = `3ff7037cbbdef3d19bc9252700bc7534`,与改坏前
**逐字节相同**;`git diff --stat -- lingshu-core/src/main/java/ai/lingshu/core/mcp/` 为**空**:

```
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

#### 8.3.6 Phase 5 收尾自查

| 项 | 结果 |
|---|---|
| `mvn -pl lingshu-core test -Dtest='StreamableHttpMcpServerConnection*,McpHttpSupport*,McpHttpDomainGuardIT'` | **49 run / 0 fail / 0 error / 0 skip** |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/**` 改动 | **0 行**(T047 临时改坏已逐字节还原) |
| 新增 ErrorCode | **0**(复用 `LINGS-M03` / `[LINGS-S01]`) |
| 新增 Maven 依赖 | **0** |
| 单用例 wall clock | 全部 ≤ 2 s(最慢 `sseNonWhitelisted_neverConnected` 1.63 s / `close_idempotent` 1.642 s) |
| JDK 8 兼容 | ✅ 无 `var` / `List.of` / `record` / `sealed` / text block |
| 稳定性抽查 | T045 连跑 5 次全绿;T046 连跑 3 次全绿;**T044 修复后连跑 10 次全绿**(修复前 8 跑 4 红) |