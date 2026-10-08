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
package ai.lingshu.core.spi;

/**
 * 🆕 Story #003 — Provider initialization failure (LINGS-S05).
 *
 * <p>Thrown by {@link SlotRouter} constructor or {@link SlotRouter#resolve(String,
 * ai.lingshu.core.runtime.AgentConfig)} when a Provider's {@link SlotProvider#version()}
 * is null, malformed semver, or incompatible with the Slot interface's
 * {@code CONTRACT_VERSION}.
 *
 * <p>Cause chain length ≥ 2: this exception wraps an {@link IllegalArgumentException}
 * from {@link Version#parse(String)} or {@link Version#isCompatible(String, String)}.
 *
 * <p>On Spring startup, this exception propagates from the {@code @Component} constructor
 * to the {@code ApplicationContext}, which logs it at ERROR level and exits JVM with code 1.
 *
 * <p>Error code: <b>{@code LINGS-S05}</b> (see constitution §4 — Slot domain).
 */
public class ProviderInitException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Fixed error code for v1 — always {@code "LINGS-S05"}. */
    private final String errorCode;

    /** Optional human-readable suggestion (may be null or empty). */
    private final String hint;

    /**
     * @param message short description (e.g. {@code "LlmProvider provider 'my-llm' v2.0.0
     *                incompatible with slot contract v1.0.0 (major version mismatch)"})
     * @param cause   the underlying {@link IllegalArgumentException} from {@link Version}
     * @param hint    actionable suggestion (e.g. {@code "implement version() returning '1.0.0'
     *                on your @Component class"}); may be null
     */
    public ProviderInitException(String message, Throwable cause, String hint) {
        super(buildMessage(message, hint), cause);
        this.errorCode = "LINGS-S05";
        this.hint = (hint == null) ? "" : hint;
    }

    /** @return the error code (always {@code "LINGS-S05"} for v1). */
    public String getErrorCode() {
        return errorCode;
    }

    /** @return the hint (empty string if none provided, never null). */
    public String getHint() {
        return hint;
    }

    private static String buildMessage(String message, String hint) {
        if (hint == null || hint.isEmpty()) {
            return "LINGS-S05: " + message;
        }
        return "LINGS-S05: " + message + " (hint: " + hint + ")";
    }
}