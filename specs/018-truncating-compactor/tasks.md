# Story #018 `truncating-compactor` — Tasks

> **Status**: Draft 2026-09-22
> **Implements**: `specs/018-truncating-compactor/plan.md`
> **Test budget**: 18 cases / 6 files

---

## T-01 — `AgentConfig.CompactorConfig` 嵌套配置

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(修改)

**操作**:
1. 在 `// ── v1.5.5 business config trio ──` 段后(`ClaudeMd` 类之后 / `TenantsConfig` 类之前)新增 `@Value public static class CompactorConfig { ... }`,3 字段 `maxPromptTokens / maxToolResultBytes / keepRecentTurns` + `defaults()` 工厂 + `validate()` 方法
2. `AgentConfig` 顶层新增 `CompactorConfig compactorConfig;` 字段(L66 `A2a a2a;` 之后)
3. `AgentConfig.defaults()`(检查是否存在)填充 `CompactorConfig.defaults()`(向后兼容空 yml)
4. `import java.util.ArrayList;`(已存在)— `import` 不重复加

**DoD**: `mvn -pl lingshu-core compile` 通过;`AgentConfigCompactorValidationTest.invalidMaxPromptTokens_throwsLingsC02` 2 case 可写(AC-018-9)。

---

## T-02 — `CompactorProps`(@Value 不可变 props)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/CompactorProps.java`(新)

**实现**:
- `@Value` 不可变类,3 字段(`maxPromptTokens / maxToolResultBytes / keepRecentTurns`)
- 静态工厂 `from(AgentConfig cfg)`:读 `cfg.getCompactorConfig()`,若 null 走 `defaults()` 再构造
- Javadoc 明确「Slot core(Compactor 接口 / CompactorProvider SPI)不引用本类,仅 Provider 边界翻译」

**DoD**: `mvn -pl lingshu-core compile` 通过;`CompactorPropsTest.from_withNullCompactorConfig_usesDefaults` + `from_withCustomConfig_passesThrough` 2 case 全过。

---

## T-03 — `TruncatingCompactor`(@Component implements Compactor)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/TruncatingCompactor.java`(新)

**实现**(对齐 dsh §6.2 L3813-3889 + plan.md §3):
- `@Component public class TruncatingCompactor implements Compactor`
- 构造器 `TruncatingCompactor(CompactorProps props)`:3 final 字段从 props 赋值
- `shouldCompact(Prompt p)`:返回 `estimateTokens(p) > maxPromptTokens`(粗估 `chars / 4`)
- `compact(TurnContext ctx)`:
  - `synchronized (ctx.session())`(dsh §6.2 L3891 副作用兜底并发)
  - history 空 → return(EC-018-1)
  - Step 1 `truncateOverlongToolResults(history)`:遍历 + `instanceof ToolResult` + `content.length() > maxToolResultBytes` 截断 + 原地 `history.set(i, new Message.ToolResult(toolUseId, truncated, isError))`
  - Step 2 `applySlidingWindow(history)`:反向扫 assistant message 数,`assistantCount > keepRecentTurns` 时记 cutIndex;cutIndex > 0 时构建 kept = `[0..cutIndex)` + system 提示,清空原 list + addAll
- `estimateTokens(Prompt)`:遍历 messages + tools,`chars += x.toString().length()`,return `chars / 4`

**DoD**: `mvn -pl lingshu-core compile` 通过;`TruncatingCompactorTest` 8 case 全过(AC-018-1 / AC-018-2 / AC-018-3 / AC-018-4 + EC-018-1/2/3/4)。

---

## T-04 — `TruncatingCompactorProvider`(@Component implements Providers.CompactorProvider)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/TruncatingCompactorProvider.java`(新)

**实现**:
- `@Component("compactorProvider_truncating")`(🆕 v1.5.28 §5.4 唯一 Bean 名约定)
- `implements Providers.CompactorProvider`
- `name() = "truncating"` + `priority() = 0` + `version() = "1.0.0"`(对齐 `Compactor.CONTRACT_VERSION = "1.0.0"`)
- `create(AgentConfig c)` 返回 `new TruncatingCompactor(CompactorProps.from(c))`

**DoD**: `mvn -pl lingshu-core compile` 通过;`TruncatingCompactorProviderTest.name_priority_version_create` 4 case 全过(AC-018-5 / AC-018-6)。

---

## T-05 — `CompactorRouter` 追加到 Routers.java

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java`(修改)

**操作**:
1. `import` 段加 `ai.lingshu.core.slot.Compactor`(L5 之后按字母序)
2. 在 `PermissionPolicyRouter` 之后(L60 之后 / `PromptBuilderRouter` 之前)追加 `CompactorRouter` 静态类:`@Component public static class CompactorRouter extends SlotRouter<Providers.CompactorProvider, Compactor> { ... }` 同 §5.3.1.0 L1854-1859 模板

**DoD**: `mvn -pl lingshu-core compile` 通过;启动日志出现 `[Compactor] resolved 1 provider(s) [contract v1.0.0]: ✓ truncating v1.0.0 -> TruncatingCompactorProvider [priority=0]`(由 `RoutersStartupTest` 扩 1 case 验证,AC-018-7)。

---

## T-06 — `SlotResolver` 验证 `@Autowired CompactorRouter`

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/SlotResolver.java`(可能微调)

**操作**:
1. 检查 `SlotResolver` ctor 是否已 `@Autowired` + 持有 `CompactorRouter` 字段(dsh §5.3.1 L1649 已设计)
2. 若缺失,补 1 行 `private final CompactorRouter compactorRouter;` + 1 行 `@Autowired public SlotResolver(..., CompactorRouter c, ...)` + 1 行 `this.compactorRouter = c;`
3. 验证 `SlotResolver.compactor(AgentConfig c)` 方法存在,内部 `return compactorRouter.resolve(c.getCompactor(), c);`

**DoD**: 启动期 Spring 自动 wire `CompactorRouter` 注入 `SlotResolver`;`AgentFactory.create(cfg)` 调 `slotResolver.compactor(cfg)` 不抛 NPE。

---

## T-07 — L1 单元测试(TruncatingCompactor + Provider + Props)

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/compaction/TruncatingCompactorTest.java`(新,8 case)

**case 列表**:
1. `shouldCompact_belowThreshold_returnsFalse` —— `maxPromptTokens=100`(chars/4 阈值) + Prompt chars=200 → estimateTokens=50,50 > 100 false
2. `shouldCompact_aboveThreshold_returnsTrue` —— Prompt chars=800 → estimateTokens=200,200 > 100 true
3. `shouldCompact_exactThreshold_returnsFalse` —— chars=400 → estimateTokens=100,100 > 100 false(严格大于,EC-018-3)
4. `compact_truncatesOverlongToolResult` —— 1 条 ToolResult content="x"*60000 + `maxToolResultBytes=50000` → history[0].content.length() = 50000 + suffix 长度
5. `compact_keepsShortToolResultUnchanged` —— ToolResult content="x"*100,content 完整保留
6. `compact_preservesToolUseId` —— 截断后 `new Message.ToolResult` 的 toolUseId 与原值相等
7. `compact_preservesIsErrorFlag` —— 截断后 `new Message.ToolResult` 的 isError 保留(true / false 各 1 case)
8. `compact_appendsCompactionSystemMessage` —— sliding window 触发后,history 末尾追加 `Message.System("[Earlier turns compacted. K messages removed.]", "compactor")`
9. `compact_keepsRecentTurns` —— 5 assistant + 5 user + 5 user,keepRecentTurns=2 → 保留最后 2 个 assistant + 前置 user + 末尾 system
10. `compact_idempotent_secondCallIsNoop` —— 调 2 次 compact,第 2 次 history size 不变(AC-018-4)
11. `compact_emptyHistory_isNoop`(EC-018-1)
12. `compact_noAssistantMessages_isNoop`(EC-018-2) —— 全 user / system 无 assistant
13. `compact_toolResultWithNullContent_isNoop`(EC-018-4)

**辅助 helper**:`FakeSession implements Session` 提供 history getter + setter;`FakeTurnContext implements TurnContext` 包 FakeSession;`FakePrompt.builder().messages(...).build()` 控 chars 数。

**DoD**: 13 case 全过。

---

## T-08 — L1 单元测试(TruncatingCompactorProviderTest + CompactorPropsTest + AgentConfigCompactorValidationTest)

**文件 1**:`TruncatingCompactorProviderTest.java`(新,4 case)
- `name_returnsTruncating`
- `priority_returnsZero`
- `version_returnsContractVersion_v1`
- `create_withCustomConfig_usesCfgValues`(`cfg.compactorConfig = new CompactorConfig(50, 25, 5)` → 新建 TruncatingCompactor 字段一致)

**文件 2**:`CompactorPropsTest.java`(新,3 case)
- `from_withValidConfig_passesThrough`
- `from_withNullCompactorConfig_usesDefaults`
- `value_immutable`(Lombok `@Value` 测试,字段 final)

**文件 3**:`AgentConfigCompactorValidationTest.java`(新,4 case)
- `defaults_compactorConfig_has3Fields`(AC-018-8)
- `existingCompactorNameString_stillWorks`(AC-018-8 向后兼容 —— 旧 cfg 只设 compactor 字段,compactorConfig = null 不抛)
- `invalidMaxPromptTokens_throwsLingsC02`(AC-018-9)
- `invalidMaxToolResultBytes_throwsLingsC02`(AC-018-9)
- `invalidKeepRecentTurns_throwsLingsC02`(AC-018-9)

**DoD**: 11 case 全过。

---

## T-09 — L2 slice 测试(RoutersStartupTest 扩 + LinearTurnEngineCompactionTest 新)

**文件 1**:`lingshu-core/src/test/java/ai/lingshu/core/impl/router/RoutersStartupTest.java`(扩,1 case 追加)
- `compactorRouter_resolvesTruncatingProvider` —— 起 mini Spring `ApplicationContext`(`@SpringBootTest(classes = ...)` or `AnnotationConfigApplicationContext` with `@ComponentScan("ai.lingshu.core")`),拿 `Routers.CompactorRouter` bean + `Providers.CompactorProvider` bean,断言 `truncating` name 唯一 + `available().size() == 1`

**文件 2**:`lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineCompactionTest.java`(新,1 case)
- `turnWithLongHistory_triggersCompaction` —— 构造 30 个 assistant message 的 Session(超过 keepRecentTurns=20),`LinearTurnEngine` 跑 1 turn,触发 `TruncatingCompactor.shouldCompact(prompt)=true` + `compact(ctx)`;验证 turn 正常 `TurnCompleted`;history size 缩减(AC-018-10)
- 复用 `SlowLlmProvider` / `RecordingPromptBuilder` / `CapturingSubscriber`(已有 in `LinearTurnEngineE2ESmokeTest` 周边)

**DoD**: 2 case 全过。

---

## T-10 — 验证 + R-13 自查

**操作**:
1. 跑 `mvn -pl lingshu-core -am test` —— 全部 case(既有 234 + Story #018 新增 18 = 252)全过,0 fail / 0 error / 0 skipped
2. 跑 `mvn -pl lingshu-core dependency:tree -DincludeScope=runtime > /tmp/deps-pre.txt`
3. 跑 `mvn -pl lingshu-core compile` 后再跑 `dependency:tree` 拿 `/tmp/deps-post.txt`
4. `diff /tmp/deps-pre.txt /tmp/deps-post.txt` —— 应当**仅时间戳差异**,0 binary delta
5. 跑 `mvn -pl lingshu-core verify` 完整 verify(包括 `banned-dependencies` enforcer)
6. 准备 PR body,末尾追加 `### R-13 dependency:tree 自查` 节,贴 pre/post diff 输出

**DoD**: 所有命令 0 失败;PR body 含 R-13 节。

---

## T-11 — 文档同步(README + dsh v1.5.38 + constitution §10 + ROADMAP.md)

**操作**:
1. **README.md** —— 测试计数 + ≥18;「## ⚡ 30 秒上手」加 Compactor 段(3 行示例);新增「Story #018 narrative section」(~30 行)
2. **dsh_agent_design.md v1.5.38 同步**(单独 PR,沿用 Story #008 / #009c 模式):
   - §0 L1 标题版本号 v1.5.37 → v1.5.38
   - §0.4 版本 blockquote 预本条
   - §6.2 L3813-3891 代码块注释 + 「Story #018 实施完成」一行
   - §13 changelog 表新增 v1.5.38 行(模仿 v1.5.36 Story #008 行的写法)
3. **constitution.md §10 R-04** —— 缓解率 33% → **66%**(reactMaxSteps 上限 33% + TruncatingCompactor 滑动窗口 33% 新增),并加 R-04 mitigation (b)「TruncatingCompactor 滑动窗口收口」条目
4. **specs/ROADMAP.md 段一**「✅ 已完成」表追加 `#018 truncating-compactor ✅ 合」行

**DoD**: 4 文件全改 + 各自 `git diff` 校验。

---

## T-12 — PR 准备 + 提交

**操作**:
1. `git checkout -b story/018-truncating-compactor`(从 `docs/specs-roadmap` 拉分支)
2. `git add specs/018-truncating-compactor/ lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/ lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java lingshu-core/src/test/java/ai/lingshu/core/impl/compaction/ lingshu-core/src/test/java/ai/lingshu/core/impl/router/RoutersStartupTest.java lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineCompactionTest.java lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigCompactorValidationTest.java README.md specs/ROADMAP.md`
3. `git commit -m "feat(core): Story #018 truncating-compactor — TruncatingCompactor + Provider + Props + Router + 18 tests (AC-018-1—AC-018-10)"`
4. `git push origin story/018-truncating-compactor`
5. `gh pr create --base docs/specs-roadmap --title "feat(core): Story #018 truncating-compactor — ToolResult 截断 + 滑动窗口 + 18 tests" --body "$(cat <<'EOF'
## Summary
- TruncatingCompactor 实现(dsh §6.2 L3813-3889):2 步压缩(ToolResult 截断 + 滑动窗口保留最近 N turn)
- TruncatingCompactorProvider(Slot 6 默认 Provider,Bean 名 `compactorProvider_truncating`)
- CompactorProps @Value 不可变 props + from(AgentConfig) 工厂
- CompactorRouter 追加到 Routers.java(补齐 dsh §5.3.1.0 L1854-1859)
- AgentConfig.CompactorConfig 嵌套(3 字段 + validate + defaults)
- 18 测试 case / 6 文件(TruncatingCompactorTest 13 + ProviderTest 4 + PropsTest 3 + ConfigValidationTest 4 + RoutersStartupTest 1 + LinearTurnEngineCompactionTest 1 - 重叠算 18)

## Test plan
- [ ] mvn -pl lingshu-core -am test 全绿
- [ ] mvn -pl lingshu-core dependency:tree pre/post diff 0 binary delta
- [ ] mvn -pl lingshu-core verify (含 banned-dependencies enforcer) 不 fail

### R-13 dependency:tree 自查
\`\`\`
diff /tmp/deps-pre.txt /tmp/deps-post.txt
\`\`\`
(实测时贴实际输出)
EOF
)"`

**DoD**: PR 创建成功 + URL 可访问;dsh v1.5.38 单独 PR 在另一分支并行提。

---

**Last updated**: 2026-09-22
**Task author**: Claude Code (per user 2026-09-22 conversation)