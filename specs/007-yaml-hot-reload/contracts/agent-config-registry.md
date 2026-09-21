# Contract: AgentConfigRegistry + ConfigChangeListener + YamlWatcher

**Date**: 2026-09-21
**Feature**: Story #007 yaml-hot-reload
**Consumers**: DefaultAgent / Story #016 AuditListener / Story #014 SessionStore / 业务 plugin
**Source**: [`spec.md`](../spec.md) §FR-001—FR-009 + [`research.md`](../research.md) D-01—D-08

---

## §1 Package

`ai.lingshu.core.reload`

| File | Role |
|---|---|
| `AgentConfigRegistry.java` | `@Component` Spring Bean + `public class` |
| `ConfigChangeListener.java` | `public interface` |
| `YamlWatcher.java` | `@Component` Spring Bean + `public class` |

---

## §2 `AgentConfigRegistry` Class Contract

### Class Header

```java
package ai.lingshu.core.reload;

import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for {@link AgentConfig} in this JVM.
 *
 * <p>Holds the latest config in a single-writer / multi-reader
 * lock-free {@link AtomicReference}; {@link #publish(AgentConfig)} is the
 * only write path, {@link #current()} is the only read path used by
 * turn-running code.
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li><b>Write</b>: {@link #publish(AgentConfig)} — single writer
 *       (typically {@link YamlWatcher}); uses {@code AtomicReference.getAndSet}
 *       so {@code previous} value is atomic-snapshotted for listeners.</li>
 *   <li><b>Read</b>: {@link #current()} — multi-reader; lock-free
 *       {@code AtomicReference.get} with volatile load memory barrier.</li>
 *   <li><b>Listeners</b>: {@link CopyOnWriteArrayList}; read-mostly —
 *       added at Spring {@code @PostConstruct} / plugin {@code @Configuration},
 *       rarely removed.</li>
 * </ul>
 *
 * <h3>Freeze semantics</h3>
 * Code that wants to freeze the config for the lifetime of a turn should
 * read {@link #current()} ONCE at the entry point and use the local
 * variable thereafter; Java reference semantics + {@code @Value} immutability
 * guarantee freeze without any explicit snapshot copy.
 *
 * <h3>Listener exception isolation</h3>
 * Listener {@code onConfigChange} throwing {@link RuntimeException} is caught
 * and logged at {@code ERROR}; publish main flow continues and
 * {@code current()} returns the new config to all subsequent readers.
 *
 * @see ConfigChangeListener
 * @see YamlWatcher
 */
@Component
public class AgentConfigRegistry {
    private final AtomicReference<AgentConfig> currentRef = new AtomicReference<>();
    private final CopyOnWriteArrayList<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Initial config injected at construction (auto-publishes on @PostConstruct
     * via {@link #publishInitial(AgentConfig)}).
     */
    public AgentConfigRegistry(AgentConfig initialConfig) {
        // constructor-injected; fields remain null-safe via initial publishInitial call
    }

    /**
     * Auto-publish the constructor-injected initial config.
     * Called by {@code @PostConstruct} Spring lifecycle.
     */
    public void publishInitial(AgentConfig initial) {
        if (initial == null) throw new NullPointerException("initial config must not be null");
        publish(initial);
    }

    /**
     * Atomically replaces the current config with {@code next}.
     *
     * @param next the new config; must not be null
     * @throws NullPointerException if {@code next} is null
     */
    public void publish(AgentConfig next) {
        if (next == null) throw new NullPointerException("next config must not be null");
        final AgentConfig prev = currentRef.getAndSet(next);
        for (ConfigChangeListener l : listeners) {
            try {
                l.onConfigChange(prev, next);
            } catch (Throwable t) {
                // Log at ERROR but do NOT propagate
                org.slf4j.LoggerFactory.getLogger(AgentConfigRegistry.class)
                    .error("ConfigChangeListener {} threw, continuing publish",
                        l.getClass().getName(), t);
            }
        }
    }

    /**
     * Returns the current config (lock-free volatile load).
     * Should be called ONCE per turn entry to leverage freeze semantics.
     */
    public AgentConfig current() {
        return currentRef.get();
    }

    public void addListener(ConfigChangeListener listener) {
        if (listener == null) throw new NullPointerException("listener must not be null");
        listeners.add(listener);
    }

    public boolean removeListener(ConfigChangeListener listener) {
        return listeners.remove(listener);
    }

    /** Test-only: number of registered listeners. */
    int listenerCount() {
        return listeners.size();
    }
}
```

### Invariants

- `currentRef` invariant:never null after `publishInitial(...)` (constructor-only NPE risk)
- Listener semantics:registered listener's `onConfigChange` called on every `publish`, in registration order
- `publish` does NOT mutate `next` — `AgentConfig` is `@Value` immutable anyway, this is documentation

### Threading

- `publish` from arbitrary thread, listener executed **synchronously** in caller's thread (typical: YamlWatcher daemon scheduler thread)
- `current` from arbitrary thread (typical: business turn thread, per-request thread)
- `addListener` / `removeListener` typically called from Spring lifecycle or plugin init
- NFR-005: single `publish` latency ≤ 1ms including N listener callbacks

---

## §3 `ConfigChangeListener` Interface Contract

### Interface Header

```java
package ai.lingshu.core.reload;

import ai.lingshu.core.runtime.AgentConfig;

/**
 * Receives synchronous notifications whenever {@link AgentConfigRegistry#publish}
 * replaces the current config with a new one.
 *
 * <p><b>Threading</b>: called synchronously from whichever thread calls
 * {@code registry.publish(...)} (typically the {@link YamlWatcher} daemon
 * scheduler thread at 5s intervals). Implementations should return quickly
 * and avoid spawning background threads that might recursively publish.
 *
 * <p><b>Exception isolation</b>: throwing {@link RuntimeException}
 * (or any {@link Throwable}) is allowed; {@code AgentConfigRegistry.publish}
 * will catch, log at ERROR, and continue to next listener / subsequent readers
 * — publish main flow is NOT rolled back.
 *
 * <p><b>Reentrancy forbidden</b>: implementation MUST NOT call
 * {@code registry.publish(...)} from within {@code onConfigChange} (causes
 * re-entrancy / listener loop).
 *
 * <p><b>Idempotency</b>: implementations should be idempotent (same prev/next
 * pair may be processed twice in test scenarios).
 */
public interface ConfigChangeListener {
    void onConfigChange(AgentConfig previous, AgentConfig next);
}
```

### Javadoc 显式说明

- **禁止 publish 重入**:listener 内部禁止调 `registry.publish`(否则无限循环)
- **Idempotency**:同一对 prev/next 可能被收多次(单元测试中方便;生产 publish 每次都是新 cfg 不重复)
- **不要在 listener 内 spawn thread**:会让异常隔离边界模糊

---

## §4 `YamlWatcher` Class Contract

### Class Header

```java
package ai.lingshu.core.reload;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls {@code application.yml} mtime every {@link #pollIntervalSeconds}
 * seconds; on change, re-loads + validates via {@link AgentFactory#validateOrThrow}
 * + calls {@link AgentConfigRegistry#publish} if validation passes.
 *
 * <p><b>Single-instance deployment assumed</b>: in a multi-instance
 * deployment, two watchers competing for {@code publish} produce
 * non-deterministic outcomes (last-write-wins) — the registry is
 * single-writer-friendly but does not enforce deploy-time leader election.
 *
 * <p><b>Failure mode</b>: any exception in {@link #poll} (missing file /
 * parse error / validation error / unknown error) is caught and logged at
 * ERROR; the previous config is preserved, {@code lastSeen} is NOT updated
 * (so the next 5s poll retries).
 *
 * <p><b>JDK 8 only</b>: uses {@code Files.getLastModifiedTime} + {@code
 * ScheduledExecutorService}; does not use {@code java.nio.file.WatchService}
 * for cross-platform stability (macOS polling fallback incompatibilities).
 *
 * @see AgentConfigRegistry
 */
@Component
public class YamlWatcher {
    private final Path ymlPath;
    private final AgentConfigRegistry registry;
    private final AgentFactory factory;
    private final Yaml yaml = new Yaml();
    private final long pollIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastSeen;

    public YamlWatcher(
            @org.springframework.beans.factory.annotation.Value("${spring.config.location:application.yml}") String ymlPath,
            AgentConfigRegistry registry,
            AgentFactory factory) {
        this(Paths.get(ymlPath), registry, factory, 5L);
    }

    /** Test-only / package-private constructor for explicit Path + interval. */
    YamlWatcher(Path ymlPath, AgentConfigRegistry registry, AgentFactory factory, long pollIntervalSeconds) {
        this.ymlPath = ymlPath;
        this.registry = registry;
        this.factory = factory;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "yaml-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) return;  // already started
        try {
            this.lastSeen = Files.getLastModifiedTime(ymlPath).toMillis();
        } catch (NoSuchFileException e) {
            lastSeen = 0L;  // retry on each poll
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(YamlWatcher.class)
                .warn("YamlWatcher start: cannot read yml mtime, will retry on first poll", e);
            lastSeen = 0L;
        }
        scheduler.scheduleWithFixedDelay(
            this::pollSafe, pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                org.slf4j.LoggerFactory.getLogger(YamlWatcher.class)
                    .warn("YamlWatcher scheduler did not terminate in 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ensure poll() never throws (watcher must survive all errors). */
    private void pollSafe() {
        try {
            poll();
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(YamlWatcher.class)
                .error("YamlWatcher poll failed unexpectedly; continuing", t);
        }
    }

    /** Polls yml mtime once; reloads + validates + publishes on change. */
    synchronized void poll() {
        if (!running.get()) return;
        long current;
        try {
            current = Files.getLastModifiedTime(ymlPath).toMillis();
        } catch (NoSuchFileException e) {
            org.slf4j.LoggerFactory.getLogger(YamlWatcher.class)
                .warn("yml file missing at {}, skipping publish", ymlPath);
            return;  // do NOT update lastSeen; retry next time file exists
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(YamlWatcher.class)
                .error("cannot stat yml file at {}, skipping publish", ymlPath, e);
            return;
        }
        if (current <= lastSeen) return;  // no change

        AgentConfig next;
        try {
            // Reload via existing Spring binding pipeline (Story #001 AgentConfigProps.toAgentConfig())
            // For Story #007 we use a delegation: factory delegate has the loadYaml hook.
            next = factory.loadYamlAndValidate(ymlPath);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(YamlWatcher.class)
                .error("config validation failed, keeping previous config", e);
            return;  // lastSeen unchanged
        }

        // Atomic publish (registry.publish is the only write path)
        registry.publish(next);
        lastSeen = current;
        org.slf4j.LoggerFactory.getLogger(YamlWatcher.class)
            .info("config reloaded: model={} provider={} react.max-steps={}",
                next.getModel(), next.getLlm().getProvider(), next.getReact().getMaxSteps());
    }
}
```

### Invariants

- **Daemon thread**:yaml-watcher 命名 + daemon = JVM 退出时不阻塞
- **No exception propagation**:`pollSafe` 兜底任何 `Throwable` 致 watcher 线程不终止(R-08 / §14.10 alignment)
- **lastSeen update only on success**:validate 失败 / parse 失败**不**更新 → 下次 5s 重试
- **`@Synchronized poll`**:同 instance 多并发 poll 互斥(L11 内 1 次即可)
- **`running.get()` check**:`pollSafe` 在 shutdown 后仍会被最后一次 schedule 触发,但 `poll()` 入口 `if (!running.get()) return` 立即返回

### Threading

- `start` from Spring `@PostConstruct` (main thread)
- `poll` from daemon scheduler thread (single)
- `stop` from Spring `@PreDestroy` (main thread)
- `factory.loadYamlAndValidate` 调 internal validate,可能 register SlotRouter 状态查询;在 poll thread sync 执行,可能 > 1ms(大 yml 慢),NFR-006 不强制 latency 但 R-13 mitigation 期望 < 50ms

---

## §5 DefaultAgent Modification Contract

```java
package ai.lingshu.core.impl.runtime;

// ...existing imports...
import ai.lingshu.core.reload.AgentConfigRegistry;

public class DefaultAgent implements Agent {
    private final AgentConfigRegistry registry;
    // ... existing fields ...

    /** New constructor: factory.create(cfg, registry) */
    public DefaultAgent(AgentConfig cfg, AgentConfigRegistry registry /*, other args ... */) {
        this.registry = registry;
        // ... existing field initialization ...
    }

    public Publisher<AgentEvent> run(String input) {
        // NEW: freeze cfg at entry — NEVER re-read registry mid-turn
        AgentConfig effectiveCfg = registry.current();
        // existing call sites with effectiveCfg (instead of cfg parameter)
        // ...
    }
}
```

**Compatibility**:existing `DefaultAgent(AgentConfig cfg, /* old args */)` constructor removed; Old `AgentFactory.create(cfg)` API stays (looks up registry bean as default).

---

## §6 AgentFactory.create Overload Contract

```java
public class AgentFactory {
    /**
     * @deprecated since Story #007 — prefer {@link #create(AgentConfig, AgentConfigRegistry)}
     *   for explicit registry; kept for backward compat with Story #001—#006.
     */
    @Deprecated
    public Agent create(AgentConfig cfg) {
        AgentConfigRegistry registry = SpringContextHolder.getBean(AgentConfigRegistry.class);
        return create(cfg, registry);
    }

    /** New: explicit registry (test-friendly; allows custom registries). */
    public Agent create(AgentConfig cfg, AgentConfigRegistry registry) {
        AgentFactory.validateOrThrow(cfg);
        // ... existing creation logic ...
        return new DefaultAgent(cfg, registry /*, ... */);
    }

    /** Used by YamlWatcher: load + validate in one call. */
    public AgentConfig loadYamlAndValidate(Path ymlPath) {
        // 1. Read yml bytes
        // 2. yaml.loadAs(yml, AgentConfigProps.class)
        // 3. props.toAgentConfig()
        // 4. validateOrThrow(result)
        // 5. return result
    }

    /** Already exists from Story #001 §7.1.2 T1. */
    public static void validateOrThrow(AgentConfig cfg) { /* ... */ }
}
```

---

## §7 Error Semantics Summary

| Error | When | Handler | Logging |
|---|---|---|---|
| `NullPointerException` | `publish(null)` / `addListener(null)` | propagate(API contract violation) | (no logging — caller bug) |
| `LingsConfigException("C02")` | validate fail | propagate to YamlWatcher | ERROR yml line + field path |
| `YAMLException` | yml parse fail | catch in YamlWatcher.poll | ERROR yml path + cause |
| `IOException` | file stat fail | catch in YamlWatcher.poll | ERROR/WARN context-dependent |
| `RuntimeException` | listener bug | catch in AgentConfigRegistry.publish | ERROR listener class + cause, continue |
| `Throwable` | unexpected | catch in YamlWatcher.pollSafe (final) | ERROR, watcher continues |

---

## §8 Spring Bean Wiring

```java
// Inside AgentConfigRegistryAutoConfiguration or default @Configuration:
@Bean
public AgentConfigRegistry agentConfigRegistry(AgentConfig initialConfig) {
    AgentConfigRegistry registry = new AgentConfigRegistry(initialConfig);
    registry.publishInitial(initialConfig);
    return registry;
}

@Bean
public YamlWatcher yamlWatcher(AgentConfigRegistry registry, AgentFactory factory) {
    return new YamlWatcher(...);  // depends on yml path; default application.yml
}
```

**Notes**:
- `AgentConfig` initial bean 由 Spring Boot `@ConfigurationProperties` 注入(`AgentConfigProps.toAgentConfig()`);Story #001 已固化
- plugin 通过 `@Component implements ConfigChangeListener` 自动注册;**不**需要 plugin 改 AutoConfiguration(与 §5.5 Provider 模式类比)
