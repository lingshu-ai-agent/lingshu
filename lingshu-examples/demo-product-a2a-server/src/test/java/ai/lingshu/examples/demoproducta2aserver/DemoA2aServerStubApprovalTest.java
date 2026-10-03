package ai.lingshu.examples.demoproducta2aserver;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.slot.ToolExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #041 — L1 unit test for {@code DemoA2aServer.StubToolExecutionContext.approval()}
 * fail-safe override (AC-041-07).
 *
 * <p>The translate demo A2A server has no human channel — any {@link Decision.AskUser}
 * must be resolved to {@link Decision.Deny} with the demo-specific message, with no
 * blocking. Story #041 confirms this is the intended contract, not a stub.
 *
 * <p>The StubToolExecutionContext is a {@code private static final} inner class on
 * {@code DemoA2aServer}, so the test reaches it via reflection (the contract we
 * verify is the public SPI behaviour, not the inner-class visibility).
 */
class DemoA2aServerStubApprovalTest {

    @Test
    @DisplayName("AC-041-07: DemoA2aServer.StubToolExecutionContext.approval().ask(AskUser) → fail-safe Deny")
    void approvalAsksUserReturnsDenyImmediately() throws Exception {
        Class<?> ctxClass = Class.forName(
            "ai.lingshu.examples.demoproducta2aserver.DemoA2aServer$StubToolExecutionContext");
        Constructor<?> ctor = ctxClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        ToolExecutionContext ctx = (ToolExecutionContext) ctor.newInstance();

        Decision.AskUser ask = new Decision.AskUser(
            "translate?", Collections.<Decision.Option>emptyList());

        long startMs = System.currentTimeMillis();
        Decision result = ctx.approval().ask(ask);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(result).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) result).getReason())
            .contains("DemoA2aServer has no ApprovalGate")
            .contains("AskUser denied");
        // Fail-safe must NOT block — return in well under 1s
        assertThat(elapsedMs).isLessThan(1_000L);
    }
}
