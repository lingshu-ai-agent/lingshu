# Quickstart: Story #009a a2a-grpc-transport

**Story**: Story #009a(锚定 dsh §5.6.3.2 L3174-3320)
**Goal**: 7 个验证场景(AC-10 关联 + US1—US3 + EC-1—EC-5)
**Prerequisite**: Story #001—#009 已 merged

---

## 场景 1:AC-10 关联验证(L5 E2E — GrpcA2aEndToEndIT)

**目标**: 验证完整 gRPC 链路可端到端跑通(Story #009 服务端 `LocalAgentCardGenerator` + 本 Story 客户端 `GrpcA2aTransport`)

**步骤**:
```bash
# 1. 全量编译 + 测试
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
mvn -pl lingshu-a2a-client verify -Dtest=GrpcA2aEndToEndIT
```

**期望**:
- BUILD SUCCESS
- `GrpcA2aEndToEndIT.TC-E2E-1 fetchCard_realGrpc_returnsCard` PASS
- `GrpcA2aEndToEndIT.TC-E2E-2 submit_realGrpc_returnsToolResult` PASS
- 启动日志含 `[A2aTransport] resolved 1 provider(s) [contract v1.0.0]: ✓ grpc-1.0.0 v1.0.0 -> GrpcA2aTransportProvider [priority=10]`

---

## 场景 2:US1 主路径 — GrpcA2aTransportProvider.create(cfg) 成功

**目标**: 验证 `AgentConfig.defaults()` 时 GrpcA2aTransportProvider create 成功

**步骤**:
```bash
mvn -pl lingshu-a2a-client test -Dtest=GrpcA2aTransportProviderTest#TC-PROV-1_create_validGrpcTarget_returnsGrpcA2aTransport
```

**期望**:
- `provider.create(AgentConfig.defaults())` 返回 `GrpcA2aTransport` 实例
- 实例 `channel` 已建连 `localhost:50051`
- 实例 `cardCache` 已 cache 5min TTL

---

## 场景 3:US2 主路径 — AgentCardCache hit/miss/negative 三态

**目标**: 验证缓存 5 状态(命中 / miss / 过期 / 负缓存命中 / 负缓存过期)+ stats hitRatio

**步骤**:
```bash
mvn -pl lingshu-a2a-client test -Dtest=AgentCardCacheTest
```

**期望**:
- `TC-CACHE-1 put_thenGet_returnsCard` PASS(hit)
- `TC-CACHE-2 get_unknownKey_returnsNull` PASS(miss)
- `TC-CACHE-3 putNegative_thenGet_returnsNull` PASS(negative hit)
- `TC-CACHE-4 entryExpires_returnsNull` PASS(expiry miss)
- `TC-CACHE-5 negativeCacheExpires_fallsThrough` PASS(negative expiry)
- `TC-CACHE-6 stats_hitRatio_calculatesCorrectly` PASS(0.333)

---

## 场景 4:US3 主路径 — A2aTransportRouter 单 Provider + 多 Provider 同存

**目标**: 验证 Router 单 Provider 解析 + 多 Provider 同存启动日志

**步骤**:
```bash
mvn -pl lingshu-core test -Dtest=A2aTransportRouterTest
```

**期望**:
- `TC-RTR-1 singleProvider_resolvesCorrectly` PASS
- `TC-RTR-2 multipleProviders_resolvesByName` PASS
- `TC-RTR-3 sameNamePriority_largerPriorityWins` PASS(priority=20 胜出 priority=10)
- `TC-RTR-4 unknownName_throwsIllegalArgumentException` PASS(LINGS-S01)

---

## 场景 5:EC-1 — grpcTarget null 抛 LINGS-S07

**目标**: 验证空 grpcTarget 启动期 fail-fast

**步骤**:
```bash
mvn -pl lingshu-a2a-client test -Dtest=GrpcA2aTransportProviderTest#TC-PROV-2_create_nullGrpcTarget_throwsLingsS07
```

**期望**:
- `assertThatThrownBy(() -> provider.create(cfg))` 抛 `LingsA2aServerException`
- `errorCode == "LINGS-S07"`
- 启动期 fail-fast(Spring 上下文启动失败)

---

## 场景 6:R-13 dep-tree 自查(baseline + post-diff)

**目标**: 验证 grpc 依赖增量符合 R-13 mitigation (d)

**步骤**:
```bash
# 1. 实施前 baseline(已合 Story #009 后)
mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009a-pre.txt

# 2. 实施后(post #009a)
mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009a-post.txt

# 3. diff
diff /tmp/deps-009a-pre.txt /tmp/deps-009a-post.txt
```

**期望**:
- 增量行包含:
  - `io.grpc:grpc-stub:1.55.1`
  - `io.grpc:grpc-core:1.55.1`
  - `io.grpc:grpc-api:1.55.1`
  - `io.grpc:grpc-protobuf:1.55.1`
  - `com.google.protobuf:protobuf-java:3.22.3`
  - `io.netty:netty-handler:4.1.x`
  - `io.netty:netty-transport:4.1.x`
  - `com.google.guava:guava:31.1-jre`
- 增量总计 ~11 包

---

## 场景 7:R-13 binary size check(< 35MB baseline)

**目标**: 验证 binary size 不超 baseline 太多(grpc +5MB)

**步骤**:
```bash
mvn -pl lingshu-a2a-client package
du -h target/lingshu-a2a-client-*.jar
```

**期望**:
- `lingshu-a2a-client-*.jar` size < 60MB(< 35MB baseline + 25MB buffer for grpc + dependencies)
- 实际 size ~5—15MB(grpc + protobuf + netty 总体积)

---

## 总结

7 个验证场景覆盖:
- AC-10 关联(L5 E2E 端到端 grpc)
- US1—US3 主路径(Provider + Cache + Router)
- EC-1 边界(grpcTarget null 抛 LINGS-S07)
- R-13 dep-tree 自查 + binary size check

**全部 PASS 才能提 PR**。