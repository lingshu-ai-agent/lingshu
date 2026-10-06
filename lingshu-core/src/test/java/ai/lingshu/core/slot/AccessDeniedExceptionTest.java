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
package ai.lingshu.core.slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #028 — L1 unit tests for {@link AccessDeniedException}.
 *
 * <p>Verifies the contract that every sandbox-layer denial throws an exception whose
 * {@code getMessage()} starts with the canonical {@code [LINGS-S01]} ErrorCode prefix,
 * matching the {@code getMessage()} pattern of {@code LingsLlmProviderException}
 * (Story #027a) and {@code LingsDelegateException} (Story #023).
 */
class AccessDeniedExceptionTest {

    @Test
    @DisplayName("AC-NN-S01: simple ctor auto-prefixes message with [LINGS-S01]")
    void simpleCtorAutoPrefixesErrorCode() {
        AccessDeniedException ex = new AccessDeniedException("Path escapes working dir: /etc/passwd");

        assertThat(ex.getMessage())
            .as("getMessage() must start with the canonical ErrorCode")
            .startsWith("[LINGS-S01]")
            .contains("Path escapes working dir");
        assertThat(AccessDeniedException.ERROR_CODE).isEqualTo("LINGS-S01");
    }

    @Test
    @DisplayName("AC-NN-S01: (reason, cause) ctor preserves wrapped cause and prefix")
    void reasonCauseCtorPreservesCause() {
        IOException ioEx = new IOException("connection reset");
        AccessDeniedException ex = new AccessDeniedException("Domain not whitelisted", ioEx);

        assertThat(ex.getMessage())
            .startsWith("[LINGS-S01]")
            .contains("Domain not whitelisted");
        assertThat(ex.getCause()).isSameAs(ioEx);
    }

    @Test
    @DisplayName("AC-NN-S01: null reason is treated as empty string (no NPE)")
    void nullReasonIsTreatedAsEmpty() {
        AccessDeniedException ex = new AccessDeniedException(null);

        // "[LINGS-S01] " followed by nothing
        assertThat(ex.getMessage()).isEqualTo("[LINGS-S01] ");
    }
}