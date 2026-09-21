package ai.lingshu.core.reload;

import ai.lingshu.core.runtime.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for the current {@link AgentConfig} in this JVM
 * (Story #007, AC-06 dssh §14.8 N8).
 *
 * <p>Holds the latest config in a single-writer / multi-reader lock-free
 * {@link AtomicReference}; {@link #publish(AgentConfig)} is the only write path
 * (typically called by {@link YamlWatcher}); {@link #current()} is the read path
 * used by {@link ai.lingshu.core.impl.runtime.DefaultAgent} at turn entry.
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li><b>Write</b> — {@link #publish(AgentConfig)}: single writer; uses
 *       {@code AtomicReference.getAndSet} so the previous value is atomically
 *       snapshotted for listeners.</li>
 *   <li><b>Read</b> — {@link #current()}: multi-reader; lock-free
 *       {@code AtomicReference.get} with volatile-load memory barrier; readers
 *       always observe a fully-constructed {@link AgentConfig}.</li>
 *   <li><b>Listeners</b> — {@link CopyOnWriteArrayList}; read-mostly; added at
 *       Spring {@code @PostConstruct} / plugin {@code @Configuration}, rarely
 *       removed.</li>
 * </ul>
 *
 * <h3>Freeze semantics</h3>
 * <p>Code that wants to freeze the config for the lifetime of a turn should
 * read {@link #current()} ONCE at the entry point and use the local variable
 * thereafter; Java reference semantics + {@code @Value} immutability guarantee
 * freeze without any explicit snapshot copy (per dsh §14.8 N8 / R-03 mitigation).
 *
 * <h3>Listener exception isolation</h3>
 * <p>A listener {@code onConfigChange} throwing any {@link Throwable} is caught
 * and logged at {@code ERROR}; publish main flow continues and
 * {@link #current()} returns the new config to all subsequent readers.
 *
 * <h3>Re-entrancy forbidden</h3>
 * <p>Listeners <b>must not</b> call {@code registry.publish(...)} from within
 * {@link ConfigChangeListener#onConfigChange} — see {@link ConfigChangeListener}
 * Javadoc.
 *
 * <p>🆕 Story #007 — part of the AC-06 hot-reload contract (spec §FR-001 / FR-002).
 *
 * @see ConfigChangeListener
 * @see YamlWatcher
 */
@Component
public class AgentConfigRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AgentConfigRegistry.class);

    private final AtomicReference<AgentConfig> currentRef = new AtomicReference<>();
    private final CopyOnWriteArrayList<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Auto-publish the initial config wired in at Spring bean construction time.
     *
     * <p>Called from {@code @PostConstruct} (or by the Spring auto-configuration
     * factory) once the {@link AgentConfig} bean has been bound. After this call
     * {@link #current()} is guaranteed non-null for the lifetime of the registry.
     *
     * @param initial the config to publish as the baseline; must not be null
     * @throws NullPointerException if {@code initial} is null
     */
    public void publishInitial(AgentConfig initial) {
        if (initial == null) {
            throw new NullPointerException("initial config must not be null");
        }
        publish(initial);
    }

    /**
     * Atomically replace the current config with {@code next}.
     *
     * <p>Single-writer semantics — typically invoked only by the
     * {@link YamlWatcher} daemon scheduler thread. After this method returns,
     * {@link #current()} returns {@code next} for all subsequent readers.
     *
     * <p>Listeners are invoked synchronously <b>in registration order</b>;
     * a listener throwing any {@link Throwable} is caught and logged at
     * {@code ERROR} — the publish main flow continues and {@link #current()}
     * still returns {@code next} for subsequent readers (no rollback).
     *
     * @param next the new config; must not be null
     * @throws NullPointerException if {@code next} is null
     */
    public void publish(AgentConfig next) {
        if (next == null) {
            throw new NullPointerException("next config must not be null");
        }
        final AgentConfig prev = currentRef.getAndSet(next);
        for (ConfigChangeListener l : listeners) {
            try {
                l.onConfigChange(prev, next);
            } catch (Throwable t) {
                LOG.error("ConfigChangeListener {} threw, continuing publish",
                    l.getClass().getName(), t);
            }
        }
    }

    /**
     * Return the current config (lock-free volatile load).
     *
     * <p>Should be called <b>once per turn entry</b> by
     * {@link ai.lingshu.core.impl.runtime.DefaultAgent} to leverage freeze
     * semantics — the local reference is then used for the rest of the turn.
     *
     * @return the currently published config; never null after
     *         {@link #publishInitial(AgentConfig)} has been invoked
     */
    public AgentConfig current() {
        return currentRef.get();
    }

    /**
     * Register a listener to be invoked on every subsequent
     * {@link #publish(AgentConfig)}.
     *
     * @param listener the listener to add; must not be null
     * @throws NullPointerException if {@code listener} is null
     */
    public void addListener(ConfigChangeListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener must not be null");
        }
        listeners.add(listener);
    }

    /**
     * Unregister a previously added listener. No-op if {@code listener} was
     * never registered (or has already been removed).
     *
     * @param listener the listener to remove; may be null (in which case
     *                 the call is a no-op)
     * @return {@code true} if the listener was present and removed;
     *         {@code false} otherwise
     */
    public boolean removeListener(ConfigChangeListener listener) {
        if (listener == null) {
            return false;
        }
        return listeners.remove(listener);
    }

    /** Test-only: number of registered listeners. Package-private for unit tests. */
    int listenerCount() {
        return listeners.size();
    }
}