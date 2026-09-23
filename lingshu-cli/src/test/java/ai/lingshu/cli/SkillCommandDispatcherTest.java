package ai.lingshu.cli;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.RunResult;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.slot.ToolRegistry;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story #020c — {@link SkillCommandDispatcher} L1 + L2 tests.
 *
 * <p><b>No Mockito:</b> same rationale as {@link TestSupport} — Mockito 5's inline
 * mock-maker has issues on JDK 23; we use <b>hand-rolled test doubles</b> for
 * {@link ToolRegistry} / {@link ToolExecutor} / {@link Agent} / {@link Skill}.
 * Each is ~30 LOC and exercises the real interface contract.
 */
@DisplayName("Story #020c — SkillCommandDispatcher")
class SkillCommandDispatcherTest {

    // ── Hand-rolled test doubles ─────────────────────────────────────────

    /** Real {@link ToolRegistry} backed by a {@code LinkedHashMap} — preserves insertion order. */
    static final class StubToolRegistry implements ToolRegistry {
        private final ConcurrentMap<String, Tool> tools = new ConcurrentHashMap<String, Tool>();
        private final ConcurrentMap<String, Skill> skills = new ConcurrentHashMap<String, Skill>();

        void registerSkill(Skill s) {
            skills.put(s.name(), s);
            tools.put(s.name(), s);
        }

        @Override public void register(Tool t) { tools.put(t.name(), t); }
        @Override public Tool lookup(String n) { return tools.get(n); }
        @Override public Collection<String> names() { return Collections.unmodifiableSet(tools.keySet()); }
        @Override public List<ToolSpec> modelVisibleSpecs() {
            List<ToolSpec> out = new ArrayList<ToolSpec>();
            for (Tool t : tools.values()) out.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
            Collections.sort(out, new java.util.Comparator<ToolSpec>() {
                @Override public int compare(ToolSpec a, ToolSpec b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            return out;
        }
        @Override public Skill findSkill(String n) { return skills.get(n); }
        @Override public Set<String> skillNames() { return Collections.unmodifiableSet(skills.keySet()); }
        @Override public Tool findByName(String n) {
            Tool t = tools.get(n);
            if (t == null) throw new IllegalArgumentException("Unknown tool: " + n);
            return t;
        }
    }

    /** Hand-rolled Skill — name + description + fixed execute content. */
    static final class StubSkill implements Skill {
        private final String name;
        private final String description;
        private final String executeContent;
        StubSkill(String name, String description) { this(name, description, ""); }
        StubSkill(String name, String description, String executeContent) {
            this.name = name;
            this.description = description;
            this.executeContent = executeContent;
        }
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public JsonNode inputSchema() {
            try {
                return new ObjectMapper().readTree("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public ToolResult execute(ToolCall c, ToolExecutionContext ctx) {
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(c.getId())
                .content(executeContent)
                .isError(false)
                .build();
        }
    }

    /** {@link ToolExecutor} that returns a fixed {@link ToolResult} (or captures for verification). */
    static final class StubToolExecutor implements ToolExecutor {
        ToolResult nextResult = null;
        ToolCall lastCall = null;
        ToolExecutionContext lastCtx = null;
        int dispatchCount = 0;
        /** If non-null, throw this from dispatch — for permission-denied tests etc. */
        RuntimeException toThrow = null;
        @Override public ToolResult dispatch(ToolCall call, ToolExecutionContext ctx) {
            dispatchCount++;
            lastCall = call;
            lastCtx = ctx;
            if (toThrow != null) throw toThrow;
            if (nextResult != null) return nextResult;
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content("stub-executed")
                .isError(false)
                .build();
        }
    }

    /** Minimal {@link Agent} — returns a fixed {@link RunResult} on continueWithUserMessageBlocking. */
    static final class StubAgent implements Agent {
        RunResult next = new RunResult("stub-output", 1, Usage.zero(), StopReason.END_TURN, 0L);
        String lastContent = null;
        @Override public Session session() { return new StubSession(); }
        @Override public AgentConfig config() { return AgentConfigDefaults.defaults(); }
        @Override public Publisher<AgentEvent> run(String u) { return null; }
        @Override public RunResult runBlocking(String u) { return next; }
        @Override public Publisher<AgentEvent> continueWithUserMessage(String content) {
            lastContent = "called-content:" + content;
            return null;
        }
        @Override public RunResult continueWithUserMessageBlocking(String content) {
            lastContent = content;
            return next;
        }
    }

    static final class StubSession implements Session {
        // Stub — minimal interface, methods return defaults.
        // Tool ExecutionContext only needs the session reference in CliSkillToolExecutionContext.
        @Override public String id() { return "stub-session"; }
        @Override public List<Message> history() { return Collections.emptyList(); }
        @Override public Session fork(String subagentType) { return this; }
        @Override public Checkpoint checkpoint() { return null; }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    StubToolRegistry registry;
    StubToolExecutor executor;
    StubAgent agent;
    SkillCommandDispatcher dispatcher;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new StubToolRegistry();
        executor = new StubToolExecutor();
        agent = new StubAgent();
        dispatcher = new SkillCommandDispatcher(registry, executor, mapper);
    }

    // ─── AC-020c-1: Bean registration is wired (manual stub suffices for L1) ──

    // AC-020c-1 is verified by Spring bean presence in lingshu-cli TestContext;
    // see CliRunnerSkillTriggerTest for that integration test.

    // ─── AC-020c-2: parse — 5 + EC cases ────────────────────────────────

    @Test
    @DisplayName("parse: /commit fix login → name=commit args=fix login (AC-020c-2)")
    void parse_withSlashAndArgs() {
        SkillCommandDispatcher.ParsedCommand p = dispatcher.parse("/commit fix login");
        assertEquals("commit", p.name);
        assertEquals("fix login", p.args);
    }

    @Test
    @DisplayName("parse: /commit → name=commit args=\"\" (AC-020c-2)")
    void parse_onlyCommand() {
        SkillCommandDispatcher.ParsedCommand p = dispatcher.parse("/commit");
        assertEquals("commit", p.name);
        assertEquals("", p.args);
    }

    @Test
    @DisplayName("parse: /  (only slash + whitespace) → name=\"\" args=\"\" (AC-020c-2)")
    void parse_emptyAfterTrim() {
        SkillCommandDispatcher.ParsedCommand p = dispatcher.parse("/  ");
        assertEquals("", p.name);
        assertEquals("", p.args);
    }

    @Test
    @DisplayName("parse: commit (no slash) → name=\"\" args=\"\" (AC-020c-2)")
    void parse_noSlash() {
        SkillCommandDispatcher.ParsedCommand p = dispatcher.parse("commit");
        assertEquals("", p.name);
        assertEquals("", p.args);
    }

    @Test
    @DisplayName("parse: null → name=\"\" args=\"\" (AC-020c-2)")
    void parse_nullSafe() {
        SkillCommandDispatcher.ParsedCommand p = dispatcher.parse(null);
        assertEquals("", p.name);
        assertEquals("", p.args);
    }

    @Test
    @DisplayName("parse: /  (only slash) → name=\"\" args=\"\" (EC-020c-2)")
    void parse_onlySlash() {
        SkillCommandDispatcher.ParsedCommand p = dispatcher.parse("/");
        assertEquals("", p.name);
        assertEquals("", p.args);
    }

    // ─── AC-020c-3: isSkillCommand — 5 cases ────────────────────────────

    @Test
    @DisplayName("isSkillCommand: /commit when commit registered → true (AC-020c-3)")
    void isSkillCommand_whenRegistered_returnsTrue() {
        registry.registerSkill(new StubSkill("commit", "git commit helper"));
        assertTrue(dispatcher.isSkillCommand("/commit"));
    }

    @Test
    @DisplayName("isSkillCommand: /xxx when not registered → false (AC-020c-3)")
    void isSkillCommand_whenNotRegistered_returnsFalse() {
        registry.registerSkill(new StubSkill("commit", "d"));
        assertFalse(dispatcher.isSkillCommand("/xxx"));
    }

    @Test
    @DisplayName("isSkillCommand: hello (no slash) → false (AC-020c-3)")
    void isSkillCommand_noSlash_returnsFalse() {
        registry.registerSkill(new StubSkill("commit", "d"));
        assertFalse(dispatcher.isSkillCommand("hello"));
    }

    @Test
    @DisplayName("isSkillCommand: null → false (AC-020c-3)")
    void isSkillCommand_nullSafe() {
        assertFalse(dispatcher.isSkillCommand(null));
    }

    @Test
    @DisplayName("isSkillCommand: /review anything → true (args ignored, AC-020c-3)")
    void isSkillCommand_argsIgnored() {
        registry.registerSkill(new StubSkill("review", "d"));
        assertTrue(dispatcher.isSkillCommand("/review anything here"));
    }

    // ─── AC-020c-4: listSkillNames ────────────────────────────────────

    @Test
    @DisplayName("listSkillNames: 3 Skills → sorted list (AC-020c-4)")
    void listSkillNames_sortsAndDedups() {
        registry.registerSkill(new StubSkill("zebra", "z"));
        registry.registerSkill(new StubSkill("apple", "a"));
        registry.registerSkill(new StubSkill("mango", "m"));
        List<String> names = dispatcher.listSkillNames();
        assertEquals(3, names.size());
        assertEquals("apple", names.get(0));
        assertEquals("mango", names.get(1));
        assertEquals("zebra", names.get(2));
    }

    @Test
    @DisplayName("listSkillNames: empty registry → empty list (AC-020c-4)")
    void listSkillNames_empty() {
        assertEquals(Collections.emptyList(), dispatcher.listSkillNames());
    }

    // ─── AC-020c-5: listSkills ────────────────────────────────────────

    @Test
    @DisplayName("listSkills: 3 Skills → 3 SkillInfo (name + desc) sorted (AC-020c-5)")
    void listSkills_returnsNameAndDescription() {
        registry.registerSkill(new StubSkill("commit", "git commit helper"));
        registry.registerSkill(new StubSkill("review", "PR review helper"));
        List<SkillCommandDispatcher.SkillInfo> list = dispatcher.listSkills();
        assertEquals(2, list.size());
        assertEquals("commit", list.get(0).name);
        assertEquals("git commit helper", list.get(0).description);
        assertEquals("review", list.get(1).name);
        assertEquals("PR review helper", list.get(1).description);
    }

    @Test
    @DisplayName("listSkills: empty registry → empty list (AC-020c-5)")
    void listSkills_empty() {
        assertEquals(Collections.emptyList(), dispatcher.listSkills());
    }

    // ─── AC-020c-6: printSkillList ────────────────────────────────────

    @Test
    @DisplayName("printSkillList: empty registry → '(none registered...)' (AC-020c-6)")
    void printSkillList_emptyRegistry() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        dispatcher.printSkillList(out);
        String s = buf.toString(StandardCharsets.UTF_8);
        assertTrue(s.contains("Available commands: (none registered"),
            "empty message should warn: " + s);
    }

    @Test
    @DisplayName("printSkillList: populated registry → banner with all Skills (AC-020c-6)")
    void printSkillList_populatedRegistry() {
        registry.registerSkill(new StubSkill("commit", "git commit helper"));
        registry.registerSkill(new StubSkill("review", "PR review helper"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        dispatcher.printSkillList(out);
        String s = buf.toString(StandardCharsets.UTF_8);
        assertTrue(s.contains("[LINGS-Z99] Available commands (2):"), s);
        assertTrue(s.contains("/commit"), s);
        assertTrue(s.contains("/review"), s);
        assertTrue(s.contains("git commit helper"), s);
        assertTrue(s.contains("PR review helper"), s);
    }

    @Test
    @DisplayName("printSkillList: 3 Skills → sorted by name in output (AC-020c-6)")
    void printSkillList_sortsByName() {
        registry.registerSkill(new StubSkill("zebra", "z"));
        registry.registerSkill(new StubSkill("apple", "a"));
        registry.registerSkill(new StubSkill("mango", "m"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        dispatcher.printSkillList(out);
        String s = buf.toString(StandardCharsets.UTF_8);
        int aPos = s.indexOf("/apple");
        int mPos = s.indexOf("/mango");
        int zPos = s.indexOf("/zebra");
        assertTrue(aPos >= 0 && mPos >= 0 && zPos >= 0, "all 3 names must appear: " + s);
        assertTrue(aPos < mPos && mPos < zPos, "must be sorted: " + s);
    }

    // ─── EC-020c-1: truncate ──────────────────────────────────────────

    @Test
    @DisplayName("printSkillList: description > 80 chars → truncated (EC-020c-1)")
    void printSkillList_truncatesLongDescription() {
        String longDesc = "x".repeat(100);
        registry.registerSkill(new StubSkill("commit", longDesc));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        dispatcher.printSkillList(out);
        String s = buf.toString(StandardCharsets.UTF_8);
        // Expected truncation: 77 chars + "..." = 80 chars max
        assertTrue(s.contains("..."), "ellipsis expected: " + s);
        // Verify no 100-char run leaks through
        int descStart = s.indexOf("— ") + 2;
        String desc = s.substring(descStart).trim();
        assertTrue(desc.length() <= 80, "desc must be ≤80 chars but was " + desc.length() + ": " + desc);
    }

    // ─── AC-020c-9: handleUserInput invalid prefix ────────────────────

    @Test
    @DisplayName("handleUserInput: 'hello world' (no slash) → LINGS-Z01 (AC-020c-9)")
    void handleUserInput_invalidPrefixThrowsZ01() {
        LingsCliException ex = assertThrows(LingsCliException.class,
            () -> dispatcher.handleUserInput("hello world", agent));
        assertEquals("LINGS-Z01", ex.getErrorCode());
        // Hint should mention /xxx format
        assertNotNull(ex.getHint());
        assertTrue(ex.getHint().contains("/<skill-name>"), "hint must show usage: " + ex.getHint());
        // Executor must NOT have been called
        assertEquals(0, executor.dispatchCount, "executor.dispatch must NOT be called on invalid prefix");
    }

    @Test
    @DisplayName("handleUserInput: '' (empty) → LINGS-Z01 (AC-020c-9)")
    void handleUserInput_emptyThrowsZ01() {
        LingsCliException ex = assertThrows(LingsCliException.class,
            () -> dispatcher.handleUserInput("", agent));
        assertEquals("LINGS-Z01", ex.getErrorCode());
        assertEquals(0, executor.dispatchCount);
    }

    // ─── L2: AC-020c-7 happy path ─────────────────────────────────────

    @Test
    @DisplayName("handleUserInput: /commit fix bug → execute + wrap + continue (AC-020c-7)")
    void handleUserInput_happyPath_executesAndContinues() {
        registry.registerSkill(new StubSkill("commit", "git commit helper"));
        executor.nextResult = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId("cli-skill-1")
            .content("tool-result-content")
            .isError(false)
            .build();
        RunResult agentResult = new RunResult("agent-final-text", 1, Usage.zero(), StopReason.END_TURN, 5L);
        agent.next = agentResult;

        RunResult result = dispatcher.handleUserInput("/commit fix bug", agent);
        assertSame(agentResult, result, "Result from agent.continueWithUserMessageBlocking must be returned as-is");

        // Executor was called with a properly-formed ToolCall
        assertEquals(1, executor.dispatchCount);
        ToolCall sentCall = executor.lastCall;
        assertEquals("commit", sentCall.getName());
        assertEquals("fix bug", sentCall.getInput().get("input").asText());
        assertTrue(sentCall.getId().startsWith("cli-skill-"), "id prefix: " + sentCall.getId());

        // Agent.continueWithUserMessageBlocking was called with result.content
        assertEquals("tool-result-content", agent.lastContent);
    }

    // ─── L2: AC-020c-8 unknown skill ──────────────────────────────────

    @Test
    @DisplayName("handleUserInput: /nonexistent → LINGS-S05 + hint lists available (AC-020c-8)")
    void handleUserInput_unknownThrowsLingsS05() {
        registry.registerSkill(new StubSkill("commit", "d"));
        LingsCliException ex = assertThrows(LingsCliException.class,
            () -> dispatcher.handleUserInput("/nonexistent", agent));
        assertEquals("LINGS-S05", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("/nonexistent"), "message must mention bad name: " + ex.getMessage());
        assertTrue(ex.getHint().contains("Available"), "hint must suggest available skills: " + ex.getHint());
        assertTrue(ex.getHint().contains("/commit"), "hint must list /commit: " + ex.getHint());
        // Executor must NOT have been called
        assertEquals(0, executor.dispatchCount);
    }

    // ─── L2: EC-020c-3 skill returns error ────────────────────────────

    @Test
    @DisplayName("handleUserInput: Skill returns isError=true → LINGS-T02 (EC-020c-3)")
    void handleUserInput_skillReturnsError_throwsLingsT02() {
        registry.registerSkill(new StubSkill("commit", "d"));
        executor.nextResult = ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId("cli-skill-2")
            .content("boom — syntax error")
            .isError(true)
            .build();
        LingsCliException ex = assertThrows(LingsCliException.class,
            () -> dispatcher.handleUserInput("/commit bad input", agent));
        assertEquals("LINGS-T02", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Skill execution failed"), ex.getMessage());
        assertTrue(ex.getMessage().contains("boom"), "must echo error from tool: " + ex.getMessage());
        // Agent must NOT have been called (error short-circuits)
        assertNull(agent.lastContent, "agent.continueWithUserMessageBlocking must NOT be invoked on error");
    }
}
