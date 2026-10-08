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
package ai.lingshu.core.slot;

import lombok.Value;

/**
 * Per-call resource limits applied by {@link ToolExecutor} before delegating to a {@link Tool}.
 *
 * <p>Sourced from {@code AgentConfig.toolTimeoutSeconds} + per-call overrides.
 * Zero in any field means "no limit" (intentional, not default — defaults live in
 * {@code AgentConfig}, not here).
 */
@Value
public class ToolCallConfig {
    /** Single-call timeout (seconds). {@code 0} = no timeout. */
    int timeoutSeconds;
    /** Token budget for LLM-emitting tools (read tools, summarizers). {@code 0} = no budget. */
    int maxTokens;
    /** Cost ceiling (USD × 1e6). {@code 0} = no ceiling. */
    int maxCostMicros;
}