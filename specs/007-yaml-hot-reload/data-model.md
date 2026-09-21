# Data Model: Story #007 yaml-hot-reload

**Date**: 2026-09-21
**Source**: [`spec.md`](./spec.md) + [`research.md`](./research.md)
**Consumers**: [`contracts/agent-config-registry.md`](./contracts/agent-config-registry.md), `quickstart.md`, `tasks.md`

---

## Entities & State

| Entity | 类型 | 状态 | 关系 |
|---|---|---|---|
| `AgentConfigRegistry` | `@Component` Spring Bean (单例) | `current: AtomicReference<AgentConfig>` 持有单写多读 cfg;`listeners: CopyOnWriteArrayList<ConfigChangeListener>` | 持有 1 个 cfg 引用 + N 个 listener |
| `AgentConfig` (existing from Story #001) | `@Value` 不可变 | — | 由 `AgentConfigRegistry.current` 持有 |
| `YamlWatcher` | `@Component` Spring Bean (单例) | `lastSeen: volatile long`,`running: boolean`,`ymlPath: Path`,`registry: AgentConfigRegistry`,`factory: AgentFactory`,`yaml: Yaml`,`scheduler: ScheduledExecutorService` | 写 `registry.publish(next)` |
| `ConfigChangeListener` | interface | — | 被 `AgentConfigRegistry` 持有 + 业务 Bean 实现 |

---

## Entity Detail

### AgentConfigRegistry

| 字段 | 类型 | 修饰 | 约束 |
|---|---|---|---|
| `current` | `AtomicReference<AgentConfig>` | `private final` | 单写多读 |
| `listeners` | `CopyOnWriteArrayList<ConfigChangeListener>` | `private final` | 读多写少(发布事件 + 注册) |

| 方法 | 签名 | 行为 |
|---|---|---|
| `current()` | `public AgentConfig current()` | `return currentRef.get();` |
| `publish(next)` | `public void publish(AgentConfig next)` | 1) `AgentConfig prev = currentRef.getAndSet(next);` 2) 同步触发 listeners(异常隔离) 3) 入参校验 `next != null` 抛 `NullPointerException` |
| `addListener(l)` | `public void addListener(ConfigChangeListener l)` | `listeners.add(l);` + null 校验 |
| `removeListener(l)` | `public boolean removeListener(ConfigChangeListener l)` | `return listeners.remove(l);` |
| 构造器 | `@Autowired` | 注入 `AgentConfig`(启动期 cfg)+ 自动 publish initial + 实现 `SmartInitializingSingleton` 也可 |

**State Transitions**:
```
                       publish(next)
              +-------------------------+
              |                         v
   [Empty] -> [Holding cfg1] -> [Holding cfg2] -> [Holding cfg3]
                                            ^
                                            |
                                publish(next) (any time)
```

### ConfigChangeListener

```java
public interface ConfigChangeListener {
    /**
     * @param previous 替换前的 cfg(immutable)
     * @param next     替换后的 cfg(immutable)
     * @throws RuntimeException 不允许影响 publish 主流程,但会触发日志 ERROR
     */
    void onConfigChange(AgentConfig previous, AgentConfig next);
}
```

| 方法 | 行为 |
|---|---|
| `onConfigChange(prev, next)` | 业务方实现:可订阅 cfg 变更后执行 init/refresh/cleanup。**异常必须被 publish 主流程 catch** |

### YamlWatcher

| 字段 | 类型 | 修饰 | 约束 |
|---|---|---|---|
| `ymlPath` | `Path` | `private final` | 注入(yml 路径) |
| `registry` | `AgentConfigRegistry` | `private final` | 注入 |
| `factory` | `AgentFactory` | `private final` | 注入,validateOrThrow 复用 |
| `yaml` | `Yaml` | `private final` | SnakeYAML 实例(Spring 已经依赖 snakeyaml 2.x,§2 deps) |
| `lastSeen` | `long` | `private volatile` | 上次 mtime(JDK 内存屏障足够) |
| `pollIntervalSeconds` | `long` | `private final` | 默认 5s |
| `scheduler` | `ScheduledExecutorService` | `private final` | daemon thread "yaml-watcher" |
| `running` | `boolean` | `private volatile` | 控制启停 |

| 方法 | 签名 | 行为 |
|---|---|---|
| `start()` | `@PostConstruct public void start()` | 1) `running = true` 2) 初始化 `lastSeen = ymlPath.getLastModifiedTime().toMillis()`(`NoSuchFileException` 时 = 0) 3) `scheduler.scheduleWithFixedDelay(this::pollSafe, pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS)` |
| `stop()` | `@PreDestroy public void stop()` | 1) `running = false` 2) `scheduler.shutdownNow()` 3) `awaitTermination(5, SECONDS)` |
| `pollSafe()` | `private void pollSafe()` | try-catch `Throwable` 包裹 `poll()`,确保 watcher 线程不终止 |
| `poll()` | `private synchronized void poll()` | 1) `if (!running) return` 2) `long current = ymlPath.getLastModifiedTime().toMillis()`(`NoSuchFileException` catch → warn skip) 3) `if (current <= lastSeen) return` 4) `load + validate + publish + lastSeen = current`;任意异常 catch + log + **不**更新 lastSeen(下次重试) |

**State Transitions**:
```
   [Not started]
        | @PostConstruct start()
        v
   [Running, lastSeen=init]
        | @PreDestroy stop()
        v
   [Stopped]
```

每个 `poll()` 子状态:
```
   [Ready]
     | read yml mtime
     v
   [Compared]
     | mtime <= lastSeen -> back to Ready
     | mtime >  lastSeen -> Load YAML
     v
   [Loaded] -> validate -> [Validated]
                              | OK -> publish -> [Published]
                              | FAIL -> log error + back to Ready (lastSeen unchanged)
```

### DefaultAgent (modified)

| 字段 | 类型 | 修饰 | 变更 |
|---|---|---|---|
| `registry` | `AgentConfigRegistry` | `private final` | 🆕 由 factory.create(cfg, registry) 注入 |
| `cfg` | `AgentConfig` | `private final` | 🆕 在构造期或 `run(input)` 入口赋值(两种方案见 research D-03) |

| 方法 | 签名 | 行为 |
|---|---|---|
| 构造器 | `public DefaultAgent(AgentConfig cfg, AgentConfigRegistry registry, ...)` | 接收 registry + cfg(注意 cfg 此时仍可由 builder 提供) |
| `run(input)` | `public Publisher<AgentEvent> run(String input)` | **改动**:入口 `AgentConfig effectiveCfg = registry.current();` 然后**整个 turn 用 effectiveCfg**(不动 registry) |

---

## Validation Rules

| Entity | Rule | 验证时机 |
|---|---|---|
| `AgentConfigRegistry.publish` | `next != null` 否则 NPE | publish 时 |
| `AgentConfigRegistry.publish` | `next.isValid()` 内部规则(Story #001 已有)| publish 时(已被 validateOrThrow 保证) |
| `YamlWatcher.poll` | yml mtime 读取不抛 `IOException` 终止 watcher | poll 时(catch `IOException`) |
| `YamlWatcher.poll` | SnakeYAML parse 不抛 `YAMLException` 终止 watcher | poll 时(catch `YAMLException`) |
| `AgentFactory.validateOrThrow` | 27+ 字段验证(Story #001 已固化)| YamlWatcher publish 时复用 |

---

## Storage & Persistence

| 数据 | 存储位置 | 保留期 |
|---|---|---|
| `AgentConfig` 对象 | JVM heap(registry current ref)| 进程生命周期 |
| `lastSeen` (long mtime) | YamlWatcher field | 进程生命周期 |
| listener 列表 | `CopyOnWriteArrayList<ConfigChangeListener>` 在 registry | 进程生命周期 |
| yml 文件 mtime | OS 文件系统 | 无限(由 yml 文件本身决定) |

**不**引入 disk persistence(leveldb/redis):
- reload 是 in-memory config cache + OS file system 监听,**不**需要持久化
- 进程重启 = 启动期 reload 重新跑 yml-bind(`AgentConfigProps.toAgentConfig()`),无需迁移

---

## Concurrency Model

| Operation | Thread | 锁 |
|---|---|---|
| `registry.publish` | 任意线程(主要 YamlWatcher daemon)| `AtomicReference.getAndSet` lock-free CAS |
| `registry.current` | 任意线程(主要业务 turn 入口)| `AtomicReference.get` lock-free volatile read |
| `registry.addListener` | 主要 @PostConstruct / plugin @Configuration | `CopyOnWriteArrayList` lock-free add |
| `registry.removeListener` | 主要 Spring shutdown / plugin disable | `CopyOnWriteArrayList` lock-free remove |
| `registry.publish` 内 listeners 触发 | 与 publish 同步触发,**不**跨线程 | `for-each listeners { try { listener.onConfigChange(prev, next); } catch (Throwable t) { log.error(...); } }` |
| `YamlWatcher.poll` | daemon scheduler thread | `synchronized(this) on poll()`(L 11 排他:同 instance 多 poll 并发避免) |
| `DefaultAgent.run` 内 `registry.current()` | turn 入口一次 | atomic read |

**关键不变量**:
- publish 期间**不**持锁超过 1 次 listener 触发(典型 < 1ms)
- listener 内**禁止**调 `registry.publish`(否则重入死循环;Javadoc 显式警告)
- Listener 异常**禁止**阻断 publish(异常隔离)
- `DefaultAgent.run` 在 freeze 之后**禁止**再次 `registry.current()`(违反即 NFR-007 冻结合规)

---

## Key Relationships

```
                  +----------------------+
                  | AgentConfigRegistry  | (@Component Spring Bean)
                  +----------------------+
                          |   |  |  |  |
                          |   |  |  |  +-- N× listeners (ConfigChangeListener impl)
                          |   |  |  +---- get/AtomicReference -> latest cfg
                          |   |  +------- publish (AtomicReference.set)
                          |   +---------- removeListener
                          +-------------- addListener
                                  |
                          (publish 触发)
                                  v
                  +----------------------+
                  | YamlWatcher          | (@Component Spring Bean)
                  +----------------------+
                          |   poll() 5s 读 yml mtime
                          |             |
                          |           [OS FS]
                          |
                          v (detect change)
                  load YAML + validate + publish
                          |
                          v (调 registry.publish)
                          |
                  +----------------------+
                  | DefaultAgent.run()   | (per turn)
                  +----------------------+
                          |
                          v (entries 拿 registry.current())
                  cfg (final, frozen)
```

---

## Migration / Forward Compat

- **Story #001—#006 测试零回归**(SC-009 验证):旧 `AgentFactory.create(cfg)` API 仍存在(走 default registry bean lookup)
- **plugin 扩展点**:plugin 写 `ConfigChangeListener` 接口实现 @Component 即可(§5.4 multi Provider 模式 类比)
- **未来重命名**:Registry / Listener / Watcher 三件套可能后续 v2 重构(spi → own `ConfigSource` 接口);Story #007 仅私有实现,**不**新增 SPI
