# Tasks: Story #032 `web-fetch-local-tool`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,严格按 P1—P6 拆分 commit)
>
> **实施顺序严格按 plan §6**:`WebFetchTool.java` 主体 → `LocalToolsAutoConfiguration` 加 `@Bean` → L1 单元测试 → L2 集成测试(mock HTTP server + Spring 装配)→ L3 blackbox IT → demo yml 接线 → README 同步 → AC 验证 → R-13 dep-tree 自查 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#032` 复用 JDK 8 `HttpURLConnection` / `BufferedReader` / `InputStreamReader` / Jackson `JsonNode` + Spring `@Component` / `@Bean` 全 JDK 8 built-in 0 新二进制,需验证 0 binary delta 第 17 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(tool): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(1 新增 + 2 modify)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/WebFetchTool.java` 新增 —— `@Component("webFetchTool") public class WebFetchTool implements Tool`(对齐 `ReadTool` / `BashTool` precedent)+ (1) `name()="web_fetch"` 常量 `TOOL_NAME`;(2) `description()` 静态字符串含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported";(3) `inputSchema()` 静态 JSON Schema `{url: string required, max_bytes?: integer}`;(4) `execute(call, ctx)` 4 段:`args.path("url").asText()` 检查 → `args.has("max_bytes")` 解析 maxBytes → `ctx.http().get(url)` 委托 → truncation marker;catch `AccessDeniedException` → `ToolResult.error("[LINGS-S01] ...")`;catch `IOException` → `ToolResult.error("HTTP fetch failed: ...")`;(5) `sourceCategory()="local"` override(对齐 `Tool` default #031);类级 Javadoc 覆盖 (1) Claude Code parity rationale + (2) GET-only 范围锁定 + (3) HTTPS transparent + (4) `@Component` 而非 `@Autowired` 因 stateless + (5) `ctx.http()` 必须走 WhitelistedHttpClient.check() 防御(预估 30min,spec §4 AC-NN-1—AC-NN-4 + AC-NN-7 + AC-NN-10—AC-NN-12)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfiguration.java` modify —— 加 `@Bean public Tool webFetchTool() { return new WebFetchTool(); }`;现有 4 `@Bean`(`readTool` / `writeTool` / `listDirTool` / `bashTool`)**0 改动**;Bean 命名对齐 v1.5.28 §5.5 plugin non-Slot type Bean 样板(`<role>Tool` 而非 `<slot>Provider_<name>`,因 Tool 是 non-Slot type Bean)(预估 5min,spec §4 AC-NN-13)
- [ ] **T03** `lingshu-examples/demo-product/src/main/resources/application.yml` modify —— `agent.sandbox.domain-whitelist` 段加 5 示例 domain(`api.openai.com` / `api.anthropic.com` / `raw.githubusercontent.com` / `huggingface.co` / `localhost` for IT)+ 注释 `🆕 Story #032 — domain-whitelist now enforced for local web_fetch Tool (Claude Code parity, Read/Bash/WebFetch triplet complete);HTTPS supported transparently`;引用 dsh §6.3 + spec §3.4(预估 5min,spec §4 AC-NN-15)

> **P1 总耗时**:~40 min

---

## P2:测试(4 测试文件 + 15 case)

- [ ] **T04** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/WebFetchToolTest.java` 新增 L1 Unit —— 8 case(AC-NN-1—AC-NN-4 + AC-NN-7 + AC-NN-10—AC-NN-12):
   - case 1:`tool.name() == "web_fetch"`
   - case 2:`tool.description()` 含 "domain whitelist"
   - case 3:`tool.inputSchema()` 静态 JSON Schema 校验 `properties.url.type=="string"` + `properties.max_bytes.type=="integer"` + `required==["url"]`
   - case 4:`tool.sourceCategory() == "local"`
   - case 5:L1 happy path mock — mock `ToolExecutionContext`,注入 `WhitelistedHttpClient(Collections.emptyList())`(空白名单)+ execute `args={url:"http://anywhere"}` → `ToolResult.error` 含 `[LINGS-S01] Domain not whitelisted: anywhere`(走 sandbox 防御,反向 AC-7)
   - case 6:truncation > 1MB — mock `ctx.http().get(url)` 返 2MB string(用 `StringUtils.repeat('a', 2_097_152)`)+ execute 默认 maxBytes → `result.getContent().length() == 1048576 + markerLen`
   - case 7:`max_bytes` 覆盖 — args `{url, max_bytes: 100}` + mock 返 1KB string → `result.getContent().length() == 100 + markerLen`
   - case 8:URL 缺失 — args `{}` → `result.isError() && result.getContent() == "url is required"`
   (预估 60min)
- [ ] **T05** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/WebFetchToolHttpServerIT.java` 新增 L2 集成 —— 4 case(AC-NN-5 + AC-NN-6 + AC-NN-8 + AC-NN-9):
   - fixture:`WebFetchTestSupport.findFreePort()` + `HttpServer.create(new InetSocketAddress(port), 0)` + `HttpHandler` 返可控 body / status code / delay
   - case 1(L2 happy path):mock server 200 返 "hello world" + `WhitelistedHttpClient(["localhost"])` + execute → `ToolResult.success(callId, "hello world")`
   - case 2(L2 HTTPS 透明):mock server at `http://` 但验证 `ctx.http()` 真接通(简化:断言 mock server 收到 `User-Agent: ChaOS-LingShu-Sandbox/1.0` 头)
   - case 3(L2 域在白名单 happy path):`WhitelistedHttpClient(["localhost"])` + mock server at localhost → 200 返回 body(同 case 1 但显式验证 whitelist 命中路径)
   - case 4(L2 HTTP 404):mock server 404 返 "not found" + execute → `result.isError() && result.getContent().contains("HTTP fetch failed") && result.getContent().contains("404")`
   (预估 60min)
- [ ] **T06** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfigurationWebFetchIT.java` 新增 L2 Spring 装配 —— 2 case(AC-NN-13 + AC-NN-14):
   - case 1:`@SpringBootTest(classes = LocalToolsAutoConfiguration.class)` 启动 → `applicationContext.getBean("webFetchTool", Tool.class)` 拿到非 null + `tool.name()=="web_fetch"`
   - case 2:`toolRegistry.findByName("web_fetch")` 不返 `Optional.empty()` + `tool.sourceCategory()=="local"`
   (预估 30min)
- [ ] **T07** `lingshu-examples/demo-product/src/test/java/.../DemoProductWebFetchIT.java` 新增 L3 blackbox —— 1 case(AC-NN-15):
   - `@SpringBootTest(classes = DemoProductApplication.class, properties = {"agent.sandbox.domain-whitelist=localhost"})` 启动 demo-product + mock HTTP server at localhost + LLM prompt "fetch http://localhost:port/data" → 验证 response 含 mock server 返回的 body + `agent.sandbox.domain-whitelist` 真生效(测试用 domain 加进 whitelist)
   (预估 60min)

> **P2 总耗时**:~210 min(~3.5h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=WebFetchToolTest` 验证 8 case(name/description/inputSchema/sourceCategory + L1 sandbox denial + truncation + max_bytes + URL missing)(预估 5min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=WebFetchToolHttpServerIT` 验证 4 case L2 真发请求(happy path + HTTPS 透明 + whitelist 命中 + HTTP 404)(预估 10min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=LocalToolsAutoConfigurationWebFetchIT` 验证 2 case Spring 装配(webFetchTool Bean 注册 + ToolRegistry.findByName 命中)(预估 10min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductWebFetchIT` 验证 1 case demo-product 端到端(预估 15min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test` 全模块无 fail,新增 8 case 全过,647 pre test 0 回归(预估 15min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-examples/demo-product test` 全模块无 fail(预估 15min)

> **P3 总耗时**:~70 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-032-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-032-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-031-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-032-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-product package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min

---

## P5:文档同步(7 文件)

- [ ] **T-doc-1** `README.md` 顶部「更新日期」段加 🆕 v1.5.49 Story #032 顶部 blockquote(WebFetch 本地 HTTP 抓取已上线,Claude Code parity 三件套完成)(预估 10min)
- [ ] **T-doc-2** `README.md`「核心特性」段补 🌐 WebFetch 本地 HTTP 抓取 bullet:`web_fetch` Tool 直接调 `RuntimeSandbox.http()` → `WhitelistedHttpClient` domain-whitelist 守卫;HTTPS 透明;GET-only(POST 走 MCP fetch server);1 MB truncation(预估 10min)
- [ ] **T-doc-3** `README.md`「Story 路线图」段追加 #032 retrospective(5 文件改动 / 15 case AC 黑盒验证 / R-13 0 binary delta 第 17 次 / Claude Code parity primary / `WhitelistedHttpClient` 基建启用 / 0 新 ErrorCode / 0 新 Maven 依赖 / 与 #033 Path B + Mitigation 1 配合)(预估 15min)
- [ ] **T-doc-4** `specs/032-web-fetch-local-tool/quickstart.md` 起草(给 Alice 30min 跑通 WebFetch,模板对齐 #031 quickstart.md)(预估 30min)
- [ ] **T-doc-5** `specs/032-web-fetch-local-tool/data-model.md` 起草(`WebFetchTool` 4 method + 1 sourceCategory override + inputSchema JSON Schema + execute 4 段逻辑 + truncation marker + 错误处理 2 catch)(预估 30min)
- [ ] **T-doc-6** `dsh_agent_design.md` §13 changelog 加 `v1.5.48 → v1.5.49` 行(本 Story 实施记录,8-10 节概要对齐 #031)(预估 15min)
- [ ] **T-doc-7** `dsh_agent_design.md` §6.3 `WhitelistedHttpClient` 段补「🆕 v1.5.49 Story #032 启用」+ `WebFetchTool` 引用;§6.3 L5726 占位 `WebFetch 域名白名单` + L6383 占位 `web/ WebFetch` 改为实装段引用(预估 10min)
- [ ] **T-doc-8** `constitution.md` §10 R-13 风险登记:`Story #032` 标记「已缓解」+ 第 17 次 0 binary delta 验证结果(预估 5min)
- [ ] **T-doc-9** `ROADMAP.md` 段一 ✅ 已完成表加 `#032` 行(2026-10-01,~662 pass / 0 fail / R-13 0 binary delta 第 17 次 / 0 新 ErrorCode / +15 new case)+ §6 提议 Story 列表加 `#032` 行(从 `⬜ 待实施` → `✅ 已合`)(预估 10min)
- [ ] **T-doc-10** `lingshu-docs` 仓 `docs/concepts/tools.md` 加 `web_fetch` 段(Story 推 master 后开,本 Story 内**不**强制;留 OQ-Future,预估 30min)
- [ ] **T-doc-11** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.48` → `v1.5.49`)(预估 5min)

> **P5 总耗时**:~170 min(~2.8h)

---

## P6:PR 提交与合并

- [ ] **T-pr-1** 创建分支 `feature/story-032-web-fetch-local-tool`(基于 main)(预估 2min)
- [ ] **T-pr-2** 累计 commit(P1 + P2 + P3 + P4 + P5 共 ~25 commit),每 commit 格式 `feat(tool): T-NN <一句话>`(预估 30min)
- [ ] **T-pr-3** 推送到 `origin/feature/story-032-web-fetch-local-tool`(预估 2min)
- [ ] **T-pr-4** `gh pr create --base main --head feature/story-032-web-fetch-local-tool --title "feat(tool): Story #032 web-fetch-local-tool — Claude Code parity 三件套完成" --body "$(cat <<'EOF'
## Summary

- 🆕 Story #032 本地 `WebFetchTool`(`name()="web_fetch"`)打通 `ctx.http()` → `WhitelistedHttpClient` → domain-whitelist 守卫
- Claude Code parity:本地 Tool 三件套(`ReadTool` fs / `BashTool` process / `WebFetchTool` http)完成
- HTTPS 透明(JDK `HttpsURLConnection`,0 新 Maven 依赖)
- GET-only 范围锁定(POST/PUT/DELETE 走 MCP fetch server)
- 1 MB truncation marker(`\n...[truncated, original N bytes]`)
- 0 新 ErrorCode(复用 #028 `LINGS-S01`)
- 0 新 Maven 依赖

## 关键变更

- **新增** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/WebFetchTool.java`(@Component `name="webFetchTool"` implements Tool,~120 行)
- **修改** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfiguration.java` +5 行(`@Bean public Tool webFetchTool()`)
- **修改** `lingshu-examples/demo-product/src/main/resources/application.yml` `domain-whitelist` 段加 5 示例 domain
- **修改** `README.md` 加核心特性 bullet + Story 路线图段 + 版本号同步
- **新增** `WebFetchToolTest` L1 8 case
- **新增** `WebFetchToolHttpServerIT` L2 4 case 真发请求
- **新增** `LocalToolsAutoConfigurationWebFetchIT` L2 2 case Spring 装配
- **新增** `DemoProductWebFetchIT` L3 1 case demo-product 端到端

## Acceptance Criteria

15 case AC 黑盒全过:
- L1: AC-NN-1—AC-NN-4 + AC-NN-7 + AC-NN-10—AC-NN-12 (8 case)
- L2: AC-NN-5 + AC-NN-6 + AC-NN-8 + AC-NN-9 + AC-NN-13 + AC-NN-14 (6 case)
- L3: AC-NN-15 (1 case)

## R-13 dependency:tree 自查

`mvn -pl lingshu-core dependency:tree` pre/post diff:
- 0 新 Maven 坐标(`HttpURLConnection` / `BufferedReader` / `InputStreamReader` / Jackson `JsonNode` + Spring `@Component` / `@Bean` 全 JDK 8 built-in)
- R-13 mitigation (d) baseline 镜像**第 17 次 PASS 0 binary delta**

## 关键不变项

- `Tool` SPI 0 改动(`sourceCategory()` default 方法已在 #031 加)
- `ToolExecutor` 5 步流水线 0 改动(§4.10.1 硬规则 2 守住)
- `RuntimeSandbox` interface / `WhitelistedHttpClient` class 0 改动(只是被 `WebFetchTool` 消费)
- `AgentConfig` schema 0 字段新增
- `LinearTurnEngine` ReAct 主循环 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段 0 改动
- 9 Slot 顶层体系不变
- JDK 8 兼容(无 `var` / `List.of` / `sealed` / `records` / `java.net.http.HttpClient` JDK 11+)
- Spring AI `ChatClient.tools().call()` 仍禁止使用(§4.10.1 硬规则 2)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"`(预估 5min)
- [ ] **T-pr-5** CI 跑通(R-13 enforcer + 单元测试 + 集成测试);如有 MCP heartbeat flake 复现,确认 pre-existing(CLAUDE.md 已文档化 2-3 MCP heartbeat flake pre-existing)(预估 15min)
- [ ] **T-pr-6** `git rebase main`(吸收后续 commit,如有);最终 merge commit `--no-ff`(预估 10min)
- [ ] **T-pr-7** `git push origin main`(推送到主分支;若用 PR 模式则由 reviewer 合并)(预估 2min)

> **P6 总耗时**:~66 min

---

## 总耗时估算

| 阶段 | 任务 | 时间 |
|---|---|---|
| P1 | 接口与核心实现 | ~40 min |
| P2 | 测试 | ~210 min(~3.5h) |
| P3 | AC 黑盒验证 | ~70 min |
| P4 | 依赖与构建 | ~50 min |
| P5 | 文档同步 | ~170 min(~2.8h) |
| P6 | PR 提交与合并 | ~66 min |
| **总计** | | **~606 min(~10h)** |

---

## 反模式提醒(本 Story 拒绝)

- ❌ 引入 `OkHttp` / `Apache HttpClient` / `java.net.http.HttpClient`(JDK 11+)/ `WebClient` —— 0 新依赖硬约束
- ❌ 直调 JDK `HttpURLConnection` 绕过 `ctx.http()` —— 违反 sandbox 防御,反向 AC-7 禁止
- ❌ 实现 POST/PUT/DELETE —— scope creep,MCP fetch server 已覆盖
- ❌ 实现 streaming 响应 —— 本 Story 简单 in-memory 优先
- ❌ 加 `webFetch` 段到 `AgentConfig` schema —— 0 schema 改动,复用 `domain-whitelist` + 默认 30 s timeout
- ❌ `WebFetchTool` 写成 `@Configuration` + 工厂 Bean 模式 —— `@Component` 直接 new 最简

---

**Status**: ✅ Tasks Ready — 等待用户批准 / 实施开始
