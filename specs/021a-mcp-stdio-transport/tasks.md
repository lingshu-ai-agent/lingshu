# Story #021a `mcp-stdio-transport` — Tasks

> **Status**: Draft 2026-09-23
> **Implements**: `specs/021a-mcp-stdio-transport/plan.md`
> **Test budget**: ≥ 45 cases / 13 files(L1 13 + L2 10 + L2+L3 18 + EC 4)

---

## T-01 — `McpTransportType` enum(独立类型,避免循环依赖)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/McpTransportType.java`(新, ~25 行)

**实现**(对齐 plan.md §3.1):
- `public enum McpTransportType { STDIO, SSE, STREAMABLE_HTTP }`
- 3 字面值,顺序固定

**Javadoc**(类级,3 段落):
1. **What** — MCP 传输类型枚举
2. **Why here, not in mcp package** — 避免循环依赖(plan.md §3.1):`AgentConfig.ServerConfig` 在 `runtime` 包要 import,`McpTransport.connect(List<McpServerConfig>)` 在 `mcp` 包也 import —— 提到 `runtime` 顶层,mcp 单向依赖 runtime
3. **Default choice** — `#021a` 只实现 STDIO;SSE / STREAMABLE_HTTP 抛 `McpTransportException`

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- AC-021a-deps-1:R-13 dep-tree 0 binary delta(pre/post `mvn dependency:tree` 仅时间戳差异)
- `McpTransportTypeTest`(1 case,`assertThreeValuesInOrder`)

---

## T-02 — `AgentConfig.ServerConfig` 扩 5 字段 + 工厂 ctor

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(修改, +30 行)

**修改**(对齐 plan.md §2.7):
- `ServerConfig` inner class 扩 5 字段:
  - `ai.lingshu.core.runtime.McpTransportType transport`(默认 `STDIO`)
  - `String url`(默认 `null`)
  - `long heartbeatIntervalMs`(默认 `30000L`)
  - `long heartbeatTimeoutMs`(默认 `10000L`)
  - `long reconnectCapMs`(默认 `60000L`)
- import `ai.lingshu.core.runtime.McpTransportType`(本包,无需 import)
- 字段顺序按 yml 习惯:`name / transport / command / args / env / url / 3 心跳`

**Lombok 处理**:
- `@Value` 是 final 字段,加新字段需要**重新构造**对象
- **方案 1**(简单):加 `@Builder.Default` —— 但 `@Value` 生成的 ctor 不支持 @Builder.Default 默认值(只有 `@Builder` 单独用才行)
- **方案 2**(更稳):用 `@Builder.Default` 在新字段上(Lombok `@Value @Builder` 组合支持 `@Builder.Default`)
- **方案 3**(最稳):手写 default 静态方法 `ServerConfig.defaultStdios(String name, String command, List<String> args)` —— 但破坏 `@Value` 简洁

**采用方案 2**:`@Builder.Default` 给 5 个新字段默认值,确保老 yml 4 字段反序列化后默认值生效。

**Javadoc 改动**:
- `ServerConfig` 类级 Javadoc 追加:
  - 🆕 Story #021a — transport 枚举 + 3 心跳参数(默认保守值)
  - 反向兼容:Story #020a 老 yml 4 字段 → transport 默认 STDIO,心跳默认 30000/10000/60000

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- 现有 `AgentConfigTest` / `AgentConfigDefaultsTest` 等不挂(老用例继续过)
- `AgentConfigMcpBackwardCompatTest` 3 case 通过(老 yml 4 字段 → 新 9 字段 + 默认值)

---

## T-03 — `McpServerConfig` runtime POJO

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConfig.java`(新, ~110 行)

**实现**(对齐 plan.md §2.1):
- 9 字段:
  - `String name`(required)
  - `McpTransportType transport`(required)
  - `String command`(nullable)
  - `@Builder.Default List<String> args = new ArrayList<>()`
  - `@Builder.Default Map<String, String> env = new HashMap<>()`
  - `String url`(nullable)
  - `@Builder.Default long heartbeatIntervalMs = 30_000L`
  - `@Builder.Default long heartbeatTimeoutMs = 10_000L`
  - `@Builder.Default long reconnectCapMs = 60_000L`
- Lombok 注解:`@Value @Builder @Jacksonized`
- **删除** 内嵌 `Transport` enum(移到 `McpTransportType`,plan.md §3.1)
- 引用 `ai.lingshu.core.runtime.McpTransportType`

**imports**:
- `ai.lingshu.core.runtime.McpTransportType`
- `lombok.Builder` / `lombok.Value` / `lombok.extern.jackson.Jacksonized`
- `java.util.ArrayList` / `java.util.HashMap` / `java.util.List` / `java.util.Map`

**Javadoc**(类级,5 段落):
1. **What** — runtime config POJO,8 字段
2. **Why separate from `AgentConfig.ServerConfig`** —— 两层解耦(runtime mutable 心跳参数 vs YAML 不可变)
3. **Default 数值选择** — 30s 心跳 / 10s ping 超时 / 60s 重连上限(plan.md §2.1)
4. **JDK 8 兼容** —— `@Value @Builder @Jacksonized`,不用 record / sealed / List.of
5. **Tests override defaults** —— `StdioMcpServerConnection(McpServerConfig cfg, long hbIntervalMs, long hbTimeoutMs, long reconnectCapMs)` 4-arg ctor 允许测试 fixture 传小值

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpServerConfigTest` 4 case 通过(见 T-08)
- AC-021a-1

---

## T-04 — `ConnectionState` enum 6 态

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/ConnectionState.java`(新, ~30 行)

**实现**(对齐 plan.md §2.2):
- `public enum ConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, FAILED }`
- 6 字面值,顺序固定

**Javadoc**(类级,5 段落):
1. **What** — MCP 连接生命周期状态机
2. **State diagram** —— ASCII 图:`IDLE → CONNECTING → CONNECTED ⇄ DISCONNECTED → RECONNECTING → CONNECTED...` + `FAILED ← close()`
3. **6 态语义** —— 每态 1 句
4. **Terminal states** —— `FAILED` 是终态,只能 `close()` 出来
5. **JDK 8 兼容** —— enum 字面值,不用任何新特性

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `ConnectionStateTest` 1 case 通过(`assertSixValues`)
- AC-021a-2

---

## T-05 — `McpServerConnection` interface 8 方法

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnection.java`(新, ~95 行)

**实现**(对齐 plan.md §2.3):
- `public interface McpServerConnection extends AutoCloseable`
- 8 方法签名(对齐 dsh L4569-4591):
  - `String name()`
  - `ConnectionState state()`
  - `Instant lastHeartbeatAt()`
  - `List<McpToolDescriptor> listTools()`
  - `McpCallResult callTool(String toolName, JsonNode input)`
  - `void onStateChange(Consumer<ConnectionState> listener)`
  - `void start()`
  - `@Override void close()`
- `McpToolDescriptor` 和 `McpCallResult` 是 #021b 引入的类,**Story #021a 先定义 minimal version**(放在 mcp 包)
  - `McpToolDescriptor`:`@Value @Builder`(name / description / inputSchema)
  - `McpCallResult`:`@Value @Builder`(success / content / errorMessage / isError)
- 这两个类是 #021b 也会用到的,**Story #021a 引入后 #021b 直接消费**

**imports**:
- `com.fasterxml.jackson.databind.JsonNode`
- `java.time.Instant` / `java.util.List` / `java.util.function.Consumer`

**Javadoc**(类级,5 段落):
1. **What** — MCP 连接生命周期接口
2. **state machine integration** —— listener 模式通知 `McpTransport`,connecter 注册/unregister McpToolAdapter
3. **非 CONNECTED callTool 直接返 error,不抛异常** —— 与 §4.10.1 硬规则 2 兼容
4. **listener 异常隔离** —— 单 listener 抛异常不影响其他(#021b 需依赖此契约)
5. **JDK 8 兼容** —— 8 abstract 方法,不用 default method(JDK 8 编译器友好)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpServerConnectionContractTest` 1 case 通过(`interfaceHasEightMethods`)
- AC-021a-3

---

## T-06 — `McpTransportException` LINGS-M01 载体

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpTransportException.java`(新, ~30 行)

**实现**(对齐 plan.md §2.6):
- `public class McpTransportException extends RuntimeException`
- 字段:`private final String code;`
- 3 ctor:
  - `McpTransportException(String code, String message)`
  - `McpTransportException(String code, String message, Throwable cause)`
  - `String getCode()` getter

**Javadoc**(类级,3 段落):
1. **What** — MCP transport 域异常
2. **Story #021a 引入新错误域 M** —— `LINGS-M01 = MCP_CONNECT_FAILED`(factory 不支持 SSE/HTTP 抛此)
3. **对齐 dsh §15.9** —— 错误码命名约定 `LINGS-<域><2 位数字>`

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpTransportExceptionTest` 1 case(`carriesCodeAndMessage`)
- LINGS-M01 在 factory test 验证被抛

---

## T-07 — `McpServerConnectionFactory` + `McpToolDescriptor` + `McpCallResult` minimal

**文件**(2 新建,1 minor):
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java`(新, ~50 行)
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpToolDescriptor.java`(新, ~30 行,`@Value @Builder`)
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpCallResult.java`(新, ~50 行,`@Value @Builder` + static `error(String)` factory)

**`McpServerConnectionFactory` 实现**(对齐 plan.md §2.4):
- `public final class McpServerConnectionFactory { private McpServerConnectionFactory() {} ... }`
- 单 public static 方法:`public static McpServerConnection create(McpServerConfig cfg)`
- switch `cfg.getTransport()`:
  - `STDIO` → `new StdioMcpServerConnection(cfg)`
  - `SSE` / `STREAMABLE_HTTP` → `throw new McpTransportException("LINGS-M01", ...)`
- unreachable `throw new IllegalStateException(...)` 防止 switch 不完整

**`McpToolDescriptor` 实现**:
- `@Value @Builder public class McpToolDescriptor`
- 字段:`String name` / `String description` / `JsonNode inputSchema`

**`McpCallResult` 实现**:
- `@Value @Builder public class McpCallResult`
- 字段:`String content` / `String errorMessage` / `boolean isError`
- `public static McpCallResult success(String content) { return McpCallResult.builder().content(content).isError(false).build(); }`
- `public static McpCallResult error(String errorMessage) { return McpCallResult.builder().errorMessage(errorMessage).isError(true).build(); }`

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpServerConnectionFactoryTest` 4 case:
  - `create_stdio_dispatchReturnsStdioConnection`
  - `create_sse_throwsM01`
  - `create_streamableHttp_throwsM01`
  - `create_null_throwsIllegalStateException`(边界)
- AC-021a-4

---

## T-08 — `McpServerConfigTest` L1 4 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpServerConfigTest.java`(新, ~80 行)

**测试方法**:

| 测试 | 输入 | 断言 |
|---|---|---|
| `builder_minimal_defaultsAreCorrect` | `McpServerConfig.builder().name("x").transport(STDIO).build()` | `args.size()==0 && env.isEmpty() && heartbeatIntervalMs==30000 && heartbeatTimeoutMs==10000 && reconnectCapMs==60000` |
| `builder_customHeartbeatValues` | 显式 set `heartbeatIntervalMs=50` | getter 返 50 |
| `builder_jacksonize_serializationRoundtrip` | `ObjectMapper.writeValueAsString` + `readValue` | 两对象 `equals` |
| `value_immutable_setterThrows` | `config.setName("y")` | 编译错(Lombok @Value 生成 final 字段,无 setter)|

**DoD**:
- `mvn -pl lingshu-core test -Dtest=McpServerConfigTest` 4 case 全过
- AC-021a-1

---

## T-09 — `McpServerConnectionFactoryTest` L2 4 case + `ConnectionStateTest` L1 1 case + `McpServerConnectionContractTest` L1 1 case

**文件**(3 新建):
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpServerConnectionFactoryTest.java`(新, ~80 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/ConnectionStateTest.java`(新, ~30 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpServerConnectionContractTest.java`(新, ~30 行)

**测试方法**:

| 测试 | 输入 | 断言 |
|---|---|---|
| `McpServerConnectionFactoryTest.create_stdio_dispatchReturnsStdioConnection` | `cfg(transport=STDIO)` | 返回 `StdioMcpServerConnection` 实例,`state()==IDLE` |
| `McpServerConnectionFactoryTest.create_sse_throwsM01` | `cfg(transport=SSE)` | 抛 `McpTransportException`,`getCode()=="LINGS-M01"` |
| `McpServerConnectionFactoryTest.create_streamableHttp_throwsM01` | `cfg(transport=STREAMABLE_HTTP)` | 同上 |
| `McpServerConnectionFactoryTest.create_null_throwsIllegalStateException` | cfg null | `IllegalStateException` |
| `ConnectionStateTest.assertSixValuesInOrder` | enum.values() | `[IDLE, CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, FAILED]` |
| `McpServerConnectionContractTest.interfaceHasEightMethods` | `McpServerConnection.class.getMethods()` | 8 方法签名匹配 |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=McpServerConnectionFactoryTest,ConnectionStateTest,McpServerConnectionContractTest` 6 case 全过
- AC-021a-2 + AC-021a-3 + AC-021a-4

---

## T-10 — `TestMcpServer` fake MCP server 子进程 fixture

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/fixture/TestMcpServer.java`(新, ~150 行)

**实现**:
- `public class TestMcpServer { public static void main(String[] args) throws IOException { ... } }`
- 启动 loop:从 stdin 读 line → JSON parse → dispatch by method name
- 4 个 handler:
  - `initialize` → 回 `{"jsonrpc":"2.0","id":N,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"test","version":"1.0"},"capabilities":{}}}` + 换行
  - `notifications/initialized`(无 id) → 忽略不回
  - `tools/list` → 回 `{"jsonrpc":"2.0","id":N,"result":{"tools":[{"name":"echo","description":"echo input","inputSchema":{"type":"object","properties":{"input":{"type":"string"}}}}]}}` + 换行
  - `ping` → 回 `{"jsonrpc":"2.0","id":N,"result":{}}` + 换行
  - `tools/call` → 回 `{"jsonrpc":"2.0","id":N,"result":{"content":"fake-result","isError":false}}` + 换行
- **简化 framing**:MCP spec 用 Content-Length header;TestMcpServer 用 line-delimited JSON(每行一个 frame),**不**支持 Content-Length 模式
- 但 Story #021a `StdioMcpServerConnection.sendAndAwait` 必须用 Content-Length 模式(MCP spec)
- **冲突解决**:TestMcpServer dual-mode reader —— 读 line 时先 peek 看是否 `Content-Length:` 开头,是则按 Content-Length 解析;否则按 line-delimited

**为什么用 line-delimited 简化**:TestMcpServer 写在测试目录,可以灵活控制协议;但 Story #021a 生产代码用 MCP spec Content-Length —— **测试 fixture 必须兼容**生产协议(否则集成测试无意义)

**实际方案**:TestMcpServer **只支持 Content-Length mode**(与 MCP spec 一致),line-delimited 不支持。这样:
- Story #021a `StdioMcpServerConnection.sendAndAwait` 实现 Content-Length framing
- Test fixture 启动后从 stdin 读 Content-Length frame,parse,handler 写 Content-Length response
- 集成测试 fixture 启动 `java -cp ... TestMcpServer`,验证 start() / heartbeat / reconnect / close 全流程

**DoD**:
- 手动启动 `java -cp target/test-classes:... ai.lingshu.core.mcp.fixture.TestMcpServer` + 写 `Content-Length: 82\r\n\r\n{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}` → 验证回包正确
- AC-021a-5 ~ AC-021a-9 全部 L3 测试用到此 fixture

---

## T-11 — `StdioMcpServerConnection` 完整实现

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/StdioMcpServerConnection.java`(新, ~280 行)

**实现**(对齐 plan.md §2.5):
- `public class StdioMcpServerConnection implements McpServerConnection`
- 字段(10 个,见 plan.md §2.5 表格)
- ctor(2 个):
  - `public StdioMcpServerConnection(McpServerConfig cfg)` —— 调 4-arg ctor 用 cfg 默认值
  - `public StdioMcpServerConnection(McpServerConfig cfg, long hbIntervalMs, long hbTimeoutMs, long reconnectCapMs)`
- 8 abstract 实现 + 5 私有方法(start / probe / scheduleReconnect / stopProcess / transition / callTool / close / sendAndAwait / sendNotification / buildInitializeParams / parseToolList / parseCallResult)

**关键不变量**:
- `state` 用 `AtomicReference`,`lastBeat` / `reconnectAttempts` 用 `AtomicReference` / `AtomicInteger`(多线程:hb 线程 + caller 线程)
- `process / stdin / stdout / cachedTools` 用 `volatile`
- `listeners` 用 `CopyOnWriteArrayList`(多读少写)
- daemon `ThreadFactory`(mcp-hb-{cfg.getName()})—— 不阻塞 JVM exit

**sendAndAwait 关键实现**:
```java
private JsonNode sendAndAwait(String method, Map<String, Object> params, long timeoutMs) throws IOException {
    long id = idCounter.incrementAndGet();
    ObjectNode frame = mapper.createObjectNode();
    frame.put("jsonrpc", "2.0");
    frame.put("id", id);
    frame.put("method", method);
    frame.set("params", mapper.valueToTree(params));
    byte[] body = mapper.writeValueAsBytes(frame);
    String header = "Content-Length: " + body.length + "\r\n\r\n";
    stdin.write(header.getBytes(StandardCharsets.UTF_8));
    stdin.write(body);
    stdin.flush();
    // 阻塞读 stdout 直到找到匹配 id 的 response 或 timeout
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] tmp = new byte[1024];
    while (System.nanoTime() < deadline) {
        // ... 读 Content-Length 帧,parse,检查 id
    }
    throw new IOException("sendAndAwait timeout: " + method);
}
```

**简化版**:`StdioMcpServerConnection` 的 sendAndAwait 用 `BufferedReader` + **line-delimited** 模式(每行一个 JSON frame,**不**用 Content-Length header)—— 简化测试 fixture 复杂度。这是**与 MCP spec 的偏差**,但 dsh L4697-4708 模板不强制 Content-Length。

**采用简化版**(line-delimited)—— 简化测试 fixture,牺牲严格遵循 MCP spec。Story #021b 时改回 Content-Length 模式(那时 TestMcpServer 也升级)。

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- AC-021a-5 + AC-021a-6 + AC-021a-7 + AC-021a-8 + AC-021a-9(见 T-12 ~ T-15 测试)

---

## T-12 — `StdioMcpServerConnectionStartTest` L2 + L3 5 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/StdioMcpServerConnectionStartTest.java`(新, ~200 行)

**测试方法**(用 TestMcpServer fixture, hbIntervalMs=100, hbTimeoutMs=500, reconnectCapMs=200):

| 测试 | 流程 | 断言 |
|---|---|---|
| `start_fakeServer_5stepsReachesConnected` | 启动 TestMcpServer → 创建 connection → start() → 等 1s | `state()==CONNECTED && listTools().size()==1 && lastHeartbeatAt() 距 now < 1s` |
| `start_invalidCommand_immediateFailureSchedulesReconnect` | cfg.command="this-does-not-exist" | `start()` 后 state `RECONNECTING`(非 IDLE) |
| `start_alreadyConnected_noop` | 调 start() 第二次 | state 仍 `CONNECTED`,无新子进程 |
| `start_duringReconnecting_noop` | start() 后立即 scheduleReconnect → 再 start() | state 仍 `RECONNECTING`(不抢) |
| `start_reconnect_resetsAttempts` | start → 故意 kill 子进程 → 触发 reconnect → reconnect 后 reconnectAttempts==0 | 验证 state 重置 |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StdioMcpServerConnectionStartTest` 5 case 全过
- AC-021a-5

---

## T-13 — `StdioMcpServerConnectionHeartbeatTest` L2 + L3 4 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/StdioMcpServerConnectionHeartbeatTest.java`(新, ~150 行)

**测试方法**:

| 测试 | 流程 | 断言 |
|---|---|---|
| `probe_processAlive_pingSucceeds_stateStable` | TestMcpServer + hbIntervalMs=100 | 等 5 次 heartbeat 后 state==CONNECTED,`lastHeartbeatAt` 持续更新 |
| `probe_processDead_transitionsToDisconnected` | start 后 `process.destroy()` → 等 2s | state==DISCONNECTED 然后 RECONNECTING |
| `probe_pingHangs_transitionsToDisconnected` | TestMcpServer 故意不回 ping(模拟僵死)→ hbTimeoutMs=200 | 等 1s,state==DISCONNECTED |
| `probe_doubleCheck_bothRequired` | start 后手动 disable ping handler 但 process alive → state==DISCONNECTED | 验证双探活缺一不可 |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StdioMcpServerConnectionHeartbeatTest` 4 case 全过
- AC-021a-6

---

## T-14 — `StdioMcpServerConnectionReconnectTest` L2 + L3 3 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/StdioMcpServerConnectionReconnectTest.java`(新, ~120 行)

**测试方法**(用 hbIntervalMs=50, reconnectCapMs=300):

| 测试 | 流程 | 断言 |
|---|---|---|
| `backoff_sequence_1s_2s_4s_8s_16s_32s_60sCap` | start → kill process → 记录每次 reconnect 间隔 | 序列 = [50, 100, 200, 300(cap), 300, 300, 300](第 5 次后 cap)|
| `reconnect_unbounded_noMaxAttempts` | start → kill 5 次 | 第 5 次 reconnect 仍发生(无限重试) |
| `reconnect_success_resetsAttempts` | start → kill → reconnect 成功 → kill → reconnect | reconnectAttempts 第二次从 1 起算(归零)|

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StdioMcpServerConnectionReconnectTest` 3 case 全过
- AC-021a-7

---

## T-15 — `StdioMcpServerConnectionListenerTest` L2 3 case + CloseTest 2 case + CallToolNotConnectedTest 1 case

**文件**(3 新建):
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/StdioMcpServerConnectionListenerTest.java`(新, ~100 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/StdioMcpServerConnectionCloseTest.java`(新, ~80 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/StdioMcpServerConnectionCallToolNotConnectedTest.java`(新, ~50 行)

**测试方法**:

| 测试 | 流程 | 断言 |
|---|---|---|
| `ListenerTest.singleListener_calledOnStateChange` | start → 监听 state | 收到 CONNECTED 通知 |
| `ListenerTest.multiListener_allCalled` | 3 个 listener 注册 → start | 3 listener 都被通知 |
| `ListenerTest.listenerThrows_otherListenersStillCalled` | listener 1 抛异常 → listener 2/3 正常 | listener 2/3 都收到通知 |
| `CloseTest.close_transitionsToFailedIdempotent` | close() → close() 第二次 | state==FAILED,无 exception |
| `CloseTest.close_daemonThreadTerminated` | close() 后 hb 线程状态 | `hb.isShutdown()==true` |
| `CallToolNotConnectedTest.callTool_returnsErrorNotThrows` | start 失败 → state==DISCONNECTED → callTool | `result.isError()==true && 不抛异常` |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StdioMcpServerConnectionListenerTest,StdioMcpServerConnectionCloseTest,StdioMcpServerConnectionCallToolNotConnectedTest` 6 case 全过
- AC-021a-8 + AC-021a-9 + EC-021a-2

---

## T-16 — `AgentConfigMcpBackwardCompatTest` L1 3 case + `AgentConfigMcpExpansionTest` L1 3 case

**文件**(2 新建):
- `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigMcpBackwardCompatTest.java`(新, ~80 行)
- `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigMcpExpansionTest.java`(新, ~80 行)

**测试方法**:

| 测试 | 输入 yml | 断言 |
|---|---|---|
| `BackwardCompatTest.legacy4Fields_transportDefaultsStdio` | yml 4 字段(name / command / args / env)| `cfg.transport==STDIO && cfg.url==null && cfg.heartbeatIntervalMs==30000 && cfg.heartbeatTimeoutMs==10000 && cfg.reconnectCapMs==60000` |
| `BackwardCompatTest.legacyConfig_createsValidMcpServerConfig` | 同上 | `McpServerConfig.fromAgentConfig(cfg)` 构造成功,字段对齐 |
| `BackwardCompatTest.legacyConfig_factoryCreatesStdioConnection` | 同上 | `McpServerConnectionFactory.create(McpServerConfig.fromAgentConfig(cfg)) instanceof StdioMcpServerConnection` |
| `ExpansionTest.newFields_transportSse` | yml 含 `transport: sse` | `cfg.transport==SSE` |
| `ExpansionTest.newFields_urlField` | yml 含 `url: https://mcp.example.com/sse` | `cfg.url=="https://mcp.example.com/sse"` |
| `ExpansionTest.newFields_heartbeatOverride` | yml 含 `heartbeatIntervalMs: 5000` | `cfg.heartbeatIntervalMs==5000` |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=AgentConfigMcpBackwardCompatTest,AgentConfigMcpExpansionTest` 6 case 全过
- AC-021a-10

---

## T-17 — `McpTransportExceptionTest` L1 1 case + EC 子进程立即死 1 case

**文件**(2):
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpTransportExceptionTest.java`(新, ~30 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/StdioMcpServerConnectionStartErrorTest.java`(新, ~50 行)

**测试方法**:

| 测试 | 流程 | 断言 |
|---|---|---|
| `McpTransportExceptionTest.carriesCodeAndMessage` | `new McpTransportException("LINGS-M01", "msg")` | `getCode()=="LINGS-M01" && getMessage()=="msg"` |
| `StartErrorTest.invalidCommand_schedulesReconnect` | cfg.command="this-does-not-exist" → start() | state==RECONNECTING,日志含 "start failed" |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=McpTransportExceptionTest,StdioMcpServerConnectionStartErrorTest` 2 case 全过
- EC-021a-1

---

## T-18 — R-13 dep-tree 自查 + JDK 8 兼容 grep 自查 + 全模块编译 + 全量测试

**执行命令**(对齐 SOP §3.4):

```bash
# R-13 dep-tree pre 截图
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-pre.txt

# (实施期间 T-01 ~ T-17 后)
mvn -pl lingshu-core compile  # 编译过
mvn -pl lingshu-core test     # 全量测试过
mvn install -N               # 父 POM 校验过

# R-13 dep-tree post 截图
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-post.txt

# diff(应仅时间戳差异)
diff /tmp/dep-tree-pre.txt /tmp/dep-tree-post.txt
# 预期输出:仅时间戳行差异(8 行左右),0 binary delta

# JDK 8 兼容 grep 自查
grep -rE "\bvar\b|\bList\.of\b|\bsealed\b|record\s+\w+|Pattern matching|Text blocks" \
  lingshu-core/src/main/java/ai/lingshu/core/mcp/ \
  lingshu-core/src/test/java/ai/lingshu/core/mcp/ \
  2>&1
# 预期输出:空(无 JDK 9+ / 14+ / 15+ 特性)
```

**DoD**:
- `mvn -pl lingshu-core test` 全部 case 通过(L1 13 + L2 10 + L2+L3 18 + EC 4 = **45 case**)
- `mvn install -N` 通过
- `diff /tmp/dep-tree-pre.txt /tmp/dep-tree-post.txt` 仅时间戳差异
- JDK 8 grep 无命中

---

## T-19 — 文档同步(PR body 必备 + README + ROADMAP + dsh §15.10 + CLAUDE.md)

**修改**(PR body 内,**不**在 code commit):

- PR title:`feat(core): Story #021a mcp-stdio-transport — McpServerConnection + 6-态状态机 + Stdio 实现`
- PR body 模板:
  - **Summary**:5 句话
  - **Why**:dsh §6.5 (2.1) + #021b 前置
  - **What**:7 文件 / 1 测试目录 / 1 新 ErrorCode
  - **AC-021a-NN 验证**:贴 `mvn -pl lingshu-core test` 输出
  - **R-13 dependency:tree 自查**:贴 pre/post diff(见 T-18)
  - **Test count**:45 case
  - **JDK 8 兼容**:grep 自查结果

**commits 后文档同步**(独立 `docs(sync)` commit):

- `README.md` 加 "📡 MCP server 长生命周期 + 指数退避重连已上线" 核心特性 bullet + Story #021a 完整 retrospective
- `specs/ROADMAP.md` 「✅ 已完成」加 #021a 行 + 「🟡 待补」#021a 划掉 + 「🎯 实施节奏建议」next
- `dsh_agent_design.md` §15 加 §15.10 MCP 域(`LINGS-M01 MCP_CONNECT_FAILED`)
- `CLAUDE.md` Last updated 2026-09-23 + Version 1.3.33(Story #021a 同步条目)

**DoD**:
- PR body 含 R-13 自查节 + AC 验证输出
- docs(sync) commit 在 PR merge 后单独提交
- dsh §15.10 + README + ROADMAP + CLAUDE.md 4 件齐

---

## 任务依赖图

```
T-01 (McpTransportType enum)
   ↓
T-02 (AgentConfig.ServerConfig 扩字段)
   ↓
T-03 (McpServerConfig POJO)
   ↓
T-04 (ConnectionState enum)
   ↓
T-05 (McpServerConnection interface + McpToolDescriptor + McpCallResult minimal)
   ↓
T-06 (McpTransportException)
   ↓
T-07 (McpServerConnectionFactory)
   ↓
T-08 (McpServerConfigTest L1)
   ↓
T-09 (FactoryTest + ConnectionStateTest + ContractTest)
   ↓
T-10 (TestMcpServer fixture)
   ↓
T-11 (StdioMcpServerConnection 完整实现)
   ↓
T-12 ~ T-15 (Start/Heartbeat/Reconnect/Listener/Close/CallToolNotConnected 测试)
   ↓
T-16 (AgentConfigMcpBackwardCompat + Expansion 测试)
   ↓
T-17 (McpTransportException + StartError EC 测试)
   ↓
T-18 (R-13 自查 + 全量测试)
   ↓
T-19 (文档同步)
```

**实施顺序**:T-01 → T-02 → T-03 → T-04 → T-05 → T-06 → T-07(编译过)→ T-08 → T-09(测试过)→ T-10 → T-11 → T-12 → T-13 → T-14 → T-15 → T-16 → T-17 → T-18 → T-19

---

## 测试 case 计数(checklist)

| Task | 测试文件 | case 数 |
|---|---|---:|
| T-08 | McpServerConfigTest | 4 |
| T-09 | McpServerConnectionFactoryTest | 4 |
| T-09 | ConnectionStateTest | 1 |
| T-09 | McpServerConnectionContractTest | 1 |
| T-12 | StdioMcpServerConnectionStartTest | 5 |
| T-13 | StdioMcpServerConnectionHeartbeatTest | 4 |
| T-14 | StdioMcpServerConnectionReconnectTest | 3 |
| T-15 | StdioMcpServerConnectionListenerTest | 3 |
| T-15 | StdioMcpServerConnectionCloseTest | 2 |
| T-15 | StdioMcpServerConnectionCallToolNotConnectedTest | 1 |
| T-16 | AgentConfigMcpBackwardCompatTest | 3 |
| T-16 | AgentConfigMcpExpansionTest | 3 |
| T-17 | McpTransportExceptionTest | 1 |
| T-17 | StdioMcpServerConnectionStartErrorTest | 1 |
| **T-01** | **McpTransportTypeTest**(inline in T-01)| 1 |
| **T-02** | **(无独立 test,合并到 T-16 BackwardCompat)** | 0 |
| **T-10** | **(fixture,无独立 test,被 T-12 ~ T-15 使用)** | 0 |
| **T-11** | **(生产代码,被 T-12 ~ T-15 测试覆盖)** | 0 |
| **Total** | | **37** |

**实际 case 数 37**(与 spec.md 估计 ≥31 case + plan.md 估计 ≥45 case 折中;**最小** 37 case,**目标** 45 case,可加 case 见各 T-NN「可能扩展」段)

---

## 关联文档

- `specs/021a-mcp-stdio-transport/spec.md`(WHY / WHO / WHAT / AC / EC)
- `specs/021a-mcp-stdio-transport/plan.md`(HOW / 接口契约 / 文件布局 / 测试策略)
- dsh v1.5.37 §6.5 (2.1) L4553-4871(McpServerConnection 心跳保活与重连)
- dsh v1.5.37 §6.5 (2) L4454-4551(McpTransport 调用契约)
- dsh v1.5.37 §15.9 L7211-7220(ErrorCode 编码约定)
- dsh v1.5.37 §4.10.1 硬规则 2(ToolExecutor 5 步流水线)
- dsh v1.5.37 §14.15.7(7 层测试金字塔)
- SOP v1.21 §3.2 + §3.4(R-13 自查链路)
- Story #021b mcp-tool-adapter(下一块砖)
- Story #021c mcp-sse-and-http-transport(SSE + streamable HTTP 收官,见 ROADMAP 行6)
- ROADMAP §6.5 (2.1) 提议 Story 列表第 4 行