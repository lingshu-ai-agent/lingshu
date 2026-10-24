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
| `/tmp/054-deptree-pre.clean.txt` | 29997 B | 同上 **清洗后**的可比对摘要(唯一比对基准) |
| `/tmp/054-test-pre.txt` | 63001 B | `mvn test` 全量输出 |
| `/tmp/054-mcp-tests-pre.txt` | 2775 B | MCP 包 27 文件 × `@Test` 计数清单(78 个) |

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
Maven 会去 central 拉 —— 该仓未发布到 central,此时加 `-o`(offline)或先 `mvn -DskipTests=true install -pl lingshu-a2a-client`。

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

---

## 3. 跑全量 + 对比基线

```bash
mvn -pl lingshu-core,lingshu-examples/demo-product test 2>&1 | tee /tmp/054-test-post.txt
```

判定标准(Phase 5 / T051 的验收线):

| 指标 | Phase 0 基线 | Phase 2+ 目标 |
|---|---|---|
| Tests run | 802 | ≥ 802(只增不减) |
| Failures | 0 | **必须 0** |
| Errors | 18(全在 `ai.lingshu.core.mcp.*`) | **必须 0** |
| Skipped | 0 | 0 |

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

输出里必须出现 `banned-dependencies` **Rule 0: passed**。本 Story 引入的新增物
(`com.sun.net.httpserver`、`AtomicLong`、`CountDownLatch`、`Collections.*`)全是 JDK 1.6+ 内置,
0 新 Maven 依赖(FR-026),所以这条是纯守门,预期一次就过。

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
mvn -pl lingshu-core test -Dtest='ai.lingshu.core.mcp.**'   # 本 Phase 新用例绿
mvn -pl lingshu-core,lingshu-examples/demo-product test       # 全量 Errors 计数下降
git status --short                                             # 只该看到本 Story 的文件
```

`git status` 里若出现 `README.md` / `UPDATEINFO.md` / `specs/ROADMAP.md` 三个 **PR #90 遗留脏文件**,
**不要 `git add` 它们**(按文件逐个 add,见下)。唯一例外是
`lingshu-examples/demo-product/src/main/resources/application.yml` —— 那个是缺陷 A 的合法改动,属于本 Story。

---

**Last updated**: 2026-10-24 · Phase 3 write-back(US1 相对路径落地 + R-13 镜像实测 + T016 fixture 收口)