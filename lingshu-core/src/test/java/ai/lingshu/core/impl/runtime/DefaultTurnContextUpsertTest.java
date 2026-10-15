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

import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #048 — L1 tests for {@link DefaultTurnContext#appendToolResult(ToolResult)}
 * upsert-by-toolUseId semantics (AC-048-01 / AC-048-02 / AC-048-03).
 *
 * <p>Why this test matters: Story #030 introduced {@code Decision.AskUser} which makes
 * {@code LinearTurnEngine.dispatchWithPolicy} block on a {@code CompletableFuture<Decision>}
 * gated by {@code ApprovalRegistry}. If the frontend never renders Allow/Deny buttons,
 * the gate never resolves. Story #048 fixes the resulting hang by committing a sentinel
 * {@code tool_result} (content {@code "[PENDING_APPROVAL] Awaiting user decision ..."})
 * <em>before</em> the gate, so history always carries a {@code tool_result} block
 * paired to the originating {@code tool_use}. The gate, when it eventually resolves
 * (Allow / Deny / timeout), commits a <em>real</em> {@code ToolResult} with the same
 * {@code toolUseId}. Without dedup, history would grow duplicate {@code tool_result}
 * blocks for the same {@code tool_use}, and the next turn's Anthropic request would
 * 400 with {@code "messages.X: tool_use ids were found without tool_result blocks"}.
 *
 * <p>Fix: {@link DefaultTurnContext#appendToolResult(ToolResult)} scans the history
 * tail from the end back to the most recent {@link Message.Assistant}, and if it
 * finds a {@link Message.ToolResult} carrying the same {@code toolUseId}, replaces it
 * in place; otherwise appends a new one. The scan is bounded by the most recent
 * Assistant so older turns carrying the same {@code toolUseId} (uniqueness not
 * guaranteed across turns) are left untouched.
 *
 * <p>This class locks the three contract edges end-to-end:
 * <ul>
 *   <li>AC-048-01 — same toolUseId within current turn ⇒ replace in place</li>
 *   <li>AC-048-02 — different toolUseId ⇒ plain append at end</li>
 *   <li>AC-048-03 — same toolUseId but only past the most recent Assistant ⇒
 *       the older duplicate is left untouched (scope-by-most-recent-Assistant bound)</li>
 * </ul>
 */
@DisplayName("Story #048 — DefaultTurnContext.appendToolResult upsert-by-toolUseId")
class DefaultTurnContextUpsertTest {

    private static AgentConfig cfg() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1, 5, 0, 0, 0,
            10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(), "default",
            16,      // 🆕 Story #044 — maxConcurrentTurns
            32);     // 🆕 Story #044 — maxConcurrentQueueDepth
    }

    /**
     * AC-048-01 — {@code upsert_sameToolUseId_replacesExisting}.
     *
     * <p>Setup: history = {@code [Assistant("A"), TR(X,"a"), User("B")]} (3 messages,
     * where {@code "B"} is a User follow-up after the first turn's tool result).
     * We commit a new {@code ToolResult} with {@code toolUseId=X content="b"}
     * — semantically this is the "real" Allow/Deny outcome replacing the sentinel.
     *
     * <p>Expected: history size stays 3; the {@code Message.ToolResult} at index 1 is
     * REPLACED in place (now {@code content="b"}); the trailing User message is
     * undisturbed.
     */
    @Test
    @DisplayName("AC-048-01: upsert_sameToolUseId_replacesExisting")
    void upsert_sameToolUseId_replacesExisting() {
        DefaultSession session = new DefaultSession();
        // Seed: assistant turn → tool result X (sentinel) → user follow-up
        session.append(new Message.Assistant(
            "A", Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero()));
        session.append(new Message.ToolResult("X", "a", false));
        session.append(new Message.User("B"));

        DefaultTurnContext ctx = new DefaultTurnContext(
            session, cfg(), new CapturingSubscriber(), "user input");

        // Commit a "real" Allow result with the same toolUseId — must replace in place
        ToolResult replacement = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId("X")
            .isError(false)
            .content("b")
            .build();
        ctx.appendToolResult(replacement);

        List<Message> history = session.history();
        assertThat(history).hasSize(3);
        // Index 0: Assistant unchanged
        assertThat(history.get(0)).isInstanceOf(Message.Assistant.class);
        assertThat(((Message.Assistant) history.get(0)).getText()).isEqualTo("A");
        // Index 1: ToolResult replaced in place — content now "b"
        assertThat(history.get(1)).isInstanceOf(Message.ToolResult.class);
        Message.ToolResult replaced = (Message.ToolResult) history.get(1);
        assertThat(replaced.getToolUseId()).isEqualTo("X");
        assertThat(replaced.getContent()).isEqualTo("b");
        assertThat(replaced.isError()).isFalse();
        // Index 2: User follow-up untouched
        assertThat(history.get(2)).isInstanceOf(Message.User.class);
        assertThat(((Message.User) history.get(2)).getContent()).isEqualTo("B");
    }

    /**
     * AC-048-02 — {@code upsert_differentToolUseId_appendsNormally}.
     *
     * <p>Setup: history = {@code [Assistant("A"), TR(X,"a")]} (2 messages).
     * We commit a new {@code ToolResult} with {@code toolUseId=Y} — a different
     * tool's result (no match for the existing one).
     *
     * <p>Expected: plain append at end of history (no replace). Size becomes 3.
     */
    @Test
    @DisplayName("AC-048-02: upsert_differentToolUseId_appendsNormally")
    void upsert_differentToolUseId_appendsNormally() {
        DefaultSession session = new DefaultSession();
        session.append(new Message.Assistant(
            "A", Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero()));
        session.append(new Message.ToolResult("X", "a", false));

        DefaultTurnContext ctx = new DefaultTurnContext(
            session, cfg(), new CapturingSubscriber(), "user input");

        ToolResult newToolResult = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId("Y")
            .isError(false)
            .content("b")
            .build();
        ctx.appendToolResult(newToolResult);

        List<Message> history = session.history();
        assertThat(history).hasSize(3);
        // Index 0: Assistant unchanged
        assertThat(history.get(0)).isInstanceOf(Message.Assistant.class);
        // Index 1: original ToolResult X unchanged
        assertThat(history.get(1)).isInstanceOf(Message.ToolResult.class);
        Message.ToolResult original = (Message.ToolResult) history.get(1);
        assertThat(original.getToolUseId()).isEqualTo("X");
        assertThat(original.getContent()).isEqualTo("a");
        // Index 2: NEW ToolResult Y appended at end (not a replace)
        assertThat(history.get(2)).isInstanceOf(Message.ToolResult.class);
        Message.ToolResult appended = (Message.ToolResult) history.get(2);
        assertThat(appended.getToolUseId()).isEqualTo("Y");
        assertThat(appended.getContent()).isEqualTo("b");
    }

    /**
     * AC-048-03 — {@code upsert_scopedToMostRecentAssistantMessage}.
     *
     * <p>Setup: history = {@code [TR(X,"old"), Assistant("A"), TR(X,"first")]} —
     * note the {@code TR(X,"old")} predates the most recent Assistant. Uniqueness
     * of {@code toolUseId} is NOT guaranteed across turns (Anthropic's protocol
     * only requires matching {@code tool_result} to the {@code tool_use} it pairs
     * with, both within the same request). We commit a new {@code ToolResult}
     * with {@code toolUseId=X content="second"}.
     *
     * <p>Expected: the upsert scan is BOUNDED by the most recent
     * {@link Message.Assistant} (index 1). So only {@code TR(X,"first")} at
     * index 2 is a candidate; {@code TR(X,"old")} at index 0 lies OUTSIDE the
     * scan window and is left untouched (preserves cross-turn isolation).
     * Final history = {@code [TR(X,"old"), Assistant("A"), TR(X,"second")]}.
     */
    @Test
    @DisplayName("AC-048-03: upsert_scopedToMostRecentAssistantMessage")
    void upsert_scopedToMostRecentAssistantMessage() {
        DefaultSession session = new DefaultSession();
        // Seed: prior turn TR(X,"old") (outside current-turn scan bound)
        session.append(new Message.ToolResult("X", "old", false));
        // Most recent Assistant — defines the scan window's start
        session.append(new Message.Assistant(
            "A", Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero()));
        // Current turn's first ToolResult for X — the sentinel candidate
        session.append(new Message.ToolResult("X", "first", false));

        DefaultTurnContext ctx = new DefaultTurnContext(
            session, cfg(), new CapturingSubscriber(), "user input");

        // Commit a "real" result with the same toolUseId — must replace the
        // current-turn TR(X,"first") but NOT the prior-turn TR(X,"old").
        ToolResult currentTurnReplacement = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId("X")
            .isError(false)
            .content("second")
            .build();
        ctx.appendToolResult(currentTurnReplacement);

        List<Message> history = session.history();
        assertThat(history).hasSize(3);
        // Index 0: prior-turn TR(X,"old") UNCHANGED — outside the scan window
        assertThat(history.get(0)).isInstanceOf(Message.ToolResult.class);
        Message.ToolResult prior = (Message.ToolResult) history.get(0);
        assertThat(prior.getToolUseId()).isEqualTo("X");
        assertThat(prior.getContent()).isEqualTo("old");
        // Index 1: Assistant unchanged
        assertThat(history.get(1)).isInstanceOf(Message.Assistant.class);
        // Index 2: current-turn TR(X,"first") REPLACED IN PLACE — content now "second"
        assertThat(history.get(2)).isInstanceOf(Message.ToolResult.class);
        Message.ToolResult current = (Message.ToolResult) history.get(2);
        assertThat(current.getToolUseId()).isEqualTo("X");
        assertThat(current.getContent()).isEqualTo("second");
    }
}