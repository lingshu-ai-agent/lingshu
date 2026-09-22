# Quickstart: Story #009d a2a-remote-schema-builder

**Story**: Story #009d a2a-remote-schema-builder
**Spec**: [`spec.md`](./spec.md)
**Plan**: [`plan.md`](./plan.md)

> 本节是实施期 manual verification 清单 + 7 个验证场景,与 §3 FR 1:1 对应 + 14 EC 全覆盖 + R-13 mitigation (d) 强制项。

---

## 1. 验证场景(7 个)

### Scenario 1: RemoteAgentSchemaBuilder happy path(US1 + AC-1.3—1.7 + VS-1)

**目标**:验证 `buildToolSpecs(...)` 对 2 cards × 2 skills 的标准输入,返 4 个 ToolSpec,按 name 字典序排序。

**步骤**:
1. `cd lingshu-a2a-client && mvn test -Dtest=RemoteAgentSchemaBuilderTest#testBuildToolSpecsHappyPath`
2. 观察输出:4 个 `ToolSpec`,名字依次 `call_alice_echo` / `call_alice_greet` / `call_bob_search` / `call_bob_summarize`(字典序)
3. 验证每个 ToolSpec.description 含 `(via alice: ...)` 或 `(via bob: ...)`

**期望**:
- ✅ 测试 PASS
- ✅ `assertThat(specs).hasSize(4)` + 名字 4 个(`call_alice_*` 在 `call_bob_*` 前)
- ✅ `specs.get(0).getName() == "call_alice_echo"`

### Scenario 2: buildToolSpecs 边界路径(EC-1 + EC-2 + EC-3 + EC-4 + EC-7)

**目标**:验证空 list / null / card 缺字段 / skill 类型错误 / skill 缺 id 等边界路径静默跳过,不抛异常。

**步骤**:
1. `mvn test -Dtest=RemoteAgentSchemaBuilderTest#testBuildToolSpecsEmptyList+testBuildToolSpecsNullInput+testBuildToolSpecsCardMissingName+testBuildToolSpecsSkillMissingId`
2. 观察:5 个 case 全部 PASS,无 NPE / IllegalArgumentException / ClassCastException
3. 验证:WARN 日志记录被跳过的 card / skill(如启用 logback DEBUG 级别可见)

**期望**:
- ✅ 5 个 case 全 PASS
- ✅ `buildToolSpecs(null)` 返 `Collections.emptyList()`(不抛 NPE)
- ✅ `buildToolSpecs([])` 返 `Collections.emptyList()`

### Scenario 3: buildToolSpecs schema 处理(AC-1.6 + EC-7)

**目标**:验证 fallback `{type:object, additionalProperties:true}` 与含 `inputSchema` 字段的透传路径。

**步骤**:
1. `mvn test -Dtest=RemoteAgentSchemaBuilderTest#testBuildToolSpecsFallbackSchema+testBuildToolSpecsWithInputSchema`
2. 观察:fallback case 的 ToolSpec.inputSchema 是 `{type:object, additionalProperties:true}` ObjectNode;透传 case 的 ToolSpec.inputSchema 是 skill 的 inputSchema
3. 用 `assertThat(specs.get(0).getInputSchema().get("type").asText()).isEqualTo("object")` 验证

**期望**:
- ✅ 2 个 case 全 PASS
- ✅ fallback case:`inputSchema.path("additionalProperties").asBoolean() == true`
- ✅ 透传 case:`inputSchema.path("properties").path("x").path("type").asText() == "string"`

### Scenario 4: RemoteAgentTool description hint(US2 + AC-2.1 + 关键不变项 #1)

**目标**:验证 RemoteAgentTool 3 参构造器生效,description 含 `"RemoteAgentSchemaBuilder wired"` hint;2 参构造器保留(向后兼容)。

**步骤**:
1. `mvn test -Dtest=RemoteAgentToolTest`
2. 观察:已有 4 case(#009c)全 PASS + 新增 ≥ 2 case 全 PASS
3. 验证:`testDescriptionWithSchemaBuilderWired` 中 `tool.description()` 包含 `"RemoteAgentSchemaBuilder wired"`
4. 验证:`testDescriptionWithNullSchemaBuilder` 中 `tool.description()` 是 #009c 固定字串

**期望**:
- ✅ ≥ 6 case 全 PASS(原有 4 + 新增 2)
- ✅ `name() == "remote_agent"`(关键不变项 #1)
- ✅ `inputSchema()` 仍是 #009c 固定 schema(关键不变项 #1)

### Scenario 5: HttpJsonRpcA2aTransportAutoConfiguration Bean wiring(US3 + AC-3.1 + AC-3.2)

**目标**:验证 `RemoteAgentSchemaBuilder` Bean 由 Spring 暴露,且 RemoteAgentTool 注入同一实例。

**步骤**:
1. `mvn test -Dtest=HttpJsonRpcA2aTransportAutoConfigurationTest`
2. 观察:已有 2 case(#009c)全 PASS + 新增 ≥ 1 case 全 PASS
3. 验证 `testRemoteAgentSchemaBuilderBeanWiring`:Spring 容器 `RemoteAgentSchemaBuilder` Bean 非 null,且 `remoteAgentTool.getSchemaBuilder()` 与容器 Bean `==`

**期望**:
- ✅ ≥ 3 case 全 PASS
- ✅ `@Autowired RemoteAgentSchemaBuilder schemaBuilder` 不为 null
- ✅ wiring 通路走通

### Scenario 6: 启动日志(可选,US3 + VS-4 + T019)

**目标**:观察 demo 启动日志含 `RemoteAgentSchemaBuilder` Bean 初始化。

**步骤**(若有 `lingshu-examples/demo-empty`):
1. `mvn -pl lingshu-examples exec:java -Dexec.mainClass="ai.lingshu.examples.Main" -Dexec.args="--config src/main/resources/application.yml"`
2. 观察启动日志

**期望**:
- ✅ `[A2aTransport] resolved 3 provider(s)`(沿用 #009c 行为,不变)
- ✅ `[RemoteAgentSchemaBuilder]` Bean 初始化成功(若有 INFO 日志)
- ✅ 若 demo 输出 RemoteAgentTool description:含 `"RemoteAgentSchemaBuilder wired"` hint

**若无 demo**:跳过此场景。

### Scenario 7: 全部测试不 regress(NFR-005 + T014)

**目标**:跑全模块测试,验证 274 已有 case + ≥ 9 新增 case 全过。

**步骤**:
1. `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test`
2. 观察:`Tests run: 283, Failures: 0, Errors: 0, Skipped: 0`

**期望**:
- ✅ 283 case 全过(0 fail / 0 error / 0 skipped)
- ✅ build SUCCESS

---

## 2. R-13 mitigation (d) 强制项(5 步)

### Step 1: dep-tree baseline capture
- `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009d-pre.txt`
- **期望**:含 #009a + #009b + #009c 已落地的 grpc-stub + protobuf-java + os-maven-plugin + protobuf-maven-plugin

### Step 2: dep-tree post-implementation
- 实施 Phase 2—5 后跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009d-post.txt`
- **期望**:与 `deps-009d-pre.txt` **完全一致**

### Step 3: dep-tree diff
- `diff /tmp/deps-009d-pre.txt /tmp/deps-009d-post.txt`
- **期望**:**空输出**(0 binary delta)

### Step 4: verify enforcer 不 fail
- `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`
- **期望**:BUILD SUCCESS + `banned-dependencies` enforcer 规则**不** fail

### Step 5: PR body 末尾
- 把 `deps-009d-pre.txt` 与 `deps-009d-post.txt` 关键子树贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节
- **期望**:PR review 通过 reviewer 验证 dep tree diff = 0

---

## 3. EC 全覆盖对照表(14 EC)

| EC | 验证场景 | 期望 |
|---|---|---|
| **EC-1** cards == null | Scenario 2 `testBuildToolSpecsNullInput` | `buildToolSpecs(null)` 返 `[]` 不抛 NPE |
| **EC-2** cards.isEmpty() | Scenario 2 `testBuildToolSpecsEmptyList` | `buildToolSpecs([])` 返 `[]` |
| **EC-3** card 缺 name | Scenario 2 `testBuildToolSpecsCardMissingName` | 跳过该 card,无 NPE |
| **EC-4** card.skills 类型错误(String 而非 List) | (覆盖在 happy path 中间接触发 try-catch)| try-catch RuntimeException,WARN 日志,跳过该 card |
| **EC-5** skill 缺 description | Scenario 1 中 card 含 skill 无 description | description = `(via <agent>: <agent.desc>)`,`[no description]` 替换空 skillDesc |
| **EC-6** card 缺 description | Scenario 1 中 card 无 description | description = `<skill.desc> (via <agent>: [no description])` |
| **EC-7** skill 缺 inputSchema | Scenario 3 `testBuildToolSpecsFallbackSchema` | fallback `{type:object, additionalProperties:true}` |
| **EC-8** RemoteAgentTool 2 参构造器调用 | Scenario 4 `testDescriptionWithNullSchemaBuilder` | description 走 BASE_DESCRIPTION(不变)|
| **EC-9** description 截断 N 个 | (dev/scope:本期 hint 简化版,不真拼 skills 列表;留 OQ-Future) | 后续 Story 验证 |
| **EC-10** schemaBuilder != null 但 buildToolSpecs 返 [] | (dev/scope:本期 description hint 始终返回,不看 specs 大小) | 后续 Story 验证 |
| **EC-11** schemaBuilder.buildToolSpecs 抛 RuntimeException | Scenario 4 `testDescriptionFallsBackOnException` | description 走 BASE_DESCRIPTION + WARN 日志 |
| **EC-12** cards 含重复 agentName | (覆盖在 happy path 边界)| spec 重复并排,不抛错 |
| **EC-13** ToolSpec.name 含特殊字符 | (边界场景) | 透传,WARN 日志,不 fail-fast |
| **EC-14** RemoteAgentSchemaBuilder 注入失败 | (理论不会,因 AutoConfiguration 自动 wire)| schemaBuilder = null,走 fallback |

---

## 4. 关键不变项 quick check

| 不变项 | 验证方式 |
|---|---|
| `A2aTransport` 5 方法契约不变 | `mvn -pl lingshu-a2a-client test`(已有 274 case 全过)|
| `A2aTransportRouter` 行为不变 | `testMultiProviderCoexistence` + `testResolveHttpJsonRpc` 仍 PASS |
| `AgentCardCache` 行为不变 | `mvn -pl lingshu-a2a-client test`(已有 cache 相关 case 全过)|
| `InProcessA2aRegistry` 行为不变 | `mvn -pl lingshu-a2a-client test`(已有 case 全过)|
| `AgentConfig.A2a` 字段不变 | `mvn -pl lingshu-core test`(defaults() 静态方法不破坏)|
| `ToolSpec` 不新建(关键不变项 #2)| `RemoteAgentSchemaBuilder` import `ai.lingshu.core.message.ToolSpec`,**不**新建同名类 |
| `AgentCard` / `AgentSkill` 字段不变 | `mvn -pl lingshu-a2a-server test` 全过 |
| `RemoteAgentTool` name/inputSchema/execute 不变(关键不变项 #1)| Scenario 4 中 `testInputSchemaFixedShape` + `testExecuteHappyPath` 仍 PASS |
| `RemoteAgentTool` 2 参构造器保留 | Scenario 4 中 `testDescriptionWithNullSchemaBuilder` PASS |
| SPI 注册文件 imports 行数不变 | `cat lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 仍 1 行 |

---

## 5. dev/scope 偏差 quick check

**AC-2.2 / AC-2.3 完整 skills 列表拼接**:本期**不**落地,留 OQ-Future(plan.md §5.1)。**理由**:Story 边界 < 3 ErrorCode + < 5 文件;完整拼接需要 AgentRef + cfg.getA2a().getRemoteAgents() + PromptBuilder 集成。

**本期 description 行为**:
- `schemaBuilder == null` → BASE_DESCRIPTION(#009c 固定字串)
- `schemaBuilder != null` → BASE_DESCRIPTION + `" (RemoteAgentSchemaBuilder wired — N-tool schemas pending)"`
- `schemaBuilder.buildToolSpecs(...)` 抛 RuntimeException → BASE_DESCRIPTION + WARN 日志

**reviewer 验证**:PR body + spec.md §9 OQ-1 / OQ-5 / OQ-6 显式标注 + plan.md §5.1 deviation note。

---

## 6. 故障排查

| 症状 | 可能原因 | 解决方案 |
|---|---|---|
| `mvn -pl lingshu-a2a-client compile` 失败:找不到 `ToolSpec` | 未 import `ai.lingshu.core.message.ToolSpec` | 加 import(FR-006 + 关键不变项 #2)|
| `RemoteAgentSchemaBuilderTest` NPE on `ObjectMapper.convertValue` | skill.inputSchema 是 String 而非 Map | test fixture 用 `Map<String, Object>` 而非 String |
| `HttpJsonRpcA2aTransportAutoConfigurationTest.testRemoteAgentSchemaBuilderBeanWiring` 失败:NoSuchBeanDefinition | 测试 classes 列表缺 `HttpJsonRpcA2aTransportAutoConfiguration` | 加进 `@SpringBootTest(classes = {...})` |
| `mvn verify` 失败:banned-dependencies | 不小心引入新依赖 | 跑 `mvn dependency:tree -Dverbose=true` 检查;R-13 mitigation (d) 强度最弱 0 binary delta |
| CI matrix JDK 8 job 失败 | a2a-client compile target 已升 1.11(沿用 #009c)| 加 `-pl !lingshu-a2a-client` 到 CI matrix |

---

## 7. 总结

**全部 7 验证场景通过 + R-13 mitigation (d) 5 步全过 + 14 EC 全覆盖 + 10 不变项 quick check 全过 + dev/scope 偏差显式标注** 即 Story #009d 完成 ✅

**owner**: Claude Code(根据用户 2026-09-22 会话反馈)
**关联 PR**: 待 T024—T027 提交后产出
**关联 Story**: 前序 #009a / #009b / #009c 全部 merged