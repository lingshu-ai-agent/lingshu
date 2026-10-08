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
package ai.lingshu.core.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #029 — L1 unit test for {@link PermissionErrorCodes} (1 case).
 *
 * <p>Locks the P-domain ErrorCode prefix identity so downstream code can
 * rely on {@code Decision.Deny.reason.startsWith("[LINGS-P01]")}.
 */
class PermissionErrorCodesTest {

    @Test
    @DisplayName("AC-029-7: LINGS_P01_constant_value")
    void LINGS_P01_constant_value() {
        assertThat(PermissionErrorCodes.LINGS_P01).isEqualTo("LINGS-P01");
    }
}
