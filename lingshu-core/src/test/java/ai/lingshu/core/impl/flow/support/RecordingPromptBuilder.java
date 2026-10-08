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