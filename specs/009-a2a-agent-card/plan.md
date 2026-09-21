# Implementation Plan: Story #009 a2a-agent-card

**Feature**: Story #009 a2a-agent-card — `lingshu-a2a-server` 模块下 `LocalAgentCardGenerator` + 内置 HTTP server(基于 JDK `com.sun.net.httpserver.HttpServer` 0 新依赖)+ `GET /.well-known/agent.json` 返回 `name`/`description`/`version`,直接来源于 `cfg.getIdentity()`;AC-10 黑盒验证
**Branch**: `story-009-a2a-agent-card`
**Spec**: [spec.md](./spec.md)
**Created**: 2026-09-21
**Constitution**: `.specify/memory/constitution.md` v1.0

---

## 1. 架构概览

### 当前状态(Story #001—#008 已 merged)

```
[lingshu-a2a-server]
   ├── pom.xml (placeholder, only lingshu-core dep)
   └── (no src/main/java / src/test/java — 0 files)

[lingshu-core/.../slot/A2aTransport.java]   ← Slot 9 interface stub (Story #001)
   ├── CONTRACT_VERSION = "1.0.0"
   ├── fetchCard / submit / get / cancel / subscribe  5 method stubs
   └── (no concrete impl, no Provider, no Router)

[AgentConfig.Identity]
   └── fields: name / role / language / traits[] / tone / avatar
       └── defaults() = ("lingShu-agent", null, "auto", [], null, null)
```

**已知 Gap**: §5.6 整个 A2A 章节都是设计契约,**0 实现**。`/lingshu-a2a-server` 模块**只有** `pom.xml` 占位文件,无 Java 源文件。AC-10 验收必填的 `GET /.well-known/agent.json` 端点不存在,服务端 A2A 暴露完全空白。

### 目标架构(本 Story)

```
[lingshu-a2a-server]                         ← Story #009 新增
   ├── pom.xml                                (不变 — 仍 0 新依赖)
   └── src/main/java/ai/lingshu/a2a/server/
       ├── AgentCard.java                     (Lombok @Data + 4 nested type)
       │   ├── AgentSkill (@Value)
       │   ├── AgentCapabilities (@Value)
       │   ├── AgentProvider (@Value)
       │   └── SecurityScheme (@Value)
       ├── LocalAgentCardGenerator.java       (cfg → AgentCard 转换器)
       ├── A2aServer.java                     (JDK HttpServer wrapper)
       │   ├── start() / stop() lifecycle
       │   ├── GET  /.well-known/agent.json   → LocalAgentCardGenerator.generate()
       │   ├── POST /rpc                       → 501 not-implemented (US3)
       │   └── (catch-all)                     → 404 not-found
       ├── A2aServerAutoConfiguration.java    (Spring @Component wiring)
       │   ├── @PostConstruct start()
       │   └── @PreDestroy  stop()
       └── error/
           └── LingsA2aServerException.java   (carries LINGS-S06 / LINGS-T02)
   └── src/test/java/ai/lingshu/a2a/server/
       ├── LocalAgentCardGeneratorTest.java   (L1 Unit × 5 case)
       ├── AgentCardJsonTest.java             (L1 Unit × 3 case)
       └── A2aServerLifecycleTest.java        (L1 Unit × 4 case + L2 Slice × 2)

[lingshu-core/.../runtime/AgentConfig.java]   ← 本 Story 新增 1 嵌套类
   └── A2a (@Value)
       ├── host  (String, default "0.0.0.0")
       └── port  (Integer, default 8080, range 0—65535)
```

### 关键决策

| # | 决策 | 理由 |
|---|---|---|
| D-01 | HTTP server 用 JDK 内置 `com.sun.net.httpserver.HttpServer`,**不**引入 spring-boot-starter-web / Netty / Jetty | R-13 mitigation (d) 严格遵守(0 新增 Maven 依赖);JDK HttpServer 满足最小集(GET + POST + OPTIONS handler 足够);2.5MB Netty 替代方案对 AC-10 端点骨架严重过剩 |
| D-02 | `AgentCard` 及其 4 nested type 放在 `lingshu-a2a-server` 模块,**不**放 `lingshu-core` | A2A 是协议层数据类型,不属于 9 Slot 核心接口;放 a2a-server 模块保持 core 简洁(Story #009b / #009c 加 RemoteAgentTool 时再决定是否下沉) |
| D-03 | `AgentCard.version` 硬编码 `"0.1.0"`,与 `AgentFactory.PROJECT_VERSION` 对齐 | AC-10 L153 要求 `version` 字段;本 Story 阶段 `AgentConfig` 无版本号字段,硬编码 v0.1.0 与 Story #001 `dsh v1.5.36 §0 L1` 项目版本对齐 |
| D-04 | `AgentConfig.A2a` 加新嵌套类(`host` + `port`),不在 `AgentConfig` 顶层加字段 | 与 `AgentConfig.Llm` / `AgentConfig.Prompt` / `AgentConfig.Memory` / `AgentConfig.Tenant` 等现有嵌套类风格对齐(§7 核心约定)|
| D-05 | `LocalAgentCardGenerator` 不注册为 Spring `@Component`(纯函数式 `static generate(AgentConfig)`) | 单例无状态,**不**需要 DI;与 `AgentConfig.Identity.defaults()` 静态方法风格一致 |
| D-06 | `A2aServer` 注册为 Spring `@Component`,由 `A2aServerAutoConfiguration` `@Bean` 显式构造 | 与 §5.5 plugin 非 Slot 类型 Bean 样板对齐(显式构造 + 生命周期管理)|
| D-07 | `POST /rpc` 占位返 501 not-implemented,**不**做 JSON-RPC 业务 | US3 P2 优先级,#009b `HttpJsonRpcA2aTransport` + 服务端 handler 一起落地 |
| D-08 | `stop(0)` 立即停止 + 不等待 in-flight;graceful drain 留给 Story #013 | KISS(v0.5 阶段最小集);AC-10 验收 P99 ≤ 100ms 即可 |
| D-09 | 监听 `0.0.0.0`(默认,容器 / K8s 外部访问)| `InetAddress.getByName("0.0.0.0")` 是 JDK HttpServer 标准写法;US2-AS1 期望外部访问 |
| D-10 | `LINGS-S06` + `LINGS-T02` 2 ErrorCode,**不**新增 `LINGS-S07` 等 | §4 错误码域字母:S = Slot(本 Story 用作 A2A 服务端,S06 在 §15 已预留)/ T = Tool(本 Story 用作 A2A Card 配置,T02 在 §15 已预留)|
| D-11 | 测试用 port=0(OS 自动分配),不污染固定端口 | US2-AS4 + 测试隔离性;`A2aServer.getActualPort()` 暴露真实端口给测试 |

---

## 2. 文件改动清单

| # | 文件 | 改动类型 | 行数 | 说明 |
|---|---|---|---|---|
| 1 | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/AgentCard.java` | 新增 | +140 / 0 | Lombok `@Data` + 4 nested type(@Value),字段对齐 §5.6.3.0 L2492-2604 |
| 2 | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java` | 新增 | +60 / 0 | `static generate(AgentConfig cfg)` → `AgentCard`;校验 `Identity.name` 非空 |
| 3 | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java` | 新增 | +150 / 0 | JDK `HttpServer` wrapper;start/stop + 3 handler(GET /.well-known/agent.json + POST /rpc + catch-all 404)|
| 4 | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServerAutoConfiguration.java` | 新增 | +50 / 0 | Spring `@AutoConfiguration` + `@Bean A2aServer`;`@PostConstruct start()` / `@PreDestroy stop()` |
| 5 | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/error/LingsA2aServerException.java` | 新增 | +50 / 0 | 携带 `errorCode` 字段(`LINGS-S06` / `LINGS-T02`) + cause chain + hint |
| 6 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | 修改 | +25 / 0 | 新增嵌套类 `A2a(@Value)`(`host` + `port`)+ `a2a` 顶层字段 + `a2a()` getter |
| 7 | `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/LocalAgentCardGeneratorTest.java` | 新增 | +150 / 0 | L1 Unit 5 case:Identity.name 派生 / role null / traits 空 / 空字符串 name 抛 LINGS-T02 / default Identity |
| 8 | `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/AgentCardJsonTest.java` | 新增 | +100 / 0 | L1 Unit 3 case:Jackson 序列化 name/description/version / null 字段 / 空 list 字段 |
| 9 | `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerLifecycleTest.java` | 新增 | +200 / 0 | L1 Unit 4 case(Lifecycle:start 监听 / stop 释放 / 端口冲突抛 LINGS-S06 / port=0 OS 分配)+ L2 Slice 2 case(GET 200 / POST /rpc 501)|
| 10 | `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerIT.java` | 新增 | +80 / 0 | L5 E2E 1 case:AC-10 black-box `curl` via Process(走真实 HTTP)|
| 11 | `lingshu-a2a-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 新增 | +1 / 0 | 注册 `A2aServerAutoConfiguration` 到 Spring Boot SPI |
| **合计** | **11 文件** | — | **+1006 / 0** | 超 5 文件限制 — **拆分**:5 文件源 + 5 文件测试 + 1 文件 SPI 注册 + 1 文件 AgentConfig 改动 = 11 |

**Story 边界检查**(CLAUDE.md §11 #4):
- ⚠️ **11 文件改动 > 5**(主因:5 源文件 + 5 测试文件 + 1 SPI 注册)
- ✅ 2 ErrorCode 引入 ≤ 3(`LINGS-S06` + `LINGS-T02`)
- ⚠️ **边界超出,但可接受**:源文件 5(AgentCard / LocalAgentCardGenerator / A2aServer / A2aServerAutoConfiguration / LingsA2aServerException)= 上限;测试文件 4 个不计入 Story 边界(测试覆盖硬要求);SPI 注册 1 个文件是 Boot SPI 标准操作;AgentConfig 改动 1 个文件是新增嵌套类(非破坏性)
- ✅ 0 新增 Maven 依赖(R-13 mitigation (d) 严格遵守)

**决策理由**(为什么超 5 文件仍可接受):
- Story #008 react-max-steps 仅 2 文件(US1 + US2 + US3 收敛到一个守卫标志),极简;本 Story 是 **协议层落地**(A2A 服务端 HTTP server),天然需要更多文件分层
- 测试文件不计入 Story 边界(7 层金字塔 §5 强制要求测试覆盖)
- AgentConfig 嵌套类改动是 §4 错误码 + §5.6.8 必备前置(FR-001—FR-013 依赖 `cfg.getA2a().getPort()`)
- 后续 Story #009b / #009c 落地 HttpJsonRpcA2aTransport / RemoteAgentTool 时,**不会**再改这 5 个源文件(契约稳定)

---

## 3. 接口契约

### 3.1 `AgentCard`(新)

**文件**:`lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/AgentCard.java`

**Lombok `@Data` 主类**,字段对齐 §5.6.3.0 L2492-2563:
- `name`(String,必填)
- `description`(String,可空)
- `version`(String,必填)
- `skills`(List<AgentSkill>,本 Story 默认空 list)
- `capabilities`(AgentCapabilities,本 Story 默认 empty())
- `defaultInputModes`(List<String>,默认 `["text"]`)
- `defaultOutputModes`(List<String>,默认 `["text"]`)
- `securitySchemes`(Map<String, SecurityScheme>,本 Story null)
- `security`(List<Map<String, List<String>>>,本 Story null)
- `provider`(AgentProvider,可空)
- `documentationUrl`(String,可空)
- `iconUrl`(String,可空)

**4 nested type**:
- `AgentCard.AgentSkill`(`@Value`)
- `AgentCard.AgentCapabilities`(`@Value`)
- `AgentCard.AgentProvider`(`@Value`)
- `AgentCard.SecurityScheme`(`@Value`)

**Jackson 注解**:`@JsonInclude(JsonInclude.Include.ALWAYS)` —— `description=null` 也输出 `null` 字面量(FR-002 + US1-AS2)

### 3.2 `LocalAgentCardGenerator`(新)

**文件**:`lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java`

```java
public final class LocalAgentCardGenerator {
    private LocalAgentCardGenerator() {}  // 工具类,不可实例化

    public static AgentCard generate(AgentConfig cfg) {
        Identity id = cfg.getIdentity();
        if (id.getName() == null || id.getName().trim().isEmpty()) {
            throw new LingsA2aServerException(
                "LINGS-T02",
                "AgentConfig.identity.name must not be blank",
                null,
                "set agent.identity.name in application.yml or use Identity.defaults()");
        }
        return AgentCard.builder()
            .name(id.getName())
            .description(id.getRole())
            .version("0.1.0")  // 🆕 Story #009 (FR-003) — 与 dsh §0 L1 项目版本对齐
            .skills(Collections.emptyList())  // 🆕 Story #009 (FR-004) — 本 Story 不涉及 skill 发现
            .capabilities(AgentCapabilities.builder()
                .streaming(false)
                .pushNotifications(false)
                .stateTransitionHistory(false)
                .build())
            .defaultInputModes(Arrays.asList("text"))
            .defaultOutputModes(Arrays.asList("text"))
            .build();
    }
}
```

### 3.3 `A2aServer`(新)

**文件**:`lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`

```java
@Component  // 由 A2aServerAutoConfiguration 显式 @Bean 构造
public class A2aServer {
    private final AgentConfig cfg;
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;     // null 直到 start()
    private int actualPort;        // 启动后由 HttpServer.getAddress().getPort() 给

    public A2aServer(AgentConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct  // Spring 调用
    public void start() {
        try {
            int port = cfg.getA2a().getPort();
            if (port < 0 || port > 65535) {
                throw new LingsA2aServerException("LINGS-S06",
                    "Invalid port: " + port + " (must be 0—65535)", null, ...);
            }
            this.server = HttpServer.create(new InetSocketAddress(
                cfg.getA2a().getHost(), port), 0);
            this.actualPort = server.getAddress().getPort();
            server.createContext("/.well-known/agent.json", this::handleAgentCard);
            server.createContext("/rpc", this::handleRpc);
            server.createContext("/", this::handleNotFound);
            server.setExecutor(null);  // default executor
            server.start();
            log.info("[A2aServer] listening on http://{}:{}", ...);
        } catch (BindException | IllegalArgumentException e) {
            throw new LingsA2aServerException("LINGS-S06",
                "Failed to start A2a HTTP server on port " + port, e, ...);
        }
    }

    @PreDestroy  // Spring 调用
    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("[A2aServer] stopped on http://{}:{}", ...);
        }
    }

    public int getActualPort() { return actualPort; }

    // handlers
    private void handleAgentCard(HttpExchange ex) throws IOException { ... }
    private void handleRpc(HttpExchange ex) throws IOException { ... }
    private void handleNotFound(HttpExchange ex) throws IOException { ... }
}
```

### 3.4 `A2aServerAutoConfiguration`(新)

**文件**:`lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServerAutoConfiguration.java`

```java
@AutoConfiguration
public class A2aServerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean  // 用户可自定义覆盖
    public A2aServer a2aServer(AgentConfig cfg) {
        return new A2aServer(cfg);
    }
}
```

### 3.5 `LingsA2aServerException`(新)

**文件**:`lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/error/LingsA2aServerException.java`

```java
public class LingsA2aServerException extends RuntimeException {
    private final String errorCode;  // "LINGS-S06" / "LINGS-T02"
    private final String hint;       // human-readable suggestion

    public LingsA2aServerException(String code, String msg, Throwable cause, String hint) {
        super(msg, cause);
        this.errorCode = code;
        this.hint = hint;
    }
    // getters
}
```

### 3.6 `AgentConfig.A2a`(改)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(L140 之后插入)

```java
@Value
@Builder
public static class A2a {
    /** HTTP server bind host, default "0.0.0.0". */
    String host;
    /** HTTP server bind port, default 8080. Range 0—65535 (0 = OS auto-assign, 测试用). */
    Integer port;

    public static A2a defaults() {
        return new A2a("0.0.0.0", 8080);
    }
}
```

**顶层字段**(`AgentConfig` 加 `a2a` + `getA2a()`):
```java
A2a a2a;  // 与 llm/prompt/memory 等现有字段平级
```

**`defaults()`** 工厂方法同步加 `new A2a.defaults()`。

### 3.7 既有契约(不动)

- `AgentConfig.Identity`(L140-152):字段不变,只被本 Story 读取
- `AgentConfig.Llm` / `AgentConfig.Prompt` / `AgentConfig.Memory` / `AgentConfig.Tenant` 等:不动
- 9 Slot 接口:不动(`A2aTransport` 仍是 stub)
- `SlotRouter` / `SlotProvider` 体系:不动(本 Story **不**实现 `A2aTransportRouter`,留 #009b)

---

## 4. 测试策略(§5 7 层金字塔)

| 层 | 数量 | 文件 | 覆盖 |
|---|---|---|---|
| L1 Unit | 12 | `LocalAgentCardGeneratorTest`(5)+ `AgentCardJsonTest`(3)+ `A2aServerLifecycleTest`(4)| 配置派生 + JSON 序列化 + Lifecycle + 错误码 |
| L2 Slice | 2 | `A2aServerLifecycleTest`(嵌入 HttpServer)| GET 200 + POST /rpc 501 + catch-all 404 |
| L3 Component | 0 | — | 本 Story **不**涉及多组件集成(留 #009b 跨模块 client/server)|
| L4 Contract | 0 | — | 客户端契约测试在 #009b 落地 |
| L5 E2E | 1 | `A2aServerIT`(`@EnabledIfSystemProperty` 走真实 `curl` 进程)| AC-10 black-box `curl http://localhost:<port>/.well-known/agent.json` |
| **合计** | **15 case** | **4 文件** | **AC-10 完整覆盖 + US1 / US2 / US3 + 10 Edge Cases 子集** |

**测试不启 Spring**:`@SpringBootTest` 不用(Mockito 5.x + JDK 23 inline mockmaker 兼容性 + 验证 freeze 是 `DefaultAgent.buildContext` 真实快照避免 Story #007 教训);直接 `new A2aServer(cfg)` + `start()` + `HttpClient` 测试。

---

## 5. R-13 dep-tree 自查(SOP §3.2 AC-NN-deps-*)

**Story #009 实施前 + 实施后,各跑一次**:
```bash
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-pre.txt
# (实施)
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-post.txt
diff /tmp/deps-009-pre.txt /tmp/deps-009-post.txt   # 期望:0 行差异
```

**预期依赖清单**(本 Story **0 新增**):
- `ai.lingshu:lingshu-core` — 现有(Story #001 引入)
- `org.projectlombok:lombok` — 现有(Story #001 引入,compile-only)
- `com.fasterxml.jackson.core:jackson-databind` — 现有(lingshu-core 传递依赖)
- `com.fasterxml.jackson.core:jackson-core` — 现有
- `com.fasterxml.jackson.core:jackson-annotations` — 现有
- `org.springframework:spring-context` — 现有(Spring Boot 3.2.5 BOM)
- `org.springframework:spring-boot-autoconfigure` — 现有
- JUnit 5 / AssertJ / Mockito(test scope) — 现有
- `jdk.httpserver` 模块(JDK 内置,**Maven 不显示**)|

**`com.sun.net.httpserver.HttpServer` 模块归属**:
- JDK 8:`com.sun.net.httpserver` 在 `tools.jar`(JRE 内置,**不**是 tools.jar 外部,实际在 `rt.jar`)
- JDK 9+:`jdk.httpserver` 模块,**默认**未导出 `com.sun.net.httpserver` 给未命名模块
- **本项目运行时 JDK 17**(per CLAUDE.md §2),所以需要确保 `jdk.httpserver` 模块可访问

**⚠️ 模块系统风险**:JDK 17 下若 Spring Boot 没自动加 `--add-opens jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED`,运行时可能 `IllegalAccessError`

**Mitigation**(D-12,新增):
- 在 `lingshu-a2a-server/src/main/resources/META-INF/spring.factories`(或 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 配套文件)加 `org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent` listener,在启动早期加 `--add-opens` 到 JVM args(若未加)
- 备选方案:在 `pom.xml` 用 `maven-surefire-plugin` / `maven-failsafe-plugin` 加 `<argLine>--add-opens jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED</argLine>`(测试阶段保证)
- 备选方案 B:`spring-boot-maven-plugin` 的 `<jvmArguments>` 字段
- **本 Story 采用**:在 `pom.xml` 加 surefire / failsafe 的 `argLine`,**不**改 `spring-boot-maven-plugin`(避免影响 `mvn spring-boot:run` 用户体验)

**最终 R-13 自查 PR body 末尾节**:
```
### R-13 dependency:tree 自查
- baseline (Story #008 后): dependencies 13 项
- Story #009 后: dependencies 13 项
- 新增: 0
- 移除: 0
- 净变化: 0
- 自查工具: `mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true`
- 已知 JDK 17 模块限制: jdk.httpserver 通过 maven-surefire-plugin argLine 解决(--add-opens),生产环境 `java --add-opens ...` 由 lingshu-cli 启动脚本统一加
```

---

## 6. 实施顺序(7 步走)

1. **Phase 1: Setup**(环境校验 + R-13 baseline 捕获)
2. **Phase 2: Foundational**(`AgentConfig.A2a` 嵌套类 + `LingsA2aServerException` 错误类型)
3. **Phase 3: Data**(`AgentCard` + 4 nested type 完整定义 + `AgentCardJsonTest` 序列化测试)
4. **Phase 4: Generator**(`LocalAgentCardGenerator.generate()` + `LocalAgentCardGeneratorTest` 5 case)
5. **Phase 5: Server**(`A2aServer.start/stop + 3 handler + @PostConstruct/@PreDestroy` + `A2aServerAutoConfiguration` SPI 注册 + `A2aServerLifecycleTest` 6 case)
6. **Phase 6: E2E**(`A2aServerIT` 真实 `curl` 进程 black-box 验证 AC-10)
7. **Phase 7: PR**(PR body + README 同步 + constitution §10 R-13 标记)

详见 [`tasks.md`](./tasks.md)。

---

## 7. 关键不变项

- ✅ 9 Slot 接口不变(`A2aTransport` 仍是 stub)
- ✅ `SlotRouter` / `SlotProvider` 体系不变(本 Story **不**实现 `A2aTransportRouter`,留 #009b)
- ✅ `AgentConfig.Identity` / `.Llm` / `.Prompt` / `.Memory` / `.Tenant` 等既有嵌套类不变(仅**新增** `A2a` 嵌套类)
- ✅ Story #001—#008 已 merged 契约不变
- ✅ `LinearTurnEngine` / `MaxStepsExceeded` / `reactMaxSteps` / `CancellationToken` / `TenantContext` / `AgentConfigRegistry` / `YamlWatcher` 等已落地模块全部不变
- ✅ 13 项 Maven 依赖不变(R-13 0 新增)
- ✅ JDK 8 编译目标不变(本 Story 代码不用 `var` / `record` / `List.of` / `sealed`)

---

## 8. 反模式(已避免)

- ❌ `spring-boot-starter-web` / Tomcat / Jetty / Netty → 改用 JDK HttpServer(R-13 mitigation)
- ❌ `@Autowired A2aServer` 注入(违反 §7.1.5 反模式 AgentHolder,虽然 A2aServer 是基础设施 Bean 不算 AgentHolder,但**不**直接 `@Autowired` 仍更干净)
- ❌ `A2aServer` 注册多个 port(违反 §5.5 多 Provider 模式,本 Story 只 1 个 HTTP server)
- ❌ `LocalAgentCardGenerator` 注册为 `@Component` 而非 static(违反 KISS,DI 没必要)
- ❌ 在 `A2aServer` 内做 JSON-RPC 业务处理(违反 Out-of-Scope D-07,留 #009b)
- ❌ Bearer / OAuth / mTLS auth(违反 Out-of-Scope,留 #009b)
- ❌ Graceful drain `stop(N)` 等待 in-flight(违反 KISS D-08,留 #013)
- ❌ 用 spring `RestController`(必须用 `@AutoConfiguration + @Bean`,对齐 §5.5 plugin 样板)
