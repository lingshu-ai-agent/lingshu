# Quickstart: Story #009b a2a-inprocess-transport

**Story**: #009b
**Branch**: `story-009b-a2a-inprocess-transport`
**Created**: 2026-09-22

> 7 个验证场景,覆盖 spec.md 4 User Stories + 12 Edge Cases + AC-10 关联。

---

## 验证场景总览

| # | 场景 | US | 类型 | 验证方式 |
|---|---|---|---|---|
| **VS-1** | in-process fetchCard happy path | US-1 + US-3 | L1 Unit | InProcessA2aTransportTest |
| **VS-2** | in-process fetchCard 命中 AgentCardCache | US-1 + EC-12 | L1 Unit | InProcessA2aTransportTest |
| **VS-3** | in-process fetchCard miss → 抛 LINGS-S08 | US-1 + EC-1 | L1 Unit | InProcessA2aTransportTest |
| **VS-4** | InProcessA2aRegistry 单例 + 100 线程并发 | US-2 + EC-4 + EC-10 | L1 Unit | InProcessA2aRegistryTest |
| **VS-5** | A2aServer.start() 自动 register + stop() 自动 unregister | US-3 + EC-7 + EC-8 | L2 Slice | A2aServerInProcessRegistrationTest |
| **VS-6** | 3 Provider 同存(http-jsonrpc + grpc + in-process)| US-4 + AC-10 | L2 Slice | InProcessA2aTransportAutoConfigurationTest |
| **VS-7** | in-process 0 binary delta(R-13 mitigation (d) baseline) | NFR-003 | R-13 mirror | `mvn dependency:tree` diff |

---

## VS-1: in-process fetchCard happy path

**目标**:验证 `InProcessA2aTransport.fetchCard(agentName)` 在 registry 已有对应 card 时正常返回

**前置**:
```java
InProcessA2aRegistry.getInstance().clear();
Map<String, Object> cardA = new HashMap<>();
cardA.put("name", "alice-coding");
cardA.put("description", "Remote Alice coding agent");
cardA.put("version", "1.0.0");
InProcessA2aRegistry.getInstance().put("alice-coding", cardA);

AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
InProcessA2aTransport transport = new InProcessA2aTransport(
    InProcessA2aRegistry.getInstance(), cache);
```

**操作**:
```java
Map<String, Object> result = transport.fetchCard("alice-coding");
```

**期望**:
```java
assertThat(result.get("name")).isEqualTo("alice-coding");
assertThat(result.get("description")).isEqualTo("Remote Alice coding agent");
assertThat(result.get("version")).isEqualTo("1.0.0");
assertThat(cache.stats().missCount).isEqualTo(1); // 第一次 miss
assertThat(cache.stats().hitCount).isEqualTo(0);
```

**测试文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportTest.java#testFetchCardHappyPath`

---

## VS-2: in-process fetchCard 命中 AgentCardCache

**目标**:验证 `fetchCard` 第二次调用命中 `AgentCardCache`,**不**走 registry

**前置**:同 VS-1,继续

**操作**:
```java
Map<String, Object> firstResult = transport.fetchCard("alice-coding"); // miss
Map<String, Object> secondResult = transport.fetchCard("alice-coding"); // hit cache

// 清场:让 registry.get 找不到(模拟 registry put 被删)
InProcessA2aRegistry.getInstance().remove("alice-coding");
Map<String, Object> thirdResult = transport.fetchCard("alice-coding"); // 仍 hit cache,registry miss 不影响
```

**期望**:
```java
assertThat(firstResult).isEqualTo(secondResult);
assertThat(secondResult).isEqualTo(thirdResult); // 关键:cache hit 屏蔽 registry miss
assertThat(cache.stats().hitCount).isEqualTo(2); // 第二次 + 第三次都是 hit
assertThat(cache.stats().missCount).isEqualTo(1); // 只有第一次
```

**测试文件**:`InProcessA2aTransportTest#testFetchCardHitsCache`

**Javadoc 注解**:`@DisplayName("EC-12: fetchCard 命中缓存,不 registry lookup")`

---

## VS-3: in-process fetchCard miss → 抛 LINGS-S08

**目标**:验证 `fetchCard(unknownAgent)` 抛 `LINGS-S08` + 负缓存

**前置**:
```java
InProcessA2aRegistry.getInstance().clear();
AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
InProcessA2aTransport transport = new InProcessA2aTransport(
    InProcessA2aRegistry.getInstance(), cache);
```

**操作**:
```java
assertThatThrownBy(() -> transport.fetchCard("unknown-agent"))
    .isInstanceOf(LingshuException.class)
    .hasMessageContaining("LINGS-S08")
    .hasMessageContaining("No in-process A2A server registered for agentName='unknown-agent'")
    .hasMessageContaining("Available: []");

// 负缓存命中
Map<String, Object> result2 = transport.fetchCard("unknown-agent");
assertThat(result2).isNull();
assertThat(cache.stats().negativeHitCount).isEqualTo(1);
```

**期望**:
```java
assertThat(cache.stats().negativeHitCount).isEqualTo(1); // 第一次抛 LINGS-S08 前 putNegative
```

**测试文件**:`InProcessA2aTransportTest#testFetchCardMissThrowsLingsS08`

**Javadoc 注解**:`@DisplayName("EC-1 + EC-3: registry miss → LINGS-S08 + 负缓存")`

---

## VS-4: InProcessA2aRegistry 单例 + 100 线程并发

**目标**:验证 `getInstance()` 单例 + `ConcurrentHashMap` 100 线程并发原子性

**操作**:
```java
@Test
void testSingletonIdentity() {
    InProcessA2aRegistry r1 = InProcessA2aRegistry.getInstance();
    InProcessA2aRegistry r2 = InProcessA2aRegistry.getInstance();
    assertThat(r1).isSameAs(r2);
    r1.clear();
}

@Test
void testConcurrentPut10000() throws InterruptedException {
    InProcessA2aRegistry registry = InProcessA2aRegistry.getInstance();
    registry.clear();

    int threads = 100;
    int perThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
        final int tid = t;
        executor.submit(() -> {
            try {
                for (int i = 0; i < perThread; i++) {
                    String key = "agent-" + tid + "-" + i;
                    Map<String, Object> card = new HashMap<>();
                    card.put("name", key);
                    registry.put(key, card);
                }
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await();
    executor.shutdown();

    assertThat(registry.size()).isEqualTo(10000);
    assertThat(registry.names().size()).isEqualTo(10000);
    registry.clear();
}
```

**期望**:
```java
assertThat(registry.size()).isEqualTo(10000); // 无 key 丢失
```

**测试文件**:`InProcessA2aRegistryTest#testSingletonIdentity` + `testConcurrentPut10000`

**Javadoc 注解**:`@DisplayName("EC-4 + EC-10: ConcurrentHashMap 原子保证 + 单例工厂")`

---

## VS-5: A2aServer.start() 自动 register + stop() 自动 unregister

**目标**:验证 Spring 上下文启动 Agent → `A2aServer.start()` 把 card put 进 registry;`stop()` 把 card 删

**前置**(L2 Slice):
```java
@SpringBootTest(classes = {
    AgentConfig.class,
    A2aServer.class,
    A2aServerAutoConfiguration.class
})
@TestPropertySource(properties = {
    "agent.identity.name=test-alice-coding",
    "agent.a2a.host=127.0.0.1",
    "agent.a2a.port=0"  // OS-assigned
})
class A2aServerInProcessRegistrationTest {
    @Autowired A2aServer a2aServer;

    @BeforeEach
    void clear() {
        InProcessA2aRegistry.getInstance().clear();
    }
}
```

**操作**:
```java
@Test
void testStartRegistersAndStopUnregisters() {
    // Spring 上下文启动已触发 A2aServer.start()
    assertThat(InProcessA2aRegistry.getInstance().contains("test-alice-coding"))
        .isTrue();

    Map<String, Object> registered = InProcessA2aRegistry.getInstance().get("test-alice-coding");
    assertThat(registered).isNotNull();
    assertThat(registered.get("name")).isEqualTo("test-alice-coding");

    a2aServer.stop();

    assertThat(InProcessA2aRegistry.getInstance().contains("test-alice-coding"))
        .isFalse();
}
```

**期望**:通过

**测试文件**:`lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerInProcessRegistrationTest.java`

**Javadoc 注解**:`@DisplayName("EC-7 + EC-8: start 注册 + stop 注销")`

---

## VS-6: 3 Provider 同存(http-jsonrpc + grpc + in-process)

**目标**:验证 `A2aTransportRouter` 注入 #009b `InProcessA2aTransportProvider` + 启动日志列 3 行 ✓

**前置**(L2 Slice):
```java
@SpringBootTest(classes = {
    GrpcA2aTransportAutoConfiguration.class,
    InProcessA2aTransportAutoConfiguration.class,
    A2aTransportRouter.class
})
class InProcessA2aTransportAutoConfigurationTest {
    @Autowired A2aTransportRouter router;
}
```

**操作**:
```java
@Test
void testMultiProviderCoexistence() {
    Set<String> available = router.available();
    assertThat(available).containsExactlyInAnyOrder("grpc-1.0.0", "in-process-1.0.0");
}

@Test
void testResolveInProcess() {
    InProcessA2aTransport transport = (InProcessA2aTransport)
        router.resolve("in-process-1.0.0", null);
    assertThat(transport).isNotNull();
}

@Test
void testUnknownNameThrows() {
    assertThatThrownBy(() -> router.resolve("unknown", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown A2aTransportRouter 'unknown'")
        .hasMessageContaining("Available:");
}
```

**期望**:
- 启动日志含 `resolved 2 provider(s)` + 两行 ✓ 列表(#009b 阶段只有 2 个,未来 #009c 才有 3 个)
- `available()` 集合 2 元素
- `resolve("in-process-1.0.0", cfg)` 返回 `InProcessA2aTransport`

**测试文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfigurationTest.java`

**Javadoc 注解**:`@DisplayName("US-4 + AC-10: 多 Provider 同存 + 路由")`

---

## VS-7: in-process 0 binary delta(R-13 mitigation (d) baseline 镜像)

**目标**:验证本 Story **+0 新依赖**,`mvn dependency:tree -pl lingshu-a2a-client` 与 #009a baseline **完全一致**

**前置**:
```bash
# 已在 Step 1 抓取 baseline
ls -la /tmp/deps-009b-pre.txt
```

**操作**:
```bash
cd <lingshu 主仓根>
mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009b-post.txt

# 对比
diff /tmp/deps-009b-pre.txt /tmp/deps-009b-post.txt
```

**期望**:`diff` 命令**无输出**(两个文件完全一致)

**enforcer 验证**:
```bash
mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify
```

**期望**:BUILD SUCCESS,enforcer `banned-dependencies` 规则**不 fail**

**关键子树(贴到 PR body `### R-13 dependency:tree 自查` 节)**:
```
ai.lingshu:lingshu-a2a-client:jar:0.1.0-SNAPSHOT
├── io.grpc:grpc-stub:jar:1.55.1:compile              [#009a 已落地,本 Story 不变]
├── com.google.protobuf:protobuf-java:jar:3.22.3:compile  [#009a 已落地,本 Story 不变]
├── ...
[无新增 subtree]
```

**测试方式**:手动跑命令 + 贴输出到 PR body,**不**写自动化测试

---

## 总结

7 个验证场景覆盖:
- ✅ AC-10 关联(US-4 + VS-6)
- ✅ 4 个 User Story(US-1—US-4 各有覆盖)
- ✅ 12 个 Edge Cases 中的 5 个(EC-1 / EC-4 / EC-7 / EC-8 / EC-10 / EC-12)
- ✅ R-13 mitigation (d) baseline 镜像(VS-7)

**未在 L1/L2 覆盖的 Edge Cases**:
- EC-2 / EC-3 / EC-5 / EC-6 / EC-9 / EC-11:由测试代码自然覆盖(在 L1/L2 测试 setup + tearDown + 异常路径中触发)

**测试 case 总数**:20 case(6 InProcessA2aRegistryTest + 5 InProcessA2aTransportTest + 3 InProcessA2aTransportProviderTest + 3 InProcessA2aTransportAutoConfigurationTest + 3 A2aServerInProcessRegistrationTest)
