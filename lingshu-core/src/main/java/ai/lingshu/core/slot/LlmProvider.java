package ai.lingshu.core.slot;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.spi.ContractVersionRef;
import org.reactivestreams.Subscriber;

import java.util.concurrent.CompletableFuture;

/**
 * Slot 1 — Stream a prompt to an LLM and surface both incremental events and the final
 * structured response.
 *
 * <p>Two-channel output pattern (see dsh §4.10):
 * <ul>
 *   <li>{@code sink.onNext(...)} — incremental {@link AgentEvent.TextDelta} / {@link AgentEvent.ToolStarted} / {@link AgentEvent.ToolProgress} events</li>
 *   <li>{@code future} — completes with the final {@link LlmResponse} (text + tool calls + usage + stop reason)</li>
 * </ul>
 *
 * <p>This split exists because {@code FlowEngine} needs the structured response to drive the
 * next iteration of ReAct, while the UI / log subscriber wants a live text stream. A single
 * return value would force one or the other to lag.
 *
 * <p>Implementations may use Spring AI {@code ChatModel} as the protocol converter (dsh §4.10.1
 * 硬规则 2), or talk to a provider SDK directly. The interface is provider-agnostic.
 */
public interface LlmProvider {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). Provider.version() must be
     *  compatible per {@link ai.lingshu.core.spi.Version#isCompatible(String, String)}. */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Begin streaming. Must NOT block; return immediately with an in-flight future.
     *
     * @param prompt fully assembled prompt (messages + tools + hints) from {@code PromptBuilder}
     * @param ctx    turn context (config + session + sink + userInput + done flag)
     * @param sink   reactive subscriber receiving {@link AgentEvent} deltas; may be null for fire-and-forget
     * @return future that completes when the model emits its terminal token / errors / cancels
     */
    CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                          Subscriber<? super AgentEvent> sink);
}