# Fixture 线上契约 —— `TestMcpHttpServer` 合并版

> **Story #054 / Phase 1 / T008**
>
> **契约方向**:fixture **对 transport** 扮演「**一个规范 MCP server**」。本文件描述的是
> **fixture 必须在线上吐出什么**,而 `SseMcpServerConnection` /
> `StreamableHttpMcpServerConnection` / `McpHttpSupport` 三者**实际会发什么、会怎么解**。
>
> **对齐来源(不是印象)**:本文每一条都对照生产代码逐行读过:
> - `McpHttpSupport.java`(631 行)—— HTTP/JSON-RPC 编解码 single source of truth
> - `SseMcpServerConnection.java`(792 行)—— SSE 握手 / id 关联 / 心跳
> - `StreamableHttpMcpServerConnection.java`(648 行)—— Streamable HTTP 会话 / 404 恢复
>
> **配套**:`../data-model.md` 定义 fixture 开关模型 + 请求记录器模型;
> 本文件只讲**线上帧形状与响应头形状**。

---

## §0 三个钉死的协议常量

以 `McpHttpSupport.java` L87–93 的 **实际值**为准(不是 MCP 文档的常见写法,也不是旧 fixture 的值):

| 常量 | 字段 | 实际值 | 出处 |
|---|---|---|---|
| `McpHttpSupport.PROTOCOL_VERSION` | `static final String` | `"2025-06-18"` | L87 |
| `McpHttpSupport.HEADER_PROTOCOL_VERSION` | `static final String` | `"MCP-Protocol-Version"` | L88 |
| `McpHttpSupport.HEADER_SESSION_ID` | `static final String` | `"Mcp-Session-Id"` | L89 |

三者在生产代码里是 **package-private**,fixture 在 `ai.lingshu.core.mcp.fixture` 包下
**拿不到**——所以 fixture 必须**字面量硬编码**这三个字符串,且值必须与上表完全一致。
(不一致时不会有编译错误,只会有断言在运行时炸,这是本 Story 要消灭的静默漂移。)

### ⚠️ 已知不一致 —— T011 要修

**现状**:`TestMcpHttpServer` 与 `TestMcpSseServer` 的 initialize 响应都报
`"protocolVersion": "2024-11-05"`,而生产 `PROTOCOL_VERSION = "2025-06-18"`。

- 严格说 `2024-11-05` 是一个**合法的历史 MCP 版本**,服务端可以主动降级协商,不算协议违规;
- 但它让 **FR-019「协议版本记录 / 回放 / 缺省兜底」**永远走不到 fallback 分支
  (`result.path("protocolVersion").asText("")` 只会拿到非空串),
  也让「fixture 与生产是否同版本」这件事无法一眼看出。

**T011 处置**:fixture 的 `initialize` result **回显 `MCP-Protocol-Version` 请求头的值**
(没有该头时回 `2025-06-18`),这样:
- 正常握手 ⇒ fixture 报 `2025-06-18`,与生产默认一致;
- 测 FR-019 的「服务端不返回 protocolVersion ⇒ 客户端兜底 `PROTOCOL_VERSION`」时,
  才需要 fixture **故意不返回**该字段——那由一个专门的 sysprop 开关控制,而不是
  「fixture 常年报一个别的版本」这种隐式行为。

---

## §1 SSE wire 契约

对应 `SseMcpServerConnection` + `McpHttpSupport` 的 SSE 分支。

### §1.1 端点拓扑:SSE transport 是 **2 个端点**,不是 1 个

```
                          ┌──────────────────────────────┐
   client ──GET /sse─────▶│  长连接 text/event-stream     │
         (cfg.url +"/sse")│  首帧必须是 event: endpoint    │
                          └───────┬──────────────────────┘
                                  │ event: endpoint
                                  │ data: <POST 端点 URI>
                                  ▼
                          ┌──────────────────────────────┐
   client ──POST ─────────▶│  server 公告的那一个 URI      │
                          │  (所有 JSON-RPC 都打这里)     │
                          └──────────────────────────────┘
```

**`cfg.url` → SSE 流的 URL 解析规则**(`resolveSseUrl`,static,L397–402):

```java
static String resolveSseUrl(String url) {
    if (url.endsWith("/sse")) { return url; }
    return url.endsWith("/") ? url + "sse" : url + "/sse";
}
```

⇒ fixture 的 SSE 流必须挂在 `/sse`(`cfg.url` 写 `http://127.0.0.1:<port>/sse`
或 `http://127.0.0.1:<port>` / `.../` 三种写法都落到同一个 `/sse` 上下文)。

### §1.2 `GET /sse` 请求头(fixture 必须**接受**,生产确实会发)

`openSseStream`(L734–749)按下述顺序设置:

| 头 | 值 | 生产是否强依赖 |
|---|---|---|
| `Accept` | `text/event-stream` | 否( fixture 记录即可,不据此分流) |
| `Cache-Control` | `no-cache` | 否 |

另:`connection.setReadTimeout(0)` —— **SSE 读超时为无限**。这是 fixture 心跳失败用例的
前提:连接会**永远挂着**,不会自己超时(见 `../data-model.md` §1.3 `noEndpointAnnounce`)。

**FR-030 域守卫**:生产在 `openSseStream` 之前先 `McpHttpSupport.checkOrThrow(sseUrl, whitelist)`,
**domain 不通则一个字节都不会发**。fixture 在白名单用例里绑 `127.0.0.1`,
配置侧 `McpServerConfig.domainWhitelist(Arrays.asList("127.0.0.1"))` 放行。

### §1.3 `GET /sse` 响应头(fixture **必须**给)

```java
ex.getResponseHeaders().set("Content-Type", "text/event-stream");
ex.getResponseHeaders().set("Cache-Control", "no-cache");
ex.sendResponseHeaders(200, 0);   // 0 = chunked / 不定长
```

- `Content-Type: text/event-stream` —— 缺失会让生产侧 SSE 读取路径直接退化;
- `sendResponseHeaders(200, 0)` 的 **0 必须保留**:它是「不定长响应」的信号,
  用固定 `Content-Length` 会让流在第一条事件后就被判为「读完了」。

### §1.4 首帧:**必须**是 `event: endpoint`(FR-011 / FR-012)

这是**整个 SSE 握手的闸门**。生产 `doConnect()` L282–331:

```java
postEndpoint = null;
endpointLatch = new CountDownLatch(1);
startSseReader(sseUrl);
if (!await(endpointLatch, hbTimeoutMs)) {
    throw new IOException("no 'endpoint' event from SSE server within " + hbTimeoutMs + "ms");
}
String endpoint = postEndpoint;
if (endpoint == null || endpoint.isEmpty()) {
    throw new IOException("SSE 'endpoint' event carried no URI");
}
```

**两条失败必须能被区分**(它们是两条不同的 FR):

| 失败 | 消息 | 对应 FR |
|---|---|---|
| 流活着但**从未**发 `event: endpoint` | `no 'endpoint' event from SSE server within Nms` | FR-009 / FR-016 |
| 发了 `event: endpoint` 但 data 空 | `SSE 'endpoint' event carried no URI` | FR-012 负例 |

⇒ 这就是 `noEndpointAnnounce` 开关存在的理由(见 `../data-model.md` §1.3):
**关掉公告 ≠ 掐断流**,掐断流会走到另一个分支去,测不出「等公告超时」。

#### 帧的精确形状

```
event: endpoint
data: <POST 端点 URI>

```

- `event:` 与 `data:` 各占一行,两者之间**无空行**;
- 事件以**单个空行**结束(SSE 帧分隔符);
- **`data` 可以是相对 URI**(FR-012)。生产解析:

```java
// resolveEndpoint(String announced), L408–414
try {
    return new URL(new URL(resolveSseUrl(cfg.getUrl())), announced).toString();
} catch (MalformedURLException e) {
    return announced;   // 解析不了就原样当绝对 URL 用
}
```

⇒ fixture **默认应该发相对 URI**(如 `data: /messages?sessionId=abc123`),
因为这才是 MCP 规范推荐形状,也是 FR-012 要覆盖的形状;
fixture 另需支持发绝对 URI 以覆盖 fallback 分支。

### §1.5 后续帧:`event: message` + JSON-RPC `id` 关联(FR-013)

生产 `readSseLoop`(L588–648)只对 `event == "message"` 的帧调 `handleMessage`,
再走 `pending.get(id)` 关联。关联结构是 `ConcurrentHashMap<Long, Pending>`,
**登记发生在 send 之前、摘除发生在 send 的 `finally`**(L339–353):

```java
long id = nextRequestId.incrementAndGet();     // 1-based,单调递增
pending.put(id, slot);
try {
    send(...);
    await(slot.latch, hbTimeoutMs);           // 超时 → IOException
} finally {
    pending.remove(id);
}
```

⇒ 对 fixture 的三条硬要求:

1. **必须回显请求里的 `id`**,不能像现在这样硬编码 `id = 1L`
   (`TestMcpSseServer` 的 5 个 handler 全部 `writeJsonRpc(ex, 1L, r)` —— 这正是
   「id 关联从未被测到」的根因);
2. **`id` 从 1 开始**且单调递增(第一次 `initialize` 拿 id=1,`tools/list` id=2,
   `tools/call` id=3,`ping` 依心跳次数继续递增);
3. **允许乱序到达**。生产按 id 关联而非按到达顺序,因此 fixture
   *可以*把响应打乱顺序发,SC-003 的反向证明用例正是靠这个。
   fixture 需要一个 `outOfOrder` 开关来支撑该用例(Phase 2 T012 落地)。

**帧形状**:

```
event: message
data: {"jsonrpc":"2.0","id":7,"result":{...}}

```

### §1.6 SSE 帧解析的两处**已知分歧**(T008 如实记录,不掩盖)

`SseMcpServerConnection.readSseLoop` 与 `McpHttpSupport.readSseResponse` 是**两份
独立的 SSE 解析实现**,行为不完全一致:

| 行为 | `McpHttpSupport.readSseResponse`(L489–540) | `SseMcpServerConnection.readSseLoop`(L588–648) |
|---|---|---|
| 遇到空行 | **仅当 data 非空时**才重置 `event` | **每次**空行都重置 `event` |
| `:` 注释行 | 忽略 | 忽略 |
| `retry:` 字段 | 忽略 | 忽略 |
| 多行 `data:` | 用 `\n` 连接 | 同 |

⇒ **fixture 不应**依赖「空行后仍保留上一帧 event」这种行为,
也不应发 `retry:` 字段去试探实现(两端都不实现)。这是
`../data-model.md` §3.4 记录的盲点之一(「SSE `retry:` 字段」)。

### §1.7 SSE transport 的 POST 端点

生产 `send()` L356–363:

```java
if (postEndpoint == null) {
    throw new IOException("MCP SSE POST endpoint not announced yet");
}
McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);
McpHttpSupport.postNotification(endpoint, envelope, hbTimeoutMs);
```

⚠️ **与 Streamable HTTP 的分歧**:SSE 侧走的是 **legacy `postNotification` 静态方法**,
**不是** `postJsonRpcSpec`。所以 SSE 的 POST 请求头上:

- ✅ `Content-Type: application/json`
- ✅ `Accept: application/json`
- ❌ **没有** `MCP-Protocol-Version`
- ❌ **没有** `Mcp-Session-Id`
- ❌ **响应不做 SSE 解析**(只按 `application/json` 读 raw body)

⇒ fixture 的 SSE 公告 POST 端点**只需**接受裸 JSON-RPC envelope 并回
`application/json`;**不要**指望生产在 SSE 侧发会话头。
(会话概念属于 Streamable HTTP,见 §2。)

**通知无 id(FR-014)**:`notifications/initialized` 经 `wrapNotification`(L183–191)
生成的是 `{jsonrpc, method, params?}` —— **不带 `id`**,因此 server **不得回响应**,
fixture 直接 `204 No Content` 即可。

### §1.8 SSE 心跳 = JSON-RPC `ping`(FR-015 / FR-007)

**不是** `GET /health`。生产 SSE 侧心跳走
`request("ping", mapper.createObjectNode())` —— 一个**普通的、带 id 的 JSON-RPC 请求**,
走公告出来的 POST 端点,**不是** 一个独立的 GET 端点。

⇒ fixture 必须:
- 接受公告端点上的 `ping` 请求(带 id),回 `{"jsonrpc":"2.0","id":<same>,"result":{}}`;
- **不提供** `GET /health`(旧 fixture 有,§1.9 要删)。

### §1.9 已删除:`GET /health` 与 `dontReplyHealth`(FR-007)

旧 `TestMcpHttpServer` / `TestMcpSseServer` 都有 `GET /health` + `dontReplyHealth` 开关。
PR #90 之后生产代码**再也不发**这个请求(心跳已换成 JSON-RPC `ping`),
所以这两个 fixture 形状**已经是死代码**,T011 一并删掉:

- ❌ 删 `GET /health` context;
- ❌ 删 `dontReplyHealth` sysprop;
- ✅ 心跳失败用例改用 `../data-model.md` §1.4 记录的新开关
  (Streamable HTTP 用 `expireSession=true`;SSE 用 `closeSseAfter` + `delayMs`)。

---

## §2 Streamable HTTP wire 契约

对应 `StreamableHttpMcpServerConnection` + `McpHttpSupport.postJsonRpcSpec`。

### §2.1 端点拓扑:**只有一个端点**(FR-017)

```java
// callTool(), L169–174
String endpoint = cfg.getUrl();
// "Single MCP endpoint: tools/call goes to cfg.getUrl() itself, never to
//  a "/tools/call" sub-path."
```

⇒ **没有** `/initialize`、`/tools/list`、`/tools/call`、`/notifications/initialized`
这四个子路径。**每一个** JSON-RPC 消息都 POST 到 `cfg.getUrl()` 本身。
fixture 的 handler **必须读 body 的 `method` 字段来分派**,不能靠 URL 路径分派。
这是旧 `TestMcpHttpServer` 的 5 端点方案被整个淘汰的原因。

### §2.2 请求头(fixture 必须能读到的 4 个)

`postJsonRpcSpec`(L419–481)按下述**顺序**设置:

| # | 头 | 值 | 何时出现 |
|---|---|---|---|
| 1 | `Content-Type` | `application/json` | 恒定 |
| 2 | `Accept` | `application/json, text/event-stream` | 恒定 |
| 3 | `Mcp-Session-Id` | `cfg` 协商出来的会话 id | **仅当非空** |
| 4 | `MCP-Protocol-Version` | 协商出来的协议版本 | **仅当非空** |

> 顺序对 fixture 无强制意义(是给「请求记录器」照抄用的),但**取值**与**有无**是
> FR-018 / FR-019 的断言目标。

### §2.3 握手 4 次 POST —— initialize 与 notification 的头**不一样**(FR-018 / FR-019)

`initialize(String endpoint)` L271–292:

```java
long initId = nextRequestId.incrementAndGet();
ObjectNode initEnv = wrapJsonRpc(initId, "initialize", buildInitializeParams());
HttpRpcResponse initResp = postJsonRpcSpec(endpoint, initEnv, hbTimeoutMs,
                                           /*sessionId*/ null,
                                           /*protocolVersion*/ null,
                                           initId);        // ← 两个头都是 null!
if (initResp.getSessionId() != null && !initResp.getSessionId().isEmpty()) {
    sessionId = initResp.getSessionId();
}
JsonNode result = unwrap(initResp.getBody(), "initialize");
String negotiated = result.path("protocolVersion").asText("");
protocolVersion = negotiated.isEmpty() ? PROTOCOL_VERSION : negotiated;

ObjectNode notifEnv = wrapNotification("notifications/initialized", null);
postJsonRpcSpec(endpoint, notifEnv, hbTimeoutMs, sessionId, protocolVersion, null);
```

⇒ **wire 上必须体现的不对称**:

| 请求 | `id` | `Mcp-Session-Id` | `MCP-Protocol-Version` | 期望响应 |
|---|---|---|---|---|
| ① `initialize` | 有 | **无** | **无** | `application/json` 或 SSE + **`Mcp-Session-Id` 响应头** |
| ② `notifications/initialized` | **无** | 有 | 有 | 无 body(202/204 皆可) |
| ③ `tools/list` | 有 | 有 | 有 | JSON 或 SSE |
| ④ 后续 `tools/call` / `ping` | 有 | 有 | 有 | JSON 或 SSE |

**「① 无会话头」是规范要求的**(客户端此刻还不知道会话),也是请求记录器
(`../data-model.md` §2.2 的 `sessionIdHeader` 字段)要能区分的一格。

### §2.4 响应:`application/json` **或** `text/event-stream`(FR-023)

`postJsonRpcSpec` 的分派逻辑:

```java
if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
    return readSseResponse(conn, expectedId);   // 按 id 关联
}
byte[] raw = readAllBytes(...);
return raw.length == 0 ? EMPTY_OBJECT : mapper.readTree(raw);
```

⇒ fixture **必须两种都能发**,否则只测到一半:

- `application/json`:`ex.getResponseHeaders().set("Content-Type", "application/json")` +
  固定长度 body;
- `text/event-stream`:`set("Content-Type", "text/event-stream")` +
  `sendResponseHeaders(200, 0)`,body 用 SSE 帧写:
  ```
  event: message
  data: {"jsonrpc":"2.0","id":3,"result":{"content":"fake-result","isError":false}}

  ```
  `readSseResponse` 按 `node.id == expectedId` 关联;流走完还没有匹配项 ⇒
  `McpTransportException(LINGS_M03, "SSE response stream ended without a matching JSON-RPC response")`。

⚠️ **`expectedId` 可以是 `null`**(通知场景)。此时 fixture 仍然**不回复**,
不要为通知也造一个 SSE 帧 —— 那会污染流。

### §2.5 非 2xx → `McpHttpStatusException`(FR-020 / FR-022)

```java
if (code < 200 || code >= 300) {
    throw new McpHttpStatusException(code, McpErrorCodes.LINGS_M03,
        "HTTP " + code + ": " + truncate(body, 200));
}
```

⇒ fixture 造错状态码时,生产侧的可观察结果是:
- 异常类型 `McpHttpSupport.McpHttpStatusException`(extends `McpTransportException`)
- `getStatus()` = fixture 给的状态码
- `getMessage()` 前缀 `LINGS-M03`

⇒ **404 是唯一的协议语义状态码**,其余 4xx/5xx 都只是通用失败。

### §2.6 404 + 活会话 ⇒ 清会话 → 重握手 → **同 id 重试恰好一次**(FR-020 / FR-021)

`request(...)` L302–318:

```java
long id = nextRequestId.incrementAndGet();
ObjectNode envelope = wrapJsonRpc(id, method, params);
try {
    return post(endpoint, envelope, id);
} catch (McpHttpSupport.McpHttpStatusException e) {
    if (e.getStatus() == 404 && sessionId != null) {
        sessionId = null;
        protocolVersion = null;
        initialize(endpoint);          // ← 重握手:新会话 + 新 protocolVersion
        return post(endpoint, envelope, id);   // ← 同一个 id、同一份 envelope
    }
    throw e;                          // ← 非 404,或无会话 → 原样上抛
}
```

**三处语义,fixture 都要能造出来**:

| # | 条件 | 生产行为 | FR |
|---|---|---|---|
| 1 | 404 **且** `sessionId != null` | 清会话 → 重握手 → **同 id 重试一次** | FR-020 |
| 2 | 重试**再次** 404 | **不上抛重试循环**,异常直接冒到调用方 | FR-021 |
| 3 | 404 **且** `sessionId == null` | **不重握手**,直接上抛 | FR-022 |

⇒ fixture 的 404 必须是**「持续 404」**而不是「只 404 一次」:
只 404 一次的话重试就会成功,测不出 FR-021。开关语义见
`../data-model.md` §1.3 `expireSession`。

⇒ 另外注意 **FR-024**:重握手会走完整的 `initialize`,因此 fixture 会**再看到一次
不带会话头的 `initialize`**。fixture 应当为**每次** `initialize` 都发一个新的
`Mcp-Session-Id` 响应头,才能让「换了新会话」可断言。

### §2.7 会话 id 从响应头取,不是从 body 取(FR-018)

`post(...)` L320–327:

```java
if (resp.getSessionId() != null && !resp.getSessionId().isEmpty()) {
    sessionId = resp.getSessionId();
}
```

⇒ fixture **必须**通过**响应头** `Mcp-Session-Id` 发会话 id。
把 session id 塞进 JSON body 的 result 里**无效**,客户端读不到。

### §2.8 重连 = 重新协商(FR-024)

`doConnect()` L237–261 在 `initialize` 之前先:

```java
// A reconnect always negotiates a brand-new session.
sessionId = null;
protocolVersion = null;
initialize(endpoint);
```

⇒ fixture 侧的可观察结果:重连成功后,后续请求带的是**新会话 id**,
而不是重连前那个。请求记录器按顺序读 `sessionIdHeader` 即可断言。

### §2.9 域守卫:每一次出站 HTTP 之前(FR-030)

| 调用点 | 代码 |
|---|---|
| `doConnect()` | `checkOrThrow(endpoint, whitelist)` L243 |
| `callTool()` | `checkOrThrow(endpoint, whitelist)` L181 |
| `heartbeatTick()` | `checkOrThrow(endpoint, whitelist)` L401 |

⇒ fixture 无需为守卫做任何特殊事;只需绑 `127.0.0.1` 并让
`McpServerConfig.domainWhitelist` 含 `"127.0.0.1"`。
注意 `checkOrThrow` 的 `whitelist.contains(host)` 是**大小写敏感**的,
fixture 白名单写 `"127.0.0.1"`(不带协议、不带端口)。

---

## §3 fixture **不**提供的东西(负向清单)

写清楚边界,避免 T012 时又把旧形状加回来:

| 旧形状 | 状态 | 原因 |
|---|---|---|
| `POST /initialize` | ❌ 删 | Streamable HTTP 单端点(FR-017);SSE 走公告端点 |
| `POST /notifications/initialized` | ❌ 删 | 同上 |
| `POST /tools/list` | ❌ 删 | 同上 |
| `POST /tools/call` | ❌ 删 | 同上 |
| `GET /health` | ❌ 删 | 心跳已换 JSON-RPC `ping`(FR-007) |
| `GET /tools/list`(SSE 推送用) | ❌ 从未有过 | 旧 SSE fixture 用 `notifications/tools/list_changed` 推事件,方向反了(生产是**拉**式) |
| 响应 id 硬编码 `1L` | ❌ 删 | 必须回显请求 id(FR-013) |
| wildcard bind `new InetSocketAddress(port)` | ❌ 删 | 必须绑 `127.0.0.1`(constitution §6 兼容性矩阵) |

**关于 `notifications/tools/list_changed`**:旧 `TestMcpSseServer` 每 `pushIntervalMs` 推一条
`{"method":"notifications/tools/list_changed"}`。新契约下**保留**这个 server push
(SSE transport 的价值之一就是服务端主动推变更),但它必须是一个**合法的
`event: message` 帧、且不带 `id`**(通知),生产侧应触发工具列表重拉(FR-029)。
Phase 2 T012 落地,此处只登记形状。

---

## §4 T012 验收自查表

T012(合并 fixture 实现)完成时,逐条勾:

- [ ] `GET /sse` 首帧是 `event: endpoint`,data 为**相对 URI**(§1.4)
- [ ] 关闭公告时流**仍活着**(与「掐断流」可区分)(§1.4 / `../data-model.md` §1.3)
- [ ] 每个 JSON-RPC 响应的 `id` **回显请求 id**(§1.5)
- [ ] `notifications/*` 帧**不带 id**(§1.5 / §2.3)
- [ ] SSE 公告 POST 端点回 `application/json`,不带会话头也 OK(§1.7)
- [ ] Streamable HTTP 端点**按 body 的 `method` 分派**,不看 URL 路径(§2.1)
- [ ] `initialize` 的响应**经响应头**带 `Mcp-Session-Id`(§2.7)
- [ ] `initialize` 请求**不带** `Mcp-Session-Id` / `MCP-Protocol-Version`(§2.3)
- [ ] `application/json` 与 `text/event-stream` **两种响应都能发**(§2.4)
- [ ] 404 可持续、可被重试穿透测出「恰好一次」(§2.6)
- [ ] 每次 `initialize` 发**新**会话 id(§2.8)
- [ ] 无 `GET /health`、无 5 端点路径(§3)
- [ ] 绑 `127.0.0.1`,不绑 wildcard(§3)
- [ ] 三个协议常量与 §0 表逐字符一致(§0)

---

**Last updated**: 2026-10-23 · Phase 1 / T008