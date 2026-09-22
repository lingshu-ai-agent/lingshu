package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #005 — L2 tests for {@link AgentFactory#broadcastCancel()} and the static
 * {@link AgentFactory#BROADCAST_REGISTRY} lifecycle.
 *
 * <p>Verifies FR-009: Ctrl-C → JVM shutdown hook → broadcastCancel() → all in-flight
 * turns see {@code isCancelled() == true}.
 */
class AgentFactoryBroadcastCancelTest {

    private AgentFactory factory;

    @BeforeEach
    void setUp() {
        AgentFactory.clearBroadcastRegistryForTest();

        // Build minimal stub providers (mirrors AgentFactoryDescriptionTest pattern)
        Providers.LlmProviderProvider llmP = new Providers.LlmProviderProvider() {
            @Override public String name() { return "anthropic"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
        Providers.ToolExecutorProvider toolP = new Providers.ToolExecutorProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) { return null; }
        };
        Providers.PermissionPolicyProvider permP = new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "allow-all"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return null; }
        };
        Providers.PromptBuilderProvider promptP = new Providers.PromptBuilderProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return null; }
        };
        Providers.FlowEngineProvider flowP = new Providers.FlowEngineProvider() {
            @Override public String name() { return "linear"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
        Providers.MemorySourceProvider memP = new Providers.MemorySourceProvider() {
            @Override public String name() { return "identity"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public MemorySource create(AgentConfig cfg) { return null; }
        };

        factory = new AgentFactory(
            new Routers.LlmProviderRouter(Collections.singletonList(llmP)),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolP)),
            new Routers.PermissionPolicyRouter(Collections.singletonList(permP)),
            new Routers.PromptBuilderRouter(Collections.singletonList(promptP)),
            new Routers.FlowEngineRouter(Collections.singletonList(flowP)),
            new Routers.MemorySourceRouter(Collections.singletonList(memP)));
    }

    private static AgentConfig defaultConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults());  // compactorConfig (Story #018)
    }

    @Test
    @DisplayName("broadcastCancel_firesAllRegisteredTokens")
    void broadcastCancel_firesAllRegisteredTokens() {
        Session s1 = new DefaultSession();
        Session s2 = new DefaultSession();
        Session s3 = new DefaultSession();

        TurnContext t1 = DefaultTurnContext.createWithBroadcast(s1, defaultConfig(), null, "t1");
        TurnContext t2 = DefaultTurnContext.createWithBroadcast(s2, defaultConfig(), null, "t2");
        TurnContext t3 = DefaultTurnContext.createWithBroadcast(s3, defaultConfig(), null, "t3");

        assertThat(AgentFactory.activeTurnCount()).isEqualTo(3);
        assertThat(t1.cancellation().isCancelled()).isFalse();
        assertThat(t2.cancellation().isCancelled()).isFalse();
        assertThat(t3.cancellation().isCancelled()).isFalse();

        factory.broadcastCancel();

        assertThat(t1.cancellation().isCancelled()).isTrue();
        assertThat(t2.cancellation().isCancelled()).isTrue();
        assertThat(t3.cancellation().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("broadcastCancel_isIdempotent_doubleCallSafe")
    void broadcastCancel_isIdempotent_doubleCallSafe() {
        Session s = new DefaultSession();
        TurnContext t = DefaultTurnContext.createWithBroadcast(s, defaultConfig(), null, "t");

        factory.broadcastCancel();
        factory.broadcastCancel();   // second call must not throw

        assertThat(t.cancellation().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("activeTurnCount_reflectsRegistry")
    void activeTurnCount_reflectsRegistry() {
        assertThat(AgentFactory.activeTurnCount()).isZero();

        Session s1 = new DefaultSession();
        Session s2 = new DefaultSession();

        DefaultTurnContext.createWithBroadcast(s1, defaultConfig(), null, "t1");
        assertThat(AgentFactory.activeTurnCount()).isEqualTo(1);

        DefaultTurnContext.createWithBroadcast(s2, defaultConfig(), null, "t2");
        assertThat(AgentFactory.activeTurnCount()).isEqualTo(2);

        // 4-arg constructor does NOT register
        new DefaultTurnContext(s1, defaultConfig(),
            (Subscriber<AgentEvent>) null, "manual");
        assertThat(AgentFactory.activeTurnCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("broadcastCancel_unregisteredTurns_ignored")
    void broadcastCancel_unregisteredTurns_ignored() {
        // Turn created via 4-arg constructor (no broadcast registration)
        Session s = new DefaultSession();
        TurnContext t = new DefaultTurnContext(
            s, defaultConfig(), (Subscriber<AgentEvent>) null, "unreg");

        // Registry is empty (4-arg constructor doesn't add)
        assertThat(AgentFactory.activeTurnCount()).isZero();

        factory.broadcastCancel();

        // Token not registered → broadcastCancel skips it → still false
        assertThat(t.cancellation().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("registerCancellationStatic_nullToken_rejected")
    void registerCancellationStatic_nullToken_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> AgentFactory.registerCancellationStatic(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("clearBroadcastRegistryForTest_resetsStateAcrossTests")
    void clearBroadcastRegistryForTest_resetsStateAcrossTests() {
        // Demonstrate test isolation: even if another test left tokens in the registry,
        // clearBroadcastRegistryForTest() empties it.
        Session s = new DefaultSession();
        DefaultTurnContext.createWithBroadcast(s, defaultConfig(), null, "dirty");
        assertThat(AgentFactory.activeTurnCount()).isGreaterThan(0);

        AgentFactory.clearBroadcastRegistryForTest();
        assertThat(AgentFactory.activeTurnCount()).isZero();
    }
}
