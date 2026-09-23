package ai.lingshu.core.impl.config;

import ai.lingshu.core.runtime.AgentConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory for the default {@link AgentConfig} (Story #001 AC-01-2: "empty yml must boot").
 *
 * <p>Every field is populated. Slots that have no provider registered yet get a sentinel
 * {@code "default"} name (the Router will fail-fast at startup with a clear "Unknown X"
 * message if no provider is registered, see dsh §5.2). The 27-field default is documented
 * field-by-field in {@link AgentConfig}.
 */
public final class AgentConfigDefaults {

    /** Default name used by every Slot's YAML config key — Router resolves it. */
    public static final String DEFAULT_NAME = "default";

    /** Linear turn engine (Story #001 default; alternatives: {@code "adk"}, {@code "alibaba-graph"} later). */
    public static final String FLOW_ENGINE_LINEAR = "linear";

    /** Anthropic LlmProvider (Story #001 default; OpenAI/Gemini/DeepSeek alternatives in Story #003). */
    public static final String LLM_PROVIDER_ANTHROPIC = "anthropic";

    private AgentConfigDefaults() {}

    /**
     * Build an {@link AgentConfig} that satisfies the "empty yml must boot" requirement.
     * All collections are immutable ({@link Collections#emptyList()} / {@link Collections#emptyMap()}).
     */
    public static AgentConfig defaults() {
        return new AgentConfig(
            FLOW_ENGINE_LINEAR,
            new AgentConfig.Llm(
                LLM_PROVIDER_ANTHROPIC,
                "claude-3-5-sonnet-latest",
                8192,
                1.0),
            new AgentConfig.Prompt(
                DEFAULT_NAME,
                Collections.<String>emptyList(),
                null),
            DEFAULT_NAME,
            new AgentConfig.Sandbox(
                DEFAULT_NAME,
                "chroot",
                java.nio.file.Paths.get(System.getProperty("user.dir")),
                Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            DEFAULT_NAME,
            DEFAULT_NAME,
            null,    // delegate
            null,    // mcp
            null,    // skills
            8,       // toolParallelism
            60,      // toolTimeoutSeconds
            300,     // approvalTimeoutSeconds
            0,       // turnTimeoutSeconds (no limit)
            60,      // llmTimeoutSeconds
            50,      // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            DEFAULT_NAME,    // a2aTransport
            null,            // tenants (Story #006 — null = single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
            AgentConfig.ToolsConfig.defaults());     // tools (Story #019)
    }

    /** Static fallback map (used by tests / debug endpoints). */
    public static Map<String, String> asStringMap() {
        Map<String, String> m = new HashMap<>();
        m.put("flowEngine", FLOW_ENGINE_LINEAR);
        m.put("llm.provider", LLM_PROVIDER_ANTHROPIC);
        m.put("toolExecutor", DEFAULT_NAME);
        m.put("compactor", DEFAULT_NAME);
        m.put("sessionStore", DEFAULT_NAME);
        m.put("a2aTransport", DEFAULT_NAME);
        m.put("prompt.builder", DEFAULT_NAME);
        m.put("sandbox.policy", DEFAULT_NAME);
        m.put("sandbox.runtime", "chroot");
        return m;
    }
}