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
package ai.lingshu.core.impl.prompt;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #053 — L2 slice test for {@link AgentFactory#loadYamlAndValidate}
 * parsing the {@code agent.instructions.anti-verify-loop} sub-key introduced
 * by Story #051 (opt-in flag) and flipped to default-on in Story #053.
 *
 * <p>Mirrors the L2 pattern from
 * {@code AgentFactoryYamlPermissionPolicyIT} (Story #029) — minimal yml
 * payload, 8-stub {@link AgentFactory}, exercise the yml → {@link AgentConfig}
 * binding path then build a {@link Prompt} via {@link DefaultPromptBuilder}
 * and assert the anti-verify-loop clause presence / absence against the
 * {@code system} segment.
 *
 * <p>Locks the AC-053-03 contract: yml
 * {@code agent.instructions.anti-verify-loop: false} truly binds to opt-out
 * (no clause appended to INSTRUCTIONS segment), and yml omitting the key
 * keeps the Story #053 default-on behavior (clause appended).
 */
class DefaultPromptBuilderYamlOptOutIT {

    /** Unique substring of {@link DefaultPromptBuilder#ANTI_VERIFY_LOOP_CLAUSE}. */
    private static final String CLAUSE_MARKER = "## 反验证循环守则(Anti-Verification-Loop)";

    private static Path writeTemp(String content) throws IOException {
        Path tmp = Files.createTempFile("agent-yml-anti-verify-loop-", ".yml");
        Files.write(tmp, content.getBytes("UTF-8"));
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** Minimal AgentFactory with 8 stub routers; loadYamlAndValidate only uses toAgentConfig + validate. */
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
        return new AgentFactory(
            new Routers.LlmProviderRouter(Collections.singletonList(llm)),
            new Routers.ToolExecutorRouter(Collections.singletonList(tool)),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policy)),
            new Routers.PromptBuilderRouter(Collections.singletonList(prompt)),
            new Routers.FlowEngineRouter(Collections.singletonList(flow)),
            new Routers.MemorySourceRouter(Collections.singletonList(memory)),
            new Routers.RuntimeSandboxRouter(Collections.singletonList(sandbox)),
            new Routers.SessionStoreRouter(Collections.singletonList(sessionStore)));
    }

    /** Mock {@link TurnContext} that returns the given {@link AgentConfig} + empty history + "hi" input. */
    private static TurnContext mockCtx(AgentConfig cfg) {
        Session session = mock(Session.class);
        when(session.history()).thenReturn(Collections.<Message>emptyList());
        TurnContext ctx = mock(TurnContext.class);
        when(ctx.config()).thenReturn(cfg);
        when(ctx.session()).thenReturn(session);
        when(ctx.userInput()).thenReturn("hi");
        return ctx;
    }

    // ── AC-053-03: yml opt-out → Prompt system text 不含 clause ─────────────

    @Test
    @DisplayName("AC-053-03: yamlFalseBinding_skipsClause")
    void yamlFalseBinding_skipsClause() throws IOException {
        // AC-053-03 forward: yml anti-verify-loop: false → Instructions.isAntiVerifyLoop() == false
        // → DefaultPromptBuilder guard if (ins.isAntiVerifyLoop() && ...) 短路 → no clause.
        Path yml = writeTemp(
            "agent:\n" +
            "  instructions:\n" +
            "    anti-verify-loop: false\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);

        // Bound state — opt-out.
        assertThat(cfg.getInstructions().isAntiVerifyLoop())
            .as("yml anti-verify-loop: false → Instructions.isAntiVerifyLoop() == false")
            .isFalse();

        // Build Prompt and assert NO clause in system segment.
        DefaultPromptBuilder builder = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = builder.build(mockCtx(cfg));
        assertThat(prompt.getMessages().get(0)).isInstanceOf(Message.System.class);
        String systemText = ((Message.System) prompt.getMessages().get(0)).getContent();

        assertThat(systemText)
            .as("antiVerifyLoop=false via yml → no anti-verify-loop clause in [INSTRUCTIONS]")
            .doesNotContain(CLAUSE_MARKER)
            .doesNotContain("反验证循环")
            .doesNotContain("Anti-Verification-Loop");

        // ROLE segment still present (no other behavior change).
        assertThat(systemText).contains("你是 lingShu-agent");
    }

    // ── AC-053-03 reverse: yml 缺 key → 默认 true → clause 应 append ──────

    @Test
    @DisplayName("AC-053-03 reverse: yamlDefaultBinding_appendsClause")
    void yamlDefaultBinding_appendsClause() throws IOException {
        // AC-053-03 reverse: yml omits anti-verify-loop key → toAgentConfig falls back to true
        // (Story #053 default-on flip) → clause appended to [INSTRUCTIONS] segment.
        Path yml = writeTemp(
            "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);

        // Bound state — default-on (key omitted).
        assertThat(cfg.getInstructions().isAntiVerifyLoop())
            .as("yml 缺 anti-verify-loop key → Instructions.isAntiVerifyLoop() == true (Story #053 default-on)")
            .isTrue();

        // Build Prompt and assert clause present in system segment.
        DefaultPromptBuilder builder = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = builder.build(mockCtx(cfg));
        assertThat(prompt.getMessages().get(0)).isInstanceOf(Message.System.class);
        String systemText = ((Message.System) prompt.getMessages().get(0)).getContent();

        assertThat(systemText)
            .as("antiVerifyLoop=true by default (Story #053 default-on) → clause appended to [INSTRUCTIONS]")
            .contains(CLAUSE_MARKER)
            .contains("不要对同一目标资源反复触发验证性的只读调用");

        // Order check: ROLE segment precedes clause.
        int idxRole = systemText.indexOf("你是 lingShu-agent");
        int idxClause = systemText.indexOf(CLAUSE_MARKER);
        assertThat(idxRole).as("ROLE segment precedes clause").isLessThan(idxClause);
    }
}