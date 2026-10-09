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
 * SessionStore 域异常 (Story #014, dsh v1.5.X §15.X).
 *
 * <p>Thrown when {@code FileSessionStore} detects an IO or serialization failure:
 *
 * <ul>
 *   <li><b>save</b> ({@code FileSessionStore.save}) — Jackson serialization of the
 *       {@link ai.lingshu.core.message.Checkpoint} fails (e.g. unserializable message
 *       body), {@code Files.write} to {@code .tmp} fails (e.g. permission denied,
 *       disk full), or the atomic {@code Files.move ATOMIC_MOVE} fails (e.g. cross-
 *       filesystem link, contention).</li>
 *
 *   <li><b>load</b> ({@code FileSessionStore.load}) — {@code Files.readAllBytes}
 *       fails, or Jackson deserialization finds the file content corrupt
 *       (manual edit, partial write).</li>
 * </ul>
 *
 * <p><b>Why here, not in {@code ai.lingshu.core.exception}</b> — The public
 * {@code core.exception} SPI package is for cross-cutting exception types that
 * every slot may throw (e.g. {@link ai.lingshu.core.exception.LingsConfigException}).
 * SessionStore is a single-slot concern (Slot 5) so this exception lives next
 * to its throw site ({@code FileSessionStore}), matching the
 * {@code McpTransportException} / {@code LingsLlmProviderException} pattern where
 * the exception lives next to its slot.
 *
 * <p><b>Error code embedding</b> — {@link #getMessage()} prefixes the
 * exception message with {@code [<code>]}, so callers using
 * {@code assertThatThrownBy().hasMessageContaining("LINGS-X01")} work without
 * needing to call {@link #getCode()} explicitly. This mirrors the
 * Story #023 {@code LinearTurnEngine.L117} convention
 * ({@code "LINGS-C02 CONFIG_VALIDATION_FAILED: ..."}) and the
 * {@code LlmErrorCodes} ({@code [LINGS-L01]}) conventions used elsewhere.
 *
 * <p><b>JDK 8 compatibility</b> — Plain {@code RuntimeException} subclass
 * with an explicit {@code serialVersionUID}; no {@code Exception.captureStackTrace}
 * cleverness.
 *
 * @since 1.5.X
 */
public class LingsSessionStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Canonical error code (e.g. {@code "LINGS-X01"}); aligns with dsh §15 catalog. */
    private final String code;

    /**
     * @param code    machine-readable code (e.g. {@link SessionStoreErrorCodes#LINGS_X01});
     *                always set so callers can branch on it without parsing the message
     * @param message human-readable explanation of the IO / serialization failure
     */
    public LingsSessionStoreException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @param code    machine-readable code (e.g. {@link SessionStoreErrorCodes#LINGS_X01})
     * @param message human-readable explanation of the IO / serialization failure
     * @param cause   underlying cause (e.g. an {@link java.io.IOException} from
     *                {@code Files.write} / {@code Files.move} / Jackson)
     */
    public LingsSessionStoreException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * @return the canonical error code (e.g. {@code "LINGS-X01"})
     */
    public String getCode() {
        return code;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Override prefix the message with {@code [<code>]} so test assertions like
     * {@code assertThatThrownBy().hasMessageContaining("LINGS-X01")} succeed
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