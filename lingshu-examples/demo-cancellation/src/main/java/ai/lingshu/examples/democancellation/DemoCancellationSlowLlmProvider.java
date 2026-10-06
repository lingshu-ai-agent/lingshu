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
package ai.lingshu.examples.democancellation;

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
 * Story #005 demo SlowLlmProvider —— 第一次 stream() 返一个 future,该 future 仅在
 * {@code ctx.cancellation()} fire 时 complete(StopReason.CANCELLED),或 maxWaitMs 兜底超时
 * complete(END_TURN)。
 *
 * <p>本类为 {@code lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/SlowLlmProvider.java}
 * 的 demo 内本地副本,避免跨模块 test fixture import。
 */
public class DemoCancellationSlowLlmProvider implements LlmProvider {

    private final long maxWaitMs;

    public DemoCancellationSlowLlmProvider(long maxWaitMs) {
        this.maxWaitMs = maxWaitMs;
    }

    @Override
    public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                Subscriber<? super AgentEvent> sink) {
        CompletableFuture<LlmResponse> fut = new CompletableFuture<>();
        Runnable unregister = ctx.cancellation().onCancel(() -> fut.complete(
            new LlmResponse("cancelled", Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                StopReason.CANCELLED, Usage.zero())));

        Thread safety = new Thread(() -> {
            try {
                Thread.sleep(maxWaitMs);
                fut.complete(new LlmResponse("safety-net",
                    Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                    StopReason.END_TURN, Usage.zero()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "demo-cancel-safety-net-" + System.nanoTime());
        safety.setDaemon(true);
        safety.start();
        return fut;
    }
}
