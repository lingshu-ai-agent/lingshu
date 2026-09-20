package ai.lingshu.core.slot;

import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.TurnContext;

/**
 * Slot 7 — Assembles the {@link Prompt} (messages + tools + hints) the LLM will see (dsh §4.5).
 *
 * <p>Default implementation composes five orthogonal blocks in fixed order (dsh §4.5.1):
 * <ol>
 *   <li>{@code [ROLE]} — identity.name + role + traits + tone + language</li>
 *   <li>{@code [INSTRUCTIONS]} — file or inline + mustache rendering</li>
 *   <li>{@code [PROJECT MEMORY]} — CLAUDE.md (project + user) + extras</li>
 *   <li>{@code [CONVERSATION HISTORY]} — session messages before the current user input</li>
 *   <li>{@code [USER MESSAGE]} — the current input</li>
 * </ol>
 *
 * <p>Tool schemas are returned in the {@link Prompt#tools} field (not stuffed into the system
 * text) so that prompt-cache breakpoints can hit system independently of tool schema churn
 * (see dsh §4.5.1 note).
 *
 * <p>Alternative implementations: RAG-augmented builders (fetch top-K docs first), JSON-mode
 * builders (force structured output), etc. All implement {@code build(ctx)} with no other
 * required methods.
 */
public interface PromptBuilder {

    /**
     * Build the prompt for the current turn.
     *
     * @param ctx turn context (session + config + sink + userInput + done flag)
     * @return fully assembled prompt ready for {@link LlmProvider#stream}
     */
    Prompt build(TurnContext ctx);
}