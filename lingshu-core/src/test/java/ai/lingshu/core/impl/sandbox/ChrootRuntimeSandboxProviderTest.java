package ai.lingshu.core.impl.sandbox;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.RuntimeSandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #028 — L1 tests for {@link ChrootRuntimeSandboxProvider} (dsh §5.5 default
 * RuntimeSandboxProvider for the {@code chroot} name slot).
 *
 * <p>AC-NN-4 contract:
 * <ol>
 *   <li>{@link ChrootRuntimeSandboxProvider#name()} returns {@code "chroot"} — must
 *       match the default {@code AgentConfig.Sandbox.runtime} value and the design doc
 *       §6.3 template name (so {@code agent.sandbox.runtime: chroot} resolves out of
 *       the box)</li>
 *   <li>{@link ChrootRuntimeSandboxProvider#priority()} returns {@code 10} — user-supplied
 *       higher-priority providers can win on the same slot</li>
 *   <li>{@link ChrootRuntimeSandboxProvider#create(AgentConfig)} returns the Spring-wired
 *       {@link DefaultRuntimeSandbox} singleton</li>
 * </ol>
 */
class ChrootRuntimeSandboxProviderTest {

    @Test
    @DisplayName("AC-NN-4: provider.name() returns 'chroot' (matches default runtime slot)")
    void nameIsChroot() {
        ChrootRuntimeSandboxProvider provider =
            new ChrootRuntimeSandboxProvider(stubSandbox());

        assertThat(provider.name()).isEqualTo("chroot");
    }

    @Test
    @DisplayName("AC-NN-4: provider.priority() returns 10 (default wins when no override)")
    void priorityIsTen() {
        ChrootRuntimeSandboxProvider provider =
            new ChrootRuntimeSandboxProvider(stubSandbox());

        assertThat(provider.priority()).isEqualTo(10);
    }

    @Test
    @DisplayName("AC-NN-4: provider.version() returns 1.0.0 (semver ContractVersionRef)")
    void versionIsSemver() {
        ChrootRuntimeSandboxProvider provider =
            new ChrootRuntimeSandboxProvider(stubSandbox());

        assertThat(provider.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("AC-NN-4: provider.create(AgentConfig) returns the wired DefaultRuntimeSandbox")
    void createReturnsWiredSingleton() {
        DefaultRuntimeSandbox sandbox = stubSandbox();
        ChrootRuntimeSandboxProvider provider = new ChrootRuntimeSandboxProvider(sandbox);

        RuntimeSandbox created = provider.create(stubConfig());

        assertThat(created).isSameAs(sandbox);
    }

    @Test
    @DisplayName("AC-NN-4: provider.create ignores config arg (singleton strategy)")
    void createIgnoresConfigArg() {
        DefaultRuntimeSandbox sandbox = stubSandbox();
        ChrootRuntimeSandboxProvider provider = new ChrootRuntimeSandboxProvider(sandbox);

        // Same sandbox instance regardless of config — Spring wiring owns lifecycle
        assertThat(provider.create(stubConfig())).isSameAs(sandbox);
        assertThat(provider.create(stubConfig())).isSameAs(sandbox);
    }

    // ── helpers ───────────────────────────────────────────

    private static DefaultRuntimeSandbox stubSandbox() {
        return new DefaultRuntimeSandbox(null, stubConfig());
    }

    private static AgentConfig stubConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox(null, null, null, null, null),
            "default",
            "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
                        AgentConfig.ToolsConfig.defaults(), "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }
}