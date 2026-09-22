# Research: Story #009a a2a-grpc-transport

**Date**: 2026-09-22
**Author**: Claude Code(基于 dsh v1.5.36 §5.6.3.2 L3174-3320 + 用户 2026-09-22 会话反馈)
**Goal**: 锁定 Story #009a 的 5 项关键设计决策,避免实施期反推

---

## 决策 1:grpc-java + protobuf 选型(替代品 + 体积评估)

### 候选方案
| 方案 | 体积 | JDK 8 兼容 | 学习曲线 | 决策 |
|---|---|---|---|---|
| **io.grpc:grpc-stub + protobuf-java**(锚定)| ~5MB(grpc-stub 0.7MB + protobuf-java 1.6MB + 传递 netty ~2MB + guava ~3MB)| ✅ grpc-java 1.55.x 支持 JDK 8 | 中(protobuf idl + grpc stub)| ✅ **选定**(dsh §5.6.3.2 L3184 明确)|
| Apache Dubbo 3 triple(grpc 协议)| 没部署器生态 | ✅ | 高(Dubbo 体系)| ❌ 引入 Dubbo 生态违反 KISS |
| Spring 6 HttpInterface + RSocket | ~1MB | ✅ | 低 | ❌ 不支持 protobuf + streaming |
| HTTP/2 + protobuf 手写 | ~1.5MB(protobuf only)| ✅ | 高(自实现 gRPC)| ❌ 自实现 gRPC 违反 §5.7 决策 |

### 决策理由
- dsh §5.6.3.2 L3184 明确锚定 grpc-java(高频小消息 < 1KB + 强 schema 场景)
- R-13 mitigation (d) 允许引入,只须镜像 dep-tree + binary size < 35MB baseline
- grpc-java 1.55.x 是当前 LTS,2026-09-15 后停止支持 1.50.x 系列

### 扳机条件(重新评估)
- 高频小消息场景被 JsonRPC polling 替代(实测 P99 latency < 5ms 即考虑降级 HTTP+JSON)
- 二进制膨胀 > 10MB(35MB baseline 之上)→ 评估 protobuf-java 体积 + grpc-netty-shaded 替代 netty

---

## 决策 2:AgentCardCache 自实现 vs Caffeine/Guava Cache

### 候选方案
| 方案 | 体积 | 功能完整度 | 决策 |
|---|---|---|---|
| **ConcurrentHashMap + 手动 TTL**(锚定)| 0 新依赖 | 基础(put/get/putNegative/invalidate/stats)| ✅ **选定** |
| Caffeine(com.github.benmanes.caffeine:caffeine)| ~1.5MB | 完整(size limit + access TTL + write TTL + async refresh)| ❌ 引入新依赖违反 R-13 |
| Guava Cache(com.google.guava:guava 已有传递)| 0 新依赖 | 完整(CacheBuilder + size limit + TTL)| ❌ 但 grpc-java 已传递 guava,**未**直接用避免耦合 |
| Spring Cache(@Cacheable + ConcurrentMapCache)| 0 新依赖 | 集成 Spring 但 API 重量级(annotation 驱动)| ❌ 5 行直写缓存用不上 Spring 抽象 |

### 决策理由
- AgentCardCache 需求是 5 方法的薄薄一层,ConcurrentHashMap 足够
- 负缓存(短 TTL)是 `cacheTtl / 4` 计算,**不**用 Guava CacheBuilder 的 expireAfterWrite(复杂度高)
- `stats()` 4 指标(hits / misses / negatives / hitRatio)10 行代码,不必依赖框架
- 0 新依赖 + R-13 mitigation (d) 满足

### 扳机条件
- AgentCard 总数 > 10K 时需要 size-based eviction(ConcurrentHashMap 无限增长)→ 引入 Caffeine

---

## 决策 3:`A2aTransportRouter` 独立文件 vs 塞进 `Routers.java` 内聚

### 候选方案
| 方案 | 命名一致性 | 测试覆盖 | 决策 |
|---|---|---|---|
| **独立文件**(锚定)`lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`| ✅ §5.3.1.0 子节样板(7 Router concrete stub 大多独立文件)| ✅ 独立测试文件 | ✅ **选定** |
| 塞进 `Routers.java` 内聚 | ❌ 但 `Routers.java` 已 6 Router 拥挤 | ⚠️ 测试塞 `RoutersTest` 多类 | ❌ 内聚牺牲命名一致性 |

### 决策理由
- §5.3.1.0 L1892-2080 子节展示的 7 Router concrete stub 样板,**5/7 独立文件**(`LlmProviderRouter` / `ToolExecutorRouter` / `PermissionPolicyRouter` / `SessionStoreRouter` / `CompactorRouter`),**2/7 内聚**(`PromptBuilderRouter` / `MemorySourceRouter` 在 `Routers.java`)
- `A2aTransportRouter` 是 grpc 强依赖,本应在 `lingshu-a2a-client` 模块(grpc 依赖归属),而非 `lingshu-core` —— 但 Router 必须放在 core(被 AgentFactory `@Autowired`),**反**的妥协:独立文件,grpc 依赖通过 `GrpcA2aTransportProvider.create()` 在运行时引入,core 编译时不依赖 grpc

### 扳机条件
- 后续 §5.3.1.0 Router 总数 > 10 时考虑合并回 `Routers.java`(目前 7 Router 在 `Routers.java` 已 L122,加 A2aTransportRouter 到 8 Router,L160+,但**仍**独立文件清晰优先)

---

## 决策 4:grpc channel 生命周期管理(`@PreDestroy` + JVM shutdown hook)

### 候选方案
| 方案 | 资源泄漏风险 | 复杂度 | 决策 |
|---|---|---|---|
| **`@PreDestroy close()` + JVM shutdown hook 兜底**(锚定)| ✅ 双保险 | 中(10 行)| ✅ **选定** |
| 仅 `@PreDestroy`(Spring context 关闭才清理) | ⚠️ Spring context 未关闭时 channel 泄漏 | 低 | ❌ 单保险 |
| 仅 JVM shutdown hook(进程退出才清理) | ⚠️ Spring context 关闭但 JVM 不退出时 channel 泄漏 | 低 | ❌ 单保险 |
| `try-with-resources`(A2aTransport 实现 AutoCloseable)| ❌ 阻塞 stub 不支持 try-with-resources | 高 | ❌ API 不匹配 |

### 决策理由
- `ManagedChannel.shutdown()` 是异步非阻塞,`awaitTermination(5, SECONDS)` 等 in-flight RPC 完成(最大 5s 容忍)
- `InterruptedException` 兜底 + `shutdownNow()` 强杀 + `Thread.currentThread().interrupt()` 恢复中断标志
- JVM shutdown hook 是兜底:Spring context `@PreDestroy` 先执行,JVM shutdown hook 后执行(同一进程关停两次保险)
- 用户可手动调 `transport.close()` 主动释放

### 扳机条件
- in-flight RPC 超过 5s(典型 AI 工作负载允许 30s+ turn timeout)→ 调整 `awaitTermination(30, SECONDS)` 或加 `awaitTermination(Long.MAX_VALUE, NANOSECONDS)`

---

## 决策 5:protobuf idl 设计(5 RPC + 6 message)

### 候选方案
| 方案 | 兼容性 | 演进空间 | 决策 |
|---|---|---|---|
| **5 RPC + 6 message 最小集**(锚定)| ✅ A2A v1.0 spec 对齐 | ✅ 预留 skills / TaskEvent 扩展字段 | ✅ **选定** |
| 全功能集(加 authentication / metadata / 多 part 消息)| ❌ A2A v1.0 spec 未稳定 | ⚠️ 多余字段 | ❌ 违反 KISS |
| 直接用 Google A2A 官方 protobuf(.proto)| ✅ spec 对齐 | ✅ | ⚠️ 但 Google A2A protobuf 还在 draft,**不**引外部依赖(R-13)| ❌ |

### 决策理由
- 5 RPC 与 A2aTransport 5 方法一一对应(`GetCard` / `Submit` / `GetTask` / `Cancel` / `Subscribe` stream)
- 6 message 最小集覆盖 US1—US3 + 10 Edge Cases
- `Card.skills` 预留 `repeated string`(后续 #009d `RemoteAgentSchemaBuilder` 扩展 `repeated Skill` nested)
- `TaskEvent` 多字段(taskId / eventType / payloadJson)兼容 streaming + polling 两种实现

### 扳机条件
- A2A v1.0 spec 引入新 RPC(如 `ListAgents` / `SubscribeTaskList` 等)→ proto 加新 RPC + service 兼容
- `Card.skills` 需要结构化(`Skill.id` / `Skill.description` / `Skill.inputSchema`)→ 升级为 `message Skill { ... }`

---

## 总结

5 项决策全部锁定,Story #009a 实施期不引入新决策;任何决策变更须走 RFC + 更新本文件 + dsh §5.6.3.2。