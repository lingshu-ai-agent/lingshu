package ai.lingshu.core.slot;

import ai.lingshu.core.spi.ContractVersionRef;

import java.util.Collection;

/**
 * Slot 2 registry half — a process-wide name → {@link Tool} map shared by all
 * {@link ToolExecutor} instances (dsh §4.6 + §5.5).
 *
 * <p><b>Why a separate SPI:</b> previously {@link ToolExecutor} implementations
 * owned the tool registry internally (see {@code DefaultToolExecutor#register}).
 * That made {@code LocalToolsAutoConfiguration} have to depend on the concrete
 * {@code DefaultToolExecutor} class instead of the {@code ToolExecutor} SPI —
 * swapping out the executor implementation (e.g. to {@code ParallelToolExecutor})
 * silently broke auto-tool-registration. With a dedicated {@code ToolRegistry},
 * the executor looks up tools via injection, the auto-configuration registers
 * them via the same injection, and the two concerns are independent.
 *
 * <p><b>Lifecycle:</b> a single {@code ToolRegistry} bean is shared per JVM
 * (typically exposed by {@code LocalToolsAutoConfiguration} as a Spring
 * {@code @Bean}). {@link ToolExecutor} Providers pull the same singleton in via
 * {@code @Autowired} when constructing per-turn executors.
 *
 * <p><b>Concurrency:</b> {@link #register(Tool)} MUST be safe to call from
 * multiple threads concurrently (e.g. {@code LocalToolsAutoConfiguration} +
 * MCP server connection re-registration). Implementations are expected to use
 * a {@link java.util.concurrent.ConcurrentHashMap} under the hood.
 */
public interface ToolRegistry {

    /** Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Register a tool. Duplicate {@link Tool#name()} registrations are logged
     * and the FIRST registration is kept (matches the prior
     * {@code DefaultToolExecutor#register} semantics).
     */
    void register(Tool tool);

    /**
     * Look up a tool by its {@link Tool#name()}. Returns {@code null} if not
     * registered — callers translate {@code null} into
     * {@link ToolException.ToolNotFoundException} per the dispatch contract.
     */
    Tool lookup(String name);

    /**
     * All currently registered tool names. Returns an unmodifiable view that
     * may be a snapshot (callers MUST NOT assume liveness for iteration).
     */
    Collection<String> names();
}