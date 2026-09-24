# Story #022 `spring-ai-annotation-tool` — Spec

> **Status**: Draft 2026-09-24
> **Source**: dsh v1.5.40 §6.5 (3) L4873-4980(`@AgentTool` 注解 + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` 三件套) + §4.6 Tool / §4.10.1 硬规则 2 / §6.4 Skill 边界 / §15.4 ErrorCode 域 + constitution v1.0
> **前置依赖**:`spring-ai-bom` 1.0.0-M6 已锁(constitution §2 第 13 项)+ `Tool` interface 已落(`#003`)+ `ToolRegistry.register / unregister / lookup` 已扩(`#020a` + `#021b`)+ `ToolExecutor.dispatch()` 5 步流水线不变(`#004` §4.10.1)+ Skill-2-index 兼容(`#020a`)。**#022 无 Story 前置**,可与 `#009e` / `#023` 并行(ROADMAP 段二 🎯 节奏)

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.40 §6.5 (3) L4873-4980「(3) SpringAI 注解 Tool — Scheme 由注解生成,执行走我们自己」
- **对应 AC**: dsh §0.4 AC-04(默认 ReAct 闭环中 LLM 看到 tool schema,调 tool,ToolExecutor 处理) — Spring AI 注解 Tool 是该 AC 的第 3 条 Scheme 来源(手写 JSON Schema / MCP server / @AgentTool 反射)
- **对应风险**: R-13(`spring-ai-bom` 误用 / binary 膨胀)Mitigation (d) 强制自查
- **涉及 ErrorCode**: **LINGS-T08 `TOOL_REFLECTION_FAILED`**(dsh §15.4 域字母 T 第 8 号;#022 新增;**修正 ROADMAP drift** — ROADMAP 段二表第 7 行列的「LINGS-T02 (反射调用失败)」是抄写误差,§15.4 L7167-7177 表中 LINGS-T02 已用于 `TOOL_TIMEOUT`(`ToolExecutor.dispatch()` 抛 `toolTimeoutSec` 超),不可复用,启用 T08 是 §15.4 域字母 T 编号顺延的下一个空位)

---

## 1. WHY(为什么做这个 Story)

dsh §6.5 (3) L4856-4865 已经画出了 LingShu 工具体系的「统一视图」—— `Tool` interface 是单一入口,3 条 Scheme 来源(hand-written JSON Schema / MCP server / @AgentTool 反射)在 `ToolExecutor.dispatch()` 这一层全部归一,**但代码侧只落地了前两条**:

- **第 1 条**:`#019` built-in-tools 落地 Read / Write / Edit / Bash 4 个手写 `Tool` 实现(dsh §6.5 (1))—— `LocalToolsAutoConfiguration.afterPropertiesSet()` 隐式 `Map<String, Tool>` 注入 `ToolRegistry`
- **第 2 条**:`#021b` mcp-tool-adapter 落地 `McpTransport` + `McpToolAdapter` + `ToolRegistry.unregister()` SPI 扩展—— MCP server 暴露的 tool 自动注入,断开自动撤回
- **第 3 条**:**本 Story #022** —— SpringAI `@Tool` 注解风格在 LingShu 内的等价物 `@AgentTool`,扫 Spring 容器里所有带 `@AgentTool` 注解的方法,运行时包成 `SpringAiToolAdapter implements Tool`,与 `ToolRegistry.register()` / `ToolExecutor.dispatch()` 衔接

**业务后果** — 当前 `lingshu-core` 仓存在 3 个连锁 gap:

1. **`@AgentTool` 注解 0 实现** — dsh §6.5 (3) L4883-4889 字面给了 `@Target(METHOD) @Retention(RUNTIME) public @interface AgentTool { String name(); String description(); String[] capabilities() default {}; }` 完整定义,**完全没有对应源码**。grep `lingshu-core/src/main/java/` 全部 0 命中。
2. **`SpringAiToolAdapter` 0 实现** — dsh §6.5 (3) L4896-4956 字面给了 ~60 行 `public class SpringAiToolAdapter implements Tool { ctor(Object bean, Method method, AgentTool annotation); name() / description() / inputSchema() / execute(ToolCall, ToolExecutionContext); private generateSchemaFromMethod(Method); private jsonTypeOf(Class<?>) }` 完整骨架 + `execute()` 内 反射调用 + `InvocationTargetException` 捕获 + `ToolResult.error` 转 LLM 可见 error + `Exception` catch-all,**0 源码**。
3. **`AgentToolScanner` + `JsonArgsConverter` 0 实现** — dsh L4961-4979 给 `AgentToolScanner implements ApplicationContextAware { setApplicationContext(ctx) { ctx.getBeansWithAnnotation(Component.class).values() → 反射循环找 @AgentTool → registry.register(new SpringAiToolAdapter(...)) } }` 完整 ~20 行 + `JsonArgsConverter.convert(JsonNode input, Parameter[] params)` 工具类,**0 源码**。

**Story #022 业务价值**:
- 用户在 `@Component` Bean 上挂 `@AgentTool(name="...", description="...")` 注解,**重启后 Agent 启动期自动注册成 Tool**,LLM 通过 `[TOOL SCHEMAS]` 段看到该 tool 并可调
- 用户已有的业务方法(如 `userService.createOrder(...)`、`invoiceService.generatePdf(...)`)被 1 行注解包装成 Agent tool,**零样板代码**(对比手写一个 `Tool implements Xxx` 类需要写 4 方法 + yaml 注册)
- 与 `#019` 手写 Tool 路径对齐:三件套(注解 + 适配器 + 扫描器)组合产出与 `Read / Write / Edit / Bash` 同等生命周期行为
- 与 `#021b` MCP 路径对齐:同样在 Spring ApplicationContext 启动期扫描,但走本地反射(不跨进程)

**关键不变项**:
- `Tool` interface 5 方法 + `ToolRegistry` interface 8 方法 + `ToolExecutor.dispatch()` 5 步流水线(`#004` §4.10.1)**全部 0 改动**
- `#019` 落地的 4 个 hand-written Tool 仍注册路径不变(`LocalToolsAutoConfiguration.afterPropertiesSet`)
- `#021b` 落地的 `McpTransport` + `McpToolAdapter` 不变
- dsh §4.10.1 硬规则 1(ReAct 自实现)+ 硬规则 2(Spring AI **不**走 `ChatClient.prompt().call()` 自动工具执行)+ 硬规则 3(Provider 显式映射)全部不变
- 0 新 Maven 依赖 — `spring-ai-bom` 已锁,本 Story 直接 import `spring-ai-core`(已 transitive),`JsonArgsConverter` 用 Jackson `ObjectMapper`(已锁 #5)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 在公司已有 `@Service` 上加 `@AgentTool(name="createOrder", description="创建订单")` 注解,**0 样板代码**就暴露给 Agent;复用现有 Spring Bean 容器,不需要把业务方法搬出 `@Configuration`/`@Component` |
| **Agent 框架贡献者 / 插件作者(Bob 类)** | 写 starter / 插件时,在 starter jar 内 `@Component public class XxxTools { @AgentTool(...) public String foo(...) {...} }` 自动被 `AgentToolScanner` 拉到 `ToolRegistry`,LLM 自动看到,无需 starter 调用方 yml 配任何东西 |
| **运维稳定性关注者(Eve 类)** | 注解 method 抛异常 → `SpringAiToolAdapter.execute()` catch + 转 `ToolResult.error` + LINGS-T08 ErrorCode,LLM 看到 error message 后可重试或换策略;**不会绕过 `PermissionPolicy.check()` / SandboxApply / Checkpoint** 任一步(§4.10.1 硬规则 2) |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `SpringAiToolAdapter` / `AgentToolScanner` / `JsonArgsConverter` 3 类;L2 slice 用真实 `ApplicationContext` 跑 `AgentToolScanner.setApplicationContext()` 验证注册路径;L3 集成测 LLM → ToolExecutor → @AgentTool 端到端;`mvn dependency:tree` 0 增量(R-13 mitigation (d) 第 7 次验证) |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**:
- 用户在 `@Component` Bean 的某个方法上标注 `@AgentTool(name="...", description="...")`,Spring 容器启动时 `AgentToolScanner` 自动把该方法包装成 `SpringAiToolAdapter`,注册进 `ToolRegistry`
- `ToolRegistry.modelVisibleSpecs()` 包含 `SpringAiToolAdapter` 实例(与其余 4 个 Read/Write/Edit/Bash tool 一视同仁),LLM 在 `[TOOL SCHEMAS]` 段看到该 tool 的 JSON Schema(由方法签名反射生成)
- ReAct Action 阶段 LLM 发出 `tool_use(name="createOrder", input={"itemId": "..."})` → `ToolExecutor.dispatch()` → 走 5 步流水线(`PermissionPolicy` → `registry.lookup("createOrder")` → `TimeoutWrap` → `SandboxApply` → `adapter.execute()` → `Checkpoint`)→ `SpringAiToolAdapter.execute()` 通过 `method.invoke(bean, args)` 反射调原方法 → `ToolResult.success` → LLM 看到

**新配置参数**(`AgentConfig` 当前无需变更;注解是 `Method` 级 metadata,不进 yml):

| 注解字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `name()` | 是 | — | tool 名(对应 `Tool.name()`),LLM 看到;唯一性约束(`ToolRegistry.register` dup 走 first-wins + log warn) |
| `description()` | 是 | — | tool 描述(LLM 决策时用) |
| `capabilities()` | 否 | `{}` | 预留,`PermissionPolicy.check()` 决策用;**`#022` 不消费**,**OQ-Future**(`/permit.list` 接入时一并启用) |

**用户会看到的错误码**:

| ErrorCode | 抛出位置 | 触发条件 | 用户响应 |
|---|---|---|---|
| **LINGS-T08 `TOOL_REFLECTION_FAILED`** | `SpringAiToolAdapter.execute()` 内部 catch-all | `@AgentTool` 标注的方法 `method.invoke(bean, args)` 抛 `InvocationTargetException` 包裹的业务异常,或其他 `Exception`(e.g. `IllegalAccessException` / `IllegalArgumentException` 参数类型不匹配) | 检查 `@AgentTool` 方法实现 / 检查 LLM 输出参数类型与 method signature 是否对齐 |

**关键不变量**(不变项):
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 全部 0 改动
- `#019` 落地的 `Read / Write / Edit / Bash` 4 个 hand-written Tool 注册路径不变
- `#021b` 落地的 `McpTransport` + `McpToolAdapter` 不变
- `ToolRegistry.unregister(String)` 仍为唯一 unregister 入口;`#022` **不**主动调 unregister(`@Component` Bean 销毁 = 整个 JVM 退出,无需 unregister)
- LINGS-T08 是**新增** ErrorCode;LINGS-T01—T07 编号全部不动

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — 注解扫描 + 注册完整路径

**Given** 一个 `@Component public class CalcTools { @AgentTool(name="add", description="两数相加") public int add(int a, int b) { return a + b; } }` 在 `ApplicationContext` 内
**When** Spring 容器刷新完毕(`AgentToolScanner.setApplicationContext(ctx)` 被回调)
**Then** `ToolRegistry.lookup("add") != null` 且 `lookup("add")` 返回的实例 `instanceof SpringAiToolAdapter` 且 `.description()` 返回 `"两数相加"`,且 `ToolRegistry.names()` 集合包含 `"add"`
**断言方式**:L2 Slice 测试用 `AnnotationConfigApplicationContext` + `AgentToolScanner` 注册 + `DefaultToolRegistry.lookup(...)`

### AC-NN-2 — 反射调用 + ToolResult 包装

**Given** 同 AC-NN-1 场景,且 `add(int a, int b)` 方法已通过 AC-NN-1 注册到 `ToolRegistry`
**When** `ToolExecutor.dispatch(new ToolCall("id-1", "add", objectMapper.readTree("{\"a\": 2, \"b\": 3}")), ctx)` 被调用
**Then** 返回 `ToolResult.success("id-1", "5")`,且方法真实返回 `int 5`(断言通过 mock/spy 在 `CalcTools.add` 上挂 counter 验证 1 次调用 + 2 / 3 参数传递正确)
**断言方式**:L2 Slice 测试用真实 `ToolExecutor.dispatch` 调用链(sandbox 设为 pass-through,permission 设为 allow-all)

### AC-NN-3 — 反射失败转 ToolResult.error + LINGS-T08 编码

**Given** 一个 `@Component public class FailingTool { @AgentTool(name="boom", description="必抛") public String boom() { throw new RuntimeException("svc down"); } }` 在 ctx 内,已注册
**When** `ToolExecutor.dispatch(new ToolCall("id-2", "boom", objectMapper.createObjectNode()), ctx)`
**Then** 返回 `ToolResult.error("id-2", "...")`,`result.errorCode() == "LINGS-T08"`(dsh §15 编码约束 — 抛出方必带 `errorCode` 字段),且**不**抛异常(对齐 §4.10.1 硬规则 2)
**断言方式**:L2 Slice 测试,无 `try/catch` 包裹 dispatch — 任何异常飞出来就是 bug

### AC-NN-4 — R-13 mitigation (d) 依赖零增量

**Given** Story #022 引入 `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` + `@AgentTool` 注解 + `AutoConfiguration`
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#021b` pre-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;关键是 `spring-ai-bom:1.0.0-M6` 仍为 transitive,**不**引入 `spring-ai-spring-boot-starter`(banned list 第 1 条)或 `com.knuddels:jtokkit`(banned list 第 2 条)或 `io.netty:netty-all` 冲突对版本
**断言方式**:对照 `specs/021b-mcp-tool-adapter/` PR body 末尾的 `### R-13 dependency:tree 自查` 节

### AC-NN-5 — JsonArgsConverter primitive 类型转换

**Given** 方法签名 `public String format(String prefix, int count, boolean upper)` + JSON 输入 `{"prefix": "hi", "count": 3, "upper": true}`
**When** `JsonArgsConverter.convert(inputNode, method.getParameters())`
**Then** 返回 `Object[] { "hi", 3, true }`(类型严格匹配 `int` non-nullable,非 `Integer` boxed)
**断言方式**:L1 Unit 测试覆盖 6 类 primitive(String / int / long / boolean / double / Integer nullable)+ 1 类 collection failure(`List<String>` 字段用户传 `null` 走默认空 list)+ 1 类 unknown type → `IllegalArgumentException` 全表

### AC-NN-6 — `capabilities()` 字段占位(不消费)

**Given** 一个 `@AgentTool(name="x", description="y", capabilities={"db.write", "fs.write"})` 标注
**When** 容器启动 + `ToolRegistry.lookup("x")` 返回实例
**Then** `instanceof SpringAiToolAdapter` 且 `.description() == "y"`(capabilities 字段**保留**但不消费,`PermissionPolicy` 暂未接)
**断言方式**:L1 Unit 测试断言 `method.getAnnotation(AgentTool.class).capabilities()` 长度 == 2 + `adapter.execute()` 调用不读 capabilities 字段(`PermitPolicy` 模拟)

### AC-NN-deps-1(R-13 mitigation (d) — 强制)

**Given** 当前 Story 引入 / 修改依赖
**When** 跑 `mvn dependency:tree -pl lingshu-core -Dverbose`
**Then** 输出中**必须不包含** `banned-dependencies` 列表(见上)的任何条目,关键子树(>= 3 层的 `spring-ai-*` / `com.knuddels:*` / `io.netty:*` / `com.fasterxml.jackson.*` 版本冲突对)贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节

### AC-NN-deps-2(R-13 mitigation (d) — 强制)

**Given** Story #022 引入 Spring AI 等横向依赖
**When** `mvn -pl lingshu-core verify` 跑 enforcer
**Then** `banned-dependencies` 规则**必须在 build 阶段 fail**(若依赖没碰,run 配置 `enforcer.skip=true` 显式跳过 + 在 PR body 说明)

**banned-dependencies 列表**(dsh §17 R-13 (b)):
- `org.springframework.ai:spring-ai-spring-boot-starter`(全家桶,**任何 Story 都不许引**)
- `org.springframework.ai:spring-ai-vector-store-*`
- `org.springframework.ai:spring-ai-etl-*`
- `org.springframework.ai:spring-ai-spring-boot-starter` 等横切 starter
- `com.knuddels:jtokkit`(自己引 tokenizers,不允许)
- `io.netty:netty-all` 版本冲突对(LingShu 锁定 4.1.106.Final,任何 Story 引入必须 match)
- `com.fasterxml.jackson.*` 主版本号不一致(锁定 2.15.x 系列)

**豁免流程**:Story 实施者认为有正当理由要引 banned 条目 → 必须开 issue 描述场景 + 受影响 AC + R-13 缓解修订方案 → RFC review → 批准后才能改 banned 列表

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| Schema 生成走 `jackson-module-jsonSchema` 的 `MethodSchema` | dsh §6.5 (3) L4930-4946 明确「简化:读参数类型 + @ToolParam 描述」 — production 可用 jackson-module-jsonSchema 但 #022 走简化路径,**只覆盖 String / primitive / Boolean / Integer 等基础类型**,复杂类型(Object / Map / List / 自定义 POJO)返回 `object` placeholder(OQ-Future 留给 Story 进入 prompt cache 阶段再补) |
| `@AgentTool` 支持 static method 或 `@Bean` 工厂方法 | dsh §6.5 (3) L4970 原文写 `ctx.getBeansWithAnnotation(Component.class).values()`,**只扫 `@Component` Bean 实例**,不扫 static method,也不扫 `@Bean` 工厂方法 — 实施时严格对齐 dsh |
| `@ToolParam(name="...", description="...")` 字段级注解 | dsh §6.5 (3) L4931 注释明确「生产可用 jackson-module-jsonSchema」,**当前简化路径不实现** — parameter 描述走方法 Javadoc 或 `description` 字段(待 OQ-Future 启动时再补) |
| `@AgentTool` 方法的 AOP 织入(权限 / 审批门 / audit log)| `PermissionPolicy.check()` 在 §4.7 已就位但**不消费** `@AgentTool.capabilities()` 字段 — 留给 §14.10 audit log / §4.7 权限审批门 Story 时一并启用;`#022` 只做最简平的反射 wrap |
| 智能感知 methods 重载(overload)多 schema 暴露 | dsh §6.5 (3) L4971 `bean.getClass().getMethods()` 一次性反射,**不做**重载消歧;同名 method 只取第一个发现(按 `getMethods()` 顺序) — 用户写 `@AgentTool` 时**应保持方法名唯一** |
| 启动期对 @AgentTool name 唯一性预校验 | dsh `ToolRegistry.register` 是 dup-name first-wins + log warn(已落,见 `DefaultToolRegistry.register`);`#022` **不**做启动期 fail-fast,行为对齐 — 留给 `#019` 后续 consolidation Story |
| `AgentToolScanner` 自动监听 Bean 生命周期(unregister on destroy) | `#021b` McpTransport 走 listener 模式处理 DISCONNECTED,`#022` 无对应远端资源 → `@Component` Bean 与 JVM 同生命周期,无需 unregister;**故意不引入 SmartLifecycle** — `#022` 比 `#021b` 简单一档 |
| Agent-level 功能开关(`agent.agentTool.enabled: true/false`)| 默认全开;`@AgentTool` 是轻量零配置扩展,需要关闭的用户**不写注解即可** — config field 是 over-engineering(OQ-Future) |
| 反射方法调用的超时 / 重试 / 熔断 | dsh §14.2 RetryPolicy / §14.3 CircuitBreaker 由 `ToolExecutor.dispatch()` 第 3 步 `TimeoutWrap` 统一管,与 Tool 自身实现无关;**`#022` 不重复实现** |
| 测试覆盖用 SpringBootTest(`@SpringBootTest`) 启动整个上下文 | dsh §4.10.1 硬规则 2 不能依赖完整 Spring 启动路径(规避 Mockito 5.x + JDK 23 inline-mock 兼容 issue,见 `#007` Story 经验) — 测试用裸 `AnnotationConfigApplicationContext` + 手装 `AgentToolScanner` + `DefaultToolRegistry` 即可 |

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#003` spi-slot-router — `Tool` interface 契约 + `ToolRegistry` interface 契约(register / lookup / names / findByName / findSkill / skillNames / modelVisibleSpecs)
  - `#004` tool-parallel-dispatch — `ToolExecutor.dispatch()` 5 步流水线
  - `#019` built-in-tools — `LocalToolsAutoConfiguration.afterPropertiesSet()` 手装 `Map<String, Tool>` 隐式注册路径样板(`#022` 参考同款模式但 Spring 现成 `ApplicationContextAware`)
  - `#020a` skill-foundation — `ToolRegistry` Skill 2-index 兼容,`SpringAiToolAdapter` 不是 `Skill`(不写 `/xxx` CLI),但保持双 index 不污染
  - `#021b` mcp-tool-adapter — `ToolRegistry.unregister(String)` SPI 扩展(虽 `#022` 不调,留兼容)
  - `spring-ai-bom` 1.0.0-M6 — `#003` 引入(constitution §2 第 13 项)
- **后续 Story(本 Story 是其前置)**:
  - `#023` delegate-sub-agent — Sub-agent 通过 `DelegateTool` 间接调其他 tool,可能复用 `#022` 的反射路径调子 Agent 自己的 `@AgentTool`(OQ-Future,`#023` 当前锁定走 `DelegateProps.type` 字段)
  - §14.10 N10 audit-log — `AuditLogger` SPI 落地时,`@AgentTool.capabilities()` 字段消费,`PermissionPolicy.check()` 读此字段做决策

---

**Spec writer**: Claude Code
**Spec date**: 2026-09-24
**Spec version**: v0.1 Draft
