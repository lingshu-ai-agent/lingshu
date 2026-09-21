# Research: Story #007 yaml-hot-reload

**Date**: 2026-09-21
**Branch**: `story-007-yaml-hot-reload`
**Spec**: [`spec.md`](./spec.md)
**Plan**: [`plan.md`](./plan.md)

> Phase 0 output: **8 design decisions** resolved (D-01—D-08)
> 所有 NEEDS CLARIFICATION 已落地,无遗留疑点。

---

## D-01:`AtomicReference<AgentConfig>` 单写多读 而非 `volatile` 字段

**决策**:`registry` 内部持 `private final AtomicReference<AgentConfig> current = new AtomicReference<>();`,publish = `current.set(next)`,read = `current.get()`。

**Rationale**:
- `AtomicReference` 比 `volatile AgentConfig field` 更严格:volatile 只能保证可见性(MESI cache line flush),`AtomicReference.get()` 利用 `VarHandle` 内存屏障更强;虽然单写多读场景两者都能 work,但 AtomicReference 是 JDK 官方对 "immutable 对象在多线程间发布" 的标准答案
- AtomicReference 提供 `getAndSet` / `compareAndSet` 等未来扩展操作(若需要乐观锁 publish 不被覆盖);Story #007 不必用,但保留 API 空间
- 多线程单元测试简洁:`registry.current()` 永远拿到 either 旧 or 新,无中间态

**Alternatives considered**:
- `volatile AgentConfig field` —— 能 work,但 API 表达能力不足 + 多读者写者扩展性差
- `ConcurrentHashMap<String, AgentConfig>` —— 杀鸡用牛刀,1 个 key 用 Map 是反模式
- `ReadWriteLock + AgentConfig field` —— 单写多读根本不需要锁,lock-free 更快

---

## D-02:`Files.getLastModifiedTime` + 5s poll 而非 `WatchService` (JDK NIO file events)

**决策**:`YamlWatcher.poll()` 用 `Files.getLastModifiedTime(ymlPath).toMillis()` 对比 `lastSeen`,变则 reload;调度用 `ScheduledExecutorService.scheduleWithFixedDelay(...)`,默认 5s 间隔。

**Rationale**:
- **JDK 8 兼容**:`WatchService` 是 JDK 7 NIO,JDK 8 OK,但 `WatchKey` 在 Linux / Windows / macOS 行为差异:macOS 不可靠(polling fallback),Linux inotify 文件描述符 leak 风险;`getLastModifiedTime` 是 cross-platform stable API
- **极简**:无 daemon thread 同步状态,无 `WatchKey.cancel()` 资源管理,无 overflow detection(快速写文件可能 WatchService 静默丢失)
- **delay 5s 可接受**:yml hot-reload 是"运维操作",秒级延迟无 SLA 违反;§14.8 design verbatim 5s + .yml 文件频率 < 1次/分钟;典型 NFS / 本地盘 mtime 都是稳态
- **断电 / no-inotify 环境可工作**:docker volume mount / tmpfs / NFS 都 OK;WatchService 在某些 mount 会 fail

**Alternatives considered**:
- `WatchService` + ENTRY_MODIFY events —— 平台兼容性差 + 需 listener semantics 维护 + JDK 8 适配不友好
- 业务代码主动触发 reload(Spring `@RefreshScope` + actuator `/reload`)—— 太多手工干预,与"自动 reload"语义冲突
- io.methvin.watcher (third-party `DirectoryWatcher`)—— §2 deps 锁定不允许引额外依赖,R-13 mitigation (d) 强制

**降级条件**:若 NFS mtime 抖动(chmod 也会更新 mtime),可在 `YamlWatcher` 加 `pollIntervalSeconds=30`(生产默认)or `pollIntervalSeconds=2`(开发默认)。

---

## D-03:DefaultAgent.run 入口 `cfg = registry.current()` 一次冻结 而非 `configRef.get()` 包装

**决策**:`DefaultAgent` 构造器注入 `AgentConfigRegistry`;`run(input)` 入口执行 `AgentConfig cfg = registry.current();` 然后把 `cfg` 传给后续所有 `TurnContext`/子调用;**整个 turn 不**再 `current()`。

**Rationale**:
- **冻结语义自然成立**:Java 局部变量 + 不可变 `@Value` 对象,一旦赋值即不可变;后续 `registry.publish` **不**影响本 turn 的 `cfg` 引用(Java 引用语义非指针别名)
- **零 overhead**:`@Value AgentConfig` 是 `final` fields + 不可变 List,字段读取是 plain field load,**比**每步 `registry.current()` (volatile read + memory barrier) 快 10x
- **AC-06 验证友好**:写测试 `assertSame(cfg1, T1.run(input))` 即可验证冻结;无需 mock or 时序 assert

**Alternatives considered**:
- `AtomicReference<AgentConfig> field` 注入 Agent,每步 `field.get()` —— 单 turn 多次 volatile read 性能 + 时序复杂性都更高
- 监听 publish 自动重置 turn 状态 —— 与"冻结"语义相反,违反 FR-004
- 在 Agent 创建时 snapshot 一次 `final AgentConfig cfg`,但构造器参数变更会让 factory 调用方 break —— 仍要走 registry 注入模式(FR-005)

**特殊场景**:Story #016 AuditLogger 或 Story #014 SessionStore 等"长生命周期"服务需要"重新订阅 config" 能力,**不**通过 DefaultAgent —— 它们是 `@Component` 单例 Spring Bean,实现 `ConfigChangeListener` 在 `registry.addListener(...)` 注册回调,publish 时自动收 `onConfigChange(prev, next)`。

---

## D-04:`AgentFactory.create` 旧签名保留 + 新增 `create(cfg, registry)` 重载

**决策**:新增 public 重载 `public Agent create(AgentConfig cfg, AgentConfigRegistry registry)`;既有 `public Agent create(AgentConfig cfg)` 内部 default registry = `applicationContext.getBean(AgentConfigRegistry.class)`(Spring 单例 Bean)。

**Rationale**:
- **向后兼容 Story #001—#006**:旧 `create(cfg)` 调用点零改动,既有多租户 / cancellation / parallel dispatch 等测试零回归
- **registry 默认 Bean**:`AgentConfigRegistry` 是 `@Component` Spring Bean,Spring 自动 register;旧 `create(cfg)` 通过 DI lookup 拿到同一实例,**符合** §1 #9 Spring Boot SPI + §1 #11 默认实现位置约定
- **显式注册路径 + 默认 fallback**:少数场景(测试 / 启动期注入 cfg)允许显式传 registry;99% 业务代码用旧签名即可

**Alternatives considered**:
- **直接改 `create` 签名**(破所有 Story #001—#006 测试)—— 风险高 / test churn 巨大
- 让每个 Agent 内部 `addListener` 自管刷新 —— 与 AC-06 冻结语义相反
- 把 registry 静态化(static field)—— 单测困难,违反 Spring Bean 语义

---

## D-05:`ConfigChangeListener` 异常隔离 — publish 不 rollback

**决策**:listener 抛 RuntimeException → catch + ERROR 日志 + **不**回滚 publish;后续 listener 继续触发(`try-catch` 包裹每个 listener)。

**Rationale**:
- **publish 应 idempotent 可见**:即使 listener 全失败,registry.current() **必须**拿到 next(否则 freeze 语义 broken)
- **隔离 vs 传播 tradeoff**:典型 mid-platform 模式(AWS SQS / Kafka consumer commit)都是"listener 异常不影响 main flow"
- **可观测性 vs 哑火**:ERROR 日志 + listener class name + cause message 让运维定位问题;不静默吞错
- **Java SPI 风格**:与 §5.5 Provider 异常隔离 + §11 错误码约定一致

**Alternatives considered**:
- **listener 异常 abort publish**(回滚 next)—— 强一致性但违反 AC-06 冻结语义(在途 turn 拿不到 next,反而可能拿半完成 cfg)
- **listener 异步调**(spawn thread)—— 复杂度爆炸,且 listener 内部若重 publish 触发死锁
- **listener 异常 escalate 到 JVM error** —— 过度,publish 是日常操作不是 critical path

**开 listener 写法**:业务方要么实现 `ConfigChangeListener` interface 单方法,要么匿名 inner class:`registry.addListener((prev, next) -> log.info("config changed: {}", next));` —— 保证最简用法可用。

---

## D-06:`AgentConfig` 不可变 + 无需 `snapshot()` 方法

**决策**:`AgentConfig` Lombok `@Value` 默认所有字段 `final` + 不可变集合(`Collections.unmodifiableList(new ArrayList<>(...))` 在 `AgentConfigDefaults` 阶段已 deep copy);**不**新增 `.snapshot()` 方法。

**Rationale**:
- `@Value` 已是天然 immutable,Java 引用语义自然冻结(局部变量 = 不可变引用 + 内部字段 final)
- **额外 `.snapshot()` 是反模式**:同一个对象实例 in-memory 不可能"突变",只有 publish 替换 registry 当前引用才会发生
- 测试断言 `assertSame(originalCfg, turn.cfg)` 直接验证,**无 snapshot 概念**
- **降低 API surface** —— Story #007 by nature ≤ 5 文件改动(3 new + 2 modified),不引入冗余方法

**Alternatives considered**:
- 手写 `defensive copy` in constructor —— Lombok `@Value` 已处理 + 在 `AgentConfigDefaults` 已 unmodifiableList 包装
- 加 `record`-style `with()` 方法(JDK 14+,违反 §3)—— 不允许
- 加 `.snapshot()` 显式方法 —— 增加 API surface 但无功能价值;alias of `this`

---

## D-07:`reload` 包位置 = `ai.lingshu.core.reload`(new package)

**决策**:3 个 new file 都在 `lingshu-core/src/main/java/ai/lingshu/core/reload/`:
- `AgentConfigRegistry.java`
- `ConfigChangeListener.java`
- `YamlWatcher.java`

**Rationale**:
- **新包 vs 复用既有包**:Story #006 创建了 `tenant/` 包,显示新 feature 都开新包 — reload/ 与"config 热更" 单一职责强绑定
- **路径规则对齐**:`lingshu-core/` 是 application code;新包与 `runtime/` / `tenant/` / `exception/` / `validation/` 同级
- **避免污染 runtime 包**:runtime/ 现在有 `AgentFactory` / `DefaultAgent` / `AgentConfig`(核心),reload 概念相对独立,可独立 future extension(Story #013 healthcheck / Story #010 otel 等都可能复用 listener 机制)

**Alternatives considered**:
- 放 `runtime/reload/` sub-package —— 增加深度,reload 是 cross-cutting concern 不属于 runtime 直接子概念
- 放 `config/` 包 —— config 包当前只有 `AgentConfigDefaults`,reload 不是 config 静态值,而是 config 动态变更机制
- 放 `impl/reload/` —— reload 是 interface + impl 混合(AgentConfigRegistry 是 @Component),impl/ 倾向于放 concrete impl

---

## D-08:`YamlWatcher` 5s poll 间隔对齐 §14.8 + 可配置

**决策**:默认 `pollIntervalSeconds = 5L`,作为构造函数参数(默认 5),留扩展接口 `new YamlWatcher(ymlPath, registry, factory, scheduler, 5L)`。

**Rationale**:
- **§14.8 verbatim**:原文 `@Scheduled(fixedDelay = 5_000)` 即 5 秒
- **JDK 8 ScheduledExecutorService** 替代 Spring `@Scheduled`:减少 Spring Context dependency,watcher 在 Spring 启动早期 / 非 Spring context(单元测试)都可工作
- **可配置**:生产挂 NFS 时降频至 30s(避免 mtime 抖动 false positive);开发/dev 状态保持 5s(快速反馈);可在 `application.yml` 加 `agent.reload.poll-interval-seconds` 让运维调,Story #007 实现**不**做 yml binding(留 Story 后续),保持 Stort #007 简单

**Alternatives considered**:
- Spring `@Scheduled(fixedDelayString = "${agent.reload.poll-interval-seconds:5000}")` —— 需要 Spring `@Configuration` `@EnableScheduling`,在 lingshu-core 引入 org.springframework.context.annotation 切换复杂
- `ScheduledExecutorService` daemon thread —— 本决策
- 每次 `Object.wait(5_000)` —— 不允许(占用线程 + 无法 schedule 取消)

---

## Risks / Anti-patterns 表

| Anti-pattern | Mitigation |
|---|---|
| ❌ YamlWatcher poll loop block 多秒(load 巨 yml) | `poll()` 用 `Files.size` 快读 + `Files.readAllBytes` 加 timeout(Long.MAX 但 99% < 1s);实在大交给运维调 `pollIntervalSeconds=30` |
| ❌ publish 触发 listener 死循环(听众改 cfg 又 publish) | listener 不允许直接 publish(需通过 `AgentFactory.validateOrThrow + publish` 链路);Javadoc 显式说明 |
| ❌ 默认 registry 多实例(per thread/per request) | `@Component` 默认 Spring 单例;测试通过 constructor 注入同实例 |
| ❌ listener 抛异常吞掉 publish | D-05 异常隔离 — publish main flow commit,listener 失败仅日志 |
| ❌ DefaultAgent.run 多次 `current()` | D-03 入口 freeze,后续不再访问 |
| ❌ DefaultAgent 注入 `AtomicReference<AgentConfig>` 字段 | D-03 不要 — 字段赋值时机难定 + 引用替换 complexity;走 registry 注入 + 入口 freeze |

---

## 8 设计决策总结(总览)

| ID | 决策 | Motivation |
|---|---|---|
| D-01 | AtomicReference 单写多读 | 多线程可见性 + API 扩展性 |
| D-02 | Files.getLastModifiedTime + 5s poll | JDK 8 cross-platform + 无 daemon thread complexity |
| D-03 | DefaultAgent.run 入口 freeze | 引用语义自然冻结 + 零 overhead |
| D-04 | AgentFactory.create 重载保留 | 向后兼容 Story #001—#006 |
| D-05 | listener 异常隔离 | publish main flow 不可阻断 |
| D-06 | 无 `.snapshot()` 方法 | @Value 已不可变,API 简朴 |
| D-07 | `reload/` 新包 | Cross-cutting concern 独立 |
| D-08 | 5s poll 间隔可配置 | 对齐 §14.8 + NFS 降频 |

---

## 引用章节

- dsh §0.4 AC-06 L121-125(YAML 热更无中断)
- dsh §14.8 N8 L6780-6814(YamlWatcher + AgentConfigRegistry + DefaultAgent 读最新 config)
- dsh §7.1.2 T1 + §17 R-03 L7237(validateOrThrow 复用 + 数据竞争缓解)
- constitution §1.12(启动期配置校验 extend 到 hot-reload 期)
- constitution §2.13(R-13 0 新增依赖)
- Story #006 plan.md D-04(Provider 注入模式类比 reload listener 注册)
