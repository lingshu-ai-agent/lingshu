package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.concurrent.CancellationTokens;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import ai.lingshu.core.impl.tool.DefaultToolExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

import java.nio.file.Paths;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #005 — L1 tests for the FR-004 invariant: {@code TurnContext.cancellation()}
 * and {@code ToolExecutionContext.cancellation()} must return the <b>same</b> {@link CancellationToken}
 * reference. This is the dsh §14.12 N12 three-layer wiring guarantee.
 *
 * <p>If this invariant breaks, Ctrl-C fired at the turn level won't reach tool polls —
 * AC-04 200ms budget becomes impossible.
 */
class CancellationTokenSharingTest {

    private static AgentConfig defaultConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1,    // toolParallelism
            5,    // toolTimeoutSeconds
            0, 0, 0,
            10,   // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults());  // compactorConfig (Story #018)
    }

    @Test
    @DisplayName("sharedIdentity_turnCtxAndToolCtx_returnSameTokenReference")
    void sharedIdentity_turnCtxAndToolCtx_returnSameTokenReference() {
        // DefaultTurnContext.createWithBroadcast auto-registers; clear to avoid pollution.
        AgentFactory.clearBroadcastRegistryForTest();

        Session session = new DefaultSession();
        TurnContext turnCtx = DefaultTurnContext.createWithBroadcast(
            session, defaultConfig(), (Subscriber<AgentEvent>) null, "test");

        DefaultToolExecutionContext toolCtx = new DefaultToolExecutionContext(turnCtx);

        // Identity check (==) — same reference, not just equals. dsh §14.12 N12.
        assertThat(toolCtx.cancellation())
            .as("Tool ctx must share the turn ctx's cancellation token (dsh §14.12 N12)")
            .isSameAs(turnCtx.cancellation());
    }

    @Test
    @DisplayName("unsharedIdentity_differentTurnContexts_returnDifferentTokens")
    void unsharedIdentity_differentTurnContexts_returnDifferentTokens() {
        AgentFactory.clearBroadcastRegistryForTest();

        Session s1 = new DefaultSession();
        TurnContext t1 = DefaultTurnContext.createWithBroadcast(
            s1, defaultConfig(), null, "turn1");

        Session s2 = new DefaultSession();
        TurnContext t2 = DefaultTurnContext.createWithBroadcast(
            s2, defaultConfig(), null, "turn2");

        // Different turns → different tokens (no cross-contamination)
        assertThat(t1.cancellation()).isNotSameAs(t2.cancellation());
        assertThat(t1.cancellation().isCancelled()).isFalse();
        assertThat(t2.cancellation().isCancelled()).isFalse();

        // Firing t1 must NOT cancel t2 (token isolation)
        t1.cancellation().fire();
        assertThat(t1.cancellation().isCancelled()).isTrue();
        assertThat(t2.cancellation().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("cancellationPropagation_toolCtxSeesTurnCtxFire")
    void cancellationPropagation_toolCtxSeesTurnCtxFire() {
        AgentFactory.clearBroadcastRegistryForTest();

        Session session = new DefaultSession();
        TurnContext turnCtx = DefaultTurnContext.createWithBroadcast(
            session, defaultConfig(), null, "propagation");
        DefaultToolExecutionContext toolCtx = new DefaultToolExecutionContext(turnCtx);

        assertThat(toolCtx.cancellation().isCancelled()).isFalse();
        turnCtx.cancellation().fire();
        // Tool ctx sees the cancellation — both reference the same token.
        assertThat(toolCtx.cancellation().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("backCompat_4ArgConstructor_createsFreshUnbroadcastToken")
    void backCompat_4ArgConstructor_createsFreshUnbroadcastToken() {
        // Story #001 / #004 callers used the 4-arg constructor; that path must still work.
        // The 4-arg delegate creates a fresh CancellationToken (not broadcast-registered).
        AgentFactory.clearBroadcastRegistryForTest();

        Session session = new DefaultSession();
        TurnContext turnCtx = new DefaultTurnContext(
            session, defaultConfig(), null, "back-compat 4-arg");

        assertThat(turnCtx.cancellation()).isNotNull();
        assertThat(turnCtx.cancellation().isCancelled()).isFalse();
        // 4-arg constructor does NOT register with the broadcast registry
        assertThat(AgentFactory.activeTurnCount())
            .as("4-arg constructor must not auto-register with broadcast registry")
            .isEqualTo(0);
    }

    @Test
    @DisplayName("createWithBroadcast_autoRegistersWithBroadcastRegistry")
    void createWithBroadcast_autoRegistersWithBroadcastRegistry() {
        AgentFactory.clearBroadcastRegistryForTest();

        Session s = new DefaultSession();
        TurnContext t = DefaultTurnContext.createWithBroadcast(
            s, defaultConfig(), null, "broadcast-test");

        assertThat(AgentFactory.activeTurnCount())
            .as("createWithBroadcast must auto-register the token")
            .isEqualTo(1);

        // The registered token is the one we got back from turnCtx.cancellation().
        t.cancellation().fire();
        assertThat(t.cancellation().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("nullSessionOrConfig_5ArgConstructor_rejected")
    void nullSessionOrConfig_5ArgConstructor_rejected() {
        AgentFactory.clearBroadcastRegistryForTest();
        Session session = new DefaultSession();
        AgentConfig cfg = defaultConfig();

        assertThatThrownBy(() -> new DefaultTurnContext(null, cfg, null, "x",
            CancellationTokens.create()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultTurnContext(session, null, null, "x",
            CancellationTokens.create()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultTurnContext(session, cfg, null, "x", null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
