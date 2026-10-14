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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #047 — L2 slice test for {@link AgentFactory#loadYamlAndValidate}
 * parsing the yml sub-keys the ask-policy wiring depends on:
 *
 * <ul>
 *   <li>{@code agent.permission-policy: ask} — top-level Slot 4 name, mirrors
 *       the Story #029 {@code strict} precedent. This is the yml key that was
 *       silently ignored by the Bug A typo at
 *       {@code AgentFactory.java:280} (read {@code sandbox.getPolicy()}
 *       sub-slot instead of {@code permissionPolicy} top-level field).</li>
 *   <li>{@code agent.tools.ask-list: [bash_safe, write_file]} — Slot 4 ask
 *       gating sub-key introduced by Story #030. {@code
 *       DemoProductApplication.readTools()} used to hardcode an empty list
 *       (Bug B step 1), so demo-product's yml could never flow through.</li>
 * </ul>
 *
 * <p>Locks the YAML → {@link AgentConfig} parse contract so the 9-Router
 * wiring downstream in {@link AgentFactory#create(AgentConfig)} (Bug A fix at
 * line 285) actually has the right string to feed into
 * {@code PermissionPolicyRouter.resolve(...)}.
 *
 * <p>Mirrors {@code AgentFactoryYamlPermissionPolicyIT} (Story #029) and
 * {@code AgentFactoryYamlSessionStoreSaveIT} (Story #046): this test isolates
 * the YAML parse layer and never calls {@link AgentFactory#create(AgentConfig)},
 * so all 9 stub Router Providers return {@code null} from
 * {@code create(AgentConfig)}.
 *
 * <p><b>AC mapping:</b>
 * <ul>
 *   <li>AC-047-1 → {@link #yml_permissionPolicyAsk_bindsTopLevel}</li>
 *   <li>AC-047-2 → {@link #yml_toolsAskList_bindsIntoToolsConfig}</li>
 * </ul>
 */
class AgentFactoryAskPolicyYamlIT {

    private static Path writeTemp(String content) throws IOException {
        Path tmp = Files.createTempFile("agent-yml-askpolicy-", ".yml");
        Files.write(tmp, content.getBytes("UTF-8"));
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /**
     * Minimal AgentFactory wired with 9 stub Routers (all Providers return
     * {@code null} from {@code create(AgentConfig)} since this test never calls
     * {@link AgentFactory#create(AgentConfig)}, only
     * {@link AgentFactory#loadYamlAndValidate(Path)}). The 9-Router order in
     * the ctor must match the @Autowired primary ctor of AgentFactory
     * exactly (L118-137 as of Story #047).
     */
    private static AgentFactory stubFactory() {
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
        Providers.PermissionPolicyProvider policy = new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return null; }
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
        // Story #045 Slot 6 Compactor stub — 9-Router @Autowired ctor resolves only when present.
        Providers.CompactorProvider compactor = new Providers.CompactorProvider() {
            @Override public String name() { return "truncating"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public Compactor create(AgentConfig cfg) { return null; }
        };
        return new AgentFactory(
            new Routers.LlmProviderRouter(Collections.singletonList(llm)),
            new Routers.ToolExecutorRouter(Collections.singletonList(tool)),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policy)),
            new Routers.PromptBuilderRouter(Collections.singletonList(prompt)),
            new Routers.FlowEngineRouter(Collections.singletonList(flow)),
            new Routers.MemorySourceRouter(Collections.singletonList(memory)),
            new Routers.RuntimeSandboxRouter(Collections.singletonList(sandbox)),
            new Routers.SessionStoreRouter(Collections.singletonList(sessionStore)),
            new Routers.CompactorRouter(Collections.singletonList(compactor)));
    }

    /**
     * AC-047-1 — yml {@code agent.permission-policy: ask} must bind verbatim to
     * {@link AgentConfig#getPermissionPolicy()} so that the post-Bug-A
     * {@code AgentFactory.create()} line
     * {@code policyRouter.resolve(config.getPermissionPolicy(), config)} (Story
     * #047 fix at line 285) can route to {@code AskUserPermissionPolicyProvider}
     * (name="ask"). Pre-Story #047, the typo at line 280 read
     * {@code sandbox.getPolicy()} instead, which silently forced every config
     * to "default" and made the ask-list wiring unreachable.
     */
    @Test
    @DisplayName("AC-047-1: yml_permissionPolicyAsk_bindsTopLevel")
    void yml_permissionPolicyAsk_bindsTopLevel() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  permission-policy: ask\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getPermissionPolicy()).isEqualTo("ask");
    }

    /**
     * AC-047-2 — yml {@code agent.tools.ask-list: [bash_safe, write_file]}
     * must bind verbatim into
     * {@link AgentConfig.ToolsConfig#getAskList()} so that
     * {@code AskUserPermissionPolicy.check()} (Path 2) can match against it.
     * Pre-Story #047, {@code DemoProductApplication.readTools()} hardcoded an
     * empty list for the {@code askList} field (Bug B step 1), so demo-product
     * yml could never configure ask-mode even with the correct
     * {@code permission-policy: ask}.
     */
    @Test
    @DisplayName("AC-047-2: yml_toolsAskList_bindsIntoToolsConfig")
    void yml_toolsAskList_bindsIntoToolsConfig() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  permission-policy: ask\n" +
            "  tools:\n" +
            "    enabled: true\n" +
            "    ask-list:\n" +
            "      - bash_safe\n" +
            "      - write_file\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getPermissionPolicy()).isEqualTo("ask");
        assertThat(cfg.getTools().getAskList()).containsExactly("bash_safe", "write_file");
    }
}
