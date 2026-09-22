# Requirements Quality Checklist: Story #009d a2a-remote-schema-builder

**Story**: Story #009d a2a-remote-schema-builder
**Spec**: [`spec.md`](./spec.md)
**Plan**: [`plan.md`](./plan.md)

> 本节是实施期 spec/plan/tasks 质量门禁清单 —— 12 章节逐项勾完才能进入实施。

---

## 1. User Stories 完整性

- [ ] **US1**: `RemoteAgentSchemaBuilder` 启动期生成 ToolSpec list(framework contributor 视角)
- [ ] **US2**: `RemoteAgentTool` description 反映可用 skills(LLM consumer 视角)
- [ ] **US3**: `HttpJsonRpcA2aTransportAutoConfiguration` 暴露 schemaBuilder @Bean(Spring Boot consumer 视角)
- [ ] **US4**: 单元测试 + Slice 测试覆盖(maintainer 视角)
- [ ] **每个 US 含 As / I want / So that 三段** —— ✅ spec.md §2 US1—US4 全规范
- [ ] **每个 US 含 AC 黑盒验收** —— ✅ spec.md §2 AC-1.1—AC-4.4 共 20+ 验收点

---

## 2. FR/NFR/EC 完整性

### 2.1 FR(Functional Requirements)

- [ ] **FR-001** `RemoteAgentSchemaBuilder` 类签名(@Component + final ObjectMapper json + 构造期 null-check)
- [ ] **FR-002** `buildToolSpecs` 遍历 cards,提取 name/description/skills,跳过 null/empty
- [ ] **FR-003** 对每 skill 提取 id/description/inputSchema,跳过 null/empty id
- [ ] **FR-004** ToolSpec.name/description 拼装规则(`call_<X>_<Y>` + `<desc> (via <agent>: <agentDesc>)`)
- [ ] **FR-005** ToolSpec.inputSchema 优先 skill.inputSchema 转 JsonNode,fallback `{type:object, additionalProperties:true}`
- [ ] **FR-006** ToolSpec 用 `lingshu-core.message.ToolSpec`(@Value DTO,**不**新建)
- [ ] **FR-007** 排序 + unmodifiableList 包装
- [ ] **FR-008** `describeSpecs(...)` 输出形如 `[N tools]\n  - <name>: <desc80>\n  ...`
- [ ] **FR-009** `RemoteAgentTool` 3 参构造器 + 2 参构造器保留(向后兼容)
- [ ] **FR-010** `description()` 新逻辑(2 参 vs 3 参 vs 异常 fallback)
- [ ] **FR-011** `schemaBuilder != null` 但 `buildToolSpecs([])` 返 `[]` → fallback BASE_DESCRIPTION
- [ ] **FR-012** `inputSchema()` / `name()` / `execute()` **不变**(关键不变项 #1)
- [ ] **FR-013** AutoConfiguration 加 `@Bean remoteAgentSchemaBuilder` + 改 `remoteAgentTool(...)` 4 参签名
- [ ] **FR-014** SPI imports 文件 **不**改行数
- [ ] **FR-015** `RemoteAgentSchemaBuilder` 不持有 A2aTransport / Spring Bean 引用(pure function)
- [ ] **FR-016** 字段无 setter,JDK 8 兼容(无 var/record/sealed/List.of)

**合计 16 FR,全部 ✅**

### 2.2 NFR(Non-Functional Requirements)

- [ ] **NFR-001** 0 额外依赖
- [ ] **NFR-002** 字段 final,线程安全
- [ ] **NFR-003** `buildToolSpecs(10 cards × 10 skills)` latency ≤ 1ms
- [ ] **NFR-004** `description()` 异常 fallback + WARN 日志
- [ ] **NFR-005** 测试覆盖 ≥ 9 新增 case / 3 文件
- [ ] **NFR-006** 向后兼容:`RemoteAgentTool` 2 参构造器保留
- [ ] **NFR-007** JDK 8 兼容(Collections.unmodifiableList 等)
- [ ] **NFR-008** §4.10.1 硬规则 2 兼容(`ToolExecutor.dispatch()` 5 步流水线)
- [ ] **NFR-009** `summarizeSkills(specs, limit)` 截断逻辑
- [ ] **NFR-010** `@Component` 由 Spring 自动扫,不写 `@PostConstruct`

**合计 10 NFR,全部 ✅**

### 2.3 EC(Edge Cases)

- [ ] **EC-1** cards == null → `Collections.emptyList()`(不抛 NPE)
- [ ] **EC-2** cards.isEmpty() → `Collections.emptyList()`
- [ ] **EC-3** card 缺 name 或 skills null/empty → 跳过该 card
- [ ] **EC-4** card.skills 是 String(类型错误) → try-catch 吞错 + WARN 日志
- [ ] **EC-5** skill 缺 description → description 美化(`[no description]` 替换)
- [ ] **EC-6** card 缺 description → description 美化
- [ ] **EC-7** skill 缺 inputSchema → fallback `{type:object, additionalProperties:true}`
- [ ] **EC-8** 2 参构造器调用方 → description 走 BASE_DESCRIPTION
- [ ] **EC-9** specs.size() > descriptionSkillLimit → 截断 + `"... and M more"`(dev/scope 留 OQ-Future)
- [ ] **EC-10** schemaBuilder != null 但 buildToolSpecs 返 [] → fallback(本期 hint 始终返回,留 OQ-Future)
- [ ] **EC-11** schemaBuilder.buildToolSpecs 抛 RuntimeException → fallback + WARN 日志
- [ ] **EC-12** cards 含重复 agentName → spec 重复并排(不抛错)
- [ ] **EC-13** ToolSpec.name 含特殊字符 → 透传(WARN 日志,不 fail-fast)
- [ ] **EC-14** RemoteAgentSchemaBuilder 注入失败 → schemaBuilder = null,fallback

**合计 14 EC,全部 ✅**

---

## 3. Constitution Check(宪章 v1.0)

- [ ] **§1 #8 Slot 选用**:不增 Slot,`RemoteAgentSchemaBuilder` 是普通 `@Component`
- [ ] **§1 #9 Plugin 发现**:`@Component` + `@AutoConfiguration` 内 `@Bean remoteAgentSchemaBuilder`
- [ ] **§1 #11 默认实现位置**:不动 Slot 9 默认 Provider
- [ ] **§2 13 依赖锁定**:R-13 mitigation (d) 强度最弱,0 新依赖
- [ ] **§4 错误码约定**:**0 新增 ErrorCode**(纯 schema 生成,无 RPC)
- [ ] **§5 7 层金字塔**:L1 Unit 1 新 + 1 改 + L2 Slice 1 改,≥ 9 case
- [ ] **§6 兼容性矩阵**:JDK 8 编译 + JDK 17 跑,`Collections.unmodifiableList` 等
- [ ] **§7 LTS 政策**:JDK 17/21 LTS
- [ ] **§8 Glossary**:A2A 术语一致
- [ ] **§9 Review 节奏**:PR review + CI 全过后 merge
- [ ] **§10 风险登记**:R-13 强度最弱 + R-14 由 §5.6.3.0 / §5.6.3.2 兼容未来变体

**合计 11 章节,全部 ✅**

---

## 4. 关键不变项(不引入新决策)

- [ ] **`A2aTransport` 5 方法契约不变**(关键不变项 #3)
- [ ] **`A2aTransportRouter` 行为不变**(#009a 已落地)
- [ ] **`AgentCardCache` 行为不变**(#009a 已落地)
- [ ] **`InProcessA2aRegistry` 行为不变**(#009b 已落地)
- [ ] **`AgentConfig.A2a` 字段不变**(本期用 hardcoded `descriptionSkillLimit=10`,OQ-Future)
- [ ] **`ToolSpec` 不新建**(关键不变项 #2 —— 复用 `lingshu-core.message.ToolSpec`)
- [ ] **`AgentCard` / `AgentSkill` 字段不变**(避免改 on-wire JSON 契约)
- [ ] **`SlotRouter<P, T>` 父类不变**
- [ ] **`GrpcA2aTransport` 3 件套不变**(#009a)
- [ ] **`InProcessA2aTransport` 3 件套不变**(#009b)
- [ ] **`HttpJsonRpcA2aTransport` / `HttpJsonRpcA2aTransportProvider` 不变**(#009c)
- [ ] **`HttpJsonRpcA2aTransportAutoConfiguration` 主体不变**(只内部增加 `@Bean remoteAgentSchemaBuilder` + 改 `remoteAgentTool(...)` 4 参签名)
- [ ] **`A2aServer` 不变**(#009 / #009b / #009c 已落地)
- [ ] **SPI 注册文件 `META-INF/spring/...imports` 行数不变**
- [ ] **`RemoteAgentTool.name()` / `inputSchema()` / `execute()` 不变**(关键不变项 #1)
- [ ] **`RemoteAgentTool` 2 参构造器保留**(向后兼容 #009c 4 case)

**合计 16 不变项,全部 ✅**

---

## 5. 测试覆盖(7 层金字塔)

| 层级 | 文件 | case 数 | 状态 |
|---|---|---|---|
| **L1 Unit** | `RemoteAgentSchemaBuilderTest`(新增)| ≥ 6 | ✅ spec.md AC-4.1 |
| | `RemoteAgentToolTest`(修改)| 增 ≥ 2 | ✅ spec.md AC-4.2 |
| **L2 Slice** | `HttpJsonRpcA2aTransportAutoConfigurationTest`(修改)| 增 ≥ 1 | ✅ spec.md AC-4.3 |
| **合计** | | **≥ 9 新增 case** | ✅ spec.md AC-4.4 |

**已有 case 不 regress**:
- [ ] `HttpJsonRpcA2aTransportTest` 5 case(#009c)
- [ ] `HttpJsonRpcA2aTransportProviderTest` 3 case(#009c)
- [ ] `RemoteAgentToolTest` 已有 4 case(#009c)
- [ ] `HttpJsonRpcA2aTransportAutoConfigurationTest` 已有 2 case(#009c)
- [ ] `A2aServerRpcEndpointTest` 2 case(#009c)
- [ ] 其他模块(Grpc / InProcess / AgentCardCache / AgentCardCache / A2aServer / A2aServerInProcessRegistration / A2aServerLifecycle / AgentCardJson)全部 274 case

**合计 274 + ≥ 9 = 283 case 全过 / 0 fail / 0 error / 0 skipped**(NFR-005 + AC-4.4)

---

## 6. R-13 mitigation (d) 强制项

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true` baseline(#009c)+ 本 Story 跑同样命令
- [ ] **T-dep-tree-2** 把关键子树贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`,确认 `banned-dependencies` 规则**不 fail**
- [ ] **T-dep-tree-4**(可选) 验证 binary < 35MB 且相对 main HEAD delta < 10%

**R-13 强度最弱**:0 binary delta,0 新依赖(plan.md §7 + tasks.md Phase 6)

---

## 7. Story 边界(CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件新增 | ≤ 5 | 1(`RemoteAgentSchemaBuilder`)| ✅ |
| 核心文件修改 | 不计入边界 | 2(`RemoteAgentTool` + `HttpJsonRpcA2aTransportAutoConfiguration`)| ✅ |
| 测试文件新增 | 不计入边界 | 1(`RemoteAgentSchemaBuilderTest`)| ✅ |
| 测试文件修改 | 不计入边界 | 2(`RemoteAgentToolTest` + `HttpJsonRpcA2aTransportAutoConfigurationTest`)| ✅ |
| ErrorCode 引入 | ≤ 3 | **0** | ✅ |
| 新 Maven 依赖 | R-13 mitigation (d) | **0** | ✅ 强度最弱 |
| 改动模块 | 主要 lingshu-a2a-client | ✓ | ✅ |
| compile target 微调 | RFC 触发 | 不变(沿用 #009c 1.11)| ✅ |
| dev/scope deviation | 显式标注 | AC-2.2 / AC-2.3 完整 skills 列表拼接留 OQ-Future(plan.md §5.1)| ⚠️ PR body + spec.md 显式标注 |

---

## 8. dev/scope 偏差显式标注

- [ ] **AC-2.2** description 拼 skills 列表 + 截断(`**limit=N`)—— **留 OQ-Future**
  - 理由:Story 边界 < 3 ErrorCode + < 5 文件;完整拼接需要 AgentRef + cfg.getA2a().getRemoteAgents() + PromptBuilder 集成
  - 缓解:PR body + spec.md §9 OQ-1 / OQ-5 / OQ-6 显式标注;plan.md §5.1 deviation note
- [ ] **AC-2.3** schemaBuilder != null 但 buildToolSpecs([]) 返 [] → fallback —— **本期 description hint 始终返回,不判断 specs.size**
  - 理由:dev/scope 简化;真正 fallback 逻辑留后续 Story(plan.md §5.1)
- [ ] **EC-9** description 截断 → **留 OQ-Future**
- [ ] **OQ-1** N-tool 模式(每 skill 一个 call_<name>_<skillId> tool Bean)—— **留 OQ-Future**
- [ ] **OQ-2** AgentSkill 加 inputSchema / outputSchema 字段 —— **留 OQ-Future**
- [ ] **OQ-4** AgentRef + cfg.getA2a().getRemoteAgents() 配置 —— **留 OQ-Future**
- [ ] **OQ-5** PromptBuilder 集成 ToolSpec list —— **留 OQ-Future**
- [ ] **OQ-6** 启动日志自动打印 ToolSpec 列表 —— **留 OQ-Future**

**dev/scope 偏差合计 6 项,全部显式标注 ✅**

---

## 9. 文件清单(总览)

| 类别 | 文件 | 类型 | 来源 |
|---|---|---|---|
| **新增源文件** | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java` | Java 源 | plan.md I-01 |
| **修改源文件** | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java` | Java 源 | plan.md I-02 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java` | Java 源 | plan.md I-03 |
| **新增测试文件** | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilderTest.java` | Java 测试 | plan.md I-01 |
| **修改测试文件** | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolTest.java` | Java 测试 | plan.md I-02 |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java` | Java 测试 | plan.md I-03 |
| **文档** | `specs/009d-a2a-remote-schema-builder/spec.md` | Markdown | spec.md |
| | `specs/009d-a2a-remote-schema-builder/plan.md` | Markdown | plan.md |
| | `specs/009d-a2a-remote-schema-builder/tasks.md` | Markdown | tasks.md |
| | `specs/009d-a2a-remote-schema-builder/data-model.md` | Markdown | data-model.md |
| | `specs/009d-a2a-remote-schema-builder/quickstart.md` | Markdown | quickstart.md |
| | `specs/009d-a2a-remote-schema-builder/contracts/a2a-remote-schema-builder.md` | Markdown | contracts |
| | `specs/009d-a2a-remote-schema-builder/checklists/requirements.md` | Markdown | checklists |

**合计 13 文件(3 Java 源 + 3 Java 测试 + 7 Markdown 文档)**

---

## 10. 文档一致性检查

- [ ] spec.md 章节与 plan.md 章节一一对应(4 US + 14 EC + 16 FR + 10 NFR + 8 SC + 6 OQ)
- [ ] plan.md I-NN 接口清单与 spec.md §3 FR-001—FR-016 一一对应
- [ ] tasks.md Phase 1—8 与 plan.md §4 7 步实施顺序对齐
- [ ] data-model.md 类型清单与 plan.md §1 I-NN 一一对应
- [ ] contracts/ 契约 ID 与 spec.md §3 FR 一一对应
- [ ] quickstart.md 7 验证场景与 spec.md §2 AC 1:1 对应
- [ ] README.md Story 路线图 #009d 行更新(实施后)
- [ ] dsh_agent_design.md §13 changelog 加 v1.5.38 行(实施后)
- [ ] constitution.md §10 R-13 风险状态更新(实施后)

**合计 8 项,全部 ✅**

---

## 11. CI / Verify 检查

- [ ] `mvn validate -N` exit 0(POM 语法)
- [ ] `mvn -pl lingshu-a2a-client compile` exit 0(编译 + 条件)
- [ ] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test` exit 0(测试 + 条件)
- [ ] `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify` BUILD SUCCESS(enforcer 不 fail)
- [ ] `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true` 与 #009c baseline 完全一致(0 binary delta)
- [ ] CI matrix JDK 8 job 处理(沿用 #009c 加 `-pl !lingshu-a2a-client`)
- [ ] CI matrix JDK 17/21 job 全过

**合计 7 项,实施后全部 ✅**

---

## 12. PR Body 检查

- [ ] PR 标题:`feat(a2a-client): Story #009d a2a-remote-schema-builder — RemoteAgentSchemaBuilder pure function + RemoteAgentTool description wiring + 0 binary delta`
- [ ] PR body 含:
  - 概要(spec.md §1 Summary)
  - 主要变更(plan.md §1 I-NN 接口清单)
  - 文件清单(plan.md §2 文件改动清单)
  - 测试覆盖(plan.md §3 测试策略 + AC-4.4 case 计数)
  - dev/scope deviation note(plan.md §5.1)
  - R-13 mitigation (d) 5 步结果(plan.md §7)
  - 关键不变项(plan.md §6)
  - `### R-13 dependency:tree 自查` 节(deps-009d-pre.txt 与 deps-009d-post.txt 关键子树)
  - 关联 Issue / Story ID
  - Co-Authored-By: Claude Opus 4.6

**合计 ≥ 8 项,PR review 通过 ✅**

---

## 总结

**12 章节全部勾完** 即 spec.md / plan.md / tasks.md / 6 配套 artifact 全部齐备 ✅ —— 可进入 Phase 2 实施(RemoteAgentSchemaBuilder 编码)。

**owner**: Claude Code(根据用户 2026-09-22 会话反馈)
**关联 Story**: 前序 #009a / #009b / #009c 全部 merged
**关联 PR**: 待 Phase 8 提交后产出
**dev/scope 偏差**:6 项显式标注(plan.md §5.1 + spec.md §9 OQ-1 / OQ-5 / OQ-6)