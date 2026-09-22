package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.ModelHints;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #018 — L1 unit tests for {@link TruncatingCompactor}.
 *
 * <p>Covers the {@link TruncatingCompactor#shouldCompact(Prompt)} predicate,
 * the two-step {@link TruncatingCompactor#compact} reduction (ToolResult truncation
 * then sliding window), and idempotency / null safety.
 */
class TruncatingCompactorTest {

    // ── shouldCompact ──────────────────────────────────────────────────

    @Test
    @DisplayName("AC-018-1: shouldCompact_belowThreshold_returnsFalse")
    void shouldCompact_belowThreshold_returnsFalse() {
        // maxPromptTokens=100 ⇒ threshold is > 100; chars=200 ⇒ est=50 ⇒ 50 > 100 = false
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100, 10_000, 20));
        Prompt p = promptOf(200); // 200 chars → 50 tokens

        assertThat(c.shouldCompact(p)).isFalse();
    }

    @Test
    @DisplayName("AC-018-2: shouldCompact_aboveThreshold_returnsTrue")
    void shouldCompact_aboveThreshold_returnsTrue() {
        // maxPromptTokens=100; chars=800 → est=200 > 100 = true
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100, 10_000, 20));
        Prompt p = promptOf(800);

        assertThat(c.shouldCompact(p)).isTrue();
    }

    @Test
    @DisplayName("EC-018-3: shouldCompact_exactThreshold_returnsFalse")
    void shouldCompact_exactThreshold_returnsFalse() {
        // maxPromptTokens=100; chars=400 → est=100, strict > → false
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100, 10_000, 20));
        Prompt p = promptOf(400);

        assertThat(c.shouldCompact(p)).isFalse();
    }

    @Test
    @DisplayName("EC-018-4: shouldCompact_nullPrompt_returnsFalse")
    void shouldCompact_nullPrompt_returnsFalse() {
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100, 10_000, 20));
        assertThat(c.shouldCompact(null)).isFalse();
    }

    // ── compact: ToolResult truncation (Step 1) ───────────────────────

    @Test
    @DisplayName("AC-018-3: compact_truncatesOverlongToolResult")
    void compact_truncatesOverlongToolResult() {
        // maxToolResultBytes=50 — content of 500 chars will be truncated
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100_000, 50, 20));
        DefaultSession session = new DefaultSession("s1");
        String huge = repeat("x", 500);
        session.append(new Message.ToolResult("tu-1", huge, false));

        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));

        List<Message> history = session.history();
        assertThat(history).hasSize(1);
        Message.ToolResult tr = (Message.ToolResult) history.get(0);
        assertThat(tr.getContent().length()).isLessThan(huge.length());
        assertThat(tr.getContent()).contains("truncated");
        assertThat(tr.getToolUseId()).isEqualTo("tu-1");
        assertThat(tr.isError()).isFalse();
    }

    @Test
    @DisplayName("EC-018-1: compact_smallToolResult_leftIntact")
    void compact_smallToolResult_leftIntact() {
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100_000, 10_000, 20));
        DefaultSession session = new DefaultSession("s1");
        String small = "tiny";
        session.append(new Message.ToolResult("tu-1", small, false));

        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));

        Message.ToolResult tr = (Message.ToolResult) session.history().get(0);
        assertThat(tr.getContent()).isEqualTo(small);
    }

    // ── compact: sliding window (Step 2) ───────────────────────────────

    @Test
    @DisplayName("AC-018-4: compact_slidingWindow_dropsOldestAssistantTurns")
    void compact_slidingWindow_dropsOldestAssistantTurns() {
        // keepRecentTurns=3 — with 5 assistant turns present, oldest 2 must drop
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(10, 100_000, 3));
        DefaultSession session = new DefaultSession("s1");
        // Build: system + 5×(user + assistant)
        session.append(new Message.System("ROLE", "role"));
        for (int i = 0; i < 5; i++) {
            session.append(new Message.User("u" + i));
            session.append(new Message.Assistant(
                "a" + i,
                Collections.<ToolCall>emptyList(),
                StopReason.END_TURN,
                Usage.zero()));
        }
        assertThat(session.history()).hasSize(1 + 5 * 2);

        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));

        long assistants = session.history().stream()
            .filter(m -> m instanceof Message.Assistant)
            .count();
        assertThat(assistants).isEqualTo(3L);
    }

    @Test
    @DisplayName("AC-018-4b: compact_slidingWindow_keepsSystemBlockAndLastAssistant")
    void compact_slidingWindow_keepsSystemBlockAndLastAssistant() {
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(10, 100_000, 1));
        DefaultSession session = new DefaultSession("s1");
        session.append(new Message.System("ROLE", "role"));
        for (int i = 0; i < 4; i++) {
            session.append(new Message.User("u" + i));
            session.append(new Message.Assistant(
                "a" + i,
                Collections.<ToolCall>emptyList(),
                StopReason.END_TURN,
                Usage.zero()));
        }

        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));

        // System preserved at head; only the most recent 1 assistant turn survives.
        List<Message> hist = session.history();
        assertThat(hist.get(0)).isInstanceOf(Message.System.class);
        long assistants = hist.stream().filter(m -> m instanceof Message.Assistant).count();
        assertThat(assistants).isEqualTo(1L);
    }

    // ── compact: idempotency / null safety / end-to-end ────────────────

    @Test
    @DisplayName("EC-018-2: compact_emptySession_isNoop")
    void compact_emptySession_isNoop() {
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100, 100_000, 20));
        DefaultSession session = new DefaultSession("s1");

        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));
        assertThat(session.history()).isEmpty();
    }

    @Test
    @DisplayName("EC-018-5: compact_idempotent_secondCallNoChange")
    void compact_idempotent_secondCallNoChange() {
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(10, 100_000, 1));
        DefaultSession session = new DefaultSession("s1");
        session.append(new Message.System("ROLE", "role"));
        for (int i = 0; i < 3; i++) {
            session.append(new Message.Assistant("a" + i,
                Collections.<ToolCall>emptyList(),
                StopReason.END_TURN, Usage.zero()));
        }

        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));
        int sizeAfterFirst = session.history().size();
        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));
        assertThat(session.history()).hasSize(sizeAfterFirst);
    }

    @Test
    @DisplayName("EC-018-6: compact_nullCtx_returnsSilently")
    void compact_nullCtx_returnsSilently() {
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100, 100_000, 20));
        c.compact(null); // no throw
    }

    @Test
    @DisplayName("AC-018-10: compact_endToEnd_truncateThenWindow")
    void compact_endToEnd_truncateThenWindow() {
        // Setup: 30 assistant turns each with a huge tool result that exceeds maxToolResultBytes;
        // maxPromptTokens threshold forces the sliding-window step too.
        TruncatingCompactor c = new TruncatingCompactor(new CompactorProps(100, 50, 5));
        DefaultSession session = new DefaultSession("s1");
        session.append(new Message.System("ROLE", "role"));
        for (int i = 0; i < 30; i++) {
            session.append(new Message.User("u" + i));
            session.append(new Message.Assistant("a" + i,
                Collections.<ToolCall>emptyList(),
                StopReason.END_TURN, Usage.zero()));
            session.append(new Message.ToolResult("tu-" + i, repeat("y", 200), false));
        }

        c.compact(newTurnCtx(session, AgentConfigDefaults.defaults()));

        List<Message> hist = session.history();
        // Step 1 truncated every ToolResult; Step 2 dropped oldest turns down to keepRecentTurns.
        long assistantCount = hist.stream().filter(m -> m instanceof Message.Assistant).count();
        assertThat(assistantCount).isLessThanOrEqualTo(5L);
        // Every ToolResult that survived truncation must be shorter than the original 200-char payload.
        for (Message m : hist) {
            if (m instanceof Message.ToolResult) {
                assertThat(((Message.ToolResult) m).getContent().length()).isLessThan(200);
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    /** Build a prompt whose messages contain exactly {@code totalChars} characters of text. */
    private static Prompt promptOf(int totalChars) {
        List<Message> msgs = new ArrayList<>();
        String s = repeat("a", totalChars);
        msgs.add(new Message.System(s, "role"));
        return Prompt.builder().messages(msgs).tools(Collections.<ToolSpec>emptyList()).hints(new ModelHints(null, null, null)).build();
    }

    private static DefaultTurnContext newTurnCtx(Session s, AgentConfig cfg) {
        Subscriber<AgentEvent> noop = new Subscriber<AgentEvent>() {
            @Override public void onSubscribe(org.reactivestreams.Subscription sub) { sub.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent e) { }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        };
        return new DefaultTurnContext(s, cfg, noop, "");
    }
}
