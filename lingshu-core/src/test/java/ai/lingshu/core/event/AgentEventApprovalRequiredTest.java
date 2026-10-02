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