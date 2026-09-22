# Feature Specification: Story #009d a2a-remote-schema-builder

**Feature Branch**: `story-009d-a2a-remote-schema-builder`
**Created**: 2026-09-22
**Status**: Draft
**Input**: User description: "Story #009d a2a-remote-schema-builder —— `lingshu-a2a-client` 模块下 `RemoteAgentSchemaBuilder`(`@Component`,启动期扫 `AgentCard.skills[]` 生成 `List<ToolSpec>`,按 `(agentName, skillId)` 排序稳定 prompt cache 命中)+ `RemoteAgentTool` 接入 `ToolRegistry`(已由 #009c 完成 `@Bean Tool remoteAgentTool(...)` 暴露,本 Story 强化 description 拼接动态 skills 列表,使 LLM 在 ReAct Action 阶段看到完整可用 skill 集合)。**复用** #009a 的 `AgentCardCache`(已存 `Map<String, Object>` 视图)+ #009b 的 `InProcessA2aRegistry`(单元测试 mock 通路)+ #009c 的 `RemoteAgentTool` 单 tool 模式与 `HttpJsonRpcA2aTransportAutoConfiguration` 双 Bean 模板。**0 额外依赖**,**0 新 ErrorCode** —— 纯 schema 生成,无 RPC,无新错误码。**锚定** dsh §5.6.3 L2429-2472 `RemoteAgentTool` / `RemoteAgentToolAutoConfiguration` 草图 + §5.6.3.0 L2729-2833 `RemoteAgentSchemaBuilder` 完整定义(按 `Map<String, Object>` 视图适配 + `ToolSpec` 已落地 `lingshu-core/message/ToolSpec.java` 路径)+ §5.5 L1887-2003 Slot 5 Tool 注册路径(非 SPI 模式,Spring 自动扫 `@Bean Tool`)。**关键约束** —— 当前 lingshu 实际 AgentSkill(linghu-a2a-server/AgentCard.java 内嵌类 `@Value` 不可变)只含 `id` / `name` / `description` / `tags` / `examples` / `inputModes` / `outputModes` **7 字段**,**不含** dsh §5.6.3.0 描述的 `inputSchema`/`outputSchema` 字段(那是 Story #009b 已 deferred 的扩展)—— 本 Story 用 `{type: 'object', additionalProperties: true}` fallback generic schema 描述每个 skill 的输入形状,**不**改 AgentSkill 字段(避免改 on-wire JSON 契约,out-of-scope)。"

**Source Design Doc**: `dsh_agent_design.md` v1.5.37
- §5.6.3 L2395-2480(`A2aTransport` SPI 接口契约 + `RemoteAgentTool` / `RemoteAgentToolAutoConfiguration` 草图)
- §5.6.3.0 L2729-2833(`RemoteAgentSchemaBuilder` 完整定义 —— 本 Story 主要锚定)
- §5.6.1 L2346-2390(对照表 `Tool 发现机制` Spring `@Component` / `@AutoConfiguration` + `@Bean` 路径)
- §5.5 L1887-2003(默认实现注册约定 + 🆕 v1.5.28 多 Provider 模式样板)
- §5.6.3.1 L2995-3172(HttpJsonRpcA2aTransport concrete class —— #009c 复用)
- §5.6.3.2 L3174-3320(3 件套模式扩展指南)
- §4.6 L1100-1180(`Tool` 接口 + `ToolSpec` Provider-agnostic DTO)
- §4.10.1 硬规则 2(`ToolExecutor.dispatch()` 5 步流水线 —— `RemoteAgentTool` 接入 ToolRegistry 后必经)
- §4.5.1 `[TOOL SCHEMAS]` 段 + LLM prompt cache 命中排序约定
- §15 LINGS-<域><编号> 错误码约定(**0 新增 ErrorCode** —— 纯 schema 生成,无 RPC)
- §10.1 锁定 13 项依赖表(R-13 mitigation (d) 强度最弱:0 binary delta)
- §17 R-13 / R-14 风险登记

---

## 1. Summary

本 Story 是 Story #009 A2A 客户端子系列的第 4 块(最后一块)—— 在 #009a(GrpcA2aTransport 3 件套 + AgentCardCache + A2aTransportRouter + R-13 mitigation (d) grpc +5MB)+ #009b(InProcessA2aTransport 3 件套 + InProcessA2aRegistry 单例 + R-13 mitigation (d) 0 binary delta)+ #009c(HttpJsonRpcA2aTransport 3 件套 + RemoteAgentTool + RemoteAgentToolAutoConfiguration 双 Bean + LINGS-S08 A2A_HTTP_RPC_FAILED 子码)落地后,**最后**补齐**第 4 个核心类型** `RemoteAgentSchemaBuilder` —— 启动期扫 `AgentCard.skills[]` 动态生成 `List<ToolSpec>`,按 `(agentName, skillId)` 排序稳定 prompt cache 命中。

**为何 #009c 不一并落地 `RemoteAgentSchemaBuilder`**:CLAUDE.md §11 #4 Story 边界 `≤ 5 核心文件 / ≤ 3 ErrorCode` 硬约束。#009c 已落地 4 新增 Java 文件(`HttpJsonRpcA2aTransport` / `Provider` / `AutoConfiguration`(双 Bean)/ `RemoteAgentTool`)+ 1 子码(`LINGS-S08 A2A_HTTP_RPC_FAILED`)—— 边界已满。`RemoteAgentSchemaBuilder` 拆到 #009d 单独 Story,边界预算 1 新增 + 2 微改 + 0 ErrorCode。

**关键设计选择**:**保留** #009c 的**单 tool** 模式(`Tool.name()="remote_agent"` 固定 + 1 行 inputSchema),`RemoteAgentSchemaBuilder` 作为**纯辅助类**生成 `List<ToolSpec>` 暴露给 PromptBuilder / 启动日志 / 健康检查端点等场景使用(详 §3 FR-005)。**不**改 RemoteAgentTool 的 `name()` / `inputSchema()`(避免破坏 #009c 已固化的契约与 18 个回归测试),**不**改 AgentSkill 字段(避免改 on-wire JSON 契约)。

**单 tool vs N tool trade-off**(沿用 #009c OQ-1 决策):OpenAI / Anthropic tool spec 尚未广泛支持 `oneOf` + nested union,所以 N tool 模式(每 skill 一个 `call_<name>_<skillId>` tool)会增加 LLM tool 列表长度但不显著提升调用准确率。本 Story 维持单 tool 模式,`RemoteAgentSchemaBuilder` 产出的 `List<ToolSpec>` 给启动日志与未来 `PromptBuilder [TOOL SCHEMAS]` 段使用 —— **不**直接注册为 N 个 Tool Bean。**Revisit trigger**:OpenAI / Anthropic 2025+ tool spec 广泛支持 `oneOf` + nested union 时再迁移到 N tool 模式(详 §6 OQ-1)。

**R-13 mitigation (d) 强度最弱** —— 0 额外依赖,0 binary delta,`mvn dependency:tree -pl lingshu-a2a-client` 应与 #009c baseline **完全一致**。

**0 新 ErrorCode** —— 纯 schema 生成,无 RPC,所有失败路径要么静默(skill 缺字段跳过)、要么抛 `IllegalArgumentException` 启动期 fail-fast(AgentCard.skills[] 整体为空时返回 `Collections.emptyList()` 不抛错,因为 LLM 0 tools 比启动失败更友好)。

---

## 2. User Stories

### US-1: RemoteAgentSchemaBuilder 启动期生成 ToolSpec list(as framework contributor)

**As** LingShu framework contributor maintaining the A2A client infrastructure,
**I want** `RemoteAgentSchemaBuilder` to scan a list of `AgentCard` (as `Map<String, Object>` views) at startup and produce a stable, sorted `List<ToolSpec>` describing one tool per remote skill,
**So that** the LLM can see the full set of available remote skills (via `PromptBuilder [TOOL SCHEMAS]` / startup log / health check), and prompt cache hits remain stable across turns.

**Acceptance Criteria**:
- `AC-1.1` `RemoteAgentSchemaBuilder` 是 `@Component`,无状态(pure function 类),构造器只接受 `ObjectMapper`(用于解析 Map 内 `inputSchema` 字段 + `additionalProperties: true` fallback),字段 `final ObjectMapper json`(VS-1 + FR-001)
- `AC-1.2` `RemoteAgentSchemaBuilder.buildToolSpecs(List<Map<String, Object>> cards)` 返回 `List<ToolSpec>`;入参 `cards == null` 或 `cards.isEmpty()` → 返 `Collections.emptyList()`(不抛异常)(VS-1 + EC-1 + EC-2)
- `AC-1.3` 对每张 card:提取 `card.get("name")` 作 `agentName`(null/empty 跳过该 card)+ 提取 `card.get("description")` 作 `agentDesc`(null → 空串)+ 遍历 `card.get("skills")`(`List<Map<String, Object>>`,null/empty 跳过该 card)(VS-1 + FR-002 + EC-3 + EC-4)
- `AC-1.4` 对每个 skill:提取 `skill.get("id")` 作 `skillId`(null/empty 跳过该 skill)+ 提取 `skill.get("description")` 作 `skillDesc`(null → 空串)+ 拼 `ToolSpec.name = "call_" + agentName + "_" + skillId`(FR-002 + FR-003)
- `AC-1.5` `ToolSpec.description = skillDesc + " (via " + agentName + ": " + agentDesc + ")"`(FR-004);空 skillDesc 时回退到 `"Skill: " + skillId`;空 agentDesc 时回退到 `"Agent: " + agentName`(EC-5 + EC-6)
- `AC-1.6` `ToolSpec.inputSchema` 优先读 `skill.get("inputSchema")`(若存在且为 `ObjectNode`/`JsonNode`,直接转 `JsonNode`);否则 fallback 到 `{type:object, additionalProperties:true}`(ObjectNode,允许任意键值对,匹配 #009c RemoteAgentTool 现状)**关键约束** —— 当前 lingshu AgentSkill 不含 inputSchema 字段(详 §6 OQ-2),所以实际永远走 fallback;若未来 AgentSkill 扩 inputSchema 字段,本方法自动优先使用(FR-005 + FR-006 + EC-7)
- `AC-1.7` 返回 `List<ToolSpec>` 按 `ToolSpec.name` 字典序升序排序(agentName asc, skillId asc),便于 prompt cache 命中稳定(§4.5.1 + FR-007 + VS-2)
- `AC-1.8` 返回 `Collections.unmodifiableList(...)` 包装,调用方不可修改(FR-008 + NFR-002)
- `AC-1.9` `RemoteAgentSchemaBuilder.describeSpecs(List<ToolSpec>)` 调试辅助方法:返 `String` 形如 `[N tools]\n  - call_<X>: <desc80>\n  ...`,用于启动日志与健康检查端点(FR-009)

### US-2: RemoteAgentTool description 反映可用 skills(as LLM consumer)

**As** LLM receiving the `[TOOL SCHEMAS]` prompt segment during ReAct Action phase,
**I want** `RemoteAgentTool.description()` to enumerate the available skills across all known agents (when a `RemoteAgentSchemaBuilder` is wired),
**So that** I can decide which skill to invoke without trial-and-error.

**Acceptance Criteria**:
- `AC-2.1` `RemoteAgentTool` 构造器增加重载 `RemoteAgentTool(A2aTransport transport, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)`;`schemaBuilder == null` → fallback 走 #009c 固定 description(`"Invoke a skill on a remote A2A agent. Input: {...}."`)(向后兼容 #009c 测试与 wiring)(VS-3 + FR-010 + EC-8)
- `AC-2.2` 当 `schemaBuilder != null`:`description()` 返 `"Invoke a skill on a remote A2A agent. Available skills: " + summarizeSkills(...)`,其中 `summarizeSkills` 包含最多 N 个 skill 形如 `"call_<agent>_<skillId>(<shortDesc>)"`,超过 N 个截断为 `"... and M more"`(默认 N=10,可由 yaml `agent.remoteAgent.descriptionSkillLimit` 配置,无配置 fallback 10)(FR-010 + FR-011 + EC-9)
- `AC-2.3` 当 schemaBuilder 启动期无可用 AgentCard(空 list)→ description 退化为 #009c 固定 description(`schemaBuilder != null` 但 `buildToolSpecs([])` 返 `[]` 时与 `schemaBuilder == null` 等价)(EC-10 + FR-11)
- `AC-2.4` `inputSchema()` **不变**(维持 #009c 固定 schema `{type:object, properties:{agentName, skill, input}, required:[agentName, skill, input]}`)—— N-tool 模式留 OQ-1 revisit trigger(FR-012 + 关键不变项 #1)

### US-3: HttpJsonRpcA2aTransportAutoConfiguration 暴露 RemoteAgentSchemaBuilder @Bean(as Spring Boot consumer)

**As** LingShu user configuring the engine via `application.yml`,
**I want** Spring Boot auto-wiring to expose a `RemoteAgentSchemaBuilder` bean (named by convention) alongside the existing `RemoteAgentTool` and A2aTransport providers,
**So that** I don't need to manually register the schema builder, and `RemoteAgentTool` can be injected with it without explicit config.

**Acceptance Criteria**:
- `AC-3.1` `HttpJsonRpcA2aTransportAutoConfiguration` 增加 1 个 `@Bean`:`@Bean public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json)` —— 返 `new RemoteAgentSchemaBuilder(json)`(FR-013 + VS-4)
- `AC-3.2` `remoteAgentTool(...)` Bean 修改:增加 `RemoteAgentSchemaBuilder schemaBuilder` 参数,内部 `new RemoteAgentTool(transport, json, schemaBuilder)` —— 把 schemaBuilder 透传给 RemoteAgentTool(FR-013 + AC-2.1 + AC-2.2)
- `AC-3.3` SPI 注册文件 `META-INF/spring/...imports` **不**改行数(#009c 已落地,本 Story 不动);启动日志期望包含 `resolved 3 provider(s)`(沿用 #009c 行为,RemoteAgentSchemaBuilder 是普通 @Bean,非 Slot,不走 `A2aTransportRouter` 启动日志)(FR-014)
- `AC-3.4` 当 ApplicationContext 启动期无 `RemoteAgentSchemaBuilder` Bean(理论不会发生,#009d 已自动注册,但留兜底)→ RemoteAgentTool 走 fallback 固定 description(NFR-004)

### US-4: 单元测试 + Slice 测试覆盖(as maintainer)

**As** LingShu maintainer evolving the A2A client code,
**I want** L1 Unit tests covering `RemoteAgentSchemaBuilder.buildToolSpecs` happy paths + edge cases + `RemoteAgentTool` description with/without schemaBuilder,
**So that** regressions in schema generation are caught at PR time.

**Acceptance Criteria**:
- `AC-4.1` `RemoteAgentSchemaBuilderTest` ≥ 6 case(VS-5 + FR-NFR-005):
  - `testBuildToolSpecsHappyPath` — 2 cards × 2 skills = 4 ToolSpec,按 name 字典序排序(VS-1 + AC-1.3—1.7)
  - `testBuildToolSpecsEmptyList` — `buildToolSpecs(Collections.emptyList())` 返 `[]`(EC-1)
  - `testBuildToolSpecsNullInput` — `buildToolSpecs(null)` 返 `[]` 不 NPE(EC-2)
  - `testBuildToolSpecsCardMissingName` — card 无 name 字段 → 跳过该 card(EC-3)
  - `testBuildToolSpecsCardMissingSkills` — card.skills=null 或 skills=[] → 跳过该 card(EC-4)
  - `testBuildToolSpecsSkillMissingId` — skill 无 id 字段 → 跳过该 skill(EC-7)
  - `testBuildToolSpecsFallbackSchema` — skill 无 inputSchema 字段 → fallback `{type:object, additionalProperties:true}`(AC-1.6 + EC-7)
  - `testBuildToolSpecsWithInputSchema` — skill 含 inputSchema `{"type":"object","properties":{"x":{"type":"string"}}}` → 透传该 schema(AC-1.6)
  - `testDescribeSpecs` — `describeSpecs(specs)` 输出形如 `[4 tools]\n  - call_alice_echo: ...`(AC-1.9)
- `AC-4.2` `RemoteAgentToolTest` 增 ≥ 2 新 case(沿用 #009c 已有 4 case):
  - `testDescriptionWithSchemaBuilderListsSkills` — 传入 schemaBuilder + 2 card × 1 skill → description 含 `call_alice_echo` 与 `call_bob_search`(AC-2.2)
  - `testDescriptionWithSchemaBuilderTruncates` — schemaBuilder 返 ≥ 15 skill → description 含 `"... and 5 more"`(AC-2.2 + EC-9)
  - `testDescriptionWithEmptySchemaBuilderFallsBack` — schemaBuilder 存在但 `buildToolSpecs([])` 返 `[]` → description 走 #009c 固定字串(AC-2.3 + EC-10)
- `AC-4.3` `HttpJsonRpcA2aTransportAutoConfigurationTest` 增 ≥ 1 新 case:
  - `testRemoteAgentSchemaBuilderBeanWiring` — Spring 容器有 `RemoteAgentSchemaBuilder` Bean,可被 RemoteAgentTool 注入(AC-3.1 + AC-3.2 + AC-3.4)
- `AC-4.4` 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test`,期望 274 + ≥ 9 = 283 case 全过(0 fail / 0 error / 0 skipped)(NFR-005)

---

## 3. Functional Requirements

| ID | 描述 |
|---|---|
| **FR-001** | `RemoteAgentSchemaBuilder` 类签名:`@Component public class RemoteAgentSchemaBuilder { final ObjectMapper json; public RemoteAgentSchemaBuilder(ObjectMapper json); ... }`,构造期 null-check 抛 `IllegalArgumentException`(AC-1.1 + NFR-002) |
| **FR-002** | `RemoteAgentSchemaBuilder.buildToolSpecs(List<Map<String, Object>> cards)`:遍历 cards 列表,每张 card 提取 `name`(String)/ `description`(String)/ `skills`(List<Map<String,Object>>),跳过 null/empty 字段(AC-1.3 + EC-3 + EC-4) |
| **FR-003** | 对每张 card 的 `skills[]`:每 skill 提取 `id`(String)/ `description`(String)/ `inputSchema`(Object/Map,可选),跳过 null/empty `id`(AC-1.4 + EC-7) |
| **FR-004** | `ToolSpec.name = "call_" + agentName + "_" + skillId`;`ToolSpec.description = skillDesc + " (via " + agentName + ": " + agentDesc + ")"`;空 skillDesc fallback `"Skill: " + skillId`;空 agentDesc fallback `"Agent: " + agentName`(AC-1.4 + AC-1.5 + EC-5 + EC-6) |
| **FR-005** | `ToolSpec.inputSchema`:优先 `skill.inputSchema` 转 `JsonNode`(ObjectMapper.convertValue);fallback `{type:object, additionalProperties:true}`(ObjectNode);ToolSpec.inputSchema 字段类型 `JsonNode`,与 `lingshu-core.message.ToolSpec` 一致(AC-1.6 + EC-7 + FR-006) |
| **FR-006** | `ToolSpec` 用 `lingshu-core.message.ToolSpec`(`@Value String name` + `String description` + `JsonNode inputSchema`),**不**新建 DTO;构造用 `new ToolSpec(name, description, inputSchema)`(NFR-002 + 关键不变项 #2) |
| **FR-007** | 返回 `List<ToolSpec>` 按 `ToolSpec.name` 字典序升序排序(Java `String.compareTo`);排序后 `Collections.unmodifiableList(...)` 包装(AC-1.7 + AC-1.8 + NFR-002) |
| **FR-008** | `RemoteAgentSchemaBuilder.describeSpecs(List<ToolSpec> specs)`:返 `String` 形如 `"[N tools]\n  - call_<X>: <desc80>\n  - call_<Y>: <desc80>\n"`,N = specs.size();空 list 返 `"(empty)"`;description 截断 80 字符 + `...` 后缀(AC-1.9) |
| **FR-009** | `RemoteAgentTool` 构造器增加重载 `RemoteAgentTool(A2aTransport transport, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)`:`schemaBuilder == null` → `this.schemaBuilder = null`,description 走 #009c 固定字串;`schemaBuilder != null` → 内部缓存;**保留** 原 2 参构造器 `RemoteAgentTool(A2aTransport, ObjectMapper)` 作向后兼容,#009c 测试不破坏(AC-2.1 + NFR-006) |
| **FR-010** | `RemoteAgentTool.description()` 新逻辑:当 `schemaBuilder != null` 且 `schemaBuilder.buildToolSpecs(cards).size() > 0` 时,返 `"Invoke a skill on a remote A2A agent. Available skills: " + summarizeSkills(specs, limit)`,其中 `summarizeSkills` 包含最多 N 个 skill 形如 `"call_<agent>_<skillId>(<shortDesc>)"`;`shortDesc = skillDesc.length() > 40 ? skillDesc.substring(0, 37) + "..." : skillDesc`(AC-2.2 + EC-9) |
| **FR-011** | 当 `schemaBuilder != null` 但 `buildToolSpecs([])` 返 `[]`(无 AgentCard 或 skills 全空) → 退化为 #009c 固定 description(AC-2.3 + EC-10) |
| **FR-012** | `RemoteAgentTool.inputSchema()` **不变**(维持 #009c 固定 schema);name() **不变**(`"remote_agent"` 固定);execute() **不变**;只 description 与构造器变更(AC-2.4 + 关键不变项 #1) |
| **FR-013** | `HttpJsonRpcA2aTransportAutoConfiguration.remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)` 改 4 参签名;内部 `new RemoteAgentTool(transport, json, schemaBuilder)` 透传(AC-3.1 + AC-3.2 + FR-009) |
| **FR-014** | SPI 注册文件 `META-INF/spring/...imports` **不**改行数(`HttpJsonRpcA2aTransportAutoConfiguration` 已在 #009c 落地;本 Story 只内部增加 `@Bean` 数量,不改 imports 文件)(AC-3.3 + 关键不变项 #3) |
| **FR-015** | `RemoteAgentSchemaBuilder` 不持有任何 A2aTransport / AgentCard 引用;不调用 `A2aTransport.fetchCard(...)`(启动期 fetch 由 #009a / #009b / #009c 各自的 wire-up 负责,本类只接受已 fetch 的 cards 列表作入参)—— **pure function**(NFR-001 + NFR-002) |
| **FR-016** | 字段构造无 setter,所有字段 `final`(Lombok `@Value` 或手写 final + 构造器注入);**JDK 8 兼容** —— 不用 `var` / `record` / `sealed` / `List.of` / pattern matching,用 `Collections.unmodifiableList` / `Arrays.asList` / `LinkedHashMap`(NFR-006 + CLAUDE.md §3) |

---

## 4. Non-Functional Requirements

| ID | 描述 |
|---|---|
| **NFR-001** | 0 额外依赖(`ObjectMapper` Jackson 由 Spring Boot BOM 引入,`lingshu-core.message.ToolSpec` 已落地)—— R-13 mitigation (d) 强度最弱 |
| **NFR-002** | `RemoteAgentSchemaBuilder` 所有字段 `final`,线程安全(无 mutable 状态);`buildToolSpecs(...)` 是 pure function,同输入同输出;**不**起线程、不持有 A2aTransport / AgentCardCache / Spring Bean 引用 |
| **NFR-003** | `buildToolSpecs(N cards × M skills)` latency ≤ 1ms(N=10 cards × M=10 skills = 100 skills 的 worst case 在普通笔记本 CPU 上 < 1ms;无 I/O,纯 CPU 操作) |
| **NFR-004** | `RemoteAgentTool.description()` 调 `schemaBuilder.buildToolSpecs(...)` 时,若 schemaBuilder 抛异常(理论上不会,因为 pure function 但保留防御)→ 退化为 #009c 固定 description,记录 WARN 日志 `[RemoteAgentTool] schemaBuilder.buildToolSpecs threw, falling back to fixed description: <error>`(EC-11) |
| **NFR-005** | 测试覆盖 ≥ 9 新增 case / 3 文件:`RemoteAgentSchemaBuilderTest` ≥ 6 case + `RemoteAgentToolTest` 增 ≥ 2 case + `HttpJsonRpcA2aTransportAutoConfigurationTest` 增 ≥ 1 case |
| **NFR-006** | **向后兼容**:`RemoteAgentTool` 2 参构造器 `RemoteAgentTool(A2aTransport, ObjectMapper)` **保留**,#009c 已有 4 个 RemoteAgentToolTest case 与 `HttpJsonRpcA2aTransportAutoConfigurationTest.testRemoteAgentToolBeanWiring` 全部不破坏 |
| **NFR-007** | JDK 8 兼容:不用 `var` / `record` / `sealed` / `List.of` / pattern matching;用 `Collections.unmodifiableList` / `Arrays.asList` / `LinkedHashMap` + Jackson `ObjectNode`/`ArrayNode` |
| **NFR-008** | dsh §4.10.1 硬规则 2 兼容:`RemoteAgentTool` 已接入 `ToolExecutor.dispatch()` 5 步流水线(#009c 已落地),本 Story **不**改 ToolExecutor / ToolRegistry / PermissionPolicy 行为 |
| **NFR-009** | `RemoteAgentTool.description()` 内 `summarizeSkills(specs, limit)` 调用时,若 specs.size() > limit → 输出 `"call_<X>_<Y>(<shortDesc>)"` 列表(前 limit 个)+ `"... and M more"`(M = specs.size() - limit);M = 0 时省略 `"... and M more"`(AC-2.2 + EC-9) |
| **NFR-010** | `RemoteAgentSchemaBuilder` 启动期 @Component 注册由 Spring Boot 自动扫;不写 `@PostConstruct` 不写 `ApplicationRunner` —— 真正消费方(PromptBuilder / 启动日志 / 健康检查端点)由后续 Story 落地 |

---

## 5. Edge Cases

| ID | 描述 |
|---|---|
| **EC-1** | `cards == null` → `buildToolSpecs` 返 `Collections.emptyList()` 不抛 NPE(AC-1.2 + NFR-007) |
| **EC-2** | `cards.isEmpty()` → 返 `Collections.emptyList()`(AC-1.2) |
| **EC-3** | `card.get("name") == null` 或 empty → 跳过该 card(不在 result 中出现);`card.get("skills") == null` 或 `skills.isEmpty()` → 同跳过(AC-1.3 + FR-002) |
| **EC-4** | `card.get("skills")` 是 `String`(类型错误,正常应为 `List<Map>`) → Jackson `convertValue` 抛 `IllegalArgumentException` → 整张 card 跳过(用 try-catch 吞错,记录 WARN 日志;**不**让单张畸形 card 拖垮整个 schema 生成)(FR-002 + NFR-004) |
| **EC-5** | `skill.get("description") == null` 或 empty → `skillDesc = ""`,`ToolSpec.description = " (via " + agentName + ": " + agentDesc + ")"`;为美观,`description.startsWith(" (")` 时去掉前导 `" ("` 改成 `"[no description]"`(FR-004 + EC-6) |
| **EC-6** | `card.get("description") == null` 或 empty → `agentDesc = ""`,`ToolSpec.description` 的 `agentDesc` 部分为空串;同样去掉 `"via <agent>: "` 前缀 → 改 `"[no description]"`(FR-004) |
| **EC-7** | `skill.get("inputSchema") == null` 或 类型不是 `Map`/`ObjectNode` → fallback `{type:object, additionalProperties:true}`(AC-1.6 + FR-005) |
| **EC-8** | `RemoteAgentTool` 2 参构造器调用方(#009c 测试) → 走 `schemaBuilder == null` 分支,description 走固定字串;新增 3 参构造器不破坏(AC-2.1 + NFR-006) |
| **EC-9** | `specs.size() > descriptionSkillLimit`(默认 10) → 输出前 N 个 + `"... and M more"`,M = specs.size() - N(AC-2.2 + NFR-009) |
| **EC-10** | `schemaBuilder != null` 但 `buildToolSpecs([])` 返 `[]`(无 cards) → description 退化为 #009c 固定字串(AC-2.3 + FR-011) |
| **EC-11** | `schemaBuilder.buildToolSpecs(...)` 抛 `RuntimeException`(理论不会发生,但保留防御) → 退化 #009c 固定 description + WARN 日志 `[RemoteAgentTool] schemaBuilder.buildToolSpecs threw, falling back to fixed description: <exception.getMessage()>`(NFR-004) |
| **EC-12** | `cards` 含重复 `agentName`(例如 2 张 card 都叫 `alice`)→ 每张 card 各自生成 skill → 最终 ToolSpec.name 可能重复(`call_alice_echo` 出现 2 次)→ 排序后并排,**不**抛错;**未来**重复检测留 #009d+ 增量 Story(FR-007) |
| **EC-13** | `ToolSpec.name` 含特殊字符(例如 skillId 含 `_` → `call_alice_echo_x`)→ ToolSpec.name 透传,**不**清洗;LLM tool 名字字符串长度限制由 Provider 决定(Anthropic 64 字符 / OpenAI 64 字符);超长 → 测试可见 WARN 日志(不 fail-fast,业务侧负责)(FR-003) |
| **EC-14** | `RemoteAgentSchemaBuilder` 被 Spring 启动期注入到 RemoteAgentTool 失败(`NoSuchBeanDefinitionException`)→ RemoteAgentTool 构造期 `schemaBuilder = null`,走 fallback 路径;**不**抛错让 ApplicationContext 启动失败(NFR-004 + fail-fast but graceful degradation) |

---

## 6. Out of Scope(本 Story **不**做)

- **N-tool 模式(每 skill 一个 `call_<name>_<skillId>` tool Bean)** —— 留 OQ-1 revisit trigger;当前单 tool 模式维持 #009c 契约
- **改 AgentSkill 字段加 `inputSchema`/`outputSchema`** —— 那是 #009b deferred work 或后续 Story;当前用 fallback generic schema(详 OQ-2)
- **PromptBuilder 接入 ToolSpec list** —— 留后续 Story,本 Story 只生成 List<ToolSpec> 暴露给调用方
- **启动日志自动打印 ToolSpec 列表** —— 留后续 Story,本 Story 提供 `describeSpecs()` 辅助方法
- **健康检查端点暴露 ToolSpec 列表** —— 留后续 Story
- **A2aTransport.fetchCard 启动期批量调用** —— 本 Story 假设调用方传入已 fetch 的 cards 列表;启动期批量 fetch 由 #009a / #009b / #009c 各自 wire-up 决定
- **ToolSpec 反向生成 AgentCard** —— 留 OQ-3
- **§14.8 hot-reload 触发 `RemoteAgentSchemaBuilder.refresh()`** —— 留 Story #007+ 后续 Story
- **AgentRef 类型引入 + `cfg.getA2a().getRemoteAgents()` 配置** —— dsh §5.6.3.0 描述的 AgentRef 在 lingshu 当前未落地,留 OQ-4;本 Story 接受 `List<Map<String, Object>>` 作 cards 入参,不引入新 DTO
- **新 Maven 依赖** —— R-13 mitigation (d) 强度最弱,0 binary delta
- **`AgentConfig.A2a` 加 `descriptionSkillLimit` 字段** —— 留后续 Story,本期用 hardcoded default `10`(FR-010 + EC-9)
- **改 `RemoteAgentTool.name()` / `inputSchema()` / `execute()` 行为** —— 关键不变项(本 Story 只改 description 与构造器)
- **§15 错误码新引入** —— 0 新 ErrorCode(纯 schema 生成,无 RPC,无新失败路径)

---

## 7. Constitution Check(宪章 v1.0)

| 宪章节 | 条款 | 本 Story 合规情况 |
|---|---|---|
| §1 项目原则 | #8 Slot 选用 | ✅ 不增 Slot;`RemoteAgentSchemaBuilder` 是普通 `@Component`,非 Slot |
| | #9 Plugin 发现 | ✅ `@Component` + `@AutoConfiguration` 内 `@Bean remoteAgentSchemaBuilder` |
| | #11 默认实现位置 | ✅ 不动 Slot 9 默认 Provider(`HttpJsonRpcA2aTransportProvider` 沿用 #009c) |
| §2 13 依赖锁定 | R-13 mitigation (d) 强度最弱 | ✅ 0 新依赖 |
| §4 错误码约定 | LINGS-<域><编号> 域细分 | ✅ **0 新增 ErrorCode** |
| §5 7 层金字塔 | 单元 / Slice / 集成 | ✅ L1 Unit 1 新文件 + 1 修改 + L2 Slice 1 修改 ≥ 9 case |
| §6 兼容性矩阵 | JDK 8 编译 + JDK 17 跑 | ✅ compile target 不动;`RemoteAgentSchemaBuilder` 用 `Collections.unmodifiableList` + `LinkedHashMap` |
| §7 LTS 政策 | JDK 17/21 LTS | ✅ JDK 17+ runtime |
| §8 Glossary | A2A 术语一致 | ✅ `AgentCard` / `AgentSkill` / `ToolSpec` / `RemoteAgentTool` / `RemoteAgentSchemaBuilder` 与 dsh §16 一致 |
| §9 Review 节奏 | PR review + CI | ✅ Story 完成 + PR + CI 全过后 merge |
| §10 风险登记 | R-13(13 依赖锁) + R-14(A2A 协议兼容) | ✅ R-13 强度最弱 + R-14 由 §5.6.3.0 / §5.6.3.2 「3 件套模式」兼容未来变体 |

---

## 8. Success Criteria(完成定义)

1. ✅ `spec.md` / `plan.md` / `tasks.md` / `data-model.md` / `quickstart.md` / `contracts/` / `checklists/` 三件套 + 4 配套 artifact 全部齐备
2. ✅ `RemoteAgentSchemaBuilder`(@Component,pure function,8 个 FR 全实现)+ `RemoteAgentTool` 描述构造器扩展(向后兼容 2 参)+ `HttpJsonRpcA2aTransportAutoConfiguration` @Bean 扩展
3. ✅ L1 Unit + L2 Slice 全部 case 通过(≥ 9 新增 case + 274 已有 case = 283 全过 / 0 fail / 0 error / 0 skipped)
4. ✅ `mvn dependency:tree -pl lingshu-a2a-client` 与 #009c baseline 完全一致(R-13 mitigation (d) 强度最弱 0 binary delta)
5. ✅ dsh §13 changelog 加 v1.5.38 行 + README 更新 3 Provider 列表与 #009d 状态
6. ✅ 0 新 Maven 依赖,0 新 ErrorCode
7. ✅ PR title `feat(a2a-client): Story #009d a2a-remote-schema-builder — ...` + body 末尾 `### R-13 dependency:tree 自查` 节
8. ✅ #009a / #009b / #009c 全部 274 已有测试 0 regression

---

## 9. Open Questions / Future Stories

| ID | 问题 | 落地 Story |
|---|---|---|
| **OQ-1** | 是否把 RemoteAgentTool 从单 tool 拆成 N tool(每 skill 一个 `call_<name>_<skillId>` Bean)? | **Revisit trigger** OpenAI / Anthropic tool spec 2025+ 广泛支持 `oneOf` + nested union;**当前**选单 tool 简化版 |
| **OQ-2** | 是否给 AgentSkill 加 `inputSchema` / `outputSchema` 字段?(让 schemaBuilder 用真实 schema 替代 fallback `{type:object, additionalProperties:true}`) | **Story #009d+** —— 需要改 on-wire JSON 契约 + Server 端 `LocalAgentCardGenerator` 同步;本 Story 用 fallback 简化 |
| **OQ-3** | 是否需要 ToolSpec → AgentCard 反向生成?(把 LingShu 本地 Tool 暴露为 A2A skill 给远端调) | **未来** Server-side skill 导出 Story |
| **OQ-4** | 是否引入 dsh §5.6.3.0 描述的 `AgentRef` 类型 + `cfg.getA2a().getRemoteAgents()` 配置? | **Story #009d+** —— `AgentRef` 含 `endpoint` / `transportName` / `skillIds[]` / `priority` / `enabled` 5 字段,与当前 `Map<String, Object>` 入参模型差异较大;本 Story 简化用 Map 入参 |
| **OQ-5** | PromptBuilder `build()` 是否接入 `List<ToolSpec>` 注入 `[TOOL SCHEMAS]` 段? | **Story #018+** —— PromptBuilder 集成 A2A Skill schemas |
| **OQ-6** | 启动日志自动打印 `[RemoteAgentSchemaBuilder]` 段 `describeSpecs(...)`? | **Story #018+** —— 启动日志 + 健康检查端点 |

---

## 10. References

- **设计文档**: `~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md` v1.5.37
- **SpecKit SOP**: `~/Documents/AIFullStack/MyDSHAgentDesign/speckit_operator_prompt.md` v1.18
- **SKILL**: `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` v1.0.21
- **dsh §5.6.3 L2429-2472**: RemoteAgentTool + RemoteAgentToolAutoConfiguration 草图
- **dsh §5.6.3.0 L2729-2833**: RemoteAgentSchemaBuilder 完整定义
- **dsh §5.6.1 L2346-2390**: 9 个对照表 + Tool 发现机制
- **dsh §5.5 L1887-2003**: 默认实现注册约定 + 🆕 v1.5.28 多 Provider 模式样板
- **dsh §4.10.1 硬规则 2**: ToolExecutor.dispatch() 5 步流水线
- **dsh §4.5.1**: `[TOOL SCHEMAS]` 段 + LLM prompt cache 命中排序
- **dsh §15**: LINGS-<域><编号> 错误码约定(本 Story 0 新增)
- **dsh §17**: R-13 / R-14 风险登记
- **前序 Story PR**:
  - #009a GrpcA2aTransport: PR #20 (merged 064ce3a)
  - #009b InProcessA2aTransport: PR #21 (merged 42888c3)
  - #009c HttpJsonRpcA2aTransport: merged 42f9e91
- **A2A v1.0 spec §2.1**: `GET /.well-known/agent.json` 固定路径 + `skills[]` 字段语义
- **JSON-RPC 2.0 spec**: https://www.jsonrpc.org/specification