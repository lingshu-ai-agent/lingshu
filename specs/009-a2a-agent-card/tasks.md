# Tasks: Story #009 a2a-agent-card

**Input**: Design documents from `/specs/009-a2a-agent-card/`
- spec.md(3 User Stories US1—US3 + 10 Edge Cases + FR-001—FR-013 + NFR-001—NFR-008)
- plan.md(7 步实施顺序 + 11 文件改动 + 15 测试用例)
- research.md(5 项设计决策已锁定 + 1 项新增 D-12 JDK 17 --add-opens 缓解)
- data-model.md(6 新增类型 + 2 ErrorCode)
- contracts/agent-card-http-api.md(HTTP API 契约 + 状态码表 + JSON Schema)
- contracts/a2a-server-lifecycle.md(Spring Bean 生命周期契约 + 配置契约)
- quickstart.md(10 验证场景 — AC-10 + US1—US3 + EC-1—EC-5)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#008 全部 merged(provides AgentFactory / AgentConfig.identity / SlotResolver / LinearTurnEngine / CancellationToken / TenantContext / AgentConfigRegistry / YamlWatcher / 9 Slot stub)

**Tests**: Required per FR-001—FR-013 + 8 NFR + 10 Edge Cases。**15 case** = 12 L1 Unit + 2 L2 Slice + 1 L5 E2E。

**Constitution**: v1.0 — §1 #5 Skill 与 Tool 边界 / §1 #9 Plugin 发现 / §1 #11 默认实现位置 / §1 #12 启动时配置校验 / §2 13 依赖锁定(R-13 零新增)/ §4 错误码约定(2 新增 ErrorCode)/ §5 7 层金字塔(15 case 覆盖)/ §10 R-13 已缓解

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + capture Story #008 dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #008 dependency baseline: `mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-pre.txt`
- [ ] T003 Verify current branch is `story-009-a2a-agent-card` via `git branch --show-current`
- [ ] T004 Validate baseline: `mvn -pl lingshu-a2a-server test` exits 0(Story #001—#008 tests all green — pre-implementation sanity;若 lingshu-a2a-server 当前无 test,可跳过此步)
- [ ] T005 [P0] Add `maven-surefire-plugin` `argLine` to `lingshu-a2a-server/pom.xml` for JDK 17 `--add-opens jdk.httpserver`(D-12):
  ```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <argLine>--add-opens jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED</argLine>
        </configuration>
      </plugin>
    </plugins>
  </build>
  ```

**Checkpoint**: Setup ready — code modifications can begin。

---

## Phase 2: Foundational — `AgentConfig.A2a` 嵌套类 + `LingsA2aServerException`(US1 + US2 基础 + 所有 AS 阻塞依赖)

**Purpose**: Establish the `A2a` nested class on `AgentConfig` (provides `cfg.getA2a().getPort()`) **before** any A2aServer wiring

**⚠️ CRITICAL**: 后续所有 US(US1 / US2 / US3 + EC-1—EC-10)都依赖 `AgentConfig.A2a` + `LingsA2aServerException`,此 phase 必须先完成

- [ ] T006 [P0] Modify `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`:
  - 在 `Identity` 嵌套类(L140-152)之后 / `Instructions` 嵌套类(L154-164)之前,新增:
    ```java
    /**
     * 🆕 Story #009 — A2A server-side configuration (dsh §5.6.8).
     * Used by {@code A2aServer} to bind JDK {@code com.sun.net.httpserver.HttpServer}.
     *
     * <p>Default: host="0.0.0.0", port=8080. Port range 0—65535 (port=0 means OS auto-assign, 测试用).
     */
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
  - 在 `AgentConfig` 顶层字段块(L53-56 之后,紧跟 `Tenant tenant` / `Identity identity` / ... 平级)新增 `A2a a2a;` 字段
  - 在顶层 `@Value` Lombok 生成的 getter 之外,确保 `a2a` 字段被 `@Value` 包含(Lombok 自动生成 `getA2a()`)
  - 在 `AgentConfig.defaults()` 工厂方法(L233-235 附近)同步加 `new A2a.defaults()` 参数
  - 在 `src/main/resources/` 加 yml 配置示例(若已有 application.yml):
    ```yaml
    agent:
      a2a:
        host: 0.0.0.0
        port: 8080
    ```

- [ ] T007 [P0] Create `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/error/LingsA2aServerException.java`:
  - 文件头 import:`RuntimeException` / `Getter`
  - 类签名:`public class LingsA2aServerException extends RuntimeException`
  - 字段:`@Getter private final String errorCode;`(LINGS-S06 / LINGS-T02)+ `@Getter private final String hint;`
  - 构造器:`public LingsA2aServerException(String errorCode, String message, Throwable cause, String hint)`
  - super(message, cause)
  - 赋值 `this.errorCode = errorCode; this.hint = hint;`
  - 类级 Javadoc:**"🆕 Story #009 (LINGS-S06 / LINGS-T02):A2A 服务端启动期 / AgentCard 配置校验异常。errorCode 字段由抛出方设置(参照 §15 Error Catalog)。"**

**Checkpoint**:`AgentConfig.A2a` + `LingsA2aServerException` 编译过 + `mvn -pl lingshu-core compile` 不破坏现有功能 —— `mvn -pl lingshu-core,lingshu-a2a-server -am compile`

---

## Phase 3: Data — `AgentCard` + 4 nested type(US1 基础 + FR-006 Jackson 序列化)

**Purpose**: Establish the `AgentCard` data type + 4 nested types **before** `LocalAgentCardGenerator` uses them

- [ ] T008 [P0] [US1] Create `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/AgentCard.java`:
  - 文件头 import:`lombok.Data` / `lombok.NoArgsConstructor` / `lombok.AllArgsConstructor` / `lombok.Builder` / `com.fasterxml.jackson.annotation.JsonInclude` / `com.fasterxml.jackson.annotation.JsonProperty` / `com.fasterxml.jackson.databind.JsonNode` / `java.util.List` / `java.util.Map`
  - 主类 `@Data @NoArgsConstructor @AllArgsConstructor @Builder @JsonInclude(JsonInclude.Include.ALWAYS)`
  - 12 字段:`name` / `description` / `version` / `skills` / `capabilities` / `defaultInputModes` / `defaultOutputModes` / `securitySchemes` / `security` / `provider` / `documentationUrl` / `iconUrl`(详见 plan.md §3.1)
  - 4 nested type(每个都 `@Value @NoArgsConstructor(force = true) @AllArgsConstructor @Builder`):
    - `AgentSkill`(`id` / `name` / `description` / `inputSchema` / `outputSchema` / `inputModes` / `outputModes`,字段 `@JsonProperty` 注解保持 camelCase)
    - `AgentCapabilities`(`streaming` / `pushNotifications` / `stateTransitionHistory`)+ static `empty()` 方法返 `new AgentCapabilities(false, false, false)`
    - `AgentProvider`(`organization` / `url`)
    - `SecurityScheme`(`type` / `scheme` / `bearerFormat` / `@JsonProperty("openIdConnectUrl") String openIdConnectUrl` / `flows`)
  - 主类 `isValid()` 方法(仅校验 name 非空,本 Story 不校验 skills 非空):
    ```java
    public boolean isValid() {
        return name != null && !name.isEmpty();
    }
    ```
  - 类级 Javadoc:**"🆕 Story #009 (FR-001—FR-006):A2A 协议 AgentCard 数据类型 —— 服务端在 `/.well-known/agent.json` 返回的 JSON;客户端 fetchCard 解析的目标。字段对齐 dsh §5.6.3.0 L2492-2563。skills / capabilities / provider / securitySchemes 字段本 Story 默认空 / null,留 #009b / #009c 增量扩展。"**

- [ ] T009 [P0] [US1] Create `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/AgentCardJsonTest.java`(L1 Unit, 3 case):
  - 文件头 import:`AgentCard` / `AgentCapabilities` / `ObjectMapper` / `@BeforeEach` / `@Test` / `@DisplayName` / `assertThat` / `Collections` / `Arrays`
  - `@BeforeEach setup()`:`new ObjectMapper();`
  - **3 个测试方法**:
    - `TC-API-4 serialize_minimalCard_returnsAllRequiredFields`:
      - 输入:`AgentCard.builder().name("alice").description("AI 编码助手").version("0.1.0").skills(Collections.emptyList()).capabilities(AgentCapabilities.empty()).defaultInputModes(Arrays.asList("text")).defaultOutputModes(Arrays.asList("text")).build();`
      - 期望:`mapper.writeValueAsString(card)` 含 `"name":"alice"` + `"description":"AI 编码助手"` + `"version":"0.1.0"` + `"skills":[]` + `"capabilities":{"streaming":false,...}` + `"defaultInputModes":["text"]`
    - `TC-API-5 serialize_nullDescription_returnsNullLiteral`:
      - 输入:`AgentCard.builder().name("alice").description(null).version("0.1.0").skills(...).capabilities(...).defaultInputModes(...).defaultOutputModes(...).build();`
      - 期望:JSON 含 `"description":null`(不是字段省略,显式 null)
    - `serialize_emptySkillsList_returnsEmptyArrayNotNull`:
      - 输入:`skills(Collections.emptyList())`
      - 期望:JSON 含 `"skills":[]`(不是 `"skills":null`)

**Checkpoint**:`AgentCard` 主类 + 4 nested type 编译过 + 3 L1 Unit 全过 —— `mvn -pl lingshu-a2a-server test -Dtest=AgentCardJsonTest`

---

## Phase 4: Generator — `LocalAgentCardGenerator`(US1 主路径 + US1-AS3 校验)

**Purpose**: Implement the `cfg → AgentCard` transformation logic + 5 L1 Unit test cases

- [ ] T010 [P0] [US1] Create `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java`:
  - 文件头 import:`ai.lingshu.core.runtime.AgentConfig` / `ai.lingshu.core.runtime.AgentConfig.Identity` / `LingsA2aServerException` / `slf4j.Logger` / `slf4j.LoggerFactory` / `java.util.Arrays` / `java.util.Collections`
  - 类签名:`public final class LocalAgentCardGenerator`(final + 私有构造器,工具类)
  - `private static final Logger log = LoggerFactory.getLogger(LocalAgentCardGenerator.class);`
  - `private LocalAgentCardGenerator() {}`(私有构造器)
  - **核心方法** `public static AgentCard generate(AgentConfig cfg)`:
    ```java
    public static AgentCard generate(AgentConfig cfg) {
        Identity id = cfg.getIdentity();
        if (id.getName() == null || id.getName().trim().isEmpty()) {
            throw new LingsA2aServerException(
                "LINGS-T02",
                "AgentConfig.identity.name must not be blank (current: " + id.getName() + ")",
                new IllegalArgumentException("Identity.name is blank"),
                "set 'agent.identity.name' in application.yml, or use Identity.defaults() which provides 'lingShu-agent'");
        }
        return AgentCard.builder()
            .name(id.getName())
            .description(id.getRole())
            .version("0.1.0")  // 🆕 Story #009 (FR-003) — 与 dsh §0 L1 项目版本对齐
            .skills(Collections.emptyList())  // 🆕 Story #009 (FR-004) — 本 Story 不涉及 skill 发现,留 #009c
            .capabilities(AgentCapabilities.empty())  // 🆕 Story #009 (FR-005)
            .defaultInputModes(Arrays.asList("text"))
            .defaultOutputModes(Arrays.asList("text"))
            .build();
    }
    ```
  - 类级 Javadoc:**"🆕 Story #009 (FR-001—FR-005):本地 AgentCard 生成器 —— 将 {@code AgentConfig.identity} 映射为 A2A v1.0 AgentCard。完全静态方法,**不**注册为 Spring `@Component`(单例无状态 + 无 DI 必要)。校验 Identity.name 非空,缺则抛 {@link LingsA2aServerException} (LINGS-T02)。"**

- [ ] T011 [P0] [US1] Create `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/LocalAgentCardGeneratorTest.java`(L1 Unit, 5 case):
  - 文件头 import:`LocalAgentCardGenerator` / `AgentCard` / `AgentConfig` / `AgentConfig.Identity` / `LingsA2aServerException` / `@BeforeEach` / `@Test` / `@DisplayName` / `assertThat` / `assertThatThrownBy` / `Arrays` / `Collections`
  - **辅助方法** `private static AgentConfig cfgWithIdentity(String name, String role)`:`return AgentConfig.builder()....identity(new Identity(name, role, "auto", Collections.emptyList(), null, null))....build();`(完整填 27 字段 with defaults;实际可省,builder 会自动填)
  - **5 个测试方法**:
    - `TC-API-1 generate_withIdentityName_returnsAgentCardWithName`(US1-AS1 主路径):
      - 输入:`cfgWithIdentity("alice-coding", "AI 编码助手")`
      - 期望:`generate(cfg).getName() == "alice-coding"` + `.getDescription() == "AI 编码助手"` + `.getVersion() == "0.1.0"` + `.getSkills().isEmpty()` + `.getCapabilities().isStreaming() == false`
    - `TC-API-2 generate_defaultIdentity_returnsLingShuAgent`(US1-AS2 零配置):
      - 输入:`AgentConfig.defaults()`(Identity.defaults() = "lingShu-agent" / role=null)
      - 期望:`getName() == "lingShu-agent"` + `.getDescription() == null` + `.getVersion() == "0.1.0"`
    - `TC-API-3 generate_blankIdentityName_throwsLingsT02`(US1-AS3 + EC-1):
      - 输入:`cfgWithIdentity("", null)`(name 空字符串)
      - 期望:`assertThatThrownBy(() -> generate(cfg)).isInstanceOf(LingsA2aServerException.class).extracting("errorCode").isEqualTo("LINGS-T02")`
    - `generate_nullIdentityName_throwsLingsT02`(EC-1):
      - 输入:`cfgWithIdentity(null, null)`(name null)
      - 期望:同 TC-API-3
    - `generate_whitespaceOnlyIdentityName_throwsLingsT02`(EC-1):
      - 输入:`cfgWithIdentity("   ", null)`(name 纯空白)
      - 期望:同 TC-API-3(trim().isEmpty() 校验)

**Checkpoint**:`LocalAgentCardGenerator` 编译过 + 5 L1 Unit 全过 —— `mvn -pl lingshu-a2a-server test -Dtest=LocalAgentCardGeneratorTest`

---

## Phase 5: Server — `A2aServer` + `A2aServerAutoConfiguration` + SPI 注册(US2 主路径 + US3 占位 + EC-4/EC-5)

**Purpose**: Implement the JDK HttpServer wrapper + Spring Boot Auto-Configuration + 6 lifecycle test cases

- [ ] T012 [P0] [US2] Create `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`:
  - 文件头 import:`ai.lingshu.core.runtime.AgentConfig` / `com.sun.net.httpserver.HttpServer` / `com.sun.net.httpserver.HttpExchange` / `com.sun.net.httpserver.HttpHandler` / `com.fasterxml.jackson.databind.ObjectMapper` / `jakarta.annotation.PostConstruct` / `jakarta.annotation.PreDestroy` / `org.slf4j.Logger` / `org.slf4j.LoggerFactory` / `java.io.IOException` / `java.io.OutputStream` / `java.net.InetSocketAddress` / `java.nio.charset.StandardCharsets`
  - 类签名:`public class A2aServer`(非 final,Spring `@Component` 框架可能代理)
  - 字段:
    ```java
    private static final Logger log = LoggerFactory.getLogger(A2aServer.class);
    private final AgentConfig cfg;
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;     // null until start()
    private int actualPort;        // 启动后由 HttpServer.getAddress().getPort() 给
    ```
  - 构造器:`public A2aServer(AgentConfig cfg) { this.cfg = cfg; }`
  - **`@PostConstruct public void start()`** 方法:
    - `int port = cfg.getA2a().getPort();`
    - 校验 `if (port < 0 || port > 65535) throw new LingsA2aServerException("LINGS-S06", "Invalid port: " + port + " (must be 0—65535)", null, "set 'agent.a2a.port' to a value in 0—65535 range");`
    - `String host = cfg.getA2a().getHost();`
    - try `this.server = HttpServer.create(new InetSocketAddress(host, port), 0);`
    - catch `BindException | UnknownHostException | IllegalArgumentException e` → `throw new LingsA2aServerException("LINGS-S06", "Failed to start A2a HTTP server on " + host + ":" + port + ": " + e.getMessage(), e, "change 'agent.a2a.host' / 'agent.a2a.port' in application.yml, or stop the conflicting process");`
    - `this.actualPort = server.getAddress().getPort();`
    - `server.createContext("/.well-known/agent.json", this::handleAgentCard);`
    - `server.createContext("/rpc", this::handleRpc);`
    - `server.createContext("/", this::handleNotFound);`(catch-all)
    - `server.setExecutor(null);`(default)
    - `server.start();`
    - `log.info("[A2aServer] listening on http://{}:{}", host, actualPort);`
  - **`@PreDestroy public void stop()`** 方法:
    - `if (server == null) return;`(幂等)
    - `int port = actualPort;`
    - `server.stop(0);`
    - `this.server = null;`
    - `log.info("[A2aServer] stopped on http://{}:{}", cfg.getA2a().getHost(), port);`
  - **测试 helper API** `public int getActualPort() { return actualPort; }`(US2-AS4)
  - **Handler 1: `private void handleAgentCard(HttpExchange ex)`**:
    ```java
    try {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, json.writeValueAsString(Map.of("error", "method not allowed", "method", ex.getRequestMethod())));
            ex.getResponseHeaders().add("Allow", "GET");
            return;
        }
        AgentCard card = LocalAgentCardGenerator.generate(cfg);  // 启动期已校验,运行期不会 throw
        byte[] body = json.writeValueAsBytes(card);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "public, max-age=60");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    } catch (Exception e) {
        log.error("[A2aServer] GET /.well-known/agent.json failed", e);
        sendJson(ex, 500, json.writeValueAsString(Map.of("error", "internal server error", "errorCode", "LINGS-S06")));
    } finally {
        ex.close();
    }
    ```
  - **Handler 2: `private void handleRpc(HttpExchange ex)`**(US3-AS1):
    ```java
    try {
        // 仅 POST 返 501;其他方法 405
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, json.writeValueAsString(Map.of("error", "method not allowed", "method", ex.getRequestMethod())));
            ex.getResponseHeaders().add("Allow", "POST");
            return;
        }
        byte[] body = json.writeValueAsBytes(Map.of("error", "not implemented", "method", "message/send"));
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(501, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    } catch (IOException e) {
        log.error("[A2aServer] POST /rpc failed", e);
    } finally {
        ex.close();
    }
    ```
  - **Handler 3: `private void handleNotFound(HttpExchange ex)`**(US3-AS2):
    ```java
    try {
        // GET 返 404 + JSON;其他方法 405
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, json.writeValueAsString(Map.of("error", "method not allowed", "method", ex.getRequestMethod())));
            return;
        }
        byte[] body = json.writeValueAsBytes(Map.of("error", "not found", "path", ex.getRequestURI().getPath()));
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(404, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    } catch (IOException e) {
        log.error("[A2aServer] catch-all handler failed", e);
    } finally {
        ex.close();
    }
    ```
  - **`private void sendJson(HttpExchange ex, int status, String body)`** helper:写状态码 + body + close
  - 类级 Javadoc:**"🆕 Story #009 (FR-007—FR-013):A2A 服务端 —— 包装 JDK {@link HttpServer} 暴露 {@code GET /.well-known/agent.json} 端点。`@PostConstruct start()` / `@PreDestroy stop()` 由 Spring 容器管理生命周期。R-13 严格遵守:**0** 新增 Maven 依赖。`stop(0)` 立即停止,graceful drain 留 #013。"**

- [ ] T013 [P0] [US2] Create `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServerAutoConfiguration.java`:
  - 文件头 import:`ai.lingshu.core.runtime.AgentConfig` / `org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean` / `org.springframework.context.annotation.Bean`
  - 类签名:`@AutoConfiguration public class A2aServerAutoConfiguration`
  - `@Bean @ConditionalOnMissingBean public A2aServer a2aServer(AgentConfig cfg) { return new A2aServer(cfg); }`
  - 类级 Javadoc:**"🆕 Story #009 (FR-010):A2aServer Spring Boot Auto-Configuration —— 由 Spring Boot SPI `META-INF/spring/...AutoConfiguration.imports` 注册。`@ConditionalOnMissingBean` 允许用户自定义覆盖。与 §5.5 plugin 非 Slot 类型 Bean 样板对齐。"**

- [ ] T014 [P0] Create `lingshu-a2a-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
  - 内容:`ai.lingshu.a2a.server.A2aServerAutoConfiguration`(单行,无换行)

- [ ] T015 [P0] [US2] Create `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerLifecycleTest.java`(L1 + L2 Slice, 6 case):
  - 文件头 import:`A2aServer` / `LocalAgentCardGenerator` / `AgentConfig` / `java.net.URI` / `java.net.http.HttpClient` / `java.net.http.HttpRequest` / `java.net.http.HttpResponse` / `java.net.ServerSocket` / `org.junit.jupiter.api.AfterEach` / `org.junit.jupiter.api.BeforeEach` / `org.junit.jupiter.api.Test` / `org.junit.jupiter.api.DisplayName` / `static org.assertj.core.api.Assertions.assertThat` / `static org.assertj.core.api.Assertions.assertThatThrownBy`
  - `@BeforeEach`:`A2aServer server = new A2aServer(AgentConfig.defaults()); server.start();`(默认 port=8080,测试需在隔离环境)
  - `@AfterEach`:`server.stop();`
  - **6 个测试方法**:
    - `TC-LC-1 start_withCustomPort9090_listensOn9090`(US2-AS1):
      - 输入:`new A2aServer(AgentConfig.builder()....a2a(new AgentConfig.A2a("127.0.0.1", 9090)).build()).start();`
      - 期望:`getActualPort() == 9090`
      - 注:本测试需要隔离端口,可加 `@EnabledIfSystemProperty(named = "port.test", matches = "true")` 跳到 IT,或用 `try { server.start(); } catch (LINGS-S06) { ... }`
    - `TC-LC-2 start_withDefaultPort8080_listensOn8080`(US2-AS2):
      - 输入:`new A2aServer(AgentConfig.defaults()).start();`
      - 期望:`getActualPort() == 8080`(若 8080 占用则 skip 或用 port=0)
    - `TC-LC-3 stop_releasesPortForRebind`(US2-AS3):
      - 操作:`start → stop → 新 A2aServer 同样 cfg → start` 不抛 BindException
    - `TC-LC-4 start_withPortZero_returnsOsAssignedPort`(US2-AS4):
      - 输入:`new A2aServer(cfgA2a("127.0.0.1", 0)).start();`
      - 期望:`getActualPort() > 0`(OS 分配)
    - `TC-LC-5 start_withPortAlreadyInUse_throwsLingsS06`(EC-4):
      - 操作:`server1 = new A2aServer(cfgA2a("127.0.0.1", 0)); server1.start(); int port = server1.getActualPort(); server2 = new A2aServer(cfgA2a("127.0.0.1", port)); assertThatThrownBy(server2::start).isInstanceOf(LingsA2aServerException.class).extracting("errorCode").isEqualTo("LINGS-S06");`
    - `TC-LC-6 getAgentJson_returns200WithValidCard`(L2 Slice, US1-AS1 主路径):
      - 操作:`server = new A2aServer(cfgA2a("127.0.0.1", 0)); server.start(); int port = server.getActualPort(); HttpClient client = HttpClient.newHttpClient(); HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/.well-known/agent.json")).GET().build(), HttpResponse.BodyHandlers.ofString()); assertThat(resp.statusCode()).isEqualTo(200); assertThat(resp.body()).contains("\"name\":").contains("\"version\":\"0.1.0\"");`
    - `postRpc_returns501NotImplemented`(L2 Slice, US3-AS1):
      - 操作:同 TC-LC-6 但路径 = `/rpc` + method = POST
      - 期望:`statusCode == 501` + `body.contains("\"error\":\"not implemented\"")`
  - 测试 helper `private static AgentConfig cfgA2a(String host, int port)`:`AgentConfig.defaults().toBuilder().a2a(new AgentConfig.A2a(host, port)).build();`(注:AgentConfig 是 `@Value` 不可变,**不**支持 toBuilder;改用 builder 模式构造或写一个完整 builder factory)
  - **注**:`AgentConfig.@Value` 不可变,**不**带 `@Builder`;需用静态工厂或 Lombok `@Builder` on `@Value` 加 `@With`。本 Story 在 T006 给 `A2a` 加 `@Builder`,`AgentConfig` 本身需评估是否同步加 `@Builder`。**简化方案**:写一个测试 fixture helper,用 `new AgentConfig(...)` 全字段构造(27 字段)+ 默认值传 `A2a(host, port)`。

**Checkpoint**:`A2aServer` + `A2aServerAutoConfiguration` + SPI 注册编译过 + 6 L1+L2 测试全过 —— `mvn -pl lingshu-a2a-server test -Dtest=A2aServerLifecycleTest`

---

## Phase 6: E2E — `A2aServerIT` AC-10 black-box via `curl` 进程(L5 E2E)

**Purpose**: AC-10 black-box verification via真实 `curl` Process — no mocks, no in-process HTTP client

- [ ] T016 [P0] Create `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerIT.java`(L5 E2E, 1 case):
  - 文件头 import:`A2aServer` / `AgentConfig` / `@TempDir` / `Path` / `Files` / `@Test` / `@DisplayName` / `ProcessBuilder` / `static org.assertj.core.api.Assertions.assertThat`
  - **IT setup**:
    1. `@TempDir Path tempDir;`
    2. 写临时 `application.yml`:
       ```java
       Path yml = tempDir.resolve("application.yml");
       Files.write(yml, Arrays.asList(
           "agent:",
           "  identity:",
           "    name: e2e-test-agent",
           "    role: E2E 测试 Agent",
           "  a2a:",
           "    host: 127.0.0.1",
           "    port: 18080"
       ));
       ```
    3. `Process server = new ProcessBuilder("java", "-jar", "lingshu-a2a-server/target/lingshu-a2a-server-0.1.0-SNAPSHOT.jar", "--spring.config.location=" + yml.toString()).inheritIO().start();`(或 `mvn exec:java` 调 Spring Boot main)
    4. 等待 server 启动(poll 端口 18080,30s 超时)
  - **AC-10 black-box**:
    ```bash
    Process curl = new ProcessBuilder("curl", "-s", "http://127.0.0.1:18080/.well-known/agent.json").start();
    String body = new String(curl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = curl.waitFor();
    assertThat(exit).isEqualTo(0);
    assertThat(body).contains("\"name\":\"e2e-test-agent\"").contains("\"description\":\"E2E 测试 Agent\"").contains("\"version\":\"0.1.0\"");
    ```
  - **teardown**:
    ```java
    server.destroy();
    server.waitFor(5, TimeUnit.SECONDS);
    if (server.isAlive()) server.destroyForcibly();
    ```
  - 类级 Javadoc:**"🆕 Story #009 (AC-10 L5 E2E):真实 `curl` 进程 black-box 验证 —— 启动真实 A2aServer + 用 `curl` 进程发 HTTP 请求 + 验证响应 JSON 含 name/description/version。**不**用 in-process `HttpClient` 避免 mock 假阳性。"**
  - **注**:IT 需在 `mvn verify` 阶段运行(failsafe plugin);surefire 默认 skip `*IT.java`。本 Story 可同时配 failsafe 或临时用 surefire include。

**Checkpoint**:`mvn -pl lingshu-a2a-server verify -Dtest=A2aServerIT` 通过(AC-10 black-box)

---

## Phase 7: Validate + PR(全量回归 + R-13 自查 + PR body)

**Purpose**: 全量测试通过 + R-13 dep-tree diff = 0 + PR 准备

- [ ] T017 全量编译 + 测试:`mvn -pl lingshu-a2a-server test`(期望 15 case 全过)
- [ ] T018 全模块回归(防 Story #001—#008 退化):`mvn -pl lingshu-core,lingshu-a2a-server -am test`(期望 187 + 15 = 202 case 全过)
- [ ] T019 R-13 dep-tree 自查:`mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-post.txt && diff /tmp/deps-009-pre.txt /tmp/deps-009-post.txt`(期望 0 行差异)
- [ ] T020 [P0] 更新 `README.md` + `constitution.md`:
  - `README.md` 加「A2A 服务端示例」一节:`curl http://localhost:8080/.well-known/agent.json` 示例输出 + 启动命令
  - `constitution.md` §10 R-13 行加「**Story #009 已缓解(0 新增 Maven 依赖,JDK HttpServer 替代 spring-boot-starter-web)**」
- [ ] T021 [P0] 提交:`git add . && git commit -m "feat(a2a): Story #009 a2a-agent-card — LocalAgentCardGenerator + JDK HttpServer (AC-10)

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"`
- [ ] T022 [P0] PR:`gh pr create --base main --head story-009-a2a-agent-card --title "feat(agent): Story #009 a2a-agent-card — LocalAgentCardGenerator + JDK HttpServer (AC-10)" --body "$(cat <<'EOF'
## Summary
- Story #009 a2a-agent-card 实现,落地 AC-10 端到端验收
- `lingshu-a2a-server` 模块新增 5 主源文件 + 4 测试文件 + 1 SPI 注册 + 1 AgentConfig 嵌套类改动
- JDK 内置 `com.sun.net.httpserver.HttpServer`,**0 新增 Maven 依赖**(R-13 mitigation (d) 严格遵守)
- 15 case 测试覆盖 AC-10 + US1—US3 + 10 Edge Cases
- 2 新增 ErrorCode(`LINGS-S06 A2A_SERVER_START_FAILED` + `LINGS-T02 A2A_CARD_INVALID_CONFIG`)

## Files
(spec.md + plan.md + tasks.md + research.md + data-model.md + contracts/*.md + quickstart.md + checklists/requirements.md 完整路径)

## Test plan
- AC-10 black-box:\`mvn -pl lingshu-a2a-server verify -Dtest=A2aServerIT\`
- 15 case:\`mvn -pl lingshu-a2a-server test\`
- 回归 202 case:\`mvn -pl lingshu-core,lingshu-a2a-server -am test\`

### R-13 dependency:tree 自查
- baseline (Story #008 后): N dep(s)
- Story #009 后: N dep(s)
- 新增: 0
- 移除: 0
- 净变化: 0
- 自查工具: \`mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true\`
- 已知 JDK 17 模块限制: jdk.httpserver 通过 maven-surefire-plugin argLine 解决(--add-opens)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"`
- [ ] T023 PR review → merge → 同步 main 分支
- [ ] T024 [P0] 同步 dsh 文档(本 Story **不**改 dsh,留 #009b 落地后一起):在 dsh §13 changelog 加 v1.5.37 行记录 Story #009 完成(实施者在 PR review 通过后做)

---

## Summary

| Phase | T-NN | 文件改动 | 测试 | 阻塞 |
|---|---|---|---|---|
| Phase 1 Setup | T001—T005 | pom.xml surefire argLine | — | — |
| Phase 2 Foundational | T006—T007 | AgentConfig.A2a + LingsA2aServerException | — | Phase 3—5 |
| Phase 3 Data | T008—T009 | AgentCard + 4 nested type | 3 L1 | Phase 4 |
| Phase 4 Generator | T010—T011 | LocalAgentCardGenerator | 5 L1 | Phase 5 |
| Phase 5 Server | T012—T015 | A2aServer + AutoConfiguration + SPI 注册 | 6 L1+L2 | Phase 6 |
| Phase 6 E2E | T016 | A2aServerIT | 1 L5 | Phase 7 |
| Phase 7 Validate + PR | T017—T024 | README + constitution + commit + PR | 回归 202 case | — |
| **合计** | **24 T-NN** | **11 文件** | **15 + 187 回归 = 202 case** | — |
