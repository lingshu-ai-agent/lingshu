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
package ai.lingshu.core.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug fix 2026-10-25 — L1 tests for {@link AgentEvent.Compacted} (2 cases).
 *
 * <p>Verifies the 3-arg ctor wires {@code beforeSize} / {@code afterSize} /
 * {@code approxTokensFreed} verbatim, and the no-arg back-compat ctor defaults
 * to zeros (mirrors the {@code ApprovalRequired} 2-arg back-compat pattern).
 */
class AgentEventCompactedTest {

    @Test
    @DisplayName("Bug 2026-10-25: threeArgCtor_exposesBeforeAfterAndTokensFreed")
    void threeArgCtor_exposesBeforeAfterAndTokensFreed() {
        AgentEvent.Compacted ev = new AgentEvent.Compacted(107, 42, 9000);

        assertThat(ev.getBeforeSize()).isEqualTo(107);
        assertThat(ev.getAfterSize()).isEqualTo(42);
        assertThat(ev.getApproxTokensFreed()).isEqualTo(9000);
    }

    @Test
    @DisplayName("Bug 2026-10-25: noArgCtor_backCompatZeroesPayload")
    void noArgCtor_backCompatZeroesPayload() {
        AgentEvent.Compacted ev = new AgentEvent.Compacted();

        assertThat(ev.getBeforeSize()).isZero();
        assertThat(ev.getAfterSize()).isZero();
        assertThat(ev.getApproxTokensFreed()).isZero();
    }
}
