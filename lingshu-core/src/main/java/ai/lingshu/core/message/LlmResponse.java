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

import java.util.List;

/**
 * Final structured response from {@code LlmProvider.stream}, returned via {@code CompletableFuture}
 * while the same provider may concurrently push {@code TextDelta} / {@code ToolStarted} events
 * to the sink (two-channel pattern, see dsh §4.10).
 */
@Value
public class LlmResponse {
    /** Accumulated text from the streaming response (may be empty if model went straight to tool calls). */
    String text;
    /** Tool calls the model wants executed; engine dispatches each via {@code ToolExecutor.dispatch}. */
    List<ToolCall> toolCalls;
    /** Why the model stopped emitting tokens. */
    StopReason stopReason;
    /** Token accounting for this single LLM call. */
    Usage usage;
}