package ai.lingshu.core.spi;

import ai.lingshu.core.runtime.AgentConfig;

/**
 * Base SPI contract — every Slot has one typed Provider (dsh §5.1).
 *
 * <p>The {@code T} parameter is the Slot's core interface (e.g. {@code LlmProvider},
 * {@code ToolExecutor}). Spring auto-discovers concrete Provider beans; the matching
 * {@link SlotRouter} resolves them by {@link #name()} at first use.
 *
 * <p>Four pieces of metadata are required:
 * <ul>
 *   <li>{@link #name()} — stable string for YAML configuration; must be globally unique
 *       within the Slot (dsh §5.5 v1.5.28 "唯一 Bean 名约定")</li>
 *   <li>{@link #priority()} — tie-break for same-name conflicts (dsh §5.2)</li>
 *   <li>{@link #version()} — 🆕 Story #003 semver {@code MAJOR.MINOR.PATCH}, must be
 *       compatible with the Slot's {@code CONTRACT_VERSION} per
 *       {@link Version#isCompatible(String, String)}</li>
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
     * 🆕 Story #003 — Contract version (semver {@code MAJOR.MINOR.PATCH}).
     *
     * <p>Must equal the corresponding Slot interface's {@code CONTRACT_VERSION} major,
     * and Provider minor must be ≤ Slot minor (backward-compat within major).
     *
     * <p>Validation performed at {@link SlotRouter} construction time (Spring startup):
     * <ul>
     *   <li>Format check: {@link Version#parse(String)} — strict 3-segment semver,
     *       no 'v' prefix, no leading zeros, no pre-release/build metadata</li>
     *   <li>Compat check: {@link Version#isCompatible(String, String)}</li>
     * </ul>
     *
     * <p>Failure mode: {@link ProviderInitException} with {@code errorCode="LINGS-S05"},
     * cause chain ≥ 2 (e.g. {@code IllegalArgumentException} from {@link Version#parse}),
     * optional {@code hint} field for human-readable suggestion.
     */
    String version();

    /**
     * Build the Slot instance for the given config. Called once per turn by the Router
     * (no caching at this level — implementations should be cheap to instantiate).
     */
    T create(AgentConfig config);
}