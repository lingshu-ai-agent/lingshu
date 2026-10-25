# Quickstart — Story #054 MCP 协议合规测试收口

**Branch**: `054-mcp-protocol-compliance-tests` | **Phase**: 1 / T009

本文是 Phase 1 最后一份产物,用途只有两个:
1. **验证指南** —— Phase 2—5 每写完一批用例,按 §1—§3 的命令跑,并与 Phase 0 锁定的基线对比;
2. **踩坑提醒** —— §4 记下 4 条「不看就会写错 / 跑不对」的约束。

**配套文档**:
- [`spec.md`](./spec.md) —— FR-001…FR-030 / SC-001…SC-009(需求真理,定稿不改)
- [`plan.md`](./plan.md) —— 技术方案 + Constitution Check
- [`research.md`](./research.md) —— 10 条决策 + 78 用例 break 判定
- [`data-model.md`](./data-model.md) —— fixture 开关模型 + 请求记录器模型 + 断言可见性矩阵(T007)
- [`contracts/fixture-protocol.md`](./contracts/fixture-protocol.md) —— fixture 线上帧契约(T008)
- [`tasks.md`](./tasks.md) —— T001—T055 任务清单

---

## 0. Phase 0 锁定的基线(不要重跑,直接引用)

Phase 0(T001—T006)已经把 pre 状态写死在 `/tmp` 下,Phase 2 起所有对比都跟这 4 个文件比:

| 文件 | 大小 | 内容 |
|---|---|---|
| `/tmp/054-deptree-pre.txt` | 31072 B | `mvn dependency:tree -Dverbose` **原始输出**(含时间戳 / `Downloading from central:`) |
| `/tmp/054-deptree-pre.clean3.txt` | **29830 B** | **唯一比对基准** —— §4.3.1 的 4 级清洗产物(md5 `f7a440cd1ea93dc3faa42826b14d78c8`,297 行) |
| `/tmp/054-deptree-pre.clean.txt` | 30454 B | ⚠️ **非基准** —— T052 当时的就地清洗产物,pre/post 字节相同但 **md5 不可由本文任何命令复现**(见 §9) |
| `/tmp/054-test-pre.txt` | 63001 B | `mvn test` 全量输出 |
| `/tmp/054-mcp-tests-pre.txt` | 2775 B | MCP 包 27 文件 × `@Test` 计数清单(78 个) |

> **⚠️ T054 write-back —— `.clean.txt` 这个文件名是历史包袱,别拿它当基准**。
> Phase 0 记的「29997 B / 3 级 / md5 `808576f6…`」在 T052 就地覆盖后已经**对不上磁盘**了
> (现在 30454 B / 306 行 / md5 `64c9b344…`)。**基准一律用 `.clean3.txt`**
> (§4.3.1 的 `for f in pre post` 规范命令从 raw `.txt` 重新清洗出来的那个)。

**全量测试基线**:

```text
[ERROR] Tests run: 802, Failures: 0, Failures: 0, Errors: 18, Skipped: 0
                     ↑ 注意:802 run / 0 failures / 18 errors / 0 skipped
```

**这 18 个 error 全部在 `ai.lingshu.core.mcp.*`**,根因是旧 fixture 的 `/sse` 只写 `data:` 不写 `event:`(见
[`contracts/fixture-protocol.md`](./contracts/fixture-protocol.md) §1.4),`SseMcpServerConnection.doConnect()`
在 `endpointLatch` 上等满 `hbTimeoutMs` 后抛 `IOException("no 'endpoint' event from SSE server within Nms")`。

> **⚠️ 不要把 18 errors 当成「本来就红」**。它们正是缺陷 B 的现场证据 —— 生产代码早就按规范走了,
> 测试基建还在测一条已经不存在的协议路径。Phase 2 结束时这个数必须 **降到 0**。

**依赖树基线摘要**(唯一可比对的 md5):

```text
f7a440cd1ea93dc3faa42826b14d78c8
```

> **⚠️ Phase 2 write-back**:此处原记 `808576f6e540bafbbd2567dd93ae4196`,
> 实测**同样不可复现**(reactor 计时行未被清洗,见 §4.3.1),已作废。
> 上面的 `f7a440cd…` 是清洗到第 4 级后的可复现基准。

---

## 1. 跑单个用例

```bash
cd lingshu

mvn -pl lingshu-core test -Dtest=StreamableHttpMcpServerConnectionSessionRecoveryTest
```

`mvn -pl lingshu-core test` 会先跑 `-am` 之外的依赖解析;若本地 `~/.m2` 缺 `lingshu-a2a-client:0.2.0`,
Maven 会去 central 拉 —— 该仓未发布到 central,此时加 `-o`(offline)或先跑下面这条**一次性**安装:

```bash
mvn -DskipTests=true install -am -pl lingshu-a2a-client
```

> **⚠️ T054 write-back —— `-am` 不能省**:本仓的 `lingshu-a2a-client` 依赖
> `lingshu-core` + `lingshu-a2a-server`,后两者**同样**不在本地 `~/.m2` 里
> (只装了 `0.1.0` / `0.1.0-SNAPSHOT`,`0.2.0` 只有 `.lastUpdated` 失败标记)。
> 所以 `-pl lingshu-a2a-client` **单独**跑会挂在解析 `lingshu-core:jar:0.2.0` 上:

```text
[ERROR] Failed to execute goal on project lingshu-a2a-client: Could not resolve dependencies
[ERROR] dependency: ai.lingshu:lingshu-core:jar:0.2.0 (compile)
```

> 加上 `-am` 后 reactor 依次构建 Parent → Core → A2A Server → A2A Client 并全部 `install`,
> `~/.m2/repository/ai/lingshu/lingshu-a2a-client/0.2.0/` 里才会出现真 jar
> (实测产出 `.jar` + `.jar` + `-sources.jar` + `-javadoc.jar` 4 个文件)。
> 跑完之后 §3 / §6 的 `demo-product` 腿才能解析依赖 —— 否则直接 BUILD FAILURE。

**建议顺序**:写完一个 `@Test` 先单独跑它,绿了再跑整包,最后才跑全量。

---

## 2. 跑整个 MCP 包

```bash
mvn -pl lingshu-core test -Dtest='ai.lingshu.core.mcp.**'
```

等价的显式写法(某些 surefire 版本对 `**` 通配更敏感):

```bash
mvn -pl lingshu-core test -Dtest='Sse*,Streamable*,McpHttp*,TestMcp*'
```

改 fixture(`fixture/TestMcpHttpServer.java` / `fixture/TestMcpServer.java`)后**必须**回到本组重跑 ——
fixture 是 fork 重写过的,单个类绿不代表整包绿。

> **⚠️ Phase 3 write-back —— `fixture/TestMcpSseServer.java` 已不存在**:
> Phase 2 / T016(用户授权删除)把 SSE handler **全部并入 `TestMcpHttpServer`**,
> SC-006 要求单份实现、0 重复 ⇒ 该文件已删。当前 fixture 只剩 2 个:
>
> | fixture | 承担 |
> |---|---|
> | `fixture/TestMcpHttpServer.java` | HTTP + **SSE** 两套 handler(并入后) |
> | `fixture/TestMcpServer.java` | stdio 子进程 fixture(Story #021a 起的原样) |
>
> 上面的命令 `-Dtest='Sse*,Streamable*,McpHttp*,TestMcp*'` 不受影响(前缀通配仍覆盖)。

> **⚠️ T054 write-back —— MCP 包用例数从 78 涨到 116**。
> Phase 0 锁定的基线是 **78 个 `@Test`**(27 个文件,见 §0 的
> `/tmp/054-mcp-tests-pre.txt`);Phase 2—6 补齐后实测 **116 个**。
> T054 复跑 `mvn -pl lingshu-core test -Dtest='ai.lingshu.core.mcp.**'`:
>
> ```text
> [INFO] Tests run: 116, Failures: 0, Errors: 0, Skipped: 0
> ```
>
> **只增不减 = 符合预期**(新用例全是净增,没有任何旧用例被删 —— FR-025)。

---

## 3. 跑全量 + 对比基线

> **⚠️ 前置条件**:本仓未发布到 central,`demo-product` 依赖的 `lingshu-a2a-client:0.2.0`
> **不在**本地 `~/.m2` 里。第一次跑之前必须先执行 §1 的
> `mvn -DskipTests=true install -am -pl lingshu-a2a-client`,否则本节命令会挂在
> 依赖解析上(reactor 里 `demo-product` 直接 FAILURE,`lingshu-core` 那一腿**照跑**,
> 容易让人误以为「全量绿了」)。

```bash
mvn -pl lingshu-core,lingshu-examples/demo-product test 2>&1 | tee /tmp/054-test-post.txt
```

判定标准(Phase 5 / T051 的验收线):

| 指标 | Phase 0 基线 | Phase 2+ 目标 | Phase 6 实测(T048 / T054 复跑) |
|---|---|---|---|
| Tests run | 802 | ≥ 802(只增不减) | **824**(lingshu-core)+ **9**(demo-product) |
| Failures | 0 | **必须 0** | **0 / 0** |
| Errors | 18(全在 `ai.lingshu.core.mcp.*`) | **必须 0** | **0 / 0** |
| Skipped | 0 | 0 | **0 / 0** |

**Errors 从 18 → 0 且 Failures 保持 0**,才算缺陷 B 真正关闭。任何 0→正的新增 failure 都是回归,
不许用「改断言」或「删用例」的方式压掉(FR-025:只能就地更新,不许删断言)。

抓摘要行:

```bash
grep -E 'Tests run:.*(Failures|Errors)' /tmp/054-test-post.txt | tail -5
```

---

## 4. R-13 dependency:tree 镜像(0 binary delta)

constitution §10 R-13 mitigation (d) 要求:任何 Story 结束前 pre/post 镜像 `dependency:tree`,
diff 必须是 **0 binary delta**(只允许时间戳差异)。Story #054 是 **第 37 次**该检查。

### 4.1 pre 镜像(Phase 0 已完成,这里只给出可复现命令)

```bash
mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -Dverbose \
    > /tmp/054-deptree-pre.txt 2>&1

# 清洗:去掉 Maven 自身的装饰行
# ⚠️ Phase 2 write-back:第 4 级(去 reactor 计时行)是必需的,见 §4.3.1
grep -v '^\[INFO\] ---' /tmp/054-deptree-pre.txt \
  | grep -v 'BUILD\|Total time\|Finished at' \
  | grep -v 'Downloading from central:' \
  | grep -vE '(SUCCESS|FAILURE|SKIPPED) \[' \
  > /tmp/054-deptree-pre.clean.txt

md5sum /tmp/054-deptree-pre.clean.txt
# → f7a440cd1ea93dc3faa42826b14d78c8
```

### 4.2 post 镜像(Phase 5 / T052 执行)

```bash
mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -Dverbose \
    > /tmp/054-deptree-post.txt 2>&1

grep -v '^\[INFO\] ---' /tmp/054-deptree-post.txt \
  | grep -v 'BUILD\|Total time\|Finished at' \
  | grep -v 'Downloading from central:' \
  | grep -vE '(SUCCESS|FAILURE|SKIPPED) \[' \
  > /tmp/054-deptree-post.clean.txt

diff /tmp/054-deptree-pre.clean.txt /tmp/054-deptree-post.clean.txt && echo "0 binary delta PASS"
```

> **⚠️ T054 write-back —— 上面这段 `diff` 用的是 `.clean.txt`,不是基准文件**。
> 基准判定请用 §4.3.1 的规范命令(从 raw `.txt` 重新清洗出 `.clean3.txt`),实测:

```text
md5 /tmp/054-deptree-pre.clean3.txt   → f7a440cd1ea93dc3faa42826b14d78c8
md5 /tmp/054-deptree-post.clean3.txt  → f7a440cd1ea93dc3faa42826b14d78c8
diff pre.clean3.txt post.clean3.txt  → 0 行 diff
⇒ 0 binary delta PASS(R-13 mitigation (d) 第 37 次)
```

两侧各 **297 行 / 29830 B**。**post 侧与 pre 侧 md5 完全相同** —— 这是本 Story
「0 新 Maven 依赖(FR-026)」的机器证据。

### 4.3 ⚠️ 为什么清洗里必须有 `Downloading from central:`

这条是 **T009 写作时实测才发现**的,Phase 0 的 T003 命令漏了它:

| 清洗到哪一步 | md5 | 可复现? |
|---|---|---|
| `grep -v '^\[INFO\] ---' \| grep -v 'BUILD\|Total time\|Finished at'`(T003 原命令) | `e446c786f1e97dd56737e2cab99122c0` | ❌ **不可复现** |
| 再加 `grep -v 'Downloading from central:'` | `808576f6e540bafbbd2567dd93ae4196` | ✅ 可复现 |

原始 `dependency:tree` 输出里夹了一行:

```text
Downloading from central: https://repo.maven.apache.org/maven2/ai/lingshu/lingshu-a2a-client/0.2.0/lingshu-a2a-client-0.2.0.pom
```

这行 **只在本地 `~/.m2` 没有该 artifact 时出现**。开发机 `~/.m2` 缓存命中 → 这行没有 → md5 变;
CI 全新环境 → 这行有 → md5 又变。**它跟依赖树内容毫无关系,纯粹是 Maven 缓存状态的噪声**,但会让
「0 binary delta」判定在跨机器时假失败。

**结论**:`808576f6e540bafbbd2567dd93ae4196` 才是可比对的基线摘要。T003 原命令产出的
`e446c786f1e97dd56737e2cab99122c0` **作废**,Phase 5 比对统一用清洗后这一份。

> 顺带:`/tmp/054-deptree-pre.clean.txt` 已经是清洗后的版本(29997 B),
> 比 T003 原命令的输出(30125 B)少的那一行正是上面这条 `Downloading from central:`。
> 也就是说 Phase 0 当时已经意识到要清洗,只是清洗条件写漏了。本 Story 以 `.clean.txt` 为准。

### 4.3.1 ⚠️ Phase 2 write-back:`808576f6…` 也**不可复现**(reactor 计时行)

> **实证 > 文档**:Phase 2 跑 post-mirror 时发现 §4.3 认定的「唯一比对基准」
> `808576f6e540bafbbd2567dd93ae4196` **同样每次跑都变**。原因与 §4.3 **同类但不同源**:
> `dependency:tree` 在 reactor 模式(`-pl a,b`)下除了依赖行,还会打 2 行
> **每个模块的构建耗时汇总**,它们含 wall-clock 秒数,逐次不同:

```text
[INFO] LingShu Core ....................................... SUCCESS [  0.497 s]
[INFO] LingShu Demo Product (Story #025 — HTTP SSE chat product) SUCCESS [  2.806 s]
```

§4.3 的清洗条件(3 条 `grep -v`)碰不到它们 —— 漏的是 **`(SUCCESS|FAILURE|SKIPPED) [`** 这个模式。
实测两次跑同一命令,清洗后 md5 分别为 `808576f6…` / `cdb28edf…` / `522450bc…`,**每次都变**。

**Phase 2 实测的可复现基准(清洗到第 4 级)**:

| 清洗到哪一步 | md5 | 可复现? |
|---|---|---|
| 3 级(§4.3 认定的基准) | `808576f6e540bafbbd2567dd93ae4196` | ❌ **不可复现** |
| 4 级(再加 `grep -vE '(SUCCESS\|FAILURE\|SKIPPED) \['`) | **`f7a440cd1ea93dc3faa42826b14d78c8`** | ✅ 可复现 |

**Phase 5 比对命令以此为准**:

```bash
for f in pre post; do
  grep -v '^\[INFO\] ---' /tmp/054-deptree-$f.txt \
    | grep -v 'BUILD\|Total time\|Finished at' \
    | grep -v 'Downloading from central:' \
    | grep -vE '(SUCCESS|FAILURE|SKIPPED) \[' \
    > /tmp/054-deptree-$f.clean3.txt
done
diff /tmp/054-deptree-pre.clean3.txt /tmp/054-deptree-post.clean3.txt && echo "0 binary delta PASS"
```

⚠️ **清洗必须 pre / post 用同一条命令** —— 拿 3 级 pre 去比 4 级 post 会假失败
(差的正是那 2 行计时行)。Phase 2 实测:3 级 pre vs 4 级 post = 1 行 diff(纯计时);
4 级 pre vs 4 级 post = **0 行 diff**。

> **⚠️ Phase 3 write-back —— 上面的「1 行 diff」应作废,实测是 2 行**。
> Phase 3 实跑 4 级清洗(§4.3.1 的 `for f in pre post` 规范命令)时复现:
>
> ```text
> md5 /tmp/054-deptree-pre.clean3.txt  → f7a440cd1ea93dc3faa42826b14d78c8
> md5 /tmp/054-deptree-post.clean3.txt → f7a440cd1ea93dc3faa42826b14d78c8
> diff → 0 行 diff  ⇒ 0 binary delta PASS
> ```
>
> 而「3 级 pre vs 4 级 post」实测 diff 是 **2 行**(两个模块各 1 行 reactor 计时汇总),
> 与本段第 1 句「差的正是那 2 行计时行」自洽 —— **只有紧跟其后的「1 行 diff」是笔误**。
>
> **另一个坑**:`/tmp/054-deptree-pre.clean.txt` 这个文件名带 `.clean`,
> 但它的内容其实是 **3 级** 清洗产物(md5 `808576f6e540bafbbd2567dd93ae4196`,
> 即 §4.3 已作废的那一档)。直接拿它跟 4 级 post 比 → 必然假失败。
> **必须**用上面的规范命令从 raw `.txt` 重新清洗两侧,别复用 `.clean.txt`。
>
> 附带确认(§4.3 的原始论据依然成立):`Downloading from central:` 这行只在
> **pre** raw 里出现(post raw 出现 0 次)—— 本地 `~/.m2` 缓存命中与否的噪声,
> 与依赖树内容无关,清洗掉才能跨机器复现。

### 4.4 enforcer Rule 0(T053)

```bash
mvn -pl lingshu-core validate
```

输出里必须出现这一行(**逐字匹配,不要只 grep "Rule 0"**):

```text
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
```

> **⚠️ T054 write-back**:Phase 1 原文写的是「`banned-dependencies` **Rule 0: passed**」——
> 那是**概括说法**,不是 Maven 的实际输出。实际输出带完整规则类名(见上)。
> 抓取命令:

```bash
mvn -pl lingshu-core validate 2>&1 | grep -E 'Rule [0-9]|BUILD'
# [INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
# [INFO] Rule 1: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
# [INFO] BUILD SUCCESS
```

本 Story 引入的新增物
(`com.sun.net.httpserver`、`AtomicLong`、`CountDownLatch`、`Collections.*`)全是 JDK 1.6+ 内置,
0 新 Maven 依赖(FR-026),所以这条是纯守门,预期一次就过。**T053 实测:一次过。**

---

## 5. ⚠️ 4 条踩坑提醒

### 提醒 1 — `*IT` 后缀在本仓 **没有** 构建语义

本仓 `pom.xml` **没有** surefire / failsafe 的 `<includes>` 配置。`McpHttpDomainGuardIT`、
`StreamableHttpMcpServerConnectionSessionRecoveryTest` 这些名字里的 `IT` **不会**触发任何特殊生命周期:

- 跑 `mvn -pl lingshu-core test` → **全跑**,`*Test` 和 `*IT` 一视同仁;
- 想只跑一类,必须用 `-Dtest=<正则>`(见 §1 / §2),不能指望 failsafe 的 `verify` 阶段。

推论:PR 里说「IT 测试已通过」时,必须同时给出**实际跑的命令**,不能写「verify 阶段会跑」。

### 提醒 2 — 需要可注入时间的用例必须 `new` connection,而非走工厂

两个 HTTP transport 都提供了 **4 参可注入时间构造器**:

```java
// SseMcpServerConnection.java L146-149
public SseMcpServerConnection(McpServerConfig cfg, long heartbeatIntervalMs,
                              long heartbeatTimeoutMs, long reconnectCapMs)

// StreamableHttpMcpServerConnection.java L110-113
public StreamableHttpMcpServerConnection(McpServerConfig cfg, long hbIntervalMs,
                                          long hbTimeoutMs, long reconnectCapMs)
```

`McpServerConnectionFactory.create(cfg)` 只按 `cfg.transport()` 分派、**不吃时间参数**,
所以要压缩时间的用例**必须绕过工厂直接 `new`**:

```java
// ✅ 对:能把 hbIntervalMs 从默认 6_000L 压到 100L
StreamableHttpMcpServerConnection conn =
    new StreamableHttpMcpServerConnection(cfg, 100L, 2_000L, 200L);

// ❌ 错:拿到的是 cfg 里的默认 6s/8s,一个用例就要等十几秒
McpServerConnection conn = McpServerConnectionFactory.create(cfg);
```

现有用例里 `Thread.sleep(6_000L)` / `Thread.sleep(8_000L)` 这种秒级等待,就是当年只能走工厂、
没法注时间留下的。Phase 2 起新写的用例一律走 4 参构造器,把单文件耗时从分钟级压到秒级。
**统一目标:单个用例 ≤ 2 s wall clock。**

### 提醒 3 — 注入时间压不下去的两个硬地板

即使用了 4 参构造器,有两个时间**压不动**,是生产代码里写死的:

| 地板 | 位置 | 值 | 影响 |
|---|---|---|---|
| **重连首跳 ≥ 1 s** | `StreamableHttpMcpServerConnection.computeBackoffMs` L477-487 / SSE 同款 | `base = 1_000L` 硬编码 | 测「重连」至少要等 1.2 s。`reconnectCapMs` 只能**封顶**(`Math.min(delay, reconnectCapMs)`)不能把首跳压到 1 s 以下 |
| **`await()` 下限 1 ms** | `SseMcpServerConnection.await` L365-372 | `latch.await(Math.max(1L, timeoutMs), ...)` | 传 `0` 不会立刻超时,会被夹成 1 ms。写用例别假设「传 0 = 不等」 |

**推论**:重连类 AC(FR-016 / FR-018 / FR-020)的用例预算按 **~1.5 s** 算,不能按「压到 100 ms」规划;
握手类 / 心跳类 / 编解码类 AC 不受这两条地板约束,可以做到 50—200 ms。

### 提醒 4 — fixture 必须绑 `127.0.0.1`,不能用 `localhost`

现有两个 fixture 都是 `new InetSocketAddress(port)` 通配 bind。T011 会改成
`new InetSocketAddress("127.0.0.1", port)`。

原因:Windows WSL2 的 `localhost` 解析顺序会把 IPv6 `::1` 排在 IPv4 `127.0.0.1` 前面,
fixture 只监听 IPv4 时会出现 `ConnectException: Connection refused` 的间歇性 flake。
constitution §6 兼容矩阵覆盖 Linux / macOS / Windows WSL2,**三平台一致的唯一做法是显式写 IPv4 字面量**。

同时,`McpHttpSupport.checkOrThrow(url, whitelist)` 用的是 **`URI.create(url).getHost()` + 大小写敏感的
`whitelist.contains(host)`**,所以白名单里必须写 `127.0.0.1`(不是 `localhost`)。

---

## 6. Phase 2—5 的执行顺序(速查)

| Phase | 任务 | 跑哪条命令 |
|---|---|---|
| Phase 2 | T011—T021(fixture 重写 + 就地更新 20 个失效用例) | §2 → §3 |
| US1 | T022—T0xx(编译期形状 + 传输分发) | §2 |
| US2 | T0xx(SSE 4 段握手链路) | §2 |
| US3 | T0xx(404 会话恢复链路) | §2 |
| Polish | T051—T055(全量 + R-13 镜像 + 文档同步) | §3 + §4 |

**每个 Phase 结束时的自检三连**:

```bash
# ⚠️ 第 2 条(含 demo-product)首次跑之前,必须先跑过一次 §1 的
#    mvn -DskipTests=true install -am -pl lingshu-a2a-client
#    否则 demo-product 会挂在依赖解析上,只有 lingshu-core 那一腿跑得起来。
mvn -pl lingshu-core test -Dtest='ai.lingshu.core.mcp.**'   # 本 Phase 新用例绿
mvn -pl lingshu-core,lingshu-examples/demo-product test       # 全量 Errors 计数下降
git status --short                                             # 只该看到本 Story 的文件
```

> **⚠️ T054 write-back —— 这三连的第 2 条在「干净 `~/.m2`」上会 BUILD FAILURE**。
> 报错长这样(不是测试失败,是依赖解析失败,两者容易混淆):

```text
[ERROR] Failed to execute goal on project demo-product: Could not resolve dependencies for project ai.lingshu:demo-product:jar:0.2.0
[ERROR] dependency: ai.lingshu:lingshu-a2a-client:jar:0.2.0 (compile)
[ERROR] Could not find artifact ai.lingshu:lingshu-a2a-client:jar:0.2.0 in central (https://repo.maven.apache.org/maven2)
```

关键点:`mvn -pl lingshu-core test` 那一腿**照跑照绿**,只有 `demo-product` 那一腿 FAILURE ——
只看「第一段 Tests run」会误判成全绿。**先 install 再测**。

`git status` 里若出现 `README.md` / `UPDATEINFO.md` / `specs/ROADMAP.md` 三个 **PR #90 遗留脏文件**,
**不要 `git add` 它们**(按文件逐个 add,见下)。唯一例外是
`lingshu-examples/demo-product/src/main/resources/application.yml` —— 那个是缺陷 A 的合法改动,属于本 Story。

---

## 7. Phase 4 write-back:US2(SSE)新增的测试基建

> 追加于 2026-10-24。Phase 4(T027—T036)新增了 2 个测试侧基建件与 4 个 fixture 开关。
> 两者都**只影响测试**,生产代码 0 行改动。

### 7.1 `McpStateLog` —— 唯一「真 transition」观察入口(新增)

路径:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpStateLog.java`(137 行,package-private)。

```java
static McpStateLog attach(McpServerConnection conn);   // 必须在 start() 之前调
List<ConnectionState> notifications();                  // 原始流(含重复/陈旧公告)
List<ConnectionState> transitions();                    // 只含真 transition
int connectCount();                                     // 真·CONNECTED 次数
List<Instant> beatsAtConnect();                         // 每次真 CONNECTED 时的 lastHeartbeatAt
```

**为什么需要它** —— `SseMcpServerConnection.relistTools()` 收到
`notifications/tools/list_changed` push 后会**直接**调 `notifyListeners(CONNECTED)`,
**绕过** `transition()` 的 `prev != next` 守卫 ⇒ 原始 listener 流里每次 server push
都会多出一条 `CONNECTED`。更糟的是 `relistTools()` 入口检查完 `state == CONNECTED`
之后还要做一次**阻塞**的 `tools/list` POST —— 这中间连接可能已掉,
于是 pending 的 relist 仍会公告 `CONNECTED`,描述一个**已不存在**的连接。

⇒ **数 `CONNECTED` 通知个数 ≠ 数连接次数**。`McpStateLog` 在测试侧做两道过滤:

1. 通知与上一条相同 ⇒ 重复公告,不是 transition;
2. `state == CONNECTED` 但 `conn.state() != CONNECTED` ⇒ 陈旧公告,transport 自己的
   `state()` 才是真相。

**新写法**(取代直接读 listener 列表):

```java
McpStateLog log = McpStateLog.attach(conn);
conn.start();
await().atMost(Duration.ofSeconds(5)).until(() -> conn.state() == ConnectionState.CONNECTED);
assertThat(log.connectCount()).isEqualTo(1);   // ✅ 真连接次数
```

### 7.2 `McpHttpTestSupport.pickClosedBaseUrl()` —— 确定性「已关闭端口」选择器(新增)

路径:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpTestSupport.java`(+18 行)。

```java
static String pickClosedBaseUrl() throws Exception;   // 返回形如 http://127.0.0.1:<port>
```

**做法**:起 fixture → 读它**实际**绑定的端口 → 杀掉子进程 ⇒ 该端口确定已关闭。

**替代了什么**:旧的 `http://127.0.0.1:1` 魔法字面量。它只证明了「特权端口被拒绝」,
**从没验证过一个真正被占用过的端口**。Phase 4 的 T034 / T035 全部改用本选择器。

> SSE 路径不要手写 `/sse` 后缀 —— 用 `SseMcpServerConnection.resolveSseUrl(baseUrl)` 派生。

### 7.3 Phase 4 新增的 fixture 开关

`fixture/TestMcpHttpServer.java`(现 **1146** 行)新增 4 个开关,由
`McpHttpTestSupport` 原样转发为 `-D<key>=<value>`:

| 开关 | 默认 | 用途 |
|---|---|---|
| `pushNotifications` | `true` | `false` 时把周期性 push 换成一条 SSE **注释** keepalive(仍产出字节,客户端不会卡在一个永不到来的 chunk 上,但不再被诱导去重发 `tools/list`)。**心跳类用例必须关** —— 否则每次 push 触发一次 `tools/list` 重拉,会抢走 `exitAfter` 的请求槽位,心跳 ping 反而打不出去,请求日志计数不再确定 |
| `delayAfter` | `-1` | 从第 N 个请求**开始**才延迟。配 `delayMs` 用,让「握手快、探活慢」可分离 |
| `announceUri` | fixture 的 POST URI | `event: endpoint` 帧的 data 内容(可写**相对** URI)⇒ T028 |
| `outOfOrder` | `false` | 缓存 2 个应答并**按相反顺序**推 `event: message` ⇒ T029 |

> `noEndpointAnnounce` **不是** Phase 4 新增的 —— Phase 2 就有,T030 只是在用它。

### 7.4 反证抽查(SC-003)的正确做法(T036)

```bash
# 1) 临时把 fixture 的 `event: endpoint` 首帧注释掉
# 2) 跑 SSE 包 → 期望大面积变红
mvn -pl lingshu-core test -Dtest='ai.lingshu.core.mcp.**'
# 3) 改回,确认 fixture 与破坏前逐字节相同
md5 lingshu-core/src/test/java/ai/lingshu/core/mcp/fixture/TestMcpHttpServer.java
git diff --stat
```

**实测基线(Phase 4)**:破坏后 `Tests run: 24, Failures: 2, Errors: 13`(15/24 变红);
改回后 `Tests run: 24, Failures: 0, Errors: 0`。
**注意**:`handshake_noEndpointAnnounce_neverConnects`(T030)破坏后**仍绿** —— 这是**正确**结果,
它本来就以 `noEndpointAnnounce=true` 起 fixture、断言连接**永不**进入 `CONNECTED`。
详见 research.md §8.2.6。

---

## 8. Phase 5 write-back:US3(Streamable HTTP)新增的测试基建

> 追加于 2026-10-25。Phase 5(T037—T047)新增了 2 个新测试文件、4 个 fixture 开关,
> 并把 T042—T046 的 5 个既有文件就地改写。**全部只影响测试**,生产代码 0 行改动。

### 8.1 Phase 5 新增的 fixture 开关

`fixture/TestMcpHttpServer.java`(现 **1249** 行)新增 4 个开关,由
`McpHttpTestSupport` 原样转发为 `-D<key>=<value>`:

| 开关 | 默认 | 用途 |
|---|---|---|
| `expireMethod` | `""`(空 = 关闭) | **方法作用域**过期:只让**被点名的那个 JSON-RPC 方法**的请求 404,握手流量(`initialize` / `notifications/initialized` / `tools/list`)**完全健康** |
| `expireMethodCount` | `0`(关闭) | `expireMethod` 命中多少次才 404。**`N > 0` ⇒ 前 N 次命中 404;`N = -1` ⇒ 无限次 404;`N = 0` ⇒ 该开关关闭** |
| `omitSessionId` | `false` | `true` 时 `initialize` 的应答**不带** `Mcp-Session-Id` 头 ⇒ 客户端永远拿不到本地 session ⇒ 404 **不**触发重握手(T039) |
| `respondSse` | `false` | `true` 时**每个** `POST /mcp` 都以 `text/event-stream` 形状应答(而不是 `application/json`)(T040a) |

> **为什么 T037/T038 不用 `expireSession`**:`expireSession` 是「**带 session 头的请求**」的
> 全局单调阈值。404 触发的重握手里,`notifications/initialized` **通知**是带 session 头的
> ⇒ 重握手自身先 404 ⇒「同一个 id 出现 2 次:首次 404、第二次成功」**不可达**。
> 细节见 research.md §8.3.2 的 D/E 两条。

### 8.2 「真实断连」的标准做法(T044 / T045)

Streamable HTTP 没有可被服务端主动掐断的长连接 —— 连接生命周期**只**由**心跳 `ping`** 决定。
所以「让连接真的掉线」的唯一可控手段是**让服务端对 `ping` 返 404**:

```java
Map<String, String> props = new HashMap<>();
props.put("expireMethod", "ping");
props.put("expireMethodCount", "2");     // 只抖一次 ⇒ 请求序列固定
server = McpHttpTestSupport.startHttpServer(props);
```

产生的**确定性**序列(⇒ 计数可以精确断言,不再依赖「等第 N 个 ping」的时间竞速):

```
ping#1         -> 404 -> 就地重握手(initialize#2)
retried ping#1 -> 404 -> 掉线 -> DISCONNECTED -> RECONNECTING(退避)
reconnect      -> initialize#3 + tools/list#2 -> CONNECTED
```

⇒ `initialize` 恰好 3 次、`tools/list` 恰好 2 次、两条抖动 ping **共用同一个 JSON-RPC id**。

**时间锚点必须取自 transport 自己**(`onStateChange` + `System.nanoTime()`,在 transport 线程内取),
**不能**用客户端的轮询时间 —— Awaitility 默认 100 ms 的轮询延迟会直接吃掉整个测量预算。
T044 原实现正是栽在这里:**8 次跑出 4 次红**。详见 research.md §8.3.2 的 H 条。

### 8.3 反证抽查(SC-004)的正确做法(T047)

T038 钉的是 **transport 侧**的「**恰好重试一次**」契约 ⇒ 改 fixture **不可能**让它变红
(T038 的 fixture 本来就**持续** 404)。要取证必须**临时改坏生产代码**:

```bash
# 1) 临时把 StreamableHttpMcpServerConnection.request() 的「重试一次」改成「重试 3 次」
# 2) 跑目标集 → 期望 T038 变红
mvn -pl lingshu-core test -Dtest='StreamableHttpMcpServerConnection*,McpHttpSupport*,McpHttpDomainGuardIT'
# 3) 改回,确认与破坏前逐字节相同 + 生产树 diff 为空
md5 lingshu-core/src/main/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnection.java
git diff --stat -- lingshu-core/src/main/java/ai/lingshu/core/mcp/
```

**实测基线(Phase 5)**:破坏后 `Tests run: 49, Failures: 2, Errors: 2`;
改回后 `Tests run: 49, Failures: 0, Errors: 0`。
T038 的失败信息逐字为:

```
[one original attempt + exactly one retry — the retry must not loop]
Expected size: 2 but was: 4 in:
[#4  tools/call id=3 session=session-1 POST /mcp,
 #7  tools/call id=3 session=session-2 POST /mcp,
 #10 tools/call id=3 session=session-3 POST /mcp,
 #13 tools/call id=3 session=session-4 POST /mcp]
```

### 8.4 Phase 5 的目标测试集(T047)

```bash
mvn -pl lingshu-core test \
  -Dtest='StreamableHttpMcpServerConnection*,McpHttpSupport*,McpHttpDomainGuardIT'
```

**实测基线**:`Tests run: 49, Failures: 0, Errors: 0, Skipped: 0`(9 个测试类)。
> `*IT` 后缀在 Maven 里**没有**语义 —— 完整回归要跑 `mvn -pl lingshu-core test`。

---

## 9. ⚠️ Phase 6 write-back(T054):按本文走一遍验证流程

T054 的任务定义:**「按 `quickstart.md` 走一遍验证流程,确认文档可用(命令可复制粘贴、
结果可复现);若与实际不符则就地修正 `quickstart.md`」**。

下面 §1—§8 的**每一条命令**都在 Phase 6 原样跑过一遍(2026-10-25)。

### 9.1 复跑结果总表(7 条命令,全绿)

| § | 命令 | 文档记载 | T054 实测 | 结论 |
|---|---|---|---|---|
| §1 | `mvn -pl lingshu-core test -Dtest=StreamableHttpMcpServerConnectionSessionRecoveryTest` | — | **5 / 0F / 0E / 0S** · BUILD SUCCESS | ✅ |
| §2 | `mvn -pl lingshu-core test -Dtest='ai.lingshu.core.mcp.**'` | Phase 0 = 78 个 `@Test` | **116 / 0F / 0E / 0S** · BUILD SUCCESS | ✅(数量只增不减) |
| §3 | `mvn -pl lingshu-core,lingshu-examples/demo-product test` | 目标:Errors 18 → 0 | core **824 / 0F / 0E / 0S** + demo **9 / 0F / 0E / 0S** · BUILD SUCCESS | ✅(**前置条件已补**,见 §9.2) |
| §4.1 | 4 级清洗 pre raw → `.clean3.txt` | md5 `f7a440cd…` | 297 行 / 29830 B / md5 **`f7a440cd1ea93dc3faa42826b14d78c8`** | ✅ 逐字命中 |
| §4.2 | 4 级清洗 post raw → `.clean3.txt` + `diff` | 「0 binary delta」 | md5 **同为 `f7a440cd…`**,diff **0 行** | ✅ **0 binary delta PASS(第 37 次)** |
| §4.4 | `mvn -pl lingshu-core validate` | 「Rule 0: passed」 | `Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed` + `Rule 1: …RequireJavaVersion passed` · BUILD SUCCESS | ✅(**期望串已改逐字**,见 §9.3-b) |
| §8.4 | `mvn -pl lingshu-core test -Dtest='StreamableHttpMcpServerConnection*,McpHttpSupport*,McpHttpDomainGuardIT'` | 49 / 0F / 0E / 0S | **49 / 0F / 0E / 0S** · BUILD SUCCESS | ✅ 逐字命中 |

§3 的 T004 基线对照:`/tmp/054-test-pre.txt` = `Tests run: 802, Failures: 0, Errors: 18, Skipped: 0`
⇒ **Errors 18 → 0,Failures 保持 0,run 数 802 → 824(+22)**,缺陷 B 关闭且无回归。

### 9.2 🔴 实测发现的真 bug #1:§3 / §6 的命令在「干净 `~/.m2`」上 BUILD FAILURE

**现象**:在本地 `~/.m2` 没有 `lingshu-a2a-client:0.2.0` 的机器上,§3 与 §6 第 2 条命令直接失败:

```text
[ERROR] Failed to execute goal on project demo-product: Could not resolve dependencies for project ai.lingshu:demo-product:jar:0.2.0
[ERROR] dependency: ai.lingshu:lingshu-a2a-client:jar:0.2.0 (compile)
[ERROR] Could not find artifact ai.lingshu:lingshu-a2a-client:jar:0.2.0 in central (https://repo.maven.apache.org/maven2)
```

**根因**:本仓**未发布到 central**,`~/.m2/repository/ai/lingshu/lingshu-a2a-client/` 下
只有 `0.1.0` / `0.1.0-SNAPSHOT` 的真 jar,`0.2.0/` 目录里只有
`.jar.lastUpdated` / `.pom.lastUpdated` **失败标记**(之前解析失败留下的),
没有真 artifact。`demo-product` 依赖 `lingshu-a2a-client:0.2.0` ⇒ 必须先 install。

**更糟的是**:§1 给出的补救命令**自己也是错的** —— Phase 1 原文写的是

```bash
mvn -DskipTests=true install -pl lingshu-a2a-client      # ❌ 少了 -am
```

实测 BUILD FAILURE:

```text
[ERROR] Failed to execute goal on project lingshu-a2a-client: Could not resolve dependencies
[ERROR] dependency: ai.lingshu:lingshu-core:jar:0.2.0 (compile)
```

**根因(为什么 `-am` 不能省)**:`lingshu-a2a-client` 依赖 `lingshu-core` **和**
`lingshu-a2a-server`,后两者**同样**不在本地 `~/.m2` 里(只有 `0.1.0` / `0.1.0-SNAPSHOT`)。
只 `-pl lingshu-a2a-client` 不带 `-am`,reactor 里没有这 3 个模块 ⇒ 依赖解析照样挂。

**正确命令**(已就地写进 §1 / §3 / §6):

```bash
mvn -DskipTests=true install -am -pl lingshu-a2a-client
```

加 `-am` 后 reactor 依次构建 Parent → Core → A2A Server → A2A Client 并全部 `install`,
`~/.m2/repository/ai/lingshu/lingshu-a2a-client/0.2.0/` 里出现真 jar
(实测产出 `.jar` + `-sources.jar` + `-javadoc.jar` 4 个文件),之后 §3 / §6 才跑得通。

**已改正文**:§1(补 `-am` + 完整报错原文)、§3(加前置条件 blockquote + 补 Phase 6 实测列)、
§6(自检三连加前置条件 + 明确「`lingshu-core` 那一腿照跑照绿,只看第一段会误判成全绿」)。

### 9.3 🟡 实测发现的真 bug #2:§4.4 的期望串是「概括」不是 Maven 实际输出

Phase 1 原文写「输出里必须出现 `banned-dependencies` **Rule 0: passed**」——
这是人话概括,`grep 'Rule 0: passed'` **匹配不到**任何东西。Maven 实际输出是:

```text
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
```

**已改正文**:§4.4 改成逐字期望串 + 给出可复制的抓取命令
(`mvn -pl lingshu-core validate 2>&1 | grep -E 'Rule [0-9]|BUILD'`)。

### 9.4 🟡 文档与现实的其他漂移(非 bug,但会误导读者)

| 位置 | Phase 1 记载 | 磁盘实测 | 处置 |
|---|---|---|---|
| §0 `.clean.txt` 行 | 29997 B,「唯一比对基准」 | **30454 B / 306 行 / md5 `64c9b344…`** —— T052 就地覆盖过,既不是 3 级也不是 4 级产物 | §0 行改写 + **基准改指 `.clean3.txt`** |
| §2 MCP 包用例数 | Phase 0 = 78 | **116**(Phase 2—6 净增) | §2 加 write-back 说明 |
| §4.2 post 侧 | 只说「diff 空」 | post md5 = `f7a440cd…`(与 pre 相同) | §4.2 补 md5 + 「0 binary delta PASS(第 37 次)」 |

**关于 `.clean.txt` 的实证**:把 T052 就地覆盖出来的 `.clean.txt` 和 4 级基准 `.clean3.txt` 对比,
多出来的 9 行是 **装饰行 + reactor 计时行**,没被任何一条 `grep -v` 命中:

```text
[INFO] ----------------------< ai.lingshu:lingshu-core >-----------------------
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] --- dependency:3.6.1:tree (default-cli) @ lingshu-core ---
[INFO] ----------------------< ai.lingshu:demo-product >-----------------------
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] --- dependency:3.6.1:tree (default-cli) @ demo-product ---
[INFO] LingShu Core ....................................... SUCCESS [T]
[INFO] LingShu Demo Product (Story #025 — HTTP SSE chat product) SUCCESS [T]
[INFO] BUILD SUCCESS
```

pre / post 两侧字节相同(都是 `64c9b344…`)⇒ 它**也**满足「0 binary delta」,
但这个 md5 **不能由本文任何一条命令复现** ⇒ 只能当过程产物,**不能当基准**。

> **§4.3.1 的「别复用 `.clean.txt`,必须从 raw 重新清洗」这条规则救了场** ——
> T054 正是按它从 raw 重跑才拿到 `f7a440cd…`。规则保留,仅把 §0 的「基准」指向补正。

### 9.5 附带实证:3 级清洗确实不可复现(§4.3.1 的论断再次成立)

按 §4.3.1 表格里「3 级」那档重跑一遍:

```text
md5 /tmp/054-deptree-pre.clean3lev.txt   → 808576f6e540bafbbd2567dd93ae4196
md5 /tmp/054-deptree-post.clean3lev.txt  → f274ca2254b60ee99dc2fac6c275c1ea
```

pre / post **不等** ⇒ 3 级档在 post 侧**假失败**(差的正是那 2 行 reactor 计时)。
**只有 4 级(`.clean3.txt`)才是唯一可复现基准** —— §4.3.1 的结论成立。

### 9.6 T054 结论

- §1—§8 **全部命令可复制粘贴、结果可复现**;
- 发现并就地修正 **2 个真 bug**(§3/§6 的 `install -am` 前置条件、§4.4 的期望串);
- 修正 **3 处文档漂移**(§0 基准文件、§2 用例数、§4.2 post md5);
- **0 行生产代码改动**(本任务只改 `quickstart.md` 文档)。

---

**Last updated**: 2026-10-25 · Phase 6 write-back(T054:按 quickstart 走一遍验证流程 + 修正 2 个真 bug(`install -am` 前置条件 / §4.4 期望串)+ 3 处文档漂移)· 承接 Phase 5 write-back(US3 Streamable HTTP:4 个新 fixture 开关 + 方法作用域过期 + 确定性抖动用法 + T044 时间锚点 + T045/T046 补强 + T047 反证抽查)