package ai.lingshu.core.message;

import lombok.Value;

/**
 * Model-level parameters: which model, sampling temperature, output cap.
 *
 * <p>Integer/Double boxes are used (not primitives) so any field can be null,
 * meaning "leave it to the provider default". Primitive defaults would silently
 * override the provider's own tuning.
 */
@Value
public class ModelHints {
    /** Model id string, e.g. {@code "claude-3-5-sonnet-latest"}, {@code "gpt-4o"}, {@code "deepseek-chat"}. */
    String model;
    /** Sampling temperature; {@code null} → provider default. */
    Double temperature;
    /** Max output tokens; {@code null} → provider default. */
    Integer maxTokens;
}