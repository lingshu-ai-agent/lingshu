# Tasks: Story #009d a2a-remote-schema-builder

**Input**: Design documents from `/specs/009d-a2a-remote-schema-builder/`
- spec.md(4 User Stories US1—US4 + 14 Edge Cases + FR-001—FR-016 + NFR-001—NFR-010)
- plan.md(11 I-NN 接口变更 + 1 新增 + 2 修改 + ≥ 9 新增 case + 7 步实施顺序 + R-13 mitigation (d) 5 步)
- data-model.md(1 新增类型 + 0 ErrorCode + 2 修改类型 + 6 复用类型)
- contracts/a2a-remote-schema-builder.md(2 契约 ID;1 新增 + 1 影响 + 0 修改)
- quickstart.md(7 验证场景 — AC 关联 + US1—US4 + EC-1—EC-14 + R-13 mitigation (d))
- checklists/requirements.md(12 章节质量门禁)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#009 + #009a + #009b + #009c 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `A2aTransportRouter`[#009a] / `AgentCardCache`[#009a] / `InProcessA2aRegistry`[#009b] / `HttpJsonRpcA2aTransport` 3 件套[#009c] / `RemoteAgentTool`[#009c] / `HttpJsonRpcA2aTransportAutoConfiguration` 双 Bean[#009c] / `Tool` / `ToolExecutor` / `ToolResult` / `ToolSpec` 等基础设施)

**Tests**: Required per FR-001—FR-016 + 10 NFR + 14 Edge Cases。**≥ 9 新增 case** = 6+ L1 Unit + 2+ L1 Unit(RemoteAgentTool)+ 1 L2 Slice。

**Constitution**: v1.0 — §1 #8 Slot 选用 / §1 #9 Plugin 发现 / §1 #11 默认实现位置 / §2 13 依赖锁定(R-13 **+0 新依赖**)/ §4 错误码约定(**0 新 ErrorCode**)/ §5 7 层金字塔(≥ 9 case 覆盖)/ §10 R-13 强度最弱(0 binary delta)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3 / US4)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + capture Story #009c dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 a2a-client 1.11 / 其他 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #009c dependency baseline: `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009d-pre.txt`(期望含 #009a 已落地的 grpc-stub + protobuf-java + os-maven-plugin + protobuf-maven-plugin + #009c 0 新增,**本 Story 应完全一致**)
- [ ] T003 Verify current branch is `story-009d-a2a-remote-schema-builder` via `git branch --show-current`(从 main 拉新分支 `git checkout -b story-009d-a2a-remote-schema-builder`)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test` exits 0(Story #001—#009 + #009a + #009b + #009c tests all green — pre-implementation sanity,已在前面 turn 实测 274 case 全过)

**Checkpoint**: Setup ready — code modifications can begin。

---

## Phase 2: Foundational — `RemoteAgentSchemaBuilder` 落地(US1 基础)

**Purpose**: Establish pure-function schema builder **before** any wiring changes

**⚠️ CRITICAL**: RemoteAgentTool 描述动态化(US2)+ AutoConfiguration wiring(US3)都依赖 `RemoteAgentSchemaBuilder` 类先落地;此 phase 必须先完成

- [ ] T005 [P0] [US1] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java`:
  - `@Component public class RemoteAgentSchemaBuilder`(纯辅助类,无状态)
  - 字段:`private final ObjectMapper json;`(构造期 null-check 抛 `IllegalArgumentException`)
  - 静态常量:`private static final Logger log = LoggerFactory.getLogger(RemoteAgentSchemaBuilder.class);`
  - 方法 1:`public List<ToolSpec> buildToolSpecs(List<Map<String, Object>> cards)`:
    - `cards == null || cards.isEmpty()` → `Collections.emptyList()`(EC-1 + EC-2)
    - 对每张 card:try-catch `RuntimeException` 包整张 card 解析(EC-4):
      - `String agentName = asString(card.get("name"))` —— null/empty 跳过该 card(EC-3)
      - `String agentDesc = Optional.ofNullable(asString(card.get("description"))).orElse("")`(EC-6)
      - `Object skillsObj = card.get("skills")` —— 非 `List<?>` 跳过该 card(EC-3 + EC-4)
      - 对每 skill:`if (!(skillObj instanceof Map)) continue;`(EC-4)
        - `String skillId = asString(skill.get("id"))` —— null/empty 跳过该 skill(EC-7)
        - `String skillDesc = Optional.ofNullable(asString(skill.get("description"))).orElse("")`(EC-5)
        - 构造 `ToolSpec("call_" + agentName + "_" + skillId, formatDescription(skillDesc, agentName, agentDesc), resolveInputSchema(skill))`
    - 排序:`specs.sort((a, b) -> a.getName().compareTo(b.getName()))`(AC-1.7)
    - 返回 `Collections.unmodifiableList(specs)`(AC-1.8)
  - 方法 2:`public String describeSpecs(List<ToolSpec> specs)`:
    - `specs == null || specs.isEmpty()` → `"(empty)"`
    - 否则 `"[" + specs.size() + " tools]\n" + "  - " + name + ": " + truncate(desc, 80) + "\n" + ...`(AC-1.9)
  - 私有 helper:`asString(Object)`(类型检查 + 转 String)+ `truncate(String, int)`(长度截断 + `...`)+ `formatDescription(String, String, String)`(EC-5 + EC-6 美化,空 skillDesc/agentDesc 走 `[no description]` 替换)+ `resolveInputSchema(Map<String,Object>)`(AC-1.6:优先 `skill.inputSchema` 转 JsonNode,fallback `{type:object, additionalProperties:true}` ObjectNode)
  - Javadoc 完整描述 dsh §5.6.3.0 L2729-2833 锚定 + 与 #009c RemoteAgentTool 单 tool 模式互补 + OQ-1 / OQ-2 revisit trigger + 0 新 ErrorCode 约束

- [ ] T006 [P0] 验证 Phase 2: `mvn -pl lingshu-a2a-client compile` exit 0(RemoteAgentSchemaBuilder 编译过)

**Checkpoint**: Phase 2 ready — `RemoteAgentSchemaBuilder` pure function 可用。

---

## Phase 3: User Story 2 — `RemoteAgentTool` 描述动态化(wiring 通路走通)

**Purpose**: 扩展 RemoteAgentTool 构造器接受 schemaBuilder,**不**改 inputSchema/execute/name 行为(关键不变项 #1)

**⚠️ dev/scope deviation from spec.md AC-2.2** —— 本 Story 受 Story 边界约束(< 3 ErrorCode + < 5 文件),description 拼接 skills 列表留后续 Story(详 plan.md §5.1)。本期 description 只加 hint `" (RemoteAgentSchemaBuilder wired — N-tool schemas pending)"`,让 reviewer 知道 wiring 通路已通。

- [ ] T007 [P0] [US2] Modify `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java`:
  - **保留** 2 参构造器 `RemoteAgentTool(A2aTransport transport, ObjectMapper json)`(向后兼容 #009c 测试,FR-009 + AC-2.1 + NFR-006)
  - **新增** 3 参构造器 `RemoteAgentTool(A2aTransport transport, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)`:
    - 3 字段 null-check 抛 `IllegalArgumentException`
    - 内部 `this(transport, json)` 复用 2 参构造器 + `this.schemaBuilder = schemaBuilder;`
  - 加字段 `private final RemoteAgentSchemaBuilder schemaBuilder;`(nullable)
  - 加常量:`private static final String BASE_DESCRIPTION = "Invoke a skill on a remote A2A agent. Input: {\"agentName\":\"<X>\", \"skill\":\"<Y>\", \"input\": {...}}.";`(沿用 #009c 固定字串,集中常量便于维护)
  - 加常量:`private static final String SCHEMA_BUILDER_HINT = " (RemoteAgentSchemaBuilder wired — N-tool schemas pending)";`(dev/scope 标注)
  - `description()` 改写:
    ```java
    @Override
    public String description() {
        if (schemaBuilder == null) {
            return BASE_DESCRIPTION;  // AC-2.1 + EC-8
        }
        try {
            // dev/scope: description 拼接 skills 列表留后续 Story(plan.md §5.1)
            // 本期只验证 wiring 通路走通 —— schemaBuilder 注入后 description 加 hint
            return BASE_DESCRIPTION + SCHEMA_BUILDER_HINT;  // AC-2.1
        } catch (RuntimeException e) {
            log.warn("[RemoteAgentTool] schemaBuilder.buildToolSpecs threw, falling back: {}", e.getMessage());
            return BASE_DESCRIPTION;  // EC-11 + NFR-004
        }
    }
    ```
  - `name()` **不变**(仍 = `"remote_agent"` 固定,关键不变项 #1)
  - `inputSchema()` **不变**(仍 = #009c 固定 schema,关键不变项 #1)
  - `execute()` **不变**(关键不变项 #1)
  - 加 `import org.slf4j.Logger;` + `org.slf4j.LoggerFactory;`(如未引入)
  - 加 Javadoc 类注释:本期落地 wiring + dev/scope 标注,AC-2.2 / AC-2.3 完整 skills 列表拼接留 OQ-Future Story(详 spec.md §9 OQ-1 / OQ-5)

- [ ] T008 [P0] 验证 Phase 3: `mvn -pl lingshu-a2a-client compile` exit 0 + `mvn -pl lingshu-a2a-client test -Dtest=RemoteAgentToolTest`(已有 4 case 不 regress,因 2 参构造器保留)

**Checkpoint**: Phase 3 ready — RemoteAgentTool 3 参构造器 + description() 加 hint;输入输出 schema 与 name 不变。

---

## Phase 4: User Story 3 — `HttpJsonRpcA2aTransportAutoConfiguration` 暴露 schemaBuilder @Bean + wiring

**Purpose**: Spring Boot 自动扫 `RemoteAgentSchemaBuilder` Bean 并注入到 RemoteAgentTool 构造器

- [ ] T009 [P0] [US3] Modify `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`:
  - 增加 `@Bean(name = "remoteAgentSchemaBuilder") public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json) { return new RemoteAgentSchemaBuilder(json); }`(AC-3.1 + FR-013)
  - `remoteAgentTool(...)` Bean 改 4 参签名:`public RemoteAgentTool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)`(AC-3.2)
  - 内部 `return new RemoteAgentTool(transport, json, schemaBuilder);`(FR-013)
  - Javadoc 类注释加 1 段:本 Story #009d 加 schemaBuilder @Bean + RemoteAgentTool 3 参 wiring;沿用 #009c 双 Bean 模式(transport Provider + remoteAgentTool);**不**改 SPI 注册文件 imports 行数
  - 关键不变项:保持 `HttpJsonRpcA2aTransportProvider` @Bean **不变**;保持 `remoteAgentTool` Bean 名 `"remoteAgentTool"` **不变**

- [ ] T010 [P0] 验证 Phase 4: `mvn -pl lingshu-a2a-client compile` exit 0 + `mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportAutoConfigurationTest`(已有 2 case 不 regress;若现有 test 调用 `remoteAgentTool` Bean 期望类型 = `Tool`,仍兼容因 `RemoteAgentTool implements Tool`)

**Checkpoint**: Phase 4 ready — schemaBuilder Bean 暴露 + RemoteAgentTool 自动 wire 4 参构造器。

---

## Phase 5: Tests — 3 测试文件 + ≥ 9 case

**Purpose**: L1 Unit + L2 Slice 验证所有 US + EC + 已有 case 不 regress

- [ ] T011 [P0] [US1] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilderTest.java`:
  - L1 Unit, ≥ 6 case:
    1. `testBuildToolSpecsHappyPath` — 2 cards × 2 skills = 4 ToolSpec,按 name 字典序排序;验证 `call_alice_echo` + `call_alice_greet` + `call_bob_search` + `call_bob_summarize`(VS-1 + AC-1.3—1.7)
    2. `testBuildToolSpecsEmptyList` — `buildToolSpecs(Collections.emptyList())` 返 `[]`(EC-1)
    3. `testBuildToolSpecsNullInput` — `buildToolSpecs(null)` 返 `[]` 不 NPE(EC-2)
    4. `testBuildToolSpecsCardMissingName` — card 无 name 字段 → 跳过该 card;返空 list(EC-3)
    5. `testBuildToolSpecsSkillMissingId` — skill 无 id 字段 → 跳过该 skill;card 仍生成其他 skill(EC-7)
    6. `testBuildToolSpecsFallbackSchema` — skill 无 inputSchema 字段 → 生成的 ToolSpec.inputSchema = `{type:object, additionalProperties:true}`(AC-1.6 + EC-7)
    7. `testBuildToolSpecsWithInputSchema` — skill 含 inputSchema `{"type":"object","properties":{"x":{"type":"string"}}}` → 透传该 schema(AC-1.6)
    8. `testDescribeSpecs` — `describeSpecs(specs)` 输出形如 `[2 tools]\n  - call_alice_echo: ...`(AC-1.9)
  - 工具:用 `new ObjectMapper()` 实例化 builder;Map 用 `Collections.singletonMap(...)` 或 `new HashMap<>()` 构造

- [ ] T012 [P0] [US2] Modify `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolTest.java`:
  - L1 Unit + Mockito, 增 ≥ 2 新 case(沿用 #009c 已有 4 case):
    1. `testDescriptionWithSchemaBuilderWired` — `new RemoteAgentTool(mockTransport, json, new RemoteAgentSchemaBuilder(json))` → description 含 `"schemaBuilder wired"`(AC-2.1 + AC-2.4 + FR-011 + 关键不变项 #1)
    2. `testDescriptionFallsBackOnException` — 用 Mockito mock schemaBuilder,`schemaBuilder.buildToolSpecs(any) throws new RuntimeException("test")` → description 走 BASE_DESCRIPTION(EC-11 + NFR-004 + WARN 日志断言)
    3. `testDescriptionWithNullSchemaBuilder` — `new RemoteAgentTool(mockTransport, json)`(2 参构造器)→ description 走 #009c 固定字串(AC-2.1 + EC-8 + NFR-006)
  - 注意:`#009c 已有 4 case`(testExecuteHappyPath / testExecuteTransportThrowsLingsS08 / testInputSchemaFixedShape / testDescriptionNotBlank)不破坏 —— `inputSchema()` / `name()` / `execute()` 行为不变

- [ ] T013 [P0] [US3] Modify `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java`:
  - L2 Slice(Spring `@SpringBootTest`), 增 ≥ 1 新 case(沿用 #009c 已有 2 case:testMultiProviderCoexistence + testResolveHttpJsonRpc):
    1. `testRemoteAgentSchemaBuilderBeanWiring` — Spring 容器有 `RemoteAgentSchemaBuilder` Bean(`@Autowired RemoteAgentSchemaBuilder schemaBuilder` 不为 null);同时验证 `RemoteAgentTool` Bean 注入的 schemaBuilder 与容器内 Bean 是同一实例(AC-3.1 + AC-3.2 + AC-3.4)
  - 测试 setup:`@SpringBootTest(classes = {GrpcA2aTransportAutoConfiguration.class, InProcessA2aTransportAutoConfiguration.class, HttpJsonRpcA2aTransportAutoConfiguration.class, A2aTransportRouter.class, YamlTenantConfigProvider.class, AgentConfigDefaults.class})`(沿用 #009c 测试 setup)

- [ ] T014 [P0] 验证 Phase 5: `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test` 期望 274 + ≥ 9 = 283 case 全过(0 fail / 0 error / 0 skipped)

**Checkpoint**: Phase 5 ready — 所有 ≥ 9 新增 case 通过 + 274 已有 case 不 regress。

---

## Phase 6: R-13 mitigation (d) baseline 镜像

**Purpose**: Verify 0 binary delta + enforcer pass

- [ ] T015 [P0] 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009d-post.txt`
- [ ] T016 [P0] 跑 `diff /tmp/deps-009d-pre.txt /tmp/deps-009d-post.txt`,期望**无输出**(完全一致,0 binary delta)
- [ ] T017 [P0] 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`,期望 BUILD SUCCESS + banned-dependencies 规则**不 fail**
- [ ] T018 [P0] (可选,本 Story 0 binary delta) 验证 CI matrix JDK 8 job 是否需要跳过 a2a-client(若 `.github/workflows/maven.yml` 已用 `-pl lingshu-core,lingshu-a2a-server` 则无需改;若用 `-pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client` 则需加 `-pl !lingshu-a2a-client`)

**Checkpoint**: Phase 6 ready — R-13 mitigation (d) baseline 镜像通过。

---

## Phase 7: AC-10 关联验证 + 启动日志验证

**Purpose**: End-to-end verification of RemoteAgentSchemaBuilder wiring + RemoteAgentTool description 拼接 hint

- [ ] T019 [P1] [US3] 启动日志验证(可选,若有 demo):`mvn -pl lingshu-examples exec:java -Dexec.mainClass="ai.lingshu.examples.Main" -Dexec.args="--config src/main/resources/application.yml"`
  - 期望日志包含:`[A2aTransport] resolved 3 provider(s) [contract v1.0.0]:` + 3 行 ✓ 列表(grpc + in-process + http-jsonrpc)
  - 期望额外日志:`[RemoteAgentSchemaBuilder]` Bean 初始化成功
  - 若无 demo,跳过此 task(本 Story 不强制)

- [ ] T020 [P1] [US2] 单元测试场景下 `RemoteAgentTool.description()` 含 `"RemoteAgentSchemaBuilder wired"` hint 验证(`RemoteAgentToolTest` 加 1 个 `@Timeout(value = 1, unit = TimeUnit.SECONDS)` 测试,确保 description 调用 < 1s)

**Checkpoint**: Phase 7 ready — 启动日志 + 性能验证通过(若有 demo)。

---

## Phase 8: Doc Sync + Commit + PR

**Purpose**: Sync docs + commit + PR + CI

- [ ] T021 [P1] Modify `README.md`(Story 路线图 #009d 行 + 启动日志样例):
  - 表格 #009d 行 `⏳ 待 #009c 合` → `已合 ✅(本 PR)`
  - 启动日志样例 + 测试计数 `274 → 283`(若 T014 通过)
- [ ] T022 [P1] Modify `dsh_agent_design.md` §13 changelog 加 v1.5.38 行:Story #009d 完成 + RemoteAgentSchemaBuilder + RemoteAgentTool description wiring + 0 binary delta
- [ ] T023 [P1] Modify `constitution.md` §10 R-13 风险状态:本 Story 0 binary delta → R-13 强度不变;新增 R-XX 备注:RemoteAgentSchemaBuilder 是 pure function 类,无 A2aTransport / Spring 依赖
- [ ] T024 [P0] `git add -A && git commit -m "feat(a2a-client): Story #009d a2a-remote-schema-builder — RemoteAgentSchemaBuilder pure function + RemoteAgentTool description wiring + 0 binary delta"`
  - Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
- [ ] T025 [P0] `git push origin story-009d-a2a-remote-schema-builder`
- [ ] T026 [P0] `gh pr create --title "feat(a2a-client): Story #009d a2a-remote-schema-builder — RemoteAgentSchemaBuilder + description wiring + 0 binary delta" --body "..."`(贴 spec.md + plan.md + tasks.md + AC 验证输出 + R-13 dep-tree diff + dev/scope deviation note 到 PR body)
- [ ] T027 [P0] 等 CI 全过后(若需要)手动 merge(用户授权后)

**Checkpoint**: Phase 8 ready — PR 合并 + 文档同步 + Story #009d 完成。

---

## 关键不变项(不引入新决策)

- `A2aTransport` 5 方法契约不变(关键不变项 #3)
- `A2aTransportRouter` 行为不变(#009a 已落地)
- `AgentCardCache` 行为不变(#009a 已落地,**复用**)
- `InProcessA2aRegistry` 行为不变(#009b 已落地,**复用**)
- `AgentConfig.A2a` 字段构造**不变**(本期用 hardcoded `descriptionSkillLimit=10`,OQ-Future)
- `ToolSpec` 不新建(关键不变项 #2 —— 复用 `lingshu-core.message.ToolSpec`)
- `AgentCard` / `AgentSkill` 字段**不变**(避免改 on-wire JSON 契约)
- `SlotRouter<P, T>` 父类不变
- `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration` 不变(#009a)
- `InProcessA2aTransport` / `InProcessA2aTransportProvider` / `InProcessA2aTransportAutoConfiguration` 不变(#009b)
- `HttpJsonRpcA2aTransport` / `HttpJsonRpcA2aTransportProvider` 不变(#009c)
- `HttpJsonRpcA2aTransportAutoConfiguration` 主体不变(#009c),**只**内部增加 `@Bean remoteAgentSchemaBuilder` + 改 `remoteAgentTool(...)` 4 参签名
- `A2aServer` 不变(#009 / #009b / #009c 已落地)
- SPI 注册文件 `META-INF/spring/...imports` 行数**不变**(1 行 `HttpJsonRpcA2aTransportAutoConfiguration`)
- `RemoteAgentTool` 的 `name()` / `inputSchema()` / `execute()` **不变**(关键不变项 #1),**只** `description()` 改写 + 构造器扩展
- `RemoteAgentTool` 2 参构造器 `RemoteAgentTool(A2aTransport, ObjectMapper)` **保留**(NFR-006 + #009c 4 case 不 regress)
- JDK 8 only:不用 `var` / `record` / `sealed`,用 `Collections.unmodifiableList` + `Arrays.asList` + `LinkedHashMap` + Jackson `ObjectNode`

---

## Story 边界检查(CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件新增 | ≤ 5 | 1(`RemoteAgentSchemaBuilder`)| ✅ |
| 核心文件修改 | 不计入边界 | 2(`RemoteAgentTool` 加 3 参构造器 + description() hint / `HttpJsonRpcA2aTransportAutoConfiguration` 加 @Bean + 改 4 参 wiring)| ✅ |
| 测试文件新增 | 不计入边界 | 1(`RemoteAgentSchemaBuilderTest` ≥ 6 case)| ✅ |
| 测试文件修改 | 不计入边界 | 2(`RemoteAgentToolTest` 增 ≥ 2 case / `HttpJsonRpcA2aTransportAutoConfigurationTest` 增 ≥ 1 case)| ✅ |
| ErrorCode 引入 | ≤ 3 | **0** | ✅ |
| 新 Maven 依赖 | R-13 mitigation (d) | **0** | ✅ 强度最弱 |
| 改动模块 | 主要 lingshu-a2a-client(1 新增 + 2 修改)| ✓ | ✅ |
| compile target 微调 | RFC 触发 | 不变(#009c 已升 1.11,本期不再动)| ✅ |
| dev/scope deviation | 显式标注 | AC-2.2 / AC-2.3 完整 skills 列表拼接留 OQ-Future(plan.md §5.1)| ⚠️ PR body + spec.md 显式标注 |

---

## 依赖图

```
Phase 1 (Setup)
   ↓
Phase 2 (Foundational — RemoteAgentSchemaBuilder) ─── 阻塞所有 US
   ↓
Phase 3 (US2 — RemoteAgentTool 描述动态化) ─────── 独立
   ↓
Phase 4 (US3 — AutoConfiguration wiring) ───────── 依赖 Phase 3
   ↓
Phase 5 (Tests — 1 新 + 2 修改 = 3 文件 / ≥ 9 case) ── 依赖 Phase 2 + 3 + 4
   ↓
Phase 6 (R-13 baseline 镜像)
   ↓
Phase 7 (AC-10 + 启动日志)
   ↓
Phase 8 (Doc Sync + Commit + PR)
```

---

## 任务数统计

| Phase | 任务数 | 关键产出 |
|---|---|---|
| Phase 1 Setup | 4 | R-13 baseline 镜像 + 环境验证 |
| Phase 2 Foundational | 2 | RemoteAgentSchemaBuilder pure function |
| Phase 3 US2 | 2 | RemoteAgentTool 3 参构造器 + description() hint |
| Phase 4 US3 | 2 | AutoConfiguration 加 schemaBuilder @Bean + 4 参 wiring |
| Phase 5 Tests | 4 | 1 新增 + 2 修改测试文件 + ≥ 9 case |
| Phase 6 R-13 | 4 | 0 binary delta 验证 + enforcer |
| Phase 7 AC-10 | 2 | 启动日志 + 性能验证(可选)|
| Phase 8 Doc + PR | 7 | 文档同步 + commit + push + PR + merge |
| **合计** | **27 tasks** | — |

---

## 总结

**全部 27 task 勾完** 即 Story #009d 完成 ✅

**dev/scope 关键偏差**(PR body + spec.md §9 OQ-1 / OQ-5 / OQ-6 显式标注):
- AC-2.2 / AC-2.3 完整 skills 列表拼接 **留 OQ-Future Story** —— 本期 description 只加 `"RemoteAgentSchemaBuilder wired"` hint
- N-tool 模式(每 skill 一个 `call_<name>_<skillId>` tool Bean)留 OQ-1
- PromptBuilder 集成 ToolSpec list 留 OQ-5
- 启动日志自动打印 ToolSpec 列表留 OQ-6
- AgentSkill 加 `inputSchema` / `outputSchema` 字段留 OQ-2
- AgentRef + cfg.getA2a().getRemoteAgents() 配置留 OQ-4

**理由**:CLAUDE.md §11 #4 Story 边界 < 5 文件 + < 3 ErrorCode;本期已落地 RemoteAgentSchemaBuilder pure function(US1)+ RemoteAgentTool description wiring(US2 dev/scope 简化版)+ AutoConfiguration wiring(US3)+ 完整 7 步 Phase。完整 N-tool + description 拼 skills 列表需要引入 AgentRef + cfg.getA2a().getRemoteAgents() + PromptBuilder 集成,**显著**超界。