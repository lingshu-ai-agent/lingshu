package ai.lingshu.cli;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Publisher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #020c — {@link CliRunner} ↔ {@link SkillCommandDispatcher} integration tests.
 *
 * <p>4 cases (L2) covering AC-020c-10:
 * <ul>
 *   <li>{@code doRun_withSlashPrompt_dispatchesSkill} — {@code --prompt "/commit fix bug"}
 *       routes through SkillCommandDispatcher (NOT agent.runBlocking).</li>
 *   <li>{@code doRun_withRegularPrompt_goesThroughLlm} — {@code --prompt "hello"} skips
 *       SkillCommandDispatcher and goes through the LLM path.</li>
 *   <li>{@code doRun_withListSkills_dumpsSkillListAndReturns} — {@code --list-skills}
 *       prints the banner without invoking factory.create.</li>
 *   <li>{@code doDoctor_appendsSkillListAtEnd} — {@code doctor} subcommand appends
 *       the Skill commands banner at the end of the standard doctor output.</li>
 * </ul>
 *
 * <p><b>Test doubles</b>: hand-rolled (no Mockito) per TestSupport rationale — JDK 23 +
 * Mockito 5's inline mock-maker is unreliable. All doubles are local to this class.
 */
@DisplayName("Story #020c — CliRunner Skill integration")
class CliRunnerSkillTriggerTest {

    // ── Hand-rolled stubs (no Mockito) ────────────────────────────────────

    /** Minimal {@link Skill} that returns a fixed execute content. */
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
                return new ObjectMapper().readTree(
                    "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}");
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

    /** Real {@link ToolRegistry} backed by ConcurrentHashMap — supports both Tool and Skill registration. */
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
        @Override public List<ai.lingshu.core.message.ToolSpec> modelVisibleSpecs() {
            return Collections.emptyList();
        }
        @Override public Skill findSkill(String n) { return skills.get(n); }
        @Override public Set<String> skillNames() { return Collections.unmodifiableSet(skills.keySet()); }
        @Override public Tool findByName(String n) {
            Tool t = tools.get(n);
            if (t == null) throw new IllegalArgumentException("Unknown tool: " + n);
            return t;
        }
        // 🆕 Story #021b — unregister: dual-index clear (mirrors DefaultToolRegistry.unregister
        // + the registerSkill dual-write pattern above). null name → false; unknown name → false.
        @Override public boolean unregister(String n) {
            if (n == null) return false;
            Tool removedTool = tools.remove(n);
            Skill removedSkill = skills.remove(n);
            return removedTool != null || removedSkill != null;
        }
    }

    /** Stub {@link ToolExecutor} returning a fixed ToolResult. */
    static final class StubToolExecutor implements ToolExecutor {
        ToolResult nextResult = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId("stub-id")
            .content("skill-execute-output")
            .isError(false)
            .build();
        ToolCall lastCall = null;
        @Override public ToolResult dispatch(ToolCall call, ToolExecutionContext ctx) {
            lastCall = call;
            return nextResult;
        }
    }

    /** Stub {@link Agent} — tracks whether runBlocking was called. */
    static final class StubAgent implements Agent {
        int runBlockingCount = 0;
        RunResult runBlockingResult = new RunResult(
            "agent-final-text", 1, Usage.zero(), StopReason.END_TURN, 0L);
        RunResult continueResult = new RunResult(
            "after-skill-continuation", 2, Usage.zero(), StopReason.END_TURN, 0L);
        String lastContinuedContent = null;
        @Override public Session session() { return new StubSession(); }
        @Override public AgentConfig config() { return AgentConfigDefaults.defaults(); }
        @Override public Publisher<AgentEvent> run(String u) { return null; }
        @Override public RunResult runBlocking(String u) {
            runBlockingCount++;
            return runBlockingResult;
        }
        @Override public Publisher<AgentEvent> continueWithUserMessage(String content) { return null; }
        @Override public RunResult continueWithUserMessageBlocking(String content) {
            lastContinuedContent = content;
            return continueResult;
        }
    }

    static final class StubSession implements Session {
        @Override public String id() { return "stub-session"; }
        @Override public List<Message> history() { return Collections.emptyList(); }
        @Override public Session fork(String subagentType) { return this; }
        @Override public Checkpoint checkpoint() { return null; }
    }

    /** Stub {@link ai.lingshu.core.impl.runtime.AgentFactory} — returns canned Agent + AgentConfig. */
    static final class StubAgentFactory extends ai.lingshu.core.impl.runtime.AgentFactory {
        private final AgentConfig cfg;
        private final Agent agent;
        int createCount = 0;
        StubAgentFactory(AgentConfig cfg, Agent agent) {
            super(null, null, null, null, null, null);
            this.cfg = cfg;
            this.agent = agent;
        }
        @Override public AgentConfig loadYamlAndValidate(Path path) { return cfg; }
        @Override public Agent create(AgentConfig cfg) {
            createCount++;
            return agent;
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    StubToolRegistry registry;
    StubToolExecutor executor;
    StubAgent agent;
    SkillCommandDispatcher dispatcher;
    StubAgentFactory factory;
    CliRunner runner;
    ByteArrayOutputStream outBuf;
    ByteArrayOutputStream errBuf;

    /** Minimal valid YAML content for AgentConfigDefaults.defaults(). */
    static final String VALID_YML = "agent:\n"
        + "  llm:\n"
        + "    provider: anthropic\n"
        + "    model: test\n"
        + "  sandbox:\n"
        + "    policy: default\n";

    @BeforeEach
    void setUp() {
        registry = new StubToolRegistry();
        executor = new StubToolExecutor();
        agent = new StubAgent();
        dispatcher = new SkillCommandDispatcher(registry, executor, new ObjectMapper());
        factory = new StubAgentFactory(AgentConfigDefaults.defaults(), agent);
        outBuf = new ByteArrayOutputStream();
        errBuf = new ByteArrayOutputStream();
        runner = new CliRunner(factory, dispatcher, new PrintStream(outBuf), new PrintStream(errBuf));
    }

    // ── AC-020c-10: doRun with /xxx prompt dispatches Skill ───────────────

    @Test
    @DisplayName("doRun: --prompt \"/commit fix bug\" → SkillCommandDispatcher path (no agent.runBlocking)")
    void doRun_withSlashPrompt_dispatchesSkill(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, VALID_YML.getBytes("UTF-8"));
        registry.registerSkill(new StubSkill("commit", "git commit helper", "skill-output-content"));
        executor.nextResult = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId("cli-skill-x")
            .content("tool-result-content")
            .isError(false)
            .build();
        Args args = new Args(Subcommand.RUN, yml, "/commit fix bug", null, null, false, false, false);

        runner.doRun(args);

        String stdout = outBuf.toString("UTF-8");
        // The Skill output must appear (continuation result: "after-skill-continuation")
        assertThat(stdout).contains("after-skill-continuation");
        // The /xxx intercept happened BEFORE agent.runBlocking, so agent.runBlocking must NOT have been called
        assertThat(agent.runBlockingCount)
            .as("agent.runBlocking must NOT be called when /xxx intercepts")
            .isZero();
        // factory.create was still called (we need the Agent for Skill → continue)
        assertThat(factory.createCount).isEqualTo(1);
        // executor.dispatch received a properly-formed ToolCall for "commit"
        assertThat(executor.lastCall).isNotNull();
        assertThat(executor.lastCall.getName()).isEqualTo("commit");
        assertThat(executor.lastCall.getInput().get("input").asText()).isEqualTo("fix bug");
        // continuation was fed the tool result content
        assertThat(agent.lastContinuedContent).isEqualTo("tool-result-content");
    }

    // ── AC-020c-10: doRun with regular prompt goes through LLM path ──────

    @Test
    @DisplayName("doRun: --prompt \"hello world\" (no slash) → falls through to LLM path")
    void doRun_withRegularPrompt_goesThroughLlm(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, VALID_YML.getBytes("UTF-8"));
        Args args = new Args(Subcommand.RUN, yml, "hello world", null, null, false, false, false);

        runner.doRun(args);

        String stdout = outBuf.toString("UTF-8");
        // agent.runBlocking WAS called with the prompt
        assertThat(agent.runBlockingCount).isEqualTo(1);
        // agent.continueWithUserMessageBlocking was NOT called (no Skill interception)
        assertThat(agent.lastContinuedContent).isNull();
        // The agent's stub response is printed
        assertThat(stdout).contains("agent-final-text");
        // executor was NOT called (no Skill involved)
        assertThat(executor.lastCall).isNull();
    }

    // ── AC-020c-10: doRun with --list-skills prints banner and returns ────

    @Test
    @DisplayName("doRun: --list-skills (no --prompt) → prints Skill banner; factory.create NOT called")
    void doRun_withListSkills_dumpsSkillListAndReturns(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, VALID_YML.getBytes("UTF-8"));
        registry.registerSkill(new StubSkill("commit", "git commit helper"));
        registry.registerSkill(new StubSkill("review", "PR review helper"));
        // --list-skills flag, NO --prompt (per T-04 validate() bypass)
        Args args = new Args(Subcommand.RUN, yml, null, null, null, false, false, true);

        runner.doRun(args);

        String stdout = outBuf.toString("UTF-8");
        assertThat(stdout).contains("[LINGS-Z99] Available commands (2):");
        assertThat(stdout).contains("/commit");
        assertThat(stdout).contains("/review");
        // factory.create was NOT called — banner is printed before factory.create
        assertThat(factory.createCount)
            .as("factory.create must NOT be called when --list-skills short-circuits")
            .isZero();
        // No LLM call
        assertThat(agent.runBlockingCount).isZero();
    }

    // ── AC-020c-10: doDoctor appends Skill banner at the end ──────────────

    @Test
    @DisplayName("doDoctor: appends \"[LINGS-Z99] Available commands (...)\" banner at end")
    void doDoctor_appendsSkillListAtEnd(@TempDir Path tmp) throws IOException {
        // Use TestSupport's real AgentFactory — it has wired Routers so factory.description()
        // works (StubAgentFactory passes null Routers, which makes description() NPE).
        ai.lingshu.core.impl.runtime.AgentFactory realFactory = TestSupport.buildFactory();
        CliRunner doctorRunner = new CliRunner(realFactory, dispatcher,
            new PrintStream(outBuf), new PrintStream(errBuf));
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, VALID_YML.getBytes("UTF-8"));
        registry.registerSkill(new StubSkill("commit", "git commit helper"));
        Args args = new Args(Subcommand.DOCTOR, yml, null, null, null, false, false, false);

        doctorRunner.doDoctor(args);

        String stdout = outBuf.toString("UTF-8");
        assertThat(stdout).contains("=== LingShu Doctor ===");
        assertThat(stdout).contains("agent ready (turns=");
        // The Skill banner must appear AFTER the doctor body
        int readyIdx = stdout.indexOf("agent ready (turns=");
        int bannerIdx = stdout.indexOf("[LINGS-Z99] Available commands (");
        assertThat(readyIdx).isGreaterThan(0);
        assertThat(bannerIdx).isGreaterThan(readyIdx);
        assertThat(stdout).contains("/commit");
    }

    // ── EC: --list-skills with empty registry still prints warning ────────

    @Test
    @DisplayName("doRun: --list-skills with no Skills → '(none registered...)' warning")
    void doRun_withListSkills_emptyRegistry(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, VALID_YML.getBytes("UTF-8"));
        Args args = new Args(Subcommand.RUN, yml, null, null, null, false, false, true);

        runner.doRun(args);

        String stdout = outBuf.toString("UTF-8");
        assertThat(stdout).contains("(none registered");
        assertThat(factory.createCount).isZero();
    }
}
