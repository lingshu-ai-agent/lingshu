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
package ai.lingshu.core.runtime;

import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import lombok.Value;

/**
 * Synchronous terminal result of {@link Agent#runBlocking}. Mirrors the fields of the last
 * emitted {@link ai.lingshu.core.event.AgentEvent.TurnCompleted} event.
 *
 * <p>{@code turns} counts how many user input iterations the agent went — typically 1 for
 * a single Q&amp;A but grows when the agent loops (ReAct on multiple tool calls, multi-step
 * planning, etc.).
 */
@Value
public class RunResult {
    /** Final assistant text (may be empty if the agent stopped after tool calls). */
    String finalText;
    /** Number of user-input iterations completed. */
    int turns;
    /** Aggregate token usage over the whole turn. */
    Usage totalUsage;
    /** Why the turn terminated. */
    StopReason stopReason;
    /** Wall-clock duration of the turn in milliseconds. */
    long elapsedMillis;
}