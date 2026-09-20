package ai.lingshu.core.spi;

import ai.lingshu.core.runtime.AgentConfig;

/**
 * Base SPI contract — every Slot has one typed Provider (dsh §5.1).
 *
 * <p>The {@code T} parameter is the Slot's core interface (e.g. {@code LlmProvider},
 * {@code ToolExecutor}). Spring auto-discovers concrete Provider beans; the matching
 * {@link SlotRouter} resolves them by {@link #name()} at first use.
 *
 * <p>Three pieces of metadata are required:
 * <ul>
 *   <li>{@link #name()} — stable string for YAML configuration; must be globally unique
 *       within the Slot (dsh §5.5 v1.5.28 "唯一 Bean 名约定")</li>
 *   <li>{@link #priority()} — tie-break for same-name conflicts (dsh §5.2)</li>
 *   <li>{@link #create(AgentConfig)} — factory method; implementations are stateless
 *       so the result can be cached / pooled freely</li>
 * </ul>
 */
public interface SlotProvider<T> {

    /** Stable identifier used in {@code application.yml} ({@code agent.<slot>.name: <value>}). */
    String name();

    /** Higher value wins on name conflicts; same name + same priority → registration order. */
    int priority();

    /**
     * Build the Slot instance for the given config. Called once per turn by the Router
     * (no caching at this level — implementations should be cheap to instantiate).
     */
    T create(AgentConfig config);
}