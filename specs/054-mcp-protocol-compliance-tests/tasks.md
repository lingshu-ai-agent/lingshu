---
description: "Task list for Story #054 MCP 协议合规测试收口"
---

# Tasks: MCP 协议合规测试收口(PR #90 两处评审缺陷收口)

**Input**: Design documents from `/specs/054-mcp-protocol-compliance-tests/`
**Prerequisites**: plan.md ✅ / spec.md ✅ / research.md ✅(10 条决策)/ constitution.md ✅

**Tests**: **是,本 Story 全部内容就是测试**(spec FR-001—FR-030)。测试任务**不是**可选附录,
它们**就是**交付物本身;「实现」段落指的是 **fixture / 测试基建**而非生产代码。

**⚠️ 生产代码改动 = 0 行**。`lingshu-core/src/main/java/ai/lingshu/core/mcp/**` 全部只读。
唯一非测试文件改动 = `lingshu-examples/demo-product/src/main/resources/application.yml` 1 行。

## Format: `[ID] [P?] [Story] Description`

- **[P]**:可并行(不同文件、无未完成依赖)
- **[Story]**:所属用户故事(US1 / US2 / US3);Setup / Foundational / Polish 段**不带** story 标签

---

## Phase 0: Pre-flight(不写代码,先锁基线)

**Purpose**:在动任何一行之前把「改动前的世界」固定下来,否则后面无法证明 0 regression。

- [ ] T001 从 `main` 切出工作分支 `054-mcp-protocol-compliance-tests`(**禁止** `git push origin main`;后续一律 branch + PR + `--merge`;Mac 上 push 走 `git -c http.sslVersion=tlsv1.2 push origin 054-mcp-protocol-compliance-tests`)
- [ ] T002 隔离工作树里的**无关未提交改动**:`README.md` / `UPDATEINFO.md` / `specs/ROADMAP.md`(PR #90 文档产物)与 `lingshu-examples/demo-product/src/main/resources/application.yml`(用户本地误改)**不要**误删,也不**不要**混进本 Story 的 commit —— 用 `git add <显式文件路径>` 逐个暂存,禁止 `git add -A` / `git add .`
- [ ] T003 跑 R-13 mitigation (d) baseline **pre-mirror**:`mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -Dverbose > /tmp/054-deptree-pre.txt 2>&1`,再 `grep -v '^\[INFO\] ---' /tmp/054-deptree-pre.txt | grep -v 'BUILD\|Total time\|Finished at' | md5sum` 记录摘要
- [ ] T004 跑测试基线 **pre-mirror**:`mvn -pl lingshu-core,lingshu-examples/demo-product test > /tmp/054-test-pre.txt 2>&1`,从输出记录 `Tests run: N, Failures: F, Errors: E, Skipped: S` 总数与**已知 pre-existing flake**(CLAUDE.md 已记录的 2 个 MCP heartbeat flake),这是后续 0 regression 的比对基准
- [ ] T005 记录 MCP 包改动前的 `@Test` 清单:`find lingshu-core/src/test/java/ai/lingshu/core/mcp -name '*.java' | sort` + `grep -c '@Test' <每个文件>`,落盘为 `/tmp/054-mcp-tests-pre.txt`(research.md 结论:78 个 `@Test` / 27 文件 + 3 fixture)
- [ ] T006 确认 `banned-dependencies` enforcer 改动前即 passed:`mvn -pl lingshu-core validate` 输出无 `Banned dependencies` fail

**Checkpoint**:基线三件套(pre-deptree / pre-test / pre-@Test 清单)全部落盘,分支已建,工作树状态已知。

---

## Phase 1: Setup(Phase 0/1 设计产物落地)

**Purpose**:plan.md 的 Phase 1 输出目前**尚未落盘**,`tasks.md` 依赖它们;先补齐。

- [ ] T007 创建 `specs/054-mcp-protocol-compliance-tests/data-model.md`:记录 (a) fixture 开关模型(`port` / `delayMs` / `exitAfter` / `pushIntervalMs` / `closeSseAfter` / `malformedRatio` 保留 + `expireSession` 新增默认 false + `noEndpointAnnounce` 新增默认 false + `dontReplyHealth` 删除)→ 对应规范行为的映射表;(b) **请求记录器模型** `RecordedRequest { method, id(Long,null=notification), contentType, sessionIdHeader, body }` + `recorded()` / `clearRecorded()` 两条访问路径;(c) 断言可见性矩阵(哪些 SC/FR 能从 recorder 的哪一条记录看出)
- [ ] T008 创建 `specs/054-mcp-protocol-compliance-tests/contracts/fixture-protocol.md`:描述 fixture 作为「一个规范 MCP server」对两种 transport 的**线上契约** —— SSE(`GET` 流首帧 `event: endpoint` + data=相对 URI;后续 `event: message` 按 JSON-RPC id 推;心跳走 `ping`)与 Streamable HTTP(单端点、`Mcp-Session-Id` 回放、`application/json` 与 `text/event-stream` 两种响应 content-type、404 语义)的帧形状与响应头
- [ ] T009 创建 `specs/054-mcp-protocol-compliance-tests/quickstart.md`:跑单个用例 / 跑整个 MCP 包 / 跑全量 + R-13 baseline 镜像比对的验证指南(含「需要可注入时间的用例必须 `new` connection 而非走 `McpServerConnectionFactory.create()`」这条提醒)
- [ ] T010 通读 research.md 的 10 条决策,在任务实施时逐条对照 —— 特别是 **决策 2 的两个时间下限**(重连退避首跳 ≥ 1 s 因 `computeBackoffMs` 的 `base = 1_000L` 不可注入;`await()` 有 1 ms 地板故传 0 ≠ 立即返回)与 **决策 10**(`echo-stdio.py` 的 line-JSON framing **不改**,是既有有意偏离)

**Checkpoint**:Phase 1 四份产物齐备,后续所有任务都有可查的契约与决策依据。

---

## Phase 2: Foundational(MCP HTTP 测试基建合流 —— 阻塞全部 US)

**Purpose**:把 MCP HTTP 测试基建从「两套 fork fixture + 系统属性开关 + 秒级 sleep」升级为
**单份规范形状 fixture**。这一层不做完,US2 / US3 的任何用例都写不出来也跑不对。

> **本 Phase 是 research.md 决策 1(FR-010 合并 fixture)的落地。**
> 旧 `TestMcpSseServer` 是 `TestMcpHttpServer` 的**手工复制 fork**(其 `writeJsonRpc` 在 L231-242
> 被重新实现一遍),且 `/sse` handler(L154-209)**从不写任何 `event:` 行** —— 这正是缺陷 B 的
> 根因:测试照着已被淘汰的非规范协议写,于是 `a417464` 重写后仍全绿。

- [ ] T011 重写 `lingshu-core/src/test/java/ai/lingshu/core/mcp/fixture/TestMcpHttpServer.java` 的**路由骨架**:删掉 5 个老端点(`/initialize` / `/notifications/initialized` / `/tools/list` / `/tools/call` / `/health`,分别在 L92 / L106 / L114 / L139 / L152),改为**同一个 `HttpServer` 实例**上注册两个 context —— SSE 走 `GET /sse`,Streamable HTTP 走 `POST /mcp`;两者共用同一个请求记录器与同一份 JSON-RPC 应答逻辑
- [ ] T012 在重写后的 `TestMcpHttpServer` 实现 **SSE handler**:`GET /sse` 响应头 `Content-Type: text/event-stream` + `Cache-Control: no-cache`,**首帧无条件**写 `event: endpoint` + `data: <相对 POST URI>`(data 里不得写绝对 URL,FR-012);之后按收到的 JSON-RPC 请求的 `id` 把应答以 `event: message` + `data: <完整 JSON-RPC 应答>` 推回流上;`noEndpointAnnounce=true` 时跳过首帧(供 FR-016 反证用)
- [ ] T013 在重写后的 `TestMcpHttpServer` 实现 **Streamable HTTP handler**:`POST /mcp` 是唯一端点(URL 永不是路径前缀),记录并回放 `Mcp-Session-Id` 头;按请求 `Accept` 分别以 `application/json` 与 `text/event-stream` 两种 `Content-Type` 应答;`expireSession=true` 时所有 POST 一律返 HTTP 404(供 FR-020 会话过期链路用)
- [ ] T014 在重写后的 `TestMcpHttpServer` 内置**请求记录器**(research.md 决策 4):内部类 `RecordedRequest { String method; Long id; String contentType; String sessionIdHeader; String body; }`,用 `AtomicLong` 序号 + `Collections.synchronizedList(new ArrayList<RecordedRequest>())` 保序(JDK 8:**不**用 `record` / `var` / `List.of`);对外两条路径 `fixture.recorded()`(按到达顺序)与 `fixture.clearRecorded()`
- [ ] T015 收窄 `TestMcpHttpServer` 的系统属性开关(research.md 决策 5):**删** `dontReplyHealth` 开关与整个 `GET /health` 端点(FR-007 规定心跳走 JSON-RPC `ping`,不再有厂商私有端点);**加** `expireSession`(默认 `false`)与 `noEndpointAnnounce`(默认 `false`);其余 6 个(`port` / `delayMs` / `exitAfter` / `pushIntervalMs` / `closeSseAfter` / `malformedRatio`)保留,`delayMs` 降级为「仅供单条**故意延迟**用例」使用
- [ ] T016 删除 `lingshu-core/src/test/java/ai/lingshu/core/mcp/fixture/TestMcpSseServer.java`(handler 已全部并入 `TestMcpHttpServer`,SC-006 要求单份实现、0 重复);删前先 grep 全仓确认无任何残留引用,若有则改指 `TestMcpHttpServer` 的新 API
- [ ] T017 修改 `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpTestSupport.java`:新增**开关参数透传** —— 启动 subprocess fixture 时把当前用例设定的 `expireSession` / `noEndpointAnnounce` / `delayMs` 等 sysprop 一并传给子进程(当前只透传端口相关项),保证 L3 真 socket 场景与 L1 in-process 场景**行为一致**
- [ ] T018 全 MCP 测试包**强制 IPv4 loopback**:fixture 一律绑 `HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)`,断言 URL 一律用 `127.0.0.1`,**禁用** `localhost` 字面量(constitution §6 兼容性矩阵:Windows WSL2 把 `localhost` 先解析成 `::1` 会导致 flake)
- [ ] T019 修 `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionReconnectTest.java` 的 `pickLikelyClosedPort()`(L109-113)stub —— 它**永远返回 1**,等于没做端口选择;改为「显式 `fixture.stop(0)` 关掉 fixture 后复用它的真实端口」,制造一个确定已关闭的端口(决策 2 提醒:关闭端口后重连首跳 ≥ 1 s,单用例 2 s 预算仍够)
- [ ] T020 为「需要可注入时间的用例」建立统一写法约定:这类用例**必须**直接 `new SseMcpServerConnection(cfg, hbIntervalMs, hbTimeoutMs, reconnectCapMs)` / `new StreamableHttpMcpServerConnection(cfg, ...)`(4 参构造器 L146-149 / L110-113,已存在、零生产改动),**不得**走 `McpServerConnectionFactory.create(cfg)` —— factory 只用 1 参构造器,拿不到注入值;在受影响的测试文件顶部写一行注释说明
- [ ] T021 编译 + 冒烟:`mvn -pl lingshu-core test-compile` 通过;`mvn -pl lingshu-core test -Dtest=TestMcpHttpServer*` 之类冒烟(若无同名用例则跳过)确认新 fixture 可起可停、无端口泄漏

**Checkpoint**:单份规范形状 fixture 可用、记录器可查、开关按契约收窄、旧 fork 已退役。
**从此处起** US1 / US2 / US3 才能各自独立实施与验证。

---

## Phase 3: User Story 1 — demo-product stdio 相对路径(P1)🎯 MVP

**Goal**:修掉 PR #90 贡献者留下的 Windows 绝对路径 `d:\lingshu\...\echo-stdio.py`,
让 demo-product 在**任意机器 / CI** 上开箱即用。

**Independent Test**:干净 checkout → `mvn -pl lingshu-examples/demo-product spring-boot:run`
→ 日志可见 `echo-stdio` 连通并列出工具;全程**零次**手工改 yml。

### Tests for User Story 1 ⚠️

> US1 原本只需人工端到端;为满足 constitution §11.2「不省略 AC 黑盒验证」,
> 补一条**可自动跑**的 AC,把「不得是机器相关绝对路径」这条约束锁死。

- [ ] T022 [US1] 新建 `lingshu-examples/demo-product/src/test/java/ai/lingshu/examples/demoproduct/DemoProductStdioArgsPathTest.java`(L1,3 个 case):读 `src/main/resources/application.yml`,定位 `mcp.servers[0](name=echo-stdio).args` —— (a) `args` 首元素**不是**绝对路径(无盘符 `X:\`、无前导 `/`、无 `~`);(b) 首元素以 `src/main/resources/mcp-servers/echo-stdio.py` 结尾;(c) 首元素相对模块 basedir 解析后**确实是存在的文件**

### Implementation for User Story 1

- [ ] T023 [US1] 改 `lingshu-examples/demo-product/src/main/resources/application.yml` **L215 一行**:把 `args:` 下当前指向 `application.yml` 自身 / 贡献者 `d:\...` 绝对路径的那一行,换成仓库内相对路径 `src/main/resources/mcp-servers/echo-stdio.py`(与 `a417464^` 即 Story #025 的写法一致);**保持 yml 其余部分 0 改动**(comment / `heartbeat-interval-ms` / `env` 提示等一律不动)
- [ ] T024 [US1] 确认相对路径可用的**代码依据未被破坏**:`StdioMcpServerConnection.spawn()`(L282-302)用裸 `new ProcessBuilder(buildCommandLine())` + `pb.redirectError(PIPE)`,**没有** `.directory(...)`,子进程继承 JVM CWD —— 而 `mvn -pl <module> spring-boot:run` fork 时 CWD = 模块 basedir;`McpServerConfig` 共 10 字段且**无 cwd 字段**,故 FR-003 天然成立(决策 8);此步**只核对不改代码**,若发现被改动则立即停下报告
- [ ] T025 [US1] 人工端到端验证(quickstart 步骤):`mvn -pl lingshu-examples/demo-product spring-boot:run`,确认 `echo-stdio` 进程起来、`tools/list` 返回工具、启动日志无 `LINGS-M03` 连接失败;`python3 src/main/resources/mcp-servers/echo-stdio.py` 单独手跑也应能正常应答
- [ ] T026 [US1] 跑 `mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductStdioArgsPathTest` 全绿

**Checkpoint**:US1 可独立验证 —— demo-product 在任意机器上开箱即用,无需改 yml。

---

## Phase 4: User Story 2 — SSE 规范握手合规(P1)

**Goal**:把 SSE 相关测试从「测一条生产代码已经不走的 `/health` + 无 `event:` 协议路径」
扶正到「MCP 规范握手四段链路 + JSON-RPC `ping` 心跳」,并为 SC-003 要求的
**每条链路都有「缺陷被重新引入时必失败」的反证用例**。

**Independent Test**:`mvn -pl lingshu-core test -Dtest='SseMcpServerConnection*'` 全绿,
且把 `TestMcpHttpServer` 的 SSE handler 改回「不写 `event: endpoint`」时,
`SseMcpServerConnectionStartTest` 的握手用例**必须失败**。

### Tests for User Story 2 ⚠️

- [ ] T027 [P] [US2] 在 `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionStartTest.java` 写**握手四段断言**(替换既有 `start_fakeServer_5stepsReachesConnected` L55 / `start_alreadyConnected_noop` L116,不许删断言):① 连接后 fixture 收到 `GET /sse`;② fixture 首帧确实是 `event: endpoint`;③ transport 随后 POST 到**公告里给出的那个 URI**(用记录器核对 POST 路径与公告 data 一致);④ 应答经 `event: message` 回到流上且 **JSON-RPC id 与请求 id 相等**,最终 `ConnectionState == CONNECTED`
- [ ] T028 [P] [US2] 在同一文件加 **相对 URI 解析用例**(FR-012):公告 data 写成相对形式(如 `/message?sessionId=abc`),断言 transport 把它解析成对 fixture **同一 host:port** 的绝对 URL 后再 POST —— 换句话说 URI 解析以 SSE 流所在 URL 为基准,不以 JVM CWD 为基准
- [ ] T029 [P] [US2] 在同一文件加 **并发 / 乱序 id 关联用例**(FR-013 / FR-014):连发 2 个 JSON-RPC 请求(如 `initialize` 与 `tools/list`),fixture **按相反顺序**推 2 个 `event: message`,断言 transport 仍把每个应答交给正确的调用方 —— id 关联不依赖到达顺序
- [ ] T030 [P] [US2] 在同一文件加 **无公告必失败的反证用例**(FR-016 / SC-003):以 `noEndpointAnnounce=true` 起 fixture,断言 `start()` 抛 `IOException` 且消息含 `no 'endpoint' event` 语义、连接不进入 `CONNECTED` —— 这是「测试真的在测协议」的保证
- [ ] T031 [P] [US2] 在同一文件加 **心跳走 JSON-RPC `ping` 的用例**(FR-015):断言心跳周期内 fixture 收到 `{"method":"ping"}` 且 **没有** 任何 `GET /health` 请求(记录器核对)—— 直接锁死 FR-007

### Implementation for User Story 2

- [ ] T032 [US2] 重写 `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionHeartbeatTest.java` **全部 4 个死代码用例**(该文件 162 行,4 个 `@Test` 全测已被淘汰的 `GET /health` 语义,却仍绿):`probe_healthOk` L63 → ping 成功保活;`probe_health5xx_disconnects` L91(现用 `dontReplyHealth=true`)→ ping 失败/无应答即断连;`probe_healthTimeout_disconnects` L116(现用 `delayMs=2000`)→ ping 超时即断连(用 200 ms 级可注入超时,不写秒级 sleep);`probe_doubleCheck_recoveredHeartbeat` L142 → 断连后恢复心跳重新计入 `lastHeartbeatAt`
- [ ] T033 [US2] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionListenerTest.java` 的 3 个用例:状态迁移期望序列对齐**新握手**(旧序列基于 5 端点方案,`CONNECTING → CONNECTED` 的触发条件已变)
- [ ] T034 [US2] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionReconnectTest.java`:① `sseStreamBroken_reconnects`(L117)按新协议断言断流后进入重连并回到 `CONNECTED`;② 删掉 `Thread.sleep(8_000L)` 级等待,改 Awaitility 轮询状态(单用例预算 ≤ 2 s);③ 用 T019 修好的端口选择器真正制造一个已关闭端口
- [ ] T035 [US2] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionCloseAndCallTest.java`:① `close_idempotent` L82;② `callTool_afterDisconnect_returnsError` L126;③ 该文件内写死的陈旧 URL `http://127.0.0.1:1/sse` 全部换成从 `McpServerConfig` 推出的 `resolveSseUrl()` 之类真实地址
- [ ] T036 [US2] 跑 `mvn -pl lingshu-core test -Dtest='SseMcpServerConnection*'` 全绿;并做一次**反证抽查**:临时把 fixture 的 `event: endpoint` 首帧注释掉,确认 T027—T030 的用例**变红**,改回后恢复绿(SC-003 交付证据,证据记入 PR body)

**Checkpoint**:US2 独立可验证 —— SSE 四段握手 + ping 心跳有 AC 覆盖,且每条链路都能被反证。

---

## Phase 5: User Story 3 — Streamable HTTP 单端点 + 404 会话恢复(P2)

**Goal**:把 Streamable HTTP 测试扶正到规范单端点语义,并补齐 spec 里最关键、
也从未被测过的 **404 会话过期 → 清会话 → 重 initialize → 同 id 重试恰好一次** 链路。

**Independent Test**:`mvn -pl lingshu-core test -Dtest='StreamableHttpMcpServerConnection*'` 全绿,
且把 fixture 改成「第二个 404 也不停重试」时,SC-004 的反证用例**必须失败**。

### Tests for User Story 3 ⚠️

- [ ] T037 [P] [US3] 新建 `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionSessionRecoveryTest.java`(L3,真实 socket)第 1 个用例:以 `expireSession=true` 起 fixture,连上后发一次 `tools/call`,fixture 返 **HTTP 404** 且本地持有活跃 sessionId → 断言 transport **清掉本地 sessionId 与 protocolVersion** → 重新走 `initialize` 握手 → **用同一个 JSON-RPC id** 重试**恰好一次**并拿到成功结果(记录器核对:同 id 的包出现 2 次,首次 404、第二次成功)
- [ ] T038 [P] [US3] 同一文件第 2 个用例(SC-004 反证):fixture **持续**返 404(过期后重握手拿到的新 session 也立刻过期)→ 断言 transport **不会**无限重试,只重试一次后把错误如实上抛(`LINGS-M03` 或等价既有错误路径),不吞异常、不死循环
- [ ] T039 [P] [US3] 同一文件第 3 个用例:收到 404 但**本地本来就没有**活跃 sessionId → 断言**不触发**重新握手,直接按失败处理(不做无意义的重握手)
- [ ] T040 [P] [US3] 同一文件第 4 个用例:同一请求分别以 `Accept: application/json` 与 `Accept: text/event-stream` 发起,断言两种 content-type 的应答都能被正确解析成同一个 JSON-RPC 结果(FR-022)
- [ ] T041 [P] [US3] 新建 `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpSupportPostJsonRpcSpecTest.java`(L1,纯编解码,不起 socket):给 `McpHttpSupport.postJsonRpcSpec`(L419-481,当前 **3 个生产调用点、0 个测试调用**)补双向编解码覆盖 —— 发包侧断言 JSON-RPC 2.0 信封字段(`jsonrpc` / `id` / `method` / `params`)与 `Mcp-Session-Id` 头正确写入;收包侧断言 `application/json` 与 `text/event-stream` 两种响应形状都能解出 `result` / `error`;通知(无 id)包可发不可等回包。**保留** `McpHttpSupportTest` 里既有的 4 个 `postJsonRpc`(旧非规范版)用例不动(research.md 决策 3:`postJsonRpc` 虽为死代码但删除属 scope creep 且违反 FR-025,遗留登记到 ROADMAP)

### Implementation for User Story 3

- [ ] T042 [US3] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionStartTest.java`:① `start_fakeServer_5stepsReachesConnected` L61 与 `start_alreadyConnected_noop` L120 对齐单端点握手(记录 `Mcp-Session-Id` 头回放);② 写死的陈旧 `/initialize` 路径(L59 / L87)改为直接用 `cfg.getUrl()`(单端点,URL 永不是路径前缀)
- [ ] T043 [US3] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionHeartbeatTest.java` 的 3 个用例:心跳从 `GET /health` 语义改为 JSON-RPC `ping`;并新增/改写断言覆盖 **session id 的记录与后续请求回放**(FR-018)
- [ ] T044 [US3] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionReconnectTest.java`:① 降掉 `Thread.sleep` 秒级等待,改 Awaitility(重连首跳 ≥ 1 s 是硬地板,单用例仍守 2 s 预算);② `reconnect_healthyServer_staysConnected` L100 按新协议断言;③ 补 **重连后重新 `tools/list`** 的断言(FR-029:重连不复用旧 tools 缓存)
- [ ] T045 [US3] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionCloseAndCallTest.java`:① `close_idempotent` L75;② `callTool_afterDisconnect_returnsError` L119;③ 陈旧 URL(L62 / L102 / L151)统一换 `cfg.getUrl()`
- [ ] T046 [US3] 更新 `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpDomainGuardIT.java`:① `sseWhitelisted_reachesConnected` L76 补成**正向**断言(白名单放行 ⇒ 真实握手能到 `CONNECTED`,此前只验到抛异常为止);② 3 条 vacuous 负向用例(其自身注释 L118-120 / L160-163 已自承只验到 URL 校验就短路)补上「异常后**真实 HTTP 请求未曾发出**」的断言(FR-030 每次出站前都过白名单)
- [ ] T047 [US3] 跑 `mvn -pl lingshu-core test -Dtest='StreamableHttpMcpServerConnection*,McpHttpSupport*,McpHttpDomainGuardIT'` 全绿;并做一次**反证抽查**:临时让 fixture 对重试后的包继续返 404 且允许 transport 再重试,确认 T038 变红,改回后恢复绿(SC-004 交付证据,证据记入 PR body)

**Checkpoint**:US3 独立可验证 —— 单端点 + 会话 id 回放 + 404 恢复一次链路有 AC 覆盖且可反证。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**:全量回归 + 宪章门禁自检 + 收尾。

- [ ] T048 跑全量回归 `mvn -pl lingshu-core,lingshu-examples/demo-product test`,与 T004 的 pre-mirror 比对:**既有 0 regression**(除已知 pre-existing flake 外 0 新增失败),记录新总数
- [ ] T049 [P] **FR-025 自查**:逐个被改动的测试文件核对 —— 任何被删除的断言都必须在同文件有**等价或更强**的替代断言,不存在「靠删断言让测试变绿」;把核对清单(文件 × 删了什么 × 换成了什么)记入 PR body
- [ ] T050 [P] **JDK 8 兼容自查**(constitution §1 + §6):新增 / 改写的 fixture 与测试代码 0 `var` / `List.of` / `record` / `sealed` / text block;只允许 JDK 8 已有 API(`HttpServer` / `AtomicLong` / `CountDownLatch` / `Collections.synchronizedList` / `Awaitility`)
- [ ] T051 [P] **FR-026 / FR-027 自查**:`git diff` 确认 **0 新 Maven 依赖**(pom 未改)+ **0 新 ErrorCode**(复用既有 `LINGS-M03` / `[LINGS-S01]`);确认 `src/main/java/ai/lingshu/core/mcp/**` **0 行改动**
- [ ] T052 跑 R-13 mitigation (d) baseline **post-mirror**:`mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -Dverbose > /tmp/054-deptree-post.txt 2>&1`,与 T003 的 pre 摘要 `diff` —— 必须为 **0 binary delta**(仅时间戳差异),结果记入 PR body 的 `### R-13 dependency:tree 自查` 节
- [ ] T053 跑 `mvn -pl lingshu-core validate` 确认 `banned-dependencies` enforcer Rule 0 passed
- [ ] T054 按 `quickstart.md` 走一遍验证流程,确认文档可用(命令可复制粘贴、结果可复现);若与实际不符则就地修正 `quickstart.md`
- [ ] T055 文档同步:① `specs/ROADMAP.md` 补 Story #054 行 + 勾掉「待补」表对应条目 + `postJsonRpc` 死代码遗留(research.md 决策 3);② `constitution.md` §10 R-13 缓解 Story 列表追加 `#054` 一行(**仅记账,不新增/修改任何宪章原则**,CLAUDE.md §11.3);③ `README.md` 如需提及 MCP 协议合规测试,只加一行 blockquote 指针
- [ ] T056 提交并开 PR:分支 `054-mcp-protocol-compliance-tests` → `git -c http.sslVersion=tlsv1.2 push origin 054-mcp-protocol-compliance-tests` → `gh pr create --base main --merge`;PR body 必须含:① 缺陷 (a)(b) 的根因与修法;② AC 覆盖表(SSE 四段握手 + ping 心跳 + 404 恢复一次 / 二次不恢复);③ SC-003 / SC-004 的**反证证据**(把 fixture 改坏后测试确实变红的截图或日志);④ `### R-13 dependency:tree 自查` 节;⑤ T049 的断言核对清单。合入走 `--merge`,**禁止** `git push origin main`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 0 (Pre-flight)**:无依赖 —— 必须最先跑,否则没有 0 regression 基准
- **Phase 1 (Setup)**:依赖 Phase 0 —— 产出 `data-model.md` / `contracts/fixture-protocol.md` / `quickstart.md`
- **Phase 2 (Foundational)**:依赖 Phase 1 —— **BLOCKS 全部 US**(fixture 不合规,US2/US3 的用例既写不出也跑不对)
- **User Stories (Phase 3+)**:全部依赖 Phase 2 完成;此后 **US1 / US2 / US3 可并行**
  - US1 (P1) 只改 1 行 yml + 1 个新测试文件,与 fixture 合流无耦合 ⇒ 可与 US2/US3 并行
  - US2 (P1) 与 US3 (P2) 分属两个 transport 的测试文件,无共享文件 ⇒ 可并行
- **Phase 6 (Polish)**:依赖所有目标 US 完成

### User Story Dependencies

- **US1 (P1)**:Phase 2 之后即可开始 —— **对 US2 / US3 零依赖**,可完全独立验证
- **US2 (P1)**:Phase 2 之后即可开始 —— **对 US3 零依赖**
- **US3 (P2)**:Phase 2 之后即可开始 —— **对 US2 零依赖**
- 三者**全部独立可交付**;US1 是 MVP(1 行改动 + 1 个测试文件,价值最直接)

### Within Each User Story

- US1:先写 AC 测试(T022,当前**必然红**,因为 yml 还是坏路径)→ 再改 1 行 yml(T023)→ 测试转绿 ⇒ 天然的 fail-first
- US2 / US3:「测试即交付物」。此处 fail-first 的等价形态是**反证抽查**(T036 / T047):把 fixture 按规范改坏 → 对应用例必须红 → 改回必须绿,证据进 PR body
- 契约先行:任何 fixture 行为改动先改 `contracts/fixture-protocol.md`,再改 `TestMcpHttpServer`,再改用例
- Story 完成并独立验证通过后再进入下一优先级

### Parallel Opportunities

- Phase 0 的 T003 / T004 / T005 / T006 可并行(都是只读命令)
- Phase 1 的 T007 / T008 / T009 可并行(三份文档互不依赖);T010 是通读,不阻塞
- Phase 2 的 T012 / T013 可并行(两个 handler 在不同 context);T018 / T019 可并行(不同文件)
- Phase 3(T022—T026)与 Phase 4(T027—T036)与 Phase 5(T037—T047)三段之间**可并行**(前提 Phase 2 已完成)
- Phase 4 内部 T027 / T028 / T029 / T030 / T031 五个用例在同一文件,**必须顺序改**(同文件冲突,不可标 `[P]`)
- Phase 5 内部 T037—T041 跨 2 个新文件 + Phase 4 文件,T037/T038/T039/T040 在**同一新文件**,须顺序;`T041` 与 `T037—T040` 是不同文件 ⇒ `[P]`
- Phase 6 的 T049 / T050 / T051 可并行(都是只读 diff 检查)

---

## Parallel Example: User Story 1

```bash
# US1 独立验证(Phase 2 完成后随时可跑):
Task: "T022 [US1] 新建 DemoProductStdioArgsPathTest.java(3 case)"
Task: "T023 [US1] 改 application.yml L215 → src/main/resources/mcp-servers/echo-stdio.py"

# 验证:
mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductStdioArgsPathTest
```

## Parallel Example: User Story 2 / User Story 3

```bash
# US2(SSE)与 US3(Streamable HTTP)由两人并行,Phase 2 完成后同时开工:
#   US2 线程 → T027→T036(改 SseMcpServerConnection* 5 个文件)
#   US3 线程 → T037→T047(新建 2 个文件 + 改 StreamableHttpMcpServerConnection* 4 个 + McpHttpDomainGuardIT)
# 两边共享文件数 = 0 ⇒ 无冲突
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 0:Pre-flight
2. 完成 Phase 1:Setup
3. 完成 Phase 2:Foundational(**测试基建合流,阻塞项**)
4. 完成 Phase 3:User Story 1(改 1 行 yml + 1 个 AC 测试文件)
5. **STOP and VALIDATE**:`mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductStdioArgsPathTest` 全绿
   + 人工 `spring-boot:run` 见 `echo-stdio` 连通

> MVP 的真实价值 = **把「换机器就跑不起来」这个 P1 缺陷彻底收口**;
> Phase 2 虽然是为 US2/US3 服务的基建,但对 US1 也必须先做(否则 US1 的测试跑在坏 fixture 上)。

### Incremental Delivery

1. Phase 0 + Phase 1 → 基线与契约就位
2. Phase 2 → 基建合流(MCP HTTP 测试从「两套 fork」变「单份规范 fixture」)
3. Phase 3(US1)→ 独立交付:P1 缺陷 (a) 收口
4. Phase 4(US2)→ 独立交付:SSE 规范握手 + ping 心跳有 AC 覆盖(SC-003 可反证)
5. Phase 5(US3)→ 独立交付:Streamable HTTP 单端点 + 404 会话恢复(SC-004 可反证)
6. Phase 6 → 全量回归 + R-13 镜像 + PR
7. 每一步都可单独跑、单独验、单独交付;前一步绿了再进下一步

### Parallel Team Strategy

With multiple developers:

- **Developer A**:Phase 0 + Phase 1(基线 + 契约文档)
- **Developer B**:Phase 2(fixture 合流 —— 所有人阻塞在这一步)
- Phase 2 完成后:
  - **Developer A**:Phase 3(US1,最简单,先把 P1 缺陷收口)
  - **Developer B**:Phase 4(US2,SSE)
  - **Developer C**:Phase 5(US3,Streamable HTTP)
- 三人改动文件集**互斥** ⇒ 可同时提交,最后由一人统一跑 Phase 6 与开 PR

---

## Notes

- `[P]` tasks = 不同文件、无依赖
- `[USn]` 标签把任务映射到 spec.md 的用户故事,便于 AC 追溯
- **本 Story 特别提醒**:
  - FR-025 —— 既有测试**只能就地更新**,不许删断言换绿(T049 是强制核对项)
  - SC-003 / SC-004 —— 必须留下**反证证据**(改坏 fixture ⇒ 用例变红),否则不算完成
  - 决策 2 —— 需要可注入时间的用例**必须 `new` connection**,不能走 `McpServerConnectionFactory.create()`
  - 决策 1 —— 两个 fixture 是重复实现,是缺陷 B 的根因;`TestMcpSseServer` 必须退役而不是并存
  - constitution §6 —— 一律 `127.0.0.1`,禁用 `localhost`
- 每个 task 完成后单独 commit(`feat(agent): Story #054 <task-id> — <一句话>`),
  或按逻辑分组 commit;**禁止**直接 commit 到 `main`
- 停在任何 checkpoint 都可以独立验证该阶段
- 反模式(避免):
  - ❌ 只加新测试不管旧测试 → 留下 ~20 个假绿/假红用例,缺陷 B 只修一半
  - ❌ 删掉语义失效的旧测试 → FR-025 明令禁止
  - ❌ 用 `Thread.sleep` 等「某件事发生」→ 单用例预算 2 s 会被拖爆,一律 Awaitility
  - ❌ 保留两套 fixture 并各改各的 → 漂移会重新长回来,SC-006 要求单份实现
  - ❌ 把外部真实 MCP server 连通性当通过判据 → 会给 CI 引入网络依赖(spec Assumptions 第 3 条)
---

## Phase 4 write-back:US2(SSE)已实施 — 2026-10-24

> **追加记录,不改动上文任何 task 文本** —— 上文的 checkbox 保持未勾选(沿用 Phase 3 的
> 仓内惯例:完成状态记录在 Phase-1 制品的**追加段**里,而不是就地勾选)。
> 详细的落地表 / 根因分析 / 反证证据见 `research.md` §8.2 与 `quickstart.md` §7。

### Phase 4 任务完成情况

| Task | 状态 | 落地位置 |
|---|---|---|
| T027 | ✅ | `SseMcpServerConnectionStartTest.start_fakeServer_5stepsReachesConnected`(握手四段断言) |
| T028 | ✅ | 同文件 `handshake_relativeAnnouncedUri_resolvesAgainstSseOrigin` |
| T029 | ✅ | 同文件 `handshake_concurrentCalls_correlateByIdNotArrivalOrder` |
| T030 | ✅ | 同文件 `handshake_noEndpointAnnounce_neverConnects` |
| T031 | ✅ | 同文件 `heartbeat_usesJsonRpcPing_neverCallsHealthEndpoint` |
| T032 | ✅ | `SseMcpServerConnectionHeartbeatTest` 4 例全重写(ping 语义) |
| T033 | ✅ | 状态序列对齐新握手(`McpStateLog` 两道过滤) |
| T034 | ✅ | `SseMcpServerConnectionReconnectTest.sseStreamBroken_reconnects` 改 Awaitility 轮询 |
| T035 | ✅ | `SseMcpServerConnectionCloseAndCallTest` 5 例 + 陈旧 URL 全换真实地址 |
| T036 | ✅ | 反证抽查:破坏 fixture ⇒ 24 run / 2 fail / 13 error;改回 ⇒ 24 run / 0 / 0 |

### 任务文本与现实的 3 处偏差(evidence-first,reality 优先)

| # | Task 文本 | 现实 | 处置 |
|---|---|---|---|
| A | **T030** 「断言 `start()` 抛 `IOException` 且消息含 `no 'endpoint' event` 语义」 | `start()` **不抛** —— 连接以 `RECONNECTING` 静默重试,没有异常出口 | 改为断言「永不进入 `CONNECTED`」+「未公告端点前不发任何 JSON-RPC 请求」。**同时它必须是 T036 反证里唯一保持绿的用例** —— 它本来就需要「没有 `endpoint` 帧」这个 fixture,所以破坏 fixture 对它没有影响 |
| B | **T029** 「连发 2 个 JSON-RPC 请求(如 `initialize` 与 `tools/list`)」 | 2 个 `initialize`/`tools/list` 会撞上协议自身的握手时序,测不出「id 关联不依赖到达顺序」 | 改为 2 个**并发** `tools/call`(不同 `input` ⇒ 不同应答),用 fixture 的 `outOfOrder` 开关把 2 个应答**反序**推回,断言各应答仍回到正确的调用方 |
| C | **T032** 「该文件 162 行」 | 实际 **249 行**(Phase 3 之前就已经不是 162 行) | 行数陈旧,不影响用例语义;按 reality 记录 |

### T036 反证结果的诚实陈述

任务文本要求「确认 T027—T030 的用例**变红**」。

**实测:15 / 24 变红;T030 保持绿。**

T030 保持绿是**正确结果**,不是反证失败 —— T030(`handshake_noEndpointAnnounce_neverConnects`)
本来就是那个**主动要求**「fixture 不写 `event: endpoint` 帧」的用例;把 fixture 的首帧注释掉
对它没有任何影响。按「reality 优先」原则,此处按实测结果记录,不修改 T030 的断言去凑「全红」。
