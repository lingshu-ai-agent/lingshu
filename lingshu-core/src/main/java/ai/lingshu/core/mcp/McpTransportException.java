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

/**
 * MCP transport-domain exception (Story #021a, dsh §15.9).
 *
 * <p>Introduces the {@code M} (MCP) error domain. Code names follow the
 * {@code LINGS-M<NN>} convention where {@code <NN>} is the per-domain code.
 * Story #021a defines {@code LINGS-M01 = MCP_CONNECT_FAILED}, raised by
 * {@link McpServerConnectionFactory#create(McpServerConfig)} when an
 * unsupported transport is requested (e.g. {@code SSE} or
 * {@code STREAMABLE_HTTP} before Story #021c lands).
 *
 * <p><b>JDK 8 compatibility</b> — Plain {@code RuntimeException} subclass;
 * no {@code Exception.captureStackTrace}-style cleverness.
 */
public class McpTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Canonical error code (e.g. {@code "LINGS-M01"}). */
    private final String code;

    public McpTransportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public McpTransportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return the canonical error code. */
    public String getCode() {
        return code;
    }
}