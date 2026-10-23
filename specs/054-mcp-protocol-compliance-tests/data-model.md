# Data Model: Story #054 MCP 协议合规测试收口

**Phase 1 / T007** | 消费 [plan.md](./plan.md) · [spec.md](./spec.md) · [research.md](./research.md) · [contracts/fixture-protocol.md](./contracts/fixture-protocol.md)
| 产出 [tasks.md](./tasks.md) T007 | 上游 T001—T006(Phase 0 pre-flight)已完成

---

## 0. 本文件的定位

Story #054 是**纯测试收口 Story** —— 生产代码 **0 行改动**(唯一生产侧改动是 demo-product
`application.yml` 1 行,缺陷 A)。因此「数据模型」在这里 **不是数据库 schema**,而是三组
**测试基建的数据结构**:

| # | 模型 | 用途 | 对应 FR / 决策 |
|---|---|---|---|
| 1 | **fixture 开关模型** | 子进程 fixture 的系统属性 → 规范行为的映射表 | FR-005—FR-010 / 决策 1、5 |
| 2 | **请求记录器模型** | fixture 对「transport 实际发了什么」的记账结构 | FR-006 / FR-011—FR-014 / FR-018 / 决策 4 |
| 3 | **断言可见性矩阵** | 每条 AC / 每个测试文件「能观察到什么」的对照 | SC-003、SC-004 |

三者互相咬合:**开关模型**决定 fixture 能演出哪些剧本 → **记录器模型**让剧本可断言 →
**可见性矩阵**保证每条 AC 都有至少一个「缺陷被重新引入时用例会失败」的观察点。

---

## 1. Fixture 开关模型(sysprop → 规范行为)

### 1.1 现状:两套 fork,7 个系统属性

Phase 0 实测(两个 fixture 源码逐行核对)得到的当前系统属性全集:

| sysprop | `TestMcpHttpServer` | `TestMcpSseServer` | 默认 | 现状语义 |
|---|---|---|---|---|
| `port` | ✅ | ✅ | `0` | 绑定端口;0 = OS 分配临时端口 |
| `delayMs` | ✅ | ✅ | `0` | 每个 handler 响应前 sleep N ms(模拟慢服务 / 触发读超时) |
| `exitAfter` | ✅ | ❌ | `-1` | 处理满 N 个请求后 `System.exit(0)`(模拟 OOM / 崩溃) |
| `pushIntervalMs` | ❌ | ✅ | `200` | SSE 每 N ms 推一帧 |
| `closeSseAfter` | ❌ | ✅ | `-1` | SSE 流通 N ms 后主动关闭(模拟反向代理踢线) |
| `malformedRatio` | ❌ | ✅ | `-1` | 每 N 帧推一次非 JSON `data:`(验证解析器容错) |
| `dontReplyHealth` | ✅ | ✅ | `false` | `/health` 返回 500 —— **随 `/health` 端点一起删除** |

**注意两个 fork 的属性集合并不相同**:`exitAfter` 只有 HTTP fixture 有,
`pushIntervalMs` / `closeSseAfter` / `malformedRatio` 只有 SSE fixture 有。
合并成单份 fixture(SC-006 / 决策 1)后必须取**并集**,否则现有的
`StdioMcpServerConnection` 崩溃重启用例会失去 `exitAfter` 这一个开关。

### 1.2 保留 6 个(语义不变,只换实现位置)

| sysprop | 类型 | 默认 | 归一化后的规范行为 | 谁在用 |
|---|---|---|---|---|
| `port` | int | `0` | 绑定端口;`0` = OS 分配 | 全部 IT;`StreamableHttpMcpServerConnection*` 需要固定端口的场景 |
| `delayMs` | int | `0` | **每个 handler(含 SSE POST 端点)** 响应前 sleep N ms | 超时路径用例(`postJsonRpc` / `postJsonRpcSpec` 的 `hbTimeoutMs` 触发) |
| `exitAfter` | int | `-1` | 处理满 N 个请求后 `System.exit(0)` | `StdioMcpServerConnection` 之外的「进程崩溃」剧本(崩溃重连) |
| `pushIntervalMs` | int | `200` | SSE 流每 N ms 推一帧 server push | server push → `tools/list_changed` → 重拉工具列表 |
| `closeSseAfter` | int | `-1` | SSE 流开通 N ms 后由 fixture 主动关闭 | 反向代理超时踢线 → 断连 → 重连 |
| `malformedRatio` | int | `-1` | 每 N 帧推一次非 JSON `data:`(`not-json-{{{`) | 解析器容错(非 JSON 帧必须被跳过而非炸掉) |

> `pushIntervalMs` / `closeSseAfter` / `malformedRatio` 只在 **SSE 模式**下有意义;
> 纯 Streamable HTTP 用例设不设都无害(fixture 里对应代码路径不触发)。

### 1.3 新增 3 个(本 Story 必须)

| sysprop | 类型 | 默认 | 归一化后的规范行为 | 服务的 AC |
|---|---|---|---|---|
| `expireSession` | **int 阈值** | `-1`(= 永不) | 第 N 个**带 `Mcp-Session-Id` 头**的请求起,**持续**返 **404**(模拟「服务端忘了会话」) | FR-008 / FR-020 / FR-021 / FR-022 · AC-054 会话恢复链路 |
| `noEndpointAnnounce` | boolean | `false` | `true` 时:`GET /sse` 连上后**不发 `event: endpoint` 帧**,直接开始推 `event: message` | FR-009 / FR-011 / FR-016 · AC-054 「端点缺失 ⇒ 断连」反例 |
| `omitProtocolVersion` | boolean | `false` | `true` 时:`initialize` 的 result **省略** `protocolVersion` 字段(测 FR-019 客户端兜底) | FR-019 · AC-054 协议版本兜底 |

> **⚠️ Phase 2 write-back(实证 > 文档)** —— 本节原设计为「`expireSession`
> boolean + 2 个新开关」。实施时 T011 落地后发现**两处 reality 与设计不符**:
>
> **(1) `expireSession` boolean → int 阈值(必须改)。**
> 原设计的 boolean 语义是「首次 `initialize` 之后一律 404」。
> 但 Streamable HTTP 的**握手本身也带会话头**:`StreamableHttpMcpServerConnection
> .doConnect()` 在 `initialize` 拿到 sessionId 之后,还要发
> `notifications/initialized` 与 `tools/list` —— 这两个都携带 `Mcp-Session-Id`。
> 于是「initialize 之后一律 404」会让 `CONNECTED` **永远达不到**:
> 用例根本进不了「已连上,再让会话过期」这个状态,只能测出
> 「连不上」。int 阈值才能表达**「先连上,再过期」**。
>
> 阈值语义(计数**只数携带非空 sessionId 的请求**;`initialize` 本身
> 不计数,因为客户端此刻还没有 sessionId):
>
> | 请求序 | method | 带 sessionId? | `sessionCount` | `expireSession=3` 的结果 |
> |---|---|---|---|---|
> | #0 | `initialize` | ❌(还没发) | — | **200 + 下发 `Mcp-Session-Id`** |
> | #1 | `notifications/initialized` | ✅ | 1 | 正常 202 ack |
> | #2 | `tools/list` | ✅ | 2 | 正常 200 + result |
> | #3 | `ping` | ✅ | 3 | **404** ⇒ 进入 FR-020 恢复分支 |
>
> 「持续 404」这个原设计意图**保留**:阈值命中后不再恢复,后续每个带
> 会话头的请求都 404 —— 这样才能验证 FR-021(重试**不得**再触发一次
> 重握手;若会无限循环,`callTool` 会卡到 `hbTimeoutMs` 超时失败而不是
> 静默通过)。
>
> **(2) 新增第 3 个开关 `omitProtocolVersion`。**
> T015 只点名了 2 个,但 `contracts/fixture-protocol.md` §0 明确要求
> 「测 FR-019 兜底分支需要专门 sysprop 控制」——`initialize` 响应里没有
> `protocolVersion` 时,客户端必须回落到默认值(「有头回显 / 无头用默认」,
> 见 §5 锁定决策 3)。没有这个开关,FR-019 的兜底分支**无法被测到**,
> 只能测到「有头回显」那半边。补 `omitProtocolVersion` 后两条分支都有开关。

`expireSession` 的**精确语义**(T011 实现时逐条对齐,已按 int 阈值校准):

- 只对**携带非空 `Mcp-Session-Id`** 的请求计数并可能返回 404;
  无 session 头的请求(如首次 `initialize`)永远不 404。
- 首次 `initialize` 必须正常 200 并下发 `Mcp-Session-Id` ——
  否则测试根本进不了「已连接」态。
- 计数用**每个 handler 私有的 `AtomicInteger`**(`sessionCount`),
  不能用进程级计数器:两个 transport 的握手请求数不同,共用一个计数器会串。
- 命中阈值后**持续 404**(见上)。

`noEndpointAnnounce` 的**精确语义**:

- SSE 流**必须**正常建立(200 + `Content-Type: text/event-stream`),
  否则测的就不是「端点缺失」而是「连不上」。
- `event: message` 帧照常推 —— 这样「等不到 endpoint 但流是活的」
  和「流本身死了」两种失败原因可区分。
- 不发 `event: endpoint` ⇒ `SseMcpServerConnection.doConnect()` 的
  `endpointLatch` 在 `hbTimeoutMs` 内不会被 countDown ⇒ 抛
  `IOException("no 'endpoint' event from SSE server within Nms")`。

### 1.4 删除 1 个 + 删 1 个端点

| 目标 | 处置 | 理由 |
|---|---|---|
| sysprop `dontReplyHealth` | **删除** | 它唯一的消费者是 `/health` 端点;FR-007 明令禁止 vendor 自创健康端点 |
| 端点 `GET /health` | **删除** | 心跳语义已改为 JSON-RPC `ping`(FR-015);留着就是给「生产代码已淘汰但测试还在测」留后门(缺陷 B 的病灶) |

**删除后心跳失败怎么测?** —— 靠 `expireSession=N`:心跳 `ping` 是第 N 个带 sessionId
的请求 → fixture 回 404 → `StreamableHttpMcpServerConnection.request()` 走 FR-020
恢复分支 → 若再失败则 `heartbeatTick()` catch → `handleDisconnect(...)` →
DISCONNECTED → RECONNECTING。
对 SSE 侧,心跳失败靠 `exitAfter=3`(握手恰好 3 个 POST,连上后服务器自杀,
下一个 `ping` 无处可去)+ `closeSseAfter` 关流 + `delayMs` 超时三条路径。
> **Phase 2 write-back**:SSE 侧原本想用 `dontReplyHealth` 测 5xx,
> 但该开关随 `GET /health` 一起被删除(FR-007),而 SSE 的 `ping` 走的是
> 已公布的 POST 端点 —— fixture 没有「对 ping 单独回 5xx」的开关,
> 所以改用 `exitAfter=3` 让服务器**在握手后立刻消失**,效果等价于
> 「心跳目标不存在」,且不需要新增开关。

### 1.5 绑定地址(硬约束,不是开关)

两个 fork fixture 当前都是 **wildcard bind**:

```java
// ❌ 现状(两个 fixture 相同)
HttpServer.create(new InetSocketAddress(port), 0);
```

必须改为 **IPv4 loopback**:

```java
// ✅ T011 目标
HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
```

理由:constitution §6 兼容矩阵要求三平台一致;wildcard bind 在 Windows WSL2 /
容器环境下会命中 IPv6 或被防火墙规则影响;而**测试代码里写字面量 `localhost`
更糟** —— Windows 的解析顺序(IPv6 优先)会让它连到 `::1` 而 fixture 只听 `127.0.0.1`。
唯一正确姿势是**两边都用 `127.0.0.1` 字面量**:fixture 绑它,测试的 `baseUrl` 也拼它。

### 1.6 启动协议(不变)

fixture 启动后第一件事仍必须往 stdout 打:

```
PORT=<n>
```

父进程从子进程 stdout 首行读端口(`McpHttpTestSupport.ProcessHandle`)。
这条协议 **T011 不改** —— 改它要动所有 fixture 启动方,收益为零。

---

## 2. 请求记录器模型

### 2.1 为什么需要(SC-003 的可执行性)

SC-003 要求「SSE 4 条链路各 ≥1 用例,**且缺陷被重新引入时用例会失败**」。
这句话的难点在后半句:现有 fixture **只回包不记账**,测试只能断言
「连接成功 / 拿到工具列表」这类**结果态**。

而下面这些恰恰是**过程态**的规范要求:

- 公告之前**不得**发任何请求(FR-011)
- 带 id 的必须是 request,不带 id 的必须是 notification(FR-014)
- `initialize` 之后每个请求都要**回放同一个 `Mcp-Session-Id`**(FR-018)
- 公告的必须是**相对 URI**,且客户端要按 SSE URL 解析(FR-012)
- 响应 `id` 必须**回显请求 id**,不能固定为 1(FR-006)

没有记账,这五类要求一条都断言不了,只能靠「肉眼看日志」。

### 2.2 数据结构

```java
/**
 * 一次入站请求的记账条目。fixture 侧构造,父进程侧只读断言。
 *
 * <p>JDK 8 兼容:普通 final class + final 字段 + 构造器,不用 record / var。
 */
public final class RecordedRequest {

    /** JSON-RPC method 名,例如 "initialize" / "tools/list" / "ping"。 */
    private final String method;

    /** JSON-RPC id;null 表示这是一个 notification(规范上不得带 id)。 */
    private final Long id;

    /** 请求头 Content-Type 原值,例如 "application/json"。 */
    private final String contentType;

    /** 请求头 Mcp-Session-Id 原值;null 表示该请求没带会话头。 */
    private final String sessionIdHeader;

    /** 请求体原文(未经解析),用于失败时打印上下文。 */
    private final String body;

    // 构造器 + 5 个 getter(getMethod / getId / getContentType
    //                     / getSessionIdHeader / getBody)
}
```

**5 个字段的取舍理由**:

| 字段 | 为什么必须有 | 谁断言它 |
|---|---|---|
| `method` | 断言「心跳是 `ping` 而不是 `GET /health`」的唯一依据;同时能断言请求**顺序** | FR-015 · FR-007 |
| `id` | 区分 request / notification(FR-014);断言 fixture 是按 id 回声而不是固定 1(FR-006) | FR-006 · FR-014 |
| `contentType` | 断言客户端真的按 `application/json` 发(与 `contracts/fixture-protocol.md` 的请求头契约对齐) | FR-023 |
| `sessionIdHeader` | **FR-018 的核心断言物** —— 第一个请求为 null、之后每个都等于 initialize 下发的那个 id | FR-018 · FR-019 |
| `body` | 断言失败时给开发者留现场;`tools/call` 用例还要从中取 `params.arguments` | 调试 / FR-029 |

> **刻意不记录的东西**:请求头 `Accept` / `MCP-Protocol-Version`。
> 理由:这两项由 `McpHttpSupport.postJsonRpcSpec` 硬编码拼装、不经过 fixture 可变逻辑,
> 属于**生产代码内部契约**,由 `McpHttpSupportPostJsonRpcSpecTest`(L1)直接断言即可,
> 不必让 fixture 记账(记账面越窄,fixture 越简单,SC-003 的反向证明越可靠)。

### 2.3 记录器的生命周期 API

```java
/** 进程内单例,由 fixture 的 handler 写、父进程通过查询端点读。 */
public final class RequestRecorder {

    /** 返回按到达顺序排列的全部记账条目快照(不可变副本)。 */
    public static List<RecordedRequest> recorded();

    /** 清空记账,用于「握手完成后再测心跳」这类分阶段断言。 */
    public static void clearRecorded();
}
```

**为什么是「子进程写、HTTP 查询」而不是「子进程打日志、父进程读 stdout」?**

1. `PORT=` 首行协议已经把 stdout 定位成「启动握手通道」,再塞业务日志会
   干扰首行解析;
2. 断言需要**结构化**数据(`method` / `id` 字段),日志是字符串,还得写解析器;
3. 查询端点让父进程能在**任意时刻**取快照,时间点可控(awaitility 轮询即可)。

**查询端点的形状(必须是 fixture 上的一个 GET,不得是新的业务语义端点)**:

```
GET /__recorded  →  200  application/json
   {"recorded":[{"method":"initialize","id":1,"contentType":"application/json",
                 "sessionIdHeader":null,"body":"..."}, ...]}
```

之所以叫 `/__recorded` 而不是 `/recorded` / `/requests`,是为了在 fixture 的
5 端点地图里一眼看出**这是测试探针、不是 MCP 协议端点**。
它不会与 MCP 规范冲突(`SseMcpServerConnection` / `StreamableHttpMcpServerConnection`
只 POST 自己的端点、只 GET `/sse`,永远不会误打这个路径)。

> **注意**:`/health` 删除后 fixture 的端点数从 6 降到 5(单端点 POST + `/sse` + `/__recorded`),
> 正好回到 SC-006「单份 fixture,重复数 0」的清爽状态。

### 2.4 记账的触发点(必须覆盖全部 4 类入站)

| 入站路径 | 记什么 | 注意 |
|---|---|---|
| Streamable HTTP 的**单端点 POST** | 解析 body → `method` / `id` | **同一条 POST 可能被 SSE 侧复用**(见下) |
| SSE 公告出来的**消息 POST 端点** | 同上 | 与上条走同一个 handler,记账逻辑只写一份 |
| `GET /sse` 本身 | **不记账**(它不是 JSON-RPC 请求) | 但需要在别处证明「公告到达之前没有记账条目」 |
| `GET /__recorded` | **不记账**(否则自证递归) | — |

**「公告之前零请求」怎么断言?** —— 这正是 FR-011 / SC-003 的核心反例,
实现方式是一个**时间点断言**而非一次记账断言:

1. `conn.start()`(SSE)发出 `GET /sse`;
2. fixture 收到 `GET /sse` 后,先记账一个**哨兵条目**
   `method = "__sse_opened"`(这不是 JSON-RPC,只是把「流已建立」这个时刻钉在序列里);
3. 父进程断言:`recorded()` 里 `__sse_opened` 的下标之后**必须**紧跟
   `method = "initialize"` —— 即公告之前零 JSON-RPC 请求;
4. `noEndpointAnnounce=true` 时,`initialize` **永远不出现** ⇒ 断言超时失败(FR-016)。

### 2.5 与可注入时间的配合

记录器**不引入任何时间依赖** —— 它是纯记账 + 纯查询。
所有时间相关断言由 `McpHttpTestSupport` 提供的 awaitility 轮询承担
(查询端点无副作用,可反复轮询)。

---

## 3. 断言可见性矩阵

### 3.1 可见性维度(「能观察到什么」)

| 代号 | 观察面 | 谁在观察 |
|---|---|---|
| **V1** | fixture 记账(`recorded()`)—— 方法名 / id / 请求头 / body | 父进程通过 `GET /__recorded` |
| **V2** | SSE 帧序列(`event:` 行 + `data:` 行) | SSE 客户端解析器 / 手动 socket 读取 |
| **V3** | HTTP 状态码(404 / 200 / 204) | 父进程直接发 HTTP,或由记账条目反推 |
| **V4** | 连接状态机(`ConnectionState` 6 态) | `McpServerConnection.state()` + `onStateChange` 监听 |
| **V5** | 对外错误文案(`LINGS-M03` / `[LINGS-S01]`) | `McpCallResult.error(...)` 返回串 / 异常 message |
| **V6** | 工具列表快照(`listTools()`) | 重连后是否重拉(FR-029) |
| **V7** | 请求头全量(客户端发出的) | 仅 L1 直接调 `McpHttpSupport.postJsonRpcSpec` |

### 3.2 FR / SC → 可见性 → 承载用例

| AC / FR | 要断言的规范点 | 可见性 | 承载文件(plan.md 已排期) |
|---|---|---|---|
| **FR-005** | 端点形状 = 单端点 POST + `GET /sse` + 公告 | V2 | `SseMcpServerConnectionStartTest` |
| **FR-006** | 响应**回显请求 id**,非固定 1 | V1 | `SseMcpServerConnectionStartTest` |
| **FR-007** | **无** vendor `/health`;心跳走 `ping` | V1 + V2 | `SseMcpServerConnectionHeartbeatTest` · `StreamableHttpMcpServerConnectionHeartbeatTest` |
| **FR-008** | 404 会话过期可开关 | V3 | `StreamableHttpMcpServerConnectionSessionRecoveryTest`(NEW) |
| **FR-009** | fixture 能在无公告下运行(不白屏) | V4 | `SseMcpServerConnectionReconnectTest` |
| **FR-010 / SC-006** | 单份 fixture,无 fork | — | 结构断言,`TestMcpHttpServer` 内注释 + 单测自检 |
| **FR-011** | 公告前**零请求** | V1(`__sse_opened` 哨兵) | `SseMcpServerConnectionStartTest` |
| **FR-012** | 相对 URI 按 SSE URL 解析 | V1(记到的 POST endpoint 路径) | `SseMcpServerConnectionStartTest` |
| **FR-013** | `event: message` 按 id 关联,**允许乱序 / 并发** | V1(多 id 并发记账) | `SseMcpServerConnectionStartTest` |
| **FR-014** | 带 id = request / 不带 id = notification | V1(`getId()` 是否 null) | `SseMcpServerConnectionStartTest` |
| **FR-015** | 心跳是 JSON-RPC `ping` | V1 + V4 | 两个 `*HeartbeatTest` |
| **FR-016** | 公告缺失 ⇒ 断连 | V4 + V5 | `SseMcpServerConnectionReconnectTest` |
| **FR-017** | 单端点,绝不 `url + "/tools/call"` | V1 | `StreamableHttpMcpServerConnectionStartTest` |
| **FR-018** | session id 记录 + 逐请求回放 | **V1(唯一直接观察面)** | `StreamableHttpMcpServerConnectionHeartbeatTest` |
| **FR-019** | 协议版本记录 + 回放 + 回退默认 | V1 + V7 | `StreamableHttpMcpServerConnectionStartTest` |
| **FR-020** | 404 + 有会话 ⇒ 清会话 + 重握手 + **同 id** 重试一次 | V1(两次同 id 记账)+ V3 | `*SessionRecoveryTest`(NEW) |
| **FR-021** | 重试**不得**再触发一轮恢复 | V1(记账条目数封顶) | `*SessionRecoveryTest`(NEW) |
| **FR-022** | 404 但**无**会话 ⇒ 不重握手 | V1 + V5 | `*SessionRecoveryTest`(NEW) |
| **FR-023** | 两种 content-type 都能解析 | **V7**(客户端侧) | `McpHttpSupportPostJsonRpcSpecTest`(NEW) |
| **FR-024** | 重连重新协商(不复用旧会话) | V1(第二次 initialize 的 id 递增)+ V3 | `StreamableHttpMcpServerConnectionReconnectTest` |
| **FR-025** | 既有测试保持通过、就地更新 | — | 全部既有测试文件 |
| **FR-026 / SC-007** | 0 新依赖 | — | `mvn dependency:tree` 镜像(见 quickstart) |
| **FR-027 / SC-007** | 0 新 ErrorCode | V5 | 全部用例只断言既有 `LINGS-M03` / `[LINGS-S01]` |
| **FR-028 / SC-009** | JDK 8 兼容 | — | 编译期(`<source>1.8</source>`)天然把关 |
| **FR-029** | 重连后重新拉工具 | **V6** | `StreamableHttpMcpServerConnectionReconnectTest` |
| **FR-030** | 每次出站 HTTP 前过白名单 | V5(`[LINGS-S01]` 前缀) | `McpHttpDomainGuardIT` |
| **SC-003** | 4 条 SSE 链路各 ≥1 用例**且缺陷重引入时会失败** | 由 §3.3 逐条担保 | — |
| **SC-004** | 恢复链路 + 不双重重试各 ≥1 用例 | V1 + V3 | `*SessionRecoveryTest`(NEW) |

### 3.3 SC-003 反向证明表(「缺陷重引入 ⇒ 用例必红」)

这是 T007 存在的主要理由。逐条列出**每条链路对应哪个观察面**,
使得「把生产代码改回缺陷形态」时,至少有一个断言会失败:

| SSE 链路 | 若缺陷被重新引入 | 会失败的观察面 | 会失败的断言 |
|---|---|---|---|
| **① 公告 → 端点解析** | 改回「自己猜 `/messages`」 | V1 | 记账里的 POST endpoint 路径 ≠ 公告给出的路径 |
| **② 端点 → 发 `initialize`** | 改回「先 POST 再 GET `/sse`」 | V1(`__sse_opened` 哨兵下标) | 哨兵之前出现 `initialize` |
| **③ `event: message` → id 关联** | 改回「按到达顺序取第一帧」 | V1(乱序场景) | 并发两个 id 时,先到的第二帧被错配 / 抛 `no matching JSON-RPC response` |
| **④ 无公告 → 断连** | 改回「用默认端点硬连」 | V4 | 状态机进入 CONNECTED 而非 DISCONNECTED/RECONNECTING |

同理,会话恢复链路的反向证明:

| 恢复场景 | 若缺陷被重新引入 | 会失败的观察面 | 会失败的断言 |
|---|---|---|---|
| **404 ⇒ 重握手 + 同 id 重试一次** | 改回「404 直接抛」 | V1 | 记不到第二次 `initialize` |
| **重试 ⇒ 不得再触发** | 改回「重试仍走 `request()`」 | V1 | 记账条目数 > 预期上限(无限增长 → 用例超时红) |
| **404 无会话 ⇒ 不重握手** | 改回「任何 404 都重握手」 | V1 + V5 | 多出一条 `initialize` 记账 / 错误文案从 `LINGS-M03` 变成别的 |

### 3.4 可见性的**盲区**(必须诚实记录)

不是所有规范点都有观察面。以下四点当前设计**覆盖不到**,用别的手段保证:

| 盲区 | 为什么观察不到 | 兜底手段 |
|---|---|---|
| `MCP-Protocol-Version` 头的逐请求回放 | 记录器刻意不记这个头 | `McpHttpSupportPostJsonRpcSpecTest` 用 L1 直接调 `postJsonRpcSpec` 并断言 `HttpURLConnection` 收到的请求头(V7) |
| SSE 帧里的 `retry:` 字段是否被尊重 | 生产代码明确忽略 `retry:` | 不做断言 —— 这不是规范要求,spec Edge-Case 也未列 |
| 域白名单在 **SSE 流建立前**是否已执行 | `openSseStream` 内部先 check 后 GET,外部看不到中间态 | `McpHttpDomainGuardIT` 断言「非白名单 ⇒ 连 `GET /sse` 都不发生」(用记账条数恒为 0 证明) |
| **「心跳超时」vs「握手超时」分不开** | `delayMs` 是**进程级**开关,对每个 JSON-RPC 端点一起生效;调大它,握手阶段就已经先超时了 | 用例注释里**显式声明这个局限**,断言只写「服务器慢于探针预算 ⇒ 观察到 `RECONNECTING`」,**不**声称验到了心跳本身超时(见 `SseMcpServerConnectionHeartbeatTest.probe_pingTimeout_disconnects` 的 Javadoc)。要真正隔离需要 per-method 的延迟开关 —— 属于 fixture 能力扩展,本 Phase 不做 |

> 记录器视野收窄是**刻意的**:每多记一个头,T011 的 fixture 就多一处可能写错的地方,
> 而写错的地方会变成**假绿**(断言通过但其实没验到东西)。宁可留一个诚实记录的盲区。

---

## 4. 三模型的咬合关系

```
       §1 开关模型                     §2 记录器模型
   (sysprop → 剧本)              (剧本 → 可断言的结构化事实)
          │                                    │
          │  expireSession=3                  │  recorded() 里有两条同 id 的
          │  noEndpointAnnounce=true          │  initialize + 一次同 id 重试
          ▼                                    ▼
   fixture 演出:404 / 无公告  ──────────────►  §3 可见性矩阵
                                              (哪条 FR 由哪个断言守)
                                                     │
                                                     ▼
                                              SC-003 / SC-004
                                        「缺陷重引入 ⇒ 用例必红」
```

**一句话总结**:开关模型管「能不能演出这个剧本」,记录器模型管「演出的剧本
能不能被看见」,可见性矩阵管「每条规范点是否至少有一个看得见的断言」——
三者缺一,SC-003 就是一句无法验证的口号。

---

## 5. 与后续 Phase 的接口(本文件锁定的契约)

> **编号口径**:`tasks.md` 的 task ID 为权威。本表已按 tasks.md 校正
> (原表把「透传」记为 T012 / 「记录器」记为 T013,与 tasks.md 不符)。

| 后续 task | 从本文件继承的约束 |
|---|---|
| **T011**(重写 `TestMcpHttpServer` 路由骨架) | §1 开关表 6 保留 + 2 新增 + 1 删除;§1.5 绑 `127.0.0.1`;§2.3 `/__recorded` 端点;§2.4 哨兵条目 |
| **T012**(SSE handler `GET /sse`) | §1.3 新增开关 `noEndpointAnnounce`;§1.5 绑 `127.0.0.1`;§2.4 记账触发点 |
| **T013**(Streamable handler `POST /mcp`) | §1.3 新增开关 `expireSession`;§1.6 `PORT=` 启动协议不变;§2.4 记账触发点 |
| **T014**(新增 `RecordedRequest` + `RequestRecorder`) | §2.2 的 5 字段;§2.3 的 2 方法(实例 `recorded()` / `clearRecorded()`)+ 静态 `RequestRecorder.fetch(baseUrl)` 父进程探针 |
| **T015**(收窄 sysprop 开关) | §1.1 的并集属性集;§1.2 保留 6 个;§1.3 新增 2 个;§1.4 删除 `dontReplyHealth` + `GET /health` |
| **T016**(删除 `fixture/TestMcpSseServer.java`) | §1.1 的并集属性集(删除面比 tasks.md 描述更大,见 §6 finding 2);`PORT=` 协议不变 |
| **T017**(`McpHttpTestSupport` 透传)**仅验证,零代码改动** | §1.1 的并集属性集;`PORT=` 协议不变 —— 实证:`McpHttpTestSupport.buildCommand` L65-80 已把每个 sysProp 以 `-D<k>=<v>` 传下去 |
| **T018—T021**(地址绑定 / 端口选择 / 可注入时间 / 编译冒烟) | §1.5 绑 `127.0.0.1`(`localhost` 字面量禁止);§3.2 的 AC → 文件映射;§3.3 的反向证明表 |

---

**Last updated**: 2026-10-23 · Phase 2 起点 · 编号按 tasks.md 校正