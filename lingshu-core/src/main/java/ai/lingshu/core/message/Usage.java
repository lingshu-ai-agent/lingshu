package ai.lingshu.core.message;

import lombok.Value;

/**
 * Token accounting for a single LLM call (or aggregate over a turn).
 *
 * <p>Token counts are provider-reported; we do not compute them locally
 * (no client-side tokenizer — see dsh §17 R-13 mitigation (d) dependency rules).
 */
@Value
public class Usage {
    /** Prompt / input tokens consumed. */
    int inputTokens;
    /** Completion / output tokens produced. */
    int outputTokens;

    /** Zero-usage for stub / synthetic responses. */
    public static Usage zero() {
        return new Usage(0, 0);
    }

    /** Element-wise sum — used to accumulate per-step usage into a turn total. */
    public Usage plus(Usage other) {
        return new Usage(this.inputTokens + other.inputTokens,
                         this.outputTokens + other.outputTokens);
    }
}