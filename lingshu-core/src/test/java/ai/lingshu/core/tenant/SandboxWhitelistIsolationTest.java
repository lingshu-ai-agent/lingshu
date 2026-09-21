package ai.lingshu.core.tenant;

import ai.lingshu.core.impl.sandbox.DefaultRuntimeSandbox;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #006 — L1 unit tests for per-tenant command whitelist isolation (US4,
 * FR-002, FR-013, AC-05).
 *
 * <p>Three core assertions:
 * <ul>
 *   <li><b>alice denies git, bob allows git</b> — whitelist lookup honours the
 *       tenantId from {@link TenantContext}.</li>
 *   <li><b>No tenant → global whitelist</b> — single-tenant mode (or unset
 *       TenantContext) still works, just against the global AgentConfig
 *       commandWhitelist.</li>
 *   <li><b>Unknown tenant → global fallback</b> — defensive, mirrors Story #006
 *       Edge Case.</li>
 * </ul>
 */
class SandboxWhitelistIsolationTest {

    @AfterEach
    void clearTenantContext() {
        while (TenantContext.current() != null) {
            TenantContext.clear();
        }
    }

    private static TenantConfig tenantWithWhitelist(String tid, List<String> whitelist) {
        return TenantConfig.builder()
            .tenantId(tid)
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/" + tid)).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(whitelist == null ? null : new java.util.ArrayList<>(whitelist))
                .build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(10_000_000L).build())
            .build();
    }

    private static TenantConfigProvider stubProvider(TenantConfig... tenants) {
        Map<String, TenantConfig> map = new HashMap<>();
        for (TenantConfig tc : tenants) {
            map.put(tc.getTenantId(), tc);
        }
        return new TenantConfigProvider() {
            @Override public String name() { return "stub"; }
            @Override public int priority() { return 0; }
            @Override public java.util.Optional<TenantConfig> resolve(String tenantId) {
                return java.util.Optional.ofNullable(map.get(tenantId));
            }
            @Override public List<String> listTenantIds() {
                return Collections.unmodifiableList(
                    Arrays.asList(map.keySet().toArray(new String[0])));
            }
        };
    }

    /** Global fallback config — only the sandbox fields matter for these tests. */
    private static AgentConfig fallbackConfig(List<String> globalWhitelist) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                globalWhitelist == null ? Collections.<String>emptyList() : globalWhitelist,
                Collections.<String>emptyList()),
            "default",
            "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,                                       // a2aTransport
            null,                                       // tenants (single-tenant mode)
            AgentConfig.A2a.defaults());                // a2a (Story #009)
    }

    @Test
    @DisplayName("L1-014: aliceWhitelistRejectsGit_bobWhitelistAllowsGit")
    void aliceWhitelistRejectsGit_bobWhitelistAllowsGit() {
        TenantConfig alice = tenantWithWhitelist("alice", Arrays.asList("ls", "cat"));
        TenantConfig bob = tenantWithWhitelist("bob", Arrays.asList("ls", "cat", "git"));
        TenantConfigProvider provider = stubProvider(alice, bob);
        AgentConfig fallback = fallbackConfig(Arrays.asList("ls")); // global: just ls
        RuntimeSandbox sandbox = new DefaultRuntimeSandbox(provider, fallback);

        // alice's whitelist has no "git" → PermissionDeniedException.
        // ProcessRunner.run() declares IOException; convert to RuntimeException for
        // the Supplier lambda since TenantContext.runAs expects no checked throws.
        assertThatThrownBy(() ->
                TenantContext.runAs("alice", () -> {
                    try {
                        sandbox.process().run("git", Arrays.asList("status"), null);
                    } catch (java.io.IOException ioe) {
                        throw new RuntimeException(ioe);
                    }
                    return null;
                }))
            .isInstanceOf(ToolException.PermissionDeniedException.class)
            .hasMessageContaining("'git'")
            .hasMessageContaining("alice");

        // bob's whitelist allows "git" → the whitelist gate passes. The actual
        // ProcessBuilder.start() may still fail with IOException on the test host if
        // `git` is not installed — that's fine, we only care that the whitelist didn't
        // throw PermissionDeniedException. We catch all exceptions and verify the
        // permission class was not raised.
        Throwable[] caught = new Throwable[1];
        TenantContext.runAs("bob", () -> {
            try {
                sandbox.process().run("git", Arrays.asList("status"), null);
            } catch (Exception t) {
                caught[0] = t;
            }
            return null;
        });
        if (caught[0] != null) {
            assertThat(caught[0]).isNotInstanceOf(ToolException.PermissionDeniedException.class);
        }
    }

    @Test
    @DisplayName("L1-016: noTenantContext_fallsBackToGlobalWhitelist")
    void noTenantContext_fallsBackToGlobalWhitelist() {
        TenantConfig alice = tenantWithWhitelist("alice", Arrays.asList("ls", "cat"));
        TenantConfigProvider provider = stubProvider(alice);
        // Global whitelist permits "ls" but not "git"
        AgentConfig fallback = fallbackConfig(Arrays.asList("ls"));
        RuntimeSandbox sandbox = new DefaultRuntimeSandbox(provider, fallback);

        // Without TenantContext, the call falls back to the global whitelist.
        // "ls" is on the global whitelist → no PermissionDeniedException.
        Throwable[] caught = new Throwable[1];
        try {
            sandbox.process().run("ls", Arrays.asList("-la"), null);
        } catch (Exception t) {
            caught[0] = t;
        }
        if (caught[0] != null) {
            assertThat(caught[0]).isNotInstanceOf(ToolException.PermissionDeniedException.class);
        }

        // A command NOT on the global whitelist is denied — same deny class as the
        // per-tenant case, but the message identifies the fallback as global.
        assertThatThrownBy(() -> {
            try {
                sandbox.process().run("git", Arrays.asList("status"), null);
            } catch (java.io.IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }).isInstanceOf(ToolException.PermissionDeniedException.class)
            .hasMessageContaining("global");
    }

    @Test
    @DisplayName("Edge: unknownTenant_fallsBackToGlobalWhitelist")
    void unknownTenant_fallsBackToGlobalWhitelist() {
        TenantConfig alice = tenantWithWhitelist("alice", Arrays.asList("ls", "cat"));
        TenantConfigProvider provider = stubProvider(alice);
        AgentConfig fallback = fallbackConfig(Arrays.asList("ls", "cat"));
        RuntimeSandbox sandbox = new DefaultRuntimeSandbox(provider, fallback);

        // "ghost" is not in the provider → defensive fallback to global whitelist.
        // Global allows "cat" → whitelist gate passes (no PermissionDeniedException).
        Throwable[] caught = new Throwable[1];
        TenantContext.runAs("ghost", () -> {
            try {
                sandbox.process().run("cat", Arrays.asList("/etc/hostname"), null);
            } catch (Exception t) {
                caught[0] = t;
            }
            return null;
        });
        if (caught[0] != null) {
            assertThat(caught[0]).isNotInstanceOf(ToolException.PermissionDeniedException.class);
        }

        // Global doesn't allow "git" → PermissionDeniedException.
        assertThatThrownBy(() ->
                TenantContext.runAs("ghost", () -> {
                    try {
                        sandbox.process().run("git", Arrays.asList("status"), null);
                    } catch (java.io.IOException ioe) {
                        throw new RuntimeException(ioe);
                    }
                    return null;
                }))
            .isInstanceOf(ToolException.PermissionDeniedException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("Edge: emptyWhitelist_deniesAll")
    void emptyWhitelist_deniesAll() {
        TenantConfig alice = tenantWithWhitelist("alice", Collections.<String>emptyList());
        TenantConfigProvider provider = stubProvider(alice);
        AgentConfig fallback = fallbackConfig(Arrays.asList("ls")); // global allows "ls"
        RuntimeSandbox sandbox = new DefaultRuntimeSandbox(provider, fallback);

        // alice's empty whitelist denies even "ls" — the global whitelist is irrelevant
        // when the tenant has its own explicit (empty) whitelist.
        assertThatThrownBy(() ->
                TenantContext.runAs("alice", () -> {
                    try {
                        sandbox.process().run("ls", Collections.<String>emptyList(), null);
                    } catch (java.io.IOException ioe) {
                        throw new RuntimeException(ioe);
                    }
                    return null;
                }))
            .isInstanceOf(ToolException.PermissionDeniedException.class);
    }

    @Test
    @DisplayName("Edge: nullOrEmptyCommand_throwsIAE")
    void nullOrEmptyCommand_throwsIAE() {
        TenantConfigProvider provider = stubProvider();
        RuntimeSandbox sandbox = new DefaultRuntimeSandbox(provider, fallbackConfig(null));

        assertThatThrownBy(() -> {
            try {
                sandbox.process().run(null, Collections.<String>emptyList(), null);
            } catch (java.io.IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            try {
                sandbox.process().run("", Collections.<String>emptyList(), null);
            } catch (java.io.IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Sanity: ofWhitelist_factoryReturnsImmutableView")
    void ofWhitelist_factoryReturnsImmutableView() {
        // The TenantConfig.Sandbox.ofWhitelist() factory defensively copies into
        // an unmodifiableList. The bare Lombok Builder does not — only the factory
        // guarantees the immutable invariant. Verify the factory path.
        java.util.ArrayList<String> source = new java.util.ArrayList<>(Arrays.asList("ls"));
        TenantConfig.Sandbox sb = TenantConfig.Sandbox.ofWhitelist(source);
        assertThatThrownBy(() ->
                sb.getCommandWhitelist().add("rm"))
            .isInstanceOf(UnsupportedOperationException.class);
        // Mutating the source after construction does NOT affect the Sandbox.
        source.add("rm");
        assertThat(sb.getCommandWhitelist()).containsExactly("ls").doesNotContain("rm");
    }
}
