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

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

/**
 * Story #008 demo — Noop {@link Tool},返 success 立即结束 tool dispatch,
 * 让 engine 下一轮继续调用 LLM → 反复触发直到 max-steps 耗尽。
 */
@Component("noop")
public class NoopTool implements Tool {

    @Override public String name() { return "noop"; }
    @Override public String description() { return "no-op — always returns success"; }

    @Override
    public JsonNode inputSchema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId(call.getId())
            .content("noop ok")
            .isError(false)
            .build();
    }
}