package ai.lingshu.cli;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.slot.ToolExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #041 — L1 unit test for {@code SkillCommandDispatcher.CliSkillToolExecutionContext.approval()}
 * fail-safe override (AC-041-08).
 *
 * <p>CLI single-shot mode is user-initiated; commands are implicitly user-approved.
 * Any {@link Decision.AskUser} returned by a permission policy must resolve to
 * {@link Decision.Deny} immediately with the CLI-specific message — no blocking,
 * no approval flow (CLI users have no UI channel). Story #041 confirms this is
 * the intended contract, not a stub.
 *
 * <p>The CliSkillToolExecutionContext is a {@code private static final} inner class
 * on {@code SkillCommandDispatcher}, so the test reaches it via reflection.
 */
class SkillCommandDispatcherStubApprovalTest {

    @Test
    @DisplayName("AC-041-08: SkillCommandDispatcher.CliSkillToolExecutionContext.approval().ask(AskUser) → fail-safe Deny")
    void approvalAsksUserReturnsDenyImmediately() throws Exception {
        Class<?> ctxClass = Class.forName(
            "ai.lingshu.cli.SkillCommandDispatcher$CliSkillToolExecutionContext");
        // 1-arg ctor takes a Session; we pass null because we only invoke approval()
        // which doesn't dereference the session.
        Constructor<?> ctor = ctxClass.getDeclaredConstructor(ai.lingshu.core.runtime.Session.class);
        ctor.setAccessible(true);
        ToolExecutionContext ctx = (ToolExecutionContext) ctor.newInstance(new Object[]{null});

        Decision.AskUser ask = new Decision.AskUser(
            "/commit ?", Collections.<Decision.Option>emptyList());

        long startMs = System.currentTimeMillis();
        Decision result = ctx.approval().ask(ask);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(result).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) result).getReason())
            .contains("CLI Skill dispatch")
            .contains("AskUser approval")
            .contains("Story #020c MVP");
        // Fail-safe must NOT block — return in well under 1s
        assertThat(elapsedMs).isLessThan(1_000L);
    }
}
