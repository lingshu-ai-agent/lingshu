package ai.lingshu.core.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.spi.ContractVersionRef;
import org.reactivestreams.Subscriber;

/**
 * Slot 8 — Turn execution topology (dsh §4.11).
 *
 * <p>Consumes a {@link TurnContext} (session + config + userInput + sink + done flag) and emits
 * an {@link AgentEvent} stream back via the sink. How the slots are orchestrated is entirely
 * the implementation's choice.
 *
 * <p>v1 default: {@code LinearTurnEngine} — fixed sequence of 6 slots
 * (prompt → llm → tool loop → checkpoint). v2+: {@code DagTurnEngine}, state-machine
 * engines, external adapters (Google ADK / Alibaba Graph / LangGraph4j, see dsh §4.11.1-4).
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Honor the {@code ctx.done()} flag — break the loop on cancel</li>
 *   <li>Call {@code ctx.session().checkpoint()} before returning so {@link ai.lingshu.core.slot.SessionStore}
 *       can persist the final state</li>
 *   <li>Emit {@link AgentEvent.TurnCompleted} as the terminal event</li>
 * </ul>
 */
public interface FlowEngine {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Execute one turn. The call returns when the engine emits {@code TurnCompleted} or
     * {@code ErrorEvent} (and the corresponding continuation has resolved). The implementation
     * may complete synchronously (default {@code LinearTurnEngine}) or asynchronously
     * (external adapters bridge their runner into the sink).
     *
     * @param ctx  turn context (session + config + userInput + sink + done flag)
     * @param sink reactive subscriber; the engine pushes {@link AgentEvent} instances here
     */
    void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink);
}