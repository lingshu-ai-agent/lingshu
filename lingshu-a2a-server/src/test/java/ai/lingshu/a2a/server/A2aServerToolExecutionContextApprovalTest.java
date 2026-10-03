package ai.lingshu.a2a.server;

import ai.lingshu.core.decision.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #041 — L1 unit test for {@link A2aServerToolExecutionContext#approval()}
 * fail-safe override (AC-041-06).
 *
 * <p>The A2A server side has no human channel — any {@link Decision.AskUser} returned
 * by a permission policy must be resolved to {@link Decision.Deny} with a clear,
 * non-blocking reason (no event emitted, no ApprovalRegistry lookup). Story #041
 * confirms this is the intended contract, not a stub.
 */
class A2aServerToolExecutionContextApprovalTest {

    @Test
    @DisplayName("AC-041-06: A2aServerToolExecutionContext.approval().ask(AskUser) → fail-safe Deny")
    void approvalAsksUserReturnsDenyImmediately() {
        A2aServerToolExecutionContext ctx = new A2aServerToolExecutionContext();
        Decision.AskUser ask = new Decision.AskUser(
            "rm -rf / ?", Collections.<Decision.Option>emptyList());

        long startMs = System.currentTimeMillis();
        Decision result = ctx.approval().ask(ask);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(result).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) result).getReason())
            .contains("A2aServerToolExecutionContext.approval()")
            .contains("AskUser denied");
        // Fail-safe must NOT block — return in well under 1s
        assertThat(elapsedMs).isLessThan(1_000L);
    }
}
