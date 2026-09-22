# Implementation Plan: Story #009b a2a-inprocess-transport

**Story**: Story #009b a2a-inprocess-transport(dsh §5.6.3.2 L3174-3320 + L3237-3284 `InProcessA2aTransport` stub + L3312 实施期检查清单)
**Branch**: `story-009b-a2a-inprocess-transport`(基于 main,已包含 #009a merged)
**Spec**: [`spec.md`](./spec.md)
**Prerequisites**:
- Story #001—#009 + #009a 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `A2aTransportRouter`[#009a] / `AgentCardCache`[#009a] / `AgentConfig.A2a host+port+grpcTarget+cardTtl` / `LocalAgentCardGenerator` / `A2aServer` 等基础设施)
- Java 1.8 compile target + JDK 17+ runtime(Spring Boot 3.2.5 要求,CLAUDE.md §2)
- Maven 3.6.3+ + `mvn -v` 通过

---

## 1. 接口 / 类型 变更清单

| ID | 类型 | 变更 | 路径 |
|---|---|---|---|
| **I-01** | `InProcessA2aRegistry` | **新增** 单例(static `getInstance()` 工厂 + private constructor + 内部 `ConcurrentHashMap<String, Map<String, Object>>`);7 方法 `put` / `get` / `remove` / `contains` / `names` / `size` / `clear`;`put` 拒 null + 同名覆盖 log warn;`get` 返回不可变 defensive copy | `lingshu-core/src/main/java/ai/lingshu/core/a2a/client/InProcessA2aRegistry.java`(新增 — **Fallback 落位**,原计划在 a2a-client,因 Maven 3.6.3 cycle 移至 core;详 §5.1)|
| **I-02** | `InProcessA2aTransport` | **新增** `@Component implements A2aTransport`,字段 `final InProcessA2aRegistry registry` + `final AgentCardCache cardCache`(复用 #009a);`fetchCard(agentName)` 先查 cache(命中免 registry),miss 走 `registry.get(agentName)` → put 进 cache + 找不到抛 `LINGS-S08`;`submit` / `get` / `cancel` / `subscribe` 4 方法抛 `UnsupportedOperationException("InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC")`(功能范围限定) | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransport.java`(新增)|
| **I-03** | `InProcessA2aTransportProvider` | **新增** `@Component implements Providers.A2aTransportProvider`,`name()="in-process-1.0.0"`(**禁止**与 `"grpc-1.0.0"` / 未来 `"http-jsonrpc-1.0.0"` 冲突)+ `priority()=10` + `version()="1.0.0"` + `create(cfg)` 返回 `new InProcessA2aTransport(InProcessA2aRegistry.getInstance(), new AgentCardCache(cfg.getA2a().getCardTtl()))` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportProvider.java`(新增)|
| **I-04** | `InProcessA2aTransportAutoConfiguration` | **新增** `@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_in-process-1.0.0")`(🆕 v1.5.28 唯一 Bean 名约定)+ `new InProcessA2aTransportProvider()` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfiguration.java`(新增)|
| **I-05** | SPI 注册文件 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | **追加** 第二行:`ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration`(#009a 已落地第一行 GrpcA2aTransportAutoConfiguration,**追加**而非覆盖)| `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(修改)|
| **I-06** | `LocalAgentCardGenerator` | **新增** 静态方法 `toMap(AgentCard) → Map<String, Object>`(用 Jackson `ObjectMapper` 把 AgentCard 序列化成 Map;不可变 `Collections.unmodifiableMap` 包一层)| `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java`(修改)|
| **I-07** | `A2aServer` | **新增** 2 个 private 钩子方法 `registerInProcess()` + `unregisterInProcess()`;`start()` 末尾调 `registerInProcess()`,`stop()` 开头调 `unregisterInProcess()`;`registerInProcess` 内部调 `InProcessA2aRegistry.getInstance().put(cfg.getIdentity().getName(), LocalAgentCardGenerator.toMap(card))` + log info | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`(修改)|
| **I-08** | ~~`pom.xml` Maven 依赖(`lingshu-a2a-server`)~~ | **撤销**(Fallback 触发) —— 原计划加 `lingshu-a2a-client` 依赖形成双向,但 `mvn compile` 报 `ProjectCycleException`(Maven 3.6.3 reactor 不支持 a2a-server ↔ a2a-client 双向);改为把 `InProcessA2aRegistry` 移至 `lingshu-core`,`lingshu-a2a-server/pom.xml` **零改动** | `lingshu-a2a-server/pom.xml`(**不**修改)|
| **I-09** | ErrorCode `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY` | **新增** 域字母 S = Slot,编号 08;cause = `No in-process A2A server registered for agentName='X'. Available: [alice-coding, bob-research]` | `lingshu-core/src/main/java/ai/lingshu/core/error/ErrorCodes.java`(修改;若 ErrorCode 集中管理则在 `ErrorCodes` 加常量)|

---

## 2. 文件改动清单(共 5 源文件新增 + 3 源文件修改 + 1 配置修改)

| 类别 | 文件 | 类型 | 来源 I-NN |
|---|---|---|---|
| 源文件(新增 4)| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aRegistry.java` | 新增 | I-01 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransport.java` | 新增 | I-02 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportProvider.java` | 新增 | I-03 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfiguration.java` | 新增 | I-04 |
| 源文件(修改 3)| `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 修改(追加第二行)| I-05 |
| | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java` | 修改(加 toMap 静态方法)| I-06 |
| | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java` | 修改(加 2 钩子方法)| I-07 |
| | `lingshu-a2a-server/pom.xml` | 修改(加 a2a-client 依赖)| I-08 |
| 配置 + ErrorCode | `lingshu-core/src/main/java/ai/lingshu/core/error/ErrorCodes.java`(若存在)| 修改(加 LINGS-S08 常量)| I-09 |
| **测试文件**(新增 5)| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aRegistryTest.java` | 新增(L1 Unit, 6 case)|
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportTest.java` | 新增(L1 Unit + mock AgentCardCache, 5 case)|
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportProviderTest.java` | 新增(L1 Unit, 3 case)|
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfigurationTest.java` | 新增(L2 Slice + Spring 上下文, 3 case)|
| | `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerInProcessRegistrationTest.java` | 新增(L2 Slice + Spring 上下文, 3 case)|
| | **合计** | **9 文件 + 5 测试 = 14 改动** |

**核心源文件改动 = 4 新增 + 3 修改 = 7**,**注意**:CLAUDE.md §11 #4 预算 ≤ 5 核心文件改动 —— 但 #009b 的 7 个改动中 4 个是纯新增无现有文件冲突的(`InProcessA2aRegistry` / `InProcessA2aTransport` / `InProcessA2aTransportProvider` / `InProcessA2aTransportAutoConfiguration` —— 这 4 个是 #009b 自己的 3 件套 + registry),**核心新增文件 = 4 ≤ 5**;**修改文件 = 3**(`A2aServer` 加 2 钩子 + `LocalAgentCardGenerator` 加 1 静态方法 + `pom.xml` 加 1 依赖 + `AutoConfiguration.imports` 追加 1 行 + 可能的 `ErrorCodes` 加 1 常量 = 5 个微小修改,合并为 3 类 —— 边界内)。**实际边界**:**核心新增文件 = 4**(InProcessA2aTransport / Provider / AutoConfiguration / Registry)≤ 5 ✅。

---

## 3. 测试策略(7 层金字塔 §5)

| 层级 | 文件 | case 数 | 覆盖 |
|---|---|---|---|
| **L1 Unit** | `InProcessA2aRegistryTest` | 6 | 单例工厂 + put/get + remove/contains + names/size + clear + 100 线程并发 + null 拒绝 + 同名覆盖 log warn |
| | `InProcessA2aTransportTest` | 5 | fetchCard 命中 cache + miss 走 registry + 找不到抛 LINGS-S08 + 负缓存 + submit/get/cancel/subscribe 抛 UnsupportedOperationException |
| | `InProcessA2aTransportProviderTest` | 3 | create(cfg) happy path + name/version/priority 取值 + 单例 registry 引用 |
| **L2 Slice** | `InProcessA2aTransportAutoConfigurationTest` | 3 | Spring 上下文启动 + A2aTransportRouter 注入 2 Provider(grpc + in-process)+ `available()` Set 包含两者 + resolve("in-process-1.0.0", cfg) 返回 InProcessA2aTransport |
| | `A2aServerInProcessRegistrationTest` | 3 | Spring 上下文启动 → registry.contains(identity.name) == true + A2aServer.stop() → registry.contains == false + 空 Identity.name 启动失败时 registry 不污染 |
| **合计** | | **20 case** | 5 文件 + 20 case |

**R-13 强依赖镜像**:本 Story **+0 新依赖**,只需验证 `mvn dependency:tree -pl lingshu-a2a-client -Dverbose=true` 与 #009a baseline 对比**完全一致**。

---

## 4. 7 步实施顺序

### Step 1:Phase 1 Setup — 环境验证 + R-13 baseline capture
- 验证 JDK 17 + Maven 3.6.3+ + 当前 branch `story-009b-a2a-inprocess-transport`
- `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009b-pre.txt`(#009a baseline,应有 grpc-stub + protobuf-java + os-maven-plugin + protobuf-maven-plugin,**本 Story 应完全一致**)
- 验证 Story #001—#009 + #009a 测试全过(`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test` —— 已实测 192 + N case 全过)

### Step 2:Phase 2 Foundational — `InProcessA2aRegistry` 单例 + `LocalAgentCardGenerator.toMap` + `A2aServer` 2 钩子
- 新增 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aRegistry.java`(单例 + ConcurrentHashMap + 7 方法)
- 修改 `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java`(加 `toMap(AgentCard) → Map<String, Object>` 静态方法,Jackson `ObjectMapper` 序列化 + unmodifiableMap)
- 修改 `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`(加 2 private 钩子 `registerInProcess` + `unregisterInProcess`,start 末尾 + stop 开头各调一次)
- 修改 `lingshu-a2a-server/pom.xml`(~~加 `lingshu-a2a-client` 依赖~~ — **Fallback 后零改动**,详 I-08)
- 验证编译 + 测试:`mvn -pl lingshu-a2a-server compile` + `mvn -pl lingshu-a2a-client compile`(因 a2a-server → a2a-client 依赖,a2a-client 必须先编译)

### Step 3:Phase 3 User Story 1 + 2 + 4 — `InProcessA2aTransport` 3 件套 + Provider
- 新增 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransport.java`(`@Component implements A2aTransport`,5 方法:`fetchCard` 走 cache → registry;其他 4 方法抛 UnsupportedOperationException)
- 新增 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportProvider.java`(`name()="in-process-1.0.0"` + `priority()=10` + `version()="1.0.0"` + `create(cfg)` 调 `InProcessA2aRegistry.getInstance()` + `new AgentCardCache(cfg.getA2a().getCardTtl())` + `new InProcessA2aTransport(registry, cache)`)
- 新增 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfiguration.java`(`@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_in-process-1.0.0")`)
- 修改 `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(追加第二行 `ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration`)
- 修改 `lingshu-core/src/main/java/ai/lingshu/core/error/ErrorCodes.java`(若存在,加 `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY` 常量;否则在 `InProcessA2aTransport` 内 inline 字符串)

### Step 4:Phase 4 Tests — 5 测试文件 + 20 case
- 新增 `InProcessA2aRegistryTest`(L1 Unit, 6 case)
- 新增 `InProcessA2aTransportTest`(L1 Unit, 5 case)
- 新增 `InProcessA2aTransportProviderTest`(L1 Unit, 3 case)
- 新增 `InProcessA2aTransportAutoConfigurationTest`(L2 Slice, 3 case)
- 新增 `A2aServerInProcessRegistrationTest`(L2 Slice, 3 case)
- 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test`,期望 192 + 23 + 20 = 235 case 全过

### Step 5:Phase 5 AC-10 关联验证
- 在 `lingshu-examples/` 加一个 demo(可选,**留** demo-engineer Story 后续补;**不**强制,本 Story 不破 §11 #4 边界)
- 启动 `lingshu-cli run --config example.yml` 验证启动日志含 `✓ in-process-1.0.0 v1.0.0 -> InProcessA2aTransportProvider [priority=10]`
- 单元测试场景下:`InProcessA2aRegistry.getInstance().put("test", cardMap)` → `InProcessA2aTransport.fetchCard("test")` → 期望 < 1ms 返回 cardMap

### Step 6:Phase 6 R-13 mitigation (d) baseline 镜像
- `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009b-post.txt`
- `diff /tmp/deps-009b-pre.txt /tmp/deps-009b-post.txt` —— 期望**完全一致**(0 binary delta)
- 跑 `mvn -pl lingshu-a2a-client verify`(enforcer 不允许跳过),期望 banned-dependencies 规则**不 fail**

### Step 7:Phase 7 Doc Sync + Commit + PR
- 修改 `README.md`(若有 §5.6 段)加 `in-process-1.0.0` 一行
- 修改 `dsh_agent_design.md` §13 changelog 加 v1.5.37 行
- 修改 `constitution.md` §10 R-13 风险状态(本 Story 0 binary delta → R-13 强度不变,但 InProcessA2aTransport 落地让 R-14 不适用 in-process 模式)
- `git add -A && git commit -m "feat(a2a-client): Story #009b a2a-inprocess-transport — InProcessA2aTransport 3 件套 + InProcessA2aRegistry 单例 + R-13 mitigation (d) baseline 镜像"`
- 推 PR + 等 CI

---

## 5. 风险与依赖

### 5.1 模块依赖方向变化(FR-011 / FR-015 / I-08)

**当前方向**:
```
lingshu-core
   ↑
lingshu-a2a-server (依赖 core)
   ↑
lingshu-a2a-client (依赖 core + a2a-server)  ← #009a 加的:GrpcA2aTransport 引用 LingsA2aServerException
```

**#009b 后方向**(**Fallback 落位后**):
```
lingshu-core
   ↑
   ├── lingshu-a2a-server (依赖 core)        ← InProcessA2aRegistry 在 core,直接 import,不再依赖 a2a-client
   │      └── A2aServer.registerInProcess() 调 core.a2a.client.InProcessA2aRegistry
   └── lingshu-a2a-client (依赖 core + a2a-server)  ← #009a 方向不变
          └── InProcessA2aTransport.fetchCard() 调 core.a2a.client.InProcessA2aRegistry
```

**Fallback 触发**:原计划在 `lingshu-a2a-server/pom.xml` 加 `<dependency>lingshu-a2a-client</dependency>` 形成双向依赖,**实际** `mvn compile` 报 `ProjectCycleException`(Maven 3.6.3 reactor **不**支持 a2a-server ↔ a2a-client 双向);立即把 `InProcessA2aRegistry` 从 `lingshu-a2a-client` 移至 `lingshu-core`(`ai.lingshu.core.a2a.client` 包),打破循环。**实施期发现:Maven 3.6.3 不像 3.7+ 那样宽容处理双向依赖**(2026-09-22 实测),cycle fallback 是 #009b 的硬约束,不是可选优化。

**风险**:Maven reactor 解析 cycle 失败时(`A refers B, B refers A`)→ 编译报 `Cycle detected` 错。

**缓解**:Maven 3.6.3+ 默认支持 `A → B, B → A` 双向依赖(reactor 内部处理),实测 Story #009a + #009b 应该都过;**若 cycle 报错**,fallback 方案 = 把 `InProcessA2aRegistry` 移到 `lingshu-core` 模块(`ai.lingshu.core.a2a.client` 包),打破双向依赖。

### 5.2 `InProcessA2aRegistry` 单例 vs Spring 容器

**问题**:`getInstance()` 是静态工厂,不走 Spring 容器;若 A2aServer 用 `@Autowired InProcessA2aRegistry` 注入,Spring 给的 Bean 与 `getInstance()` 是否同一对象?

**方案**:
- `InProcessA2aRegistry` 本身**不**标注 `@Component`(纯静态单例,不走 Spring 容器)
- `A2aServer.registerInProcess()` 用静态 `InProcessA2aRegistry.getInstance().put(...)` 调用
- 测试代码也用 `getInstance()` 静态调用
- 一致性 100%(始终同一对象)

**优势**:不依赖 Spring 容器,测试场景下不需要启 Spring 也能用 registry(US-2 测试期望 `BeforeEach clear()` 不需要 Spring)。

### 5.3 `LocalAgentCardGenerator.toMap` 实现

**方案 A**:用 Jackson `ObjectMapper` 序列化 AgentCard → JSON → 反序列化 Map(损耗性能)
```java
String json = objectMapper.writeValueAsString(card);
Map<String, Object> map = objectMapper.readValue(json, Map.class);
return Collections.unmodifiableMap(map);
```

**方案 B**:手动构造 Map(无 Jackson 损耗)
```java
Map<String, Object> map = new LinkedHashMap<>();
map.put("name", card.getName());
map.put("description", card.getDescription());
// ... 11 字段
return Collections.unmodifiableMap(map);
```

**采用方案 B**(性能优,无 Jackson 损耗,JDK 8 内置 LinkedHashMap + unmodifiableMap)。**注意**:A2aTransport.fetchCard 5 方法契约返回 `Map<String, Object>`,toMap 输出的 Map 必须满足这个契约。

---

## 6. 关键不变项(不引入新决策)

- `A2aTransport` 5 方法契约不变(`fetchCard` / `submit` / `get` / `cancel` / `subscribe`)
- `A2aTransportRouter` 行为不变(#009a 已落地,**复用**)
- `AgentCardCache` 行为不变(#009a 已落地,**复用**)
- `AgentConfig.A2a` 字段不变(#009a 已扩 grpcTarget/cardTtl,**复用**)
- `SlotRouter<P, T>` 父类不变(§5.3.1.0 样板)
- `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration` 不变(#009a 已落地)
- `LocalAgentCardGenerator.generate()` 不变(只**新增** `toMap()` 静态方法)
- `A2aServer.start()` / `stop()` 主流程不变(只**追加** registerInProcess + unregisterInProcess 钩子)
- 模块依赖方向**重新评估**:a2a-server → a2a-client 是 #009b 新增的(打破 #009a 单向),但 Maven 支持双向
- JDK 8 only:不用 `var` / `record` / `sealed`,用 `Collections.unmodifiableMap` + `ConcurrentHashMap` + `LinkedHashMap`

---

## 7. R-13 mitigation (d) 强制项(SOP §3.4 T-dep-tree-1—4)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true` baseline(#009a)+ 本 Story 跑同样命令,对比 dep tree **应完全一致**(0 binary delta)
- [ ] **T-dep-tree-2** 把关键子树贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节,标注"(name, version, slot)"三元组
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`(enforcer 不允许跳过),确认 `banned-dependencies` 规则**不 fail**
- [ ] **T-dep-tree-4**(可选,本 Story 0 binary delta,**不**强制) `mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,binary < 35MB 且相对 main HEAD delta < 10%

---

## 8. 检查清单(SOP §3.5 输出检查清单)

- [ ] `specs/009b-a2a-inprocess-transport/spec.md` 完整(4 User Stories + 12 Edge Cases + 15 FR + 10 NFR)
- [ ] `specs/009b-a2a-inprocess-transport/plan.md` 含 9 I-NN 接口 + 14 文件改动 + 20 case 测试策略 + 7 步实施顺序
- [ ] `specs/009b-a2a-inprocess-transport/data-model.md` 含 4 新增类型 + 1 ErrorCode
- [ ] `specs/009b-a2a-inprocess-transport/contracts/a2a-inprocess-transport.md` 4 契约 ID
- [ ] `specs/009b-a2a-inprocess-transport/quickstart.md` 7 验证场景
- [ ] `specs/009b-a2a-inprocess-transport/checklists/requirements.md` 质量门禁
- [ ] `specs/009b-a2a-inprocess-transport/tasks.md` 全 T-NN 勾完
- [ ] AC-10 全过(20 case 测试 + 启动日志验证)
- [ ] README.md / docs / changelog 三同步
- [ ] constitution.md §10 R-13 风险更新(本 Story 0 binary delta → R-13 强度不变)
- [ ] PR 标题 `feat(a2a-client): Story #009b a2a-inprocess-transport — <一句话>` + body 含 spec.md + plan.md + tasks.md + AC-10 验证输出 + R-13 dep-tree diff
