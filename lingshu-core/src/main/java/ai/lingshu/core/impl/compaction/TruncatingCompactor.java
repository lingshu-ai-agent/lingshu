package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Story #018 — {@code Compactor} v1 implementation (dsh §6.2 L3813-3889).
 *
 * <p>Two-step reduction applied in order:
 * <ol>
 *   <li><b>ToolResult truncation</b> — any {@link Message.ToolResult#getContent()} whose
 *       UTF-8 byte length exceeds {@code maxToolResultBytes} is replaced by
 *       {@code head + "… [truncated N bytes] …" + tail} so the model still sees a hint
 *       of what came back, but the prompt shrinks dramatically.</li>
 *   <li><b>Sliding-window drop</b> — if step 1 was not enough to bring the prompt under
 *       {@code maxPromptTokens}, drop the oldest assistant+tool_use+tool_result triples
 *       beyond the most recent {@code keepRecentTurns} assistant turns. System + User
 *       messages are preserved.</li>
 * </ol>
 *
 * <p>Idempotent: if neither step changes the history, {@code compact} returns silently.
 *
 * <p>Atomicity: when at least one step changed the history, the new list is committed
 * via {@link Session#compact(List)} which acquires the same internal lock as
 * {@link Session#history()} / {@code append(Message)} — a concurrent tool-result append
 * cannot interleave with a compactor swap.
 *
 * <p><b>Why not {@code @Component}:</b> {@code TruncatingCompactor} needs
 * {@link CompactorProps} which is derived from {@link ai.lingshu.core.runtime.AgentConfig}
 * — there is no per-process singleton {@code CompactorProps} bean. The {@link TruncatingCompactorProvider}
 * is the sole construction site (via {@code Provider.create(config)}), so this class
 * stays a plain Java class with no Spring annotation. Without this constraint,
 * any Spring context that scans {@code ai.lingshu.core.impl.compaction} (e.g.
 * {@code lingshu-examples/demo-engineer}) fails to start with
 * {@code NoSuchBeanDefinitionException: CompactorProps}.
 */
@Slf4j
public class TruncatingCompactor implements Compactor {

    /** Marker inserted between the head and tail when a ToolResult is truncated. */
    static final String TRUNCATION_MARKER = "… [truncated %d bytes] …";

    /** Bytes retained on each side of a truncated ToolResult content (head + tail). */
    static final int TRUNCATION_KEEP_BYTES = 1024;

    /** Industry-standard ~4 chars per token; conservative overestimate. */
    static final int CHARS_PER_TOKEN = 4;

    private final CompactorProps props;

    public TruncatingCompactor(CompactorProps props) {
        if (props == null) {
            throw new IllegalArgumentException("props must not be null");
        }
        this.props = props;
    }

    // ── Compactor contract ─────────────────────────────────────────────

    @Override
    public boolean shouldCompact(Prompt prompt) {
        if (prompt == null || prompt.getMessages() == null) {
            return false;
        }
        int estimatedTokens = estimateTokens(prompt.getMessages());
        return estimatedTokens > props.getMaxPromptTokens();
    }

    @Override
    public void compact(TurnContext ctx) {
        if (ctx == null || ctx.session() == null) {
            return;
        }
        List<Message> original = ctx.session().history();
        if (original == null || original.isEmpty()) {
            return;
        }

        // Step 1 — in-place ToolResult content truncation (immutable Message copy).
        List<Message> afterStep1 = truncateToolResults(original, props.getMaxToolResultBytes());
        boolean step1Changed = afterStep1.size() != original.size()
            || messagesContentDiffer(original, afterStep1);

        // Step 2 — sliding window (always run when assistant-count > keepRecentTurns, OR
        // when step 1 did not get us under the token budget).
        List<Message> afterStep2 = afterStep1;
        boolean step2Changed = false;
        if (countAssistantTurns(afterStep1) > props.getKeepRecentTurns()
            || estimateTokens(afterStep1) > props.getMaxPromptTokens()) {
            afterStep2 = applySlidingWindow(afterStep1, props.getKeepRecentTurns());
            step2Changed = messagesContentDiffer(afterStep1, afterStep2);
        }

        if (!step1Changed && !step2Changed) {
            return;
        }

        ctx.session().compact(afterStep2);
        log.info("compactor: step1={} step2={} turnsBefore={} turnsAfter={}",
            step1Changed, step2Changed, original.size(), afterStep2.size());
    }

    // ── Internals ──────────────────────────────────────────────────────

    /**
     * Replace each {@link Message.ToolResult#getContent()} longer than
     * {@code maxBytes} with {@code head + marker + tail}. Non-ToolResult messages pass
     * through unchanged.
     */
    static List<Message> truncateToolResults(List<Message> history, int maxBytes) {
        List<Message> out = new ArrayList<>(history.size());
        for (Message m : history) {
            if (m instanceof Message.ToolResult) {
                Message.ToolResult tr = (Message.ToolResult) m;
                String content = tr.getContent();
                int byteLen = content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
                if (byteLen > maxBytes) {
                    String truncated = truncateUtf8(content, maxBytes);
                    out.add(new Message.ToolResult(tr.getToolUseId(), truncated, tr.isError()));
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    /**
     * Keep only the most recent {@code keepRecentTurns} {@link Message.Assistant}
     * messages together with their paired {@link Message.ToolUse} / {@link Message.ToolResult}
     * triples (the user prompt immediately preceding the oldest kept assistant is also kept).
     * {@link Message.System} blocks at the head are preserved verbatim.
     *
     * <p>Algorithm: walk from the tail counting Assistant messages. The first
     * assistant that pushes the count past {@code keepRecentTurns} is the
     * (N+1)-th-from-end — we cut at the position right after it. Everything
     * from the resulting {@code cutIndex} onwards is kept; everything before
     * (except for any leading System blocks) is dropped.
     */
    static List<Message> applySlidingWindow(List<Message> history, int keepRecentTurns) {
        if (keepRecentTurns <= 0) {
            // Defensive — never drop everything; at least preserve System/User.
            List<Message> headOnly = new ArrayList<>();
            for (Message m : history) {
                if (m instanceof Message.System || m instanceof Message.User) {
                    headOnly.add(m);
                }
            }
            return headOnly;
        }

        // Walk backwards from the tail counting Assistant messages.
        int assistantSeen = 0;
        int dropBefore = 0; // exclusive: messages [dropBefore..end) are kept; [0..dropBefore) dropped
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof Message.Assistant) {
                assistantSeen++;
                if (assistantSeen > keepRecentTurns) {
                    // The (N+1)-th-from-end assistant lives at index i; cut right after it.
                    dropBefore = i + 1;
                    break;
                }
            }
        }

        if (dropBefore == 0) {
            // All assistant turns already within window — no-op.
            return history;
        }

        // Collect leading System messages that fall inside [0..dropBefore) — we want
        // to preserve those even though their position is older than the cut.
        List<Message> leadingSystem = new ArrayList<>();
        for (int i = 0; i < dropBefore; i++) {
            if (history.get(i) instanceof Message.System) {
                leadingSystem.add(history.get(i));
            } else {
                break;
            }
        }

        List<Message> out = new ArrayList<>(leadingSystem.size() + (history.size() - dropBefore));
        out.addAll(leadingSystem);
        for (int i = dropBefore; i < history.size(); i++) {
            out.add(history.get(i));
        }
        return out;
    }

    /** Conservative token estimate: total chars / 4 (industry heuristic). */
    static int estimateTokens(List<Message> messages) {
        long totalChars = 0L;
        for (Message m : messages) {
            totalChars += messageCharLen(m);
        }
        if (totalChars == 0L) {
            return 0;
        }
        long est = (totalChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
        return (int) Math.min(est, Integer.MAX_VALUE);
    }

    /** Count the number of {@link Message.Assistant} entries in {@code messages}. */
    static int countAssistantTurns(List<Message> messages) {
        int n = 0;
        for (Message m : messages) {
            if (m instanceof Message.Assistant) {
                n++;
            }
        }
        return n;
    }

    private static long messageCharLen(Message m) {
        if (m instanceof Message.System) {
            return ((Message.System) m).getContent() == null ? 0
                : ((Message.System) m).getContent().length();
        }
        if (m instanceof Message.User) {
            return ((Message.User) m).getContent() == null ? 0
                : ((Message.User) m).getContent().length();
        }
        if (m instanceof Message.Assistant) {
            Message.Assistant a = (Message.Assistant) m;
            long n = a.getText() == null ? 0 : a.getText().length();
            if (a.getToolCalls() != null) {
                for (ToolCall tc : a.getToolCalls()) {
                    n += tc.getName() == null ? 0 : tc.getName().length();
                    n += tc.getInput() == null ? 0 : tc.getInput().toString().length();
                }
            }
            return n;
        }
        if (m instanceof Message.ToolUse) {
            Message.ToolUse tu = (Message.ToolUse) m;
            long n = tu.getName() == null ? 0 : tu.getName().length();
            n += tu.getInput() == null ? 0 : tu.getInput().toString().length();
            return n;
        }
        if (m instanceof Message.ToolResult) {
            Message.ToolResult tr = (Message.ToolResult) m;
            return tr.getContent() == null ? 0 : tr.getContent().length();
        }
        return 0L;
    }

    /**
     * Compare two message lists by content fingerprint. Cheap; only invoked when sizes match.
     */
    private static boolean messagesContentDiffer(List<Message> a, List<Message> b) {
        if (a.size() != b.size()) {
            return true;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!messageEquals(a.get(i), b.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean messageEquals(Message x, Message y) {
        if (x == y) return true;
        if (x == null || y == null) return false;
        if (!x.getClass().equals(y.getClass())) return false;
        if (x instanceof Message.ToolResult) {
            Message.ToolResult a = (Message.ToolResult) x;
            Message.ToolResult b = (Message.ToolResult) y;
            return a.isError() == b.isError()
                && safeEq(a.getToolUseId(), b.getToolUseId())
                && safeEq(a.getContent(), b.getContent());
        }
        return safeEq(x.toString(), y.toString());
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Truncate a UTF-8 string so that the resulting byte length is &lt;= {@code maxBytes}.
     * Preserves a head + tail window around the cut so the model still sees both ends.
     * Naive character-wise iteration is acceptable — the content is already known to be
     * large and we are not optimizing for byte-exact precision.
     */
    static String truncateUtf8(String s, int maxBytes) {
        if (s == null) return null;
        int origBytes = s.getBytes(StandardCharsets.UTF_8).length;
        if (origBytes <= maxBytes) return s;

        int keep = Math.min(TRUNCATION_KEEP_BYTES, maxBytes / 2);
        // Walk character-by-character from the head keeping at most `keep` bytes.
        StringBuilder head = new StringBuilder(keep);
        int headBytes = 0;
        int i = 0;
        while (i < s.length() && headBytes < keep) {
            int cp = s.codePointAt(i);
            int cpBytes = utf8Bytes(cp);
            if (headBytes + cpBytes > keep) break;
            head.appendCodePoint(cp);
            headBytes += cpBytes;
            i += Character.charCount(cp);
        }

        // Walk from the tail back, also capped to `keep` bytes.
        StringBuilder tail = new StringBuilder(keep);
        int tailBytes = 0;
        int j = s.length();
        while (j > i && tailBytes < keep) {
            int cp = s.codePointBefore(j);
            int cpBytes = utf8Bytes(cp);
            if (tailBytes + cpBytes > keep) break;
            tail.insert(0, new String(Character.toChars(cp)));
            tailBytes += cpBytes;
            j -= Character.charCount(cp);
        }

        long saved = (long) origBytes - (headBytes + tailBytes);
        String marker = String.format(TRUNCATION_MARKER, saved);
        return head.append(marker).append(tail).toString();
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }
}
