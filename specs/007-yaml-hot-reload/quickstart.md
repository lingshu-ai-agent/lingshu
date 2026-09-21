# Quickstart: Story #007 yaml-hot-reload

**Feature**: Story #007 yaml-hot-reload(AC-06 — YAML 热更无中断)
**Branch**: `story-007-yaml-hot-reload`
**Target**: 工程化早期验证 reload 不阻塞 turn + 配置变更自动生效

> 这是一份**可执行验证脚本**,聚焦于 AC-06 黑盒主路径;实施细节留 `tasks.md`。

---

## §1 前置

| 依赖 | 版本 |
|---|---|
| JDK | 17+(实际跑)/ 1.8 编译目标 |
| Maven | 3.6.3+ |
| 既有 story | Story #001—#006 已 merge |
| 测试夹具 | 临时 yml 文件 + mock 监听器 |

```bash
mvn -v   # Maven 3.6.3+, Java 17
```

---

## §2 验证场景 1:Registry AtomicReference swap (L1 Unit)

**目标**:验证 `AgentConfigRegistry.publish` 后 `current()` 立即返回新 cfg,**无中间态**可被读。

```bash
mvn test -Dtest=AgentConfigRegistryTest#publishCurrentAtomic_swap_immediateReads
```

**期望**:1 green test;`publish(cfg2)` 之后 100 线程并发 `current()` 全部立即看到 `cfg2`(无丢失更新 / 无陈旧读)。

---

## §3 验证场景 2:Listener 异常隔离 (L1 Unit)

```bash
mvn test -Dtest=AgentConfigRegistryTest#listenerThrows_publishContinues
```

**步骤**:
1. 注入 mock listener `onConfigChange(prev, next) → throw RuntimeException("boom")`
2. 再注入 ok listener,断言 ok listener 仍被调(并断言 publish 不回滚)
3. 断言 `registry.current() == next`(新 cfg 已经生效)

**期望**:1 green test;listener 异常**不**阻断 publish 主流程;`current()` 返回新 cfg(下一个 listener 仍触发)。

---

## §4 验证场景 3:InFlightFreeze (L1 + Mockito)

**目标**:AC-06 核心 — T1 启动拿 cfg1,中途 `registry.publish(cfg2)`,T1 全程 cfg 引用未变。

```bash
mvn test -Dtest=InFlightFreezeTest#finalFieldDefiesMidTurnConfigSwap
```

**步骤**:
1. 创建 registry,publish cfg1
2. 创建 DefaultAgent,run(input) 启动
3. ReAct 循环到 step 1 时 mock `registry.publish(cfg2)`(模拟外部 yml 改动)
4. 继续跑完 T1,断言 `agent.cfg == cfg1`(引用相同,`assertSame` 即可)
5. 启动 T2,断言 `T2.cfg == cfg2`

**期望**:1 green test;AC-06 冻结语义验证。

---

## §5 验证场景 4:Watcher mtime change → publish (L1 Unit)

**目标**:修改 yml 文件后 5s 内 watcher 自动 reload + publish。

```bash
mvn test -Dtest=YamlWatcherTest#pollMtimeChange_triggersPublishAfterValidation
```

**步骤**:
1. Setup:tmp dir / write `application.yml` with minimal config + sandbox whitelist = `[ls,cat]`
2. Start watcher;assert initial `lastSeen = yml.mtime`
3. Update yml: append `git` to whitelist;`Files.setLastModifiedTime` 推迟 1s(JVM mtime 精度)
4. Call `watcher.poll()` (avoid 5s wait);assert `registry.current().sandbox.commandWhitelist.contains("git")`
5. Assert `lastSeen` updated to new mtime

**期望**:1 green test;mtime 变动触发 reload + validate + publish。

---

## §6 验证场景 5:Invalid yml → keep old config (L1 Unit)

```bash
mvn test -Dtest=YamlWatcherTest#pollInvalidYaml_keepsOldConfig
```

**步骤**:
1. Start watcher with valid yml + published cfg1
2. Modify yml: write broken content(`malformed: :: invalid yaml ::`)
3. Call `watcher.poll()`
4. Assert `registry.current() == cfg1`(仍为旧 cfg,未被替换)
5. Assert `lastSeen`**不**更新(下一次 poll 会再尝试)
6. Fix yml back to valid;call `watcher.poll()` 一次
7. Assert `registry.current() != cfg1`,`lastSeen` 更新

**期望**:1 green test;**rollback 语义** + 自动重试。

---

## §7 验证场景 6:Validate failure → keep old config (L1 Unit)

```bash
mvn test -Dtest=YamlWatcherTest#pollValidationFail_keepsOldConfig
```

**步骤**:
1. Start watcher with valid yml + published cfg1
2. Modify yml:remove `agent.llm.provider` 字段(必填校验会失败)
3. Call `watcher.poll()`
4. Assert `registry.current() == cfg1`
5. Assert log contains `"C02"` + `"agent.llm.provider is required"`

**期望**:1 green test;`validateOrThrow` 失败 → rollback + ERROR 日志 + 字段路径。

---

## §8 验证场景 7:AC-06 黑盒 E2E(全链路)

**目标**:AC-06 黑盒主路径 — T1 跑 sandbox 跑 `["ls"]` 旧 whitelist 生效;同时外部 touch yml 加 `git`;T2 跑 `["git", "status"]` 立即生效;**T1 不**被中断 / 重启。

```bash
mvn test -Dtest=YamlHotReloadIT#ac06YamlHotReload_noInterrupt_oldTurnFrozen_nextTurnSeesNewConfig
```

**Setup**:
1. `@SpringBootTest` + 临时 yml(`/tmp/lingshu-test/application.yml`)
2. Initial yml:`sandbox.command-whitelist=[ls, cat, echo]`
3. Spring 启动 AgentFactory + AgentConfigRegistry + YamlWatcher

**Action**:
1. 启动 T1 = thread.submit(`agent.runBlocking("[run 'ls /tmp']")`)— 注意 `ls` 在旧 whitelist 里
2. Sleep 100ms 等 T1 进 ReAct step 0
3. Modify yml:append `git` + `Files.setLastModifiedTime(d + "1s")`
4. Sleep 6s 等 watcher poll(默认 5s + buffer)
5. **Do NOT** cancel T1;just verify T1 still running with cfg1(代理:assert agent.cfg == oldCfg via debug hook)
6. 启动 T2 = `agent.runBlocking("[run 'git status']")`
7. Wait T1 done:assert T1 内部 sandbox 调用历史**不**包含 `git`(仅含 ls)
8. Wait T2 done:assert T2 内部 sandbox 调用历史**包含** `git status` 且**成功**

**Expected output**:
```
T1 completed: tool calls = [ls /tmp], cfg ref = cfg1 (frozen)
Watcher: reloaded config, lastSeen updated, publish(cfg2)
T2 completed: tool calls = [git status], cfg ref = cfg2 (new)
```

**期望**:1 green test;AC-06 黑盒主路径 100% pass。

---

## §9 R-13 dependency:tree 自查

```bash
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-007-after.txt
diff /tmp/deps-006-baseline.txt /tmp/deps-007-after.txt
```

**期望**:**0 new dependencies** 输出(`AtomicReference` / `ScheduledExecutorService` / `Files` / SnakeYAML 已经在 baseline)。

如果 diff 显示新 transitive deps:**STOP** → investigate → RFC → remove。

---

## §10 完整回归验证

```bash
mvn -pl lingshu-core test
```

**期望**:`Tests run: ≥ 41`(Story #006 后),**0 failures**,**0 errors**。包括:
- Story #001 — #005 既有 19 测试
- Story #006 新增 22 测试(L1 + L5 E2E)
- Story #007 新增 14 测试(L1 + L2 + L5 E2E)

无回归 + AC-06 黑盒通过 = Story #007 可 PR。

---

## §11 反模式自检(避免 Story 期间写下)

- ❌ 用 `WatchService`(JDK NIO,平台兼容问题)
- ❌ 让 `DefaultAgent.run` 在中途 `registry.current()`(违反 freeze)
- ❌ Listener 内调 `registry.publish`(重入死循环)
- ❌ Listener 异常冒泡到 publish 主流程(异常应隔离)
- ❌ 用 `ConcurrentHashMap` 装 1 个 key(过度设计)
- ❌ publish 失败 → 自动 rollback(违反语义)
- ❌ 在 DefaultAgent 持 `AtomicReference<AgentConfig>` field(局部变量 freeze 更简洁)
- ❌ watcher 用 `synchronized` 锁所有路径(watchdog 阻塞 scheduler 自身)
- ❌ 5s 间隔硬编码不可配(NFS 降频需求)
- ❌ 引第三方 `io.methvin.watcher`(R-13 mitigation 违反)

---

## §12 链路引用

- [`spec.md`](../spec.md) §FR-001—FR-009 — 注册表 / 监听器 / watcher / DefaultAgent / AgentFactory 重载
- [`research.md`](../research.md) D-01—D-08 — 8 个设计决策 + rationale
- [`data-model.md`](../data-model.md) — entities + state + concurrency + storage
- [`contracts/agent-config-registry.md`](../contracts/agent-config-registry.md) §1—§8 — 完整 Java 代码契约
- [`plan.md`](../plan.md) §Constitution Check + File-Level Changes + Test Strategy
- dsh §14.8 N8 L6780-6814 — 设计原典
- dsh §0.4 AC-06 L121-125 — 验收标准
