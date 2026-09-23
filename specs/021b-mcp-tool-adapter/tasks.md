# Story #021b `mcp-tool-adapter` — Tasks

> **Status**: Draft 2026-09-23
> **Implements**: `specs/021b-mcp-tool-adapter/plan.md`
> **Test budget**: ≥ 24 cases / 7 files(L1 13 + L2 6 + L3 2 + IT 3)

---

## T-01 — `McpErrorCodes` 错误码常量集中

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpErrorCodes.java`(新, ~25 行)

**实现**(对齐 plan.md §3.5):
- `public final class McpErrorCodes { private McpErrorCodes() {} ... }`
- 2 个常量:`LINGS_M01 = "LINGS-M01"`(MCP_CONNECT_FAILED,`#021a`)+ `LINGS_M02 = "LINGS-M02"`(MCP_TOOL_CALL_FAILED,`#021b`)

**Javadoc**(类级,3 段):
1. **What** — MCP 错误码常量集中处,避免散落字符串字面量
2. **Why here** — `#021a` 用 `McpTransportException` ctor 直接传字符串 `"LINGS-M01"`,散落 1 处;`#021b` 新增 M02 → 必须集中,避免后续 `#021c` / `#022` 加码时漏改
3. **Per-code 说明** — 每常量一行:`M01` = `McpServerConnectionFactory.create()` 抛,SSE/HTTP 未实现;`M02` = `McpToolAdapter.execute()` 捕获非预期 Exception 转此码

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpErrorCodesTest`(2 case:`assertM01ValueIsCorrect` / `assertM02ValueIsCorrect` / `assertPrivateConstructorRejected` via `Constructor.newInstance`)

---

## T-02 — `McpTransport` 协调者组件(主代码 1)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransport.java`(新, ~150 行)

**实现**(对齐 plan.md §3.1):
- `@Component public class McpTransport`
- 字段:`private final List<McpServerConnection> connections = new CopyOnWriteArrayList<>();`
- 方法 4 个:`connect(List<McpServerConfig>, ToolRegistry)` / `callTool(serverName, toolName, input)` / `close()` / `getConnections()` 只读
- 私有方法:`onConnectionStateChange(cfg, conn, state, registry)` / `summarizeNames()`

**Javadoc**(类级,5 段):
1. **What** — MCP server 总装组件(plan §3.1)
2. **Why here** — `#021b` 必须有协调者,`#021a` 落的 `McpServerConnection` 是单 server 视角,本类聚合 N 个 server + 转发 + register/unregister 钩子
3. **Listener 模式** — `conn.onStateChange(state -> onConnectionStateChange(...))` 注册在 `conn.start()` **之前**(plan §7 R-021b-03)
4. **命名空间** — Tool 名 = `serverName + ":" + toolName`(plan §3.1.3)
5. **与 §4.10.1 硬规则 2 兼容** — 0 改动 `ToolExecutor`

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpTransportTest`(L2,3 case):
  - `connect(emptyConfigs) → noOp`:验证 idle INFO 日志 + connections 为空
  - `callTool(unknownServer) → IllegalStateException`:验证 `Unknown MCP server`
  - `listener_triggersRegisterOnConnected`:mock conn 触发 state = CONNECTED → `registry` 收到 N 个 register 调用
  - `listener_triggersUnregisterOnDisconnected`:mock conn 触发 state = DISCONNECTED → `registry` 收到 N 个 unregister 调用
  - `close_closesAllConnections`:mock N conn → close → 全部 conn.close() 被调 + connections.clear()

---

## T-03 — `McpToolAdapter` Tool 接口包装(主代码 2)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpToolAdapter.java`(新, ~90 行)

**实现**(对齐 plan.md §3.2):
- `public class McpToolAdapter implements Tool`
- 6 字段:`McpTransport transport / String serverName / String namespacedName / String remoteToolName / String description / JsonNode inputSchema`
- ctor:`(McpTransport, String serverName, String namespacedName, McpToolDescriptor desc)` — 4 参
- 方法:`name()` = namespacedName;`description()` = desc.getDescription();`inputSchema()` = desc.getInputSchema()
- `execute(call, ctx)` 5 路径:success / error / `McpTransportException` → LINGS-M0X 已知码 / 其他 Exception → LINGS-M02 / **不抛异常**

**Javadoc**(类级,5 段):
1. **What** — MCP tool 适配器(plan §3.2)
2. **ctor 参数说明** — `serverName` 用于 callTool 第二参;`namespacedName` 用于 ToolRegistry 索引;`remoteToolName` 用于 callTool 第二参去 namespace
3. **错误语义** — 4 路径错误转化表
4. **§4.10.1 硬规则 2** — 任何情况下不抛异常
5. **JDK 8 兼容** — POJO,无 record/sealed/var

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpToolAdapterTest`(L1,6 case):
  - `name_returns_namespacedName`(ctor=3)
  - `description_returns_desc_description`(ctor=3)
  - `inputSchema_returns_desc_schema`(ctor=3)
  - `execute_success_returns_ToolResult_success`
  - `execute_mcpError_returns_ToolResult_error_with_message`
  - `execute_unexpectedException_returns_LINGS-M02_error`
  - `execute_neverThrows`:用 `assertDoesNotThrow` 强制

---

## T-04 — `McpTransportLifecycle` Spring 桥接(主代码 3)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportLifecycle.java`(新, ~80 行)

**实现**(对齐 plan.md §3.3):
- `@Component public class McpTransportLifecycle implements SmartLifecycle`
- 字段:`McpTransport transport / List<McpServerConfig> configs / ToolRegistry toolRegistry / volatile boolean running`
- ctor:3 参注入
- 方法:`start()` → `transport.connect(configs, toolRegistry)` + `running = true`;`stop()` → `transport.close()` finally `running = false`;`isRunning()` → `running`

**Javadoc**(类级,3 段):
1. **What** — Spring lifecycle 桥接(plan §3.3)
2. **为什么 SmartLifecycle 不是 @PostConstruct** — R-13 规避 `javax.annotation-api` 依赖(同 #019 `LocalToolsAutoConfiguration` rationale)
3. **phase 默认** — `Integer.MAX_VALUE - 1024`(SmartLifecycle 默认值)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpTransportLifecycleTest`(L2,3 case):
  - `start_callsConnectAndSetsRunning`
  - `stop_callsCloseAndClearsRunning`(用 try/finally 模拟 shutdown exception)
  - `isRunning_returns_field`

---

## T-05 — `McpTransportAutoConfiguration` `@Bean` 接线(主代码 4)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportAutoConfiguration.java`(新, ~70 行)

**实现**(对齐 plan.md §3.4):
- `@Configuration public class McpTransportAutoConfiguration`
- `@Bean(name = "mcpServerConfigs") public List<McpServerConfig> mcpServerConfigs(AgentConfig agentConfig)`
- 私有方法:`toRuntimeConfig(sc)` 转换器 + `toRuntimeTransport(t)` 转换
- 空配置 → `Collections.emptyList()`
- 3 心跳字段:`> 0` 才覆盖,否则走 `@Builder.Default`

**Javadoc**(类级,4 段):
1. **What** — 启动期 YAML→runtime 转换(plan §3.4)
2. **为什么不用 `@ConfigurationProperties`** — JDK 8 `@Value` POJO 无 Spring metadata,手写转换器 0 依赖
3. **Bean 名约定** — `mcpServerConfigs` 显式名(对齐 dsh §5.4 唯一 Bean 名约定,`#021a` 后的多 Provider 模式)
4. **关键不变项** — `AgentConfig.ServerConfig`(`#021a` 落地,扩 5 字段)**0 改动**

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpTransportAutoConfigurationTest`(L2,3 case):
  - `emptyConfig_returnsEmptyList`
  - `oneStdioServer_returnsOneRuntimeConfig`(逐字段 assertEquals)
  - `threeStdioServers_returnsThreeRuntimeConfigs`
  - `nullTransport_defaultsToStdio`
  - `heartbeatFields_zeroOrNegative_useDefault`(用 `McpServerConfig.builder().build()` 对比默认值)

---

## T-06 — `ToolRegistry.unregister(String)` SPI 扩展

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java`(修改, +20 行)

**实现**(对齐 plan.md §3.6.1):
- **接口**新增方法:`boolean unregister(String name);`
- 8 字段 Javadoc:`@param / @return / @since / @implSpec` 完整
- 关键约束:不抛异常 / null name 静默 false / 并发安全 MUST / Skill dual-index MUST lock-step
- **`@since 1.0.0`** 标记 — dsh 接口契约的 next minor bump

**DoD**:
- `mvn -pl lingshu-core compile` 通过(`#020a/#020b/#020c/#021a` 既有实现仍编译)
- `DefaultToolRegistryUnregisterTest`(L1,5 case,见 T-07)

---

## T-07 — `DefaultToolRegistry.unregister` 默认实现

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolRegistry.java`(修改, +25 行)

**实现**(对齐 plan.md §3.6.2):
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

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `DefaultToolRegistryUnregisterTest`(L1,5 case):
  - `unregister_existingName_returnsTrue_and_removes_from_registry`
  - `unregister_unknownName_returnsFalse_and_doesNothing`
  - `unregister_nullName_returnsFalse`
  - `unregister_skillTool_also_removes_from_skillsByName`(verify `findSkill(name) == null`)
  - `unregister_concurrent_calls_are_threadSafe`(`ExecutorService.invokeAll` 并发 unregister 50 个 name,验证 final size 一致)

---

## T-08 — `McpErrorCodesTest` L1 测试

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/mcp/McpErrorCodesTest.java`(新, ~30 行)

**测试 case**(2):
1. `assertM01Value` — `assertEquals("LINGS-M01", McpErrorCodes.LINGS_M01)`
2. `assertM02Value` — `assertEquals("LINGS-M02", McpErrorCodes.LINGS_M02)`
3. `assertPrivateConstructorRejected` — `Constructor.setAccessible(true).newInstance()` 抛 `InvocationTargetException` 包 `IllegalStateException` / `AssertionError`(utility class 不允许实例化)

**DoD**: `mvn -pl lingshu-core test -Dtest=McpErrorCodesTest` 3/3 pass

---

## T-09 — `McpToolAdapterTest` L1 测试

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpToolAdapterTest.java`(新, ~120 行)

**测试 case**(6):
1. `name_returns_namespacedName` — ctor(serverName="gh", namespacedName="gh:search", desc=...) → `adapter.name() == "gh:search"`
2. `description_returns_desc_description` — `adapter.description() == desc.description`
3. `inputSchema_returns_desc_schema` — `adapter.inputSchema() == desc.inputSchema`
4. `execute_mcpSuccess_returns_ToolResult_success` — mock transport.callTool → `McpCallResult.success("ok")` → `result.isError() == false && result.content() == "ok"`
5. `execute_mcpError_returns_ToolResult_error` — mock transport → `McpCallResult.error("fail")` → `result.isError() && result.errorMessage() == "fail"`
6. `execute_unexpectedException_returns_LINGS-M02_error` — mock transport → throw `RuntimeException("boom")` → `result.isError && result.errorMessage() contains "LINGS-M02"`
7. `execute_mcpTransportException_returns_message_with_code` — mock transport → throw `new McpTransportException("LINGS-M99", "x")` → `result.errorMessage().contains("LINGS-M99")`(验证已知码不被覆盖为 M02)
8. `execute_neverThrows` — assertDoesNotThrow(任何输入)

**DoD**: `mvn -pl lingshu-core test -Dtest=McpToolAdapterTest` 8/8 pass

---

## T-10 — `DefaultToolRegistryUnregisterTest` L1 测试

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolRegistryUnregisterTest.java`(新, ~100 行)

**测试 case**(5):
1. `unregister_existingName_returnsTrue` — register ReadTool → unregister("Read") → true + `lookup("Read") == null`
2. `unregister_unknownName_returnsFalse` — unregister("NonExistent") → false + 0 副作用
3. `unregister_nullName_returnsFalse` — unregister(null) → false
4. `unregister_skillTool_also_removes_from_skillsByName` — register SkillTool → `findSkill("X") != null` → unregister → `findSkill("X") == null`
5. `unregister_concurrentCalls_threadSafe` — 注册 N=50 tools → `ExecutorService` 并发 unregister 全部 → final size == 0

**DoD**: `mvn -pl lingshu-core test -Dtest=DefaultToolRegistryUnregisterTest` 5/5 pass

---

## T-11 — `McpTransportTest` L2 集成测试

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/mcp/McpTransportTest.java`(新, ~150 行)

**测试 case**(5):
1. `connect_emptyConfigs_logsIdle` — verify `List.of()` → connections 空 + log 内容含 "idle"
2. `connect_validConfigs_addsConnections` — mock factory + N=2 cfg → connections.size() == 2
3. `callTool_dispatchesToCorrectConnection` — 2 conn → `callTool("server1", "tool", input)` → conn1.callTool 被调,conn2 未调
4. `callTool_unknownServer_throwsIllegalState` — `callTool("missing", ...)` → `IllegalStateException("Unknown MCP server: missing")`
5. `listener_connected_triggersRegister` — mock conn 触发 CONNECTED → `registry.register` 被调 N 次(每个 tool 一次)
6. `listener_disconnected_triggersUnregister` — mock conn 触发 DISCONNECTED → `registry.unregister` 被调 N 次
7. `listener_failed_triggersUnregister` — mock conn 触发 FAILED → `registry.unregister` 被调 N 次
8. `close_closesAllConnections_andClearsList` — mock N conn → close → 全部 conn.close() 被调 + connections 空

**DoD**: `mvn -pl lingshu-core test -Dtest=McpTransportTest` 8/8 pass

---

## T-12 — `McpTransportAutoConfigurationTest` L2 集成测试

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/mcp/McpTransportAutoConfigurationTest.java`(新, ~120 行)

**测试 case**(5):
1. `nullAgentConfig_returnsEmptyList` — `mcpServerConfigs(null)` → `emptyList()`
2. `emptyMcpConfig_returnsEmptyList` — AgentConfig 默认值(无 mcp)→ emptyList
3. `oneStdioServer_returnsOneRuntimeConfig_withAllFieldsCorrect`(逐字段 assertEquals:name / command / args / env / url / transport / 3 心跳默认)
4. `threeStdioServers_returnsThreeRuntimeConfigs`
5. `heartbeatFields_setInYaml_propagateToRuntime`(set AgentConfig.ServerConfig.heartbeatIntervalMs=5000 → runtime.heartbeatIntervalMs=5000)
6. `heartbeatFields_zeroOrNegative_useMcpServerConfigDefault`(set AgentConfig.ServerConfig.heartbeatIntervalMs=0 → runtime.heartbeatIntervalMs=30000 default)

**DoD**: `mvn -pl lingshu-core test -Dtest=McpTransportAutoConfigurationTest` 6/6 pass

---

## T-13 — `McpToolAdapterIT` L3 真子进程集成测试

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpToolAdapterIT.java`(新, ~150 行)

**复用**:`#021a` `McpTestSupport` fixture + `TestMcpServer`(fake stdio MCP server)

**测试 case**(2):
1. `stdio_mcpServer_registersTool_andExecuteSucceeds` — 用 `McpTestSupport.stdioCfg(...)` 启动真 stdio MCP server → `McpTransport.connect` → 等 `ConnectionState.CONNECTED` → 验证 `registry` 收到 1 个 namespaced tool(`github:echo` 或类似)→ `ToolExecutor.dispatch` 调该 tool → `ToolResult.success`
2. `stdio_mcpServer_killed_unregistersTool` — 启动 → CONNECTED → register → `process.destroyForcibly()` → 等 `DISCONNECTED` → `registry.lookup("github:echo") == null`

**DoD**: `mvn -pl lingshu-core -Dtest=McpToolAdapterIT integration-test` 2/2 pass(test class 加 `@IntegrationTest` 注解或 Surefire 分组)

---

## T-14 — 验证流水线(全量 AC 黑盒)

**执行命令**(按顺序,全过 → 合 PR):

```bash
# AC-021b-1: 全测试 0 fail
mvn -pl lingshu-core test
# 期望:425+ tests pass, 0 fail(401 pre-existing + ≥24 new)

# AC-021b-2: 编译通过
mvn -pl lingshu-core compile
# 期望:BUILD SUCCESS

# AC-021b-3: 依赖 0 增量(R-13 mitigation (d))
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-post-021b.txt 2>&1
diff /tmp/dep-tree-pre-021b.txt /tmp/dep-tree-post-021b.txt
# 期望:0 binary delta(仅时间戳差异)

# AC-021b-4: L3 集成 2/2 pass
mvn -pl lingshu-core -Dtest=McpToolAdapterIT integration-test
# 期望:2/2 pass

# AC-021b-5: #021a 9 文件 0 改动
git diff --stat main -- lingshu-core/src/main/java/ai/lingshu/core/mcp/
# 期望:0 行改动(仅 +21b 新文件 + 修改 ToolRegistry / DefaultToolRegistry)

# AC-021b-6: LINGS-M02 出现 1 次
grep -r "LINGS-M02" lingshu-core/src/main/java/
# 期望:1 处常量(McpErrorCodes.LINGS_M02)+ 1 处硬编码(McpToolAdapter.execute catch-all)+ 0 处其他

# AC-021b-7: Unregister 5/5 pass
mvn -pl lingshu-core test -Dtest='*Unregister*'
# 期望:5/5 pass
```

**DoD**:7 项 AC 全部通过 → `git status` 干净 → commit + PR

---

## T-15 — commit + PR

**commit message**:
```
feat(core): Story #021b mcp-tool-adapter — McpTransport + McpToolAdapter + register/unregister 钩子 + LINGS-M02
```

**PR body**(贴 spec.md + plan.md + tasks.md + AC 验证输出):

```markdown
## Story #021b `mcp-tool-adapter`

Implements dsh §6.5 (2) `McpTransport` 调用契约 + `McpToolAdapter` 包装。

### 文档
- spec: `specs/021b-mcp-tool-adapter/spec.md`
- plan: `specs/021b-mcp-tool-adapter/plan.md`
- tasks: `specs/021b-mcp-tool-adapter/tasks.md`

### 改动
- 新建 5 main 文件:`McpTransport` / `McpToolAdapter` / `McpTransportLifecycle` / `McpTransportAutoConfiguration` / `McpErrorCodes`
- 修改 2 SPI:`ToolRegistry.unregister(String)` + `DefaultToolRegistry.unregister` 实现
- 新建 6 测试文件:L1 3 + L2 2 + L3 1 = ≥24 case

### R-13 dependency:tree 自查
- baseline: `#021a` 合入后的 `mvn dependency:tree` 快照(`/tmp/dep-tree-pre-021b.txt`)
- post:本 Story 合入后(/tmp/dep-tree-post-021b.txt)
- diff:**0 binary delta**(仅 build timestamp 差异)
- 复用:Spring `SmartLifecycle`(`spring-context` 已锁)+ Jackson `JsonNode`(`jackson-databind` 已锁)+ JDK 8 `CopyOnWriteArrayList`

### AC 验证(7 项全过 → 合)
- [x] AC-021b-1:`mvn -pl lingshu-core test` → ≥425 tests pass 0 fail
- [x] AC-021b-2:`mvn -pl lingshu-core compile` → BUILD SUCCESS
- [x] AC-021b-3:`mvn dependency:tree` diff → 0 binary delta
- [x] AC-021b-4:L3 集成 → 2/2 pass
- [x] AC-021b-5:`#021a` 9 文件 0 改动
- [x] AC-021b-6:`LINGS-M02` 1 处常量 + 1 处硬编码(无散落)
- [x] AC-021b-7:`*Unregister*` 5/5 pass

### 关键不变项
- `McpServerConnection` / `ConnectionState` / `McpServerConfig` / `McpServerConnectionFactory` / `StdioMcpServerConnection` / `McpTransportException` / `McpCallResult` / `McpToolDescriptor` / `McpTransportType` —— 0 改动(`#021a` 已合并)
- `ToolExecutor.dispatch()` 5 步流水线 —— 0 改动
- `PermissionPolicy.check()` —— 0 改动
- `LocalToolsAutoConfiguration` —— 0 改动
- `ToolRegistry` 7 既有方法签名 —— 0 改动(仅新增 `unregister`)

Closes #N(占位)
```

**merge 后**(参考 SOP §8 文档同步):
- `README.md` 加 #021b retrospective
- `specs/ROADMAP.md` 主链 ✅ #021b + 移到已完成段
- `constitution.md` §10 加 R-021b-01/02/03/04 风险登记
- `CLAUDE.md` v1.3.34 同步(本 Story 0 dsh 改动,版本号引用不变)