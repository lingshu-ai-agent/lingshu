package ai.lingshu.core.reload;

import ai.lingshu.core.runtime.AgentConfig;

/**
 * Receives synchronous notifications whenever {@link AgentConfigRegistry#publish}
 * replaces the current {@link AgentConfig} with a new one.
 *
 * <h3>Threading</h3>
 * <p>Called synchronously from whichever thread invokes
 * {@code AgentConfigRegistry.publish(...)} — typically the
 * {@link YamlWatcher} daemon scheduler thread at 5 s intervals. Implementations
 * should return quickly and avoid spawning background threads that might
 * recursively publish.
 *
 * <h3>Exception isolation</h3>
 * <p>Throwing any {@link Throwable} is allowed; {@link AgentConfigRegistry#publish}
 * catches the throwable, logs at {@code ERROR}, and continues to the next
 * listener — publish main flow is <b>not</b> rolled back, and subsequent readers
 * of {@link AgentConfigRegistry#current()} still observe the new config.
 *
 * <h3>Re-entrancy forbidden</h3>
 * <p>Implementations <b>must not</b> call {@code registry.publish(...)} from within
 * {@link #onConfigChange} — this would cause an unbounded re-entrant listener
 * loop and is explicitly disallowed by the contract.
 *
 * <h3>Idempotency</h3>
 * <p>Implementations should be idempotent (the same {@code prev} / {@code next}
 * pair may be processed twice in test scenarios; in production each publish
 * carries a freshly built {@link AgentConfig}, so pair-equality is the only
 * natural signal of a duplicate).
 *
 * <p>🆕 Story #007 — part of the AC-06 hot-reload contract (spec §FR-002).
 *
 * @see AgentConfigRegistry
 * @see YamlWatcher
 */
public interface ConfigChangeListener {

    /**
     * Notification that {@code registry} has atomically replaced
     * {@code previous} with {@code next}.
     *
     * @param previous the config the registry was holding immediately prior to this
     *                  publish; never {@code null} after initial publish
     * @param next      the new config just installed; never {@code null}
     */
    void onConfigChange(AgentConfig previous, AgentConfig next);
}