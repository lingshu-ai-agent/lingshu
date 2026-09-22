# Tasks: Story #009a a2a-grpc-transport

**Input**: Design documents from `/specs/009a-a2a-grpc-transport/`
- spec.md(3 User Stories US1—US3 + 10 Edge Cases + FR-001—FR-015 + NFR-001—NFR-009)
- plan.md(9 源文件改动 + 5 测试文件 + 21 case + 7 步实施顺序 + R-13 mitigation (d) 5 步)
- research.md(grpc-java 选型 + protobuf idl 设计 + R-13 binary size 估算)
- data-model.md(8 新增类型 + 1 ErrorCode + 6 protobuf message)
- contracts/a2a-grpc-transport.md(5 RPC + 1 Router 契约 + 1 Cache 契约)
- quickstart.md(7 验证场景 — AC-10 关联 + US1—US3 + EC-1—EC-10)
- checklists/requirements.md(质量门禁清单)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#009 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `AgentConfig.A2a host+port` / `LocalAgentCardGenerator` / JDK `HttpServer` 等基础设施)

**Tests**: Required per FR-001—FR-015 + 9 NFR + 10 Edge Cases。**21 case** = 14 L1 Unit + 5 L2 Slice + 2 L5 E2E。

**Constitution**: v1.0 — §1 #8 Slot 选用 / §1 #9 Plugin 发现 / §1 #11 默认实现位置 / §2 13 依赖锁定(R-13 +2 新依赖)/ §4 错误码约定(1 新增 ErrorCode LINGS-S07)/ §5 7 层金字塔(21 case 覆盖)/ §10 R-13 已部分缓解(grpc +5MB 二进制,镜像必执行)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + capture Story #009 dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #009 dependency baseline: `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009a-pre.txt`(期望 0 grpc / protobuf / netty / guava 包)
- [ ] T003 Verify current branch is `story-009a-a2a-grpc-transport` via `git branch --show-current`(从 main 拉新分支 `git checkout -b story-009a-a2a-grpc-transport`)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test` exits 0(Story #001—#009 tests all green — pre-implementation sanity)

**Checkpoint**: Setup ready — code modifications can begin。

---

## Phase 2: Foundational — `AgentConfig.A2a` 扩展 + `lingshu-a2a-client/pom.xml` 加 grpc 依赖(US1 + US2 + US3 基础 + 所有 AS 阻塞依赖)

**Purpose**: Extend `AgentConfig.A2a` + add 2 new deps **before** any A2aTransport wiring

**⚠️ CRITICAL**: 后续所有 US(US1 / US2 / US3 + EC-1—EC-10)都依赖 `AgentConfig.A2a.grpcTarget` / `cardTtl` + grpc / protobuf 类路径,此 phase 必须先完成

- [ ] T005 [P0] Modify `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`:
  - 在 `A2a` 嵌套类(L285-291 已有 host + port 字段)新增 2 字段:
    ```java
    /** 🆕 Story #009a — gRPC server target host:port (dsh §5.6.3.2 L3221).
     * Default "localhost:50051". Used by {@code ManagedChannelBuilder.forTarget()}. */
    String grpcTarget;
    /** 🆕 Story #009a — AgentCard cache TTL (dsh §5.6.3.2 L3224).
     * Default 5 minutes. Negative cache TTL = cardTtl / 4 (75s). */
    Duration cardTtl;
    ```
  - 更新 `A2a.defaults()`(L290):
    ```java
    public static A2a defaults() {
        return new A2a("0.0.0.0", 8080, "localhost:50051", Duration.ofMinutes(5));
    }
    ```
  - 同步新增 import: `java.time.Duration`
  - **不**新增启动期校验(留 T0XX)

- [ ] T006 [P0] Modify `lingshu-a2a-client/pom.xml`(R-13 mitigation (d) 强依赖):
  - 文件头 import:`<properties>` 加:
    ```xml
    <grpc.version>1.55.1</grpc.version>
    <protobuf.version>3.22.3</protobuf.version>
    ```
  - `<dependencies>` 加 2 新 deps:
    ```xml
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-stub</artifactId>
        <version>${grpc.version}</version>
    </dependency>
    <dependency>
        <groupId>com.google.protobuf</groupId>
        <artifactId>protobuf-java</artifactId>
        <version>${protobuf.version}</version>
    </dependency>
    ```
  - `<build>` 加 protobuf-maven-plugin(grpc-java 代码生成):
    ```xml
    <build>
      <extensions>
        <extension>
          <groupId>kr.motd.maven</groupId>
          <artifactId>os-maven-plugin</artifactId>
          <version>1.7.1</version>
        </extension>
      </extensions>
      <plugins>
        <plugin>
          <groupId>org.xolstice.maven.plugins</groupId>
          <artifactId>protobuf-maven-plugin</artifactId>
          <version>0.6.1</version>
          <configuration>
            <protocArtifact>com.google.protobuf:protoc:${protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
            <pluginId>grpc-java</pluginId>
            <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
          </configuration>
          <executions>
            <execution>
              <goals>
                <goal>compile</goal>
                <goal>compile-custom</goal>
              </goals>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </build>
    ```

**Checkpoint**:`AgentConfig.A2a` 扩展 + `pom.xml` grpc 依赖编译过 + `mvn -pl lingshu-a2a-client compile` 不破坏现有功能

---

## Phase 3: Data — `a2a.proto` + protobuf-maven-plugin 代码生成(US1 基础 + FR-015 protobuf idl)

**Purpose**: Define protobuf IDL **before** GrpcA2aTransport uses generated stub

- [ ] T007 [P0] Create `lingshu-a2a-client/src/main/proto/a2a.proto`:
  - syntax = "proto3";
  - package `ai.lingshu.a2a.v1`;
  - option java_multiple_files = true;
  - option java_package = "ai.lingshu.a2a.v1";
  - option java_outer_classname = "A2aProto";
  - **service** `A2aService`:
    ```protobuf
    service A2aService {
      rpc GetCard (AgentName) returns (Card);
      rpc Submit (SubmitRequest) returns (Task);
      rpc GetTask (TaskId) returns (Task);
      rpc Cancel (TaskId) returns (CancelAck);
      rpc Subscribe (TaskId) returns (stream TaskEvent);
    }
    ```
  - **messages**:
    ```protobuf
    message AgentName { string name = 1; }
    message SubmitRequest {
      string agent_name = 1;
      string skill = 2;
      string input_json = 3;     // matches ToolCall args JSON
    }
    message TaskId { string id = 1; }
    message Card {
      string name = 1;
      string description = 2;
      string version = 3;
      repeated string skills = 4;
    }
    message Task {
      string id = 1;
      string status = 2;          // PENDING / RUNNING / COMPLETED / FAILED / CANCELED
      string result_json = 3;    // matches ToolResult output
      string error = 4;
    }
    message TaskEvent {
      string task_id = 1;
      string event_type = 2;     // PROGRESS / LOG / PARTIAL_RESULT
      string payload_json = 3;
    }
    message CancelAck { bool accepted = 1; }
    ```

- [ ] T008 [P0] Verify protobuf compile + Java stub generation:
  - `mvn -pl lingshu-a2a-client compile`
  - 期望:`target/generated-sources/protobuf/java/ai/lingshu/a2a/v1/` 6 message class 生成
  - 期望:`target/generated-sources/protobuf/grpc-java/ai/lingshu/a2a/v1/A2aServiceGrpc.java` 生成
  - 若失败:检查 `pom.xml` protobuf-maven-plugin 配置(`protocArtifact` / `pluginArtifact` os classifier)

**Checkpoint**:`a2a.proto` + 7 generated Java class 编译过 + `mvn -pl lingshu-a2a-client compile` 0 error

---

## Phase 4: Cache — `AgentCardCache`(US2 主路径 + 5 L1 Unit test cases)

**Purpose**: Implement TTL cache **before** GrpcA2aTransport uses it

- [ ] T009 [P0] [US2] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/AgentCardCache.java`:
  - 文件头 import:`lombok.Value` / `lombok.Getter` / `java.time.Duration` / `java.time.Instant` / `java.util.concurrent.ConcurrentHashMap` / `java.util.concurrent.atomic.AtomicLong`
  - 类签名:`public final class AgentCardCache`(final + 私有构造器)
  - 字段:
    ```java
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Duration cacheTtl;          // 默认 5 min
    private final Duration negativeCacheTtl;  // cacheTtl / 4 = 75s
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong negatives = new AtomicLong(0);
    ```
  - 构造器:
    ```java
    public AgentCardCache(Duration cacheTtl) {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must be > 0 (current: " + cacheTtl + ")");
        }
        this.cacheTtl = cacheTtl;
        this.negativeCacheTtl = cacheTtl.dividedBy(4);  // 75s for 5min
    }
    ```
  - **核心方法** `public Map<String, Object> get(String agentName)`:
    ```java
    public Map<String, Object> get(String agentName) {
        CacheEntry entry = cache.get(agentName);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (Instant.now().isAfter(entry.expireAt)) {
            cache.remove(agentName, entry);  // lazy eviction
            misses.incrementAndGet();
            return null;
        }
        if (entry.value == null) {  // negative cache hit
            negatives.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.value;
    }
    ```
  - **方法** `public void put(String agentName, Map<String, Object> card)`:
    ```java
    public void put(String agentName, Map<String, Object> card) {
        if (agentName == null || agentName.isEmpty() || card == null) {
            throw new IllegalArgumentException("agentName and card must not be null/empty");
        }
        cache.put(agentName, new CacheEntry(card, Instant.now().plus(cacheTtl)));
    }
    ```
  - **方法** `public void putNegative(String agentName)`:
    ```java
    public void putNegative(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be null/empty");
        }
        cache.put(agentName, new CacheEntry(null, Instant.now().plus(negativeCacheTtl)));
    }
    ```
  - **方法** `public void invalidate(String agentName) { cache.remove(agentName); }`
  - **方法** `public Stats stats() { return new Stats(hits.get(), misses.get(), negatives.get()); }`
  - **nested** `private static final class CacheEntry { final Map<String, Object> value; final Instant expireAt; CacheEntry(Map<String, Object> v, Instant e) { this.value = v; this.expireAt = e; } }`
  - **nested** `@Getter public static final class Stats { private final long hits; private final long misses; private final long negatives; public Stats(long h, long m, long n) { this.hits = h; this.misses = m; this.negatives = n; } public double hitRatio() { long total = hits + misses + negatives; return total == 0 ? 0.0 : (double) hits / total; } }`
  - 类级 Javadoc:**"🆕 Story #009a (FR-006—FR-008):AgentCard TTL 缓存 —— `GrpcA2aTransport.fetchCard()` 先查本地,命中免 RPC;失败 put 负缓存(短 TTL = cacheTtl/4)。JDK ConcurrentHashMap 实现,**不**引 Caffeine / Guava Cache(R-13 零依赖)。thread-safe,`stats()` 提供 hits/misses/negatives/hitRatio 4 指标供 #010 OTel 集成。"**

- [ ] T010 [P0] [US2] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/AgentCardCacheTest.java`(L1 Unit, 6 case):
  - 文件头 import:`AgentCardCache` / `AgentCardCache.Stats` / `java.time.Duration` / `org.junit.jupiter.api.Test` / `org.junit.jupiter.api.DisplayName` / `static org.assertj.core.api.Assertions.assertThat` / `static org.assertj.core.api.Assertions.assertThatThrownBy` / `java.util.HashMap` / `java.util.Map` / `java.util.concurrent.TimeUnit`
  - **6 个测试方法**:
    - `TC-CACHE-1 put_thenGet_returnsCard`:
      - 输入:`cache.put("alice", cardOf("alice", "AI coding"))` + `cache.get("alice")`
      - 期望:`get` 返回 card;`stats().getHits() == 1`
    - `TC-CACHE-2 get_unknownKey_returnsNull()`:
      - 输入:`cache.get("unknown")`
      - 期望:返 null;`stats().getMisses() == 1`
    - `TC-CACHE-3 putNegative_thenGet_returnsNull()`:
      - 输入:`cache.putNegative("bob")` + `cache.get("bob")`
      - 期望:返 null;`stats().getNegatives() == 1`(负缓存计数)
    - `TC-CACHE-4 entryExpires_returnsNull`:
      - 输入:`cache = new AgentCardCache(Duration.ofMillis(100)); cache.put("alice", card); Thread.sleep(150); cache.get("alice")`
      - 期望:返 null(过期清除);`stats().getMisses() == 1`
    - `TC-CACHE-5 negativeCacheExpires_fallsThrough`:
      - 输入:`cache = new AgentCardCache(Duration.ofSeconds(1)); cache.putNegative("bob"); Thread.sleep(300); cache.get("bob")`(负缓存 TTL = 250ms)
      - 期望:返 null(过期);`stats().getMisses() == 1`
    - `TC-CACHE-6 stats_hitRatio_calculatesCorrectly`:
      - 输入:`cache.put("alice", card)` + `cache.get("alice")`(hit) + `cache.get("bob")`(miss) + `cache.putNegative("charlie")` + `cache.get("charlie")`(negative)
      - 期望:`stats().getHitRatio() == 1.0 / 3.0 ≈ 0.333`

**Checkpoint**:`AgentCardCache` 编译过 + 6 L1 Unit 全过 —— `mvn -pl lingshu-a2a-client test -Dtest=AgentCardCacheTest`

---

## Phase 5: Router — `A2aTransportRouter` concrete stub(US3 主路径 + 4 L1 Unit test cases)

**Purpose**: Implement Slot 9 Router **before** GrpcA2aTransportProvider auto-wires

- [ ] T011 [P0] [US3] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`:
  - 文件头 import:`ai.lingshu.core.slot.A2aTransport` / `ai.lingshu.core.spi.Providers` / `ai.lingshu.core.spi.SlotRouter` / `org.slf4j.LoggerFactory` / `org.springframework.stereotype.Component` / `java.util.List`
  - 类签名:`@Component public class A2aTransportRouter extends SlotRouter<Providers.A2aTransportProvider, A2aTransport>`
  - 构造器:
    ```java
    public A2aTransportRouter(List<Providers.A2aTransportProvider> providers) {
        super(providers, "A2aTransport", LoggerFactory.getLogger(A2aTransportRouter.class));
    }
    ```
  - `@Override protected Class<A2aTransport> getSlotInterface() { return A2aTransport.class; }`
  - 类级 Javadoc:**"🆕 Story #009a (FR-004—FR-005):Slot 9 A2aTransportRouter concrete stub —— 按 `cfg.getA2aTransport()` 字符串选 Provider;`@Component` + `@Autowired List<Providers.A2aTransportProvider>`;复用父类 `SlotRouter<P, T>` byName map + priority 决胜 + 启动日志样板;`resolve(name, cfg)` 调 `provider.create(cfg)` 返回 `A2aTransport` 实例。🆕 #009a 是 `Routers.java` 之外第 1 个独立 Router 文件先例(`Routers.java` 6 Router 内聚 + 本 Router 外聚,因 grpc 强依赖应在 lingshu-a2a-client,而非 core 内聚)。"**

- [ ] T012 [P0] [US3] Create `lingshu-core/src/test/java/ai/lingshu/core/impl/router/A2aTransportRouterTest.java`(L1 Unit + L2 Slice, 4 case):
  - 文件头 import:`A2aTransportRouter` / `Providers.A2aTransportProvider` / `A2aTransport` / `ToolResult` / `Mockito.mock` / `@Test` / `@DisplayName` / `static org.assertj.core.api.Assertions.assertThat` / `static org.assertj.core.api.Assertions.assertThatThrownBy` / `java.util.Collections`
  - **mock helper**:
    ```java
    private static A2aTransportProvider mockProvider(String name, int priority, String version, A2aTransport instance) {
        A2aTransportProvider p = mock(A2aTransportProvider.class);
        when(p.name()).thenReturn(name);
        when(p.priority()).thenReturn(priority);
        when(p.version()).thenReturn(version);
        when(p.create(any(AgentConfig.class))).thenReturn(instance);
        return p;
    }
    ```
  - **4 个测试方法**:
    - `TC-RTR-1 singleProvider_resolvesCorrectly`:
      - 输入:`p1 = mockProvider("grpc-1.0.0", 10, "1.0.0", mockA2aTransport)` + `new A2aTransportRouter(List.of(p1))`
      - 期望:`router.resolve("grpc-1.0.0", AgentConfig.defaults())` 返回 mockA2aTransport 实例
    - `TC-RTR-2 multipleProviders_resolvesByName`:
      - 输入:`p1 = mockProvider("grpc-1.0.0", 10, ...)` + `p2 = mockProvider("http-jsonrpc-1.0.0", 10, ...)` + `router.resolve("http-jsonrpc-1.0.0", cfg)`
      - 期望:返回 p2.create(cfg) 实例
    - `TC-RTR-3 sameNamePriority_largerPriorityWins`:
      - 输入:`p1 = mockProvider("grpc-1.0.0", 10, "1.0.0", instance1)` + `p2 = mockProvider("grpc-1.0.0", 20, "1.0.0", instance2)` + `router.resolve("grpc-1.0.0", cfg)`
      - 期望:返回 instance2(priority=20 胜出)
    - `TC-RTR-4 unknownName_throwsIllegalArgumentException`:
      - 输入:`router.resolve("unknown", cfg)`
      - 期望:抛 `IllegalArgumentException` 含 `Unknown A2aTransportRouter 'unknown'. Available: [grpc-1.0.0]`

**Checkpoint**:`A2aTransportRouter` 编译过 + 4 L1 Unit 全过 —— `mvn -pl lingshu-core test -Dtest=A2aTransportRouterTest`

---

## Phase 6: Transport — `GrpcA2aTransport` + `GrpcA2aTransportProvider` + AutoConfiguration(US1 主路径 + 11 test cases)

**Purpose**: Implement grpc client + Provider + AutoConfiguration + 5 测试文件

- [ ] T013 [P0] [US1] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransport.java`:
  - 文件头 import:`ai.lingshu.core.slot.A2aTransport` / `ai.lingshu.core.message.ToolResult` / `ai.lingshu.a2a.v1.A2aServiceGrpc` / `ai.lingshu.a2a.v1.AgentName` / `ai.lingshu.a2a.v1.Card` / `ai.lingshu.a2a.v1.SubmitRequest` / `ai.lingshu.a2a.v1.TaskId` / `ai.lingshu.a2a.v1.Task` / `ai.lingshu.a2a.v1.TaskEvent` / `ai.lingshu.a2a.v1.CancelAck` / `io.grpc.ManagedChannel` / `io.grpc.stub.StreamObserver` / `jakarta.annotation.PreDestroy` / `org.slf4j.Logger` / `org.slf4j.LoggerFactory` / `java.util.HashMap` / `java.util.Map` / `java.util.concurrent.TimeUnit` / `java.util.function.Consumer`
  - 类签名:`public class GrpcA2aTransport implements A2aTransport`(非 final,Spring 可能代理)
  - 字段:
    ```java
    private static final Logger log = LoggerFactory.getLogger(GrpcA2aTransport.class);
    private final ManagedChannel channel;
    private final A2aServiceGrpc.A2aServiceBlockingStub blockingStub;
    private final A2aServiceGrpc.A2aServiceStub asyncStub;  // for subscribe
    private final AgentCardCache cardCache;
    ```
  - 构造器:
    ```java
    public GrpcA2aTransport(ManagedChannel channel, AgentCardCache cardCache) {
        this.channel = channel;
        this.blockingStub = A2aServiceGrpc.newBlockingStub(channel);
        this.asyncStub = A2aServiceGrpc.newStub(channel);
        this.cardCache = cardCache;
    }
    ```
  - **`@Override public Map<String, Object> fetchCard(String agentName)`**:
    ```java
    Map<String, Object> cached = cardCache.get(agentName);
    if (cached != null) return cached;
    try {
        Card card = blockingStub.getCard(AgentName.newBuilder().setName(agentName).build());
        Map<String, Object> result = new HashMap<>();
        result.put("name", card.getName());
        result.put("description", card.getDescription());
        result.put("version", card.getVersion());
        result.put("skills", card.getSkillsList());
        cardCache.put(agentName, result);
        return result;
    } catch (Exception e) {
        cardCache.putNegative(agentName);  // 负缓存避免反复打挂的 remote
        log.error("[GrpcA2aTransport] fetchCard({}) failed", agentName, e);
        throw new RuntimeException("GrpcA2aTransport.fetchCard failed: " + e.getMessage(), e);
    }
    ```
  - **`@Override public ToolResult submit(String agentName, String skill, String inputJson)`**:
    ```java
    try {
        SubmitRequest req = SubmitRequest.newBuilder()
            .setAgentName(agentName).setSkill(skill).setInputJson(inputJson).build();
        Task task = blockingStub.submit(req);
        return task.getError().isEmpty()
            ? ToolResult.success(task.getResultJson())
            : ToolResult.error(task.getError());
    } catch (Exception e) {
        log.error("[GrpcA2aTransport] submit({}) failed", agentName, e);
        return ToolResult.error("GrpcA2aTransport.submit failed: " + e.getMessage());
    }
    ```
  - **`@Override public ToolResult get(String taskId)`** + **`@Override public boolean cancel(String taskId)`**:
    - 同 submit 模式,分别 stub.getTask(taskId) / stub.cancel(TaskId.newBuilder())
  - **`@Override public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent)`**:
    ```java
    asyncStub.subscribe(TaskId.newBuilder().setId(taskId).build(), new StreamObserver<TaskEvent>() {
        @Override public void onNext(TaskEvent event) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskId", event.getTaskId());
            payload.put("eventType", event.getEventType());
            payload.put("payload", event.getPayloadJson());
            onEvent.accept(payload);
        }
        @Override public void onError(Throwable t) {
            log.error("[GrpcA2aTransport] subscribe({}) error", taskId, t);
        }
        @Override public void onCompleted() {}
    });
    ```
  - **`@PreDestroy public void close()`**:
    ```java
    @PreDestroy
    public void close() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                log.info("[GrpcA2aTransport] channel shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
    ```
  - 类级 Javadoc:**"🆕 Story #009a (FR-001 + FR-011—FR-013):gRPC A2aTransport 客户端 —— 调 5 RPC 方法(fetchCard/submit/get/cancel/subscribe),`subscribe` 用 grpc streaming(`A2aServiceStub.subscribe` + `StreamObserver`)替代 HttpJsonRpc polling。`fetchCard` 先查 `AgentCardCache` 命中免 RPC;RPC 失败 put 负缓存。`@PreDestroy close()` 调 `channel.shutdown().awaitTermination(5, SECONDS)`,防 channel 资源泄漏。"**

- [ ] T014 [P0] [US1] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportProvider.java`:
  - 文件头 import:`ai.lingshu.core.runtime.AgentConfig` / `ai.lingshu.core.slot.A2aTransport` / `ai.lingshu.core.spi.Providers.A2aTransportProvider` / `io.grpc.ManagedChannelBuilder` / `org.slf4j.Logger` / `org.slf4j.LoggerFactory` / `java.time.Duration` / `java.util.concurrent.TimeUnit`
  - 类签名:`@Component public class GrpcA2aTransportProvider implements A2aTransportProvider`
  - 字段:`private static final Logger log = LoggerFactory.getLogger(GrpcA2aTransportProvider.class);`
  - **`@Override public String name() { return "grpc-1.0.0"; }`**(与 dsh §5.6.3.2 L3217 "grpc" 一致,加 version suffix "1.0.0" 与 `version()` 字段对齐)
  - **`@Override public int priority() { return 10; }`**
  - **`@Override public String version() { return "1.0.0"; }`**
  - **`@Override public A2aTransport create(AgentConfig cfg)`**:
    ```java
    String grpcTarget = cfg.getA2a().getGrpcTarget();
    if (grpcTarget == null || grpcTarget.trim().isEmpty()) {
        throw new LingsA2aServerException(
            "LINGS-S07", "AgentConfig.a2a.grpcTarget must not be null/empty", null,
            "set 'agent.a2a.grpcTarget' in application.yml (e.g. 'localhost:50051')");
    }
    Duration cardTtl = cfg.getA2a().getCardTtl();
    if (cardTtl == null) cardTtl = Duration.ofMinutes(5);  // fallback to default
    ManagedChannel channel = ManagedChannelBuilder.forTarget(grpcTarget)
        .usePlaintext().build();
    AgentCardCache cache = new AgentCardCache(cardTtl);
    log.info("[GrpcA2aTransportProvider] created for grpcTarget={}, cardTtl={}", grpcTarget, cardTtl);
    return new GrpcA2aTransport(channel, cache);
    ```
  - 类级 Javadoc:**"🆕 Story #009a (FR-002):GrpcA2aTransport typed Provider —— `name()='grpc-1.0.0'` + `priority()=10` + `version()='1.0.0'`;`create(cfg)` 校验 `grpcTarget` 非空 + 建 `ManagedChannel`(`usePlaintext()`,TLS 部署层处理) + 实例化 `AgentCardCache(cardTtl)` + 返回 `GrpcA2aTransport`。失败抛 `LingsA2aServerException (LINGS-S07)`。"**
  - **注意**:`LingsA2aServerException` 在 `lingshu-a2a-server` 包;跨模块复用,**不**新增 `LingsA2aClientException`(避免异常类膨胀),沿用 Story #009 已落地的 `LingsA2aServerException`(errorCode 命名空间全局)

- [ ] T015 [P0] [US1] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportAutoConfiguration.java`:
  - 文件头 import:`ai.lingshu.core.spi.Providers.A2aTransportProvider` / `org.springframework.boot.autoconfigure.AutoConfiguration` / `org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean` / `org.springframework.context.annotation.Bean`
  - 类签名:`@AutoConfiguration public class GrpcA2aTransportAutoConfiguration`
  - `@Bean(name = "a2aTransportProvider_grpc-1.0.0") @ConditionalOnMissingBean public A2aTransportProvider grpcA2aTransportProvider() { return new GrpcA2aTransportProvider(); }`
  - 类级 Javadoc:**"🆕 Story #009a (FR-003):GrpcA2aTransport Spring Boot Auto-Configuration —— `@Bean(name='a2aTransportProvider_grpc-1.0.0')` 唯一 Bean 名 + `@ConditionalOnMissingBean` 允许用户自定义覆盖。§5.4 唯一 Bean 名约定。"**

- [ ] T016 [P0] Create `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
  - 内容:`ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration`(单行,UTF-8 无 BOM)

- [ ] T017 [P0] [US1] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportProviderTest.java`(L1 Unit, 4 case):
  - **4 个测试方法**:
    - `TC-PROV-1 create_validGrpcTarget_returnsGrpcA2aTransport`:
      - 输入:`AgentConfig.defaults()`(grpcTarget="localhost:50051", cardTtl=5min)
      - 期望:`provider.create(cfg) instanceof GrpcA2aTransport`
    - `TC-PROV-2 create_nullGrpcTarget_throwsLingsS07`:
      - 输入:`cfg.getA2a()` with `grpcTarget=null`
      - 期望:`assertThatThrownBy(() -> provider.create(cfg)).isInstanceOf(LingsA2aServerException.class).extracting("errorCode").isEqualTo("LINGS-S07")`
    - `TC-PROV-3 create_emptyGrpcTarget_throwsLingsS07`:
      - 输入:`cfg.getA2a()` with `grpcTarget=""`
      - 期望:同 TC-PROV-2
    - `TC-PROV-4 provider_nameAndPriority`:
      - 期望:`provider.name() == "grpc-1.0.0"` + `provider.priority() == 10` + `provider.version() == "1.0.0"`

- [ ] T018 [P0] [US1] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportTest.java`(L2 Slice, 5 case, mock grpc):
  - **helper**:
    ```java
    private ManagedChannel mockChannel() { return mock(ManagedChannel.class); }
    private AgentCardCache realCache() { return new AgentCardCache(Duration.ofMinutes(5)); }
    ```
  - **5 个测试方法**:
    - `TC-TRN-1 fetchCard_cacheHit_skipsRpc`:
      - mock:`cache.put("alice", cardOf("alice"))` + `channel = mockChannel()`
      - 期望:`transport.fetchCard("alice")` 返回 cached card;`channel` 0 次 grpc call(verify mock zero interactions)
    - `TC-TRN-2 fetchCard_cacheMiss_callsBlockingStub`:
      - mock:`blockingStub.getCard(any)` → `Card.newBuilder().setName("alice")....build()`
      - 期望:`transport.fetchCard("alice")` 返回 card;`blockingStub.getCard(any)` 调 1 次
    - `TC-TRN-3 fetchCard_grpcError_putsNegativeCache`:
      - mock:`blockingStub.getCard(any)` 抛 `StatusRuntimeException(UNAVAILABLE)`
      - 期望:`transport.fetchCard("alice")` 抛 RuntimeException;`cache.get("alice")` 返回 null(负缓存命中)
    - `TC-TRN-4 submit_returnsToolResult`:
      - mock:`blockingStub.submit(any)` → `Task.newBuilder().setResultJson("{\"ok\":true}")....build()`
      - 期望:`transport.submit("alice", "skill", "{}")` 返回 `ToolResult.success("{\"ok\":true}")`
    - `TC-TRN-5 subscribe_asyncStubCallbackFiresOnEvent`:
      - mock:`asyncStub.subscribe(any, any)` capture StreamObserver + manually invoke onNext(TaskEvent)
      - 期望:`Consumer<Map<String, Object>>` 收到 event map 含 `taskId` + `eventType` + `payload`

- [ ] T019 [P0] [US1] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aEndToEndIT.java`(L5 E2E, 2 case, 真实 grpc):
  - **helper**:
    ```java
    @BeforeEach setup() {
        server = InProcessServerBuilder.forName("a2a-it").addService(mockA2aService).build().start();
        channel = InProcessChannelBuilder.forName("a2a-it").build();
        cache = new AgentCardCache(Duration.ofMinutes(5));
        transport = new GrpcA2aTransport(channel, cache);
    }
    @AfterEach teardown() {
        transport.close();
        server.shutdownNow();
    }
    ```
  - **mock A2aServiceImpl**:`GetCard(AgentName)` → `Card.newBuilder().setName(req.getName())....build()`;`Submit(SubmitRequest)` → `Task.newBuilder().setId("task-1").setStatus("COMPLETED").setResultJson("{\"ok\":true}")....build()`
  - **2 个测试方法**:
    - `TC-E2E-1 fetchCard_realGrpc_returnsCard`:
      - 操作:`transport.fetchCard("alice")`
      - 期望:返回 `Map<String, Object>` 含 `name="alice"`;`channel.isActive()` → true
    - `TC-E2E-2 submit_realGrpc_returnsToolResult`:
      - 操作:`transport.submit("alice", "skill", "{}")`
      - 期望:返回 `ToolResult.success("{\"ok\":true}")`;`channel.isActive()` → true

**Checkpoint**:Phase 6 全部编译过 + 4 + 5 + 2 = 11 case 全过 —— `mvn -pl lingshu-a2a-client verify`

---

## Phase 7: Validate + PR(全量回归 + R-13 dep-tree diff + PR body)

**Purpose**: 全量测试通过 + R-13 自查 + PR 准备

- [ ] T020 全量编译 + 测试:`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test`(期望 234 + 21 = 255 case 全过)
- [ ] T021 R-13 dep-tree 自查:`mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009a-post.txt && diff /tmp/deps-009a-pre.txt /tmp/deps-009a-post.txt`(期望 +grpc-stub +protobuf-java + 传递 io.grpc:grpc-* + io.netty:netty-* + com.google.guava:guava)
- [ ] T022 binary size check:`mvn -pl lingshu-a2a-client package && du -h target/lingshu-a2a-client-*.jar`(期望 < 35MB baseline + 25MB = 60MB;实际 grpc +5MB ≈ 5—10MB)
- [ ] T023 [P0] 更新 `README.md` + `constitution.md`:
  - `README.md` 加「A2A Client (gRPC) 示例」一节:`agent.a2aTransport: grpc-1.0.0` 切换 + 启动日志样例
  - `constitution.md` §10 R-13 行加「**Story #009a 部分缓解(+2 Maven 依赖 grpc-stub + protobuf-java +5MB 二进制,R-13 mitigation (d) 镜像已执行)**」
- [ ] T024 [P0] 提交:`git add . && git commit -m "feat(a2a): Story #009a a2a-grpc-transport — GrpcA2aTransport 3 件套 + A2aTransportRouter + AgentCardCache

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"`
- [ ] T025 [P0] PR:`gh pr create --base main --head story-009a-a2a-grpc-transport --title "feat(agent): Story #009a a2a-grpc-transport — GrpcA2aTransport 3 件套 + A2aTransportRouter + AgentCardCache" --body "$(cat <<'EOF'
## Summary
- Story #009a a2a-grpc-transport 实现,落地 dsh §5.6.3.2 L3174-3320 「3 件套模式」的 grpc 半边
- 5 Java 文件新增(`GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration` / `A2aTransportRouter` / `AgentCardCache`)+ 1 `AgentConfig.A2a` 嵌套类扩字段 + 1 `pom.xml` 加 grpc 依赖 + 1 protobuf idl `a2a.proto`
- 5 测试文件 + 21 测试 case(L1 14 + L2 5 + L5 2)
- 1 新增 ErrorCode(`LINGS-S07 A2A_GRPC_INIT_FAILED`)
- +2 Maven 依赖(`io.grpc:grpc-stub` + `com.google.protobuf:protobuf-java`),R-13 mitigation (d) 镜像已执行

## Files
(spec.md + plan.md + tasks.md + research.md + data-model.md + contracts/*.md + quickstart.md + checklists/requirements.md 完整路径)

## Test plan
- 21 case:\`mvn -pl lingshu-a2a-client test\`
- 回归 234 + 21 = 255 case:\`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test\`
- L5 E2E:\`mvn -pl lingshu-a2a-client verify -Dtest=GrpcA2aEndToEndIT\`

### R-13 dependency:tree 自查
- baseline (Story #009 后): 0 grpc / protobuf / netty 包
- Story #009a 后: +io.grpc:grpc-stub:1.55.1 +io.grpc:grpc-core:1.55.1 +io.grpc:grpc-api:1.55.1 +io.grpc:grpc-protobuf:1.55.1 +io.grpc:grpc-stub:1.55.1 +com.google.protobuf:protobuf-java:3.22.3 +io.netty:netty-handler:4.1.x +io.netty:netty-transport:4.1.x +io.netty:netty-codec-http:4.1.x +com.google.guava:guava:31.1-jre
- 新增: 11 dep(s)
- 移除: 0
- 净变化: +11 dep(s) / +5MB 二进制
- 自查工具: \`mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true\`
- 已知 JDK 模块限制: 无需 --add-opens(grpc 反射不触发 JDK 17 module barrier)

EOF
)"`
- [ ] T026 PR review → merge → 同步 main 分支
- [ ] T027 [P0] 同步 dsh 文档:dsh §13 加 v1.5.37 行记录 Story #009a 完成 + §6.4 §5 SPI 槽位总表 Slot 9 行增加「GrpcA2aTransportProvider」状态行(实施者在 PR review 通过后做)

---

## Summary

| Phase | T-NN | 文件改动 | 测试 | 阻塞 |
|---|---|---|---|---|
| Phase 1 Setup | T001—T004 | — | — | — |
| Phase 2 Foundational | T005—T006 | AgentConfig.A2a + pom.xml grpc 依赖 | — | Phase 3—6 |
| Phase 3 Data | T007—T008 | a2a.proto + 代码生成 | — | Phase 4—6 |
| Phase 4 Cache | T009—T010 | AgentCardCache | 6 L1 | Phase 5—6 |
| Phase 5 Router | T011—T012 | A2aTransportRouter | 4 L1 | Phase 6 |
| Phase 6 Transport | T013—T019 | GrpcA2aTransport + Provider + AutoConfiguration + SPI | 4+5+2 = 11 L1+L2+L5 | Phase 7 |
| Phase 7 Validate + PR | T020—T027 | README + constitution + commit + PR | 回归 234 + 21 = 255 case | — |
| **合计** | **27 T-NN** | **6 源文件 + 2 配置 + 1 proto + 5 测试 = 14 改动** | **21 + 234 回归 = 255 case** | — |