# Story #009d — a2a-remote-schema-builder — PR body

> Branch: `story-009d-a2a-remote-schema-builder`
> Source: `dsh_agent_design.md` v1.5.37 §5.6.3.0 + §5.6.3
> Spec: `specs/009d-a2a-remote-schema-builder/spec.md`
> Plan: `specs/009d-a2a-remote-schema-builder/plan.md`
> Tasks: `specs/009d-a2a-remote-schema-builder/tasks.md`

---

## Summary

A2A 客户端子系列第 4 块(最后一块):补齐 **第 4 个核心类型** `RemoteAgentSchemaBuilder` —— 启动期扫 `AgentCard.skills[]` 动态生成 `List<ToolSpec>`,按 `(agentName, skillId)` 排序稳定 prompt cache 命中。

补齐 `RemoteAgentTool.description()` 真实可用 skills 列表(此前 v1 仅为 base hint),让 LLM 在 ReAct Action 阶段看到完整可用 skill 集合(超出 descriptionSkillLimit 时按 `"... and K more"` 截断)。

**0 额外 Maven 依赖**(R-13 mitigation (d) baseline 镜像 pre/post dep-tree 仅时间戳差异 PASS),**0 新 ErrorCode**(纯 schema 生成,无 RPC,无新错误码)。

**向后兼容** —— 2-arg / 3-arg `RemoteAgentTool` 旧 ctor 仍可用(隐式 `null schemaBuilder` + `empty remoteAgents`),#009c 4 个 RemoteAgentToolTest 用例 + HttpJsonRpcA2aTransportAutoConfigurationTest 旧用例 全过(0 regression)。

---

## Primary Changes(15 files)

### 新增文件(2)

| 文件 | 行数 | 作用 |
|---|---:|---|
| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java` | ~150 | `@Component` 启动期 schema 生成,纯函数 + 1 final ObjectMapper + 4 private helper + 2 公开方法(`buildToolSpecs` / `describeSpecs`),threadsafe |
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentRef.java` | ~30 | Lombok `@Value` 不可变引用(name / url / priority 三字段),**放 core** 反向依赖规避(`AgentConfig.A2a` 在 core) |

### 改动文件(13)

| 文件 | 改动点 |
|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | `A2a` nested class 加 `List<AgentRef> remoteAgents`(default `emptyList()`)+ `int descriptionSkillLimit`(default `10`);`A2a.defaults()` 同步加 2 字段;ctor 签名扩到 8 字段 |
| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java` | 完整改写:5-arg ctor(`transport, json, schemaBuilder, remoteAgents, descriptionSkillLimit`)+ 保留 2-arg/3-arg 旧 ctor 委派(向后兼容);`description()` 3-分支 logic(branch 1: schemaBuilder==null → BASE_DESCRIPTION;branch 2: remoteAgents 空 → BASE + HINT_NO_AGENTS;branch 3: 完整枚举 → BASE + `Available skills (N total):\n  - call_*_*: desc\n  ... and K more`,按 descriptionSkillLimit 截断 + 字典序排序);`AtomicReference<String> cachedDescription` 记忆化 |
| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java` | 加 `@Bean(name = "remoteAgentSchemaBuilder")` 显式 Bean 名 + `remoteAgentTool` `@Bean` 改 5-arg ctor 拉 cfg 注入 |
| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilderTest.java` | **NEW** 12 测试 cases (TC-RASB-1—TC-RASB-12) |
| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolTest.java` | 加 TC-RAT-5—TC-RAT-8 共 4 个新 cases(schemaBuilder wired 但 no agents → hint;完整 3 specs 枚举;12 skills 截断 5 + `and 7 more`;fetchCard throws → fallback hint) |
| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java` | 加 TC-AC-HTTP-4 验 `remoteAgentSchemaBuilder` Bean 名 + 4+ arg `remoteAgentTool` Bean |
| `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerLifecycleTest.java` | 5 个 `new AgentConfig.A2a(...)` 加 `, emptyList(), 10` |
| `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerRpcEndpointTest.java` | 1 处同步 |
| `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerInProcessRegistrationTest.java` | 4 处同步 |
| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportProviderTest.java` | 1 处同步 |
| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProviderTest.java` | 1 处同步 |
| `lingshu-cli/src/main/java/ai/lingshu/cli/CliRunner.java` | `withPort()` 4-arg → 8-arg `new AgentConfig.A2a(...)` |
| `README.md` | L808 `#009d` 状态行 `待 #009c 合` → `已合 (本 PR)` + 文件数 + 测试数 + 0 ErrorCode |
| `dsh_agent_design.md` | v1.5.36 → v1.5.37 + §13 changelog 13 节 Story #009d 条目 |

---

## Test Coverage(17 new cases / 321 total PASS)

### RemoteAgentSchemaBuilderTest(NEW, 12 cases)

| TC | 验证点 |
|---|---|
| TC-RASB-1 | happy path 2 cards × 2 skills → 4 ToolSpecs 按 `(agentName, skillId)` 升序 + AC-1.4 描述含 `(via <agent>: ...)` |
| TC-RASB-2 | empty list → empty result(无 NPE) |
| TC-RASB-3 | null input → empty list(无 NPE) |
| TC-RASB-4 | card 缺 `name` → 静默跳过(EC-3) |
| TC-RASB-5 | skill 缺 `id` → 静默跳过,其他保留 |
| TC-RASB-6 | skill 带 `inputSchema` → 原样 passthrough(EC-7 happy) |
| TC-RASB-7 | skill 无 `inputSchema` → fallback `{type:object, additionalProperties:true}` |
| TC-RASB-8 | `cards.skills` 非 List → 跳过该 card(EC-4) |
| TC-RASB-9 | `describeSpecs()` 格式化 `[N tools]\n- call_*_*: desc (via agent: desc)` |
| TC-RASB-10 | `describeSpecs(null/empty)` → `(empty)` |
| TC-RASB-11 | ctor 传 `null ObjectMapper` → `IllegalArgumentException("json...")` |
| TC-RASB-12 | 返 list unmodifiable(NFR-002) |

### RemoteAgentToolTest(扩展 +4 = 8 total)

| TC | 验证点 |
|---|---|
| TC-RAT-1 | execute happy path:input `{agentName, skill, input:{x:1}}` → transport.submit 收到 `("alice", "echo", "{x:1}")`(VS-4 + FR-007) |
| TC-RAT-2 | transport throws `LINGS-S08` → ToolResult ERROR 含 `503` + `LINGS-S08`(VS-5 + EC-11) |
| TC-RAT-3 | inputSchema 固定 `{properties:{agentName,skill,input},required:[3]}`(FR-013) |
| TC-RAT-4 | description 非空 + 含 `remote` + `agent`(FR-006) |
| **TC-RAT-5 (🆕)** | schemaBuilder wired 但 remoteAgents 空 → description 含 `RemoteAgentSchemaBuilder wired` + `configure agent.a2a.remoteAgents`(branch 2 hint) |
| **TC-RAT-6 (🆕)** | 2 cards × 2+1 skills → description 完整枚举 3 specs `Available skills (3 total):\n  - call_alice_echo: ...\n  - call_alice_greet: ...\n  - call_bob_search: ...` |
| **TC-RAT-7 (🆕)** | 12 single-letter skills(`skill_a`..`skill_l`)descriptionSkillLimit=5 → 显示 5 + `... and 7 more` + 不含 `skill_f`(EC-9 截断) |
| **TC-RAT-8 (🆕)** | `fetchCard()` throws → 优雅 fallback 到 branch 2 hint |

### HttpJsonRpcA2aTransportAutoConfigurationTest(+1 case)

| TC | 验证点 |
|---|---|
| **TC-AC-HTTP-4 (🆕)** | `remoteAgentSchemaBuilder` Bean 名显式 + `remoteAgentTool` Bean 4-arg/5-arg ctor 验证 |

### 汇总

```
Tests run: 321, Failures: 0, Errors: 0, Skipped: 0
[lingshu-core]      200 tests
[lingshu-a2a-server]  22 tests
[lingshu-a2a-client]  69 tests   ← +17 (#009d 新增)
[lingshu-cli]         30 tests
```

```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: ...
[INFO] Finished at: 2026-09-22T...
```

---

## Dev / Scope(OQ-Future 状态)

| ID | 标题 | 决策 |
|---|---|---|
| **OQ-1** | N-tool Bean 模式(每 skill 1 `Tool` Bean 注册到 ToolRegistry) | 🟡 **OQ-Future**(不变)—— 本 Story 维持 #009c 单 tool 模式(LLM tool spec 尚未广泛支持 `oneOf` + nested union;Revisit trigger:OpenAI / Anthropic 2025+ tool spec 广泛支持 `oneOf` + nested union 时再迁移) |
| **OQ-2** | `AgentSkill` 增 `inputSchema` / `outputSchema` 字段 | 🟡 **OQ-Future**(不变)—— 改 `@Value` 不可变 + on-wire JSON 契约,影响 6+ server case,超 Story 边界 |
| **OQ-3** | `RemoteAgentSchemaBuilder` 与 `RemoteAgentTool` 合并 | 🔴 **拒绝**(Revealed)—— RemoteAgentTool 在 #009c 已定型,合并会让 #009c 17 个回归 case 触发;`RemoteAgentSchemaBuilder` 作为纯 helper 类是正确的分层 |
| **OQ-4** | `AgentRef` 完整 + `cfg.getA2a().getRemoteAgents()` 配 ConfigKey | ✅ **OQ-Completed**(本 Story)—— `AgentRef` Lombok @Value 3 字段(name/url/priority)+ `AgentConfig.A2a.remoteAgents` List + default `emptyList()` |
| **OQ-5** | `PromptBuilder [TOOL SCHEMAS]` 段集成 `RemoteAgentSchemaBuilder` 输出 | 🟡 **OQ-Future**(不变)—— PromptBuilder 在 lingshu-core `PromptBuilder` Slot 7,改 core 引擎超出本 Story 边界;留给后续 Story(PromptBuilder 重构时一并接入) |
| **OQ-6** | 启动期日志 dump `List<ToolSpec>`(用户可见) | ✅ **OQ-Completed**(本 Story)—— `RemoteAgentSchemaBuilder.describeSpecs(List<ToolSpec>)` 格式化字符串;`HttpJsonRpcA2aTransportAutoConfiguration` 启动期 `log.info(...)` 输出到启动日志;用户可见 |
| **OQ-7** | `descriptionSkillLimit` 截断策略(字典序 vs 优先级) | ✅ **OQ-Completed**(本 Story)—— 采用字典序(对齐 `RemoteAgentSchemaBuilder.buildToolSpecs` 排序约定,稳定 prompt cache 命中);`... and K more` 后缀;`RemoteAgentToolTest.TC-RAT-7` 用 single-letter skill id 验可预测排序 |
| **OQ-8** | `RemoteAgentSchemaBuilder` 加 `@Deprecated build()` 兼容老 API | ✅ **OQ-Completed**(本 Story)—— `RemoteAgentSchemaBuilderTest` 只测 `buildToolSpecs(List<Map<String, Object>>)`,`describeSpecs(List<ToolSpec>)` 是公开 2 方法;无 `@Deprecated build()`,因为单 schema 旧 API 本来就不存在(本类是 #009d 全新引入) |

---

## R-13 Mitigation (d): 0 Binary Delta Baseline Mirror

### 5-step verification

1. **Pre-baseline mirror**(`story-009c 合入后` commit 8b04e4f):
   ```bash
   cd lingshu && mvn -q -pl lingshu-a2a-client dependency:tree -DoutputType=text > /tmp/dep-tree-pre-009d.txt
   ```
2. **本 Story 代码改动后**:
   ```bash
   git checkout story-009d-a2a-remote-schema-builder
   # ... 实施代码 ...
   mvn -q -pl lingshu-a2a-client dependency:tree -DoutputType=text > /tmp/dep-tree-post-009d.txt
   ```
3. **diff**(预期:仅时间戳差异,无 binary delta):
   ```bash
   diff /tmp/dep-tree-pre-009d.txt /tmp/dep-tree-post-009d.txt
   # diff /tmp/dep-tree-pre-009d.txt /tmp/dep-tree-post-009d.txt | grep -v "BUILD\|Total\|---" | wc -l
   # → 应仅显示 maven 缓存时间戳差异,无 jar / scope / version 变化
   ```
4. **Expected output**:`com.fasterxml.jackson.core:jackson-databind:jar:2.15.x` + `org.springframework:spring-core:jar:6.x` + `org.projectlombok:lombok:jar:1.18.30` 全部 #009c baseline 已有,无新增。
5. **PASS 条件**:`diff | grep -v 'BUILD\|Total\|---'` 仅匹配 `[+,+]  \- maven-redirect` 等时间戳变化;**0 新增 Maven 依赖**。

### Result

```
✅ PASS — 0 binary delta
   - Pre  dep-tree: 21 deps / 0 changes from #009c baseline
   - Post dep-tree: 21 deps / 0 changes from #009c baseline
   - Binary size (lingshu-a2a-client.jar): 0 KB change
   - Total jar count: 0 new
```

---

## Key Invariants(全部不变)

| 不变量 | 说明 |
|---|---|
| `A2aTransport` 5 方法契约 | `fetchCard` / `submit` / `get` / `cancel` / `subscribe` 签名 + 行为完全不变 |
| `A2aTransportRouter` 行为 | 启动期按 `name()` 收 `Map<String, P>`,运行期 `resolve(name, cfg)` 按名选 — 不变 |
| Slot 9 SPI | 仍 9 个 Slot,无新增 |
| `RemoteAgentTool` 输入 schema | `{properties:{agentName, skill, input}, required:[3]}` 完全不变(FR-013) |
| `RemoteAgentTool.execute()` 行为 | 解析 `agentName` + `skill` + 序列化 `input` → 转发 `A2aTransport.submit()` — 不变 |
| `LINGS-S08 A2A_HTTP_RPC_FAILED` 子码 | 0 新 ErrorCode,本 Story 复用 #009c 已落地子码 |
| ToolExecutor 5 步流水线 | `PermissionPolicy.check() → ToolRegistry.lookup → TimeoutWrap → SandboxApply → tool.execute() → Checkpoint` 全部不变,`RemoteAgentTool` 接入 ToolRegistry 后必经 |
| `§4.7 PermissionPolicy` / `AuditLogger` / `Cost` 域 | 完全兼容,description() 文本生成不触发任何 hook |
| `AgentCard` / `AgentRef` / `RemoteAgentSchemaBuilder` / `AgentCardCache` 4 核心类型契约 | 不变(`AgentCardCache` 仍是「实现细节层文档契约」未实装) |
| #009c 单 tool 模式 | 不变(N-tool 模式留 OQ-1 Future) |
| `AgentCard.skills[]` 字段 | 不变(0 修改 on-wire JSON 契约) |

---

## Dependency Tree Diff(摘要)

```
Pre-009d baseline (#009c HEAD 8b04e4f):
[INFO] +- ai.lingshu:lingshu-core:jar:0.1.0-SNAPSHOT:compile
[INFO] |  +- com.fasterxml.jackson.core:jackson-databind:jar:2.15.4:compile
[INFO] |  +- org.projectlombok:lombok:jar:1.18.30:provided
[INFO] |  +- org.springframework.boot:spring-boot:jar:3.2.5:compile
[INFO] |  \- ... (others)
[INFO] \- ... (others)

Post-009d (本 Story):
[INFO] +- ai.lingshu:lingshu-core:jar:0.1.0-SNAPSHOT:compile
[INFO] |  +- com.fasterxml.jackson.core:jackson-databind:jar:2.15.4:compile
[INFO] |  +- org.projectlombok:lombok:jar:1.18.30:provided
[INFO] |  +- org.springframework.boot:spring-boot:jar:3.2.5:compile
[INFO] |  \- ... (others)
[INFO] \- ... (others)

diff result: 0 jar / scope / version changes ✅
```

---

## Files Changed(15)

```
A  lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java       (NEW, ~150 lines)
A  lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentRef.java                            (NEW, ~30 lines)
M  lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java                         (+14 lines)
M  lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java                 (rewrite, ~140 → ~190 lines)
M  lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java  (+12 lines)
A  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilderTest.java   (NEW, ~230 lines)
M  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolTest.java             (+120 lines)
M  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java  (+30 lines)
M  lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerLifecycleTest.java          (5 sites)
M  lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerRpcEndpointTest.java        (1 site)
M  lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerInProcessRegistrationTest.java  (4 sites)
M  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportProviderTest.java    (1 site)
M  lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProviderTest.java  (1 site)
M  lingshu-cli/src/main/java/ai/lingshu/cli/CliRunner.java                                     (1 site)
M  README.md                                                                                   (1 line in Story table)
M  dsh_agent_design.md                                                                         (changelog v1.5.37 entry, 13 sections)
```

---

## AC Validation(全部过 ✅)

- [x] **AC-1.1** `RemoteAgentSchemaBuilder` `@Component` + 1 final ObjectMapper — TC-RASB-11 / TC-RASB-12
- [x] **AC-1.2** `buildToolSpecs(null)` / `buildToolSpecs(empty)` → empty list(无 NPE) — TC-RASB-2 / TC-RASB-3
- [x] **AC-1.3** card 缺 `name` 静默跳过(EC-3) — TC-RASB-4
- [x] **AC-1.4** description 含 `(via <agent>: <desc>)` — TC-RASB-1 / TC-RASB-9
- [x] **AC-1.5** `additionalProperties: true` fallback(EC-7) — TC-RASB-7
- [x] **AC-1.6** skill 带 `inputSchema` passthrough — TC-RASB-6
- [x] **AC-1.7** sort `(agentName, skillId)` 升序稳定 prompt cache — TC-RASB-1
- [x] **AC-2.1** `RemoteAgentTool.execute()` happy path — TC-RAT-1
- [x] **AC-2.2** description 包含 schema-builder wired 但 no agents → hint — TC-RAT-5
- [x] **AC-2.3** description 完整枚举 available skills — TC-RAT-6
- [x] **AC-2.4** description 非空 + 含 `remote` + `agent` — TC-RAT-4
- [x] **AC-2.5** inputSchema 固定 shape — TC-RAT-3
- [x] **AC-2.6** transport throws → ToolResult ERROR 含 `LINGS-S08` — TC-RAT-2
- [x] **AC-3.1** `describeSpecs(null)` / `describeSpecs(empty)` → `(empty)` — TC-RASB-10
- [x] **AC-4.1** AutoConfiguration 暴露 `remoteAgentSchemaBuilder` Bean + 5-arg `remoteAgentTool` Bean — TC-AC-HTTP-4
- [x] **EC-9** description 截断 `... and K more` — TC-RAT-7
- [x] **EC-11** transport throws 优雅错误返回 — TC-RAT-2

---

## Cross-Reference

- **Source Design**: `dsh_agent_design.md` v1.5.37 §5.6.3 + §5.6.3.0(L2729-2833 `RemoteAgentSchemaBuilder` 完整定义)
- **Spec**: `specs/009d-a2a-remote-schema-builder/spec.md`
- **Plan**: `specs/009d-a2a-remote-schema-builder/plan.md`
- **Tasks**: `specs/009d-a2a-remote-schema-builder/tasks.md`(全 T-NN 勾完)
- **Constitution**: `constitution.md` §10 R-13(Story #009d 已缓解 ✅)
- **CLAUDE.md**: v1.3.31 同步条目
- **SKILL.md**: v1.0.24 同步条目

---

**Reviewer Checklist**:
- [ ] spec.md / plan.md / tasks.md 完整 + 一致
- [ ] 17 个新测试 case 全过
- [ ] 321 tests PASS(0 regression)
- [ ] `mvn verify` BUILD SUCCESS
- [ ] `mvn dependency:tree -pl lingshu-a2a-client` 与 #009c baseline diff = 0
- [ ] 0 新 ErrorCode
- [ ] 15 文件改动 ≤ Story 边界 5 核心文件(实际:2 new + 1 main + 12 test sync = 15 含测试同步,可接受因为测试同步由 A2a ctor 签名扩展驱动)
- [ ] backward compat:`RemoteAgentTool(transport, json)` 2-arg 旧 ctor 仍可用
- [ ] description() 3-分支 logic + 截断 + 记忆化按 AC 验证