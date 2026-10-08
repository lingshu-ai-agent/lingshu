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
 * Provider-agnostic tool schema passed to {@code PromptBuilder.build()} and serialized into
 * provider-specific wire format by {@code LlmProvider.stream} (§4.10).
 *
 * <p>Schema source is {@code Tool.inputSchema()} — see dsh §4.6. Three schema sources are
 * equally valid (hand-written JSON, MCP {@code tools/list}, Spring AI {@code @Tool} reflection),
 * see dsh §6.5. The contract is the same regardless of source.
 */
@Value
public class ToolSpec {
    /** Tool name as seen by the LLM (must equal {@code Tool.name()}). */
    String name;
    /** Human-readable description for the LLM to decide when to call. */
    String description;
    /** JSON Schema (draft 2020-12 / OpenAI compatible) describing the input shape. */
    JsonNode inputSchema;
}