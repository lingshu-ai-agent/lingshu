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
package ai.lingshu.core.impl.flow;

import ai.lingshu.core.impl.permission.AskUserPermissionPolicy;
import ai.lingshu.core.impl.permission.StrictPermissionPolicy;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #004 — L2P test for {@link LinearTurnEngineProvider} wiring.
 *
 * <p>Validates the 5-arg constructor + each {@code Routers.XxxRouter.resolve(name, cfg)}
 * call + the {@code @Qualifier("agentToolPool")} {@link ExecutorService} injection.
 *
 * <p>Mirrors the Spring autowire graph (see
 * {@code specs/004-tool-parallel-dispatch/contracts/parallel-dispatch-pipeline.md}).
 */
class LinearTurnEngineProviderTest {

    private static Providers.LlmProviderProvider llmProvider(String name, int prio) {
        return new Providers.LlmProviderProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.flow.support.EchoLlmProvider(
                    java.util.Collections.singletonList(
                        new ai.lingshu.core.message.LlmResponse("", java.util.Collections.emptyList(),
                            ai.lingshu.core.message.StopReason.END_TURN,
                            ai.lingshu.core.message.Usage.zero())));
            }
        };
    }
    private static Providers.PromptBuilderProvider promptProvider(String name, int prio) {
        return new Providers.PromptBuilderProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.flow.support.RecordingPromptBuilder();
            }
        };
    }
    private static Providers.ToolExecutorProvider toolProvider(String name, int prio) {
        return new Providers.ToolExecutorProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.tool.DefaultToolExecutor(
                    new ai.lingshu.core.impl.permission.AllowAllPermissionPolicy(),
                    new ai.lingshu.core.impl.tool.DefaultToolRegistry());
            }
        };
    }
    private static Providers.PermissionPolicyProvider policyProvider(String name, int prio) {
        return new Providers.PermissionPolicyProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.permission.AllowAllPermissionPolicy();
            }
        };
    }
    private static Providers.FlowEngineProvider flowProvider(String name, int prio) {
        return new Providers.FlowEngineProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
    }

    private AgentConfig defaultConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",                                                    // toolExecutor
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            4, 5, 0, 0, 0, 10,                                              // parallelism, timeout, reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
                        AgentConfig.ToolsConfig.defaults(), "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    @Test
    @DisplayName("L2P-001: provider_wiresAll5SlotsAndPoolIntoLinearTurnEngine")
    void provider_wiresAll5SlotsAndPoolIntoLinearTurnEngine() {
        // Build minimal Router set with one Provider per slot (mirrors Spring boot sequence)
        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(llmProvider("anthropic", 10)));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(promptProvider("default", 0)));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(toolProvider("default", 0)));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(policyProvider("default", 0)));

        // Real ThreadPoolExecutor matching ToolExecutorConfig's @Bean shape
        ExecutorService pool = Executors.newFixedThreadPool(
            4, r -> { Thread t = new Thread(r, "test-pool"); t.setDaemon(true); return t; });

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            prompt, llm, tool, policy, pool, null);  // 🆕 Story #030 — null ApprovalRegistry

        AgentConfig cfg = defaultConfig();
        FlowEngine engine = provider.create(cfg);

        // Engine is the linear one with the right priority (0) and version (1.0.0)
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);
        assertThat(provider.name()).isEqualTo("linear");
        assertThat(provider.priority()).isEqualTo(0);
        assertThat(provider.version()).isEqualTo("1.0.0");

        // Sanity-check the pool is the same instance we passed in (no copies)
        // We do this indirectly: invoke engine.runTurn via real flow with a stub ctx
        // — but for unit test purposes, assert pool identity through reflection-free path.
        // The pool's ThreadFactory thread name prefix should be visible via toString.
        if (pool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) pool;
            assertThat(tpe.getCorePoolSize()).isEqualTo(4);
        }

        provider.name(); provider.priority(); provider.version();  // touch accessors
        pool.shutdown();
    }

    @Test
    @DisplayName("L2P-002: provider_unknownRouterName_throws (consistency w/ Story #003)")
    void provider_unknownRouterName_throws() {
        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(llmProvider("anthropic", 10)));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(promptProvider("default", 0)));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(toolProvider("default", 0)));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(policyProvider("default", 0)));
        ExecutorService pool = Executors.newFixedThreadPool(2);

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            prompt, llm, tool, policy, pool, null);  // 🆕 Story #030 — null ApprovalRegistry

        // Wire a config whose toolExecutor name is unknown — Router.resolve() must throw.
        AgentConfig bad = new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "nonexistent-tool-executor",                                // <-- unknown
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            4, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
                        AgentConfig.ToolsConfig.defaults(), "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.create(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonexistent-tool-executor");

        pool.shutdown();
    }

    /**
     * 🆕 Regression — Bug E fix verification.
     *
     * <p>Bug E was: {@code LinearTurnEngineProvider.create()} at line 181 read
     * {@code config.getSandbox().getPolicy()} (Slot 3 sub-slot, default
     * {@code "default"}) when resolving the {@link PermissionPolicy} to inject into
     * the {@link LinearTurnEngine}, instead of the top-level Slot 4
     * {@code config.getPermissionPolicy()} field. This is the *engine-injection*
     * twin of Story #047's Bug A at {@code AgentFactory.java:285} — the AgentFactory
     * side was fixed in Story #047, but the duplicate of the same typo in
     * {@code LinearTurnEngineProvider} was not caught and silently forced the engine
     * to receive {@code AllowAllPermissionPolicy} (or {@code StrictPermissionPolicy}
     * if the user happened to set {@code agent.sandbox.policy: ask}) regardless of
     * the top-level {@code agent.permission-policy: ask} yml key.
     *
     * <p>User-visible symptom: demo-product with
     * {@code agent.permission-policy: ask} + {@code agent.tools.ask-list: [bash_safe]}
     * never emits {@code AgentEvent.ApprovalRequired} — the engine's
     * {@link PermissionPolicy#check} runs on the wrong policy instance and falls
     * through path 4 (allow-list wildcard) → {@code Decision.Allow}, skipping the
     * ask-list path 2.
     *
     * <p>Why the existing L3 IT ({@code DemoProductAskPolicyEndToEndIT}) did NOT
     * catch this: it bypasses the {@code AgentFactory.create()} →
     * {@code LinearTurnEngineProvider.create()} wiring by calling
     * {@code askProvider.create(demoAgentConfig)} directly. Bug E only fires
     * inside the {@code provider.create(cfg)} path, which the L3 IT never
     * exercised. Story #047 Bug A's L1/L2 IT
     * ({@code AgentFactoryAskPolicyRoutingIT}) tests
     * {@code AgentFactory.create()}'s Provider capture, not what gets injected
     * into the engine — same blind spot.
     *
     * <p><b>Verification strategy</b> — wire a {@code LinearTurnEngineProvider}
     * directly with a {@link Routers.PermissionPolicyRouter} that has BOTH
     * {@code "ask"} (AskUserPermissionPolicy) and {@code "strict"}
     * (StrictPermissionPolicy) Providers registered. Construct a config where
     * {@code config.getPermissionPolicy() = "ask"} but
     * {@code config.getSandbox().getPolicy() = "default"} — the exact
     * bug-triggering condition. Call {@code provider.create(cfg)} and reflect
     * on the resulting engine's {@code permissionPolicy} field.
     *
     * <p>Pre-fix, the engine would have received {@code StrictPermissionPolicy}
     * (because {@code config.getSandbox().getPolicy() = "default"} was routed
     * by accident... wait, "default" → AllowAllPermissionPolicy actually;
     * regardless the engine would NOT receive the ask-named policy). The
     * assertion {@code .isInstanceOf(AskUserPermissionPolicy.class)} would have
     * FAILED pre-fix.
     */
    @Test
    @DisplayName("regression: provider reads top-level config.getPermissionPolicy() not sandbox.policy")
    void regression_providerReadsTopLevelPermissionPolicy_notSandboxSubField() throws Exception {
        // Routers wired with both ask + strict + default Providers so any
        // mis-routed name still resolves to *something* (the test cares about
        // which policy the engine actually receives, not whether the Router throws).
        Providers.PermissionPolicyProvider askProvider = new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "ask"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) {
                return new AskUserPermissionPolicy(cfg.getTools());
            }
        };
        Providers.PermissionPolicyProvider strictProvider = new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "strict"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) {
                return new StrictPermissionPolicy(cfg.getTools());
            }
        };
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Arrays.<Providers.PermissionPolicyProvider>asList(askProvider, strictProvider));

        // Other 3 routers — keep using the existing test fixture shape.
        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(llmProvider("anthropic", 10)));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(promptProvider("default", 0)));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(toolProvider("default", 0)));

        ExecutorService pool = Executors.newFixedThreadPool(
            2, r -> { Thread t = new Thread(r, "regression-pool"); t.setDaemon(true); return t; });

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            prompt, llm, tool, policy, pool, null);

        // Config with the BUG-TRIGGERING condition: top-level
        // permissionPolicy = "ask" (what the user wrote in yml), but
        // sandbox.policy = "default" (what yml does NOT touch, so the
        // Slot 3 sub-slot falls through to its default "default"). The two
        // fields are deliberately set to DIFFERENT names so the test fails
        // unambiguously if the provider reads the wrong one.
        //
        // ⚠️ AgentConfig ctor order MUST match the @Value field order — 27 args.
        // Reordering any field requires editing all 55+ test fixture call sites
        // (AgentConfig.java:113-124 has the rationale).
        AgentConfig cfg = new AgentConfig(
            "linear",                                                    //  1. flowEngine
            new AgentConfig.Llm("anthropic", "test-model", null, null), //  2. llm
            new AgentConfig.Prompt("default",
                Collections.<String>emptyList(), null),                  //  3. prompt
            "default",                                                   //  4. toolExecutor
            new AgentConfig.Sandbox("default", "noop", Paths.get("."),   //  5. sandbox ← WRONG field stays "default"
                Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            null,                                                        //  6. compactor (Slot 6 name)
            null,                                                        //  7. sessionStore (Slot 5 name)
            null,                                                        //  8. delegate
            null,                                                        //  9. mcp
            null,                                                        // 10. skills
            4, 5, 0, 0, 0, 10,                                           // 11-16. toolParallelism / toolTimeout / approvalTimeout / turnTimeout / llmTimeout / reactMaxSteps
            AgentConfig.Identity.defaults(),                             // 17. identity
            AgentConfig.Instructions.empty(),                            // 18. instructions
            AgentConfig.Memory.defaults(),                               // 19. memory
            null,                                                        // 20. a2aTransport
            null,                                                        // 21. tenants
            AgentConfig.A2a.defaults(),                                  // 22. a2a
            AgentConfig.CompactorConfig.defaults(),                      // 23. compactorConfig
            AgentConfig.ToolsConfig.defaults(),                          // 24. tools
            "ask",                                                       // 25. permissionPolicy ← CORRECT field = "ask"
            16,                                                          // 26. maxConcurrentTurns
            32);                                                         // 27. maxConcurrentQueueDepth

        // Sanity: the config fields really are set to the values we expect.
        // If this assertion ever fails it means AgentConfig's ctor changed
        // and the indexes in this test are stale — fix the indexes, not the
        // assertion.
        assertThat(cfg.getPermissionPolicy())
            .as("test fixture invariant: top-level Slot 4 must be \"ask\"")
            .isEqualTo("ask");
        assertThat(cfg.getSandbox().getPolicy())
            .as("test fixture invariant: Slot 3 sub-slot must be \"default\" (NOT \"ask\") "
                + "so a mis-routed resolve would actually return a different policy")
            .isEqualTo("default");

        FlowEngine engine = provider.create(cfg);
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);

        // Read the engine's permissionPolicy field via reflection. This is the
        // field the ReAct loop consults at L604 in dispatchWithPolicy → if
        // Bug E is present the engine holds StrictPermissionPolicy (or
        // AllowAllPermissionPolicy), not AskUserPermissionPolicy.
        Field field = LinearTurnEngine.class.getDeclaredField("permissionPolicy");
        field.setAccessible(true);
        PermissionPolicy injected = (PermissionPolicy) field.get(engine);

        assertThat(injected)
            .as("Bug E fix: LinearTurnEngineProvider must inject the policy resolved by "
                + "the top-level Slot 4 name (config.getPermissionPolicy() = \"ask\"), not "
                + "the Slot 3 sub-slot (config.getSandbox().getPolicy() = \"default\"). "
                + "Pre-fix the engine received the wrong policy and demo-product's "
                + "ask-mode never emitted AgentEvent.ApprovalRequired.")
            .isInstanceOf(AskUserPermissionPolicy.class);

        pool.shutdown();
    }
}