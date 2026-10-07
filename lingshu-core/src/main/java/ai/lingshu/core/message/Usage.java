/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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