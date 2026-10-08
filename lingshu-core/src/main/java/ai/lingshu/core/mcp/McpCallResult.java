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
package ai.lingshu.core.mcp;

import lombok.Builder;
import lombok.Value;

/**
 * Minimal MCP tool-call result (Story #021a → #021b).
 *
 * <p>Carries either a successful payload or an error message, never both.
 * Use the static factories {@link #success(String)} and {@link #error(String)}
 * to make the intent explicit at call sites.
 *
 * <p><b>JDK 8 compatibility</b> — {@code @Value @Builder}, no
 * {@code record}/{@code sealed}.
 */
@Value
@Builder
public class McpCallResult {

    /** Tool output (for success). Null when {@link #isError} is true. */
    String content;

    /** Error message (for failure). Null when {@link #isError} is false. */
    String errorMessage;

    /** True iff this result represents a failure (transport / tool error). */
    boolean isError;

    /** Successful result factory. */
    public static McpCallResult success(String content) {
        return McpCallResult.builder().content(content).isError(false).build();
    }

    /** Error result factory. */
    public static McpCallResult error(String errorMessage) {
        return McpCallResult.builder().errorMessage(errorMessage).isError(true).build();
    }
}