# Story #018 `truncating-compactor` — Spec

> **Status**: Draft 2026-09-22
> **Source**: dsh v1.5.37 §6.2 `TruncatingCompactor v1` (L3813-3891) + §5.5 Slot 2 stub (L2122-2141) + §4.9 `Compactor` interface (L717-723)
> **Closes gap**: Slot 6 (`Compactor`) interface has existed since Step -1 (Story #001 scaffold), `CompactorProvider` marker exists in `Providers.java`, but **no concrete `TruncatingCompactor` implementation, no `TruncatingCompactorProvider`, no `CompactorProps` config, and no `CompactorRouter` concrete stub** — the engine has been running without any compactor plug-in since v0.1.0-SNAPSHOT

---

## WHY

`Compactor` 是 Slot 6(dsh §4.9)的契约接口,2 方法契约:`shouldCompact(Prompt)` 纯谓词 + `compact(TurnContext)` 副作用地缩减 `ctx.session().history()`。`LinearTurnEngine.runTurn()` 主循环在每次 LLM 调用前都判 `compactor.shouldCompact(prompt)` → true 时调 `compactor.compact(ctx)`(dsh §6.1 L3630-3631)。

但 Story #001—#017 期间:
1. **`CompactorRouter` 没有 concrete stub** —— `Routers.java` L31-122 列了 6 Router(LlmProvider / ToolExecutor / PermissionPolicy / PromptBuilder / FlowEngine / MemorySource),`CompactorRouter` 不在内;`A2aTransportRouter` 单文件独立。Slot 6 Router **缺失**;
2. **`TruncatingCompactorProvider` 是 `UnsupportedOperationException` stub** —— dsh §5.5 L2126-2139 给的 AutoConfiguration 模板里 `create(AgentConfig c)` 抛 `UnsupportedOperationException("TODO: Story #015 — TruncatingCompactor")`(注:原 dsh 标 #015,ROADMAP 重排为 #018);
3. **`CompactorProps` 不存在** —— dsh §6.2 L3828-3836 引用了 `props.getMaxPromptTokens() / getMaxToolResultBytes() / getKeepRecentTurns()` 但未给出类定义;
4. **`AgentConfig.compactor` 是裸 `String` 字段** —— 没有嵌套 `CompactorConfig` 容纳三个数值阈值。

**Story #018 目标**:补齐 4 件套(TruncatingCompactor + TruncatingCompactorProvider + CompactorProps + CompactorRouter)+ AgentConfig 嵌套 CompactorConfig + Spring 默认装载 + 测试。

**业务价值**:
- dsh §14.15.1 NFR「单 turn history ≤ 100K tokens」当前**不可观测 / 不可保证** —— 没有 compactor 任何 turn 都能跑超直到 OOM;
- dsh §1.5.3 R-04(ReAct 失控循环)缓解率 33%(`reactMaxSteps` 上限一项),Story #018 不直接缓解 R-04 但为 R-04 mitigation (c)「Compactor 滑动窗口收口」铺路;
- dsh §14.4 N4 CostBudget(Story #013 P2 滞后)等下游 Story 都假设 `Compactor` 已就位 —— 拖到 P2 实现会让依赖链爆。

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类) | application.yml 里写 `agent.compactor: truncating` + 自定义 3 个阈值,空 yml 必须能跑(零配置原则) |
| **业务配置方**(Diana 类) | 不写代码,只看 dsh §14.15.1 NFR「单 turn history ≤ 100K tokens」是否真生效 |
| **CI 工程师**(Charlie 类) | L1/L2 测试 case ≥ 8 个,`mvn -pl lingshu-core test` 0 fail |

## WHAT

Story #018 落地 Slot 6(`Compactor`)默认 Provider 全套:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `CompactorProps` | `@Value` 不可变 props | `lingshu-core/.../impl/compaction/CompactorProps.java` | ~30 |
| `TruncatingCompactor` | `@Component implements Compactor` | `lingshu-core/.../impl/compaction/TruncatingCompactor.java` | ~70(dsh §6.2 L3813-3889) |
| `TruncatingCompactorProvider` | `@Component implements Providers.CompactorProvider` | `lingshu-core/.../impl/compaction/TruncatingCompactorProvider.java` | ~30 |
| `CompactorRouter`(新增到 `Routers.java`) | `@Component extends SlotRouter<...>` | `lingshu-core/.../impl/router/Routers.java`(追加) | ~10 |
| `AgentConfig.CompactorConfig`(嵌套) | `@Value` 嵌套配置 | `lingshu-core/.../runtime/AgentConfig.java`(追加) | ~40 |
| Spring 默认装载 | `@Bean(name = "compactorProvider_truncating")` in `TruncatingCompactorProvider` 自带 `@Component`(无需独立 AutoConfiguration)| 同 Provider | 0 增量 |
| 测试 | L1 + L2 | `lingshu-core/src/test/java/.../compaction/` + `router/` | ~280 行 / ≥10 case |

**`CompactorConfig` 字段**(3 个,与 dsh §6.2 L3828-3835 对齐):

| 字段 | 类型 | 默认 | yml key | 约束 |
|---|---|---|---|---|
| `maxPromptTokens` | `int` | `100_000`(dsh §14.15.1 单 turn ≤ 100K) | `agent.compactor.max-prompt-tokens` | > 0,启动期校验 |
| `maxToolResultBytes` | `int` | `50_000`(50KB) | `agent.compactor.max-tool-result-bytes` | > 0,启动期校验 |
| `keepRecentTurns` | `int` | `20`(N turn 滑动窗口) | `agent.compactor.keep-recent-turns` | > 0,启动期校验 |

**`TruncatingCompactor` 行为**(2 步,dsh §6.2 L3844-3880):
1. **ToolResult 截断**:遍历 history,任何 `ToolResult.content.length() > maxToolResultBytes` 的,截断到 `maxToolResultBytes` + 追加 `\n...[truncated, original N bytes]`(**原地替换**该 message,保持原 toolUseId / isError);
2. **滑动窗口**:从 history 末尾向前扫,数 assistant message 数(每数到一个 `> keepRecentTurns` 时记录 cutIndex = i + 1);cutIndex > 0 时,在 [0..cutIndex) 末尾追加 1 条 `Message.System("[Earlier turns compacted. K messages removed.]", "compactor")`,history 清空后填充 kept + system 提示。

**`shouldCompact(Prompt)` 谓词**(dsh §6.2 L3839-3841):`estimateTokens(prompt) > maxPromptTokens`,estimate 算法 `chars / 4`(粗估 4 字符 = 1 token,中位估值)。

**关键约束**:
- dsh §10.1 锁定 13 项依赖,**0 新 Maven coordinates**(R-13 mitigation (d))
- dsh §6.2 L3891 「副作用地修改 history 需要 Session 加锁;v1 选 Session 层加 `synchronized`」 —— 实施期需要确认 Session `history()` 返回 `List<Message>` 是否支持并发修改,若不支持需 v1 在 `TruncatingCompactor.compact()` 头加 `synchronized (ctx.session())`
- dsh §4.10.1 硬规则不涉及 Compactor(Slot 6 不调 Spring AI / 不并行 dispatch / 不需要 Provider 显式映射),Story #018 0 风险区
- dsh §5.4 plugin AutoConfiguration 唯一 Bean 名约定:`@Bean(name = "compactorProvider_truncating")`(🆕 v1.5.28 多 Provider 模式)

## 反向 AC(明确不做)

- ❌ `SummaryCompactor`(LLM 摘要式压缩)—— 留 v2 SPI 替换,dsh §6.2 L3888 注释 + §14.11 P2
- ❌ ToolResult 摘要(摘要式而非截断式)—— 留 v2
- ❌ 滑动窗口 N 之外的 turn **永久删除**(留 audit)—— v1 只追加 system 提示,**不**写 audit log(Story #016 AuditLogger 在 P2 滞后)
- ❌ `Compactor` 接口改动(2 方法契约不变)
- ❌ Session API 改动 —— Session `history()` 返回的 `List<Message>` 不动,compactor 在 Session 层 `synchronized` 兜底并发
- ❌ CompactorRegistry(单 JVM 多 Compactor 实例管理)—— 单 turn 单 Compactor 实例足够
- ❌ `Compactor` 改异步(`CompletableFuture<Void> compactAsync(ctx)`)—— 同步即可,dsh §6.2 同步设计

## AC 编号(Story #018 新增,不入 dsh §0.4)

| AC | 描述 | 验证 |
|---|---|---|
| **AC-018-1** | `TruncatingCompactor` `shouldCompact(Prompt)` 纯函数行为:`chars ≤ 4 × maxPromptTokens` 时 false,`chars > 4 × maxPromptTokens` 时 true | `TruncatingCompactorTest.shouldCompact_belowThreshold_returnsFalse` + `shouldCompact_aboveThreshold_returnsTrue` + `shouldCompact_exactThreshold_returnsFalse`(`chars / 4 > maxPromptTokens` 严格大于)|
| **AC-018-2** | `compact(ctx)` 对超长 `ToolResult`(`content.length > maxToolResultBytes`)做原地截断,替换 message 保留 `toolUseId` + `isError`,append `\n...[truncated, original N bytes]` 提示 | `TruncatingCompactorTest.compact_truncatesOverlongToolResult` + `compact_keepsShortToolResultUnchanged` + `compact_preservesToolUseId` + `compact_preservesIsErrorFlag` |
| **AC-018-3** | `compact(ctx)` 滑动窗口:history 中 assistant message 数 > `keepRecentTurns` 时,只保留最近 N 个 + 前置 1 条 system 提示 | `TruncatingCompactorTest.compact_keepsRecentTurns` + `compact_withFewerTurnsIsNoop` + `compact_appendsCompactionSystemMessage` |
| **AC-018-4** | `compact(ctx)` 是**幂等** —— 连续调 2 次,第 2 次是 no-op(已压缩 session 不再被压缩) | `TruncatingCompactorTest.compact_idempotent_secondCallIsNoop` |
| **AC-018-5** | `TruncatingCompactorProvider.name() = "truncating"` + `priority() = 0` + `version() = "1.0.0"`(对齐 Slot `CONTRACT_VERSION = 1.0.0`) | `TruncatingCompactorProviderTest.name_priority_version` |
| **AC-018-6** | `TruncatingCompactorProvider.create(AgentConfig)` 返回的 `TruncatingCompactor` 持有 props 与 cfg 一致(覆盖默认时用 cfg 值,空 cfg 时用默认)| `TruncatingCompactorProviderTest.create_withEmptyConfig_usesDefaults` + `create_withCustomConfig_usesCfgValues` |
| **AC-018-7** | `CompactorRouter` 启动期列出 1 个 Provider(`truncating`),bean name `compactorProvider_truncating`,启动日志格式与其他 6 Router 一致 | `RoutersStartupTest.compactorRouter_resolvesTruncatingProvider`(L2 slice,起 Spring `ApplicationContext` 拿 bean)|
| **AC-018-8** | `AgentConfig.compactor` 字段(裸 String)与新嵌套 `CompactorConfig` 共存:旧 yml `compactor: truncating` 仍工作(无嵌套 cfg 时用 defaults)| `AgentConfigBackwardsCompatTest.existingCompactorNameString_stillWorks` + `defaults_compactorConfig_has3Fields` |
| **AC-018-9** | 启动期 yml 校验:`maxPromptTokens <= 0` / `maxToolResultBytes <= 0` / `keepRecentTurns <= 0` 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED` | `AgentConfigCompactorValidationTest.invalidMaxPromptTokens_throwsLingsC02` + 2 case |
| **AC-018-10** | LinearTurnEngine 主循环集成:turn 中 history 超 `maxPromptTokens` 时自动触发 `TruncatingCompactor.compact()`,之后 LLM 调用正常完成 | `LinearTurnEngineCompactionTest.turnWithLongHistory_triggersCompaction`(L2,集成 `LinearTurnEngine` + `RecordingPromptBuilder` + `EchoLlmProvider` + 真实 `TruncatingCompactor`,触发 shouldCompact → compact → 后续 LLM 调用 → `TurnCompleted`)|

**EC(边界 case)**:
- **EC-018-1** | 空 history(`ctx.session().history().isEmpty()`)调 compact 不抛异常 | `TruncatingCompactorTest.compact_emptyHistory_isNoop`
- **EC-018-2** | history 全是 system / user message 无 assistant —— sliding window 不触发(assistantCount 永远 ≤ keepRecentTurns)| `TruncatingCompactorTest.compact_noAssistantMessages_isNoop`
- **EC-018-3** | `maxPromptTokens = 1` + `chars = 5` → estimateTokens = 1,`1 > 1 = false` 不触发(边界严格大于) | `TruncatingCompactorTest.shouldCompact_exactThreshold_returnsFalse`
- **EC-018-4** | `Message.ToolResult.getContent()` 为 null(空 result)| 不抛 NPE,正常处理 | `TruncatingCompactorTest.compact_toolResultWithNullContent_isNoop`

## ErrorCode 引入(2 条,Story 边界 ≤3 内)

| 码 | 域 | 触发场景 |
|---|---|---|
| `LINGS-C02` | C(配置)| `CompactorConfig` 3 字段任一 ≤ 0 —— 复用 Story #001 已落地码,不新增 |
| `LINGS-Z01` | Z(其他)| Compactor 内部 invariant 违反(空 Session / null history)—— 复用 Story #017 已落地码,不新增 |

**0 新 ErrorCode**(复用既有 2 条)。

## 出口标准(DoD)

- [ ] `specs/018-truncating-compactor/{spec,plan,tasks}.md` 三件套合入主分支
- [ ] 5 个生产文件(CompactorProps / TruncatingCompactor / TruncatingCompactorProvider / CompactorConfig 嵌套 / CompactorRouter 追加)落地
- [ ] ≥ 10 测试 case 全过(`mvn -pl lingshu-core test`),其中 1 个 L2(`LinearTurnEngineCompactionTest` 集成验证)
- [ ] R-13 `mvn dependency:tree` 自查:**0 新 Maven coordinates**(`mvn -pl lingshu-core dependency:tree -DincludeScope=runtime` pre/post diff 仅时间戳)
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节
- [ ] README.md 累计测试数 + ≥ 10 + 「## ⚡ 30 秒上手」加 Compactor 段 + Story #018 narrative section
- [ ] `constitution.md` §10 R-04 缓解率:33% → **66%**(reactMaxSteps 上限 33% + TruncatingCompactor 滑动窗口 33% 新增)
- [ ] dsh v1.5.38 单独 PR 同步(沿用 Story #008 / #009c 模式)
- [ ] ROADMAP.md 段一「✅ 已完成」表追加 `#018 truncating-compactor`

## 不在 Story #018 范围(显式 deferred)

- ❌ `SummaryCompactor`(LLM 摘要式压缩)—— 留 v2 SPI 替换(§14.11)
- ❌ ToolResult 摘要 —— 留 v2
- ❌ Compactor 在 AuditLogger 写压缩事件 —— 留 Story #016(AuditLogger P2 滞后)
- ❌ 异步 Compactor —— 同步实现足够
- ❌ `Compactor.shouldCompact` 改判 LLM cost(turn 累计 token 而非 prompt 单条)—— 留 CostBudget Story #013
- ❌ Compactor 在 SlideWindow 时把 user/assistant 配对语义保留 —— v1 简单按 assistant 切,留 v2

---

**Last updated**: 2026-09-22
**Spec author**: Claude Code (per user 2026-09-22 conversation)
**Reviewer**: 待 PR review