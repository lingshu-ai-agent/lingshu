# Tasks: Story #031 `permission-policy-pattern-matching`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 1 新增 + 9 modify + 7 测试新增 ≈ 17 文件,但严格按 P1—P6 拆分 commit,每个 commit 1-3 文件)
>
> **实施顺序严格按 plan §3**:`Tool.sourceCategory()` default method → `PermissionPatterns` 工具类 → 5 个 Tool override → `StrictPermissionPolicy` 升级 → `StrictPermissionPolicyProvider` @Autowired ToolRegistry → demo yml 改 pattern → 测试 fixture → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#031` 复用 JDK 8 `String` / `Map` / `HashMap` / `Collections.emptyMap()` + Lombok + Spring `@Component` / `@Autowired` 全 JDK 8 built-in 0 新二进制,需验证 0 binary delta 第 16 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(permission): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(1 新增 + 9 modify)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java` modify — 加 **1 default method** `default String sourceCategory() { return "local"; }` + 类级 Javadoc 补 (1) default method 零侵入说明 + (2) 5 内置 category 约定(`local` / `mcp` / `skill` / `a2a` / `delegate`)+ (3) plugin author 自定义字符串空间 + §5.5 L2168-2189 reference(预估 10min,spec §4 AC-NN-2)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPatterns.java` 新增工具类 —— `public final class PermissionPatterns` + `private PermissionPatterns() {}` + `public static boolean matches(String toolName, String toolCategory, String pattern)` 实现 3 类 pattern:(`*` → `return true`;`<category>:*` → `pattern.endsWith(":*")` + `String.substring` + `String.equals(toolCategory)`;`<exact>` → `pattern.equals(toolName)`);`null` pattern 抛 `IllegalArgumentException`;类级 Javadoc 覆盖 (1) 3 类 pattern 形式 + (2) 优先级语义 + (3) 纯 JDK 8 String ops 不引 regex(预估 20min,spec §4 AC-NN-1)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpToolAdapter.java` modify — 加 `@Override public String sourceCategory() { return "mcp"; }` 单行 + 行内 Javadoc 引用 dsh §6.5 (2) + §4.7(预估 3min,spec §4 AC-NN-2)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillTool.java` modify — 加 `@Override public String sourceCategory() { return "skill"; }` 单行 + 行内 Javadoc 引用 dsh §6.4(预估 3min,spec §4 AC-NN-2)
- [ ] **T05** `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java` modify — 加 `@Override public String sourceCategory() { return "a2a"; }` 单行 + 行内 Javadoc 引用 dsh §5.6.3(预估 3min,spec §4 AC-NN-2,跨模块)
- [ ] **T06** `lingshu-core/src/main/java/ai/lingshu/core/agent/DelegateTool.java` modify — 加 `@Override public sourceCategory() { return "delegate"; }` 单行 + 行内 Javadoc 引用 dsh §6.6(预估 3min,spec §4 AC-NN-2)
- [ ] **T07** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicy.java` modify —— (1) 加字段 `private final Map<String, String> nameToCategory;`(Lombok `@Value` 自动 final);(2) 构造器扩 `StrictPermissionPolicy(AgentConfig.ToolsConfig tools, Map<String, String> nameToCategory)`;(3) `@Override public Decision check(ToolCall call, ToolExecutionContext ctx)` 3 决策路径升级为 pattern matching:`String toolName = call.name(); String toolCategory = nameToCategory.getOrDefault(toolName, "local");` → (a) `for (String pattern : tools.getDenyList()) if (PermissionPatterns.matches(toolName, toolCategory, pattern)) return new Decision.Deny("[LINGS-P01] Tool '" + toolName + "' matches deny pattern '" + pattern + "'");`;(b) `if (tools.getAllowList().isEmpty()) return new Decision.Allow("default policy: allow (no allow-list)"); for (String pattern : tools.getAllowList()) if (PermissionPatterns.matches(toolName, toolCategory, pattern)) return new Decision.Allow("strict policy: allow (matches pattern '" + pattern + "')");`;(c) `return new Decision.Deny("[LINGS-P01] Tool '" + toolName + "' not in allow-list (category=" + toolCategory + ")");`;类级 Javadoc 补 (1) pattern matching 说明 + (2) back-compat 字面 equals 路径(预估 30min,spec §4 AC-NN-3 + AC-NN-4 + AC-NN-5 + AC-NN-9)
- [ ] **T08** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyProvider.java` modify —— (1) 加字段 `private final ToolRegistry toolRegistry;`(Lombok `@Value` + `@Autowired`);(2) 构造器加 `@Autowired public StrictPermissionPolicyProvider(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; }`(或用 Spring `@Autowired` field injection);(3) `@Override public PermissionPolicy create(AgentConfig cfg)` 改写:`Map<String, String> nameToCategory = new HashMap<>(); for (Tool t : toolRegistry.findAll()) { nameToCategory.put(t.name(), t.sourceCategory()); } return new StrictPermissionPolicy(cfg.getTools(), nameToCategory);`(预估 15min,spec §4 AC-NN-6)
- [ ] **T09** `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java` modify(可选,若 `findAll()` 已存在则跳过)—— 优先查 `DefaultToolRegistry` 现有 method(`lookupAll()` / `all()` / `snapshot()` 等);若必须新增,加 `default Collection<Tool> findAll() { return Collections.emptyList(); }` default method(零侵入 SPI 扩展,back-compat 守住) + DefaultToolRegistry `@Override public Collection<Tool> findAll() { return new ArrayList<>(nameToTool.values()); }`(预估 10min,spec §4 AC-NN-6)
- [ ] **T10** `lingshu-examples/demo-product/src/main/resources/application.yml` modify —— `allow-list` 段改 pattern(2 选 1):**Option A 一行解决** `allow-list: ["*"]` + 注释 `🆕 Story #031 pattern matching — "*" 通配覆盖所有 12 个 Tool(本地 + MCP + Skill + A2A + Delegate),替代 Story #029 静态枚举 12 行;新增 MCP server / Skill / RemoteAgent 自动允许`;**Option B 精细控制** `allow-list: [mcp:*, skill:*, a2a:*, delegate:*, read_file]` + 注释同上;选 Option A(推荐,最简);注释引用 dsh §5.5 + §4.7 + §15.4 P 段(预估 10min,spec §4 AC-NN-7 + AC-NN-8)

> **P1 总耗时**:~107 min(~1.8h)

---

## P2:测试(7 测试文件 + 21 case)

- [ ] **T11** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/PermissionPatternsTest.java` 新增 L1 Unit —— 6 case(AC-NN-1):
   - case 1:`matches("read_file", "local", "*")` → `true`
   - case 2:`matches("read_file", "local", "read_file")` → `true`(字面 equals)
   - case 3:`matches("read_file", "local", "write_file")` → `false`(字面 mismatch)
   - case 4:`matches("echo", "mcp", "mcp:*")` → `true`(category 命中)
   - case 5:`matches("read_file", "local", "mcp:*")` → `false`(category 不匹配)
   - case 6:`matches("agent", "skill", "skill:*")` → `true`(skill category 命中)
   - + 2 边界 case:`matches("any", "local", null)` 抛 `IllegalArgumentException` + `matches("any", "local", "")` → `false`(空 pattern)(预估 30min)
- [ ] **T12** `lingshu-core/src/test/java/ai/lingshu/core/tool/ToolSourceCategoryTest.java` 新增 L1 Unit —— 5 case(AC-NN-2):
   - case 1:手动 new `McpToolAdapter(mockDescriptor)` → `sourceCategory() == "mcp"`
   - case 2:`SkillTool.fromMarkdown("agent", "# agent\ncontent")` → `sourceCategory() == "skill"`
   - case 3:`new RemoteAgentTool(mockSchemaBuilder, mockTransport)` → `sourceCategory() == "a2a"`
   - case 4:`new DelegateTool(...)` → `sourceCategory() == "delegate"`
   - case 5:自定义 `class TestLocalTool implements Tool {}` 不 override → `sourceCategory() == "local"`(default method 验证)(预估 60min)
- [ ] **T13** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyPatternTest.java` 新增 L1 Unit —— 5 case pattern + 1 case back-compat(AC-NN-3 + AC-NN-4 + AC-NN-5):
   - case 1:`allow-list: [mcp:*, skill:*, read_file]` + `deny-list: [danger_*]` + `nameToCategory: {echo → mcp, agent → skill, read_file → local, danger_tool → local, write_file → local}` + `policy.check(ToolCall("echo"))` → `Allow` + reason 含 `matches pattern 'mcp:*'`
   - case 2:`policy.check(ToolCall("agent"))` → `Allow` + reason 含 `matches pattern 'skill:*'`
   - case 3:`policy.check(ToolCall("read_file"))` → `Allow` + reason 含 `matches pattern 'read_file'`
   - case 4:`policy.check(ToolCall("danger_tool"))` → `Deny` + reason 含 `matches deny pattern 'danger_*'`
   - case 5:`policy.check(ToolCall("write_file"))` → `Deny` + reason 含 `not in allow-list (category=local)`
   - case 6(back-compat):Story #029 现有 `allow-list: [read_file, write_file, list_dir, bash_safe]`(无 pattern)+ `policy.check(ToolCall("read_file"))` → `Allow` + `policy.check(ToolCall("unknown"))` → `Deny`(字面 equals 行为 0 回归,Story #029 `StrictPermissionPolicyTest` 5 case 复用)(预估 90min)
- [ ] **T14** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/StrictPermissionPolicyReasonTest.java` 新增 L1 Unit —— 1 case(AC-NN-9):`allow-list: [read_file]` + `deny-list: [danger_*]` + `policy.check(ToolCall("danger_tool"))` → `Deny.reason` AssertJ `hasMessageContaining("[LINGS-P01] Tool 'danger_tool' matches deny pattern 'danger_*'")`(预估 15min)
- [ ] **T15** `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentFactoryPatternMatchingIT.java` 新增 L2 Slice —— 1 case(AC-NN-6):`AnnotationConfigApplicationContext` 装配 `PermissionPolicyAutoConfiguration` + `StrictPermissionPolicyProvider` + `ToolRegistry`(mock 5 个 Tool:2 个 McpToolAdapter + 2 个 SkillTool + 1 个本地)+ `AgentFactory.create(cfg)` → 通过反射读 `StrictPermissionPolicy.nameToCategory` 字段验证 5 entry 正确填充(预估 60min)
- [ ] **T16** `lingshu-examples/demo-product/src/test/java/.../DemoProductPermissionWildcardIT.java` 新增 L3 黑盒 —— 1 case(AC-NN-7):启动 demo-product Spring Boot + yml `permission-policy: strict` + `tools.allow-list: ["*"]`;模拟调 12 个 Tool 名(4 本地 + 2 MCP + 4 Skill + 1 A2A + 1 Delegate)→ 全 `Decision.Allow`;启动日志验证 `StrictPermissionPolicyProvider` 创建成功(预估 90min)
- [ ] **T17** `lingshu-examples/demo-product/src/test/java/.../DemoProductPermissionCategoryPatternIT.java` 新增 L3 黑盒 —— 1 case(AC-NN-8):同 demo 但 yml `allow-list: [mcp:*, skill:*, read_file]`;6 case inline 验证:`echo`(mcp) → Allow;`agent`(skill) → Allow;`read_file`(local) → Allow;`write_file`(local) → Deny;`remote_agent`(a2a) → Deny;`Task`(delegate) → Deny(预估 90min)

> **P2 总耗时**:~435 min(~7.25h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=PermissionPatternsTest` 验证 3 类 pattern 6 case + 2 边界 case(预估 5min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=ToolSourceCategoryTest` 验证 5 个 Tool 实现 sourceCategory() 返回正确 category(预估 5min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyPatternTest#patternMatching` 验证 pattern matching 5 case(预估 5min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyPatternTest#mixedPatternAndExact` 验证 pattern + exact 混合 3 case(预估 5min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyTest` 验证 Story #029 字面 equals back-compat 0 回归(5 case)(预估 5min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-core test -Dtest=AgentFactoryPatternMatchingIT` 验证 `AgentFactory.create()` 真接 nameToCategory(预估 10min)
- [ ] **T-validate-AC-NN-7** 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductPermissionWildcardIT` 验证 `allow-list: ["*"]` 一行解决 12 个 Tool(预估 15min)
- [ ] **T-validate-AC-NN-8** 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductPermissionCategoryPatternIT` 验证 6 case 精细 pattern 控制(预估 15min)
- [ ] **T-validate-AC-NN-9** 跑 `mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyReasonTest` 验证 reason 字符串含 pattern 信息(预估 3min)
- [ ] **T-validate-AC-NN-10** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose` + diff 对比 `#029` post-commit baseline 镜像,验证 0 新 Maven 坐标(R-13 第 16 次)(预估 15min)
- [ ] **T-validate-AC-NN-11** 跑 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 全模块无 fail,新增 21 case 全过,626 pre test 0 回归(预估 30min)

> **P3 总耗时**:~113 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-031-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-031-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-029-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-031-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-product package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min

---

## P5:文档同步(9 文件)

- [ ] **T-doc-1** `README.md` 顶部加 `#031` 1 段(PermissionPolicy pattern matching,`PermissionPatterns` + `Tool.sourceCategory()` + 5 内置 category)(预估 10min)
- [ ] **T-doc-2** `specs/031-permission-policy-pattern-matching/quickstart.md` 起草(给 Alice 30min 跑通 pattern matching,模板对齐 `#027a`/`#027b`/`#028`/`#029`)(预估 30min)
- [ ] **T-doc-3** `specs/031-permission-policy-pattern-matching/data-model.md` 起草(`PermissionPatterns` / `Tool.sourceCategory()` / 5 override / `StrictPermissionPolicy` 升级对照表 + pattern grammar 形式表 + back-compat 路径说明)(预估 30min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.47 → v1.5.48` 行(本 Story 实施记录,14 节概要对齐 `#029`)(预估 15min)
- [ ] **T-doc-5** `dsh_agent_design.md` §5.5 Slot 4 `StrictPermissionPolicy` design intent 段补「🆕 v1.5.48 Story #031 落地 pattern matching」段(类别白名单语义兑现)(预估 10min)
- [ ] **T-doc-6** `constitution.md` §10 R-13 风险登记:`Story #031` 标记「已缓解」+ 第 16 次 0 binary delta 验证结果(预估 5min)
- [ ] **T-doc-7** `ROADMAP.md` 段一 ✅ 已完成表加 `#031` 行(2026-09-30,~640 pass / 0 fail / R-13 0 binary delta 第 16 次 / 0 新 ErrorCode / +21 new case)+ §6 提议 Story 列表加 `#031` 行(从 `⬜ 待实施` → `✅ 已合`)(预估 10min)
- [ ] **T-doc-8** `lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#031` 段落(Story 推 master 后开,本 Story 内**不**强制;留 OQ-Future,预估 30min)
- [ ] **T-doc-9** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.47` → `v1.5.48`)(预估 5min)

> **P5 总耗时**:~145 min(~2.4h)

---

## P6:PR 提交与合并

- [ ] **T-pr-1** 创建分支 `feature/story-031-permission-policy-pattern-matching`(基于 main)(预估 2min)
- [ ] **T-pr-2** 累计 commit(P1 + P2 + P3 + P4 + P5 共 ~30 commit),每 commit 格式 `feat(permission): T-NN <一句话>`(预估 30min)
- [ ] **T-pr-3** 推送到 `origin/feature/story-031-permission-policy-pattern-matching`(预估 2min)
- [ ] **T-pr-4** `gh pr create --base main --head feature/story-031-permission-policy-pattern-matching --title "feat(permission): Story #031 permission-policy-pattern-matching" --body "$(cat <<'EOF'
## Summary

- 🆕 Story #031 PermissionPolicy pattern matching(`*` 通配 + `<category>:*` 类别前缀)
- `PermissionPatterns` 静态工具类 + 3 类 pattern(`*` / `<name>` / `<category>:*`)纯 JDK 8 String ops
- `Tool` interface 加 1 default method `sourceCategory()`(默认 `"local"`)
- 5 个 Tool 实现 override(`McpToolAdapter` / `SkillTool` / `RemoteAgentTool` / `DelegateTool` / 其它默认 local)
- `StrictPermissionPolicy.check()` 升级为 pattern matching(back-compat 字面 equals 0 回归)
- `StrictPermissionPolicyProvider` `@Autowired ToolRegistry` 注入,**0 SPI interface 改动**
- demo-product yml 改 `allow-list: ["*"]` 一行解决 12 个 Tool(替代 Story #029 静态枚举)
- 0 新 ErrorCode(复用 Story #029 `LINGS-P01`),reason 字符串升级含 pattern 信息

## Test plan

- [x] L1 Unit `PermissionPatternsTest` 6 case + 2 边界 case
- [x] L1 Unit `ToolSourceCategoryTest` 5 case
- [x] L1 Unit `StrictPermissionPolicyPatternTest` 5 case pattern + 1 case back-compat
- [x] L1 Unit `StrictPermissionPolicyReasonTest` 1 case
- [x] L2 Slice `AgentFactoryPatternMatchingIT` 1 case
- [x] L3 黑盒 `DemoProductPermissionWildcardIT` 1 case
- [x] L3 黑盒 `DemoProductPermissionCategoryPatternIT` 6 case inline
- [x] 全模块 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 无 fail
- [x] Story #029 back-compat 测试(`StrictPermissionPolicyTest` 5 case 字面 equals)0 回归
- [x] R-13 mitigation (d) baseline 镜像第 16 次 PASS 0 binary delta

### R-13 dependency:tree 自查

\`mvn -pl lingshu-core dependency:tree -Dverbose\` pre/post diff 仅时间戳差异,无新增 Maven 坐标。复用 JDK 8 `String` / `Map` / `HashMap` / `Collections.emptyMap()` + Lombok `@Value` + Spring `@Component` / `@Autowired` 全 JDK 8 standard + 已锁 13 项依赖表内。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"`(预估 10min)
- [ ] **T-pr-5** 等 CI 全绿 + reviewer approval 后 merge(走 squash merge 保持 main commit 历史 clean)(预估 10min)

> **P6 总耗时**:~54 min

---

## 总耗时估算

| Phase | 时长 |
|---|---:|
| P1 实现 | ~107 min(~1.8h)|
| P2 测试 | ~435 min(~7.25h)|
| P3 AC 验证 | ~113 min(~1.9h)|
| P4 依赖构建 | ~50 min(~0.8h)|
| P5 文档同步 | ~145 min(~2.4h)|
| P6 PR 提交 | ~54 min(~0.9h)|
| **合计** | **~904 min(~15.1h)** |

> **Story #031 体量**与 Story #029(`~750 min`)+ Story #028(`~1300 min`)+ Story #027a(`~770 min`)同量级;核心文件 1 新增 + 9 modify 比 #029 略多(因 Tool SPI 5 处 override),但每个 modify 1-3 行最小侵入;测试 21 case 与 #029(18 case)+ #028(39 case)同量级

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-09-30
**Tasks version**: v0.1 Draft