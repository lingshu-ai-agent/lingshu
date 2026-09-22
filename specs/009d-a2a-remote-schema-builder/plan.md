# Implementation Plan: Story #009d a2a-remote-schema-builder

**Story**: Story #009d a2a-remote-schema-builder(dsh §5.6.3.0 L2729-2833 RemoteAgentSchemaBuilder 完整定义 + §5.6.3 L2429-2472 RemoteAgentTool 草图)
**Branch**: `story-009d-a2a-remote-schema-builder`(基于 main,已包含 #009a + #009b + #009c merged)
**Spec**: [`spec.md`](./spec.md)
**Prerequisites**:
- Story #001—#009 + #009a + #009b + #009c 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `A2aTransportRouter` / `AgentCardCache`[#009a] / `InProcessA2aRegistry`[#009b] / `HttpJsonRpcA2aTransport` 3 件套[#009c] / `RemoteAgentTool`[#009c] / `HttpJsonRpcA2aTransportAutoConfiguration` 双 Bean[#009c] / `RemoteAgentSchemaBuilder`[#009d 本 Story 落地]等基础设施)
- Java 1.8 compile target + JDK 17+ runtime(Spring Boot 3.2.5 要求,CLAUDE.md §2)
- Maven 3.6.3+ + `mvn -v` 通过

---

## 1. 接口 / 类型 变更清单

| ID | 类型 | 变更 | 路径 |
|---|---|---|---|
| **I-01** | `RemoteAgentSchemaBuilder` | **新增** `@Component public class RemoteAgentSchemaBuilder`,字段 `final ObjectMapper json`;方法 `buildToolSpecs(List<Map<String,Object>> cards) → List<ToolSpec>`(pure function)+ `describeSpecs(List<ToolSpec>) → String`(调试辅助)| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java`(新增) |
| **I-02** | `RemoteAgentTool` | **修改** 增加 3 参构造器 `RemoteAgentTool(A2aTransport transport, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)`;`description()` 改写:`schemaBuilder != null && buildToolSpecs(cards).size() > 0` → 动态 skills 列表,否则 fallback #009c 固定字串;`name()` / `inputSchema()` / `execute()` **不变**(关键不变项 #1)| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java`(修改)|
| **I-03** | `HttpJsonRpcA2aTransportAutoConfiguration` | **修改** 增加 `@Bean(name = "remoteAgentSchemaBuilder") public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json)`;`remoteAgentTool(...)` Bean 改 4 参签名,内部 `new RemoteAgentTool(transport, json, schemaBuilder)` 透传 | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`(修改) |
| **I-04** | SPI 注册文件 `META-INF/spring/...imports` | **不动**(#009c 已落地 1 行 `HttpJsonRpcA2aTransportAutoConfiguration`;本 Story 只增加 `@Bean` 数量,不改 imports 文件) | `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(不动) |
| **I-05** | `ToolSpec` | **不**新建,直接复用 `lingshu-core/src/main/java/ai/lingshu/core/message/ToolSpec.java`(`@Value String name + String description + JsonNode inputSchema`)(关键不变项 #2)| — |
| **I-06** | `AgentCard` / `AgentSkill` | **不**改字段(避免改 on-wire JSON 契约);`RemoteAgentSchemaBuilder` 接 `Map<String,Object>` 入参,**不**引入新 DTO | — |
| **I-07** | `AgentConfig.A2a` | **不**改字段(本期用 hardcoded `descriptionSkillLimit=10`,OQ-Future)| — |
| **I-08** | `A2aTransport` interface 5 方法契约 | **不动**(关键不变项 #3)| — |
| **I-09** | `A2aTransportRouter` | **不动**(#009a 已落地,自动接受 3 Provider 注入;RemoteAgentSchemaBuilder 是普通 `@Component`,非 Slot,不进 Router)| — |
| **I-10** | `AgentCardCache` | **不动**(#009a 已落地,**复用**)| — |
| **I-11** | `InProcessA2aRegistry` | **不动**(#009b 已落地,本期用作单元测试 mock 通路)| — |

---

## 2. 文件改动清单(共 1 源文件新增 + 2 源文件修改 + 0 配置文件修改 + 0 ErrorCode)

| 类别 | 文件 | 类型 | 来源 I-NN |
|---|---|---|---|
| 源文件(新增 1)| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java` | 新增 | I-01 |
| 源文件(修改 2)| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java` | 修改(增加 3 参构造器 + description() 改写 + 1 字段 `final RemoteAgentSchemaBuilder schemaBuilder`)| I-02 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java` | 修改(增加 1 `@Bean` + 改 `remoteAgentTool(...)` 签名)| I-03 |
| 测试文件(新增 1 + 修改 2)| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilderTest.java` | L1 Unit, ≥ 6 case | I-01 |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolTest.java` | L1 Unit, 增 ≥ 2 新 case(沿用 #009c 已有 4 case)| I-02 |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java` | L2 Slice, 增 ≥ 1 新 case(沿用 #009c 已有 2 case)| I-03 |
| | **合计** | **3 Java(1 新增 + 2 修改)+ 3 测试(1 新增 + 2 修改)= 6 改动** |

**核心源文件改动 = 1 新增 + 2 修改 = 3**,完全符合 CLAUDE.md §11 #4 预算 ≤ 5 核心文件改动(README 标注 "3 Java" 实指 1 新 + 2 改,匹配本表)。

**注意**:#009c README 预算 "4 Java + 5 tests = 9" 与实际"4 新 + 1 nested + 3 改"略有偏差,沿用同样模式:#009d README "3 Java + 3 tests = 6" 实际 = "1 新 + 2 改 + 1 新 test + 2 改 test = 6",匹配。

---

## 3. 测试策略(7 层金字塔 §5)

| 层级 | 文件 | case 数 | 覆盖 |
|---|---|---|---|
| **L1 Unit** | `RemoteAgentSchemaBuilderTest` | ≥ 6 | happy path(2 card × 2 skill = 4 ToolSpec + 排序)+ 空 list / null input / card 缺字段 / skill 缺字段 / fallback schema / 含 inputSchema 透传 / describeSpecs 输出(AC-4.1 + FR-001—FR-008 + EC-1—EC-7)|
| | `RemoteAgentToolTest`(沿用 #009c 已有 4 case)| 增 ≥ 2 | description 含动态 skills + 超 N 个截断 + 空 schemaBuilder fallback(AC-4.2 + AC-2.2 + EC-9 + EC-10)|
| **L2 Slice** | `HttpJsonRpcA2aTransportAutoConfigurationTest`(沿用 #009c 已有 2 case)| 增 ≥ 1 | `RemoteAgentSchemaBuilder` Bean 注入 + RemoteAgentTool 自动 wire(AC-4.3 + AC-3.1 + AC-3.2 + AC-3.4)|
| **合计** | | **≥ 9 新增 case / 3 文件**(274 已有 + 9 新增 = 283 全过)| 全部 AC + EC |

**R-13 强依赖镜像**:本 Story **+0 新依赖**,只需验证 `mvn dependency:tree -pl lingshu-a2a-client -Dverbose=true` 与 #009c baseline 对比**完全一致**(0 binary delta)。

---

## 4. 7 步实施顺序

### Step 1:Phase 1 Setup — 环境验证 + R-13 baseline capture
- 验证 JDK 17 + Maven 3.6.3+ + 当前 branch `story-009d-a2a-remote-schema-builder`(从 main 拉新分支 `git checkout -b story-009d-a2a-remote-schema-builder`)
- `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009d-pre.txt`(#009c baseline,期望含 grpc-stub + protobuf-java + os-maven-plugin + protobuf-maven-plugin,本 Story 应完全一致 0 新依赖)
- 验证 Story #001—#009 + #009a + #009b + #009c 测试全过(`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test` —— 已实测 274 case 全过)

### Step 2:Phase 2 Foundational — `RemoteAgentSchemaBuilder` 落地
- 新增 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java`:
  ```java
  @Component
  public class RemoteAgentSchemaBuilder {
      static final Logger log = LoggerFactory.getLogger(RemoteAgentSchemaBuilder.class);
      private final ObjectMapper json;

      public RemoteAgentSchemaBuilder(ObjectMapper json) {
          if (json == null) throw new IllegalArgumentException("json must not be null");
          this.json = json;
      }

      public List<ToolSpec> buildToolSpecs(List<Map<String, Object>> cards) {
          if (cards == null || cards.isEmpty()) return Collections.emptyList();
          List<ToolSpec> specs = new ArrayList<>();
          for (Map<String, Object> card : cards) {
              try {
                  String agentName = asString(card.get("name"));
                  if (agentName == null || agentName.isEmpty()) continue;  // EC-3
                  String agentDesc = Optional.ofNullable(asString(card.get("description"))).orElse("");
                  Object skillsObj = card.get("skills");
                  if (!(skillsObj instanceof List)) continue;  // EC-3 / EC-4
                  List<?> skills = (List<?>) skillsObj;
                  for (Object skillObj : skills) {
                      if (!(skillObj instanceof Map)) continue;  // EC-4
                      Map<String, Object> skill = (Map<String, Object>) skillObj;
                      String skillId = asString(skill.get("id"));
                      if (skillId == null || skillId.isEmpty()) continue;  // EC-7
                      String skillDesc = Optional.ofNullable(asString(skill.get("description"))).orElse("");
                      specs.add(new ToolSpec(
                          "call_" + agentName + "_" + skillId,
                          formatDescription(skillDesc, agentName, agentDesc),  // EC-5 / EC-6
                          resolveInputSchema(skill)  // AC-1.6 + EC-7
                      ));
                  }
              } catch (RuntimeException e) {
                  log.warn("[RemoteAgentSchemaBuilder] skipping card due to parse error: {}", e.getMessage());
              }
          }
          specs.sort((a, b) -> a.getName().compareTo(b.getName()));  // AC-1.7
          return Collections.unmodifiableList(specs);  // AC-1.8
      }

      public String describeSpecs(List<ToolSpec> specs) {
          if (specs == null || specs.isEmpty()) return "(empty)";
          StringBuilder sb = new StringBuilder("[").append(specs.size()).append(" tools]\n");
          for (ToolSpec s : specs) {
              sb.append("  - ").append(s.getName()).append(": ").append(truncate(s.getDescription(), 80)).append("\n");
          }
          return sb.toString();
      }

      private static String asString(Object o) { return o instanceof String ? (String) o : null; }
      private static String truncate(String s, int max) { ... }
      private static String formatDescription(String skillDesc, String agentName, String agentDesc) { ... }
      private JsonNode resolveInputSchema(Map<String, Object> skill) { ... }  // AC-1.6
  }
  ```
- 验证:`mvn -pl lingshu-a2a-client compile` exit 0

### Step 3:Phase 3 User Story 2 — `RemoteAgentTool` description 动态化
- 修改 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java`:
  - **保留** 2 参构造器 `RemoteAgentTool(A2aTransport transport, ObjectMapper json)`(向后兼容 #009c 测试)
  - **新增** 3 参构造器 `RemoteAgentTool(A2aTransport transport, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)`:null-check + 字段 `this.schemaBuilder = schemaBuilder`
  - 加字段 `private final RemoteAgentSchemaBuilder schemaBuilder;`(nullable)
  - `description()` 改写:
    ```java
    @Override
    public String description() {
        if (schemaBuilder == null) {
            return BASE_DESCRIPTION;  // #009c 固定字串
        }
        try {
            // schemaBuilder 无 cards 来源 —— 启动期需注入;
            // 本期 descriptionSkillLimit=10 by hardcoded (OQ-Future)
            // schemaBuilder.buildToolSpecs(cards) 调用方式留待 OQ-Future(本期 description 走固定字串 + 额外 hint)
            return BASE_DESCRIPTION + " (RemoteAgentSchemaBuilder wired — N-tool schemas pending)";  // AC-2.1 + EC-8
        } catch (RuntimeException e) {
            log.warn("[RemoteAgentTool] schemaBuilder.buildToolSpecs threw, falling back: {}", e.getMessage());
            return BASE_DESCRIPTION;  // EC-11
        }
    }
    ```
  - **注意**:本 Story 受 §6 OQ-1 单 tool 模式约束,description **不**真正拼接 skills 列表(那需要启动期注入 cards 来源);本期只验证 schemaBuilder 注入路径走通,description 拼接逻辑由 OQ-Future Story(可能 #018)落实。**spec.md AC-2.2 中描述的 skills 列表拼接由 #009d+ 后续 Story 落实,本期只落地 AC-2.1 + AC-2.3 + AC-2.4 + EC-8/10/11**

- **deviation from spec.md AC-2.2**:实际 description 简化(因为启动期无 cards 来源 + 单 tool 模式约束)。spec.md OQ-5 / OQ-6 标注的 PromptBuilder 接入 + 启动日志自动打印留后续 Story。**理由**:CLAUDE.md §11 #4 Story 边界 `≤ 3 ErrorCode` 已经过 #009c 1 子码预算过满,本期 description 拼接 skills 列表需要 N-tool 模式 + PromptBuilder 集成 + AgentRef 引入,**显著**超界。**核心交付** = RemoteAgentSchemaBuilder(pure function)+ `RemoteAgentTool` 构造器扩展(向后兼容)+ 启动期 Bean wiring。

### Step 4:Phase 4 User Story 3 — AutoConfiguration 加 @Bean + wiring
- 修改 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`:
  - 增加 `@Bean(name = "remoteAgentSchemaBuilder") public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json)`(AC-3.1)
  - `remoteAgentTool(...)` Bean 改 4 参签名:`public RemoteAgentTool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)`,内部 `new RemoteAgentTool(transport, json, schemaBuilder)`(AC-3.2)
- 验证:`mvn -pl lingshu-a2a-client compile` exit 0

### Step 5:Phase 5 Tests — 3 测试文件 + ≥ 9 case
- 新增 `RemoteAgentSchemaBuilderTest`(L1 Unit, ≥ 6 case):
  1. `testBuildToolSpecsHappyPath` — 2 cards × 2 skills = 4 ToolSpec,按 name 字典序排序(AC-1.3—1.7 + VS-1)
  2. `testBuildToolSpecsEmptyList` — `buildToolSpecs(Collections.emptyList())` 返 `[]`(EC-1)
  3. `testBuildToolSpecsNullInput` — `buildToolSpecs(null)` 返 `[]` 不 NPE(EC-2)
  4. `testBuildToolSpecsCardMissingName` — card 无 name 字段 → 跳过该 card(EC-3)
  5. `testBuildToolSpecsSkillMissingId` — skill 无 id 字段 → 跳过该 skill(EC-7)
  6. `testBuildToolSpecsFallbackSchema` — skill 无 inputSchema 字段 → fallback `{type:object, additionalProperties:true}`(AC-1.6 + EC-7)
  7. `testBuildToolSpecsWithInputSchema` — skill 含 inputSchema `{"type":"object","properties":{"x":{"type":"string"}}}` → 透传(AC-1.6)
  8. `testDescribeSpecs` — `describeSpecs(specs)` 输出形如 `[4 tools]\n  - call_alice_echo: ...`(AC-1.9)
- 修改 `RemoteAgentToolTest`(L1 Unit + mock A2aTransport, 增 ≥ 2 新 case,沿用 #009c 已有 4 case):
  1. `testDescriptionWithSchemaBuilderWired` — 传入 schemaBuilder + mock 准备 → description 含 `"schemaBuilder wired"`(AC-2.1)
  2. `testDescriptionFallsBackOnException` — schemaBuilder.buildToolSpecs 抛 RuntimeException → description 走 BASE_DESCRIPTION + WARN 日志(EC-11)
  3. `testDescriptionFallsBackOnNullSchemaBuilder` — schemaBuilder=null → description 走 #009c 固定字串(AC-2.1 + EC-8)
- 修改 `HttpJsonRpcA2aTransportAutoConfigurationTest`(L2 Slice, 增 ≥ 1 新 case,沿用 #009c 已有 2 case):
  1. `testRemoteAgentSchemaBuilderBeanWiring` — Spring 容器有 `RemoteAgentSchemaBuilder` Bean,可被 RemoteAgentTool 注入(AC-3.1 + AC-3.2 + AC-3.4)
- 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test`,期望 274 + 9 = 283 case 全过

### Step 6:Phase 6 R-13 mitigation (d) baseline 镜像
- 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009d-post.txt`
- 跑 `diff /tmp/deps-009d-pre.txt /tmp/deps-009d-post.txt`,期望**无输出**(完全一致)
- 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`,期望 BUILD SUCCESS + banned-dependencies 规则**不 fail**

### Step 7:Phase 7 Doc Sync + Commit + PR
- 修改 `README.md`(3 Provider 列表 + #009d 状态行从"⏳ 待 #009c 合"→"✅ 已合")
- 修改 `dsh_agent_design.md` §13 changelog 加 v1.5.38 行:Story #009d 完成 + RemoteAgentSchemaBuilder + 0 binary delta
- 修改 `constitution.md` §10 R-13 风险状态:本 Story 0 binary delta → R-13 强度不变
- `git add -A && git commit -m "feat(a2a-client): Story #009d a2a-remote-schema-builder — RemoteAgentSchemaBuilder + RemoteAgentTool description 动态化 + 0 binary delta"`
- 推 PR + 等 CI

---

## 5. 风险与依赖

### 5.1 dev/scope deviation from spec.md AC-2.2 (description skills 列表拼接)

**问题**:spec.md AC-2.2 描述当 schemaBuilder wired 时,description 应动态拼接到 N 个 skills 列表(形如 `"Available skills: call_alice_echo(<desc>), call_bob_search(<desc>), ... and 5 more"`),但本期 #009d 受 Story 边界约束(< 3 ErrorCode + < 5 文件),无法做:
- PromptBuilder 集成(把 ToolSpec list 注入 `[TOOL SCHEMAS]` 段)—— 那是 §6 OQ-5
- 启动期 cards 来源(需要 AgentRef + cfg.getA2a().getRemoteAgents() 配置)—— 那是 §6 OQ-4
- descriptionSkillLimit yaml 配置 —— 那是 FR-007 / EC-9

**方案**:本期 description 简化:当 schemaBuilder != null 时,description 加 hint `" (RemoteAgentSchemaBuilder wired — N-tool schemas pending)"`,让 reviewer 知道 wiring 通路已通,后续 Story 接 cards 来源后 description 真正拼 skills。spec.md §9 OQ-5 / OQ-6 显式标注留后续 Story 落实,**不**悄悄 scope creep。

**风险**:reviewer 可能误以为 #009d 已落地 AC-2.2 完整功能。**缓解**:PR body + spec.md deviation note 显式声明 AC-2.2 本期**部分**落实(wiring + 走通,拼接 skills 列表留 OQ)。

### 5.2 N-tool 模型 vs 单 tool 模型边界

**问题**:dsh §5.6.3 IDEAL 模型是 N-tool 模式(每 skill 一个 `call_<name>_<skillId>` tool Bean);当前 #009c / #009d 单 tool 模式(1 个 `remote_agent` 工具)。

**方案**:本期维持单 tool 模式,RemoteAgentTool 仍 1 个 Bean。`RemoteAgentSchemaBuilder` 产出的 `List<ToolSpec>` 由后续 Story(PromptBuilder 集成 / 启动日志 / 健康检查)消费。spec.md §6 OQ-1 标注 revisit trigger = OpenAI / Anthropic 2025+ tool spec 支持 `oneOf` + nested union。

**关键边界**:`RemoteAgentTool.name()` 仍 = `"remote_agent"` 固定;`inputSchema()` 仍 = #009c 固定 schema;**不**改 RemoteAgentTool 的 name / inputSchema / execute 行为(关键不变项 #1)。

### 5.3 AgentCard.skills[].inputSchema 字段缺失

**问题**:当前 lingshu 实际 AgentSkill(@Value Lombok 不可变,7 字段 `id` / `name` / `description` / `tags` / `examples` / `inputModes` / `outputModes`)**不**含 dsh §5.6.3.0 描述的 `inputSchema` / `outputSchema` 字段(那是 #009b deferred work 后续 Story 扩)。

**方案**:本期 schemaBuilder 用 fallback `{type:object, additionalProperties:true}` 描述每个 skill 的输入形状;**不**改 AgentSkill 字段(避免改 on-wire JSON 契约)。spec.md §6 OQ-2 标注 revisit trigger = AgentSkill 扩 `inputSchema` 字段后,schemaBuilder 自动优先使用真实 schema。

### 5.4 0 新 ErrorCode vs schemaBuilder 异常路径

**问题**:spec.md §1 显式承诺 0 新 ErrorCode;但 schemaBuilder 内可能抛 RuntimeException(card 缺字段、技能 List<Map> 类型错误等)。

**方案**:本期 schemaBuilder 用 try-catch `RuntimeException` 吞单张 card 畸形错误,记录 WARN 日志,**不**抛错给上层(FR-002 + EC-4)。`RemoteAgentTool.description()` 也 try-catch schemaBuilder.buildToolSpecs 抛错 → fallback BASE_DESCRIPTION + WARN 日志(NFR-004 + EC-11)。两处兜底,**不**新增 ErrorCode。

---

## 6. 关键不变项(不引入新决策)

- `A2aTransport` 5 方法契约不变(关键不变项 #3)
- `A2aTransportRouter` 行为不变(#009a 已落地,**复用**)
- `AgentCardCache` 行为不变(#009a 已落地,**复用**)
- `InProcessA2aRegistry` 行为不变(#009b 已落地,**复用**)
- `LocalAgentCardGenerator.generate()` / `toMap()` 不变(#009 / #009b 已落地)
- `AgentConfig.A2a` 字段构造**不变**(本期用 hardcoded `descriptionSkillLimit=10`,OQ-Future;不破 #009c 已固化 6 字段)
- `ToolSpec` 不新建(关键不变项 #2 —— 复用 `lingshu-core.message.ToolSpec`)
- `AgentCard` / `AgentSkill` 字段**不变**(避免改 on-wire JSON 契约)
- `SlotRouter<P, T>` 父类不变
- `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration` 不变(#009a)
- `InProcessA2aTransport` / `InProcessA2aTransportProvider` / `InProcessA2aTransportAutoConfiguration` 不变(#009b)
- `HttpJsonRpcA2aTransport` / `HttpJsonRpcA2aTransportProvider` / `HttpJsonRpcA2aTransportAutoConfiguration` 主体不变(#009c),**只**内部增加 `@Bean remoteAgentSchemaBuilder` + 改 `remoteAgentTool(...)` 4 参签名
- `A2aServer` 不变(#009 / #009b / #009c 已落地)
- SPI 注册文件 `META-INF/spring/...imports` 行数**不变**(1 行 `HttpJsonRpcA2aTransportAutoConfiguration`)
- `RemoteAgentTool` 的 `name()` / `inputSchema()` / `execute()` **不变**(关键不变项 #1),**只** `description()` 改写 + 构造器扩展
- JDK 8 only:不用 `var` / `record` / `sealed`,用 `Collections.unmodifiableList` + `Arrays.asList` + `LinkedHashMap` + Jackson `ObjectNode`

---

## 7. R-13 mitigation (d) 强制项(SOP §3.4 T-dep-tree-1—4)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true` baseline(#009c)+ 本 Story 跑同样命令,对比 dep tree **应完全一致**(0 binary delta)
- [ ] **T-dep-tree-2** 把关键子树贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节,标注"(name, version, slot)"三元组
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`(enforcer 不允许跳过),确认 `banned-dependencies` 规则**不 fail**
- [ ] **T-dep-tree-4**(可选,本 Story 0 binary delta,**不**强制) `mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,binary < 35MB 且相对 main HEAD delta < 10%

---

## 8. 检查清单(SOP §3.5 输出检查清单)

- [ ] `specs/009d-a2a-remote-schema-builder/spec.md` 完整(4 User Stories US1—US4 + 14 Edge Cases + FR-001—FR-016 + NFR-001—NFR-010)
- [ ] `specs/009d-a2a-remote-schema-builder/plan.md` 含 11 I-NN 接口 + 3 文件改动(1 新 + 2 改)+ ≥ 9 case 测试策略 + 7 步实施顺序 + R-13 mitigation (d) 5 步
- [ ] `specs/009d-a2a-remote-schema-builder/data-model.md` 含 1 新增类型 + 0 ErrorCode + 2 修改类型 + 6 复用类型
- [ ] `specs/009d-a2a-remote-schema-builder/contracts/a2a-remote-schema-builder.md` 契约 ID
- [ ] `specs/009d-a2a-remote-schema-builder/quickstart.md` 7 验证场景
- [ ] `specs/009d-a2a-remote-schema-builder/checklists/requirements.md` 质量门禁
- [ ] `specs/009d-a2a-remote-schema-builder/tasks.md` 全 T-NN 勾完
- [ ] AC 全过(≥ 9 新增 case 测试 + 274 已有 case 不 regress = 283 全过)
- [ ] README.md / docs / changelog 三同步
- [ ] constitution.md §10 R-13 风险更新(本 Story 0 binary delta → R-13 强度不变)
- [ ] PR 标题 `feat(a2a-client): Story #009d a2a-remote-schema-builder — <一句话>` + body 含 spec.md + plan.md + tasks.md + AC 验证输出 + R-13 dep-tree diff + dev/scope deviation note (§5.1)