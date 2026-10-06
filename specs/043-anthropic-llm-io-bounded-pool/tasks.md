# Story #043 `anthropic-llm-io-bounded-pool` — Tasks

> **Status**: Draft 2026-10-06
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 plan**: [`plan.md`](./plan.md)
> **完成定义**: T-1 ~ T-6 全 ✅ + 现有 700+ 测试 0 改动全过

---

## T-1: AnthropicLlmProvider.java modify

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java`

**操作**:

1. 删除旧 import `Executors`,新增 `LinkedBlockingQueue` / `ThreadFactory` / `ThreadPoolExecutor` / `TimeUnit` / `AtomicInteger` 5 个 import
2. 替换 L74-78 旧 `ioExecutor` 字段为 L107 调用 `buildBoundedIoExecutor()` 静态方法
3. 新增 L78-106 类级 Javadoc 详细描述 Story #043 改动 + 镜像 ToolExecutorConfig 字段对齐表
4. 新增 L109-131 私有静态方法 `buildBoundedIoExecutor()` 实现 bounded pool shape

**代码块**(已落盘,完整代码见 plan §1.2)

**验证**:
- `grep -c 'newCachedThreadPool' lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` → 0
- `grep -c 'ThreadPoolExecutor' lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` → 2(`@Override` 关键字附近 + `new ThreadPoolExecutor(...)`)
- `grep -c 'CallerRunsPolicy' lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` → 1

---

## T-2: AnthropicLlmProviderBoundedPoolTest.java new

**路径**: `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderBoundedPoolTest.java`

**操作**: 写 7 case L1 unit test(plan §4.1):

1. `poolType_isThreadPoolExecutor` —— 反射 `ioExecutor` 字段,`getClass() == ThreadPoolExecutor.class`
2. `corePoolSize_isAvailableProcessorsTimes2`
3. `maxPoolSize_isCoreTimes2`
4. `keepAliveTime_is60Seconds`
5. `queue_isLinkedBlockingQueueOf256` —— `remainingCapacity() + size() == 256`
6. `rejectedHandler_isCallerRunsPolicy`
7. `threadName_prefix_isAnthropicLlmIo_N_daemon` —— submit Runnable + `Thread.getAllStackTraces()` 找 `anthropic-llm-io-N` + daemon=true

**验证**:
- `mvn -pl lingshu-core test -Dtest='AnthropicLlmProviderBoundedPoolTest'` → 7/7 PASS

---

## T-3: 现有测试 0 regression

**目标**: 现有 Anthropic 测试 0 改动全过(全 lingshu-core test suite)

**执行**:

```bash
mvn -pl lingshu-core test
```

**期望**:
- `Tests run: 707, Failures: 0, Errors: 0, Skipped: 0`
- 包含我新增的 7 case(700 旧 + 7 新 = 707)
- 既有 `AnthropicLlmProviderTest` / `AnthropicStreamEventTest` / `AnthropicStreamParserTest` / `AnthropicStreamProviderIT` / `LingsLlmProviderExceptionTest` 全部 PASS
- `AnthropicToolReActIT` 在 full suite 中 PASS(standalone flake pre-existing,git stash 测试确认 HEAD 也 fail)

**反向验证**:
- ✅ T-3.R1: `AnthropicLlmProviderTest` 8 case(Story #027a 落)+ 5 case(Story #027b 落)= 13 case 0 改动全过
- ✅ T-3.R2: `AnthropicStreamProviderIT` 2 case + `AnthropicToolReActIT` 2 case + `AnthropicStreamEventTest` 2 case + `AnthropicStreamParserTest` 6 case + `LingsLlmProviderExceptionTest` 1 case 0 改动全过
- ✅ T-3.R3: full suite 700 旧 case 0 改动全过(707 - 7 = 700)

---

## T-4: R-13 mitigation (d) 自查

**核心声明**: 纯实现层 hygiene fix,0 新 Maven 依赖,0 新 ErrorCode,0 新二进制

**自查清单**:

- [x] `mvn -pl lingshu-core dependency:tree` pre/post diff md5sum 相同(`9b7a46af5f08013ea85a7e9ca7d89c2e`)= **0 binary delta 第 26 次 PASS**
- [x] `banned-dependencies` enforcer Rule 0: passed(`Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed`)
- [x] 0 新 Maven 依赖 —— 改动仅用 JDK 8 内置 `ThreadPoolExecutor` + `LinkedBlockingQueue` + `AtomicInteger` + `ThreadFactory` + `CallerRunsPolicy`
- [x] 0 新 ErrorCode —— 纯池 sizing,业务异常仍走现有 LINGS-L0X 系列
- [x] 镜像 `ToolExecutorConfig.agentToolPool` 100% shape(单字段差异:线程名前缀 + per-instance 计数)
- [x] 线程名前缀 `anthropic-llm-io-N` 与 `lingshu-tool-N` 风格对齐(jstack 看得清)
- [x] daemon = true 保留(JVM exit 不阻塞)

**PR body 末尾**:

```markdown
### R-13 dependency:tree 自查

纯实现层 hygiene fix(AnthropicLlmProvider.java 字段初始化替换,1 文件 modify + 1 文件 new):
- 0 新 Maven 依赖
- 0 新 ErrorCode
- 镜像 ToolExecutorConfig.agentToolPool 100% shape
- `mvn -pl lingshu-core dependency:tree` pre/post md5sum 相同(`9b7a46af5f08013ea85a7e9ca7d89c2e`)= 0 binary delta 第 26 次 PASS
- `banned-dependencies` enforcer Rule 0: passed
```

---

## T-5: commit + PR

### T-5.1: commit

```bash
git add lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderBoundedPoolTest.java \
        specs/043-anthropic-llm-io-bounded-pool/{spec,plan,tasks}.md
git commit -m "feat(llm): Story #043 anthropic-llm-io-bounded-pool — 镜像 ToolExecutorConfig 有界池 + CallerRunsPolicy 兜底 + R-13 0 binary delta

AnthropicLlmProvider.ioExecutor 从 newCachedThreadPool(maximumPoolSize=Integer.MAX_VALUE)
替换为有界 ThreadPoolExecutor(corePool=cores*2, maxPool=cores*4, queue=256, CallerRunsPolicy,
keepAlive=60s, daemon=true)。镜像 ToolExecutorConfig.agentToolPool 100% shape,根除项目里
lingshu-core/src/main/java/ 唯一一处无界池生产代码。

线程名前缀 anthropic-llm-io-N (per-instance 计数,与 lingshu-tool-N 风格对齐)。
CallerRunsPolicy 在队列满时让 LinearTurnEngine 主线程兜底执行,back-pressure 慢不丢。

7 new case L1 unit test(反射读 ioExecutor 字段 + 验证 ThreadPoolExecutor shape +
LinkedBlockingQueue(256) + CallerRunsPolicy + 线程名前缀 + daemon)。
707 全 lingshu-core 测试 0 regression,AnthropicToolReActIT standalone flake pre-existing
(git stash 测试确认 HEAD 也 fail,与本 Story 无关)。

R-13 mitigation (d) baseline 镜像 pre/post md5sum 相同 = 0 binary delta 第 26 次 PASS。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

**PR 标题**: `feat(llm): Story #043 anthropic-llm-io-bounded-pool — bounded I/O pool + CallerRunsPolicy`

**PR body 模板**:
- spec.md / plan.md / tasks.md 三件套链接
- AC-NN-deps-1 / AC-NN-pool-1 / AC-NN-name-1 / AC-NN-1 / AC-NN-2 验证输出
- 反向 AC 验证(AnthropicToolReActIT standalone flake pre-existing)
- R-13 dependency:tree 自查节
- 关键不变项列表

### T-5.2: 合入后同步

- [ ] `dsh_agent_design.md` §13 changelog 新增 v1.5.56 行(本 Story 完成)
- [ ] `dsh_agent_design.md` §10 NFR 表 "并发 turn 数" 行加 footnote → Story #044 处理 docs/code 兑现
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 `#043` 行(第 26 次 PASS 0 binary delta)
- [ ] `specs/ROADMAP.md` 段一 ✅ 已完成加 #043 行
- [ ] `specs/ROADMAP.md` 段二 🟡 待补 #043 划掉
- [ ] `specs/ROADMAP.md` 段五 🎯 实施节奏 累计计数 42 → 43
- [ ] `CLAUDE.md` 同步 v1.3.50 → v1.3.51(本 Story 文档同步)
- [ ] `README.md` 顶部 🆕 v1.5.56 Story #043 blockquote + 「核心特性」段补 🧵 bounded I/O pool bullet + 「Story 路线图」段追加 #043 retrospective

---

## T-6: PR review / merge

```bash
gh pr create --base main --head feat/anthropic-llm-io-bounded-pool \
  --title "feat(llm): Story #043 anthropic-llm-io-bounded-pool" \
  --body "..."
```

**期望**: PR auto-merge / reviewer approve / merge commit + close #69

---

## 完成定义

- T-1 ~ T-6 全 ✅
- 707 全 lingshu-core 测试 PASS(700 旧 0 改动 + 7 新)
- R-13 mitigation (d) baseline 镜像 pre/post md5sum 相同 PASS 0 binary delta 第 26 次
- 0 新 ErrorCode / 0 新 Maven 依赖 / 0 新二进制
- PR merged + 文档同步完成
- 累计 Story 合入:42 → **43**
- `constitution.md` §4 域字母 11 不变 / §10 R-13 缓解 Story 列表 +1 行
- `dsh_agent_design.md` §13 changelog +1 行(v1.5.56) + §10 NFR 表加 footnote
- `CLAUDE.md` v1.3.50 → v1.3.51
