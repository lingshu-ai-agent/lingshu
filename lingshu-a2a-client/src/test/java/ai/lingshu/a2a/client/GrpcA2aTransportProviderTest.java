package ai.lingshu.a2a.client;

import ai.lingshu.a2a.server.LingsA2aServerException;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 unit tests — {@link GrpcA2aTransportProvider} (4 cases).
 */
class GrpcA2aTransportProviderTest {

    @Test
    @DisplayName("TC-PROV-1: create_validCfg_returnsGrpcA2aTransport")
    void create_validCfg_returnsGrpcA2aTransport() {
        GrpcA2aTransportProvider provider = new GrpcA2aTransportProvider();
        assertThat(provider.name()).isEqualTo("grpc-1.0.0");
        assertThat(provider.priority()).isEqualTo(10);
        assertThat(provider.version()).isEqualTo("1.0.0");

        AgentConfig cfg = cfg();
        A2aTransport t = provider.create(cfg);
        assertThat(t).isInstanceOf(GrpcA2aTransport.class);
        GrpcA2aTransport gt = (GrpcA2aTransport) t;
        assertThat(gt.getChannel()).isNotNull();
        assertThat(gt.getGrpcTarget()).isEqualTo("localhost:50051");
        assertThat(gt.getCardCache()).isNotNull();
        assertThat(gt.getCardCache().getCacheTtl()).isEqualTo(Duration.ofMinutes(5));

        gt.close();
    }

    @Test
    @DisplayName("TC-PROV-2: create_nullCfg_usesHardcodedDefaults (forward-compat)")
    void create_nullCfg_usesHardcodedDefaults() {
        GrpcA2aTransportProvider provider = new GrpcA2aTransportProvider();
        A2aTransport t = provider.create(null);
        assertThat(((GrpcA2aTransport) t).getGrpcTarget()).isEqualTo("localhost:50051");
        ((GrpcA2aTransport) t).close();
    }

    @Test
    @DisplayName("TC-PROV-3: create_closeReleasesChannel (lifecycle)")
    void create_closeReleasesChannel() throws InterruptedException {
        GrpcA2aTransportProvider provider = new GrpcA2aTransportProvider();
        A2aTransport t = provider.create(cfg());
        GrpcA2aTransport gt = (GrpcA2aTransport) t;

        assertThat(gt.getChannel().isShutdown()).isFalse();
        gt.close();
        gt.getChannel().awaitTermination(2, TimeUnit.SECONDS);
        assertThat(gt.getChannel().isShutdown()).isTrue();
    }

    @Test
    @DisplayName("TC-PROV-4: provider_implementsA2aTransportProviderInterface")
    void provider_implementsA2aTransportProviderInterface() {
        GrpcA2aTransportProvider provider = new GrpcA2aTransportProvider();
        assertThat(provider).isInstanceOf(ai.lingshu.core.spi.Providers.A2aTransportProvider.class);
    }

    @Test
    @DisplayName("TC-PROV-5: emptyGrpcTarget_throwsLingsS07 (EC-1)")
    void emptyGrpcTarget_throwsLingsS07() {
        GrpcA2aTransportProvider provider = new GrpcA2aTransportProvider();
        AgentConfig c = withGrpcTarget(cfg(), "");

        assertThatThrownBy(() -> provider.create(c))
            .isInstanceOf(LingsA2aServerException.class)
            .hasMessageContaining("LINGS-S07");
    }

    @Test
    @DisplayName("TC-PROV-6: whitespaceGrpcTarget_throwsLingsS07")
    void whitespaceGrpcTarget_throwsLingsS07() {
        GrpcA2aTransportProvider provider = new GrpcA2aTransportProvider();
        AgentConfig c = withGrpcTarget(cfg(), "   ");

        assertThatThrownBy(() -> provider.create(c))
            .isInstanceOf(LingsA2aServerException.class);
    }

    // --- helpers ---

    private static AgentConfig cfg() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("default", "noop",
                java.nio.file.Paths.get("."), Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            "default", "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            "default",
            null,
            AgentConfig.A2a.defaults()
        );
    }

    private static AgentConfig withGrpcTarget(AgentConfig c, String target) {
        return new AgentConfig(
            c.getFlowEngine(), c.getLlm(), c.getPrompt(), c.getToolExecutor(),
            c.getSandbox(), c.getCompactor(), c.getSessionStore(),
            c.getDelegate(), c.getMcp(), c.getSkills(),
            c.getToolParallelism(), c.getToolTimeoutSeconds(),
            c.getApprovalTimeoutSeconds(), c.getTurnTimeoutSeconds(),
            c.getLlmTimeoutSeconds(), c.getReactMaxSteps(),
            c.getIdentity(), c.getInstructions(), c.getMemory(),
            c.getA2aTransport(), c.getTenants(),
            new AgentConfig.A2a(
                c.getA2a().getHost(),
                c.getA2a().getPort(),
                target,
                c.getA2a().getCardTtl())
        );
    }
}
