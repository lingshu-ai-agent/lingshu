# Story #021b `mcp-tool-adapter` — Plan

> **Status**: Draft 2026-09-23
> **Implements**: `specs/021b-mcp-tool-adapter/spec.md`
> **Source**: dsh v1.5.37 §6.5 (2) L4454-4551(`McpTransport` 调用契约 + `McpToolAdapter` 包装)+ §15.9 L7211-7220(ErrorCode 编码约定 → §15.10 顺延 M02)+ §4.10.1 硬规则 2(ToolExecutor 5 步流水线不变)
> **Pre-req**: ✅ Story #021a `mcp-stdio-transport` 已合(`McpServerConnection` / `ConnectionState` / `McpServerConfig` / `McpServerConnectionFactory` / `StdioMcpServerConnection` / `McpTransportException` / `McpCallResult` / `McpToolDescriptor` / `McpTransportType` 9 件全部就位)
> **Next**: Story #021c `mcp-sse-and-http-transport`(`SseMcpServerConnection` + `StreamableHttpMcpServerConnection` + factory 移除 LINGS-M01 throw 兜底)消费 `McpTransport` / `McpToolAdapter` 0 改动

---

## §1 范围与非范围

### In-Scope(7 核心文件 + 1 测试目录)

| 文件 | 行为 | 行数预算 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransport.java`(新)| `@Component`,`connect(List<McpServerConfig>, ToolRegistry)` + `callTool(serverName, toolName, input)` + `close()` + 私有 `onConnectionStateChange()` + `connections: CopyOnWriteArrayList<McpServerConnection>` | ~150 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpToolAdapter.java`(新)| `public class implements Tool`,ctor `(McpTransport, String serverName, String namespacedName, McpToolDescriptor)` + name/description/inputSchema + execute 转发 transport + 异常转 `LINGS-M02` | ~90 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportLifecycle.java`(新)| `@Component implements SmartLifecycle`,start → transport.connect,stop → transport.close,isRunning | ~80 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportAutoConfiguration.java`(新)| `@Configuration` + `@Bean(name="mcpServerConfigs")` 把 `AgentConfig.Mcp.servers` 解构为 `List<McpServerConfig>` runtime config | ~70 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpErrorCodes.java`(新)| `final class` 静态常量,`LINGS_M01 / LINGS_M02`(集中 MCP 域错误码,避免散落) | ~25 |
| `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java`(修改)| 新增 `boolean unregister(String name)` 方法(Javadoc 完整描述,向后兼容) | +20 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolRegistry.java`(修改)| 实现 `unregister`(ConcurrentHashMap.remove + Skill dual-index 同步清理) | +25 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/mcp/`(新)| 6 测试文件 + 复用 `#021a` `McpTestSupport` | ~600 行 / ≥18 case |

**7 核心文件 = 5 新建 + 2 修改 + 1 测试目录**,1 新 ErrorCode(`LINGS-M02` `MCP_TOOL_CALL_FAILED`),`mvn dependency:tree` **0 增量**(MCP transport 用 JDK 内置 `ProcessBuilder` + Jackson `JsonNode` + Spring `SmartLifecycle` + Spring `@Configuration`,无新增 Maven 坐标)。

### Out-of-Scope(显式 deferred)

- **`SseMcpServerConnection` / `StreamableHttpMcpServerConnection` —— Story #021c sse-and-http-transport**
  - dsh §6.5 (2.1) L4821-4835 的 SSE 差异段(心跳 `GET /health` / 重连重建 HttpClient / `EventSource` 收 server push),代码侧 0 实现 — 留给 #021c
  - `McpServerConnectionFactory.create()` 当前 SSE/STREAMABLE_HTTP 分支 throw `LINGS-M01`(`MCP_CONNECT_FAILED`),#021c 移除 throw,加 2 个 concrete impl
- **`McpCardCache` / 增量拉取 / `tools/listChanged` 推送** —— §5.6.3.0 `AgentCardCache` 模式留给 §14 N1(OpenTelemetry)同批
- **MCP tool 的 `capabilities` / `PermissionPolicy` MCP 域决策** —— §6.5 (2) 文档未涉及,留 OQ-Future
- **`@Tool` MCP tool 调用 circuit-breaker / retry** —— §14 N2/N3 独立 Story
- **`McpToolAdapter` 加 `outputSchema` / `examples` 字段** —— §5.6.3.0 OQ-2 OQ-Future
- **`ToolRegistry.unregister(Tool)` 重载** —— 当前 McpTransport 已知 name,直接传 name,OQ-Future
- **`McpTransportAutoConfiguration` 改用 `@ConfigurationProperties` 元数据绑定** —— JDK 8 `@Value` POJO 无 Spring metadata;手写转换器保持

---

## §2 接口契约

### 2.1 `McpTransport` 公开契约

```java
public void connect(List<McpServerConfig> configs, ToolRegistry registry);
public McpCallResult callTool(String serverName, String toolName, JsonNode input);
public void close();
public List<McpServerConnection> getConnections();   // 只读快照
```

### 2.2 `McpToolAdapter` 公开契约(`implements Tool`)

```java
public McpToolAdapter(McpTransport transport, String serverName,
                       String namespacedName, McpToolDescriptor desc);
public String name();         // = namespacedName = serverName + ":" + desc.getName()
public String description();  // = desc.getDescription()
public JsonNode inputSchema(); // = desc.getInputSchema()
public ToolResult execute(ToolCall call, ToolExecutionContext ctx);  // 不抛异常
```

### 2.3 `ToolRegistry` SPI 新增(`🆕 Story #021b`)

```java
boolean unregister(String name);
```

**向后兼容**:仅新增方法,不影响 `#020a` / `#020b` / `#020c` / `#021a` 的现有调用方;`DefaultToolRegistry` 默认实现遵循 first-wins + Skill dual-index cleanup。

### 2.4 `McpErrorCodes` 集中常量

```java
public final class McpErrorCodes {
    private McpErrorCodes() {}
    public static final String LINGS_M01 = "LINGS-M01";   // MCP_CONNECT_FAILED (#021a)
    public static final String LINGS_M02 = "LINGS-M02";   // MCP_TOOL_CALL_FAILED (#021b)
}
```

---

## §3 实现细节

### 3.1 `McpTransport` 实现要点

**3.1.1 字段**
- `private final List<McpServerConnection> connections = new CopyOnWriteArrayList<>();`
  - 选 `CopyOnWriteArrayList` 是因为 listener 触发时遍历(`onConnectionStateChange`)与 `close()` 时遍历并发安全
- `private static final Logger LOG = LoggerFactory.getLogger(McpTransport.class);`

**3.1.2 `connect(configs, registry)` 流程**
1. `configs == null || configs.isEmpty()` → 打 INFO 日志 "idle",return
2. 遍历 `configs`:
   - `factory.create(cfg)` → `McpServerConnection`(`#021a` 已落地)
   - `conn.onStateChange(state -> onConnectionStateChange(cfg, conn, state, registry))` 注册 listener
   - `connections.add(conn)`
   - `conn.start()`(**异步非阻塞** — `#021a` `StdioMcpServerConnection.start` 同步完成后 `transition(CONNECTED)` 再 `scheduleAtFixedRate` 心跳)
3. 异常处理:`factory.create()` 抛 `McpTransportException`(`LINGS-M01`,SSE/HTTP 还没实现)→ 打 ERROR 日志,**不重试**(等 `#021c`)
4. 结束:INFO 日志 `connected to N server(s): [name1, name2, ...]`

**3.1.3 `onConnectionStateChange(cfg, conn, state, registry)` 私有方法**
1. `state == CONNECTED`:
   - 遍历 `conn.listTools()`,每个 tool:
     - `namespacedName = cfg.getName() + ":" + t.getName()`
     - `registry.register(new McpToolAdapter(this, cfg.getName(), namespacedName, t))`
   - INFO 日志 `[MCP:NAME] (re)connected, N tools registered`
2. `state == DISCONNECTED || state == FAILED`:
   - 遍历 `conn.listTools()`,每个 tool:
     - `namespacedName = cfg.getName() + ":" + t.getName()`
     - `registry.unregister(namespacedName)`
   - WARN 日志 `[MCP:NAME] disconnected (STATE), N tools unregistered`
3. `CONNECTING / RECONNECTING / IDLE` → 不动 registry(中间态)

**3.1.4 `callTool(serverName, toolName, input)` 转发**
1. `connections.stream().filter(c -> c.name().equals(serverName)).findFirst()` 找到目标 connection
2. `orElseThrow(() -> new IllegalStateException("Unknown MCP server: " + serverName))`
3. `conn.callTool(toolName, input)` 返回 `McpCallResult`

**3.1.5 `close()` 优雅停机**
1. INFO 日志 `shutting down — closing N connection(s)`
2. 遍历 `new ArrayList<>(connections)`(快照,避免 `CopyOnWriteArrayList` 并发修改)
3. 每个 conn `close()`,捕获异常打 WARN
4. `connections.clear()`

### 3.2 `McpToolAdapter` 实现要点

**3.2.1 字段**
- `McpTransport transport` —— 转发目标
- `String serverName` —— 用于 `transport.callTool(serverName, remoteToolName, input)`
- `String namespacedName` = `serverName + ":" + desc.getName()` —— 用于 `ToolRegistry` 索引(`name()` 返回值)
- `String remoteToolName` = `desc.getName()` —— 用于 `transport.callTool` 第二参数(去掉命名空间)
- `String description` = `desc.getDescription()` —— 缓存
- `JsonNode inputSchema` = `desc.getInputSchema()` —— 缓存

**3.2.2 `execute(call, ctx)` 流程**
1. `transport.callTool(serverName, remoteToolName, call.getInput())` 转发
2. `r.isError()` true → `ToolResult.error(call.getId(), r.getErrorMessage())`
3. false → `ToolResult.success(call.getId(), r.getContent())`
4. catch `McpTransportException` → 打 WARN 日志 + `ToolResult.error(call.getId(), "MCP call failed [CODE]: " + e.getMessage())`
5. catch 其他 `Exception` → 打 WARN 日志 + `ToolResult.error(call.getId(), "MCP call failed [LINGS-M02]: " + e.getMessage())`

**3.2.3 不抛异常保证**(§4.10.1 硬规则 2)
- `ToolResult.error` 路径覆盖所有失败情况
- 任何 `Exception` 都 catch-all,绝不让异常逃出 `execute()` 边界

### 3.3 `McpTransportLifecycle` 实现要点

**3.3.1 SmartLifecycle 选择 rationale**
- `#019` `LocalToolsAutoConfiguration` 已用 `InitializingBean`(单阶段 init,无 stop 钩子)
- MCP 需要 **start + stop 双钩子**,`SmartLifecycle` 同时提供,`spring-context` 已 transitive,**0 依赖**
- `phase` 默认 `Integer.MAX_VALUE - 1024`(SmartLifecycle 默认)→ 在所有标准 lifecycle bean 之后启动

**3.3.2 流程**
1. `start()`: `transport.connect(configs, toolRegistry)`,设 `running = true`,INFO 日志
2. `stop()`: `transport.close()`,`finally { running = false; }`,INFO 日志
3. `isRunning()`: 返回 `running` volatile field

### 3.4 `McpTransportAutoConfiguration` 实现要点

**3.4.1 `@Bean(name = "mcpServerConfigs")` 单一职责**
- 输入:`AgentConfig`(已 `#001` 落地)
- 输出:`List<McpServerConfig>`(`#021a` runtime POJO)
- 职责:把 YAML 绑定层(`AgentConfig.Mcp.servers`)转换为 runtime 层(`McpServerConfig`)
- 命名:显式 Bean 名,符合 `#021a` 之后的"唯一 Bean 名约定"(见 dsh §5.4)

**3.4.2 `toRuntimeConfig(sc)` 转换器**
- 必传:`name / transport / command / args / env / url`(从 `AgentConfig.ServerConfig` 直传)
- 三个心跳参数:`getHeartbeat*() > 0` 才覆盖,否则走 `McpServerConfig` `@Builder.Default` 默认值
- **关键不变项**:`AgentConfig.ServerConfig`(`#021a` 落地,扩 5 字段)**0 改动**

**3.4.3 `toRuntimeTransport(t)` 转换**
- `AgentConfig.McpTransportType.STDIO` → `McpServerConfig.Transport.STDIO`
- `SSE` / `STREAMABLE_HTTP` → 同名(`#021c` 才实现 factory 分支)
- null → 默认 `STDIO`
- 未知值 → 抛 `IllegalArgumentException`(启动期 fail-fast)

### 3.5 `McpErrorCodes` 实现要点

- 放 `ai.lingshu.core.impl.mcp` 包(与 `McpTransport` 同包)
- `final class` + 私有 ctor
- 2 个 `public static final String` 常量
- Javadoc 标注 `#021a` / `#021b` 来源

### 3.6 `ToolRegistry.unregister(String)` 实现要点

**3.6.1 SPI 设计**(`ToolRegistry.java`)
- 方法签名:`boolean unregister(String name)`
- 返回值语义:**true** = 有 tool 被移除,**false** = 该 name 未注册(无论从未注册还是已 unregister 过)
- `null name` → 静默返 `false`,不抛异常
- 并发:`McpTransport` listener 线程 + Tool dispatch 线程并发调用 → 实现必须 `ConcurrentHashMap.remove` 原子
- Skill dual-index 同步:被 unregister 的 tool 如果是 `Skill`,`skillsByName.remove(name)` 必须 lock-step
- 线程安全:**MUST** 支持并发调用
- Javadoc 完整:8 个 `@param` / `@return` / `@since` / `@implSpec` 字段

**3.6.2 默认实现**(`DefaultToolRegistry.java`)
```java
@Override
public boolean unregister(String name) {
    if (name == null) return false;
    Tool removed = registry.remove(name);
    if (removed == null) return false;
    if (removed instanceof Skill) {
        skillsByName.remove(name);
    }
    LOG.info("Unregistered tool: name={} class={}",
        name, removed.getClass().getSimpleName());
    return true;
}
```

---

## §4 文件清单(精确路径)

```
lingshu-core/src/main/java/ai/lingshu/core/
├── impl/
│   └── mcp/
│       ├── McpTransport.java                          (新, ~150 行)
│       ├── McpTransportLifecycle.java                 (新, ~80 行)
│       ├── McpTransportAutoConfiguration.java         (新, ~70 行)
│       └── McpErrorCodes.java                         (新, ~25 行)
├── mcp/
│   └── McpToolAdapter.java                            (新, ~90 行)
├── slot/
│   └── ToolRegistry.java                              (修改, +20 行 unregister 方法)
└── impl/
    └── tool/
        └── DefaultToolRegistry.java                   (修改, +25 行 unregister 实现)

lingshu-core/src/test/java/ai/lingshu/core/
├── impl/
│   └── mcp/
│       ├── McpTransportTest.java                      (新, ~150 行)
│       ├── McpToolAdapterTest.java                    (新, ~120 行)
│       ├── McpTransportLifecycleTest.java             (新, ~80 行)
│       ├── McpTransportAutoConfigurationTest.java     (新, ~120 行)
│       ├── McpErrorCodesTest.java                     (新, ~30 行)
│       └── DefaultToolRegistryUnregisterTest.java     (新, ~100 行)
└── mcp/
    └── McpToolAdapterIT.java                          (新, L3 集成 ~150 行,复用 McpTestSupport)
```

**总计**:5 新建 main + 2 修改 + 6 新建 test + 1 新建 IT = **14 文件 / ~1300 行 / ≥ 24 case**。

---

## §5 测试策略

### 5.1 L1 单元测试(无 Spring / 无子进程)— 16 case

| 文件 | case 数 | 覆盖 |
|---|---:|---|
| `McpErrorCodesTest` | 2 | 常量值正确 + 私有 ctor 拒止 |
| `McpToolAdapterTest` | 6 | name() = namespaced;description() = desc;inputSchema() = desc;success → ToolResult.success;isError → ToolResult.error;`McpTransportException` → 转 error message 含 code;其他 Exception → 转 `LINGS-M02` error |
| `DefaultToolRegistryUnregisterTest` | 5 | name 命中 → 移除返 true;name 未注册 → 返 false;null name → false;Skill 同时从 skillsByName 清理;并发安全(`ConcurrentHashMap.remove`)|

### 5.2 L2 集成测试(有 Spring 上下文 / Mock transport)— 6 case

| 文件 | case 数 | 覆盖 |
|---|---:|---|
| `McpTransportTest` | 3 | `connect(empty)` → idle 日志;`connect(N server) → connections.add N 次`;`callTool(serverName, toolName, input)` 转发正确;`close()` → 所有 connection close;listener 触发 `onConnectionStateChange(CONNECTED)` → registry.register;listener 触发 `DISCONNECTED` → registry.unregister |
| `McpTransportAutoConfigurationTest` | 3 | 0 server 配置 → empty list;1 stdio server → 1 McpServerConfig;3 stdio server → 3 McpServerConfig;`AgentConfig.ServerConfig.getHeartbeat*()` 0 → 默认值;`getTransport()` null → 默认 STDIO |

### 5.3 L3 集成测试(真子进程)— 2 case

| 文件 | case 数 | 覆盖 |
|---|---:|---|
| `McpToolAdapterIT` | 2 | (1) stdio MCP server 子进程启动 → adapter 注册到 ToolRegistry → LLM 调 tool → ToolResult.success;(2) stdio MCP server 子进程 kill → adapter unregister → ToolRegistry.lookup() 返 null |

### 5.4 关键不变项检查表

| 项 | 不变原因 | 验证方式 |
|---|---|---|
| `McpServerConnection` interface 8 方法 | `#021a` 已合并 | `git diff --stat HEAD~1 -- lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnection.java` 0 行 |
| `ConnectionState` enum 6 态 | `#021a` 已合并 | 同上 |
| `McpServerConfig` POJO | `#021a` 已合并 | 同上 |
| `McpServerConnectionFactory` factory dispatch | `#021a` 已合并 | 同上 |
| `StdioMcpServerConnection` 完整实现 | `#021a` 已合并 | 同上 |
| `McpTransportException` 异常载体 | `#021a` 已合并 | 同上 |
| `McpCallResult` 数据类 | `#021a` 已合并 | 同上 |
| `McpToolDescriptor` 数据类 | `#021a` 已合并 | 同上 |
| `McpTransportType` enum 3 值 | `#021a` 已合并 | 同上 |
| `AgentConfig.ServerConfig` 5 字段扩 | `#021a` 已合并 | 同上 |
| `ToolExecutor.dispatch` 5 步流水线 | §4.10.1 硬规则 2 | `git diff --stat HEAD~1 -- lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutor.java` 0 行 |
| `PermissionPolicy.check()` | §4.7 | 同上 |
| `LocalToolsAutoConfiguration` 注册 4 内置 tool | `#019` 已合并 | 同上 |
| `ToolRegistry` 7 既有方法签名 | 向后兼容 | `git diff --stat HEAD~1 -- lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java` 仅新增 `unregister` |

### 5.5 AC-NN 黑盒验证(全过 → 合 PR)

| AC | 验证 | 期望 |
|---|---|---|
| AC-021b-1 | `mvn -pl lingshu-core test` | 401 + ≥24 new = ≥425 tests pass, 0 fail |
| AC-021b-2 | `mvn -pl lingshu-core compile` | 0 编译错误 |
| AC-021b-3 | `mvn dependency:tree -pl lingshu-core` | 0 增量坐标(对比 baseline 镜像) |
| AC-021b-4 | `mvn -pl lingshu-core -Dtest=McpToolAdapterIT integration-test` | L3 集成 case 2/2 pass |
| AC-021b-5 | git diff `--stat` main → branch | **0 行**改动 `#021a` 已合 9 文件 |
| AC-021b-6 | `LINGS-M02` 错误码出现 1 次 | `grep -r "LINGS-M02" lingshu-core/src/main/java/` 命中 1 处(`McpErrorCodes`) + 1 处(`McpToolAdapter.execute()` 转 error message) + 1 处测试 |
| AC-021b-7 | `mvn -pl lingshu-core test -Dtest='*Unregister*'` | `DefaultToolRegistryUnregisterTest` 5/5 pass |

---

## §6 依赖检查(R-13 mitigation (d))

### 6.1 新增 Maven 依赖

**0 个新增**。

| 现有依赖 | 本 Story 用法 |
|---|---|
| `spring-context`(已锁)| `@Component` / `@Configuration` / `SmartLifecycle` / `@Bean` |
| `spring-beans`(transitive)| `@Autowired`(McpTransportLifecycle ctor)| |
| `jackson-databind`(已锁)| `JsonNode` / `ObjectMapper` |
| `slf4j-api`(已锁)| `LoggerFactory` |
| JDK 8 内置 | `CopyOnWriteArrayList` / `Collections.emptyList()` / `ConcurrentHashMap.remove` |

### 6.2 dependency:tree 自查(baseline 镜像法)

```bash
# 1. baseline 镜像(本 Story 实施前)
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-pre-021b.txt 2>&1

# 2. 实施 Story #021b 后
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-post-021b.txt 2>&1

# 3. diff(应仅时间戳差异)
diff /tmp/dep-tree-pre-021b.txt /tmp/dep-tree-post-021b.txt
```

期望:`0 binary delta`(允许 diff 仅显示 build timestamp)。

### 6.3 R-13 mitigation (d) 假设确认

✅ Spring `SmartLifecycle`(`spring-context`)已锁 → 启动期 `connect` 不用 `@PostConstruct` / `@PreDestroy`(规避 `javax.annotation-api` 依赖)
✅ JDK 内置 `CopyOnWriteArrayList` / `ConcurrentHashMap` → 并发集合 0 依赖
✅ Jackson `JsonNode`(`jackson-databind` 已锁)→ 不用 `jackson-module-jsonSchema`
✅ Lombok `@Value` + `@Builder`(`lingshu-core/pom.xml` 已锁)→ 配置类 + 数据类 0 样板

---

## §7 风险登记

### R-021b-01 — `ToolRegistry.unregister` SPI 扩展破坏现有实现(已被 #020a/#020b 实现锁定)

**风险**:`DefaultToolRegistry` `#020a` 落地,但**任何用户 plugin 提供 `ToolRegistry` 自定义实现**(尚未有,但 #021 之后可能)会因接口新增方法编译失败。

**缓解**:
- `unregister` 标记 `@since 1.0.0`(语义版本 minor bump 不破坏)
- 默认实现提供,**用户 plugin 通常 extend `DefaultToolRegistry` 而非直接实现 interface)
- 接口设计支持 default method(`default boolean unregister(String name) { return false; }`)? — **不**,因为 default method 在 JDK 8 interface 编译会生成 `$default$unregister$0` 桥接方法,lingshu-core 已在 #020 系列明确不用 default method(对齐 #020a 决策)。改为:
- 接口 Javadoc 强标:`@implSpec 实现 MUST 提供此方法,否则 MCP unregister hook 失效`
- **`constitution.md` §10** 加风险登记:`R-021b-01` 影响用户 plugin 实现,Story 合入后置低优先级

### R-021b-02 — `McpTransport` listener 异常可能破坏后续 register/unregister

**风险**:`StdioMcpServerConnection.transition()`(dsh §6.5 (2.1) L4774-4785)已设计 listener 异常隔离,但 `McpTransport` 自己的 listener 体(`onConnectionStateChange`)抛异常会污染 `connections` 列表。

**缓解**:
- `onConnectionStateChange` 内部 try/catch(per-tool register/unregister 单点失败不影响其他 tool)
- `registry.register(...)` 已经按 `#020a` `putIfAbsent` 语义,duplicate → WARN log,**不抛**
- `registry.unregister(...)` 设计 silent no-op,不会抛
- 但 `McpToolAdapter` ctor 抛(空指针 / IAE)→ wrap try/catch 隔离
- **测试**:`McpTransportTest` 故意 throw exception 的 listener → 验证 `connections` 列表仍稳定

### R-021b-03 — `McpTransportLifecycle.start()` 在 `McpServerConnection.start()` 异步失败时 silent

**风险**:`McpServerConnection.start()`(异步)首次失败走 `scheduleReconnect()`,**不抛异常**;`McpTransport.connect()` 调用 `conn.start()` 后立即返回,**listener 永远没机会注册**。

**缓解**:
- `McpServerConnectionFactory.create()` 抛 `LINGS-M01` / `IllegalStateException` 是同步捕获点
- `conn.onStateChange(...)` **必须在** `conn.start()` 之前注册 → 代码顺序保证(plan §3.1.2 step 2 在 step 4 之前)
- **测试**:`McpTransportTest` 用 mock conn → 验证 listener 注册先于 start
- 后续 `#021c` SSE/HTTP 实现需遵守同样顺序(`McpTransport` 0 改动)

### R-021b-04 — `McpServerConfigYmlBinder` 与 `AgentConfig.ServerConfig` 字段同步 drift

**风险**:`#021a` 已在 `AgentConfig.ServerConfig` 扩 5 字段,本 Story 通过 `McpTransportAutoConfiguration.toRuntimeConfig()` 复制转换,字段名 / 类型 drift 可能沉默。

**缓解**:
- `toRuntimeConfig()` 用 `@VisibleForTesting` 标记,**测试覆盖**每个字段映射
- `McpTransportAutoConfigurationTest` 验证所有 11 字段(name / command / args / env / url / transport / 3 heartbeat / 1 reconnect)100% 覆盖
- **测试 AC**:每个字段 `assertEquals` 直接对比
- 若 `#021c` 新增字段(如 `authToken`),需要重新跑 `McpTransportAutoConfigurationTest` 验证

---

## §8 文档同步(合入后必做)

| 项 | 操作 |
|---|---|
| `README.md` | 「Story 路线图」段追加 #021b retrospective(`McpTransport` 5 段职责 + `McpToolAdapter` 包装 + `ToolRegistry.unregister` SPI 扩展 + `LINGS-M02` + R-13 0 binary delta)|
| `specs/ROADMAP.md` | 主链 #021b ✅ + 移到「✅ 已完成」表 |
| `dsh_agent_design.md` §13 changelog | 新增 v1.5.38 行(若本 Story 触发 dsh 修订);本 Story **0 dsh 改动**(只补 `ToolRegistry.unregister` SPI,代码契约层)— dsh §6.5 (2) L4538 已经是 single-source-of-truth |
| `constitution.md` §10 | 加 R-021b-01 / 02 / 03 / 04 风险登记;Story 合入后置低优先级(R-021b-01) / 已缓解(02-04)|
| `CLAUDE.md` v1.3.34 | 对应设计文档版本号同步(本 Story **0 dsh 改动**,版本号不变) + SOP / prompts / SKILL 版本号引用同步 |
| `.claude/skills/lingshu-spec-driven-dev/SKILL.md` | 若 SKILL 流程不变,**不动** |

---

## §9 实施顺序

按 T-NN tasks.md 顺序执行,每 T-NN 一个 commit:

1. **T-01** ~ **T-05**:5 新建 main 文件(`McpTransport` / `McpToolAdapter` / `McpTransportLifecycle` / `McpTransportAutoConfiguration` / `McpErrorCodes`)
2. **T-06** ~ **T-07**:`ToolRegistry` + `DefaultToolRegistry` SPI 扩展
3. **T-08** ~ **T-13**:测试(L1 3 文件 / L2 2 文件 / L3 1 文件)
4. **T-14**:`mvn validate` + `mvn compile` + `mvn test` + dep-tree 自查
5. **T-15**:commit + PR body 贴 spec.md + plan.md + tasks.md + AC 验证输出