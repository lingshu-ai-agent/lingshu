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
package ai.lingshu.examples.demodelegate;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import org.reactivestreams.Subscriber;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Story #023 demo echo LlmProvider —— 返固定文本,触发引擎走 Task tool 派发路径。
 *
 * <p>本类为 {@code lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/EchoLlmProvider.java}
 * 的 demo 内本地副本(简化版,只返单条响应),避免跨模块 test fixture import。
 */
public class DemoDelegateEchoLlmProvider implements LlmProvider {

    @Override
    public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                Subscriber<? super AgentEvent> sink) {
        return CompletableFuture.completedFuture(
            new LlmResponse(
                "demo-delegate: provider wired; Task tool is callable from LLM-driven Agent.run",
                Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                StopReason.END_TURN,
                Usage.zero()));
    }
}
