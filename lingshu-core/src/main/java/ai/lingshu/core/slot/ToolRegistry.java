package ai.lingshu.core.slot;

import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.spi.ContractVersionRef;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Slot 2 registry half — a process-wide name → {@link Tool} map shared by all
 * {@link ToolExecutor} instances (dsh §4.6 + §5.5).
 *
 * <p><b>Why a separate SPI:</b> previously {@link ToolExecutor} implementations
 * owned the tool registry internally (see {@code DefaultToolExecutor#register}).
 * That made {@code LocalToolsAutoConfiguration} have to depend on the concrete
 * {@code DefaultToolExecutor} class instead of the {@link ToolExecutor} SPI —
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
 *
 * <p><b>🆕 Story #020a — Skill second index:</b> the registry now maintains TWO indices:
 * the canonical {@code Map<String, Tool>} (all registered tools) and a parallel
 * {@code Map<String, Skill>} (only {@link Skill Skill}-typed tools). Skills are
 * {@link Tool tools} at the dispatch level — they share the same {@code name()},
 * {@code inputSchema()}, {@code execute()} contract — but they have an additional
 * discoverability channel: the CLI {@code /xxx} dispatcher (Story #020c) calls
 * {@link #findSkill(String)}, and the prompt builder reads
 * {@link #modelVisibleSpecs()} to feed {@link Skill skills'} schemas to the LLM
 * just like any other {@link Tool}.
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

    // ─────────────────────────────────────────────────────────────────────
    //  🆕 Story #020a — Skill-aware extensions
    // ─────────────────────────────────────────────────────────────────────

    /**
     * All registered {@link Tool Tools} (including {@link Skill Skills}) as
     * {@link ToolSpec ToolSpecs} — for {@code PromptBuilder.build()} to inject into the
     * LLM prompt (dsh §4.5.1 [TOOL SCHEMAS] + §6.4 L3989).
     *
     * <p>Implementations SHOULD return a stable-sorted list (e.g. by name) so that
     * {@code PromptBuilder}'s prompt content is deterministic — this is a prerequisite
     * for upstream LLM provider prompt cache hits (cf. #009d
     * {@code RemoteAgentSchemaBuilder} sort-by-{@code (agentName, skillId)}).
     *
     * @return a list, possibly empty, of all registered Tools as {@code ToolSpec}s.
     *         The list is a snapshot — mutation by the caller is unsupported.
     */
    List<ToolSpec> modelVisibleSpecs();

    /**
     * Find a registered {@link Skill} by its {@link Skill#name() name()} — for the CLI
     * {@code /xxx} dispatcher (dsh §6.4 L3998 + Story #020c).
     *
     * <p>Returns {@code null} when no Skill matches — the CLI dispatcher uses this to
     * print "Unknown command: /xxx — Available: [...]" without exception flow.
     *
     * <p>Note: a {@link Skill} IS a {@link Tool}, so it also appears in {@link #lookup(String)}.
     * {@code findSkill} is a typed shortcut for callers that specifically want the Skill view
     * (e.g. to enumerate them in CLI auto-completion).
     *
     * @param name the Skill name (must equal {@link Skill#name()})
     * @return the Skill, or {@code null} if not registered as a Skill (may be registered as
     *         a plain Tool only)
     */
    Skill findSkill(String name);

    /**
     * All currently registered Skill names (dsh §6.4 L4001) — for CLI auto-completion and
     * startup-log dump.
     *
     * <p>Returns an unmodifiable view that may be a snapshot (callers MUST NOT assume
     * liveness for iteration).
     *
     * @return a set, possibly empty, of Skill names
     */
    Set<String> skillNames();

    /**
     * Strong-typed lookup that throws {@link IllegalArgumentException} when the name is
     * not registered — for callers that want a fail-fast contract (dsh §6.4 L4007-4008).
     *
     * <p>Contrast with {@link #lookup(String)}:
     * <ul>
     *   <li>{@code lookup(name)} → {@code Tool} or {@code null}; {@code ToolExecutor.dispatch}
     *       uses this and translates {@code null} into
     *       {@link ToolException.ToolNotFoundException} per the dispatch 5-step pipeline
     *       (dsh §4.10.1 硬规则 2).</li>
     *   <li>{@code findByName(name)} → {@code Tool} or throws {@link IllegalArgumentException};
     *       the CLI dispatcher (Story #020c) and other registry-API consumers use this
     *       when {@code null}-handling is undesired.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if no tool is registered under {@code name}
     */
    Tool findByName(String name);

    /**
     * 🆕 Story #021b — Unregister a previously-registered tool by name.
     *
     * <p>Used by {@code McpTransport#onConnectionStateChange} when a connection goes
     * {@link ai.lingshu.core.mcp.ConnectionState#DISCONNECTED} /
     * {@link ai.lingshu.core.mcp.ConnectionState#FAILED} — stale MCP tools must be removed
     * so {@link ToolExecutor#dispatch} does not route to a dead {@code McpToolAdapter}
     * (dsh §6.5 (2) L4536-4539).
     *
     * <p><b>Symmetric contract</b> with {@link #register(Tool)}: a name registered via
     * {@code register} must be removable via {@code unregister} with the same key.
     *
     * <p><b>No-op semantics</b>: removing a name that was never registered (or already
     * removed) is a silent no-op (returns {@code false}). This avoids forcing the MCP
     * state-machine listener to track prior registration state across reconnects
     * (Story #021a dsh §6.5 (2.1) L4536).
     *
     * <p><b>Thread-safe</b> — implementations MUST support concurrent calls from
     * {@code McpTransport}'s listener thread + the Tool dispatch threads.
     *
     * <p><b>Skill dual-index</b> — if the unregistered tool was also a {@link Skill},
     * the parallel skill index must be cleared in lock-step (mirrors
     * {@link #register(Tool)}).
     *
     * @param name the tool's {@link Tool#name()} (with namespace, e.g.
     *             {@code "github:search_repos"})
     * @return {@code true} if a tool was removed; {@code false} if the name was not
     *         registered, or {@code name} was {@code null}
     * @since 1.0.0
     */
    boolean unregister(String name);
}
