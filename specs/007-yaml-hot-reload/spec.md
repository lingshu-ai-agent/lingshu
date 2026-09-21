# Feature Specification: Story #007 yaml-hot-reload

**Feature Branch**: `story-007-yaml-hot-reload`
**Created**: 2026-09-21
**Status**: Draft
**Input**: User description: "Story #007 yaml-hot-reload — AgentConfigRegistry AtomicReference swap + YamlWatcher file poll + DefaultAgent 读取最新 config + 旧 turn 冻结语义(AC-06, dsh §14.8 N8 + §0.4 AC-06 L121-125)"

**Source Design Doc**: `dsh_agent_design.md` v1.5.34
- §0.4 AC-06 L121-125(YAML 热更无中断 — T1 用旧 config 跑完,T2 用新 config)
- §1 锁定决策 #9 Spring Boot Auto-Config + §7.1.2 T1 `AgentFactory.create()` 启动期集中校验(reuse for publish-time validation)
- §14.8 N8 L6780-6814(YamlWatcher + AgentConfigRegistry AtomicReference + DefaultAgent 读最新 config + 冻结语义)
- §15 ErrorCode(本 Story **0 新增**;复用 `LINGS-C02`/`LINGS-C03` 配置失败抛,`LINGS-Z01` 内部 invariant 违反)
- §17 Risk Register R-03 L7237(YAML 热更与 in-flight turn 数据竞争 — §14.8 mitigation: AtomicReference swap + 旧 turn 冻结 cfg.snapshot())(分值 6)
- §16 Glossary(YamlWatcher / AgentConfigRegistry / 冻结语义 / publish / listener)

**Constitution**: `.specify/memory/constitution.md` v1.0
- §1 #12 启动时配置校验(`AgentFactory.create()` 集中校验 — 本 Story 复用到 `YamlWatcher.publish()` 拒绝不合法 cfg)
- §1 #11 默认实现位置(lingshu-core 内置 + 按需加载)
- §1 #9 Plugin 发现(Spring Boot Auto-Config,不选 Java SPI)
- §2 13 项依赖锁定(R-13 mitigation (d) dep-tree 自查,**0 新增** — `AtomicReference` / `WatchService` / `Files.getLastModifiedTime` 全部 JDK 8 内置)
- §3 NFR baseline:热更感知 latency P99 ≤ 5s(YamlWatcher poll 5s interval 已固化),不影响 turn P50 ≤ 30s / P99 ≤ 60s
- §4 错误码约定:复用 `LINGS-C02/C03/Z01`,**0 新增**
- §5 7 层金字塔:L1 Unit(Registry / Watcher / freeze) + L2 Slice(SlotResolver 不感知 cfg swap) + L5 E2E(AC-06 黑盒主路径)
- §10 R-03 YAML 热更数据竞争缓解:本 Story 必须落实 (a) AtomicReference 单写多读 + (b) `cfg.snapshot()` 旧 turn 不可变快照 + (c) `validateOrThrow` 拒绝破坏性 cfg

**对应 AC**: **AC-06**(YAML 热更无中断 §0.4 L121-125)— Agent 跑 turn T1 时外部修改 `application.yml` 的 `agent.sandbox.command-whitelist`(新增 `git`),T2 开始时新 config 生效(`git` 允许),T1 不被中断仍用旧 whitelist(冻结语义);§14.8 `AgentConfigRegistry` AtomicReference swap + 旧 turn 冻结(`cfg.snapshot()` 拷贝)。

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — AgentConfigRegistry AtomicReference swap 单一权威 (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我**不希望** Agent 业务代码里出现"配置被偷换"的诡异行为 —— 我期望当外部 `YamlWatcher` 检测到 `application.yml` mtime 变更,新解析的 `AgentConfig` 通过 `registry.publish(next)` 单写多读地替换当前 config,**所有** turn 入口(`DefaultAgent.run`)统一通过 `registry.current()` 拿最新 config。这样新 turn 立即看到变更、在飞 turn 不丢内存不变量。

**Why this priority**: 这是 **AC-06 的核心机制**。当前(Story #006 已合)所有 turn 共享**全局** single `AgentConfig` 引用 —— 这种"启动期 load 一次"模式让"热更"几乎不可能(`@ConfigurationProperties` 虽是 immutable 但运行时只能整体重启);业务方真正需要"不重启换 cfg"的能力(灰度发布 / 紧急 throttle / sandbox whitelist 加白名单 / cost budget 调整)。**缺它** AC-06 完全 fail,LingShu 仅能"启动期固定 config"。

**Independent Test**: 在 `lingshu-core/src/test/.../hotreload/AgentConfigRegistryTest`(纯 L1 Unit,不启 Spring)写 1 个核心用例 —— `registry.publish(cfg2)` 后 `registry.current()` 立即返 `cfg2`(**无内存屏障**);并发跑 100 线程同时 `publish` + `current`(只接受单胜者语义),所有 `current()` 永远不会看到半构造对象。

**Acceptance Scenarios**:

1. **Given** `AgentConfigRegistry` 持 `AtomicReference<AgentConfig>`,初始 `publish(cfg1)`
   **When** 调 `current()` 100 次
   **Then** 全部返回 `cfg1`(不可变)
   **And** 内部状态在多线程下**无丢失更新**(AtomicReference 内存语义保证)

2. **Given** `publish(cfg1)` 之后
   **When** 调 `publish(cfg2)`
   **Then** 后续 `current()` 立即返回 `cfg2`(Lock-free,AtomicReference.set 内存屏障)
   **And** 在飞 turn 持有旧 `cfg1` 引用**不**被偷换 —— 引用语义保证 each turn 拿到的 cfg 是**不可变快照**

3. **Given** 100 线程并发跑 `publish(cfg1..cfgN)`
   **When** 任意线程调 `current()`
   **Then** 永远拿到**已完整 publish 的 config**(无半构造对象读到)—— AtomicReference.set + get 单写多读原子性保证

4. **Given** 业务代码有 2 条路径:① `DefaultAgent.run(input)` 入口(`configRef.get()`)② `YamlWatcher.poll()`(`registry.publish(next)`)
   **When** 热更触发(`publish` 替换)
   **Then** 路径 ① 当前 turn 拿**它启动时**的 cfg 引用(冻结),路径 ② 后续新 turn 拿最新 —— 互不干扰

---

### User Story 2 — YamlWatcher 文件 mtime 轮询触发 publish (Priority: P1)

作为 **Charlie(框架贡献者)**,我**期望** 一个 Bean 后置任务定期对比 `application.yml` 的 mtime,如果发现变更就重新 load YAML → 解析为 `AgentConfig` → 跑 `AgentFactory.validateOrThrow(next)` → 通过则 `registry.publish(next)`,失败则日志 ERROR 保留旧 cfg。这样运维改 yml 后,**下一个 turn 自动看到变更**(无需重启)。

**Why this priority**: 这是 **AC-06 的触发链**。没 watcher = `registry` 永远是 startup 那一刻的 cfg,业务方热更改 yml 啥事都不会发生;过度复杂(采用 JDK 9+ `WatchService` + NIO file events)又破坏 §0 L39 JDK 8 硬约束。**扳机条件**:JDK 8 内置 `Files.getLastModifiedTime` + `ScheduledExecutorService` 5s poll(对齐 §14.8 design)是完全足够的,生产可用。

**Independent Test**: 在 `lingshu-core/src/test/.../hotreload/YamlWatcherTest`(L1 Unit + 临时文件)加 3 个用例 —— ① 首次 poll 检测到 mtime change 触发 publish;② mtime 未变 skip publish(节能);③ 失败 yml 解析抛异常**不**替换(保留旧 cfg,日志 ERROR)。

**Acceptance Scenarios**:

1. **Given** watcher 启动 + `application.yml` mtime = T0
   **When** 每 5s 跑 1 次 `poll()`
   **Then** mtime 未变 → skip publish(无副作用,无内存拷贝)
   **And** 内部 `lastSeen` 状态保持 T0

2. **Given** watcher `lastSeen = T0`,yml 文件被外部 touch 后 mtime = T1(T1 > T0)
   **When** 下次 `poll()` 跑
   **Then** 检测到变更 → load YAML → 解析为 `AgentConfig next` → 调 `validateOrThrow(next)`
   **And** 校验通过则 `registry.publish(next)` + 更新 `lastSeen = T1`
   **And** 校验失败则日志 ERROR `"validation failed, keeping old config"`,**不**触发 publish

3. **Given** yml 文件存在但内容破坏(SnakeYAML 抛错)
   **When** `poll()` 跑
   **Then** catch `YAMLException` 防止 watcher 线程终止
   **And** 日志 ERROR 含异常信息(yml 路径 + 异常 cause)
   **And** `lastSeen`**不**更新,5s 后下次 poll 重试

4. **Given** watcher 在多节点 / 多实例部署下只有**一份**会 publish(假设 NIO file event 高并发容易双写)
   **When** 两个 watcher 进程同时跑
   **Then** `AtomicReference` 保证后写者胜,但**不可预期**哪个胜出
   **And** 这意味着多实例热更**不**保证一致性 —— 单实例部署假设(yml 在容器外挂卷,容器单实例)

---

### User Story 3 — DefaultAgent 旧 turn 冻结语义 (Priority: P1)

作为 **Alice**,我**期望** 我已经在跑的 turn T1 **不**因为 yml 变了就被中断或切到新 config —— T1 跑完用的就是它启动时拿的 config;sandbox 跑过的 tool result / session 写过的 checkpoint / cost 算过的 budget 都基于旧 cfg。等到 T2 开始时,新 turn 拿新 cfg 才是合理的。这样不会出现 "T1 turn 跑到一半,新 LLM provider 生效,后半段工具调用行为完全变了" 的诡异。

**Why this priority**: 这是 **AC-06 的冻结语义**。`DefaultAgent` 单 turn 单 Agent per §4.1 不变项(`config` 是 final 字段);turn 入口一次性 `cfg = registry.current()` 拷贝,**后续整个 turn 不用 `registry`**,而是用本地 `final AgentConfig cfg` 引用(不可变 snapshot)。即使 `registry` 被 publish 替换,本 turn 的 `cfg` 仍指向旧对象 —— Java 引用语义自然冻结。

**Independent Test**: 在 `lingshu-core/src/test/.../hotreload/InFlightFreezeTest`(L1 + Mockito)加 1 个核心用例 —— 模拟 `DefaultAgent.run(input)` 启动时 `cfg = registry.current()` 拿 cfg1;turn 中途 `registry.publish(cfg2)`;turn 完成后断言 cfg1 仍指向原始对象(引用 != 配置对象 identity)。

**Acceptance Scenarios**:

1. **Given** turn T1 启动时 `cfg = registry.current()`(此时 = cfg1)
   **When** T1 跑完整个 ReAct 循环(无 cancel)
   **Then** 全程 cfg 引用未变(Java final 字段),T1 写的 session / tool result / cost 都基于 cfg1
   **And** 即使中途 `registry.publish(cfg2)`,T1 内部 `cfg` 仍是 cfg1(引用语义)

2. **Given** T1 跑完(冻结语义成立)
   **When** 新 turn T2 启动
   **Then** `cfg2 = registry.current()`(此时 = cfg2)
   **And** T2 全程用 cfg2 跑(假设 cfg1 ≠ cfg2)
   **And** T1 与 T2 互不干扰 —— T1 用旧 config 完整保留, T2 用新 config 立即生效

3. **Given** turn T1 取消(cancellation token fire)
   **When** registry 同时 publish(cfg2)
   **Then** T1 在 cancellation 检查点正常退出,**不**基于 cfg2 重启任何子任务
   **And** 新 turn T2 启动拿 cfg2(若 cfg2 与 cfg1 不一致,T2 立即感知新 config)

4. **Given** turn T1 已跑完 1 步 ReAct(step 0 = Action tool call)
   **When** registry publish(cfg2, cfg2.llm.provider="openai" 改了 config)
   **Then** T1 step 1 仍然基于 cfg1.llm.provider 调 LLM
   **And** T2 step 0 用 cfg2.llm.provider 调 openai
   **And** 不会出现 "T1 之前调 anthropic,中途改 cfg2,后续 step 突然变 openai,行为诡异"

---

### User Story 4 — AgentFactory.validateOrThrow 复用校验 + publish 时间点失败回退 (Priority: P2)

作为 **Charlie**,我**期望** 热更新触发时新 cfg 必须先通过**启动期同等严格度**的校验:`AgentFactory.validateOrThrow(next)` 复跑(L7110 行引用为复用)。否则一个错误 yml(缺必填字段 / types 不匹配 / 27+ 字段语义失效)会让 agent 进入 "broken state" —— 旧 turn 跑正常的 config,新 turn 跑会一直抛 NPE。

**Why this priority**: 这是 **AC-06 的可可靠性 / R-03 缓解的可观测性**。校验是 R-03 (a) 缓解的关键:不让错误 cfg 污染 registry;若 yml 写错就 rollback(保留旧 cfg + ERROR 日志) 远比"热更成功但 agent 半残"安全。**P2 因为** 默认安全回退(直接 reject)是"自然兜底" —— 即便没实现 validateOrThrow,yaml parse 阶段也会失败(抛 YAMLException),不会污染 registry。本 P2 让此过程更显式 + 信息更完整(带字段路径)。

**Independent Test**: 在 `lingshu-core/src/test/.../hotreload/ValidateOrThrowTest`(L1 Unit)加 1 个用例 —— yml 漏 `agent.llm.provider` → 模拟 watcher publish 时 `validateOrThrow` 抛 `LingsConfigException("C02")`,断言 `registry.current()` **没**被替换。

**Acceptance Scenarios**:

1. **Given** yml 缺 `agent.llm.provider` 字段
   **When** watcher poll detect mtime change → load → validate
   **Then** `validateOrThrow` 抛 `LingsConfigException("C02", "agent.llm.provider is required")`
   **And** watcher catch + 日志 ERROR `"config validation failed, keeping previous: C02 agent.llm.provider is required"`
   **And** `registry.current()` **保持**旧 cfg(不变)
   **And** `lastSeen`**不**更新(watcher 下次 5s 后重试)

2. **Given** yml 缺 `agent.react.max-steps`(基本字段)
   **When** watcher publish 时 validate
   **Then** 同样抛 `LingsConfigException("C02")` + 字段路径
   **And** 与 Story #001 启动期校验**完全一致**的 message 格式 — 复用同一 `validate()` 静态方法

3. **Given** yml 解析成功但语义破坏(例如 `agent.llm.provider="invalid-llm-name"` 在 Router 中找不到)
   **When** watcher validate
   **Then** 抛 `LingsSlotException("S01", "llm provider 'invalid-llm-name' not found in Router")`
   **And** 同样保留旧 cfg(rollback)

4. **Given** yml 完整 + 合法
   **When** watcher publish
   **Then** validate 通过 → registry.publish(next) → lastSeen 更新
   **And** 后续新 turn 立即看到变更

---

### User Story 5 — Listener 注册 + 关键事件通知(可选增强, P3)

作为 **Charlie**,我**期望** 业务插件(plugin)可以订阅 "config changed" 事件 —— 比如更新本地 cache / 关闭旧连接 / 重新初始化 client。这样 config 替换不只是 "atomic swap",还**显式触发** plugin 端的 reconfiguration 钩子。

**Why this priority**: 这是 **ac-06 的可扩展性**。不属于 AC-06 核心验证但属于 §14.8 Listener/notify 设计意图。**P3** 是因为 Story #007 AC-06 黑盒验证**不**依赖 listener 机制,有 1-2 个 stub listener 即可(YamlWatcher 自己 + ConfigCachedProvider)—— 业务方注册监听能力留待 Story #009 / #016 plugin 实施期补。

**Independent Test**: 在 `lingshu-core/src/test/.../hotreload/ConfigChangeListenerTest`(L1 Unit)加 1 个用例 —— mock listener 注册 + 调 `registry.publish(cfg2)` → 收到 1 次 `onConfigChange(previous=cfg1, next=cfg2)`;listener 抛异常不影响 publish 主流程(隔离)。

**Acceptance Scenarios**:

1. **Given** `registry.addListener(myListener)`
   **When** `publish(next)` 被调
   **Then** `myListener.onConfigChange(prev, next)` 在 publish 主流程**之后**同步触发
   **And** listener 抛 RuntimeException → catch + 日志 ERROR `"listener X failed, continuing"`,**不**回滚 publish
   **And** 多 listener 顺序调用(注册顺序),任一失败不影响后续 listener

2. **Given** registry 有 N 个 listener
   **When** `addListener` 新 listener
   **Then** 新 listener 在后续 `publish` 时被调
   **And** 已有 listener 仍按注册顺序触发

3. **Given** listener 通过插件(plugin)注册
   **When** Spring Boot 启动
   **Then** listener 在 `AgentConfigRegistry` Bean 初始化完成后由 plugin AutoConfiguration 自动注册
   **And** 实施期 plugin 写 `McpServerConnectionStatusListener` 类即可(Slot 9 A2A / Slot 2 Mcp plugin 用得到)

---

### Edge Cases

- **yml 文件被删 / 移走**:watcher `Files.exists` 检查抛 FileNotFoundException → catch + 日志 WARN "yml file missing, keeping previous" + 不更新 lastSeen(等文件回来)
- **yml 内容为空文件**:`yaml.loadAs("", ...)` 抛 YAMLException → catch 同"yml 文件被破坏"路径
- **YamlWatcher 启动期 vs 启动前 register**:watcher Bean @PostConstruct 启动 ScheduledExecutorService,**先**注册再 publish 初始 cfg(避免运行时 "registry 空白")
- **跨线程 publish**:`AtomicReference.set` 跨线程 + 多读者(get)锁-free,但 listener 回调**必须**同步执行(避免 listener 内部 spawn thread 又跨 AtomicReference 又异步 —— 复杂度爆炸)
- **极端速率变化**:yml 被高频率 touch(每秒 10 次)— watcher 5s 间隔 → 中间 9 次 touch 都"看不见"(被合并成 1 次 publish),**不**需要 debounce 复杂设计
- **NIO WatchService vs Files.getLastModifiedTime**:**不**采用 WatchService(JDK 7 NIO API,JDK 8 OK 但需 daemon thread + 平台兼容性复杂),用 `getLastModifiedTime` + 5s poll 即可 — 与 §14.8 design 一致
- **snapshots 在跨线程 turn 池中共享**:turn 池(Story #013 / §14.6 会涉及)执行 `registry.current()` 时,turn A 拿 cfg + turn B 拿 cfg 是**独立 snapshot**(不是同一引用),即使 registry 中途 publish 也**不**影响 turn 内部 — Java 局部变量引用语义自然 freeze
- **cfg.snapshot() vs 引用语义**:`AgentConfig` 是 `@Value` 不可变,**不**需要 `.snapshot()`(Lombok @Value 默认 final + 不可变 fields);"冻结语义"借助 Java 引用语义 + final 字段已经天然成立,**不**额外加 snapshot 方法(否则即冗余)

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**:新增 `AgentConfigRegistry` 类(包 `ai.lingshu.core.reload`),内部持 `private final AtomicReference<AgentConfig> current = new AtomicReference<>();`,提供:
  - `public AgentConfig current()` → `return ref.get();`(纯读)
  - `public void publish(AgentConfig next)` → `ref.set(next);`(纯写,不触发任何 side effect)
  - `public void addListener(ConfigChangeListener l)` + `public boolean removeListener(ConfigChangeListener l)`(监听机制)
  - 构造器:**无**构造器参数,初始 `ref.set(emptyConfig)` 或 NPE(由注入器保证)
  - 类 Javadoc 明确 "**单写多读**" 语义 + "**lock-free**" 性质
- **FR-002**:新增 `ConfigChangeListener` 接口(同上包):
  - `public interface ConfigChangeListener { void onConfigChange(AgentConfig previous, AgentConfig next); }`
  - Javadoc 明确 "**异常隔离**" —— listener 抛 RuntimeException 不会影响 publish 主流程,但会触发 `LingsInternalException` ignore path
- **FR-003**:新增 `YamlWatcher` 类(包 `ai.lingshu.core.reload`),内部持:
  - `private final Path ymlPath;`(注入或 NPE)
  - `private final AgentConfigRegistry registry;`
  - `private final Yaml yaml = new Yaml();`(SnakeYAML 与 §1 #4 锁定 deps 对齐)
  - `private final AgentFactory factory;`(复用 `validateOrThrow`)
  - `private volatile long lastSeen;` —— **必须** volatile(非 AtomicLong,JVM 内存屏障足够)
  - `private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "yaml-watcher"); t.setDaemon(true); return t; });`
  - 方法:`public synchronized void start()` —— @PostConstruct 启动,首次 `lastSeen = ymlPath.getLastModifiedTime().toMillis()`,以后每 5s `poll()`
  - 方法:`private synchronized void poll()` —— 比较 mtime,变则 load + validate + publish + 更新 lastSeen + 触发 listeners
  - 类 Javadoc 明确 "**单实例假设**"(多实例部署**不**保证一致性)
- **FR-004**:改 `DefaultAgent`(若已存在;若只有 stub,新建 `DefaultAgent implements Agent`)in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java`:
  - 字段:`private final AgentConfig cfg;`(从 `AgentFactory.create(cfg, registry)` 传入,**整个 turn 不变**)
  - 构造器签名变更:`public DefaultAgent(AgentConfig cfg, AgentConfigRegistry registry, ...)`(注入 registry,但 turn 内只调用 `registry.current()` 一次然后固化为 `cfg`)
  - `run(String input)` 入口:`AgentConfig cfg = registry.current();`(冻结点)
  - 既有 final 字段不变,ReAct 循环**不**改
- **FR-005**:改 `AgentFactory.create()` 方法签名 in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java`:
  - 新增重载:`public Agent create(AgentConfig cfg, AgentConfigRegistry registry)` —— 把 registry 注入 Agent 构造器
  - 既有重载 `create(AgentConfig cfg)` 内部 default registry = 启动期 publish 单例(避免破坏 Story #001—#006)
  - 启动期保持 `registry.publish(validated)` 单次
- **FR-006**:`AgentFactory.validateOrThrow(AgentConfig cfg)` 已存在(§7.1.2 T1 / Story #001 引用),**不**新增校验逻辑 —— YamlWatcher 在 publish 时复用同一个方法,**保证** "启动期通过的配置" = "热更接受的配置"
- **FR-007**:`reload` 包 P3 增强:支持 `YamlWatcher.disabled` 标志(yml `agent.reload.disabled=true` 时 watcher 不启动,用于生产关掉热更);默认值 false(开发态推荐开)
- **FR-008**:错误处理(SOP R-13 加测项):
  - yml 文件 missing → WARN 日志 + skip poll(等文件回来)
  - yml parse fail (SnakeYAML exception) → ERROR 日志 + skip publish + 不更新 lastSeen
  - validate fail (`LingsConfigException`) → ERROR 日志 + skip publish + 不更新 lastSeen
  - listener 抛 RuntimeException → ERROR 日志(包含 listener class)+ continue publish
  - 任意异常**不**让 watcher 线程终止(catch all `Throwable` + log + continue)
- **FR-009**:`poll()` 内部轮询:5s 间隔由 `scheduler.scheduleWithFixedDelay(this::poll, 5, 5, TimeUnit.SECONDS)` 实现;首次启动立即跑一次(`schedule(...)` 立即 + `scheduleWithFixedDelay` 5s 后开始)
- **FR-010**:**关键不变项** —— `AgentConfig` Lombok `@Value` 不可变;yml → `AgentConfigProps.toAgentConfig()`(Story #001 已固化)复用;`@ConditionalOnMissingBean`/`SlotResolver` 等 9 Slot 接口**不**改;`LinearTurnEngine` ReAct 主循环**不**改;`CancellationToken` / 三层贯通**不**改(Story #005 已固化)

### Non-Functional Requirements

- **NFR-001**:AC-06 黑盒验证通过 — `YamlHotReloadIT#inFlightTurn_publishStaysFrozen_nextTurnSeesNewConfig` 测试通过,断言冻结 + 立即生效
- **NFR-002**:JDK 8 兼容 — `AtomicReference` / `Files.getLastModifiedTime` / `ScheduledExecutorService` / `Path` 全部 JDK 8 内置,**不**引 WatchService(JDK 7 NIO 复杂)也不引 Guava
- **NFR-003**:**0 新增** Maven 依赖 — AtomicReference / SnakYAML(已在 §2 deps 锁定) / 文件 API 全部 JDK 8 + 既有依赖(R-13 mitigation (d) dep-tree 自查)
- **NFR-004**:**0 新增** ErrorCode(本 Story 复用 `LINGS-C02 / C03 / Z01`)
- **NFR-005**:`AtomicReference` 是单写多读 lock-free(JDK 内置 CAS);性能开销:一次 publish = 1 次 volatile write + N 个 listener 同步调用;单次 publish latency 应 ≤ 1ms(N 次 listener × 平均回调时间)
- **NFR-006**:`poll()` 周期 5s(对齐 §14.8 design);生产环境若 yml 在网络文件系统(NFS / EFS)需本地降频至 30s(降低 mtime 元数据抖动),可在 `YamlWatcher` 配置 `pollIntervalSeconds`(默认 5s)
- **NFR-007**:`DefaultAgent.run` 入口 `cfg = registry.current()` 一次后,**整个 turn 不**再访问 registry —— 引用语义自然冻结,**0 overhead**
- **NFR-008**:R-03 缓解 3 件套全部落地 — (a) AtomicReference 单写多读 + (b) Java 引用语义 + final 字段 = 旧 turn 自然冻结 + (c) `validateOrThrow` 拒绝破坏性 cfg
- **NFR-009**:**0 回归** —— Story #001—#006 全部已有测试用例仍 green(`AgentFactory.create(cfg)` 旧签名保留 registry=全局默认)

### Key Entities

- **`AgentConfigRegistry`**(new):@Component 持 `AtomicReference<AgentConfig>` + listener 列表,provide publish/current/addListener/removeListener 4 方法 + 内部 `doPublish` 调 listener;构造器 @Autowired 注入 `AgentConfig` + 自动 publish(初始 cfg = Spring 启动期 load 的 cfg)
- **`ConfigChangeListener`**(new):interface,单方法 `onConfigChange(previous, next)` + Javadoc 异常隔离约定
- **`YamlWatcher`**(new):@Component 持 `ScheduledExecutorService` + `Path` + `AgentConfigRegistry` + `AgentFactory`(validate 复用);@PostConstruct 启动;`@PreDestroy` shutdown scheduler
- **`DefaultAgent`**(modify):构造函数新增 `AgentConfigRegistry` 注入;`run(input)` 入口拿 cfg 后冻结(final 字段);既有 ReAct 循环不变
- **`AgentFactory.create(cfg)`**(modify):重载新增 `create(AgentConfig cfg, AgentConfigRegistry registry)`,既有重载内部 default registry

**Total**:3 new + 2 modified = **5 文件改动**(CLAUDE.md §11 #4 "≤ 5 核心文件改动" 软上限正好打平)

**不超上限原因**:
1. Story #007 by nature 是"修改 + 新增 3 件套",没有多余抽象
2. **不**触 9 Slot 接口 / `LinearTurnEngine` / `CancellationToken` / `MemorySource` / `TenantContext` 等既有 components
3. `SlotResolver` / 9 Slot 路由**不**变,本 Story 仅修改 `DefaultAgent` 1 个 runtime class

### Success Criteria *(mandatory)*

- **SC-001**:`mvn -pl lingshu-core test` 全绿(L1 Unit + L2 Slice + L5 E2E 全部通过,预计 ≥ 14 测试用例)
- **SC-002**:`YamlHotReloadIT#inFlightTurn_publishStaysFrozen_nextTurnSeesNewConfig`(AC-06 黑盒核心断言)— T1 启动拿 cfg1,中途 publish(cfg2),T1 完成全程 cfg 引用未变;T2 启动拿 cfg2 立即生效新 config(若 cfg2.sandbox.commandWhitelist 包含 git, T2 跑 `["git", "status"]` 成功)
- **SC-003**:`AgentConfigRegistryTest#publishCurrentAtomic_swap_immediateReads` 测试通过 — publish 后 100 线程并发 current() 全拿到**新** cfg(锁-free 立即可见)
- **SC-004**:`YamlWatcherTest#pollMtimeChange_triggersPublishAfterValidation` 测试通过 — mtime 变 + validate 通过 → publish + lastSeen 更新
- **SC-005**:`YamlWatcherTest#pollInvalidYaml_keepsOldConfig` 测试通过 — yml parse 失败 → ERROR 日志 + registry.current() 仍是旧 cfg(rollback semantics)
- **SC-006**:`InFlightFreezeTest#finalFieldDefiesMidTurnConfigSwap` 测试通过 — T1 启动 cfg=cfg1,mock `registry.publish(cfg2)`,assert T1.cfg 引用未变
- **SC-007**:`mvn dependency:tree` 输出与 Story #006 baseline 一致 — 0 行新增依赖(满足 §2 R-13 mitigation (d))
- **SC-008**:PR body 末尾有 `### R-13 dependency:tree 自查` 节,贴关键子树(对比 Story #006 baseline)
- **SC-009**:无回归 —— Story #001—#006 全部 19 + 22 = 41 测试用例(其中 Story #006 22 个,含新增 +1 E2E)仍 green(US3 S1 兼容路径保留)
- **SC-010**:`reload` 包全部代码 + Javadoc 通过 `mvn -pl lingshu-core compile` 无 warning(JDK 8 + Lombok 兼容)
