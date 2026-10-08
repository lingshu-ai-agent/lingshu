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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;

/**
 * A single tool call emitted by the LLM (or synthesized by CLI / user).
 *
 * <p>Three fields are immutable for the lifetime of a turn:
 * <ul>
 *   <li>{@code id} — opaque correlation id, echoed back in {@code Message.ToolResult.toolUseId}.</li>
 *   <li>{@code name} — tool name; must be registered in {@code ToolRegistry}.</li>
 *   <li>{@code input} — JSON args; structure validated against {@code Tool.inputSchema()} inside {@code ToolExecutor}.</li>
 * </ul>
 */
@Value
public class ToolCall {
    /** Opaque id assigned by the LLM provider (or UUID when synthesized locally). */
    String id;
    /** Registered tool name; resolved via {@code ToolRegistry.lookup(name)} in {@code ToolExecutor.dispatch}. */
    String name;
    /** JSON args; must conform to the tool's {@code inputSchema()}. */
    JsonNode input;
}