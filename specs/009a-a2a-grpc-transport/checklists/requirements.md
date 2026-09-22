# Requirements Checklist: Story #009a a2a-grpc-transport

**Story**: Story #009a(锚定 dsh §5.6.3.2 L3174-3320)
**Date**: 2026-09-22
**Usage**: 每个 PR 提交前必走本清单,逐项确认通过才能合入

---

## A. Spec/Plan/Tasks 完整性

- [ ] A1. `spec.md` 包含 WHY/WHO/WHAT(3 User Stories US1—US3 + 10 Edge Cases + FR-001—FR-015 + NFR-001—NFR-009 + 数据模型 + 接口契约 + Out-of-Scope + 边界检查)
- [ ] A2. `plan.md` 包含接口 / 文件改动 / 测试策略 / 7 步实施顺序 / 关键不变项 / 风险边界反模式
- [ ] A3. `tasks.md` 包含 Phase 1—7 共 27 个 T-NN(Setup + Foundational + Data + Cache + Router + Transport + Validate)
- [ ] A4. `research.md` 包含 5 项关键设计决策(grpc 选型 / Cache 自实现 / Router 独立文件 / channel lifecycle / protobuf idl 设计)
- [ ] A5. `data-model.md` 包含 8 项新增数据模型(GrpcA2aTransport / Provider / AutoConfiguration / A2aTransportRouter / AgentCardCache / AgentConfig.A2a 扩展 / LINGS-S07 / protobuf idl)
- [ ] A6. `contracts/a2a-grpc-transport.md` 包含 3 个 Contract(A1 gRPC Transport + A2 Router + A3 Cache)
- [ ] A7. `checklists/requirements.md`(本文件)21 项清单全过
- [ ] A8. `quickstart.md` 包含 7 个验证场景

---

## B. 实施完整性

- [ ] B1. 5 个 Java 源文件全部创建 + 编译过
  - [ ] `lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`
  - [ ] `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransport.java`
  - [ ] `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportProvider.java`
  - [ ] `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportAutoConfiguration.java`
  - [ ] `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/AgentCardCache.java`
- [ ] B2. 1 个 Java 源文件修改:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(A2a 嵌套类扩字段)
- [ ] B3. 2 个配置文件新增
  - [ ] `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - [ ] `lingshu-a2a-client/src/main/proto/a2a.proto`
- [ ] B4. 1 个配置文件修改:`lingshu-a2a-client/pom.xml`(加 grpc 依赖 + protobuf-maven-plugin)
- [ ] B5. 5 个测试文件全部创建 + 通过
  - [ ] `lingshu-core/src/test/java/ai/lingshu/core/impl/router/A2aTransportRouterTest.java`(4 case)
  - [ ] `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/AgentCardCacheTest.java`(6 case)
  - [ ] `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportProviderTest.java`(4 case)
  - [ ] `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportTest.java`(5 case)
  - [ ] `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aEndToEndIT.java`(2 case)

---

## C. 错误码 / 测试 / R-13

- [ ] C1. 1 个新 ErrorCode 引入:`LINGS-S07 A2A_GRPC_INIT_FAILED`(≤ 3 边界)
- [ ] C2. 21 测试 case 全过
  - [ ] 14 L1 Unit(AgentCardCache 6 + GrpcProvider 4 + Router 4)
  - [ ] 5 L2 Slice(GrpcA2aTransport 5,mock grpc)
  - [ ] 2 L5 E2E(GrpcA2aEndToEndIT,InProcessServer)
- [ ] C3. 全量回归:`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test`(234 + 21 = 255 case 全过)
- [ ] C4. R-13 dep-tree 自查:`mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true` baseline + post-diff
- [ ] C5. R-13 binary size < 35MB baseline(grpc +5MB 后 < 60MB)
- [ ] C6. PR body 末附 `### R-13 dependency:tree 自查` 节,贴关键子树(io.grpc / com.google.protobuf / io.netty / com.google.guava)

---

## D. JDK 8 / Spring Boot / Lombok 兼容

- [ ] D1. 不用 `var`(JDK 10+)
- [ ] D2. 不用 `record`(JDK 14+)
- [ ] D3. 不用 `sealed` / `permits`(JDK 17+)
- [ ] D4. 不用 `List.of(...)`(JDK 9+),用 `Collections.emptyList()` / `Arrays.asList(...)`
- [ ] D5. 不用 text blocks `"""..."""`(JDK 15+)
- [ ] D6. 配置类 `@Value` Lombok(不可变风格,无 setter)
- [ ] D7. `ManagedChannelBuilder.forTarget(...)` / `A2aServiceGrpc.newBlockingStub(...)` / `protobuf-java` 自身兼容 JDK 8
- [ ] D8. protobuf-generated code 用 `com.google.protobuf.GeneratedMessage`(非 `GeneratedMessageV3`,JDK 8 兼容)|

---

## E. §4.10.1 硬规则(CLAUDE.md §11 #7-#9)

- [ ] E1. **#7 ReAct Loop 自实现**:本 Story 不涉及 ReAct,**不**用 Spring AI `ChatClient.tools().call()`(N/A)
- [ ] E2. **#8 Spring AI 只用两件事**:本 Story 不涉及 LLM 调用 / `enable.merge(slot)` Schema 生成,**不**集成 Spring AI(N/A)
- [ ] E3. **#9 Provider 显式映射**:A2aTransportRouter 用 `byName.get(name)` 按字符串 key 查 Map,**不**靠 Spring 容器扫 Bean 类型区分(满足)

---

## F. 关键不变项(锚定 dsh §5.6.3.2)

- [ ] F1. `A2aTransport` interface 5 方法契约不变(`fetchCard` / `submit` / `get` / `cancel` / `subscribe`)
- [ ] F2. `Providers.A2aTransportProvider extends SlotProvider<A2aTransport>` typed Provider 不变
- [ ] F3. `SlotRouter<P, T>` 父类行为不变(byName map + priority 决胜 + 启动日志样板)
- [ ] F4. `Routers.java` 6 Router 不动(本 Story A2aTransportRouter **不**回填)
- [ ] F5. Story #009 已落地的 `LocalAgentCardGenerator` + `A2aServer` 不动
- [ ] F6. `AgentCard` 数据类型不动(Story #009 已落地)
- [ ] F7. §5.4 plugin Bean 名约定(`@Bean(name = "<slot>Provider_<name>")`)遵守
- [ ] F8. §5.5 多 Provider 模式样板遵守(`apply(agents.a2aTransport)` 按 name 切换)
- [ ] F9. §5.7 SPI 选型决策(Spring Boot Auto-Config,不选 Java SPI/OSGi/ClassLoader 隔离)遵守

---

## G. 文档同步(CLAUDE.md §11 #1 + dsh §13 changelog)

- [ ] G1. README.md 加「A2A Client (gRPC) 示例」一节(`agent.a2aTransport: grpc-1.0.0` 切换 + 启动日志样例 + yml 配置示例)
- [ ] G2. constitution.md §10 R-13 行加「**Story #009a 部分缓解(+2 Maven 依赖 grpc-stub + protobuf-java +5MB 二进制,R-13 mitigation (d) 镜像已执行)**」
- [ ] G3. dsh §13 changelog 加 v1.5.37 行记录 Story #009a 完成 + §5.6.4 SPI 槽位总表 Slot 9 行增加「GrpcA2aTransportProvider」状态行(实施者 PR review 通过后做)
- [ ] G4. PR body 包含 spec.md + plan.md + tasks.md 路径 + 21 测试 case 验证截图
- [ ] G5. PR body 包含 `### R-13 dependency:tree 自查` 节(dep-tree diff + binary size)

---

## H. Git Workflow(CLAUDE.md §9)

- [ ] H1. Commit message 符合 `feat(a2a): Story #009a a2a-grpc-transport — <一句话>`
- [ ] H2. PR title 符合 `feat(agent): Story #009a a2a-grpc-transport — <一句话>`
- [ ] H3. PR base = `main`,head = `story-009a-a2a-grpc-transport`
- [ ] H4. **不**直接 commit 到 main
- [ ] H5. **不**跨 Story 改 constitution(只动 §10 R-13 mitigation 注释,不修改 §2 13 项锁定)
- [ ] H6. **不**push SOP/prompt 速查/SKILL 到 lingshu 仓

---

## I. Story 边界检查(CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 通过 |
|---|---|---|---|
| 核心文件改动 | ≤ 5 | 5 新增 + 1 修改(0.5 增量)| ✅ |
| ErrorCode 引入 | ≤ 3 | 1(LINGS-S07)| ✅ |
| 新 Maven 依赖 | R-13 mitigation (d) | 2(grpc-stub + protobuf-java)+ 1 plugin | ⚠️ R-13 镜像 |
| 改动模块 | 主要 lingshu-a2a-client + lingshu-core(Router) | ✅ |

---

## J. 边界 / 反模式 / 警告

- [ ] J1. **不**做 HttpJsonRpcA2aTransport(留 #009c)
- [ ] J2. **不**做 RemoteAgentTool(留 #009c)
- [ ] J3. **不**做 RemoteAgentSchemaBuilder(留 #009d)
- [ ] J4. **不**做 InProcessA2aTransport(留 #009b)
- [ ] J5. **不**做 mTLS / TLS gRPC channel(本 Story `usePlaintext()`,TLS 部署层)
- [ ] J6. **不**做 OAuth / Bearer token(留 v1.5+)
- [ ] J7. **不**做 ConnectionBackoff / RetryPolicy / CircuitBreaker(留 #011)
- [ ] J8. **不**集成 OTel / Micrometer(留 #010)
- [ ] J9. **不**做 Push Notification webhook(留 v1.5+)
- [ ] J10. **不**绕过 Spring Boot SPI(所有 Bean 走 `@Component` / `@AutoConfiguration` + `@Bean(name=...)` + `META-INF/spring/...imports`)|

---

## K. AC-10 关联验证(L5 E2E)

- [ ] K1. 启动真实 grpc server(`io.grpc.inprocess.InProcessServerBuilder`)
- [ ] K2. 通过 Spring 上下文装配 A2aTransport + A2aTransportRouter + GrpcA2aTransportProvider
- [ ] K3. `transport.fetchCard("alice")` 返回 card
- [ ] K4. `transport.submit("alice", "skill", "{}")` 返回 ToolResult.success
- [ ] K5. `transport.close()` 释放 channel 资源(`channel.isShutdown()` → true)

---

## L. 文档一致性(CLAUDE.md v1.3.30 / SOP v1.18 / SKILL v1.0.21 / prompts v1.0.16)

- [ ] L1. 本 Story 不修改 CLAUDE.md(只在本文件 + README + constitution 加增量)
- [ ] L2. 本 Story 不修改 SOP(本地流程制品)
- [ ] L3. 本 Story 不修改 SKILL(本地流程制品)
- [ ] L4. 本 Story 不修改 prompts(本地流程制品)
- [ ] L5. dsh §13 changelog 加 v1.5.37 行(PR review 通过后做)

---

## 总结

- **21 项清单**分 12 节(A—L)
- **关键门禁**:C 测试 / C4-C6 R-13 / F 关键不变项 / I 边界检查 / K AC-10 关联
- **失败处理**:任何一项未通过 → PR 不合,补齐开 / 重跑测试 / 走 RFC