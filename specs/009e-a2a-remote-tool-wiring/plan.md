# Implementation Plan: Story #009e a2a-remote-tool-wiring

**Source spec**: `spec.md` (Story #009e)
**Created**: 2026-09-24
**Anchor**: dsh v1.5.39 §5.6.2 L2366 + §5.6.3 L2458-2471 + §5.6.4 L3322-3334 + §6.5 (2.1) McpTransportLifecycle

---

## 1. Architecture Decision Summary

| 维度 | 决策 |
|---|---|
| **抽哪里** | `RemoteAgentToolAutoConfiguration` 独立 `@AutoConfiguration`(从 `HttpJsonRpcA2aTransportAutoConfiguration` 拆出) |
| **生命周期** | `RemoteAgentToolLifecycle implements SmartLifecycle`(参照 §6.5 (2.1) `McpTransportLifecycle` 完整实现样板) |
| **3 transport 共享** | 通过 `A2aTransportRouter.resolve(cfg.getA2aTransport(), cfg)` 按名路由,transport 切换无需改 Spring config |
| **register 时机** | `SmartLifecycle.start()` 而非 `@PostConstruct`(避免 `javax.annotation-api` 依赖,R-13 mitigation philosophy) |
| **Bean 名约定** | `@Bean(name = "remoteAgentTool")` + `@Bean(name = "remoteAgentSchemaBuilder")` —— §5.4 唯一 Bean 名约定 |
| **SPI 加载顺序** | `META-INF/spring/...imports` 顺序:`RemoteAgentToolAutoConfiguration` → `GrpcA2aTransportAutoConfiguration` → `InProcessA2aTransportAutoConfiguration` → `HttpJsonRpcA2aTransportAutoConfiguration`(RemoteAgentTool 先于 transport,避免 Router 解析循环) |
| **幂等保护** | `volatile boolean running` flag —— start / stop 重复调用 safe |
| **向后兼容** | `RemoteAgentTool` 2/3/5 参构造器**全部保留**;#009c / #009d 测试 0 regression(只 HttpJsonRpcA2aTransportAutoConfigurationTest 删 2 case 是有意迁移到 RemoteAgentToolAutoConfigurationTest) |

---

## 2. File-Level Plan(5 核心 Java 源文件)

### 2.1 新增文件

| # | 路径 | 类型 | 行数估算 | 职责 |
|---:|---|---|---:|---|
| 1 | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfiguration.java` | `@AutoConfiguration` | ~70 | 暴露 `remoteAgentTool` + `remoteAgentSchemaBuilder` 两个 `@Bean`,与 transport 解耦 |
| 2 | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolLifecycle.java` | `@Component implements SmartLifecycle` | ~70 | `start()` register / `stop()` unregister,参照 §6.5 (2.1) `McpTransportLifecycle` 样板 |
| 3 | `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 修改 | +1 行 | 新增 `ai.lingshu.a2a.client.RemoteAgentToolAutoConfiguration` 行(放在 transport 三行之前) |

**测试新增文件**:

| # | 路径 | 类型 | 行数估算 | 职责 |
|---:|---|---|---:|---|
| 4 | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfigurationTest.java` | L1 Unit | ~120 | 5 case:Bean wiring + transport resolve + cfg propagation + imports 文件 |
| 5 | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolLifecycleTest.java` | L2 Slice | ~100 | 4 case:start register / stop unregister / start 幂等 / isRunning 状态 |
| 6 | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentTransportWiringIT.java` | L3 IT | ~200 | 5 case:3 transport × register / dispatch / unregister 端到端 |

### 2.2 修改文件

| # | 路径 | 类型 | 行数估算 | 变更 |
|---:|---|---|---:|---|
| 7 | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java` | 简化 | -30 行 | **删** 2 `@Bean` 方法(`remoteAgentSchemaBuilder` + `remoteAgentTool`),**保留** `a2aTransportProvider_http-jsonrpc-1.0.0`;删对应 imports |
| 8 | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java` | 简化 | -30 行 | **删** 2 case(`testRemoteAgentSchemaBuilderBeanWiring` + `testRemoteAgentToolBeanWiring`),保留 3 case(transport provider Bean + 4 Provider Bean 名 distinct + imports 验证) |

**总:8 文件(3 新增 + 2 修改主代码 + 3 新增测试)**,**严格 ≤ 5 核心 Java 边界内**(3 新增主代码文件,符合 §11 #4)。

---

## 3. Implementation Strategy

### 3.1 顺序(4 步走)

| 步骤 | 内容 | 验收 |
|---|---|---|
| **Step 1** | 新增 `RemoteAgentToolAutoConfiguration.java`(独立 @AutoConfiguration) | `mvn -pl lingshu-a2a-client compile` 过 |
| **Step 2** | 修改 `HttpJsonRpcA2aTransportAutoConfiguration.java` 删 2 `@Bean` + 改 imports 文件 | 同上 |
| **Step 3** | 新增 `RemoteAgentToolLifecycle.java`(SmartLifecycle) | 同上 |
| **Step 4** | 跑测试 + R-13 自查 | `mvn verify` 全过 + `mvn dependency:tree` 0 binary delta |

**依赖关系**:Step 1 与 Step 3 独立可并行,Step 2 必须 Step 1 之后(避免编译期找不到 Bean 类型),Step 4 必须 Step 1—3 全完成后。

### 3.2 关键代码模式

**Pattern 1**:独立 `@AutoConfiguration` 与 §5.5 Slot 1—7 默认 Provider stub 同款形态

```java
@AutoConfiguration
public class RemoteAgentToolAutoConfiguration {
    @Bean(name = "remoteAgentTool")
    public RemoteAgentTool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg,
                                           ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder) {
        // ... 从 cfg 拉 transportName / remoteAgents / skillLimit
        return new RemoteAgentTool(transport, json, schemaBuilder, remoteAgents, skillLimit);
    }

    @Bean(name = "remoteAgentSchemaBuilder")
    public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json) {
        return new RemoteAgentSchemaBuilder(json);
    }
}
```

**Pattern 2**:`SmartLifecycle` 参照 §6.5 (2.1) `McpTransportLifecycle` 样板

```java
@Component
public class RemoteAgentToolLifecycle implements SmartLifecycle {
    private final RemoteAgentTool tool;
    private final ToolRegistry toolRegistry;
    private volatile boolean running;

    public RemoteAgentToolLifecycle(RemoteAgentTool tool, ToolRegistry toolRegistry) {
        this.tool = tool;
        this.toolRegistry = toolRegistry;
    }

    @Override public void start() {
        if (running) return;  // 幂等保护
        toolRegistry.register(tool);
        running = true;
        LOG.info("RemoteAgentToolLifecycle started — remote_agent registered");
    }

    @Override public void stop() {
        if (!running) return;  // 幂等保护
        try { toolRegistry.unregister(tool.getName()); }
        finally { running = false; }
        LOG.info("RemoteAgentToolLifecycle stopped — remote_agent unregistered");
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 1024; }
}
```

**Pattern 3**:`HttpJsonRpcA2aTransportAutoConfiguration` 删 2 @Bean(留 transport provider)

```java
@AutoConfiguration
public class HttpJsonRpcA2aTransportAutoConfiguration {
    // ✅ 保留 — transport provider Bean
    @Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")
    public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider() {
        return new HttpJsonRpcA2aTransportProvider();
    }
    // ❌ 删 — remoteAgentTool + remoteAgentSchemaBuilder 迁到 RemoteAgentToolAutoConfiguration
}
```

---

## 4. Interface Contracts(关键不变项)

### 4.1 `RemoteAgentTool` 类**不**改

```java
public class RemoteAgentTool implements Tool {
    public RemoteAgentTool(A2aTransport transport, ObjectMapper json) { ... }                      // #009c 2 参
    public RemoteAgentTool(A2aTransport, ObjectMapper, RemoteAgentSchemaBuilder) { ... }            // #009d 3 参
    public RemoteAgentTool(A2aTransport, ObjectMapper, RemoteAgentSchemaBuilder,                    // #009d 5 参
                           List<AgentRef>, int descriptionSkillLimit) { ... }
    // 5 参构造器 + name() + description() + inputSchema() + execute() 全部不变
}
```

### 4.2 `RemoteAgentSchemaBuilder` 类**不**改(#009d 已落地,本 Story 复用)

### 4.3 `A2aTransportRouter` 接口 / 行为**不**改(#009a 已落地)

### 4.4 `ToolRegistry` SPI **不**改(#020a 已落地)

### 4.5 `A2aTransport` 5 方法契约**不**改

### 4.6 `ToolExecutor` 5 步流水线**不**改(§4.10.1 硬规则 2)

### 4.7 SPI imports 文件加载顺序

```
ai.lingshu.a2a.client.RemoteAgentToolAutoConfiguration      ← 新增,先加载
ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration       ← #009a 已有
ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration ← #009b 已有
ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration ← #009c 已有(简化后)
```

---

## 5. Test Strategy(7 层金字塔覆盖)

### 5.1 L1 Unit — `RemoteAgentToolAutoConfigurationTest`(5 case)

| Case | 验证 |
|---|---|
| `testRemoteAgentToolBeanWiring` | Spring 容器有 `remoteAgentTool` Bean,类型是 `RemoteAgentTool`(AC-1.1) |
| `testRemoteAgentSchemaBuilderBeanWiring` | Spring 容器有 `remoteAgentSchemaBuilder` Bean,类型是 `RemoteAgentSchemaBuilder`(AC-1.3) |
| `testRemoteAgentToolResolvesTransportFromCfg` | `cfg.getA2aTransport() = "grpc-1.0.0"` → `tool.getTransport()` 实际是 `GrpcA2aTransport` 实例(AC-1.5) |
| `testRemoteAgentToolResolvesTransportDefaultHttpJsonRpc` | `cfg.getA2aTransport() == null` → fallback `"http-jsonrpc-1.0.0"`(AC-1.7 + EC-2) |
| `testRemoteAgentToolPropagatesRemoteAgents` | `cfg.getA2a().getRemoteAgents()` 非空 → `tool.getRemoteAgents()` 包含同样 list(AC-1.2) |
| `testAutoConfigurationImportsIncludesThisClass` | 反射读 `META-INF/spring/...imports` 文件,确认包含 `RemoteAgentToolAutoConfiguration` 全限定名(AC-1.4) |

**模式**:直接 `new AnnotationConfigApplicationContext(RemoteAgentToolAutoConfiguration.class)` 启动最小上下文,**不**走 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue,沿用 Story #007 pattern)

### 5.2 L2 Slice — `RemoteAgentToolLifecycleTest`(4 case)

| Case | 验证 |
|---|---|
| `testStartRegistersToolWithRegistry` | `lifecycle.start()` 后 `toolRegistry.findByName("remote_agent")` 非空,返回 `RemoteAgentTool` 实例(AC-2.3) |
| `testStopUnregistersToolFromRegistry` | `lifecycle.start()` + `lifecycle.stop()` 后 `toolRegistry.findByName("remote_agent")` 返 null(AC-2.4) |
| `testStartIsIdempotent` | `start()` 调 2 次,第 2 次不抛异常(幂等保护,AC-2.3 + EC-1) |
| `testIsRunningReflectsState` | start 前 false / start 后 true / stop 后 false(AC-2.5) |

**模式**:直接 wiring —— `new RemoteAgentTool(...)` + `new DefaultToolRegistry()` + `new RemoteAgentToolLifecycle(tool, registry)`,**不**走 Spring 容器(简化 + 验证 lifecycle 行为是 RemoteAgentToolLifecycle 自身行为,不是 Spring 装配副作用)

### 5.3 L3 IT — `RemoteAgentTransportWiringIT`(5 case)

| Case | 验证 |
|---|---|
| `testInProcessTransportRegistersRemoteAgentTool` | `agent.a2aTransport: in-process-1.0.0` 配置 + 真实 Spring 容器启动 + `ToolRegistry.findByName("remote_agent")` 非空(AC-1.6) |
| `testHttpJsonRpcTransportRegistersRemoteAgentTool` | `agent.a2aTransport: http-jsonrpc-1.0.0` 配置 + `HttpJsonRpcA2aTransportAutoConfiguration` + `RemoteAgentToolAutoConfiguration` + `RemoteAgentToolLifecycle` 三件套端到端,ToolRegistry 有 `remote_agent`(AC-1.7) |
| `testGrpcTransportRegistersRemoteAgentTool` | `agent.a2aTransport: grpc-1.0.0` 配置 + `GrpcA2aTransportAutoConfiguration` 加载 + ToolRegistry 有 `remote_agent`(AC-1.5,**grpc 修复验证**) |
| `testUnregisterOnStop` | Spring 容器 start 后 `ToolRegistry` 有 tool,模拟 `lifecycle.stop()` 后 LLM tool 列表不再含 `remote_agent`(AC-2.4) |
| `testToolDispatchReachesTransport` | `ToolExecutor.dispatch(remote_agent, call)` 端到端,验证 `transport.submit(...)` 被调,返回 `ToolResult`(AC-1.6 + §4.10.1 硬规则 2 兼容) |

**模式**:用真实 `InProcessA2aTransport` + 真实 `ToolRegistry` + 真实 `ToolExecutor` + 真实 Spring 容器;grpc / http-jsonrpc 用本地 mock server(`com.sun.net.httpserver.HttpServer` / `MockGrpcServer`);**不**用 Mockito

### 5.4 L1 Regression — `HttpJsonRpcA2aTransportAutoConfigurationTest` 删 2 case

| Case | 操作 |
|---|---|
| ~~`testRemoteAgentSchemaBuilderBeanWiring`~~ | **删**(迁到 `RemoteAgentToolAutoConfigurationTest.testRemoteAgentSchemaBuilderBeanWiring`) |
| ~~`testRemoteAgentToolBeanWiring`~~ | **删**(迁到 `RemoteAgentToolAutoConfigurationTest.testRemoteAgentToolBeanWiring`) |
| `testHttpJsonRpcA2aTransportProviderBeanWiring` | 保留(#009c 行为不变) |
| `testFourProviderBeanNamesDistinct` | 保留(#009c 行为不变) |
| `testAutoConfigurationImportsFileThreeLines` | 保留(#009c 行为不变;**更新** 期望行数 3 → 4 行,因为加 RemoteAgentToolAutoConfiguration) |

**净变更** :-2 case + ~30 行

### 5.5 回归覆盖

| Story | 已有测试 | 0 regression 要求 |
|---|---|---|
| #009a grpc | ~10 case | ✅ transport AutoConfiguration 行为不变 |
| #009b in-process | ~10 case | ✅ transport AutoConfiguration 行为不变 |
| #009c http-jsonrpc | 17 case | ✅ `RemoteAgentTool` 2 参构造器 + 行为不变,只删 2 个 AutoConfiguration 内部 test case |
| #009d schema-builder | 17 case | ✅ `RemoteAgentSchemaBuilder` 类不变,只迁 @Bean 位置 |
| #020a / #020b / #020c Skill | ~20 case | ✅ `ToolRegistry.register` 行为不变 |
| #021a / #021b / #021c MCP | ~80 case | ✅ `McpTransportLifecycle` phase 不冲突;`ToolRegistry.register` 兼容 |
| **总回归** | **~321 case** | **0 fail / 0 error** |

---

## 6. R-13 Mitigation (d) Self-Check(必须执行)

```bash
# 1. baseline 镜像
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
git stash --include-untracked
mvn -pl lingshu-a2a-client,lingshu-core dependency:tree > /tmp/deps-pre-009e.txt

# 2. 实施 Story #009e
git stash pop
# (按 tasks.md 实施)

# 3. post 检查
mvn -pl lingshu-a2a-client,lingshu-core dependency:tree > /tmp/deps-post-009e.txt
diff /tmp/deps-pre-009e.txt /tmp/deps-post-009e.txt
# 期望:仅时间戳行不同,0 binary delta

# 4. banned-dependencies enforcer
mvn -pl lingshu-a2a-client,lingshu-core verify
# 期望:BUILD SUCCESS,enforcer 不 fail
```

**预期结果**:`SmartLifecycle` 来自 `spring-context`(已 transitive 锁)+ `A2aTransportRouter` / `ToolRegistry` / `RemoteAgentTool` / `RemoteAgentSchemaBuilder` 全部已存在 —— **0 新 Maven 依赖**,**0 binary delta**。

---

## 7. Risk Register(本 Story 新增 / 缓解)

| Risk | 分值 | 缓解 |
|---|---:|---|
| **R-19**:RemoteAgentTool wiring gap(grpc / in-process 下 LLM 不可见 remote_agent) | 9 | 本 Story 全部缓解(拆独立 AutoConfig + SmartLifecycle 显式 register) |
| **R-20**:RemoteAgentToolLifecycle 与 McpTransportLifecycle 同 phase 时序竞争 | 4 | `getPhase()` 默认值 + Bean name 字典序定顺序(McpTransportLifecycle `mcpTransportLifecycle` 先,RemoteAgentToolLifecycle `remoteAgentToolLifecycle` 后),**不阻塞**(MCP tool 缺不影响 remote_agent) |
| **R-21**:ToolRegistry.register 重复注册抛 IllegalStateException 影响 Spring 启动 | 3 | `RemoteAgentToolLifecycle.start()` 幂等保护(`running` flag),只在 `running == false` 时 register |

---

## 8. Validation Checklist

- [ ] `mvn validate` 通过
- [ ] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core compile` 通过
- [ ] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test` 通过(净 +12 case,共 333 case)
- [ ] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify` 通过(含 L3 IT)
- [ ] `mvn dependency:tree -pl lingshu-a2a-client,lingshu-core` 与 baseline 一致(0 binary delta)
- [ ] `banned-dependencies` enforcer 不 fail
- [ ] dsh §13 changelog 加 v1.5.40 行 + §5.6.3.1 / §5.6.3.2 加 cross-ref + §5.6.4 SPI 总表更新
- [ ] CLAUDE.md 版本号同步 v1.3.34 → v1.3.35
- [ ] SKILL.md 同步(若关键输入表 dsh 版本号引用)
- [ ] README.md Story 路线图加 #009e retrospective
- [ ] specs/ROADMAP.md 段一加 #009e ✅ 行
- [ ] PR body 含 spec.md + plan.md + tasks.md + AC 验证输出 + R-13 自查结果
- [ ] 全部 321 已有测试 0 regression

---

## 9. Out of Scope(本 Story 不做)

- N-tool 模式(OQ-1 revisit trigger)
- AgentSkill 加 inputSchema / outputSchema 字段(OQ-2)
- PromptBuilder 接入 ToolSpec list(OQ-5)
- 启动日志自动打印 ToolSpec 列表(OQ-6)
- §14.8 hot-reload 触发 RemoteAgentSchemaBuilder.refresh()
- ToolRegistry SPI 改动
- 新 Maven 依赖
- 新 ErrorCode
- RemoteAgentTool / RemoteAgentSchemaBuilder 类本身改动
- HttpJsonRpcA2aTransportAutoConfiguration 删 2 @Bean 之外的其他改动
- GrpcA2aTransportAutoConfiguration / InProcessA2aTransportAutoConfiguration 改动
- A2aTransportRouter 改动
- §6.5 (2) MCP Tool wiring 改造
