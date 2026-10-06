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

/**
 * Reason a turn stopped. Drives downstream flow in {@code FlowEngine} (compact / cancel / error).
 *
 * @see ai.lingshu.core.runtime.FlowEngine
 */
public enum StopReason {
    /** Model returned end_turn / stop — natural finish. */
    END_TURN,
    /** Model emitted tool calls — engine must dispatch then loop. */
    TOOL_USE,
    /** Hit the configured max_tokens limit before finishing. */
    MAX_TOKENS,
    /** Compactor truncated the history mid-turn. */
    COMPACTED,
    /** User pressed Ctrl+C / engine.markDone() / timeout cascaded. */
    CANCELLED,
    /** Unhandled exception surfaced to sink. */
    ERROR
}