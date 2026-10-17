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
package ai.lingshu.core.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #050 — L1 tests for {@link AgentEvent.LlmStreamStarted} (2 cases).
 *
 * <p>Verifies the new {@code LlmStreamStarted(streamId, maxOutputTokens)}
 * nested class wires both fields and is independent of the ReAct step
 * counter (the {@code ReasoningStarted(step, maxSteps)} family). This is
 * the data contract that fixes Bug-1 (SSE {@code step} counter reset on
 * every Anthropic {@code message_start}).
 */
class AgentEventLlmStreamStartedTest {

    @Test
    @DisplayName("AC-050-01: ctorAndGetters_wireStreamIdAndMaxOutputTokens")
    void ctorAndGetters_wireStreamIdAndMaxOutputTokens() {
        String messageId = "msg_01abcDEFghiJKL";
        // maxOutputTokens=0 sentinel = "not in SSE stream" (Anthropic puts
        // max_tokens in the request, not the response). UI consumers should
        // treat 0 as "unknown" — covered by AC-050-04 in
        // AgentEventMapperLlmStreamStartedTest (demo-product).
        AgentEvent.LlmStreamStarted ev = new AgentEvent.LlmStreamStarted(messageId, 0);

        assertThat(ev.getStreamId()).isEqualTo(messageId);
        assertThat(ev.getMaxOutputTokens()).isEqualTo(0);
    }

    @Test
    @DisplayName("AC-050-02: distinctFromReasoningStarted_independentType")
    void distinctFromReasoningStarted_independentType() {
        // Bug-1 fix verification: LlmStreamStarted is a separate subclass
        // from ReasoningStarted. The engine emits both — LlmStreamStarted once
        // per Anthropic message_start (parser side), ReasoningStarted once per
        // ReAct step (engine side at LinearTurnEngine.runTurn L271). They must
        // not collide on instanceof checks (AgentEventMapper L109-114 treats
        // them differently).
        AgentEvent.LlmStreamStarted stream = new AgentEvent.LlmStreamStarted("msg_X", 8192);
        AgentEvent.ReasoningStarted reasoning = new AgentEvent.ReasoningStarted(3, 50);

        assertThat(stream).isNotInstanceOf(AgentEvent.ReasoningStarted.class);
        assertThat(reasoning).isNotInstanceOf(AgentEvent.LlmStreamStarted.class);
        // Sanity: both still extend AgentEvent root.
        assertThat(stream).isInstanceOf(AgentEvent.class);
        assertThat(reasoning).isInstanceOf(AgentEvent.class);
    }
}