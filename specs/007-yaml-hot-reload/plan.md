# Implementation Plan: Story #007 yaml-hot-reload

**Branch**: `story-007-yaml-hot-reload` | **Date**: 2026-09-21 | **Spec**: [`spec.md`](./spec.md)

**Input**: Feature specification from `/Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu/specs/007-yaml-hot-reload/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Implement `AgentConfigRegistry`(AtomicReference 单写多读)+ `ConfigChangeListener` 接口 + `YamlWatcher`(5s mtime poll)+ `DefaultAgent` 入口冻结 + `AgentFactory.create()` 重载,**不重启 JVM** 让 yml 变更生效。核心交付:

- 1 个新包 `ai.lingshu.core.reload` + 3 个新文件(`AgentConfigRegistry` + `ConfigChangeListener` + `YamlWatcher`)
- 2 个 modified 文件(`DefaultAgent.run` 入口冻结 + `AgentFactory.create` 重载)
- AC-06 黑盒:T1 跑 sandbox `ls`(旧 whitelist 生效)+ 中途 touch yml 加 `git` + T2 跑 `git status` 立即生效 + T1 **不**被中断

对应 dsh §14.8 N8 L6780-6814 + §0.4 AC-06 L121-125 + §17 R-03 缓解 3 件套(AtomicReference + 引用语义 freeze + `validateOrThrow` rollback)。

## Technical Context

**Language/Version**: Java 1.8(项目真理锁定 CLAUDE.md §2 / dsh §10.1)
**Primary Dependencies**: Spring Boot 3.2.5 / Lombok 1.18.30 / SnakeYAML 2.x(13 项锁定,**0 新增**)
**Storage**: `AgentConfig` 内存对象(Lombok `@Value` 不可变,registry AtomicReference 持有 latest);`AgentConfigRegistry` 单例 Spring Bean(`@Component`);yml 文件 mtime 通过 OS 文件系统读取
**Testing**: JUnit 5.10.x + AssertJ 3.24.x + Mockito 5.x + Awaitility 4.2.x(constitution §5 7 层金字塔,本 Story L1 + L5 E2E)
**Target Platform**: Linux / macOS / Windows(JVM 8/11/17/21 LTS)
**Project Type**: Java 库 + Spring Boot 多模块
**Performance Goals**:
- `registry.publish(next)` 1 次 `AtomicReference.set` + N listener 同步回调,NFR-005 期望 ≤ 1ms
- `registry.current()` lock-free volatile read,纳秒级
- `YamlWatcher.poll()` 5s 间隔,NFR-006 单 poll latency < 50ms(典型 yml < 100KB)
- 不影响 NFR baseline(turn P50 ≤ 30s / P99 ≤ 60s)
**Constraints**:
- JDK 8 only — 不用 `record` / `sealed` / `var` / `List.of` / `WatchService` 平台兼容复杂(CLAUDE.md §3)
- 13 项依赖锁定,**0 新增** — `AtomicReference` / `Files.getLastModifiedTime` / `ScheduledExecutorService` / `CopyOnWriteArrayList` / `SnakeYAML`(已在 §2 deps 锁定)
- 0 新增 ErrorCode(复用 `LINGS-C02` / `C03` / `Z01`)
- Story 边界 ≤ 5 核心文件改动软上限正好打平(CLAUDE.md §11 #4)
**Scale/Scope**: 单实例部署假设(多实例双 watcher 竞争 publish 后写者胜,不保证一致);N listeners ≤ 100;N tenants / N turns 不变(Story #006 已固化)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 章节 | 约束 | 本 Story 是否符合 | 备注 |
|---|---|---|---|
| §1 #1 JDK 8 兼容 | 不用 record/sealed/var/List.of/WatchService | ✅ | `AtomicReference` / `Files.getLastModifiedTime` / `ScheduledExecutorService` JDK 8 内置 |
| §1 #5 Skill 与 Tool 边界 | Skill 与 Tool 共用接口 | N/A | 本 Story 不涉及 Skill/Tool |
| §1 #6 子 Agent 注册 | SubAgentType 枚举 + 启动期校验 | N/A | 本 Story 不涉及 SubAgent |
| §1 #9 Plugin 发现 | Spring Boot SPI(**不**选 Java SPI) | ✅ | `ConfigChangeListener` 用 `@Component implements ConfigChangeListener` 模式(对齐 §5.4 多 Provider 模板) |
| §1 #11 默认实现位置 | lingshu-core 内置,按需加载 | ✅ | `reload` 包内置 core |
| §1 #12 启动时校验 | 集中 fail-fast | ✅ | `YamlWatcher` publish 时复用 `AgentFactory.validateOrThrow`(同 Story #001 启动期校验) |
| §2 13 项依赖锁定 | 0 新增 | ✅ | AtomicReference / Files / SnakeYAML 全部既有;**不**引 `io.methvin.watcher` 等第三方(R-13 mitigation d) |
| §3 NFR baseline | turn P50 ≤ 30s / P99 ≤ 60s | ✅ | publish + listener 同步 + lock-free volatile read,纳秒级 overhead |
| §4 错误码约定 | `LINGS-<域><编号>` | ✅ | 复用 `LINGS-C02 / C03 / Z01`,**0 新增** |
| §5 7 层金字塔 | L1 + L5 E2E | ✅ | 计划 L1 Unit × 13 + L5 E2E × 1 = 14 测试用例 |
| §10 R-03 YAML 热更数据竞争 | (a) AtomicReference / (b) cfg snapshot 旧 turn 不可变 / (c) validateOrThrow 拒绝破坏性 | ✅ | FR-001 AtomicReference + FR-004 入口 freeze + FR-003 validateOrThrow 复用,3 件套全部落地 |

**GATE 结果**:全部 ✅,**通过**。

---

## Project Structure

### Documentation (this feature)

```text
specs/007-yaml-hot-reload/
├── plan.md              # This file
├── research.md          # Phase 0 output (8 design decisions D-01—D-08)
├── data-model.md        # Phase 1 output (4 entities + state + concurrency)
├── quickstart.md        # Phase 1 output (7 validation scenarios + AC-06 E2E)
├── contracts/           # Phase 1 output
│   └── agent-config-registry.md # AgentConfigRegistry + ConfigChangeListener + YamlWatcher contract
├── tasks.md             # Phase 2 output (/speckit-tasks command)
├── spec.md              # Story #007 spec
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
lingshu-core/src/main/java/ai/lingshu/core/
├── reload/                                       # 🆕 Story #007 NEW PACKAGE
│   ├── AgentConfigRegistry.java                 # 🆕 @Component, AtomicReference<AgentConfig> 单写多读
│   ├── ConfigChangeListener.java                # 🆕 SPI interface, 单方法 onConfigChange(prev, next)
│   └── YamlWatcher.java                         # 🆕 @Component, Files.getLastModifiedTime 5s poll
└── impl/
    └── runtime/
        ├── AgentFactory.java                    # MODIFY: create(cfg, registry) 重载 + loadYamlAndValidate(path)
        └── DefaultAgent.java                    # MODIFY: 构造器注入 registry + run() 入口 effectiveCfg = registry.current() 冻结
```

### Test Code

```text
lingshu-core/src/test/java/ai/lingshu/core/
├── reload/                                       # 🆕 NEW PACKAGE
│   ├── AgentConfigRegistryTest.java             # 🆕 L1 Unit × 5 (US1 + FR-001)
│   ├── ConfigChangeListenerTest.java            # 🆕 L1 Unit × 2 (US5 + FR-002)
│   └── YamlWatcherTest.java                     # 🆕 L1 Unit × 5 (US2 + FR-003)
└── impl/
    └── runtime/
        ├── InFlightFreezeTest.java              # 🆕 L1 + Mockito × 1 (US3 + FR-004 + AC-06 freeze)
        └── YamlHotReloadIT.java                 # 🆕 L5 E2E × 1 (AC-06 black-box)
```

**Total**:**3 new core + 2 modified core = 5 文件改动**(CLAUDE.md §11 #4 "≤ 5 核心文件改动" 软上限正好打平);**5 new test files = 14 测试用例**

---

## File-Level Changes

### New Files (3)

| File | Purpose | Lines |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/reload/AgentConfigRegistry.java` | `@Component`,`AtomicReference<AgentConfig>` + `CopyOnWriteArrayList<ConfigChangeListener>`;`current()` / `publish(next)` / `addListener` / `removeListener` / `publishInitial(initial)`;listener 异常隔离(`catch Throwable`) | ~100 |
| `lingshu-core/src/main/java/ai/lingshu/core/reload/ConfigChangeListener.java` | SPI interface,单方法 `void onConfigChange(AgentConfig previous, AgentConfig next)`;Javadoc 明确「异常隔离」+「禁止 publish 重入」+「idempotency」 | ~40 |
| `lingshu-core/src/main/java/ai/lingshu/core/reload/YamlWatcher.java` | `@Component`,`Path` + `AgentConfigRegistry` + `AgentFactory` + `Yaml`(SnakeYAML)+ daemon `ScheduledExecutorService`;`@PostConstruct start()` / `@PreDestroy stop()` / `pollSafe()` 兜底 / `poll()` 同步执行 mtime 比较 + reload + validate + publish | ~180 |

### Modified Files (2)

| File | Why | Lines Changed |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | FR-005 — 新增 `create(AgentConfig cfg, AgentConfigRegistry registry)` 重载;既有 `create(AgentConfig cfg)` `@Deprecated` 内部 default registry = `applicationContext.getBean(AgentConfigRegistry.class)`(向后兼容 Story #001—#006);新增 `loadYamlAndValidate(Path ymlPath)` 给 YamlWatcher 复用(load + toAgentConfig + validateOrThrow) | ~30(+30 -0) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java` | FR-004 — 构造器新增 `AgentConfigRegistry registry` 字段;`run(String input)` 入口新增 `AgentConfig effectiveCfg = registry.current()` 一次性 freeze;后续整个 turn 用 `effectiveCfg`(**不**再访问 `registry`)| ~15(+12 -3) |

### Test Files (5 new)

| File | Layer | Cases |
|---|---|---|
| `AgentConfigRegistryTest.java` | L1 Unit | 5 — publishCurrentAtomic_swap_immediateReads / publishCurrent_listenerInvoked / listenerThrows_publishContinues / publishNull_throwsNPE / addRemoveListener |
| `ConfigChangeListenerTest.java` | L1 Unit | 2 — listenerRegistered_receivesCallback / listenerThrowsIsolated_continues |
| `YamlWatcherTest.java` | L1 Unit | 5 — pollMtimeChange_triggersPublishAfterValidation / pollMtimeUnchanged_skipsPublish / pollInvalidYaml_keepsOldConfig / pollValidationFail_keepsOldConfig / pollMissingFile_warnsAndRetries |
| `InFlightFreezeTest.java` | L1 + Mockito | 1 — finalFieldDefiesMidTurnConfigSwap(T1 启动拿 cfg1 + 中途 publish(cfg2) + T1.cfg 引用未变) |
| `YamlHotReloadIT.java` | L5 E2E | 1 — **AC-06 黑盒主路径**(T1 sandbox `ls` + 外部 touch 加 `git` + T2 sandbox `git status` 成功 + T1 不中断) |

### Total

- **3 new core + 2 modified core = 5 文件改动**(CLAUDE.md §11 #4 ≤ 5 软上限正好打平)
- **5 new test files**
- **Core code**: ~350 net new lines(`reload` 包 320 + DefaultAgent/AgentFactory 改造 30)
- **Test code**: ~600 net new lines
- **ErrorCode 新增**:0(复用 `LINGS-C02 / C03 / Z01`)

**Story 边界检查**:5 文件 vs CLAUDE.md §11 #4 "≤ 5 核心文件改动" **正好打平**,无须 justify。

---

## Test Strategy (L1 Unit + L5 E2E)

### L1 Unit(L1-001 ~ L1-013)

**AgentConfigRegistryTest(5 用例)**

- **L1-001**:`#publishCurrentAtomic_swap_immediateReads` — FR-001(US1 S1 + S2)
  - `registry.publish(cfg1)` + `registry.current() == cfg1`
  - `registry.publish(cfg2)` + `registry.current() == cfg2`(lock-free 立即可见)
  - 100 线程并发 `current()` 全拿最新 publish 的 cfg(无丢失更新 / 无陈旧读)
- **L1-002**:`#publishCurrent_listenerInvoked` — FR-001 + US5
  - mock listener `onConfigChange(cfg1, cfg2)` 注册 + `publish(cfg2)` → listener 收到 1 次调用
- **L1-003**:`#listenerThrows_publishContinues` — FR-001 + D-05 异常隔离
  - listener 抛 RuntimeException + ok listener 注册 + `publish(cfg2)` → ok listener 仍被调(主流程不阻断)
- **L1-004**:`#publishNull_throwsNPE` — FR-001 null 校验
  - `publish(null)` 抛 NullPointerException
- **L1-005**:`#addRemoveListener` — FR-001 listener 注册
  - `addListener(L1)` + `addListener(L2)` + `publish(cfg2)` → L1 + L2 都收 callback
  - `removeListener(L1)` + `publish(cfg3)` → 仅 L2 收 callback

**ConfigChangeListenerTest(2 用例)**

- **L1-006**:`#listenerRegistered_receivesCallback` — US5 S1
  - registry.addListener(l) + publish(cfg2) → l.onConfigChange(cfg1, cfg2) 被调 1 次
- **L1-007**:`#listenerThrowsIsolated_continues` — US5 S1 + D-05
  - l1 抛 RuntimeException + l2 ok + publish(cfg2) → l2.onConfigChange 仍被调(顺序 + 隔离)

**YamlWatcherTest(5 用例)**

- **L1-008**:`#pollMtimeChange_triggersPublishAfterValidation` — US2 S2 + FR-003
  - 临时 yml + 初始 publish cfg1 + 修改 yml(append `git` + `setLastModifiedTime` 推迟 1s)+ `watcher.poll()` → `registry.current()` 包含新 `git` + `lastSeen` 更新
- **L1-009**:`#pollMtimeUnchanged_skipsPublish` — US2 S1
  - yml 未改 + `poll()` → registry 仍持 cfg1 + lastSeen 未变(无副作用)
- **L1-010**:`#pollInvalidYaml_keepsOldConfig` — US2 S3
  - yml 写 broken content(`malformed: :: invalid yaml ::`) + `poll()` → registry 仍持 cfg1 + lastSeen 未更新(rollback)
- **L1-011**:`#pollValidationFail_keepsOldConfig` — US4 S1
  - yml 缺 `agent.llm.provider` + `poll()` → 抛 `LingsConfigException("C02")` → registry 仍持 cfg1 + ERROR 日志含 "C02" + 字段路径
- **L1-012**:`#pollMissingFile_warnsAndRetries` — Edge Case
  - yml 文件删除 + `poll()` → WARN 日志 + 不更新 lastSeen + 不替换 registry(等文件回来)

**InFlightFreezeTest(1 用例,Mockito)**

- **L1-013**:`#finalFieldDefiesMidTurnConfigSwap` — US3 + FR-004 + AC-06 freeze 核心
  - 创建 registry + publish cfg1
  - 创建 DefaultAgent + 启动 `agent.run(input)`(turn T1)
  - T1 跑到 step 1 时 mock `registry.publish(cfg2)`
  - T1 跑完,断言 `agent.getEffectiveCfg() == cfg1`(`assertSame`,引用相等)
  - 启动 T2,断言 `T2.getEffectiveCfg() == cfg2`

### L5 E2E(E2E-001,AC-06 黑盒主路径)

- **E2E-001**:`YamlHotReloadIT#ac06YamlHotReload_noInterrupt_oldTurnFrozen_nextTurnSeesNewConfig` — **AC-06 black-box**(SC-002)
  - **Setup**:
    1. `@SpringBootTest` + 临时 yml(`/tmp/lingshu-test/application.yml`)
    2. Initial yml:`sandbox.command-whitelist=[ls, cat, echo]`
    3. Spring 启动 `AgentFactory` + `AgentConfigRegistry` + `YamlWatcher`
  - **Action**:
    1. 启动 T1 = `agent.runBlocking("[run 'ls /tmp']")`(`ls` 在旧 whitelist 里)
    2. Sleep 100ms 等 T1 进 ReAct step 0
    3. Modify yml:append `git` + `Files.setLastModifiedTime(d + "1s")`
    4. Sleep 6s 等 watcher poll(默认 5s + buffer)
    5. **Do NOT** cancel T1;just verify T1 still running with cfg1(debug hook:`agent.getEffectiveCfg() == cfg1`)
    6. 启动 T2 = `agent.runBlocking("[run 'git status']")`
    7. Wait T1 done:断言 T1 内部 sandbox 调用历史**不**包含 `git`(仅含 `ls`)
    8. Wait T2 done:断言 T2 内部 sandbox 调用历史**包含** `git status` 且**成功**
  - **Expected output**:
    ```
    T1 completed: tool calls = [ls /tmp], cfg ref = cfg1 (frozen)
    Watcher: reloaded config, lastSeen updated, publish(cfg2)
    T2 completed: tool calls = [git status], cfg ref = cfg2 (new)
    ```
  - **wall-clock < 15s**(5s watcher poll + 2 turn 运行 + buffer)

**Test 总数**:14 个(L1 × 13 + L5 × 1)

---

## Implementation Order (10 steps, sequential due to dependencies)

| Step | What | Depends on | ~time |
|---|---|---|---|
| **0** | 检查 `mvn -v` + JDK 17(实际跑)+ 跑 Story #006 全测试 baseline | — | 1 min |
| **1** | 创建分支 `story-007-yaml-hot-reload` from main(已通过 spec/plan workflow 切到该分支)| step 0 | 0 min |
| **2** | 写 spec.md / plan.md / tasks.md / quickstart.md / contracts / checklists(本目录)| step 1 | 5 min |
| **3** | 新建 `reload` 包 + `AgentConfigRegistry`(FR-001)+ `ConfigChangeListener`(FR-002) | — | 15 min |
| **4** | 新建 `YamlWatcher`(FR-003)+ `AgentFactory.loadYamlAndValidate(path)` 复用 `validateOrThrow` | step 3 | 20 min |
| **5** | 改 `AgentFactory.create()` 重载 `create(cfg, registry)` + `@Deprecated` 旧签名(FR-005)| step 3 | 10 min |
| **6** | 改 `DefaultAgent` 构造器 + `run()` 入口 freeze(FR-004)| step 5 | 10 min |
| **7** | 写 L1 测试 13 个(L1-001—L1-013)+ L5 E2E 1 个(E2E-001 AC-06)| step 6 | 30 min |
| **8** | 跑 `mvn test` 全绿 + `mvn dependency:tree` 自查 + 贴 PR body | step 7 | 10 min |
| **9** | commit + push + open PR + README/docs 同步 | step 8 | 10 min |

**Total**:~111 min(~2h 实施 + 20 min PR 收尾)

---

## Key Design Decisions

### D-01:`AtomicReference<AgentConfig>` 单写多读 而非 `volatile` 字段

**决策**:`registry` 内部持 `private final AtomicReference<AgentConfig> currentRef = new AtomicReference<>();`,publish = `currentRef.getAndSet(next)`,read = `currentRef.get()`。

**Rationale**:`AtomicReference` 比 `volatile AgentConfig field` 更严格(volatile 仅保证可见性,`AtomicReference.get()` 利用 `VarHandle` 内存屏障更强);提供 `getAndSet` / `compareAndSet` 等扩展操作;多线程测试简洁。

**Alternatives considered**:`volatile field`(能力不足)/ `ConcurrentHashMap<String, AgentConfig>`(杀鸡用牛刀)/ `ReadWriteLock`(单写多读不需要锁)。

### D-02:`Files.getLastModifiedTime` + 5s poll 而非 `WatchService`

**决策**:`YamlWatcher.poll()` 用 `Files.getLastModifiedTime(ymlPath).toMillis()` 对比 `lastSeen`,变则 reload;调度用 `ScheduledExecutorService.scheduleWithFixedDelay(...)`,默认 5s 间隔。

**Rationale**:**JDK 8 兼容**(`WatchService` 在 macOS polling fallback 不可靠,Linux inotify FD leak 风险);**极简**(无 daemon thread 同步状态 / 无 `WatchKey.cancel()` / 无 overflow detection);**5s delay 可接受**(yml 热更是运维操作,秒级延迟无 SLA 违反,§14.8 design verbatim 5s);**NFS / docker volume / tmpfs 跨平台稳态**。

**Alternatives considered**:`WatchService`(平台兼容性差)/ Spring `@RefreshScope` actuator `/reload`(需手工触发)/ `io.methvin.watcher`(R-13 mitigation d 禁第三方)。

### D-03:DefaultAgent.run 入口 `cfg = registry.current()` 一次冻结 而非 `configRef.get()` 包装

**决策**:`DefaultAgent` 构造器注入 `AgentConfigRegistry`;`run(input)` 入口执行 `AgentConfig cfg = registry.current();` 然后把 `cfg` 传给后续所有子调用;**整个 turn 不**再 `current()`。

**Rationale**:**Java 局部变量 + 不可变 `@Value` 引用语义**自然冻结;`@Value` 是 final fields + 不可变 List,字段读取是 plain field load,**比**每步 `volatile read` 快 10x;AC-06 验证友好(`assertSame(cfg1, T1.run(input))`)。

**Alternatives considered**:`AtomicReference<AgentConfig> field` 注入 Agent,每步 `field.get()`(volatile read + 内存屏障开销)/ 监听 publish 自动重置 turn 状态(违反 FR-004 freeze 语义)/ 构造器 snapshot 一次 `final AgentConfig cfg`(factory 调用方 break,仍走 registry 注入)。

### D-04:`AgentFactory.create` 旧签名保留 + 新增 `create(cfg, registry)` 重载

**决策**:新增 public 重载 `public Agent create(AgentConfig cfg, AgentConfigRegistry registry)`;既有 `public Agent create(AgentConfig cfg)` `@Deprecated`,内部 default registry = `applicationContext.getBean(AgentConfigRegistry.class)`(Spring 单例 Bean)。

**Rationale**:**向后兼容 Story #001—#006** — 旧 `create(cfg)` 调用点零改动,既有多租户 / cancellation / parallel dispatch 测试零回归;显式注册路径 + 默认 fallback 双覆盖(99% 业务代码用旧签名即可,少数测试 / 启动期场景用新签名)。

**Alternatives considered**:**直接改 `create` 签名**(破所有 Story #001—#006 测试)/ Agent 内部自管 addListener(违反 AC-06 freeze 语义)/ registry 静态化(违反 Spring Bean 语义,单测困难)。

### D-05:`ConfigChangeListener` 异常隔离 — publish 不 rollback

**决策**:listener 抛 RuntimeException → `catch Throwable` + ERROR 日志 + **不**回滚 publish;后续 listener 继续触发。

**Rationale**:**publish 应 idempotent 可见** — 即使 listener 全失败,`registry.current()` **必须**拿到 next(否则 freeze 语义 broken);典型 mid-platform 模式(AWS SQS / Kafka consumer commit 都是 listener 异常不影响 main flow);ERROR 日志 + listener class name + cause 让运维定位问题;与 §5.5 Provider 异常隔离约定对齐。

**Alternatives considered**:**listener 异常 abort publish 回滚 next**(强一致性但违反 AC-06 freeze)/ **listener 异步调**(复杂度爆炸 + 重 publish 死锁)/ **listener 异常 escalate 到 JVM error**(过度,publish 是日常操作)。

### D-06:`AgentConfig` 不可变 + 无需 `snapshot()` 方法

**决策**:`AgentConfig` Lombok `@Value` 默认所有字段 `final` + 不可变集合(`Collections.unmodifiableList`);**不**新增 `.snapshot()` 方法。

**Rationale**:`@Value` 已天然 immutable,Java 引用语义自然冻结(局部变量 = 不可变引用 + 内部字段 final);**额外 `.snapshot()` 是反模式** — 同一对象实例 in-memory 不可能"突变",只有 publish 替换 registry 当前引用才会发生;测试断言 `assertSame(originalCfg, turn.cfg)` 直接验证。

**Alternatives considered**:手写 `defensive copy` in constructor(Lombok `@Value` 已处理 + `AgentConfigDefaults` 已 unmodifiableList 包装)/ 加 `record`-style `with()` 方法(JDK 14+,违反 §3)/ 加 `.snapshot()` 显式方法(增加 API surface 但无功能价值)。

### D-07:`reload` 包位置 = `ai.lingshu.core.reload`(new package)

**决策**:3 个 new file 都在 `lingshu-core/src/main/java/ai/lingshu/core/reload/`。

**Rationale**:**新包 vs 复用既有包** — Story #006 创建了 `tenant/` 包,显示新 feature 都开新包,reload 与"config 热更" 单一职责强绑定;**路径规则对齐** — `lingshu-core/` 是 application code,新包与 `runtime/` / `tenant/` / `exception/` / `validation/` 同级;**避免污染 runtime 包** — reload 概念相对独立,可独立 future extension(Story #013 healthcheck / Story #010 otel 等都可能复用 listener 机制)。

**Alternatives considered**:放 `runtime/reload/` sub-package(reload 是 cross-cutting concern 不属于 runtime 直接子概念)/ 放 `config/` 包(reload 不是 config 静态值,而是 config 动态变更机制)/ 放 `impl/reload/`(impl/ 倾向于放 concrete impl,reload 是 interface + impl 混合)。

### D-08:`YamlWatcher` 5s poll 间隔对齐 §14.8 + 可配置

**决策**:默认 `pollIntervalSeconds = 5L`,作为构造函数参数(默认 5),留扩展接口 `new YamlWatcher(ymlPath, registry, factory, scheduler, 5L)`。

**Rationale**:**§14.8 verbatim** 原文 `@Scheduled(fixedDelay = 5_000)` 即 5 秒;**JDK 8 ScheduledExecutorService** 替代 Spring `@Scheduled`(减少 Spring Context dependency,watcher 在 Spring 启动早期 / 非 Spring context(单元测试)都可工作);**可配置** — 生产挂 NFS 时降频至 30s(避免 mtime 抖动 false positive),开发/dev 状态保持 5s(快速反馈);Story #007 实现**不**做 yml binding(留 Story 后续),保持 Story #007 简单。

**Alternatives considered**:Spring `@Scheduled(fixedDelayString = "${...:5000}")`(需 Spring `@EnableScheduling`,在 lingshu-core 引入 context.annotation 切换复杂)/ `ScheduledExecutorService` daemon thread(本决策)/ 每次 `Object.wait(5_000)`(不允许 — 占用线程 + 无法 schedule 取消)。

---

## Risk & Mitigation

| Risk | Probability × Impact | Mitigation |
|---|---|---|
| **R-01**:DefaultAgent.run 多次 `current()`(违反 US3 freeze 语义)| 2×3=6 | D-03 入口 freeze + 构造期 final 字段固化 + L1-013 unit test 覆盖"中途 publish 后 effectiveCfg 仍 == cfg1" + code review checklist |
| **R-02**:YamlWatcher poll loop block 多秒(load 巨 yml 慢)| 1×3=3 | (a) `poll()` 用 `Files.size` 快读 + `Files.readAllBytes` 一次性;(b) 默认 pollIntervalSeconds=5s,生产可调 30s;(c) `pollSafe` 兜底任何 `Throwable` |
| **R-03**:publish 触发 listener 死循环(听众改 cfg 又 publish)| 1×3=3 | listener Javadoc 显式禁止 publish 重入 + listener 异常隔离 + L1-003 unit test 覆盖"listener 抛异常不阻断 publish" |
| **R-04**:默认 registry 多实例(per thread/per request)破坏 AC-06 单一权威| 1×3=3 | `@Component` Spring 单例 + 测试通过 constructor 注入同实例 + NFR-009 既有测试零回归 |
| **R-05**:listener 抛异常吞掉 publish(违反 D-05 隔离)| 2×3=6 | `catch Throwable` + ERROR 日志含 listener class name + cause + D-05 code review + L1-003 单元测试 |
| **R-06**:NFS mtime 抖动 false positive(每次 chmod 都触发 publish)| 2×2=4 | (a) 默认 5s 间隔足够合并抖动;(b) `pollIntervalSeconds` 可配置(生产 30s);(c) Story #007 不做 debounce 复杂设计,留 v2 |
| **R-07**:多实例部署双 watcher 竞争 publish 后写者胜出,不可预期 | 1×3=3 | 类 Javadoc 明确「单实例假设」+ §14.8 design verbatim + E2E-001 注释提示生产用 sticky session / leader election |
| **R-08**:依赖污染(意外引 `io.methvin.watcher` 或 Guava `ListenableFuture`)| 1×3=3 | NFR-003 强制 0 新增 + R-13 mitigation (d) `dependency:tree` 自查 + code review 拒绝 `WatchService` / 第三方 |
| **R-09**:`DefaultAgent` 改造引发 Story #001—#006 回归(AC-01—AC-05 fail)| 2×3=6 | (a) D-04 `create(cfg)` 旧签名保留 + `@Deprecated` 内部 default registry;(b) Story #001—#006 全部 41 测试用例仍 green(SC-009);(c) `DefaultAgent` 构造期 `effectiveCfg` 注入,**不**改既有 ReAct 循环 |
| **R-10**:YamlWatcher @PostConstruct 启动顺序错误(watcher 先 publish 初始 vs registry 先初始化)| 1×2=2 | YamlWatcher 不在 `@PostConstruct` 内 publish,只启动 scheduler 定时 poll;registry 自身在 Spring 启动期 `publishInitial(validated)`(独立 Bean lifecycle) |

---

## Critical Invariants (do NOT change in this Story)

- **`Tool` / `ToolExecutor` / `LlmProvider` / `PermissionPolicy` / `PromptBuilder` / `Compactor` / `A2aTransport` 接口**:**不**改 —— 本 Story 不触 9 个 Slot 接口签名
- **`MemorySource` 接口**:不**改** —— `load(TurnContext)` 签名不变
- **`SessionStore` 接口**:`save(Checkpoint)` / `load(String)` 签名不**改** —— Story #014 集成时不被破坏
- **`CancellationToken` / `AgentFactory.broadcastCancel` / `DefaultAgent.buildContext`(Story #005 已固化):不**改**
- **`LinearTurnEngine` 5-step ReAct 序列顺序**不**改**,本 Story 仅在 `DefaultAgent.run()` 入口新增 freeze
- **`AgentConfig` 27+ 字段签名向后兼容**:新加 `@Value` 不需要 .snapshot()(D-06),既有字段**不**改
- **`TenantContext` / `TenantConfig` / `TenantConfigProvider`(Story #006 已固化):不**改**
- **`LINGS-*` ErrorCode 编码**:0 新增,复用 `C02` / `C03` / `Z01`
- **`AgentFactory.validateOrThrow` 27+ 字段校验逻辑**:不**改** —— YamlWatcher 在 publish 时复用同一个方法
- **Spring Boot SPI 模式**(§5.7):不**改** —— `ConfigChangeListener` 用 `@Component implements ConfigChangeListener`,**不**用 `@AutoService` Java SPI

---

## Future Scope (Out of Story #007)

- **`WatchService` JDK NIO file events 替换 mtime poll**:Story #007 选 mtime poll + 5s interval;NIO 留 v2(平台兼容性 + overflow detection 复杂度需 RFC)
- **`agent.reload.poll-interval-seconds` yml binding**:Story #007 实现**不**做 yml binding(默认 5s 硬编码),让运维通过 `YamlWatcher` 构造器参数调;Story 后续做 `@Value("${...}")` binding
- **`agent.reload.disabled` 标志位**:spec FR-007 提及但未实现 — 留 Story 后续(生产关掉热更的运维开关)
- **debounce 合并高频 touch**:Story #007 5s 间隔天然合并,留 v2 复杂度(如需要 100ms 内 10 次 touch 只 publish 1 次)
- **multi-instance leader election**:Story #007 单实例假设;k8s leader election / Redis lock 留 Story 后续
- **`ConfigChangeListener` 业务方实现**(Story #016 AuditLogger / Story #014 SessionStore 等长生命周期服务):Story #007 仅定义 SPI + 给 1-2 stub listener,业务实现留后续 Story
- **HTTP `/reload` actuator endpoint**:Spring `@RefreshScope` 风格手动 reload;Story #007 仅自动 watch,留 Story 后续
- **NIO WatchService 在 NFS 上的 fallback**:Story #007 仅 mtime,留 v2

---

## Phase 0: Research — Findings

> **详见 [`research.md`](./research.md)** — 本节摘要

| Unknown | Decision | Rationale |
|---|---|---|
| AtomicReference vs volatile vs ConcurrentHashMap vs ReadWriteLock | AtomicReference 单写多读(D-01)| 多线程可见性 + API 扩展性 + JDK 官方对 immutable 对象发布的答案 |
| WatchService vs Files.getLastModifiedTime poll | Files.getLastModifiedTime + 5s poll(D-02)| JDK 8 cross-platform + 无 daemon thread 复杂度 + §14.8 design verbatim |
| DefaultAgent run 入口 freeze vs 字段 wrapper vs 构造期 snapshot | run 入口 `registry.current()` 一次 freeze(D-03)| 引用语义自然冻结 + 0 overhead + AC-06 验证友好 |
| AgentFactory.create 直接改 vs 重载 vs 默认 registry Bean | 重载 `create(cfg, registry)` + `@Deprecated` 旧签名(D-04)| 向后兼容 Story #001—#006 零回归 |
| listener 异常处理 — abort publish vs 隔离 continue | `catch Throwable` 隔离 continue(D-05)| publish main flow 不可阻断 + ERROR 日志可观测 + 与 §5.5 Provider 异常隔离对齐 |
| AgentConfig `.snapshot()` 显式方法 vs 无需 | 无需 `.snapshot()`(D-06)| `@Value` 已天然 immutable + 引用语义自然冻结 + API 简朴 |
| reload 放 runtime/ vs config/ vs 新 reload/ | 新 `ai.lingshu.core.reload/` 包(D-07)| Cross-cutting concern 独立 + 与 Story #006 tenant/ 对齐 |
| 5s poll vs 可配置间隔 vs @Scheduled Spring | `pollIntervalSeconds` 可配置构造器参数(D-08)| §14.8 design verbatim + NFS 降频 + JDK 8 ScheduledExecutorService 不依赖 Spring Context |

**所有 NEEDS CLARIFICATION 已解决** → 进入 Phase 1。

---

## Phase 1: Design & Contracts

> **详见 [`data-model.md`](./data-model.md) + [`contracts/agent-config-registry.md`](./contracts/agent-config-registry.md) + [`quickstart.md`](./quickstart.md)** — 本节摘要

### Data Model 摘要

- **`AgentConfigRegistry`**(`@Component` Spring Bean 单例):
  - 字段:`currentRef: AtomicReference<AgentConfig>`(private final)+ `listeners: CopyOnWriteArrayList<ConfigChangeListener>`(private final)
  - 方法:`current()` / `publish(next)` / `publishInitial(initial)` / `addListener(l)` / `removeListener(l)` / `listenerCount()`(test-only)
  - 不变量:`currentRef` 在 `publishInitial(...)` 后永不为 null;publish 不 mutate next(`@Value` 不可变)
- **`ConfigChangeListener`**(interface):
  - 单方法 `void onConfigChange(AgentConfig previous, AgentConfig next)`
  - Javadoc 明确「**禁止 publish 重入**」+「idempotency」+「不要在 listener 内 spawn thread」
- **`YamlWatcher`**(`@Component` Spring Bean 单例):
  - 字段:`ymlPath: Path` / `registry: AgentConfigRegistry` / `factory: AgentFactory` / `yaml: Yaml`(SnakeYAML)/ `lastSeen: volatile long` / `pollIntervalSeconds: long` / `scheduler: ScheduledExecutorService`(daemon "yaml-watcher")/ `running: AtomicBoolean`
  - 方法:`start()`(`@PostConstruct`)/ `stop()`(`@PreDestroy`)/ `pollSafe()`(private,Throwable 兜底)/ `poll()`(package-private synchronized,单 instance 多并发互斥)
  - 不变量:**Daemon thread**(JVM 退出不阻塞)/ **No exception propagation**(pollSafe 兜底)/ **lastSeen update only on success**(validate 失败 / parse 失败**不**更新 → 下次 5s 重试)
- **`DefaultAgent`**(modified):
  - 字段新增:`registry: AgentConfigRegistry`(private final)
  - 构造器新增:`public DefaultAgent(AgentConfig cfg, AgentConfigRegistry registry, ...)`(由 `factory.create(cfg, registry)` 注入)
  - `run(String input)` 入口新增:`AgentConfig effectiveCfg = registry.current();`(冻结点)
  - 既有 ReAct 循环 + cancellation 检查 + tenant context 校验(Story #006)**不**变

### Contracts 摘要

- `AgentConfigRegistry` 契约(5 public 方法 + 1 test-only 方法 + 4 不变量 + 4 threading 约束)— 见 contracts/agent-config-registry.md §2
- `ConfigChangeListener` 契约(单方法 + 4 项 Javadoc 约束 — 异常隔离 / 禁止重入 / idempotency / 不要 spawn thread)— 见 contracts/agent-config-registry.md §3
- `YamlWatcher` 契约(2 构造器 + `start` / `stop` / `pollSafe` / `poll` + 4 不变量 + 4 threading 约束 + 5 行 error semantics 总结)— 见 contracts/agent-config-registry.md §4
- `DefaultAgent` 修改契约(构造器签名变更 + `run()` 入口 freeze + 兼容性说明)— 见 contracts/agent-config-registry.md §5
- `AgentFactory.create` 重载契约(`@Deprecated` 旧签名 + 新 `create(cfg, registry)` + `loadYamlAndValidate(path)`)— 见 contracts/agent-config-registry.md §6
- Spring Bean Wiring 约定(`AgentConfigRegistry` + `YamlWatcher` Bean 定义 + `AgentConfig` initial bean 注入)— 见 contracts/agent-config-registry.md §8

### Quickstart 摘要

- **7 个 Validation scenarios**:
  - §2 Registry AtomicReference swap(L1)— 100 线程并发 current 全拿最新 publish 的 cfg
  - §3 Listener 异常隔离(L1)— listener 抛 RuntimeException 主流程不阻断
  - §4 InFlightFreeze(L1 + Mockito)— T1 启动 cfg1 + 中途 publish(cfg2) + T1.cfg 引用未变
  - §5 Watcher mtime change → publish(L1)— 修改 yml + Files.setLastModifiedTime 推迟 1s + 5s 内 watcher poll + publish
  - §6 Invalid yml → keep old config(L1)— broken yml + ERROR 日志 + registry 不替换
  - §7 Validate failure → keep old config(L1)— yml 缺 `agent.llm.provider` + 抛 `LINGS-C02` + 字段路径
  - **§8 AC-06 黑盒 E2E**(L5)— T1 sandbox `ls` + 外部 touch 加 `git` + T2 sandbox `git status` 成功 + T1 不中断
- **R-13 dependency:tree 自查**(§9)— 0 新增依赖(AtomicReference / ScheduledExecutorService / Files / SnakeYAML 全部既有)
- **完整回归验证**(§10)— Story #001—#006 既有 41 测试 + Story #007 新增 14 测试 = **55 测试用例**

**所有 Phase 1 artifacts 已生成** → Constitution Check **复评** ✅ 全部通过。
