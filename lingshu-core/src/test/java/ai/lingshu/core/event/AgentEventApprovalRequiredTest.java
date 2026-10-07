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

import ai.lingshu.core.decision.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #030 — L1 tests for {@link AgentEvent.ApprovalRequired} (2 cases).
 *
 * <p>Verifies the 3-arg ctor wires {@code approvalId}, the 2-arg back-compat
 * ctor defaults to {@code null}.
 */
class AgentEventApprovalRequiredTest {

    @Test
    @DisplayName("AC-030-12: threeArgCtor_exposesApprovalId")
    void threeArgCtor_exposesApprovalId() {
        Decision.AskUser ask = new Decision.AskUser(
            "permission?", Collections.<Decision.Option>emptyList());
        Consumer<Decision> cont = d -> {};
        String id = "approval-uuid-42";

        AgentEvent.ApprovalRequired ev = new AgentEvent.ApprovalRequired(ask, cont, id);

        assertThat(ev.getAsk()).isSameAs(ask);
        assertThat(ev.getContinuation()).isSameAs(cont);
        assertThat(ev.getApprovalId()).isEqualTo(id);
    }

    @Test
    @DisplayName("AC-030-13: twoArgCtor_backCompatNullsApprovalId")
    void twoArgCtor_backCompatNullsApprovalId() {
        Decision.AskUser ask = new Decision.AskUser(
            "permission?", Collections.<Decision.Option>emptyList());
        Consumer<Decision> cont = d -> {};

        AgentEvent.ApprovalRequired ev = new AgentEvent.ApprovalRequired(ask, cont);

        assertThat(ev.getAsk()).isSameAs(ask);
        assertThat(ev.getContinuation()).isSameAs(cont);
        assertThat(ev.getApprovalId()).isNull();
    }
}