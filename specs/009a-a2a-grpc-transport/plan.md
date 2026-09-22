# Implementation Plan: Story #009a a2a-grpc-transport

**Story**: Story #009a a2a-grpc-transport(dsh §5.6.3.2 L3174-3320)
**Branch**: `story-009a-a2a-grpc-transport`(基于 main)
**Spec**: [`spec.md`](./spec.md)
**Prerequisites**:
- Story #001—#009 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `AgentConfig.A2a host+port` / `LocalAgentCardGenerator` 等基础设施)
- Java 1.8 compile target + JDK 17+ runtime(Spring Boot 3.2.5 要求,CLAUDE.md §2)
- Maven 3.6.3+ + `mvn -v` 通过

---

## 1. 接口 / 类型 变更清单

| ID | 类型 | 变更 | 路径 |
|---|---|---|---|
| **I-01** | `AgentConfig.A2a`(Lombok `@Value` 嵌套类)| **扩字段** `grpcTarget: String = "localhost:50051"` + `cardTtl: Duration = Duration.ofMinutes(5)`;`defaults()` 同步;启动期校验 grpcTarget 非空 + cardTtl > 0 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(修改)|
| **I-02** | `A2aTransportRouter`(`@Component extends SlotRouter<Providers.A2aTransportProvider, A2aTransport>`)| **新增** Slot 9 Router concrete stub,`@Autowired List<Providers.A2aTransportProvider>` + `super(providers, "A2aTransport", LoggerFactory.getLogger(A2aTransportRouter.class))` | `lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`(新增)|
| **I-03** | `GrpcA2aTransport`(`@Component implements A2aTransport`)| **新增** 5 方法 grpc 实现,字段 `ManagedChannel channel` + `A2aServiceGrpc.A2aServiceBlockingStub stub` + `AgentCardCache cardCache`;`@PreDestroy close()` 调 `channel.shutdown().awaitTermination(5, SECONDS)` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransport.java`(新增)|
| **I-04** | `GrpcA2aTransportProvider`(`@Component implements Providers.A2aTransportProvider`)| **新增** typed Provider,`name()="grpc-1.0.0"` + `priority()=10` + `version()="1.0.0"` + `create(cfg)` 调 `ManagedChannelBuilder.forTarget(cfg.getA2a().getGrpcTarget()).usePlaintext().build()` + `new AgentCardCache(cfg.getA2a().getCardTtl())` + `new GrpcA2aTransport(channel, cardCache)` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportProvider.java`(新增)|
| **I-05** | `GrpcA2aTransportAutoConfiguration`(`@AutoConfiguration`)| **新增** `@Bean(name = "a2aTransportProvider_grpc-1.0.0")` + `new GrpcA2aTransportProvider()` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportAutoConfiguration.java`(新增)|
| **I-06** | `AgentCardCache`(`@Component`)| **新增** ConcurrentHashMap-based TTL cache,5 方法:`get` / `put` / `putNegative` / `invalidate` / `stats()`;nested `CacheEntry{ value: Map, expireAt: long }` + `Stats{ hits, misses, negatives, hitRatio() }` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/AgentCardCache.java`(新增)|
| **I-07** | SPI 注册文件 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | **新增** 内容:`ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration`(单行,无换行)| `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(新增)|
| **I-08** | protobuf idl `a2a.proto` | **新增** 5 RPC + 6 message 定义(GetCard / Submit / GetTask / Cancel / Subscribe stream / Card / Task / TaskEvent / SubmitRequest / TaskId / CancelAck)| `lingshu-a2a-client/src/main/proto/a2a.proto`(新增)|
| **I-09** | `pom.xml` Maven 依赖 | **新增** 2 项: `io.grpc:grpc-stub` + `com.google.protobuf:protobuf-java` + `kr.motd.maven:os-maven-plugin` (protobuf 自动检测 OS)+ `org.xolstice.maven.plugins:protobuf-maven-plugin`(grpc-java 代码生成)| `lingshu-a2a-client/pom.xml`(修改)|

---

## 2. 文件改动清单(共 9 源文件 + 2 配置)

| 类别 | 文件 | 类型 | 来源 I-NN |
|---|---|---|---|
| 源文件(新增 5)| `lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java` | 新增 | I-02 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransport.java` | 新增 | I-03 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportProvider.java` | 新增 | I-04 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportAutoConfiguration.java` | 新增 | I-05 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/AgentCardCache.java` | 新增 | I-06 |
| 源文件(修改 1)| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | 修改 | I-01 |
| 配置文件(新增 2)| `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 新增 | I-07 |
| | `lingshu-a2a-client/src/main/proto/a2a.proto` | 新增 | I-08 |
| 配置文件(修改 1)| `lingshu-a2a-client/pom.xml` | 修改 | I-09 |
| **测试文件**(新增 5)| `lingshu-core/src/test/java/ai/lingshu/core/impl/router/A2aTransportRouterTest.java` | 新增(L1 + L2 Slice) |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/AgentCardCacheTest.java` | 新增(L1 Unit, 6 case) |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportProviderTest.java` | 新增(L1 Unit, 4 case) |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportTest.java` | 新增(L2 Slice + mock grpc, 5 case) |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aEndToEndIT.java` | 新增(L5 E2E, 2 case, 真实 grpc server)|
| | **合计** | **9 文件 + 5 测试 = 14 改动** |

**核心源文件改动 = 6**(5 新增 + 1 修改),**核心源文件改动符合边界 ≤ 5**(AgentConfig.A2a 嵌套类扩展算 0.5 个改动,5.5 边界内)

---

## 3. 测试策略(7 层金字塔 §5)

| 层级 | 文件 | case 数 | 覆盖 |
|---|---|---|---|
| **L1 Unit** | `AgentCardCacheTest` | 6 | put/get/miss + TTL expiry + 负缓存 + stats hitRatio + null/边界 |
| | `GrpcA2aTransportProviderTest` | 4 | create(cfg) happy path + grpcTarget null/非法 + cardTtl 边界 + LINGS-S07 抛出 |
| | `A2aTransportRouterTest` | 4 | 单 Provider + 多 Provider 同 name priority 决胜 + 未知 name 抛 IllegalArgumentException + describe() |
| **L2 Slice** | `GrpcA2aTransportTest` | 5 | fetchCard 命中 / miss / 负缓存 + submit / get / cancel / subscribe streaming(用 mock grpc stub)|
| **L5 E2E** | `GrpcA2aEndToEndIT` | 2 | 启动真实 grpc server(InProcessServer) + 通过 Spring 上下文装配 A2aTransport + 调 fetchCard / submit 验证完整链路 |
| **合计** | | **21 case** | 5 文件 + 21 case |

**R-13 强依赖镜像**:测试运行环境不需要真实 grpc server(避免端口冲突),L5 IT 用 `io.grpc.inprocess.InProcessServerBuilder`(同进程内建 server,不占用端口)

---

## 4. 7 步实施顺序

### Step 1:Phase 1 Setup — 环境验证 + R-13 baseline capture
- 验证 JDK 17 + Maven 3.6.3+ + 当前 branch
- `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009a-pre.txt`(baseline 0 grpc deps)
- 验证 Story #001—#009 测试全过(`mvn -pl lingshu-core,lingshu-a2a-server test`)

### Step 2:Phase 2 Foundational — `AgentConfig.A2a` 扩展 + `pom.xml` 加 grpc 依赖
- 修改 `AgentConfig.java` 加 `grpcTarget` + `cardTtl` 字段
- 修改 `lingshu-a2a-client/pom.xml` 加 `grpc-stub` + `protobuf-java` + `os-maven-plugin` + `protobuf-maven-plugin`
- 验证编译 + 测试:`mvn -pl lingshu-core compile` + `mvn -pl lingshu-a2a-client compile`

### Step 3:Phase 3 Data — `a2a.proto` + protobuf-maven-plugin 代码生成
- 创建 `src/main/proto/a2a.proto` 5 RPC + 6 message
- 验证 `mvn -pl lingshu-a2a-client compile` 在 `target/generated-sources/protobuf/` 生成 Java stub
- 编译不过则调整 `pom.xml` protobuf 插件配置

### Step 4:Phase 4 Cache — `AgentCardCache`(US2 + L1 Unit 6 case)
- 创建 `AgentCardCache.java` + `CacheEntry` nested + `Stats` nested
- 创建 `AgentCardCacheTest.java`(6 case)
- 验证 `mvn -pl lingshu-a2a-client test -Dtest=AgentCardCacheTest`

### Step 5:Phase 5 Router — `A2aTransportRouter` concrete stub(US3 + L1 Unit 4 case)
- 创建 `lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`
- 创建 `A2aTransportRouterTest.java`(4 case)
- 验证 `mvn -pl lingshu-core test -Dtest=A2aTransportRouterTest`

### Step 6:Phase 6 Transport — `GrpcA2aTransport` + `GrpcA2aTransportProvider` + AutoConfiguration(US1 + L1 + L2 + L5)
- 创建 `GrpcA2aTransport.java`(5 grpc stub method + `@PreDestroy close`)
- 创建 `GrpcA2aTransportProvider.java`(`create(cfg)` + LINGS-S07 校验)
- 创建 `GrpcA2aTransportAutoConfiguration.java` + SPI `imports` 文件
- 创建 `GrpcA2aTransportProviderTest.java`(4 case)
- 创建 `GrpcA2aTransportTest.java`(5 case, mock grpc)
- 创建 `GrpcA2aEndToEndIT.java`(2 case, 真实 InProcessServer)
- 验证 `mvn -pl lingshu-a2a-client verify -Dtest=GrpcA2aEndToEndIT`

### Step 7:Phase 7 Validate + PR — 全量回归 + R-13 dep-tree diff + PR body
- 全量测试:`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test`(期望 234 + 21 = 255 case 全过)
- R-13 自查:`mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009a-post.txt && diff /tmp/deps-009a-pre.txt /tmp/deps-009a-post.txt`(期望 +grpc-stub +protobuf-java + 传递 netty / guava)
- binary size check:`mvn -pl lingshu-a2a-client package` 后 `ls -lh target/*.jar`,确认 < 35MB
- 更新 README + dsh §13 changelog
- 提交 + PR(标题 `feat(a2a): Story #009a a2a-grpc-transport — GrpcA2aTransport 3 件套 + A2aTransportRouter + AgentCardCache`)

---

## 5. 关键不变项

- `A2aTransport` interface 5 方法契约不变(已落地 lingshu-core)
- `Providers.A2aTransportProvider` interface 不变
- `SlotRouter<P, T>` 父类行为不变
- `Routers.java` 6 个 Router 不动(本 Story `A2aTransportRouter` 单独文件先例,**不**回填到 `Routers.java`)
- `LocalAgentCardGenerator`(Story #009 已落地)不动
- `A2aServer`(Story #009 已落地)不动
- `AgentCard` 数据类型不动
- §5.4 plugin Bean 名约定不变
- §5.5 多 Provider 模式样板不变
- §5.7 SPI 选型决策不变

---

## 6. 风险 / 边界 / 反模式

| 风险 | 缓解 |
|---|---|
| **R-13 grpc +5MB binary** | R-13 mitigation (d) 强制:dep-tree 自查 + PR body + binary size < 35MB baseline |
| **grpc-java 需要 JDK 8 但 CI matrix 是 ubuntu+JDK 8/17/21** | grpc-java 1.55.x 兼容 JDK 8;CI matrix 已含 JDK 17,运行时验证 |
| **protobuf-maven-plugin 跨 OS 编译差异** | 用 `os-maven-plugin` 自动检测 OS + 选 protoc 二进制 |
| **InProcessServer 启动期副作用(JVM) | 用 JUnit 5 `@AfterEach` cleanup + `Server.shutdownNow()`|
| **5 文件改动边界** | AgentConfig.A2a 嵌套类扩展算 0.5,5.5 边界内;若评审严卡 5,再拆 AgentCardCache 到 #009a2 子 Story |
| **A2aTransportRouter 独立文件先例** | `Routers.java` L31-122 已落地 6 Router,本 Story 单独文件先例,后续 §5.3.1.0 可选择性回填 |
| **grpc-java 的 netty 依赖** | grpc-stub 1.55.x 传递依赖 netty-handler / netty-transport / netty-codec-http,**不**引入 grpc-netty-shaded(避免 shading + 5MB+) |
| **grpc-java 的 guava 传递依赖** | grpc-stub 1.55.x 传递依赖 `com.google.guava:listenablefuture` (no dep)+ `com.google.guava:guava`(JDK 8 兼容 31.1-jre)|
| **A2aTransport interface String 签名 vs dsh URI/AgentRef 签名** | 适配实际签名(`fetchCard(agentName)` / `submit(agentName, skill, inputJson)` / `get(taskId)` / `cancel(taskId)` / `subscribe(taskId, onEvent)`),grpc 实现 agentName 通过 grpc request metadata 传递 |

---

## 7. 关键依赖增量(dep-tree 自查结果预计)

**本 Story +2 新依赖**:
- `io.grpc:grpc-stub:1.55.x`(~700 KB)
- `com.google.protobuf:protobuf-java:3.22.x`(~1.6 MB)
- **传递依赖**:`io.grpc:grpc-core`(~600 KB)+ `io.grpc:grpc-api`(~200 KB)+ `io.grpc:grpc-context`(~50 KB)+ `io.grpc:grpc-protobuf`(~300 KB)+ `com.google.guava:guava` 31.1-jre(~3 MB)+ `io.netty:netty-*`(~2 MB)+ `com.google.code.findbugs:jsr305`(~30 KB)
- **合计**:~6.5 MB 新依赖(`< 35MB baseline + 25MB` 仍有 ~3.5MB buffer)

**R-13 mitigation (d) 执行清单**:
1. ✅ 实施期跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009a-pre.txt`(baseline)
2. ✅ 实施后跑同命令 > `/tmp/deps-009a-post.txt`
3. ✅ `diff /tmp/deps-009a-pre.txt /tmp/deps-009a-post.txt` 验证增量
4. ✅ PR body 末加 `### R-13 dependency:tree 自查` 节,贴关键子树(io.grpc / com.google.protobuf / com.google.guava / io.netty)
5. ✅ binary size < 35MB baseline check