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
package ai.lingshu.core.impl.llm;

import ai.lingshu.core.exception.LingsConfigException;

/**
 * LlmProvider 域异常 (Story #027a, dsh v1.5.44 §15.6).
 *
 * <p>Thrown when {@link AnthropicLlmProvider} detects a malformed
 * {@code tool_use} / {@code tool_result} block in either direction of the
 * Anthropic {@code /v1/messages} wire protocol:
 *
 * <ul>
 *   <li><b>Outbound</b>({@link AnthropicLlmProvider#buildRequestBody}) —
 *       an Assistant message with {@code toolCalls} is missing {@code id} / {@code name}
 *       on one of its embedded {@code ToolCall} entries, or a {@link ai.lingshu.core.message.Message.ToolResult}
 *       is missing {@code toolUseId} / {@code content}. Failing fast at request-build
 *       time surfaces the bug at the original call site rather than letting
 *       Anthropic return a generic {@code 400 Bad Request}.</li>
 *
 *   <li><b>Inbound</b>({@link AnthropicLlmProvider#parseResponse}) —
 *       an Anthropic response {@code content[].tool_use} block is missing
 *       {@code id} / {@code name}. Failing fast preserves the contract that
 *       every {@code LlmResponse.toolCalls[]} element has a non-null {@code id}
 *       (the §4.6 {@code ToolExecutor.dispatch} pipeline keys dispatch by id).</li>
 * </ul>
 *
 * <p><b>Why here, not in {@code ai.lingshu.core.exception}</b> — The public
 * {@code core.exception} SPI package is for cross-cutting exception types that
 * every slot may throw (e.g. {@link LingsConfigException}). LlmProvider is a
 * single-slot concern (Slot 1) so this exception lives next to its throw site
 * ({@link AnthropicLlmProvider}), matching the
 * {@link ai.lingshu.core.mcp.McpTransportException} pattern where the
 * exception lives in the {@code mcp} SPI package.
 *
 * <p><b>Error code embedding</b> — {@link #getMessage()} prefixes the
 * exception message with {@code [<code>]}, so callers using
 * {@code assertThatThrownBy().hasMessageContaining("LINGS-L0X")} work without
 * needing to call {@link #getCode()} explicitly. This mirrors the
 * Story #023 {@code LinearTurnEngine.L117} convention
 * ({@code "LINGS-C02 CONFIG_VALIDATION_FAILED: ..."}) and the
 * {@link ai.lingshu.core.tool.ToolErrorCodes}
 * ({@code [LINGS-T08]}) conventions used elsewhere in the LLM provider
 * surface.
 *
 * <p><b>JDK 8 compatibility</b> — Plain {@code RuntimeException} subclass
 * with an explicit {@code serialVersionUID}; no {@code Exception.captureStackTrace}
 * cleverness.
 */
public class LingsLlmProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Canonical error code (e.g. {@code "LINGS-L01"}); aligns with dsh §15.6 catalog. */
    private final String code;

    /**
     * @param code    machine-readable code (e.g. {@link LlmErrorCodes#LINGS_L01});
     *                always set so callers can branch on it without parsing the message
     * @param message human-readable explanation of the protocol violation
     */
    public LingsLlmProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @param code    machine-readable code (e.g. {@link LlmErrorCodes#LINGS_L02})
     * @param message human-readable explanation of the protocol violation
     * @param cause   underlying cause (e.g. a Jackson parse exception)
     */
    public LingsLlmProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * @return the canonical error code (e.g. {@code "LINGS-L01"})
     */
    public String getCode() {
        return code;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Override prefix the message with {@code [<code>]} so test assertions like
     * {@code assertThatThrownBy().hasMessageContaining("LINGS-L01")} succeed
     * without explicit code lookup. When the message already starts with the
     * same bracket-prefix (defensive double-wrap guard), the prefix is not
     * re-applied — keeps callers that pre-format messages idempotent.
     */
    @Override
    public String getMessage() {
        String original = super.getMessage();
        if (original == null) {
            return "[" + code + "]";
        }
        if (original.startsWith("[" + code + "]")) {
            return original;
        }
        return "[" + code + "] " + original;
    }
}