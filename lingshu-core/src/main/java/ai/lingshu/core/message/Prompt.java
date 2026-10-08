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

import java.util.List;

/**
 * Provider-agnostic prompt fed to {@code LlmProvider.stream}.
 *
 * <p>Three concerns are split into independent fields so caching strategies can target each:
 * <ul>
 *   <li>{@code messages} — conversation context (changes every turn)</li>
 *   <li>{@code tools} — capability catalog (changes when Skill / MCP / plugin changes)</li>
 *   <li>{@code hints} — invocation parameters (changes with config drift)</li>
 * </ul>
 *
 * <p>See dsh §4.5.1 for why {@code tools} is its own field rather than stuffed into the system
 * message text. Provider-specific serialization happens inside {@code LlmProvider}.
 */
@Value
@Builder
public class Prompt {
    /** Ordered conversation: system blocks + history + current user input. */
    List<Message> messages;
    /** Tool schemas (name + description + JSON schema); empty = model has no function-calling entry. */
    List<ToolSpec> tools;
    /** Model id + temperature + max tokens; nulls defer to provider defaults. */
    ModelHints hints;
}