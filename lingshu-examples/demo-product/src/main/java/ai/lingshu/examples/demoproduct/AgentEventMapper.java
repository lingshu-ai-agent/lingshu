package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps {@link AgentEvent} subclasses to Spring SSE {@code SseEventBuilder} payloads.
 *
 * <p>This is the <b>first reactive consumer</b> in the codebase: every other call site
 * uses {@code Agent.runBlocking(...)} which drains the event stream into a list. By
 * going through {@link Agent#run(String)}, we subscribe to {@code Publisher<AgentEvent>}
 * and forward events to an SSE emitter one-by-one.
 *
 * <p>Phase 1 ships with 2 cases (TextDelta, TurnCompleted). Phase 4 fills out all 12
 * cases plus the {@link AgentEvent.Compacted} token-count workaround:
 * {@code AgentEvent.Compacted} itself carries no payload, so we track
 * {@link #lastHistorySize} on every {@link AgentEvent.MessageAppended} and compute
 * {@code approxTokensFreed = lastHistorySize − historySizeAfter} when compaction fires.
 *
 * <p>JDK 8 compatible — uses {@code instanceof} chain (no pattern matching switch).
 */
@Component
public class AgentEventMapper {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Last known history size; updated on every {@code MessageAppended} event. */
    private final AtomicInteger lastHistorySize = new AtomicInteger(0);

    /** Reset history baseline (called by ChatController before each turn). */
    public void resetBaseline(int size) {
        lastHistorySize.set(size);
    }

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
            out.set("ask", mapper.valueToTree(e.getAsk()));
        } else if (event instanceof AgentEvent.Compacted) {
            // R-1 workaround: AgentEvent.Compacted has no payload. Estimate tokens freed.
            int before = lastHistorySize.get();
            out.put("type", "compacted");
            out.put("beforeSize", before);
            out.put("afterSize", before);   // updated by the next MessageAppended
            out.put("approxTokensFreed", 0); // populated by ChatController after event lands
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
            // R-1: update baseline AFTER MessageAppended lands so the next Compacted
            // sees the post-compaction size in `afterSize`.
            out.put("type", "message");
            JsonNode mNode = mapper.valueToTree(m);
            out.set("message", mNode);
            // History grew by one (or shrank by N if this is a compaction-replace);
            // we conservatively track the absolute history size.
            lastHistorySize.incrementAndGet();
        }
    }
}