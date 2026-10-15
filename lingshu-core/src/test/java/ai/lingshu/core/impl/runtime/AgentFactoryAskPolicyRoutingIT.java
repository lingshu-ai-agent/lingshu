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
package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.permission.AskUserPermissionPolicy;
import ai.lingshu.core.impl.permission.StrictPermissionPolicy;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #047 — L1/L2 slice test for the post-Bug-A {@code AgentFactory.create()}
 * Slot 4 {@link PermissionPolicy} routing at
 * {@code AgentFactory.java:285}. Verifies that the top-level
 * {@code agent.permission-policy} YAML key drives
 * {@code policyRouter.resolve(...)} instead of the Slot 3 sub-field
 * {@code config.getSandbox().getPolicy()}.
 *
 * <p><b>Verification strategy</b> — wrap each {@link Providers.PermissionPolicyProvider}
 * so its {@code create(AgentConfig)} records (a) which Provider's
 * {@code create()} was invoked (captures the Router's resolution path) and
 * (b) what instance was returned (captures the resolved concrete type).
 * Reflection on {@code DefaultAgent} is not viable because the post-Story-#001
 * AgentFactory does not wire {@code permissionPolicy} into {@code DefaultAgent}
 * (see line 314-316 comment "ToolExecutor / PermissionPolicy / PromptBuilder
 * are resolved but not yet injected into the LinearTurnEngine"); the
 * Provider-layer capture is the only single-source-of-truth path.
 *
 * <p><b>Mirrors precedent</b> — Story #029 {@code PermissionPolicyRouterStrictIT}
 * (4 L2 cases) and Story #030 {@code PermissionPolicyRouterAskIT} (3 L2 cases)
 * lock the Router-level behavior; this test adds the AgentFactory-level slice
 * to close Bug A's wiring gap.
 *
 * <p><b>AC mapping</b>
 * <ul>
 *   <li>AC-047-1 → {@link #agentFactory_create_resolvesPermissionPolicyTopLevel_notSandboxPolicy}</li>
 *   <li>AC-047-2 → {@link #agentFactory_create_permissionPolicyAsk_resolvesAskUserPermissionPolicy}</li>
 *   <li>AC-047-3 → {@link #agentFactory_create_permissionPolicyDefault_resolvesAllowAllPermissionPolicy}</li>
 * </ul>
 */
class AgentFactoryAskPolicyRoutingIT {

    /** Last Provider's {@code name()} whose {@code create()} was invoked. */
    private final AtomicReference<String> lastProviderName = new AtomicReference<>();
    /** Concrete instance returned by the resolved Provider's {@code create()}. */
    private final AtomicReference<PermissionPolicy> resolvedPolicy = new AtomicReference<>();

    @BeforeEach
    void resetCapture() {
        lastProviderName.set(null);
        resolvedPolicy.set(null);
    }

    private static Path writeTemp(String content) throws IOException {
        Path tmp = Files.createTempFile("agent-yml-askpolicy-routing-", ".yml");
        Files.write(tmp, content.getBytes("UTF-8"));
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /**
     * Wrap a Provider so its {@code create()} records both the Provider name
     * (for routing-path assertions) and the returned instance (for type
     * assertions). The {@code instanceFactory} is called with the parsed
     * {@link AgentConfig} so per-test tools config can flow through.
     */
    private Providers.PermissionPolicyProvider recordingProvider(
            final String name, final int priority,
            final InstanceFactory instanceFactory) {
        return new Providers.PermissionPolicyProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return priority; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) {
                lastProviderName.set(name);
                PermissionPolicy instance = instanceFactory.build(cfg);
                resolvedPolicy.set(instance);
                return instance;
            }
        };
    }

    /** Lambda-shaped factory for the Policy instance a Provider returns. */
    private interface InstanceFactory {
        PermissionPolicy build(AgentConfig cfg);
    }

    /**
     * Build the 9-Router stub factory with a custom {@link Routers.PermissionPolicyRouter}.
     * The other 8 Routers carry singleton stub Providers whose {@code create()}
     * returns {@code null} (this test never calls {@link AgentFactory#create(AgentConfig)}
     * in a way that needs them). Order MUST match the 9-Router @Autowired ctor
     * of {@link AgentFactory} exactly (L118-137 as of Story #047).
     */
    private static AgentFactory stubFactory(Routers.PermissionPolicyRouter policyRouter) {
        Providers.LlmProviderProvider llm = new Providers.LlmProviderProvider() {
            @Override public String name() { return "anthropic"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
        Providers.ToolExecutorProvider tool = new Providers.ToolExecutorProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) { return null; }
        };
        Providers.PromptBuilderProvider prompt = new Providers.PromptBuilderProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return null; }
        };
        Providers.FlowEngineProvider flow = new Providers.FlowEngineProvider() {
            @Override public String name() { return "linear"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
        Providers.MemorySourceProvider memory = new Providers.MemorySourceProvider() {
            @Override public String name() { return "project-claude-md"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public MemorySource create(AgentConfig cfg) { return null; }
        };
        Providers.RuntimeSandboxProvider sandbox = new Providers.RuntimeSandboxProvider() {
            @Override public String name() { return "chroot"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public RuntimeSandbox create(AgentConfig cfg) { return null; }
        };
        Providers.SessionStoreProvider sessionStore = new Providers.SessionStoreProvider() {
            @Override public String name() { return "memory"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public SessionStore create(AgentConfig cfg) { return null; }
        };
        Providers.CompactorProvider compactor = new Providers.CompactorProvider() {
            @Override public String name() { return "truncating"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public Compactor create(AgentConfig cfg) { return null; }
        };
        return new AgentFactory(
            new Routers.LlmProviderRouter(Collections.singletonList(llm)),
            new Routers.ToolExecutorRouter(Collections.singletonList(tool)),
            policyRouter,
            new Routers.PromptBuilderRouter(Collections.singletonList(prompt)),
            new Routers.FlowEngineRouter(Collections.singletonList(flow)),
            new Routers.MemorySourceRouter(Collections.singletonList(memory)),
            new Routers.RuntimeSandboxRouter(Collections.singletonList(sandbox)),
            new Routers.SessionStoreRouter(Collections.singletonList(sessionStore)),
            new Routers.CompactorRouter(Collections.singletonList(compactor)));
    }

    /**
     * AC-047-1 — Bug A fix verification. With yml having BOTH
     * {@code agent.permission-policy: ask} (Slot 4 top-level) AND
     * {@code agent.sandbox.policy: default} (Slot 3 sub-field), the resolved
     * Provider must be the "ask" one, not the "default" one.
     *
     * <p>Pre-Story-#047, line 285 read {@code config.getSandbox().getPolicy()}
     * which silently returned "default" for any config without an explicit
     * {@code sandbox.policy: ask} entry — making the "ask" Provider unreachable
     * via the top-level {@code permission-policy} key. This test would have
     * failed pre-fix: the assertion would have observed {@code "default"}
     * instead of {@code "ask"}.
     */
    @Test
    @DisplayName("AC-047-1: AgentFactory.create_resolvesPermissionPolicyTopLevel_notSandboxPolicy")
    void agentFactory_create_resolvesPermissionPolicyTopLevel_notSandboxPolicy() throws IOException {
        // 2 Providers — both return dummy AllowAll instances; we only care which one is called.
        Routers.PermissionPolicyRouter policyRouter = new Routers.PermissionPolicyRouter(
            Arrays.<Providers.PermissionPolicyProvider>asList(
                recordingProvider("default", 0, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        return new AllowAllPermissionPolicy();
                    }
                }),
                recordingProvider("ask", 10, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        return new AllowAllPermissionPolicy();
                    }
                })));

        AgentFactory factory = stubFactory(policyRouter);
        Path yml = writeTemp(
            "agent:\n" +
            "  permission-policy: ask\n" +
            "  sandbox:\n" +
            "    policy: default\n");
        AgentConfig cfg = factory.loadYamlAndValidate(yml);
        factory.create(cfg);

        assertThat(lastProviderName.get())
            .as("Bug A fix: yml `permission-policy: ask` must route to \"ask\" Provider, "
                + "NOT to `sandbox.policy: default`. Pre-Story-#047 line 285 read "
                + "`config.getSandbox().getPolicy()` which silently forced \"default\".")
            .isEqualTo("ask");
    }

    /**
     * AC-047-2 — With yml {@code agent.permission-policy: ask}, the resolved
     * {@link PermissionPolicy} instance must be an {@link AskUserPermissionPolicy}.
     *
     * <p>This is the e2e behavior the demo-product Story #030 backend wired:
     * the {@code AskUserPermissionPolicy.check()} 5-path decision
     * (deny-list → ask-list → allow-empty → allow-hit → deny-miss) only runs
     * when the Router actually resolves the "ask" name. Pre-Bug-A-fix the
     * Router resolved "default" → {@code AllowAllPermissionPolicy.check()}
     * unconditionally returned {@code Decision.Allow} → no ApprovalRequired
     * events ever fired in demo-product.
     */
    @Test
    @DisplayName("AC-047-2: AgentFactory.create_permissionPolicyAsk_resolvesAskUserPermissionPolicy")
    void agentFactory_create_permissionPolicyAsk_resolvesAskUserPermissionPolicy() throws IOException {
        // 3 Providers — Router has ask + strict + default registered; "ask" must win.
        Routers.PermissionPolicyRouter policyRouter = new Routers.PermissionPolicyRouter(
            Arrays.<Providers.PermissionPolicyProvider>asList(
                recordingProvider("default", 0, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        return new AllowAllPermissionPolicy();
                    }
                }),
                recordingProvider("strict", 10, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        // Story #031 refactor: StrictPermissionPolicy 1-arg ctor takes
                        // AgentConfig.ToolsConfig (delegates to 2-arg ctor with empty map).
                        return new StrictPermissionPolicy(cfg.getTools());
                    }
                }),
                recordingProvider("ask", 10, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        // AskUserPermissionPolicy 1-arg ctor (Story #030 test fixture ctor)
                        // delegates to 2-arg ctor with empty nameToCategory map.
                        return new AskUserPermissionPolicy(cfg.getTools());
                    }
                })));

        AgentFactory factory = stubFactory(policyRouter);
        Path yml = writeTemp(
            "agent:\n" +
            "  permission-policy: ask\n");
        AgentConfig cfg = factory.loadYamlAndValidate(yml);
        factory.create(cfg);

        assertThat(lastProviderName.get()).isEqualTo("ask");
        assertThat(resolvedPolicy.get())
            .as("Story #030 + #047 wiring: permission-policy: ask must resolve to "
                + "AskUserPermissionPolicy (5-path decision with deny-list → ask-list → "
                + "allow-empty → allow-hit → deny-miss).")
            .isInstanceOf(AskUserPermissionPolicy.class);
    }

    /**
     * AC-047-3 — Regression: yml {@code agent.permission-policy: default} must
     * still resolve to {@link AllowAllPermissionPolicy} (yolo back-compat).
     *
     * <p>Story #001's yolo path is the historical default; demo-empty and
     * any user yml without an explicit {@code permission-policy} key must
     * continue to behave identically (no AskUser flow, no Strict deny
     * surprises) after the Bug A fix. This test locks the regression.
     */
    @Test
    @DisplayName("AC-047-3: AgentFactory.create_permissionPolicyDefault_resolvesAllowAllPermissionPolicy")
    void agentFactory_create_permissionPolicyDefault_resolvesAllowAllPermissionPolicy() throws IOException {
        // Same 3-Provider Router as AC-2 — only the YAML key changes.
        Routers.PermissionPolicyRouter policyRouter = new Routers.PermissionPolicyRouter(
            Arrays.<Providers.PermissionPolicyProvider>asList(
                recordingProvider("default", 0, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        return new AllowAllPermissionPolicy();
                    }
                }),
                recordingProvider("strict", 10, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        // Story #031 refactor: StrictPermissionPolicy 1-arg ctor takes
                        // AgentConfig.ToolsConfig (delegates to 2-arg ctor with empty map).
                        return new StrictPermissionPolicy(cfg.getTools());
                    }
                }),
                recordingProvider("ask", 10, new InstanceFactory() {
                    @Override public PermissionPolicy build(AgentConfig cfg) {
                        return new AskUserPermissionPolicy(cfg.getTools());
                    }
                })));

        AgentFactory factory = stubFactory(policyRouter);
        Path yml = writeTemp(
            "agent:\n" +
            "  permission-policy: default\n");
        AgentConfig cfg = factory.loadYamlAndValidate(yml);
        factory.create(cfg);

        assertThat(lastProviderName.get()).isEqualTo("default");
        assertThat(resolvedPolicy.get())
            .as("yolo back-compat regression: permission-policy: default must continue "
                + "to resolve AllowAllPermissionPolicy (no AskUser flow, no Strict deny).")
            .isInstanceOf(AllowAllPermissionPolicy.class);
    }
}
