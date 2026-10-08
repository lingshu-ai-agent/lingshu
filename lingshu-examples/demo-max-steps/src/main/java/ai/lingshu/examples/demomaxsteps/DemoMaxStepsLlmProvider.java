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
package ai.lingshu.examples.demomaxsteps;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.reactivestreams.Subscriber;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Story #008 demo — 始终返 1 个 {@code noop} tool call 的 LLM,强制 ReAct loop 一直转直到 max-steps 触发。
 *
 * <p>{@code LlmResponse.stopReason=TOOL_USE} + {@code toolCalls.size()=1} 触发 engine 调度 tool,
 * 不返 {@code END_TURN} 让 engine 自行退出 → 超过 maxSteps 后 LinearTurnEngine 抛 {@code MaxStepsExceeded}。
 */
public class DemoMaxStepsLlmProvider implements LlmProvider {

    private final String toolName;
    private final String toolCallId;

    public DemoMaxStepsLlmProvider(String toolName, String toolCallId) {
        this.toolName = toolName;
        this.toolCallId = toolCallId;
    }

    @Override
    public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                Subscriber<? super AgentEvent> sink) {
        ToolCall call = new ToolCall(
            toolCallId,
            toolName,
            JsonNodeFactory.instance.objectNode());
        LlmResponse resp = new LlmResponse(
            "",
            Collections.singletonList(call),
            StopReason.TOOL_USE,
            Usage.zero());
        return CompletableFuture.completedFuture(resp);
    }
}