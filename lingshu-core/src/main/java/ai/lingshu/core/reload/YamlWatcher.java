package ai.lingshu.core.reload;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls {@code application.yml} {@link java.nio.file.attribute.FileTime mtime}
 * every {@link #pollIntervalSeconds} seconds; on change, re-loads + validates
 * via {@link AgentFactory#loadYamlAndValidate} + calls
 * {@link AgentConfigRegistry#publish} if validation passes (Story #007, AC-06
 * dsh §14.8 N8).
 *
 * <p>Single-instance deployment assumed: in a multi-instance deployment, two
 * watchers competing for {@code publish} produce non-deterministic outcomes
 * (last-write-wins). The registry is single-writer-friendly but does not
 * enforce deploy-time leader election.
 *
 * <p><b>Failure mode</b>: any exception in {@link #poll} (missing file /
 * parse error / validation error / unknown error) is caught and logged at
 * {@code ERROR}; the previous config is preserved, {@link #lastSeen} is
 * <b>not</b> updated so the next poll retries.
 *
 * <p><b>JDK 8 only</b>: uses {@link Files#getLastModifiedTime} +
 * {@link ScheduledExecutorService}; does <b>not</b> use
 * {@link java.nio.file.WatchService} for cross-platform stability (macOS
 * polling fallback incompatibilities — see dsh §14.8 N8 design rationale).
 *
 * <p>🆕 Story #007 — part of the AC-06 hot-reload contract (spec §FR-003 /
 * FR-004 / FR-007 / FR-008).
 *
 * @see AgentConfigRegistry
 * @see AgentFactory#loadYamlAndValidate(Path)
 */
@Component
public class YamlWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(YamlWatcher.class);

    private final Path ymlPath;
    private final AgentConfigRegistry registry;
    private final AgentFactory factory;
    private final long pollIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Last observed mtime in millis; 0 means "never seen". Updated only on successful reload. */
    private volatile long lastSeen;

    /**
     * Spring-injected production constructor.
     *
     * @param ymlPathValue the yml file path (default {@code application.yml},
     *                     relative to the working directory)
     */
    public YamlWatcher(
            @Value("${spring.config.location:application.yml}") String ymlPathValue,
            AgentConfigRegistry registry,
            AgentFactory factory) {
        this(Paths.get(ymlPathValue), registry, factory, 5L);
    }

    /**
     * Test-only / package-private constructor that exposes the poll interval
     * so unit tests can run without waiting 5 s wall-clock.
     */
    YamlWatcher(Path ymlPath, AgentConfigRegistry registry, AgentFactory factory,
                long pollIntervalSeconds) {
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

    /**
     * Start the polling scheduler. Spring {@code @PostConstruct} hook.
     *
     * <p>Captures the initial mtime as {@link #lastSeen} so we don't double-fire
     * on the very first poll. If the file is absent at start we set
     * {@code lastSeen = 0} and retry on every poll.
     */
    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;  // already started
        }
        try {
            this.lastSeen = Files.getLastModifiedTime(ymlPath).toMillis();
            LOG.info("YamlWatcher started: polling {} every {}s (initial mtime={})",
                ymlPath, pollIntervalSeconds, lastSeen);
        } catch (NoSuchFileException e) {
            lastSeen = 0L;
            LOG.warn("YamlWatcher start: {} not found, will retry on first poll", ymlPath);
        } catch (IOException e) {
            lastSeen = 0L;
            LOG.warn("YamlWatcher start: cannot stat {}, will retry on first poll",
                ymlPath, e);
        }
        scheduler.scheduleWithFixedDelay(
            this::pollSafe,
            pollIntervalSeconds,
            pollIntervalSeconds,
            TimeUnit.SECONDS);
    }

    /**
     * Stop the polling scheduler. Spring {@code @PreDestroy} hook.
     *
     * <p>Triggers {@link ScheduledExecutorService#shutdownNow()} and waits up
     * to 5 s for the worker to terminate.
     */
    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("YamlWatcher scheduler did not terminate in 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ensure {@link #poll()} never throws (watcher must survive all errors). */
    private void pollSafe() {
        try {
            poll();
        } catch (Throwable t) {
            LOG.error("YamlWatcher poll failed unexpectedly; continuing", t);
        }
    }

    /**
     * Poll yml mtime once; reload + validate + publish on change.
     *
     * <p>Synchronized so concurrent invocations cannot race. Package-private
     * for unit tests that want to drive {@code poll()} deterministically
     * (instead of waiting for the scheduler).
     *
     * <p>Failure semantics (per spec §FR-007 + AC-06 R-03 mitigation):
     * <ul>
     *   <li>Missing file → WARN, skip, lastSeen unchanged → retry next time</li>
     *   <li>IO error → ERROR, skip, lastSeen unchanged → retry next time</li>
     *   <li>YAML parse / validation failure → ERROR, skip, lastSeen unchanged → retry</li>
     *   <li>Unexpected error → caught in {@link #pollSafe()}, watcher keeps running</li>
     *   <li>Successful reload → publish + lastSeen updated</li>
     * </ul>
     */
    synchronized void poll() {
        if (!running.get()) return;
        long current;
        try {
            current = Files.getLastModifiedTime(ymlPath).toMillis();
        } catch (NoSuchFileException e) {
            LOG.warn("yml file missing at {}, skipping publish", ymlPath);
            return;  // do NOT update lastSeen; retry next time file exists
        } catch (IOException e) {
            LOG.error("cannot stat yml file at {}, skipping publish", ymlPath, e);
            return;
        }
        if (current <= lastSeen) return;  // no change since last successful reload

        AgentConfig next;
        try {
            next = factory.loadYamlAndValidate(ymlPath);
        } catch (Exception e) {
            LOG.error("config validation failed, keeping previous config", e);
            return;  // lastSeen unchanged → next poll retries
        }

        // Atomic publish — registry.publish is the only write path
        registry.publish(next);
        lastSeen = current;
        LOG.info("config reloaded: provider={} model={} whitelist={}",
            next.getLlm().getProvider(),
            next.getLlm().getModel(),
            next.getSandbox().getCommandWhitelist());
    }

    /** Test-only accessor — current {@link #lastSeen} value (millis since epoch). */
    long getLastSeen() {
        return lastSeen;
    }
}