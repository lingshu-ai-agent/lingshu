package ai.lingshu.core.agent;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Story #023 — Closed enum of {@code Task} tool sub-agent types
 * (dsh v1.5.40 §6.6 L5033-5052).
 *
 * <p>Three values ship by default, mirroring the Claude Code fixed set:
 * <ul>
 *   <li>{@link #EXPLORE} — read-only reconnaissance, fast haiku model</li>
 *   <li>{@link #ENGINEER} — full tool set, opus/sonnet model, code edits</li>
 *   <li>{@link #REVIEWER} — read-only review of changed files</li>
 * </ul>
 *
 * <p><b>Two string facets per value:</b>
 * <ul>
 *   <li>{@code configKey} — the YAML key under
 *       {@code agent.delegate.types.<configKey>} (e.g. {@code "explore"}).
 *       Used by {@link DelegateTool#loadConfigs} and {@link SubAgentInheritance}
 *       to look up the parent-supplied {@code TypeConfig} block.</li>
 *   <li>{@code promptFile} — the per-sub-agent system-prompt file name
 *       (e.g. {@code "explore.md"}) inside {@code Delegate.promptsDir}.
 *       Resolved at sub-Agent {@link ai.lingshu.core.runtime.AgentConfig#instructions}
 *       build time.</li>
 * </ul>
 *
 * <p><b>Closed set</b> — adding a new type requires editing this enum and bumping
 * the {@code Story #023.1} minor version; users cannot extend at runtime
 * (OQ-Future {@code OQ-#023-F}).
 *
 * <p><b>JDK 8 compatibility</b> — plain enum (no {@code sealed} / record /
 * pattern-matching switches). Stream collectors use {@code Collectors.toCollection(LinkedHashSet::new)}
 * to preserve declaration order for predictable LLM-visible enum values.
 *
 * @since 1.0.0
 */
public enum SubAgentType {

    /** Read-only reconnaissance; haiku-class model; runs in a fresh session. */
    EXPLORE("explore", "explore.md"),

    /** Full engineer (read + write + bash); sonnet/opus class. */
    ENGINEER("engineer", "engineer.md"),

    /** Read-only reviewer of modified files (post-engineer pass). */
    REVIEWER("reviewer", "reviewer.md");

    /** YAML key under {@code agent.delegate.types.<configKey>}. */
    private final String configKey;

    /** System-prompt file name (resolved against {@code Delegate.promptsDir}). */
    private final String promptFile;

    SubAgentType(String configKey, String promptFile) {
        this.configKey = configKey;
        this.promptFile = promptFile;
    }

    /** YAML key under {@code agent.delegate.types.<configKey>}. */
    public String configKey() {
        return configKey;
    }

    /** System-prompt file name (resolved against {@code Delegate.promptsDir}). */
    public String promptFile() {
        return promptFile;
    }

    /**
     * Conventional alias used in {@link DelegateTool#description()} —
     * same as {@link #configKey()} for now; kept as a separate method so future
     * versions can rename one without breaking the other.
     */
    public String key() {
        return configKey;
    }

    /**
     * Resolve a configKey string back to its enum value (case-sensitive).
     *
     * @param configKey the YAML key, e.g. {@code "explore"}; must not be null
     * @return the matching {@link SubAgentType}
     * @throws IllegalArgumentException if the key does not match any declared value;
     *         message format: {@code "Unknown subagent_type: <key> (known: [explore, engineer, reviewer])"}
     */
    public static SubAgentType fromKey(String configKey) {
        if (configKey == null) {
            throw new IllegalArgumentException(
                "Unknown subagent_type: null (known: " + allKeys() + ")");
        }
        for (SubAgentType t : values()) {
            if (t.configKey.equals(configKey)) {
                return t;
            }
        }
        throw new IllegalArgumentException(
            "Unknown subagent_type: " + configKey + " (known: " + allKeys() + ")");
    }

    /**
     * Set of all declared {@link #configKey()} values, in declaration order.
     * Used by validation paths and LLM-visible enum lists (e.g. {@code DelegateTool.description()}).
     *
     * <p>Returns a fresh {@link LinkedHashSet} on each call (defensive copy of immutable enum state).
     *
     * @return unmodifiable LinkedHashSet of declared configKeys, never null, never empty
     */
    public static Set<String> allKeys() {
        return Arrays.stream(values())
            .map(SubAgentType::configKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
