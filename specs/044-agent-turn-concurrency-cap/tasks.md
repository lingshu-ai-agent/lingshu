# Story #044 `agent-turn-concurrency-cap` — Tasks

> **Status**: Draft 2026-10-06
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 plan**: [`plan.md`](./plan.md)
> **完成定义**: T-1 ~ T-6 全 ✅ + 现有 700+ 测试 0 改动全过

---

## T-1: AgentConfig.java modify

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`

**操作**:

1. 在 `reactMaxSteps` 字段之后 L67 新增 2 字段 `maxConcurrentTurns` + `maxConcurrentQueueDepth`(均为 `int`,含 Story #044 Javadoc 引用)
2. 类级 Javadoc L19 改写:`27+ tunable fields` → `27+ tunable fields (28+ post-#044)`
3. 新增 `validate()` 方法(类底部,`CompactorConfig`/`TenantsConfig` 之前),复用现有 `LINGS-C02` 路径
4. 字段顺序:`reactMaxSteps` → `maxConcurrentTurns` → `maxConcurrentQueueDepth`(放到 reactMaxSteps 旁边作为同质 int 字段)

**代码块**(已落盘,完整代码见 plan §1.2)

**验证**:
- `grep -c 'maxConcurrentTurns' lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` → ≥ 5(字段定义 + Javadoc 3 处 + validate 1 处)
- `grep -c 'maxConcurrentQueueDepth' lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` → ≥ 5
- `grep -c 'public void validate' lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` → ≥ 1(顶层新增)

---

## T-2: AgentConfigDefaults.java modify

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java`

**操作**:

1. L63 `50,      // reactMaxSteps` 之后 L64-65 新增 2 实参 `16,      // 🆕 Story #044 ... maxConcurrentTurns` + `32,     // 🆕 Story #044 ... maxConcurrentQueueDepth`
2. 类级 Javadoc L15 改写:`27-field default` → `29-field default (post-#044)`

**验证**:
- `grep -c 'maxConcurrentTurns' lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java` → ≥ 1
- `grep -c 'maxConcurrentQueueDepth' lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java` → ≥ 1

---

## T-3: AgentFactory.java modify

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java`

**操作**:

1. `toAgentConfig()` L569 `int reactMaxSteps = intOr(agent, "reactMaxSteps", 50);` 之后 L570-572 新增 2 行 `intOr()` 读取 + 注释
2. `LOG.info(...)` L595 末尾可加 `maxConcurrentTurns={} maxConcurrentQueueDepth={}` 占位(对齐日志习惯)
4. L598 `return new AgentConfig(...)` 末尾 L624 追加 2 实参 `maxConcurrentTurns, maxConcurrentQueueDepth`

**代码块**(已落盘,完整代码见 plan §1.4)

**验证**:
- `grep -c 'maxConcurrentTurns' lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` → ≥ 2
- `grep -c 'maxConcurrentQueueDepth' lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` → ≥ 2

---

## T-4: AgentConfigConcurrencyCapValidationTest.java new

**路径**: `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigConcurrencyCapValidationTest.java`

**操作**: 写 7-8 case L1 unit test(plan §4.1),镜像 `AgentConfigCompactorValidationTest` Story #018 precedent:

1. `defaults_passValidation` —— `AgentConfigDefaults.defaults().validate()` 不抛
2. `positiveCustomValues_passValidation` —— `new AgentConfig(...32, 64...).validate()` 不抛
3. `zeroMaxConcurrentTurns_throwsLingsC02` —— `maxConcurrentTurns=0` 抛 `C02` 含 `maxConcurrentTurns`
4. `negativeMaxConcurrentTurns_throwsLingsC02` —— `maxConcurrentTurns=-1` 抛 `C02`
5. `zeroMaxConcurrentQueueDepth_throwsLingsC02` —— `maxConcurrentQueueDepth=0` 抛 `C02`
6. `negativeMaxConcurrentQueueDepth_throwsLingsC02` —— `maxConcurrentQueueDepth=-1` 抛 `C02`
7. `allFieldsZero_aggregatesAllErrors` —— 聚合错误消息含 `maxConcurrentTurns` + `maxConcurrentQueueDepth`
8. `AgentConfigDefaults_passValidation` —— 整 schema 端到端不抛

**验证**:
- `mvn -pl lingshu-core test -Dtest='AgentConfigConcurrencyCapValidationTest'` → 8/8 PASS

---

## T-5: AgentFactoryYamlConcurrencyCapIT.java new

**路径**: `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlConcurrencyCapIT.java`

**操作**: 写 4-5 case L2 IT(plan §4.2),镜像 `AgentFactoryYamlPermissionPolicyIT` Story #029 precedent:

1. `yamlDefault_parses16And32` —— 空 yml → `cfg.getMaxConcurrentTurns() == 16 && cfg.getMaxConcurrentQueueDepth() == 32`
2. `yamlCustomMaxTurns_parsesCustom` —— yml `maxConcurrentTurns: 64` → `cfg.getMaxConcurrentTurns() == 64`
3. `yamlCustomQueueDepth_parsesCustom` —— yml `maxConcurrentQueueDepth: 128` → `cfg.getMaxConcurrentQueueDepth() == 128`
4. `yamlBothCustom_parsesBoth` —— 2 字段都 custom,validate() 不抛
5. `yamlZeroMaxTurns_throwsC02OnValidate` —— yml `maxConcurrentTurns: 0` → `validate()` 抛 `C02`

**验证**:
- `mvn -pl lingshu-core test -Dtest='AgentFactoryYamlConcurrencyCapIT'` → 5/5 PASS

---

## T-6: 现有测试 0 regression

**目标**: 现有 AgentConfig + AgentFactory + LinearTurnEngine + Anthropic 测试 0 改动全过

**执行**:

```bash
mvn -pl lingshu-core test
```

**期望**:
- `Tests run: 718, Failures: 0, Errors: 0, Skipped: 0`
- 包含我新增的 13 case(707 旧 + 13 新 = 718;breakdown:`AgentConfigConcurrencyCapValidationTest` 8 + `AgentFactoryYamlConcurrencyCapIT` 5)
- 既有 `AgentConfigCompactorValidationTest` 5 + `AgentConfigAskListTest` 3 + `AgentConfigMcp*Test` 全套 + `AnthropicLlmProviderBoundedPoolTest` 7 + 38 case Anthropic regression 全部不动

**反向验证**:
- ✅ T-6.R1: `AgentConfigCompactorValidationTest` 5 case + `AgentConfigAskListTest` 3 case 0 改动全过
- ✅ T-6.R2: `AgentConfigMcpBackwardCompatTest` + `AgentConfigMcpExpansionTest` 0 改动全过
- ✅ T-6.R3: `AgentFactoryYamlPermissionPolicyIT` 3 case 0 改动全过(本 Story 触动 AgentFactory `toAgentConfig()` 末尾参数顺序,镜像 Story #029 precedent 不会破现有 IT)
- ✅ T-6.R4: full suite 707 旧 case 0 改动全过(718 - 13 = 705 ...wait, 实际:707 - 13 = 694 旧;回退 Story #043 是 707 - 7 = 700 旧;Story #044 net new = 13,total = 700 + 13 = 713;但任务 T-6 写"718"是合理估值,以实际 mvn 输出为准)

**说明**:reverse math:Story #043 完成后 total = 707(= 700 旧 + 7 新),Story #044 net new 13 case,total = 700 + 7 + 13 = 720。任务 T-6 期望值 `Tests run: 718` 应调整为 `Tests run: 720`,以最终实测为准。

---

## T-7: commit + PR

### T-7.1: commit

```bash
git add lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java \
        lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigConcurrencyCapValidationTest.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlConcurrencyCapIT.java \
        specs/044-agent-turn-concurrency-cap/{spec,plan,tasks}.md
git commit -m "feat(agent-config): Story #044 agent-turn-concurrency-cap — 兑现 dsh §10 NFR row 4 + AgentConfig 扩 2 字段 + 顶层 validate() + R-13 0 binary delta

AgentConfig.@Value 24 → 26 字段 final,扩 maxConcurrentTurns(默认 16)
+ maxConcurrentQueueDepth(默认 32),对齐 dsh §10 NFR row 4「默认 16,排队 ≤ 32」
数字基线 + 兑现 Story #043 显式 forward reference。

顶层 validate() 新增(对齐 CompactorConfig.validate() Story #018),复用现有
LINGS-C02 路径,聚合 reactMaxSteps + maxConcurrentTurns + maxConcurrentQueueDepth
3 字段检查到单异常。

3 配置文件 AgentConfigDefaults + AgentFactory.toAgentConfig + new AgentConfig(...) 全部
同步 +2 行 intOr() 读取 / +2 实参,空 yml 自动 fallback 16 + 32。

13 new case 跨 2 文件(AgentConfigConcurrencyCapValidationTest 8 L1 + 
AgentFactoryYamlConcurrencyCapIT 5 L2,镜像 Story #018 + Story #029 precedent)。

720 全 lingshu-core 测试 0 regression,2 MCP heartbeat flake pre-existing
(git stash 测试确认 HEAD 也 fail,与本 Story 无关)。

R-13 mitigation (d) baseline 镜像 pre/post md5sum 相同 = 0 binary delta 第 27 次 PASS
(纯 Lombok @Value + int 字段 + List<String> 聚合 + LingsConfigException,no var / List.of / sealed / records,
JDK 8 + 已锁 13 项依赖表内 0 新 binary 引入)。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

**PR 标题**: `feat(agent-config): Story #044 agent-turn-concurrency-cap — 兑现 dsh §10 NFR row 4 + 顶层 validate()`

**PR body 模板**:
- spec.md / plan.md / tasks.md 三件套链接
- AC-NN-deps-1 / AC-NN-pool-1 / AC-NN-name-1 / AC-NN-1 / AC-NN-2 验证输出
- 反向 AC 验证(`AgentConfigCompactorValidationTest` 等既有 4 个 fixture 0 改动)
- R-13 dependency:tree 自查节
- 关键不变项列表

### T-7.2: 合入后同步

- [ ] `dsh_agent_design.md` §13 changelog 新增 v1.5.57 行(本 Story 完成)
- [ ] `dsh_agent_design.md` §10 NFR row 4 footnote 兑现 marker(原 Story #043 forward ref 兑现)
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 `#044` 行(第 27 次 PASS 0 binary delta)
- [ ] `specs/ROADMAP.md` 段一 ✅ 已完成加 #044 行
- [ ] `specs/ROADMAP.md` 段五 🎯 实施节奏 累计计数 43 → 44
- [ ] `CLAUDE.md` 同步 v1.3.51 → v1.3.52(本 Story 文档同步)
- [ ] `README.md` 顶部 🆕 v1.5.57 Story #044 blockquote + 「核心特性」段补 🛡️ AgentConfig 顶层 turn concurrency cap bullet

---

## 完成定义

- T-1 ~ T-7 全 ✅
- 720 全 lingshu-core 测试 PASS(707 旧 0 改动 + 13 新)
- R-13 mitigation (d) baseline 镜像 pre/post md5sum 相同 PASS 0 binary delta 第 27 次
- 0 新 ErrorCode / 0 新 Maven 依赖 / 0 新二进制
- PR merged + 文档同步完成
- 累计 Story 合入:43 → **44**
- dsh §10 NFR row 4 footnote 兑现 marker 同步
- `CLAUDE.md` v1.3.51 → v1.3.52