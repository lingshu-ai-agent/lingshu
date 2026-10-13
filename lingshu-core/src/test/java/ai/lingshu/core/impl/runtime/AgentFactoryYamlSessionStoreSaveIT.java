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
 * Story #046 — L2 slice test for {@link AgentFactory#loadYamlAndValidate}
 * parsing the {@code agent.session-store} top-level key (dsh §5.3.1.0 Slot 5
 * SessionStore name resolution).
 *
 * <p>Mirrors {@code AgentFactoryYamlCompactorIT} Story #045 precedent:
 * locks the YAML → {@link AgentConfig} contract so demo-product and demo-empty's
 * {@code application.yml} continue to bind the {@code agent.session-store} key
 * correctly to {@link AgentConfig#getSessionStore()} — the string that
 * {@link ai.lingshu.core.impl.flow.LinearTurnEngineProvider} passes to
 * {@link ai.lingshu.core.impl.router.Routers.SessionStoreRouter#resolve(String, AgentConfig)}
 * in {@code create()}.
 *
 * <p>This test isolates the YAML parse layer; end-to-end disk-write
 * verification (full Agent → Engine → trySave → FileSessionStore.save →
 * Files.list(dir).count()==1) lives in {@code FileSessionStoreTest} and
 * {@code AgentFactoryYamlSessionStoreLoadIT}.
 */
class AgentFactoryYamlSessionStoreSaveIT {

    private static Path writeTemp(String content) throws IOException {
        Path tmp = Files.createTempFile("agent-yml-sessionstore-", ".yml");
        Files.write(tmp, content.getBytes("UTF-8"));
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** Minimal AgentFactory with 9 stub routers (incl. Slot 5 SessionStore). */
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
        // Slot 6 Compactor stub — 9-Router @Autowired ctor resolves only when present.
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
     * AC-046-1 — Empty yml must fall back to the default {@code "memory"} session
     * store name so demo-empty's application.yml (which omits the key) gets the
     * safe in-memory default that Story #014 wired up.
     */
    @Test
    @DisplayName("AC-046-1: yamlDefault_sessionStoreMemory")
    void yamlDefault_sessionStoreMemory() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getSessionStore()).isEqualTo("memory");
    }

    /**
     * AC-046-2 — Custom {@code agent.session-store: file} must bind verbatim to
     * {@link AgentConfig#getSessionStore()} so that
     * {@link ai.lingshu.core.impl.flow.LinearTurnEngineProvider#create(AgentConfig)}
     * can pass that string into {@code SessionStoreRouter.resolve(name, cfg)}.
     * This is the precondition for the end-to-end FileSessionStore disk-write
     * path tested in {@code FileSessionStoreTest} + demo-product manual e2e.
     */
    @Test
    @DisplayName("AC-046-2: yamlCustomSessionStoreFile_bindsTopLevel")
    void yamlCustomSessionStoreFile_bindsTopLevel() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  session-store: file\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getSessionStore()).isEqualTo("file");
    }

    /**
     * AC-046-3 — Verifies the {@code session-store} key parses regardless of
     * placement relative to other top-level keys (placement tolerance — the
     * agent yml flat-key parser must not require keys in any specific order).
     * This locks the contract that demo-product's application.yml (which has
     * ~50 top-level keys interleaved) continues to bind correctly even when
     * Story #014 / #046 add or reorder other keys.
     */
    @Test
    @DisplayName("AC-046-3: yamlSessionStoreFile_parsesRegardlessOfSiblingOrder")
    void yamlSessionStoreFile_parsesRegardlessOfSiblingOrder() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n" +
            "  session-store: file\n" +
            "  compactor: truncating\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        // All three keys must round-trip the parse layer independently.
        assertThat(cfg.getSessionStore()).isEqualTo("file");
        assertThat(cfg.getCompactor()).isEqualTo("truncating");
    }
}