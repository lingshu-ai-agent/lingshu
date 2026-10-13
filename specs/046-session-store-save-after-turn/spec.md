# Story #046 — SessionStore.save() 真接通(turn 完成后持久化 Checkpoint)

**Story #**: #046
**Slug**: `session-store-save-after-turn`
**dsh §**: §6.1 LinearTurnEngine 收口 + §4.8 SessionStore 持久化
**类型**: Bug-fix / 收口 Story(close dead-code gap left by Story #014)
**预算**: 5 核心文件改动 / ≤ 3 ErrorCode / 0 新 Maven 依赖
**前置依赖**: Story #014(session-store-multi-backend 落盘 SPI)+ Story #045(compactor 7-arg ctor 模式 reference)
**挡板**: Story #014 file `Routers/SessionStoreRouter` Javadoc 自陈「full save/load wiring into the turn loop is the work of a follow-up Story」+ `AgentFactory.create()` L293-295 自陈「not yet injected into DefaultAgent」

---

## WHY(根因 + 业务价值)

### 问题

`FileSessionStore.save(Checkpoint)` 没有生产路径调用它。即使 yml 配 `session-store: file`,Agent 跑完一个 turn 后磁盘上也不会落盘。

### 根因

Story #014 落地只完成了 **Slot SPI + concrete impl + Provider + yml 接线** 四件套:
- `SessionStore` 接口(`save(Checkpoint)` + `load(String) → Optional<Checkpoint>`)落了
- `FileSessionStore` concrete class 落了(atomic write + path-traversal guard)
- `SessionStoreProvider` SPI 落了
- `SessionStoreAutoConfiguration` 把 2 个 Provider(`memory` / `file`)挂到 Spring 容器
- `Routers.SessionStoreRouter`(`@Autowired` 注入到 `AgentFactory` 9-Router ctor)落了

**但「save/load 接入 turn 主循环」没做**:
- `AgentFactory.create()` L295-297 resolve 出 `SessionStore` 实例 → **只 log 不 inject**(`AgentFactory.create()` L293-295 Javadoc 自陈「full save/load wiring into the turn loop is the work of a follow-up Story」)
- `DefaultAgent` 没有 `sessionStore` 字段
- `LinearTurnEngine` 没有 `sessionStore` 字段
- `DefaultSession.checkpoint()` 倒是已经实现(L102-114),只是**没人调它**

### 业务价值

- **Story #014 收口** — yml 配 `session-store: file` + `tools.ask-list: [bash]` 实际跑 demo-product,**重启后 session 历史可恢复**(Story #030 ask-mode 隔夜审批 → 业务方隔天回看需要 session 持久化)
- **Checkpoint IO 失败 ≠ turn 失败** — `Checkpoint.id` + `history` 都是 in-memory 已有,持久化是 best-effort,IO 失败只能 warn 不能 crash
- **`save()` 必须在 4 个 turn 收口点全调用** — cancel / waitForLlm-cancel / normal-end / exception,任何一处漏掉都会让磁盘状态与内存状态不一致(用户重启 Agent 后看到的"上半场对话"残缺)
- **Story #046 = Story #014 的「drive shaft 装配」** — engine 缺一个"turn 跑完时把 session 落盘"的钩子;没这钩子,Slot 5 整套(file backend / atomic write / multi-tenant path safety / 跨进程恢复)都是 idle 基建
- **业务场景**:demo-product 用户跑了 5 轮对话,OOM 杀掉进程 / k8s rolling-restart → 用户**预期**下一轮 `POST /api/chat/{sessionId}` 还能看到前 5 轮 history;**当前实测**:重启后 history 是空(因为 `FileSessionStore` 从来没被写过)

### 为什么不是 RFC / 宪章改 / 跨 Story 改 constitution

- **0 新接口契约**:`SessionStore` SPI + `Checkpoint` POJO + `DefaultSession.checkpoint()` **全部 0 改动**,只是补 turn-loop 调用
- **0 新 ErrorCode**:复用 Story #014 `LINGS-X01`(已在 `FileSessionStore.save/load` 内 catch `IOException` + `JsonProcessingException` 包进去),本 Story 只是把 save 调到生产路径,IO 失败仍走 `LINGS-X01` 路径,**不引入新错误码**
- **0 新 Maven 依赖**:纯 JDK 8 + Lombok + Spring + Jackson + `java.util.concurrent` 全部已锁
- **≤ 5 核心文件改动**:3 核心 prod 文件(`LinearTurnEngine` / `LinearTurnEngineProvider` / `NullSessionStore.java` new),0 改 `AgentFactory`(9-Router ctor + SessionStoreRouter resolve 已就位 from Story #014),0 改 `DefaultSession`(checkpoint() 已实现)
- **0 改 AgentConfig schema**:`agent.session-store: file` / `agent.session-store: memory` yml 接线已落(Story #014 + `ChatController.createSession` L127-130 env.getProperty 读取),**业务方 0 改动**

---

## WHO(参与者 + 边界)

| 角色 | 改动范围 | 行为 |
|---|---|---|
| **LinearTurnEngine** | modify(扩 ctor + 加 save 钩子) | T1 时刻拿 `SessionStore` 实例,T2 收口 4 个点调 `trySave(ctx)` |
| **LinearTurnEngineProvider** | modify(扩 7→8-arg ctor + 解析 SessionStore) | Spring 6 dual-ctor 模式镜像 Story #045(legacy 7-arg 委托 8-arg 传 `null` SessionStoreRouter,primary 8-arg 加 `@Autowired`) |
| **NullSessionStore** | new(镜像 NullCompactor 模式) | 单例 + `save()` no-op + `load()` 返 `Optional.empty()` 保护 Story #001-#029 fixture |
| **DefaultSession** | 0 改动 | `checkpoint()` L102-114 已实现,直接复用 |
| **AgentFactory** | 0 改动 | 9-Router ctor 已有 `SessionStoreRouter` 字段(Story #014 L114),`create()` L295-297 已有 resolve + log,**不需改** |
| **AgentConfig** | 0 改动 | `getSessionStore()` 字段已存在(Story #014),yml 接线 `agent.session-store: file` 已落(Story #014 + Story #045 共享) |
| **DefaultAgent** | 0 改动 | 业务方不需要 sessionStore 字段 — 持久化由 engine 内部收口 |
| **SessionStore** SPI | 0 改动 | 公开方法签名 `save(Checkpoint)` / `load(String) → Optional<Checkpoint>` 不变 |
| **Checkpoint** POJO | 0 改动 | `@Value` + `@JsonCreator` + `@JsonProperty` 4-arg ctor 不变,Jackson round-trip 仍工作 |
| **Routers.SessionStoreRouter** | 0 改动 | `resolve(name, cfg) → SessionStore` 行为不变 |
| **FileSessionStore** | 0 改动 | `save/load/list/delete` 4 方法契约不变,atomic write + `LINGS-X01` 错误路径不变 |
| **InMemorySessionStore** | 0 改动 | 行为不变,只是 in-memory backend 现在**真**会接到 save() 调用 |
| **demo-product ChatController** | 0 改动 | `application.yml` + `env.getProperty("agent.session-store", ...)` L127 接线已落(Story #014 + Story #045 共享),`POST /api/chat/{sessionId}` round-trip 不需改 |
| **YamlWatcher** | 0 改动 | hot-reload 不影响 SessionStore wiring(独立于 AgentConfig 的 `session-store` key) |
| **Tool / ToolExecutor / Compactor / PermissionPolicy** | 0 改动 | 9 Slot 体系不变,本 Story 只接 Slot 5 主循环 |
| **MCP / A2A / MemorySource** | 0 改动 | Slot 1/2/3/4/6/7/8/9 公开方法签名不变 |

---

## WHAT(用户故事 + 验收)

### User Story 1 — turn 完成后 Checkpoint 落盘(P1,主线)

**描述**:用户跑 demo-product,创建 session 后跑 5 轮对话,杀进程,kill -9 模拟 OOM,重启 Agent,GET `/api/sessions/{sessionId}/history` 看到前 5 轮 history 完整恢复(每轮的 user message + assistant message + tool_use/tool_result 都在)。

**Why this priority**:Story #014 file backend 整套(atomic write / path-traversal / multi-tenant)都是为这个故事铺路;不接通等于把 Story #014 整段都变成 idle 基建。

**Independent Test**:
1. `mvn -pl lingshu-examples/demo-product spring-boot:run` 启动
2. `application.yml` 配 `agent.session-store: file`(已落,Story #014 + Story #045 共享)
3. `curl -X POST localhost:8080/api/sessions` → 拿 `sessionId`
4. `curl -X POST localhost:8080/api/chat/{sessionId} -d '{"prompt":"hello"}'` 跑 1 轮
5. `ls -la /var/folders/.../T/lingshu-sessions/{sessionId}.json` → 文件**存在**
6. `cat /tmp/lingshu-sessions/{sessionId}.json` → JSON 含 `"id": "{sessionId}"` + `"history": [...]` + `"createdAt": "..."`
7. kill -9 spring-boot 进程,重启
8. `curl localhost:8080/api/sessions/{sessionId}/history` → 看到前 1 轮 history

**Acceptance Scenarios**:

1. **Given** `agent.session-store: file` yml 配置 + demo-product 启动 **When** 用户跑 1 轮对话 + 翻 `agent.session-store.dir` 目录 **Then** `<sessionId>.json` 文件存在 + JSON round-trip 成功(`@JsonCreator` 反序列化回 `Checkpoint` + `Message` 4 子类多态)

2. **Given** `agent.session-store: memory` yml 配置 + demo-product 启动 **When** 用户跑 1 轮对话 **Then** 不读不写磁盘(行为 0 变化 vs Story #001-#045) + `InMemorySessionStore.put` 接到 save() 调用且**不**写磁盘

3. **Given** `agent.session-store: file` + turn 跑到一半按 Ctrl-C **When** spring-boot shutdown hook 触发 `broadcastCancel()` **Then** 磁盘文件仍存在(因为 L210 loop-head cancel 也会调 save()) + StopReason = CANCELLED

4. **Given** turn 跑到一半 LLM 抛 RuntimeException **When** catch 块 L309-313 触发 **Then** 磁盘文件**也**存在(异常路径 L312 也调 save()) + StopReason = ERROR + 文件内容含异常发生前的 history

5. **Given** `agent.session-store: file` + 配置文件目录不可写(例如 `chmod 000 /tmp/lingshu-sessions`) **When** 跑 1 轮对话 **Then** turn **不**崩 + sink 收 `AgentEvent.ErrorEvent(LINGS-X01)` + 内存 session 仍可用 + 业务方无感

### User Story 2 — 4 个收口点全部调 save()(P1,核心契约)

**描述**:`LinearTurnEngine.runTurn()` 4 个 `TurnCompleted` 发射点(loop-head cancel L210 / waitForLlm-cancel L346 / normal-end L305 / exception L312)全部调 `trySave(ctx)`,缺一不可。这是 Slot 5 接通的核心契约。

**Why this priority**:业务方期待"turn 跑完磁盘就该有",任何一处漏掉都会让"前 5 轮对话"残缺;同时这 4 个点是 engine 4 个不同退出语义(cancel / cancel-after-llm / success / error),必须**全**覆盖。

**Independent Test**:4 个独立 L1 unit test,每个用 `RecordingSessionStore`(`extends SessionStore`,记录 `save()` 调用次数 + 传入的 `Checkpoint`)挂到 engine,跑各场景,断言 `saveCount == 1` + `lastCheckpoint.id == sessionId` + `lastCheckpoint.history.size == N`。

**Acceptance Scenarios**:

1. **Given** `LinearTurnEngine` 8-arg ctor(带 `RecordingSessionStore`)+ mock LLM 1 轮直答 **When** `runTurn()` 走完 **Then** `RecordingSessionStore.saveCount == 1` + `lastCheckpoint.getStopReason() == END_TURN` + `lastCheckpoint.getHistory()` 包含 user message + assistant message

2. **Given** 同上 + mock LLM 返回 tool call + 触发 `ctx.cancellation().fire()` **When** `runTurn()` 在 L207-212 退出 **Then** `saveCount == 1` + `lastCheckpoint.getStopReason() == CANCELLED`

3. **Given** 同上 + mock LLM 永远 hang(`CompletableFuture` 不 complete)+ 触发 cancellation **When** `runTurn()` 在 `waitForLlm` L346 退出 **Then** `saveCount == 1` + `lastCheckpoint.getStopReason() == CANCELLED`

4. **Given** 同上 + mock LLM 抛 `RuntimeException("test")` **When** `runTurn()` 走 L309-313 catch 块 **Then** `saveCount == 1` + `lastCheckpoint.getStopReason() == ERROR` + `lastCheckpoint.getHistory()` 含 user message(异常前已 append)

### User Story 3 — 跨进程 checkpoint load()(P2,load 端连通)

**描述**:`FileSessionStore.load(sessionId)` 真接到生产路径 — demo-product 重启后 `AgentFactory.create()` 看到 session id 命中磁盘 → `DefaultSession` 初始化时从 `FileSessionStore.load()` 拉历史(Story #046 v1 只做 save 端,load 端留给 OQ-Future 标记,但测试覆盖 FileSessionStore.load round-trip e2e 正确性)。

**Why this priority**:User Story 1 已覆盖 save 端,本 Story 验证 load 端 e2e(独立 L2 IT:写一个 .json → new FileSessionStore → load() → Checkpoint 字段 round-trip 正确)。

**Independent Test**:`AgentFactoryYamlSessionStoreLoadIT` 写一个手工构造的 `{sessionId}.json` → `FileSessionStore.load(sessionId)` → 断言 `Checkpoint.history.size() == 2` + `Message.User` 字段全 round-trip。

**Acceptance Scenarios**:

1. **Given** `{sessionId}.json` 含 user + assistant 2 条 message **When** `FileSessionStore.load(sessionId)` **Then** `Optional<Checkpoint>` 非空 + `history` 含 2 条 message + 字段值与原 JSON 一致

2. **Given** 磁盘无 `{sessionId}.json` **When** `load(sessionId)` **Then** `Optional.empty()`(首次会话正常路径,**不**抛)

3. **Given** 磁盘有 `{sessionId}.json` 但 JSON 损坏(`}` 缺失) **When** `load(sessionId)` **Then** 抛 `LingsSessionStoreException[LINGS-X01]`(Story #014 既有行为不变)

### User Story 4 — NullObject 保护历史 fixture(P1,back-compat)

**描述**:`NullSessionStore.INSTANCE` 单例镜像 Story #045 `NullCompactor.INSTANCE` 模式,保护 Story #001-#029 + Story #030-#045 全部 fixture(那些 fixture 用 5/6/7-arg ctor `new LinearTurnEngine(...)` 不传 SessionStore,本 Story 必须让它们的 turn 行为 0 变化)。

**Why this priority**:Story #045 实证过 — 不加 NullObject,53 个 fixture 全部需要手 new 第 8 参数,导致 Story #045 范围爆炸。本 Story 0 fixture 改动(全过 NullObject sentinel 守)是关键交付。

**Independent Test**:`NullSessionStoreTest` 5 case: `INSTANCE` 非 null / `save(anything)` 不抛 / `load(any)` 返 `Optional.empty()` / 反序列化合约 `CONTRACT_VERSION == "1.0.0"`(可省,SessionStore.CONTRACT_VERSION 是 SPI 的)+ 多线程并发 `save()` 不抛。

**Acceptance Scenarios**:

1. **Given** `NullSessionStore.INSTANCE` **When** 调 `save(anyCheckpoint)` **Then** 静默成功 + 无副作用

2. **Given** 同上 **When** 调 `load("any-id")` **Then** `Optional.empty()`(永远不命中,业务方按 first-turn 路径走)

3. **Given** 53 existing test fixture(Story #001-#029 + Story #030-#045)用 5/6/7-arg ctor **When** 跑 mvn test **Then** **0 改动**全过 + turn 行为 0 变化(`NullSessionStore.save()` 静默 no-op)

---

## AC(Acceptance Criteria 编号清单)

| AC# | 类型 | 描述 | 测试方法 |
|---|---|---|---|
| **AC-046-1** | L1 unit | `NullSessionStore.INSTANCE.save()` 静默 no-op + `load()` 返 `Optional.empty()` | `NullSessionStoreTest` |
| **AC-046-2** | L1 unit | `LinearTurnEngine` 8-arg ctor primary + 7-arg ctor legacy 委托 8-arg 传 `null` SessionStoreRouter(镜像 Story #045 dual-ctor) | `LinearTurnEngineSessionStoreTest` |
| **AC-046-3** | L1 unit | `LinearTurnEngine.runTurn` normal-end(L305 路径)调 `save()` 1 次 + Checkpoint 字段正确 | `LinearTurnEngineSessionStoreTest.normalEnd_savesOnce` |
| **AC-046-4** | L1 unit | `LinearTurnEngine.runTurn` loop-head cancel(L210 路径)调 `save()` 1 次 | `LinearTurnEngineSessionStoreTest.loopHeadCancel_savesOnce` |
| **AC-046-5** | L1 unit | `LinearTurnEngine.runTurn` waitForLlm cancel(L346 路径)调 `save()` 1 次 | `LinearTurnEngineSessionStoreTest.waitForLlmCancel_savesOnce` |
| **AC-046-6** | L1 unit | `LinearTurnEngine.runTurn` exception(L312 路径)调 `save()` 1 次 | `LinearTurnEngineSessionStoreTest.exception_savesOnce` |
| **AC-046-7** | L1 unit | `trySave` IO 失败不 crash turn + 收 `AgentEvent.ErrorEvent[LINGS-X01]` + 内存 session 仍可用 | `LinearTurnEngineSessionStoreTest.ioFailure_logsWarnAndKeepsTurnAlive` |
| **AC-046-8** | L1 unit | `LinearTurnEngineProvider` 8-arg ctor `@Autowired` 显式 primary(镜像 Story #045 Spring 6 dual-ctor) | `LinearTurnEngineProviderSessionStoreTest` |
| **AC-046-9** | L1 unit | `LinearTurnEngineProvider.create()` 在 `sessionStoreRouter != null` 时走 `resolve(name, cfg)`,否则走 `NullSessionStore.INSTANCE` | `LinearTurnEngineProviderSessionStoreTest` |
| **AC-046-10** | L1 unit | `LinearTurnEngineProvider` legacy 7-arg ctor 委托 8-arg 传 `null` SessionStoreRouter → NullSessionStore wire-in | `LinearTurnEngineProviderSessionStoreTest` |
| **AC-046-11** | L2 IT | `AgentFactory.create()` + `LinearTurnEngine.runTurn()` end-to-end + `FileSessionStore` 实际写磁盘 + 文件存在 | `AgentFactoryYamlSessionStoreSaveIT` |
| **AC-046-12** | L2 IT | `FileSessionStore.load(sessionId)` 从手工 .json 读回 + `Checkpoint.history` 字段 round-trip 正确 | `AgentFactoryYamlSessionStoreLoadIT` |
| **AC-046-13** | L2 IT | `agent.session-store: file` 实际跑 turn → `ls` 配置文件目录看到 `<sessionId>.json` | `AgentFactoryYamlSessionStoreSaveIT` |
| **AC-046-14** | L2 IT | `agent.session-store: memory` 实际跑 turn → 内存 store 接到 save() 但不写磁盘 | `AgentFactoryYamlSessionStoreSaveIT` |
| **AC-046-15** | RAC | 53 existing fixture(Story #001-#029 + Story #030-#045)**0 改动**全过(`NullSessionStore` sentinel 保护) | `mvn -pl lingshu-core test` 全 suite 跑过 0 regression |
| **AC-046-16** | DepTree | `mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = **0 binary delta PASS** | baseline 镜像 `mvn -pl lingshu-core` 之前 commit 的 pom.xml + 之后 commit 的 pom.xml md5sum 相同 |

---

## 关键不变项

| 不变项 | 状态 | 备注 |
|---|---|---|
| `SessionStore` SPI 公开方法签名 | 0 改动 | `save(Checkpoint)` + `load(String) → Optional<Checkpoint>` 2 方法不变 |
| `SessionStoreProvider` SPI 公开方法签名 | 0 改动 | `name()` / `priority()` / `version()` / `create(AgentConfig)` 4 方法不变 |
| `Routers.SessionStoreRouter` 行为 | 0 改动 | `resolve(name, cfg) → SessionStore` 不变(Story #014 已落) |
| `FileSessionStore` 公开方法签名 | 0 改动 | `save/load/list/delete` 4 方法不变,atomic write 协议不变 |
| `InMemorySessionStore` 行为 | 0 改动 | 只是现在真接到 save() 调用,行为不变 |
| `Checkpoint` POJO 形状 | 0 改动 | `@Value` + `@JsonCreator` + `@JsonProperty` 4 字段(id, history, meta, createdAt)不变 |
| `DefaultSession.checkpoint()` 实现 | 0 改动 | L102-114 已实现,直接复用 |
| `AgentConfig` 不可变契约 | 0 改动 | `getSessionStore()` 字段已存在,yml `agent.session-store: file` 接线已落 |
| `AgentFactory` 9-Router ctor | 0 改动 | Story #014 + Story #045 链路已就位,SessionStoreRouter 是 8th implicit Router |
| `AgentFactory.create()` resolve SessionStore | 0 改动 | L295-297 已 resolve,本 Story **不**需新增 resolve,只是把 resolved instance 改成实际 inject |
| `DefaultAgent` 公开方法 | 0 改动 | 业务方不需感知 sessionStore 字段(由 engine 内部消费) |
| `Tool` / `ToolExecutor` 5 步流水线 | 0 改动 | §4.10.1 硬规则 2 守住 |
| `Compactor` SPI 公开方法签名 | 0 改动 | `shouldCompact(Prompt) → boolean` + `compact(TurnContext) → void` 不变 |
| `LlmProvider` SPI | 0 改动 | `stream(Prompt, TurnContext, Subscriber<AgentEvent>) → CompletableFuture<LlmResponse>` 不变 |
| `PermissionPolicy` SPI | 0 改动 | `check(Tool, ToolExecutionContext) → Decision` 不变 |
| `PromptBuilder` SPI | 0 改动 | `build(TurnContext) → Prompt` 不变 |
| `FlowEngine` SPI | 0 改动 | `runTurn(TurnContext, Subscriber<AgentEvent>)` 公开方法不变(只新增 1 个 ctor 参数,公开方法签名 0 改动) |
| `Message` 4 子类 | 0 改动 | (System / User / Assistant / ToolResult)不变 |
| `ToolResult` 不可变契约 | 0 改动 | 4 字段不变 |
| §4.7 PermissionPolicy / AuditLogger / Cost 域 | 完全兼容 | 9 Slot 体系不变,本 Story 只接 Slot 5 主循环 |
| 24/26 字段 AgentConfig schema | 0 字段新增 | 复用 Story #044 + #045 schema |
| 9 Slot 体系 | 不变 | Slot 1/2/3/4/6/7/8/9 公开方法签名不变 |
| JDK 8 兼容 | 守住 | 纯 JDK 8 + Lombok + Spring + Jackson 已锁 13 项依赖表内 0 新 binary 引入 |
| Spring AI `ChatClient.tools().call()` 仍**禁止**使用 | 守住 | §4.10.1 硬规则 2 守住 |
| ReAct Loop 自实现 | 守住 | §4.10.1 硬规则 1 守住 |

---

## 反模式 / 风险 / OQ

### 反模式

- **不要把 save 放 `AgentFactory.create()` 后** — `create()` 只在 session 创建时跑 1 次,turn 跑 N 次,放错位置等于 0 落盘
- **不要让 save 失败 crash turn** — Checkpoint 是 best-effort 落盘,失败只能 WARN + 让 turn 继续(否则 IO 抖动会 kill 业务)
- **不要新加 public method to `LinearTurnEngine`** — 公开方法签名 0 改动,只新增 ctor 参数 + 4 个内部 save() 调用
- **不要把 save 放 `try` 外** — IO 失败抛 RuntimeException 会让 turn 走 error path 误报,**save 内部必须 try/catch**

### 风险

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| **save() 阻塞 turn tail 延迟** | 中 | turn 完 5-50ms IO 等待,NFR 拉胯 | `trySave` 内部 try/catch + best-effort log;不传 timeout(原子写 < 1ms 99% 情况);future 化待 Story #046.1 follow-up |
| **并发 turn 同 session 写文件覆盖** | 低 | 用户开 2 tab 同 sessionId → 后写覆盖前写 | `FileSessionStore.save` 已有 atomic move,但 race 仍可能;**Story #046 不修**(现 turn 都是单 Agent 串行,跨 Agent 同 sessionId 是 OQ-Future) |
| **大 history(>100K tokens)JSON 序列化慢** | 低 | turn 完卡 200ms+ 序列化 | `Checkpoint.history` 已是 immutable list 引用 + Jackson 已锁 13 项依赖;**本 Story 不优化**(Story #018 Compactor 已在管) |
| **`NullSessionStore` 名字误用** | 低 | 业务方误以为 in-memory store | Javadoc 明确「sentinel for back-compat, NOT production choice」+ `InMemorySessionStore` 是生产选择 |
| **9-Router ctor 顺序** | 低 | `LinearTurnEngineProvider` 8-arg ctor `@Autowired` 顺序写错导致 resolve 失败 | 严格镜像 Story #045 7-arg ctor 字段顺序(PromptBuilderRouter / LlmProviderRouter / ToolExecutorRouter / PermissionPolicyRouter / agentToolPool / ApprovalRegistry / CompactorRouter / **SessionStoreRouter**) |

### OQ(Open Questions) / Follow-up

- **OQ-046.1(load 端)**:本 Story 只接通 save 端(load 仍需 `FileSessionStore.load` 显式调,Story #046 不接);完整"重启恢复 history"在 Story #046.1 follow-up(5 文件改动预算留给 #046 实际只够 save 端)
- **OQ-046.2(async flush)**:save 是同步调用,turn tail 加 1-50ms IO 等待;OQ-Future 加 `CompletableFuture<Checkpoint> asyncSave = sessionStore.asyncSave(checkpoint)` 不阻塞 turn
- **OQ-046.3(并发同 sessionId)**:跨 turn / 跨 Agent 同 sessionId 写冲突 OQ-Future(加 file lock 或 move-to-versioned-name);**Story #046 范围内假设单 Agent 串行写**
- **OQ-046.4(Compactor 触发后落盘)**:当前 `trySave` 在 `TurnCompleted` 之前调,Compactor 已 mutate `ctx.session().history()` 之后才 save,**不**存原始 history(业务方若需"压缩前/后"两个版本,OQ-Future 加 `Checkpoint.compactionDelta`)

---

## 实施期顺序(高阶)

1. **Phase 1 — Setup(并行)**:写 `NullSessionStore` 单例(镜像 `NullCompactor`)+ 写 `RecordingSessionStore` test helper
2. **Phase 2 — Core 串行**:`LinearTurnEngine` 加 8-arg ctor + `trySave` helper + 4 收口点插桩 → `LinearTurnEngineProvider` 加 8-arg ctor(Spring 6 dual-ctor 模式)
3. **Phase 3 — Tests(并行)**:`NullSessionStoreTest` 3 case + `LinearTurnEngineSessionStoreTest` 6 case + `LinearTurnEngineProviderSessionStoreTest` 3 case + `AgentFactoryYamlSessionStoreSaveIT` 4 case + `AgentFactoryYamlSessionStoreLoadIT` 2 case
4. **Phase 4 — Validation**:mvn test 全 suite 跑过 0 regression(RAC) + mvn -pl lingshu-core dependency:tree baseline 镜像(AC-046-16)
5. **Phase 5 — Docs 同步**:CLAUDE.md v1.3.55 + dsh v1.5.58 → v1.5.59(本 Story 完成 + 累计 47 Story)+ specs/ROADMAP.md 加 #046 行 + README.md 「更新日期」段加 🆕 Story #046 顶部 blockquote + 「核心特性」段补 💾 Slot 5 SessionStore save 真接通 bullet + 「Story 路线图」段追加 #046 retrospective + constitution §10 R-13 缓解 Story 列表补 #046 行**第 30 次** R-13 mitigation (d) PASS 0 binary delta
