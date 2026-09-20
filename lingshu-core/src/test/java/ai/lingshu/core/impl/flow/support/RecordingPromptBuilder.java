package ai.lingshu.core.impl.flow.support;

import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.PromptBuilder;

import java.util.Collections;

/**
 * Story #004 test fixture — minimal {@link PromptBuilder} that returns an empty
 * {@link Prompt} regardless of the turn context. Lets the engine proceed past the
 * {@code Thought} step without involving the real {@code DefaultPromptBuilder} and
 * its memory-source wiring.
 *
 * <p>Test-only class.
 */
public class RecordingPromptBuilder implements PromptBuilder {

    @Override
    public Prompt build(TurnContext ctx) {
        return Prompt.builder()
            .messages(Collections.<ai.lingshu.core.message.Message>emptyList())
            .tools(Collections.<ai.lingshu.core.message.ToolSpec>emptyList())
            .hints(new ai.lingshu.core.message.ModelHints(
                ctx.config().getLlm().getModel(),
                ctx.config().getLlm().getTemperature(),
                ctx.config().getLlm().getMaxTokens()))
            .build();
    }
}