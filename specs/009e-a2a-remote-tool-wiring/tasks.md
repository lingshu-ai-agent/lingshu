# Tasks: Story #009e a2a-remote-tool-wiring

**Source plan**: `plan.md` (Story #009e)
**Created**: 2026-09-24
**Branch**: `story-009e-a2a-remote-tool-wiring`

---

## T-01: 新增 `RemoteAgentToolAutoConfiguration.java`(独立 @AutoConfiguration)

**目标**:从 `HttpJsonRpcA2aTransportAutoConfiguration` 拆出独立的 `@AutoConfiguration` 类,只暴露 `remoteAgentTool` + `remoteAgentSchemaBuilder` 两个 Bean,与 transport 解耦。

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfiguration.java`(~70 行)

**关键代码**:
```java
package ai.lingshu.a2a.client;

import ai.lingshu.core.impl.router.A2aTransportRouter;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.slot.A2aTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;

/**
 * Story #009e — Independent {@code @AutoConfiguration} for the
 * {@link RemoteAgentTool} + {@link RemoteAgentSchemaBuilder} beans.
 *
 * <p><b>Why split from {@link HttpJsonRpcA2aTransportAutoConfiguration}</b>
 * — Story #009c originally merged both beans into the transport-specific
 * AutoConfiguration (to fit CLAUDE.md §11 #4 ≤ 5 file boundary). However,
 * this meant {@code agent.a2aTransport: grpc-1.0.0} or
 * {@code agent.a2aTransport: in-process-1.0.0} users had NO
 * {@code remote_agent} tool in the LLM's tool list — the wiring gap
 * discovered in #009d testing.</p>
 *
 * <p><b>Three transports now share this single bean</b> — switching
 * {@code agent.a2aTransport} in {@code application.yml} now transparently
 * swaps the underlying transport without losing the {@code remote_agent}
 * tool. Backed by {@link A2aTransportRouter#resolve(String, AgentConfig)}
 * (§5.3.1.2).</p>
 *
 * <p><b>Registration</b> — the {@link RemoteAgentTool} bean is <b>not</b>
 * auto-registered with {@link ai.lingshu.core.slot.ToolRegistry}; explicit
 * registration is the job of {@link RemoteAgentToolLifecycle}, see #009e
 * spec.md §2 US-2 for rationale.</p>
 */
@AutoConfiguration
public class RemoteAgentToolAutoConfiguration {

    @Bean(name = "remoteAgentTool")
    public RemoteAgentTool remoteAgentTool(A2aTransportRouter router,
                                           AgentConfig cfg,
                                           ObjectMapper json,
                                           RemoteAgentSchemaBuilder schemaBuilder) {
        String transportName = cfg != null && cfg.getA2aTransport() != null
            ? cfg.getA2aTransport()
            : "http-jsonrpc-1.0.0";
        A2aTransport transport = router.resolve(transportName, cfg);

        List<AgentRef> remoteAgents = (cfg != null && cfg.getA2a() != null
            && cfg.getA2a().getRemoteAgents() != null)
            ? cfg.getA2a().getRemoteAgents()
            : Collections.<AgentRef>emptyList();
        int skillLimit = (cfg != null && cfg.getA2a() != null
            && cfg.getA2a().getDescriptionSkillLimit() > 0)
            ? cfg.getA2a().getDescriptionSkillLimit()
            : RemoteAgentTool.DEFAULT_DESCRIPTION_SKILL_LIMIT;

        return new RemoteAgentTool(transport, json, schemaBuilder, remoteAgents, skillLimit);
    }

    @Bean(name = "remoteAgentSchemaBuilder")
    public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json) {
        return new RemoteAgentSchemaBuilder(json);
    }
}
```

**验收**:
- [ ] `mvn -pl lingshu-a2a-client compile` 通过
- [ ] 文件 < 100 行
- [ ] 类级 Javadoc 引用 dsh §5.6.3 L2458-2471 草图

**依赖**:无前置

---

## T-02: 修改 `HttpJsonRpcA2aTransportAutoConfiguration.java` 删 2 @Bean

**目标**:从 `HttpJsonRpcA2aTransportAutoConfiguration` 删除 `remoteAgentSchemaBuilder` + `remoteAgentTool` 两个 `@Bean` 方法,只保留 `a2aTransportProvider_http-jsonrpc-1.0.0` 一个 Bean(职责单一化)。

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`(从 100 行 → ~30 行)

**变更**:
1. **删** `@Bean(name = "remoteAgentSchemaBuilder") public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json) { ... }` 整段(原文件 L56-64)
2. **删** `@Bean(name = "remoteAgentTool") public RemoteAgentTool remoteAgentTool(...)` 整段(原文件 L79-99)
3. **删** `import ai.lingshu.core.impl.router.A2aTransportRouter` —— 不再需要
4. **删** `import ai.lingshu.core.runtime.AgentConfig` —— 不再需要
5. **删** `import ai.lingshu.core.runtime.AgentRef` —— 不再需要
6. **删** `import java.util.Collections` —— 不再需要
7. **删** `import java.util.List` —— 不再需要
8. **改** 类级 Javadoc —— 删 "and `RemoteAgentTool` bean" 段落,改为 "Slot 9 HTTP+JSON-RPC transport provider only — `RemoteAgentTool` + `RemoteAgentSchemaBuilder` beans live in {@link RemoteAgentToolAutoConfiguration} (Story #009e)"

**保留**:`@Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0") public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider()` —— 该 Bean 与 transport 强耦合,留在此类内

**验收**:
- [ ] `mvn -pl lingshu-a2a-client compile` 通过
- [ ] 文件 < 50 行
- [ ] `httpJsonRpcA2aTransportProvider()` Bean 名不变(向后兼容 §5.4 唯一 Bean 名约定)

**依赖**:T-01

---

## T-03: 新增 `RemoteAgentToolLifecycle.java`(SmartLifecycle)

**目标**:Spring lifecycle 桥接 —— `start()` 显式 `toolRegistry.register(tool)`,`stop()` 显式 `toolRegistry.unregister(tool.getName())`,参照 §6.5 (2.1) `McpTransportLifecycle` 完整实现样板。

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolLifecycle.java`(~70 行)

**关键代码**:
```java
package ai.lingshu.a2a.client;

import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Spring lifecycle bridge (Story #009e, plan §3.3).
 *
 * <p><b>What</b> — explicit {@link ToolRegistry#register}/{@code unregister}
 * of the {@link RemoteAgentTool} bean. Without this Lifecycle, the
 * {@code remote_agent} tool would never appear in the LLM's tool list
 * (the only consumer of the bean would be {@code RemoteAgentTool}'s
 * own {@code @PostConstruct} — but {@link RemoteAgentTool} is POJO
 * without Spring dependencies).</p>
 *
 * <p><b>Why {@link SmartLifecycle} instead of {@code @PostConstruct}</b> —
 * we need <b>start + stop double hooks</b> (register on startup,
 * unregister on shutdown). {@link SmartLifecycle} provides both;
 * {@code spring-context} is already transitive, 0 new deps.
 * <ul>
 *   <li>{@code @PostConstruct} requires {@code javax.annotation-api}
 *       (JDK 9+ built-in, JDK 8 needs separate jar) — conflicts with
 *       R-13 mitigation philosophy (Story #019 rationale).</li>
 *   <li>{@code @Bean(initMethod = ...)} cannot express close.</li>
 *   <li>{@link SmartLifecycle} provides start / stop / isRunning /
 *       isAutoStartup / getPhase — fully covers both directions.</li>
 * </ul>
 *
 * <p><b>Idempotency</b> — {@code start()} and {@code stop()} are guarded
 * by a {@code running} flag so repeated calls are safe (Spring restart,
 * graceful shutdown double-firing).</p>
 *
 * <p><b>Phase</b> — default {@code Integer.MAX_VALUE - 1024} (matches
 * {@code McpTransportLifecycle}, no timing race in practice).</p>
 */
@Component
public class RemoteAgentToolLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteAgentToolLifecycle.class);

    private final RemoteAgentTool tool;
    private final ToolRegistry toolRegistry;
    private volatile boolean running;

    public RemoteAgentToolLifecycle(RemoteAgentTool tool, ToolRegistry toolRegistry) {
        this.tool = tool;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void start() {
        if (running) {
            LOG.debug("RemoteAgentToolLifecycle already started — skipping register");
            return;
        }
        toolRegistry.register(tool);
        running = true;
        LOG.info("RemoteAgentToolLifecycle started — remote_agent registered with ToolRegistry");
    }

    @Override
    public void stop() {
        if (!running) {
            LOG.debug("RemoteAgentToolLifecycle already stopped — skipping unregister");
            return;
        }
        try {
            toolRegistry.unregister(tool.getName());
        } finally {
            running = false;
        }
        LOG.info("RemoteAgentToolLifecycle stopped — remote_agent unregistered");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Default — start after all standard lifecycle beans, stop before them.
        return Integer.MAX_VALUE - 1024;
    }
}
```

**验收**:
- [ ] `mvn -pl lingshu-a2a-client compile` 通过
- [ ] 文件 < 100 行
- [ ] 类级 Javadoc 引用 dsh §6.5 (2.1) `McpTransportLifecycle` 样板
- [ ] `start()` / `stop()` 幂等保护(running flag)
- [ ] `volatile boolean running`(JDK 8 兼容,不用 `AtomicBoolean`)

**依赖**:无前置(与 T-01 独立可并行)

---

## T-04: 修改 `META-INF/spring/...imports` 加 RemoteAgentToolAutoConfiguration 行

**目标**:SPI 注册文件新增 1 行,放在 transport 三行**之前**(保证 RemoteAgentTool 先于 transport Bean 加载,避免 Router 解析循环)。

**文件**:`lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(从 3 行 → 4 行)

**变更**:
```diff
 ai.lingshu.a2a.client.RemoteAgentToolAutoConfiguration      ← 新增(#009e)
 ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration
 ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration
 ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration
```

**验收**:
- [ ] 文件包含 4 行
- [ ] `RemoteAgentToolAutoConfiguration` 行在最前(transport 之前)
- [ ] `mvn -pl lingshu-a2a-client verify` 通过(测试验证 imports 文件)

**依赖**:T-01 + T-03(否则 imports 引用的类不存在,Spring 启动失败)

---

## T-05: 新增 `RemoteAgentToolAutoConfigurationTest.java`(L1 Unit,5 case)

**目标**:单元测试覆盖 `RemoteAgentToolAutoConfiguration` 全部 Bean 注入 + transport 解析 + cfg 透传 + imports 文件验证。

**文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfigurationTest.java`(~120 行)

**关键 case**(AC-4.1):
1. `testRemoteAgentToolBeanWiring` —— Spring 容器有 `remoteAgentTool` Bean,类型 `RemoteAgentTool`
2. `testRemoteAgentSchemaBuilderBeanWiring` —— Spring 容器有 `remoteAgentSchemaBuilder` Bean
3. `testRemoteAgentToolResolvesTransportFromCfg` —— `cfg.getA2aTransport() = "grpc-1.0.0"` → `tool.getTransport()` 实际是 `GrpcA2aTransport` 实例
4. `testRemoteAgentToolResolvesTransportDefaultHttpJsonRpc` —— `cfg.getA2aTransport() == null` → fallback `"http-jsonrpc-1.0.0"`
5. `testRemoteAgentToolPropagatesRemoteAgents` —— `cfg.getA2a().getRemoteAgents()` 非空 → `tool.getRemoteAgents()` 包含同样 list
6. `testAutoConfigurationImportsIncludesThisClass` —— 反射读 imports 文件,确认包含 `RemoteAgentToolAutoConfiguration`

**模式**:`new AnnotationConfigApplicationContext(RemoteAgentToolAutoConfiguration.class)` 启动最小上下文 + 反射读 imports 文件,**不**走 `@SpringBootTest`(沿用 #007 pattern)

**验收**:
- [ ] `mvn -pl lingshu-a2a-client test -Dtest=RemoteAgentToolAutoConfigurationTest` 通过
- [ ] 5—6 case 全过

**依赖**:T-01 + T-04

---

## T-06: 新增 `RemoteAgentToolLifecycleTest.java`(L2 Slice,4 case)

**目标**:Slice 测试覆盖 `RemoteAgentToolLifecycle.start/stop` 行为,直接 wiring 不走 Spring 容器。

**文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolLifecycleTest.java`(~100 行)

**关键 case**(AC-4.2):
1. `testStartRegistersToolWithRegistry` —— `lifecycle.start()` 后 `toolRegistry.findByName("remote_agent")` 非空
2. `testStopUnregistersToolFromRegistry` —— `start()` + `stop()` 后 `findByName` 返 null
3. `testStartIsIdempotent` —— `start()` 调 2 次,第 2 次不抛异常
4. `testIsRunningReflectsState` —— start 前 false / start 后 true / stop 后 false

**模式**:`new RemoteAgentTool(mock transport, json)` + `new DefaultToolRegistry()` + `new RemoteAgentToolLifecycle(tool, registry)` 直接 wiring,**不**用 Spring 容器

**验收**:
- [ ] `mvn -pl lingshu-a2a-client test -Dtest=RemoteAgentToolLifecycleTest` 通过
- [ ] 4 case 全过

**依赖**:T-03

---

## T-07: 新增 `RemoteAgentTransportWiringIT.java`(L3 IT,5 case)

**目标**:集成测试覆盖 3 transport × register/dispatch/unregister 端到端,**核心 AC**(验证 wiring gap 已修复)。

**文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentTransportWiringIT.java`(~200 行)

**关键 case**(AC-4.4):
1. `testInProcessTransportRegistersRemoteAgentTool` —— `agent.a2aTransport: in-process-1.0.0` 配置 + Spring 容器启动 + `ToolRegistry.findByName("remote_agent")` 非空
2. `testHttpJsonRpcTransportRegistersRemoteAgentTool` —— `agent.a2aTransport: http-jsonrpc-1.0.0` 配置 + 三件套端到端
3. `testGrpcTransportRegistersRemoteAgentTool` —— `agent.a2aTransport: grpc-1.0.0` 配置 + grpc 修复验证(**核心**)
4. `testUnregisterOnStop` —— Spring start + stop 模拟,验证 unregister 后 tool 不可见
5. `testToolDispatchReachesTransport` —— `ToolExecutor.dispatch(remote_agent, call)` 端到端,验证 transport.submit() 被调

**模式**:用真实 `InProcessA2aTransport` + 真实 `ToolRegistry` + 真实 `ToolExecutor` + 真实 Spring 容器;grpc / http-jsonrpc 用本地 mock server(`com.sun.net.httpserver.HttpServer` / `MockGrpcServer`);**不**用 Mockito(规避 #007 / #008 测试已知兼容 issue)

**验收**:
- [ ] `mvn -pl lingshu-a2a-client verify -Dit.test=RemoteAgentTransportWiringIT` 通过
- [ ] 5 case 全过

**依赖**:T-01 + T-02 + T-03 + T-04

---

## T-08: 修改 `HttpJsonRpcA2aTransportAutoConfigurationTest.java` 删 2 case

**目标**:从该测试文件删除已迁移到 `RemoteAgentToolAutoConfigurationTest` 的 2 个 case,保留 3 个 transport-specific case + 更新 imports 文件期望行数(3 → 4)。

**文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java`(从 ~80 行 → ~50 行)

**变更**:
1. **删** `testRemoteAgentSchemaBuilderBeanWiring` case(整段 ~10 行)
2. **删** `testRemoteAgentToolBeanWiring` case(整段 ~10 行)
3. **改** `testAutoConfigurationImportsFileThreeLines` 期望 `lines.length == 4`(改方法名 / 改 expectedCount)

**保留**:
- `testHttpJsonRpcA2aTransportProviderBeanWiring` —— transport provider Bean 验证
- `testFourProviderBeanNamesDistinct` —— 4 个 Provider Bean 名 distinct(grpc + in-process + http-jsonrpc + remoteAgentTool 实际是普通 @Bean,不算 Provider,改回 3 Provider distinct)
- 改写为 `testThreeProviderBeanNamesDistinct`(3 个 A2aTransport Provider distinct)

**验收**:
- [ ] `mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportAutoConfigurationTest` 通过
- [ ] 3 case 全过(原 5 - 2 删 = 3)
- [ ] 删 2 case 行为已迁到 `RemoteAgentToolAutoConfigurationTest`

**依赖**:T-01 + T-04(否则 4 行 imports 验证失败)

---

## T-09: 跑全模块测试 + 验证 0 regression

**目标**:跑全模块测试,确保 #009a / #009b / #009c / #009d / #020 / #021 全部已有测试 0 regression + 本 Story 净 +12 case 全过。

**命令**:
```bash
mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify
```

**期望**:
- 321 已有 case + 5(新 AutoConfig Test) + 4(Lifecycle Test) + 5(L3 IT) - 2(删 HttpJsonRpc test) = **333 case 全过**
- 0 fail / 0 error / 0 skipped
- BUILD SUCCESS

**验收**:
- [ ] 全测试通过
- [ ] 0 fail / 0 error / 0 skipped
- [ ] L3 IT 跑通(默认 `verify` 阶段包含 IT)

**依赖**:T-05 + T-06 + T-07 + T-08

---

## T-10: R-13 mitigation (d) baseline 镜像 PASS

**目标**:验证 0 新 Maven 依赖,0 binary delta。

**命令**:
```bash
# 1. baseline 镜像
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
git stash --include-untracked
mvn -pl lingshu-a2a-client,lingshu-core dependency:tree > /tmp/deps-pre-009e.txt
git stash pop

# 2. post 检查
mvn -pl lingshu-a2a-client,lingshu-core dependency:tree > /tmp/deps-post-009e.txt
diff /tmp/deps-pre-009e.txt /tmp/deps-post-009e.txt
```

**期望**:`diff` 输出**仅时间戳行不同**,0 binary delta;`banned-dependencies` enforcer 不 fail

**验收**:
- [ ] diff 输出仅时间戳差异
- [ ] enforcer 不 fail
- [ ] PR body 末尾附 `### R-13 dependency:tree 自查` 节贴 diff 输出

**依赖**:T-09

---

## T-11: dsh v1.5.39 → v1.5.40 §13 changelog + §5.6.3.1 / §5.6.3.2 加 cross-ref

**目标**:设计文档同步 —— 加 v1.5.40 行 changelog + §5.6.3.1 L3165-3172 HttpJsonRpc AutoConfig 段改写"拆出 RemoteAgentToolAutoConfiguration" + §5.6.3.2 L3174-3320 Grpc/InProcess stub 段加 cross-ref + §5.6.4 SPI 总表 Slot 9 行更新 wiring 列。

**文件**:`dsh_agent_design.md`

**变更**:

1. **§0 标题版本号**:`v1.5.39` → `v1.5.40`
2. **§0.4 版本 blockquote**:加预本条说明
3. **§13 changelog 表头加新行**:
```
| 1.5.40 | 2026-09-24 | **Story #009e a2a-remote-tool-wiring 实施完成(RemoteAgentTool 拆独立 @AutoConfiguration + RemoteAgentToolLifecycle SmartLifecycle 显式 register/unregister + 3 transport 共享 wiring)**:... |
```
4. **§5.6.3.1 L3165-3172 段**:`HttpJsonRpcA2aTransportAutoConfiguration` 类注释改写,加"🆕 v1.5.40:本类只暴露 `a2aTransportProvider_http-jsonrpc-1.0.0`,`remoteAgentTool` + `remoteAgentSchemaBuilder` 2 Bean 已迁到独立 {@link RemoteAgentToolAutoConfiguration}(#009e),3 transport 共享"
5. **§5.6.3.2 L3174-3320 段**:GrpcA2aTransportAutoConfiguration / InProcessA2aTransportAutoConfiguration 注释加"🆕 v1.5.40:不需要在本类暴露 `remoteAgentTool`,因为 {@link RemoteAgentToolAutoConfiguration} 独立加载,通过 {@link A2aTransportRouter} 按 name 路由"
6. **§5.6.4 SPI 总表 Slot 9 行**:更新「wiring 列」(若有)+ 加 "🆕 v1.5.40 wiring 修复" 注脚

**验收**:
- [ ] `dsh_agent_design.md` L1 标题同步
- [ ] §13 changelog 加 v1.5.40 行
- [ ] §5.6.3.1 / §5.6.3.2 改写 + cross-ref
- [ ] §5.6.4 SPI 总表更新
- [ ] §0.4 版本 blockquote 加预本条说明

**依赖**:无前置(可与 T-09 并行)

---

## T-12: CLAUDE.md + SKILL.md + README.md + ROADMAP.md 同步

**目标**:周边文档同步 —— CLAUDE.md 版本号 / SKILL.md dsh 版本号引用 / README.md Story 路线图 / ROADMAP.md 已完成段。

**文件**:
1. `CLAUDE.md`:
   - L218 dsh 版本号 `v1.5.39` → `v1.5.40`
   - Last updated `2026-09-23` → `2026-09-24`
   - 末尾 v1.3.34 → v1.3.35 changelog 加本条
2. `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md`(若关键输入表 dsh 行同步):
   - 关键输入表 dsh 行 `v1.5.37` → `v1.5.40`
3. `README.md`:
   - "Story 路线图"段加 #009e retrospective(McpTransportLifecycle 同款风格)
4. `specs/ROADMAP.md`:
   - 段一 "✅ 已完成" 表加 #009e ✅ 行

**验收**:
- [ ] CLAUDE.md 4 处同步
- [ ] SKILL.md 关键输入表同步
- [ ] README.md Story 路线图同步
- [ ] ROADMAP.md 段一加 #009e 行

**依赖**:T-11

---

## T-13: git commit + PR

**目标**:提交 1 个 commit + 1 个 PR,PR body 含 spec.md + plan.md + tasks.md + AC 验证输出 + R-13 自查结果。

**commit**:
```bash
git add \
  specs/009e-a2a-remote-tool-wiring/spec.md \
  specs/009e-a2a-remote-tool-wiring/plan.md \
  specs/009e-a2a-remote-tool-wiring/tasks.md \
  lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfiguration.java \
  lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolLifecycle.java \
  lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java \
  lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfigurationTest.java \
  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolLifecycleTest.java \
  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentTransportWiringIT.java \
  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java \
  dsh_agent_design.md \
  CLAUDE.md \
  README.md \
  specs/ROADMAP.md

git commit -m "$(cat <<'EOF'
feat(a2a-client): Story #009e a2a-remote-tool-wiring — 抽 RemoteAgentToolAutoConfiguration + RemoteAgentToolLifecycle SmartLifecycle 显式 register/unregister

dsh v1.5.39 → v1.5.40
CLAUDE.md v1.3.34 → v1.3.35

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

**PR body** 模板:
```markdown
## Summary

- Story #009e a2a-remote-tool-wiring — 修复 RemoteAgentTool wiring gap(grpc / in-process 下 LLM 不可见 remote_agent)
- 抽 RemoteAgentToolAutoConfiguration 独立 @AutoConfiguration + RemoteAgentToolLifecycle SmartLifecycle 显式 register/unregister
- 3 transport 共享 wiring + 0 新 ErrorCode + 0 新 Maven 依赖

## Test plan

- [x] `mvn validate`
- [x] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core compile`
- [x] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test`
- [x] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`(含 L3 IT)

## AC Verification

- AC-1.1 / AC-1.2 / AC-1.3 / AC-1.4:RemoteAgentToolAutoConfigurationTest 5 case 全过
- AC-1.5:grpc transport 修复验证(RemoteAgentTransportWiringIT.testGrpcTransportRegistersRemoteAgentTool)
- AC-1.6:in-process transport 验证(RemoteAgentTransportWiringIT.testInProcessTransportRegistersRemoteAgentTool)
- AC-1.7:http-jsonrpc transport 向后兼容(RemoteAgentTransportWiringIT.testHttpJsonRpcTransportRegistersRemoteAgentTool)
- AC-2.1—AC-2.6:RemoteAgentToolLifecycleTest 4 case 全过
- 0 regression:#009a / #009b / #009c / #009d / #020 / #021 全部 321 已有测试 0 fail
- L3 IT 端到端:RemoteAgentTransportWiringIT 5 case 全过(含 dispatch 端到端)

## R-13 dependency:tree 自查

\`\`\`
[diff 输出 — 仅时间戳行不同,0 binary delta]
\`\`\`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

**验收**:
- [ ] commit 成功(commit message 含 Story #009e + 关键变更点)
- [ ] PR body 含 spec.md / plan.md / tasks.md + AC 验证 + R-13 自查
- [ ] Co-Authored-By 标注

**依赖**:T-09 + T-10 + T-11 + T-12

---

## Task Summary(13 个 task)

| Task | 内容 | 文件 | 依赖 | 状态 |
|---|---|---|---|---|
| T-01 | 新增 RemoteAgentToolAutoConfiguration | +1 主代码 | 无 | ⬜ |
| T-02 | 修改 HttpJsonRpcA2aTransportAutoConfiguration 删 2 @Bean | ~1 主代码 | T-01 | ⬜ |
| T-03 | 新增 RemoteAgentToolLifecycle(SmartLifecycle)| +1 主代码 | 无(可并行 T-01)| ⬜ |
| T-04 | 修改 imports 文件加 RemoteAgentToolAutoConfiguration 行 | ~1 配置文件 | T-01 + T-03 | ⬜ |
| T-05 | 新增 RemoteAgentToolAutoConfigurationTest(L1 5 case)| +1 测试 | T-01 + T-04 | ⬜ |
| T-06 | 新增 RemoteAgentToolLifecycleTest(L2 4 case)| +1 测试 | T-03 | ⬜ |
| T-07 | 新增 RemoteAgentTransportWiringIT(L3 5 case)| +1 测试 | T-01 + T-02 + T-03 + T-04 | ⬜ |
| T-08 | 修改 HttpJsonRpcA2aTransportAutoConfigurationTest 删 2 case | ~1 测试 | T-01 + T-04 | ⬜ |
| T-09 | 跑全模块测试 + 验证 0 regression | — | T-05—T-08 | ⬜ |
| T-10 | R-13 mitigation (d) baseline 镜像 PASS | — | T-09 | ⬜ |
| T-11 | dsh v1.5.40 §13 changelog + §5.6.3.1 / §5.6.3.2 加 cross-ref | ~1 文档 | 无(可与 T-09 并行)| ⬜ |
| T-12 | CLAUDE.md + SKILL.md + README.md + ROADMAP.md 同步 | 4 文档 | T-11 | ⬜ |
| T-13 | git commit + PR | — | T-09 + T-10 + T-11 + T-12 | ⬜ |

**总文件变更**:8 文件(3 新增主代码 + 2 修改主代码 + 3 新增测试 + 1 修改测试 + 1 改 imports 配置 + 4 文档)—— **严格 ≤ 5 核心 Java 边界内**(3 新增主代码)
**净新增 case**:5 + 4 + 5 - 2 = **+12 case**(321 → 333 全过)
**0 新 Maven 依赖 / 0 新 ErrorCode**
