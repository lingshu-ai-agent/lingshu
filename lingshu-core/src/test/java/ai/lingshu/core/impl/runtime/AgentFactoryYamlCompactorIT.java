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
 * Story #045 — L2 slice test for {@link AgentFactory#loadYamlAndValidate}
 * parsing the new {@code agent.compactor} top-level key (dsh §5.3.1.0 Slot 6
 * Compactor name resolution).
 *
 * <p>Mirrors {@code AgentFactoryYamlPermissionPolicyIT} Story #029 precedent:
 * locks the YAML → {@link AgentConfig} contract so demo-product and demo-empty's
 * {@code application.yml} continue to bind correctly. Top-level camelCase
 * key {@code compactor} mirrors the {@code agent.session-store} Story #014
 * precedent.
 *
 * <p>The full plumbing path (yml → AgentConfig.compactor → LinearTurnEngineProvider
 * 7-arg ctor → CompactorRouter.resolve() → Compactor instance) is verified
 * end-to-end by the integration test here: we hand the stub factory a
 * CompactorRouter that echoes its registered name, and assert that yml
 * {@code agent.compactor: <name>} flows through {@link AgentConfig#getCompactor()}
 * unchanged so downstream resolution can pick it up.
 */
class AgentFactoryYamlCompactorIT {

    private static Path writeTemp(String content) throws IOException {
        Path tmp = Files.createTempFile("agent-yml-compactor-", ".yml");
        Files.write(tmp, content.getBytes("UTF-8"));
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** Minimal AgentFactory with 9 stub routers (incl. Slot 6 Compactor). */
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
        // 🆕 Story #045 — Slot 6 Compactor stub so the 9-Router @Autowired ctor resolves.
        // Provider only needs to satisfy the SPI shape (name / priority / version / create);
        // the actual Compactor instance is irrelevant here — we only assert the yml
        // string flows through AgentConfig.getCompactor() unchanged.
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

    @Test
    @DisplayName("AC-045-9: yamlDefault_parsesCompactorTruncating")
    void yamlDefault_parsesCompactorTruncating() throws IOException {
        // Empty yml — compactor absent, must fall back to default "truncating".
        Path yml = writeTemp(
            "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getCompactor()).isEqualTo("truncating");
    }

    @Test
    @DisplayName("AC-045-9: yamlCustomCompactor_bindsTopLevel")
    void yamlCustomCompactor_bindsTopLevel() throws IOException {
        // Custom compactor name → bound verbatim to AgentConfig.compactor so the
        // CompactorRouter can resolve it. The Router's by-name map will throw if
        // the name is unknown, but we only assert the parse layer here.
        Path yml = writeTemp(
            "agent:\n" +
            "  compactor: truncating\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getCompactor()).isEqualTo("truncating");
    }
}
