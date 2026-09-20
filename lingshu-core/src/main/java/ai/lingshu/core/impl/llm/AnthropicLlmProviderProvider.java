package ai.lingshu.core.impl.llm;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.spi.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default Provider for Slot 1 ({@link ai.lingshu.core.slot.LlmProvider}) — Anthropic-protocol
 * LLM, called directly via JDK HttpClient (see {@link AnthropicLlmProvider} for rationale).
 *
 * <p>Configuration surface:
 * <ul>
 *   <li>{@code ANTHROPIC_AUTH_TOKEN} or {@code ANTHROPIC_API_KEY} env var</li>
 *   <li>{@code ANTHROPIC_BASE_URL} env var (e.g. <code>https://api.minimax.cn/anthropic</code>;
 *       <code>/v1/messages</code> is appended automatically)</li>
 *   <li>{@link AgentConfig.Llm#getModel()} / {@link AgentConfig.Llm#getMaxTokens()} /
 *       {@link AgentConfig.Llm#getTemperature()} (from YAML under {@code agent.llm.*})</li>
 * </ul>
 *
 * <p>Story #001 default name = {@code "anthropic"}, priority 0. Story #003+ adds
 * {@code openai}, {@code gemini}, {@code deepseek}, etc.
 */
@Component
public class AnthropicLlmProviderProvider implements Providers.LlmProviderProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicLlmProviderProvider.class);

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    @Override public String name() { return "anthropic"; }

    @Override public int priority() { return 0; }

    /** 🆕 Story #003 — contract version must match {@link ai.lingshu.core.slot.LlmProvider#CONTRACT_VERSION}. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public ai.lingshu.core.slot.LlmProvider create(AgentConfig config) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                "ANTHROPIC_AUTH_TOKEN (or ANTHROPIC_API_KEY) not set. Set one of those "
                    + "environment variables before starting the agent.");
        }

        String baseUrl = resolveBaseUrl();
        String anthropicVersion = System.getenv("ANTHROPIC_VERSION");
        if (anthropicVersion == null || anthropicVersion.isEmpty()) {
            anthropicVersion = "2023-06-01";
        }

        // Allow env-var override of the configured model so AC-01-1 verification can target
        // a non-Anthropic-compatible proxy without editing YAML. Shipped AgentConfigDefaults
        // still carries a real Anthropic model id.
        String model = config.getLlm().getModel();
        String modelOverride = System.getenv("ANTHROPIC_MODEL");
        if (modelOverride != null && !modelOverride.isEmpty()) {
            model = modelOverride;
        }

        LOG.info("AnthropicLlmProvider initialized: model={} baseUrl={}", model, baseUrl);
        return new AnthropicLlmProvider(baseUrl, apiKey, anthropicVersion, model,
            config.getLlm().getMaxTokens(), config.getLlm().getTemperature());
    }

    // ── Resolution helpers (Story #001 simplest path) ──────────────────

    private static String resolveApiKey() {
        String key = System.getenv("ANTHROPIC_AUTH_TOKEN");
        if (key != null && !key.isEmpty()) return key;
        key = System.getenv("ANTHROPIC_API_KEY");
        return (key == null || key.isEmpty()) ? null : key;
    }

    private static String resolveBaseUrl() {
        String url = System.getenv("ANTHROPIC_BASE_URL");
        if (url != null && !url.isEmpty()) return url;
        url = System.getProperty("spring.ai.anthropic.base-url");
        if (url != null && !url.isEmpty()) return url;
        return DEFAULT_BASE_URL;
    }
}
