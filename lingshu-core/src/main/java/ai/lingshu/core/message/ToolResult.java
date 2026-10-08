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

import lombok.Builder;
import lombok.Value;

/**
 * Result value object returned from {@link ai.lingshu.core.slot.Tool#execute} and
 * {@link ai.lingshu.core.slot.ToolExecutor#dispatch}.
 *
 * <p>Status semantics (per dsh §4.10.1 硬规则 2):
 * <ul>
 *   <li>{@link Status#SUCCESS} — normal completion, {@code content} is the output</li>
 *   <li>{@link Status#ERROR} — failed but recoverable, {@code content} holds the error message</li>
 *   <li>{@link Status#CANCELLED} — cancellation token fired, no retry</li>
 * </ul>
 *
 * <p>Distinct from {@link Message.ToolResult}, which is a Message subtype that travels
 * through session history. {@code ToolResult} → {@code Message.ToolResult} conversion happens
 * in {@code DefaultTurnContext.appendToolResult}.
 */
@Value
@Builder
public class ToolResult {
    /** Status enum. */
    Status status;
    /** Echoes back {@code ToolCall.id} for correlation in the next LLM message. */
    String toolUseId;
    /** Output text (or error message when {@code status == ERROR}). */
    String content;
    /** Convenience flag — {@code true} iff the tool failed. Mirrors {@code status == ERROR}. */
    boolean isError;

    public enum Status { SUCCESS, ERROR, CANCELLED }
}