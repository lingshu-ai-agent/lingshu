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
package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Maps {@link AgentEvent} subclasses to Spring SSE {@code SseEventBuilder} payloads.
 *
 * <p>This is the <b>first reactive consumer</b> in the codebase: every other call site
 * uses {@code Agent.runBlocking(...)} which drains the event stream into a list. By
 * going through {@link Agent#run(String)}, we subscribe to {@code Publisher<AgentEvent>}
 * and forward events to an SSE emitter one-by-one.
 *
 * <p>Phase 1 ships with 2 cases (TextDelta, TurnCompleted). Phase 4 fills out all 12
 * cases.
 *
 * <p>🆕 Bug fix 2026-10-25 — {@link AgentEvent.Compacted} now carries a real
 * {@code (beforeSize, afterSize, approxTokensFreed)} payload measured by
 * {@code LinearTurnEngine} around the compactor swap. The old R-1 workaround
 * ({@code lastHistorySize} side-channel + hardcoded {@code afterSize=beforeSize} /
 * {@code approxTokensFreed=0}) is deleted — it made every compaction look like a
 * no-op on the wire and misled the 2026-10-25 production debugging session.
 *
 * <p>JDK 8 compatible — uses {@code instanceof} chain (no pattern matching switch).
 */
@Component
public class AgentEventMapper {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Convert one {@link AgentEvent} into a JSON payload suitable for SSE {@code data:}.
     * Returns {@code null} only for {@code MessageAppended} (Phase 1 strips these — UI
     * uses /api/sessions/{id}/history endpoint for full snapshots).
     *
     * @param event   the engine event
     * @param out     ObjectNode to populate; passed in to avoid per-event allocation
     */
    public void toJson(AgentEvent event, ObjectNode out) {
        if (event instanceof AgentEvent.TextDelta) {
            out.put("type", "text");
            out.put("delta", ((AgentEvent.TextDelta) event).getText());
        } else if (event instanceof AgentEvent.ToolStarted) {
            AgentEvent.ToolStarted e = (AgentEvent.ToolStarted) event;
            out.put("type", "tool.start");
            out.put("toolCallId", e.getToolCallId());
            out.put("name", e.getName());
        } else if (event instanceof AgentEvent.ToolProgress) {
            AgentEvent.ToolProgress e = (AgentEvent.ToolProgress) event;
            out.put("type", "tool.progress");
            out.put("toolCallId", e.getToolCallId());
            out.put("partial", e.getPartial());
        } else if (event instanceof AgentEvent.ToolCompleted) {
            out.put("type", "tool.done");
            AgentEvent.ToolCompleted e = (AgentEvent.ToolCompleted) event;
            out.set("result", mapper.valueToTree(e.getResult()));
        } else if (event instanceof AgentEvent.TurnCompleted) {
            AgentEvent.TurnCompleted e = (AgentEvent.TurnCompleted) event;
            out.put("type", "turn.completed");
            out.put("reason", e.getReason().name());
            out.set("usage", mapper.valueToTree(e.getUsage()));
        } else if (event instanceof AgentEvent.ApprovalRequired) {
            AgentEvent.ApprovalRequired e = (AgentEvent.ApprovalRequired) event;
            out.put("type", "approval");
            // 🆕 Story #030 — include stable approvalId so the host UI can correlate
            // an inbound POST /api/approvals/{sessionId}/{approvalId} back to this
            // pending approval (the engine's continuation Consumer<Decision> is bound
            // to this id in the ApprovalRegistry).
            out.put("approvalId", e.getApprovalId());
            out.set("ask", mapper.valueToTree(e.getAsk()));
        } else if (event instanceof AgentEvent.Compacted) {
            // 🆕 Bug fix 2026-10-25 — forward the engine-measured payload verbatim.
            AgentEvent.Compacted e = (AgentEvent.Compacted) event;
            out.put("type", "compacted");
            out.put("beforeSize", e.getBeforeSize());
            out.put("afterSize", e.getAfterSize());
            out.put("approxTokensFreed", e.getApproxTokensFreed());
        } else if (event instanceof AgentEvent.ErrorEvent) {
            AgentEvent.ErrorEvent e = (AgentEvent.ErrorEvent) event;
            out.put("type", "error");
            Throwable t = e.getError();
            out.put("class", t.getClass().getName());
            out.put("message", t.getMessage());
        } else if (event instanceof AgentEvent.ReasoningStarted) {
            AgentEvent.ReasoningStarted e = (AgentEvent.ReasoningStarted) event;
            out.put("type", "reasoning.start");
            out.put("step", e.getStep());
            out.put("maxSteps", e.getMaxSteps());
        } else if (event instanceof AgentEvent.LlmStreamStarted) {
            // 🆕 Story #050 — distinct from reasoning.start. Emitted once per LLM
            // message_start (Anthropic message_id / OpenAI response id). NOT a
            // ReAct step counter; UI uses this for stream header (e.g. message
            // bubble id) without coupling to step.
            AgentEvent.LlmStreamStarted e = (AgentEvent.LlmStreamStarted) event;
            out.put("type", "llm.stream.started");
            out.put("streamId", e.getStreamId());
            out.put("maxOutputTokens", e.getMaxOutputTokens());
        } else if (event instanceof AgentEvent.ObservationAppended) {
            AgentEvent.ObservationAppended e = (AgentEvent.ObservationAppended) event;
            out.put("type", "observation");
            out.put("step", e.getStep());
            out.put("toolResultCount", e.getToolResultCount());
        } else if (event instanceof AgentEvent.MaxStepsExceeded) {
            AgentEvent.MaxStepsExceeded e = (AgentEvent.MaxStepsExceeded) event;
            out.put("type", "max_steps");
            out.put("maxSteps", e.getMaxSteps());
            out.set("totalUsage", mapper.valueToTree(e.getTotalUsage()));
        } else if (event instanceof AgentEvent.MessageAppended) {
            AgentEvent.MessageAppended e = (AgentEvent.MessageAppended) event;
            Message m = e.getMessage();
            out.put("type", "message");
            JsonNode mNode = mapper.valueToTree(m);
            out.set("message", mNode);
        }
    }
}