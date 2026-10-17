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
 * Story #051 AC-051-01 / AC-051-02 / AC-051-04 — Anti-verification-loop prompt
 * clause opt-in flag tests.
 *
 * <p>The opt-in flag {@code antiVerifyLoop} on {@link AgentConfig.Instructions}
 * controls whether {@link DefaultPromptBuilder#build(TurnContext)} appends the
 * {@link DefaultPromptBuilder#ANTI_VERIFY_LOOP_CLAUSE} to the INSTRUCTIONS
 * segment of the assembled system message. Default = {@code false} → 100%
 * back-compat with Story #002 baseline; opt-in via yml
 * {@code agent.instructions.anti-verify-loop: true}.
 *
 * <p>These are pure L1 unit tests — they do not exercise the yml binding path
 * (covered by AC-051-03 in a separate L2 IT
 * {@code DefaultPromptBuilderAntiVerifyLoopYamlIT}).
 */
class DefaultPromptBuilderAntiVerifyLoopTest {

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

    // ── AC-051-01: default = false → 100% back-compat ────────────────

    @Test
    @DisplayName("defaultFlag_instructSegmentUnchanged (AC-051-01)")
    void defaultFlag_instructSegmentUnchanged() {
        // antiVerifyLoop=false (the default per Story #002 baseline)
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, null, "none", Collections.<String, String>emptyMap(), false);
        AgentConfig config = buildConfig(ins);
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = b.build(ctx);

        String systemText = ((Message.System) prompt.getMessages().get(0)).getContent();

        // 100% back-compat: no anti-verify-loop clause markers in the system text.
        assertThat(systemText)
            .as("antiVerifyLoop=false (default) → no anti-verify-loop clause marker")
            .doesNotContain(CLAUSE_MARKER)
            .doesNotContain("Anti-Verification-Loop")
            .doesNotContain("反验证循环");
        // ROLE segment is still present (unchanged from baseline).
        assertThat(systemText).contains("你是 lingShu-agent");
    }

    // ── AC-051-02: enabled = true → clause appended to INSTRUCTIONS ──

    @Test
    @DisplayName("enabledFlag_appendsClauseToInstructSegment (AC-051-02)")
    void enabledFlag_appendsClauseToInstructSegment() {
        // antiVerifyLoop=true + custom inline body
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, "Custom instruction body.", "none",
            Collections.<String, String>emptyMap(), true);
        AgentConfig config = buildConfig(ins);
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = b.build(ctx);

        String systemText = ((Message.System) prompt.getMessages().get(0)).getContent();

        // Clause is appended to the [INSTRUCTIONS] segment.
        assertThat(systemText)
            .as("antiVerifyLoop=true → clause appended to INSTRUCTIONS segment")
            .contains(CLAUSE_MARKER)
            .contains("不要对同一目标资源反复触发验证性的只读调用")
            // Custom inline body still present (stack, not exclusive).
            .contains("Custom instruction body.");

        // Position: clause appears AFTER the custom inline body and BEFORE
        // the USER MESSAGE boundary — verify by checking the messages array
        // (system text precedes the user Message.User message in the list).
        assertThat(prompt.getMessages().get(0)).isInstanceOf(Message.System.class);
        Message last = prompt.getMessages().get(prompt.getMessages().size() - 1);
        assertThat(last).isInstanceOf(Message.User.class);
        assertThat(((Message.User) last).getContent()).isEqualTo("hi");
    }

    // ── Defensive (AC-051-04: on/off/default/边界): stacks with inline ─

    @Test
    @DisplayName("enabledFlag_stacksWithCustomInlineInstructions (defensive)")
    void enabledFlag_stacksWithCustomInlineInstructions() {
        // antiVerifyLoop=true + inline "禁止重复写文件" custom rule
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, "禁止重复写文件。", "none",
            Collections.<String, String>emptyMap(), true);
        AgentConfig config = buildConfig(ins);
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String systemText = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        // Both layers stack — Story #051 §WHAT comment:
        // "enable is opt-in, 业务方 inline INSTRUCTIONS 与 anti-verify-loop 条款叠加而非互斥"
        assertThat(systemText)
            .contains("禁止重复写文件。")
            .contains(CLAUSE_MARKER);

        // Verify ordering: custom inline body precedes the appended clause.
        int idxCustom = systemText.indexOf("禁止重复写文件。");
        int idxClause = systemText.indexOf(CLAUSE_MARKER);
        assertThat(idxCustom)
            .as("custom INSTRUCTIONS body must precede appended anti-verify-loop clause")
            .isLessThan(idxClause);
    }

    // ── Boundary (AC-051-04 边界): enabled + empty INSTRUCTIONS body ──

    @Test
    @DisplayName("enabledFlag_appendsEvenWhenInstructBodyEmpty (boundary)")
    void enabledFlag_appendsEvenWhenInstructBodyEmpty() {
        // antiVerifyLoop=true + INSTRUCTIONS body empty (no inline, no file)
        // sys has only [ROLE] segment → sys.length() > 0 guard fires,
        // clause still lands as content of the [INSTRUCTIONS] slot
        // (never bleeds into other segments).
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, null, "none", Collections.<String, String>emptyMap(), true);
        AgentConfig config = buildConfig(ins);
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String systemText = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        // ROLE segment is present (Identity.defaults().getName() = "lingShu-agent"),
        // so sys.length() > 0 is true → clause is appended.
        assertThat(systemText)
            .contains("你是 lingShu-agent")     // ROLE segment still present
            .contains(CLAUSE_MARKER);            // clause appended via length() > 0 guard
    }
}