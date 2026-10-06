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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — {@link ConnectionState} L1 enum test (AC-021a-2).
 *
 * <p>Single case asserting six values in the documented order.
 */
@DisplayName("Story #021a — ConnectionState contract")
class ConnectionStateTest {

    @Test
    @DisplayName("values() returns six states in declared order")
    void assertSixValuesInOrder() {
        ConnectionState[] vs = ConnectionState.values();
        assertThat(vs).hasSize(6);
        assertThat(vs[0]).isEqualTo(ConnectionState.IDLE);
        assertThat(vs[1]).isEqualTo(ConnectionState.CONNECTING);
        assertThat(vs[2]).isEqualTo(ConnectionState.CONNECTED);
        assertThat(vs[3]).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(vs[4]).isEqualTo(ConnectionState.RECONNECTING);
        assertThat(vs[5]).isEqualTo(ConnectionState.FAILED);
    }
}