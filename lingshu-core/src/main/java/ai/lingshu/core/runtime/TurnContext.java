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

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import org.reactivestreams.Subscriber;

import java.util.List;

/**
 * Per-turn runtime state shared by every Slot the engine invokes (dsh §4.12.1).
 *
 * <p>Distinct from {@link ai.lingshu.core.slot.ToolExecutionContext}:
 * <ul>
 *   <li>{@code TurnContext} — spans the whole turn (engine-wide)</li>
 *   <li>{@code ToolExecutionContext} — issued by the Sandbox per tool call (per-call)</li>
 * </ul>
 *
 * <p>Created by {@code FlowEngine.runTurn}; held by reference throughout the turn.
 * Append methods are synchronized on the underlying session to prevent the compactor
 * and concurrent tool results from racing.
 */
public interface TurnContext {

    /** Session this turn belongs to (history + id + fork / checkpoint). */
    Session session();

    /** Immutable config snapshot for this turn. */
    AgentConfig config();

    /** Reactive subscriber receiving {@link AgentEvent} instances. */
    Subscriber<? super AgentEvent> sink();

    /** Raw user input for this turn. */
    String userInput();

    /** True once {@link #markDone} has been called (cancel / error / etc.). */
    boolean done();

    /** Set {@code done = true}. The {@code FlowEngine} polls this each loop iteration. */
    void markDone();

    /**
     * Append an assistant message (model output) to history.
     *
     * <p>🆕 Story #027a — {@code toolCalls} is now a first-class input (was hardcoded
     * to an empty list by Story #001). The ReAct loop records every
     * {@link ai.lingshu.core.message.LlmResponse#getToolCalls()} on the assistant
     * turn so the next request's {@code messages[]} can echo back the
     * {@code tool_use} blocks Anthropic requires for tool-result pairing. The
     * existing {@code Message.Assistant} 4-arg ctor ({@code text, toolCalls,
     * stopReason, usage}) is preserved by the default implementation —
     * {@code stopReason} still defaults to {@code END_TURN} at this layer (see
     * dsh §6.1 ReAct loop for the rationale).
     */
    void appendAssistant(String text, List<ToolCall> toolCalls, Usage usage);

    /** Append a tool result message to history. */
    void appendToolResult(ToolResult result);

    /**
     * Insert a system message at the head of history (used by the compactor to inject
     * its summary so the model sees it as the most recent context).
     */
    void appendSystem(String content, String source);

    /**
     * 🆕 Story #005 — Per-turn cancellation token.
     *
     * <p>Shared identity with {@code ToolExecutionContext.cancellation()} — both
     * layers see the same token reference (dsh §14.12 N12 three-layer wiring).
     * Fires on Ctrl-C (JVM shutdown hook → {@code AgentFactory.broadcastCancel()}),
     * turn timeout cascade, or programmatic {@code markDone} override.
     *
     * <p>Cooperative cancellation: FlowEngine / Tool / LlmProvider must poll
     * {@code isCancelled()} or register callbacks via {@code onCancel(Runnable)}
     * to react. This is <b>not</b> {@code Thread.interrupt()}.
     */
    CancellationToken cancellation();
}