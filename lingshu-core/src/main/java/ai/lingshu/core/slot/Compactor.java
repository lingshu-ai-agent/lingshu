package ai.lingshu.core.slot;

import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.spi.ContractVersionRef;

/**
 * Slot 6 — History compactor (dsh §4.9). Reduces conversation length when it grows past
 * the model / cost / latency budget.
 *
 * <p>Two-phase contract:
 * <ol>
 *   <li>{@link #shouldCompact(Prompt)} — pure predicate, called before each LLM request.
 *       Cheap to compute (token estimate, message count, age of oldest message).</li>
 *   <li>{@link #compact(TurnContext)} — invoked when the predicate returns {@code true}.
 *       Mutates {@code ctx.session().history()} by replacing older messages with a summary
 *       or pruning tool calls. Idempotent — running it twice in a row is a no-op the second time.</li>
 * </ol>
 *
 * <p>The default implementation ({@code TruncatingCompactor}) drops oldest tool results when
 * the prompt exceeds {@code compactAtTokens}. Story #011 + follow-up work adds a summarization-based
 * compactor that calls the LLM to write the truncated messages into a single summary message.
 */
public interface Compactor {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Decide whether to compact before the next LLM call.
     *
     * @param prompt the about-to-be-sent prompt; cheap inspection only — do NOT mutate
     * @return {@code true} if {@link #compact} should run before sending this prompt
     */
    boolean shouldCompact(Prompt prompt);

    /**
     * Mutate {@code ctx.session().history()} to reduce its size (e.g. drop oldest tool results
     * or replace N messages with one summary). Must NOT throw on already-compacted sessions.
     */
    void compact(TurnContext ctx);
}