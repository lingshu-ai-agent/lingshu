package ai.lingshu.core.slot;

import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.spi.ContractVersionRef;

/**
 * Static / dynamic memory source feeding {@code [PROJECT MEMORY]} (dsh §4.5).
 *
 * <p>Each source contributes one block of text (or {@code null} if it has nothing to add this turn).
 * {@link PromptBuilder} orders sources by ascending {@link #priority()} — small numbers come first,
 * so e.g. {@code IdentityMemorySource} (priority 0) leads, {@code ProjectTreeMemorySource}
 * (priority 10) follows, and a hypothetical debug source (priority 1000) goes last.
 *
 * <p>Session history is NOT a {@code MemorySource} — it's growable mutable state owned by
 * the engine, not a static config-driven block (see dsh §4.5 tail paragraph).
 */
public interface MemorySource {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /** Stable identifier used for configuration ({@code agent.prompt.memory-sources: [identity, project-tree]}). */
    String name();

    /** Sort key — smaller values place the block earlier in the assembled system prompt. */
    int priority();

    /**
     * Produce this source's contribution for the current turn.
     *
     * @param ctx turn context (read-only — do not mutate session history)
     * @return the block text, or {@code null} if the source has nothing to contribute this turn
     *         (null is filtered out by {@code SlotResolver.memorySources})
     */
    String load(TurnContext ctx);
}