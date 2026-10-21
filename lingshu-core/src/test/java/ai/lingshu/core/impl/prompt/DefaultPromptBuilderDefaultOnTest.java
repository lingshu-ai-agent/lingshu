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

import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.MemorySource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #053 — Default-on tests for the anti-verification-loop prompt clause.
 *
 * <p>In Story #051 the {@link AgentConfig.Instructions#isAntiVerifyLoop()} flag
 * defaulted to {@code false} (opt-in). In Story #053 the
 * {@link AgentConfig.Instructions#empty()} factory is flipped to
 * {@code true} (default-on), with opt-out via yml
 * {@code agent.instructions.anti-verify-loop: false}.
 *
 * <p>These are pure L1 unit tests — the yml binding path is covered separately
 * by {@code DefaultPromptBuilderYamlOptOutIT} (AC-053-03).
 */
class DefaultPromptBuilderDefaultOnTest {

    /** Unique substring of {@link DefaultPromptBuilder#ANTI_VERIFY_LOOP_CLAUSE}. */
    private static final String CLAUSE_MARKER = "## 反验证循环守则(Anti-Verification-Loop)";

    private TurnContext ctx;
    private Session session;

    @BeforeEach
    void setUp() {
        ctx = mock(TurnContext.class);
        session = mock(Session.class);
        when(ctx.session()).thenReturn(session);
        when(session.history()).thenReturn(Collections.<Message>emptyList());
    }

    private AgentConfig buildConfig(AgentConfig.Instructions ins) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "claude-3-5-sonnet-latest", 8192, 1.0),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), 0),
            "default",
            new AgentConfig.Sandbox("default", "chroot",
                Paths.get(System.getProperty("user.dir")),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            "default",
            "default",
            null, null, null,
            8, 60, 300, 0, 60, 50,
            AgentConfig.Identity.defaults(), ins, AgentConfig.Memory.defaults(),
            "default",                 // a2aTransport
            null,                      // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(), "default",
            16,                        // 🆕 Story #044 — maxConcurrentTurns
            32);                       // 🆕 Story #044 — maxConcurrentQueueDepth
    }

    // ── AC-053-01: Instructions.empty() defaults antiVerifyLoop=true ──

    @Test
    @DisplayName("AC-053-01: empty_returnsAntiVerifyLoopTrue")
    void empty_returnsAntiVerifyLoopTrue() {
        // 🆕 v1.5.64 Story #053 — default-on flip.
        // (Story #051 had default false; flipped per 2026-10-18 user feedback
        //  "system prompt 不够啊" — opt-in 默认 false = LLM 默认反复 verify 同文件)
        AgentConfig.Instructions ins = AgentConfig.Instructions.empty();
        assertThat(ins.isAntiVerifyLoop()).isTrue();
    }

    // ── AC-053-02: Instructions.empty() → DefaultPromptBuilder appends clause ──

    @Test
    @DisplayName("AC-053-02: emptyBuild_appendsClauseByDefault")
    void emptyBuild_appendsClauseByDefault() {
        // Pass Instructions.empty() directly through builder (no override)
        AgentConfig config = buildConfig(AgentConfig.Instructions.empty());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = b.build(ctx);

        // First message is the System segment carrying the anti-verify-loop clause.
        assertThat(prompt.getMessages().get(0)).isInstanceOf(Message.System.class);
        String systemText = ((Message.System) prompt.getMessages().get(0)).getContent();

        // AC-053-02: clause is appended to [INSTRUCTIONS] by default.
        assertThat(systemText)
            .as("antiVerifyLoop=true by default (Story #053) → clause appended to [INSTRUCTIONS]")
            .contains(CLAUSE_MARKER)
            .contains("不要对同一目标资源反复触发验证性的只读调用");

        // Position: clause lands after [ROLE] segment, before USER MESSAGE boundary.
        int idxRole = systemText.indexOf("你是 lingShu-agent");
        int idxClause = systemText.indexOf(CLAUSE_MARKER);
        assertThat(idxRole).as("ROLE segment precedes clause").isLessThan(idxClause);
        // USER MESSAGE is the last message in the messages list (separate Message.User).
        Message last = prompt.getMessages().get(prompt.getMessages().size() - 1);
        assertThat(last).isInstanceOf(Message.User.class);
        assertThat(((Message.User) last).getContent()).isEqualTo("hi");
    }

    // ── Reverse AC: explicit false → clause skipped (opt-out path works) ──

    @Test
    @DisplayName("reverseAC: explicitFalseBuild_skipsClause")
    void explicitFalseBuild_skipsClause() {
        // Explicit antiVerifyLoop=false — opt-out escape hatch per Story #053.
        // Mirrors Story #051 default behavior; the flip only changes the empty() factory.
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, null, "none", Collections.<String, String>emptyMap(), false);
        AgentConfig config = buildConfig(ins);
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String systemText = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        // Opt-out path: no clause marker, no Anti-Verification-Loop substrings.
        assertThat(systemText)
            .as("antiVerifyLoop=false explicit opt-out → no clause")
            .doesNotContain(CLAUSE_MARKER)
            .doesNotContain("Anti-Verification-Loop")
            .doesNotContain("反验证循环");
        // ROLE segment is still present (no other behavior changes).
        assertThat(systemText).contains("你是 lingShu-agent");
    }

    // ── Regression AC: explicit true → clause appended (Story #051 baseline) ──

    @Test
    @DisplayName("regressionAC: explicitTrueBuild_appendsClause")
    void explicitTrueBuild_appendsClause() {
        // Explicit antiVerifyLoop=true — same behavior as empty() factory default.
        // Locks that the Story #051 opt-in path still works (defensive: someone
        // reading code after Story #053 might assume opt-in is gone).
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, "Custom instruction body.", "none",
            Collections.<String, String>emptyMap(), true);
        AgentConfig config = buildConfig(ins);
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String systemText = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(systemText)
            .as("explicit antiVerifyLoop=true → clause appended")
            .contains(CLAUSE_MARKER)
            .contains("Custom instruction body.");
        // Order: custom body precedes appended clause.
        int idxCustom = systemText.indexOf("Custom instruction body.");
        int idxClause = systemText.indexOf(CLAUSE_MARKER);
        assertThat(idxCustom).isLessThan(idxClause);
    }

    // ── Boundary AC: default-on with ROLE segment present ─────────────

    @Test
    @DisplayName("boundaryAC: emptyBuild_appendsClauseEvenWhenInstructBodyEmpty")
    void emptyBuild_appendsClauseEvenWhenInstructBodyEmpty() {
        // 🆕 Story #053 — default-on + INSTRUCTIONS body empty + ROLE segment present.
        // The guard `if (ins.isAntiVerifyLoop() && sys.length() > 0)` (DefaultPromptBuilder:177)
        // fires because ROLE segment (from Identity.defaults()) populates sys with "你是 lingShu-agent".
        AgentConfig config = buildConfig(AgentConfig.Instructions.empty());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String systemText = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        // ROLE segment present (Identity.defaults().getName() = "lingShu-agent").
        assertThat(systemText).contains("你是 lingShu-agent");
        // Clause still appended by length() > 0 guard.
        assertThat(systemText).contains(CLAUSE_MARKER);
        // No second user-message in the system text — it's in a separate Message.User message.
        // Just check the system text ends with the clause body (not just the title marker
        // — the title alone won't show trailing-newline bleed into PROJECT MEMORY segment).
        assertThat(systemText.trim()).endsWith("相同只读工具。");
    }
}