package ai.lingshu.core.message;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Provider-agnostic prompt fed to {@code LlmProvider.stream}.
 *
 * <p>Three concerns are split into independent fields so caching strategies can target each:
 * <ul>
 *   <li>{@code messages} — conversation context (changes every turn)</li>
 *   <li>{@code tools} — capability catalog (changes when Skill / MCP / plugin changes)</li>
 *   <li>{@code hints} — invocation parameters (changes with config drift)</li>
 * </ul>
 *
 * <p>See dsh §4.5.1 for why {@code tools} is its own field rather than stuffed into the system
 * message text. Provider-specific serialization happens inside {@code LlmProvider}.
 */
@Value
@Builder
public class Prompt {
    /** Ordered conversation: system blocks + history + current user input. */
    List<Message> messages;
    /** Tool schemas (name + description + JSON schema); empty = model has no function-calling entry. */
    List<ToolSpec> tools;
    /** Model id + temperature + max tokens; nulls defer to provider defaults. */
    ModelHints hints;
}