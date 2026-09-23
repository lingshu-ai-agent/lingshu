package ai.lingshu.cli;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.RunResult;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Story #020c — CLI {@code /xxx} intercept layer (dsh §6.4 L4039-4042 + L4266-4267).
 *
 * <p>Sits in lingshu-cli (NOT in lingshu-core) because:
 * <ul>
 *   <li>It owns CLI-specific output formatting ({@link #printSkillList(PrintStream)},
 *       {@link #listSkillNames()}).</li>
 *   <li>It depends on {@link LingsCliException} (Story #017 CLI module).</li>
 *   <li>It is the consumer of {@link ToolRegistry#findSkill(String)} (from lingshu-core)
 *       — never the implementor.</li>
 * </ul>
 *
 * <p><b>Three responsibilities</b> (mirroring dsh §6.4 L4039-4042):
 * <ol>
 *   <li><b>识别</b> — {@link #isSkillCommand(String)} /
 *       {@link #parse(String)}: detect whether user input is a {@code /xxx} command
 *       targeting a registered Skill.</li>
 *   <li><b>执行</b> — {@link #handleUserInput(String, Agent)}: build a synthetic
 *       {@link ToolCall}, route through {@link ToolExecutor#dispatch(ToolCall, ToolExecutionContext)}
 *       (§4.10.1 5-step pipeline — permission / registry / timeout / sandbox / execute
 *       <b>cannot be bypassed</b>), wrap the result content as a User message, and
 *       continue the turn via {@link Agent#continueWithUserMessageBlocking(String)}.</li>
 *   <li><b>展示</b> — {@link #printSkillList(PrintStream)} /
 *       {@link #listSkillNames()} / {@link #listSkills()}: surface available
 *       {@code /xxx} commands at startup or on doctor.</li>
 * </ol>
 *
 * <p><b>Why not call {@link Skill#execute(ToolCall, ToolExecutionContext)} directly:</b>
 * direct {@code execute} bypasses the 5-step {@link ToolExecutor} pipeline. The
 * {@link ToolExecutionContext} passed to the executor is the user-injected
 * CLI-single-shot context (see {@link CliSkillToolExecutionContext} below) — it does
 * NOT contribute to sandbox / permission enforcement in v1 (those are guarded by
 * Agent + RuntimeSandbox in the broader turn loop), but it ensures the executor's
 * surface contract holds.
 *
 * <p><b>Why {@link ObjectMapper} injected, not static:</b> tests can pass an
 * already-configured instance (e.g. with custom serialization); production uses
 * {@code new ObjectMapper()} on first call.
 */
@Component
public class SkillCommandDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SkillCommandDispatcher.class);

    private static final String SLASH_PREFIX = "/";

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public SkillCommandDispatcher(ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this(toolRegistry, toolExecutor, new ObjectMapper());
    }

    /** Test-only constructor — allows ObjectMapper stub for JSON construction. */
    SkillCommandDispatcher(ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                           ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    // ─── 1. 识别 ─────────────────────────────────────────────────────────

    /**
     * Parse {@code /xxx arg1 arg2 ...} → {@link ParsedCommand}(name + args).
     *
     * <p><b>Never throws</b> — empty input, missing {@code /} prefix, or trailing-only-slash
     * all yield {@code ParsedCommand("", "")} so the caller decides what to do next
     * (CliRunner dispatches to LLM path; doctor path simply shows an empty list).
     *
     * @param raw user input line (may be {@code null}, empty, whitespace-only, or any string)
     * @return parsed structure with {@code name} (empty if not a {@code /xxx} command) and
     *         {@code args} (everything after the name, trimmed)
     */
    public ParsedCommand parse(String raw) {
        if (raw == null) return new ParsedCommand("", "");
        String trimmed = raw.trim();
        if (!trimmed.startsWith(SLASH_PREFIX)) return new ParsedCommand("", "");
        String withoutSlash = trimmed.substring(SLASH_PREFIX.length());
        int ws = indexOfWhitespace(withoutSlash);
        if (ws < 0) return new ParsedCommand(withoutSlash, "");
        return new ParsedCommand(
            withoutSlash.substring(0, ws),
            withoutSlash.substring(ws + 1).trim());
    }

    /**
     * Whether {@code raw} is a registered {@code /xxx} command. Trailing args are ignored;
     * {@code isSkillCommand("/commit foo bar")} and {@code isSkillCommand("/commit")} both
     * return {@code true} iff {@code "commit"} is a registered Skill.
     *
     * @return {@code true} iff {@code raw} starts with {@code /}, the remaining first token is
     *         a Skill name in {@link ToolRegistry#skillNames()}, and the name is non-empty.
     */
    public boolean isSkillCommand(String raw) {
        ParsedCommand p = parse(raw);
        if (p.name.isEmpty()) return false;
        return toolRegistry.findSkill(p.name) != null;
    }

    // ─── 2. 执行 ─────────────────────────────────────────────────────────

    /**
     * CLI {@code /xxx} intercept entry point — mirrors dsh §6.4 L4039-4042
     * {@code handleUserInput(raw, agent, ctx)} contract adapted to CLI single-shot mode.
     *
     * <p><b>Five-step flow:</b>
     * <ol>
     *   <li>{@link #parse(String)} raw → name + args</li>
     *   <li>{@link ToolRegistry#findSkill(String)} — unregistered skill throws
     *       {@link LingsCliException} {@code LINGS-S05} with "Available: [...]" hint</li>
     *   <li>Build a {@link ToolCall} with input schema {@code {"input": args}}
     *       (matches {@code SkillTool.FIXED_INPUT_SCHEMA_JSON} / {@code CommitSkill.inputSchema()}
     *       from Story #020a)</li>
     *   <li>{@link ToolExecutor#dispatch(ToolCall, ToolExecutionContext)} — runs the
     *       §4.10.1 5-step pipeline (lookup / permission / timeout / sandbox / execute /
     *       checkpoint)</li>
     *   <li>Wrap {@code result.content} as synthetic User message →
     *       {@link Agent#continueWithUserMessageBlocking(String)} → return {@link RunResult}</li>
     * </ol>
     *
     * @param raw   user input (must start with {@code /}; non-{/} input → {@code LINGS-Z01})
     * @param agent live agent (just created via {@code AgentFactory.create(cfg)})
     * @return the terminal {@link RunResult} of the continuation turn
     * @throws LingsCliException {@code LINGS-Z01} if raw is empty / has no {@code /} prefix
     * @throws LingsCliException {@code LINGS-S05} if the skill name is not registered
     * @throws LingsCliException {@code LINGS-T02} if {@code ToolResult.isError()} is true
     */
    public RunResult handleUserInput(String raw, Agent agent) {
        ParsedCommand p = parse(raw);
        if (p.name.isEmpty()) {
            throw new LingsCliException("LINGS-Z01",
                "empty or invalid /xxx command: '" + raw + "'",
                "type /<skill-name> with optional args (use `lingshu doctor` to list available)");
        }
        Skill skill = toolRegistry.findSkill(p.name);
        if (skill == null) {
            throw new LingsCliException("LINGS-S05",
                "Unknown skill command: /" + p.name,
                "Available: " + sortedSkillNamesPrefixed());
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("input", p.args);
        ToolCall call = new ToolCall(
            "cli-skill-" + System.currentTimeMillis(),
            p.name,
            input);
        ToolExecutionContext ctx = new CliSkillToolExecutionContext(agent.session());
        ToolResult result = toolExecutor.dispatch(call, ctx);
        if (result.isError()) {
            throw new LingsCliException("LINGS-T02",
                "Skill execution failed: " + result.getContent(),
                "check skill input / system logs");
        }
        return agent.continueWithUserMessageBlocking(result.getContent());
    }

    // ─── 3. 展示 ─────────────────────────────────────────────────────────

    /**
     * Render the available {@code /xxx} commands to {@code out}. Used by:
     * <ul>
     *   <li>{@code CliRunner.doRun} when {@code --list-skills} is supplied</li>
     *   <li>{@code CliRunner.doResume} same flag</li>
     *   <li>{@code CliRunner.doDoctor} at end of doctor output</li>
     * </ul>
     * Output is sorted by name (byte-lexical) and description is truncated to
     * {@value #DESC_MAX_LENGTH} chars + ellipsis to keep the banner tidy.
     */
    public void printSkillList(PrintStream out) {
        Set<String> names = toolRegistry.skillNames();
        if (names == null || names.isEmpty()) {
            out.println("[LINGS-Z99] Available commands: (none registered — check agent.skills.* config)");
            return;
        }
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);
        out.println("[LINGS-Z99] Available commands (" + sorted.size() + "):");
        for (String n : sorted) {
            Skill s = toolRegistry.findSkill(n);
            String desc = s == null ? "(no description)" : truncate(s.description(), DESC_MAX_LENGTH);
            out.println("  /" + n + "   — " + desc);
        }
    }

    /** Maximum description length in {@link #printSkillList}. */
    public static final int DESC_MAX_LENGTH = 80;

    /** Sorted list of all registered Skill names. Empty registry → empty list. */
    public List<String> listSkillNames() {
        Set<String> names = toolRegistry.skillNames();
        List<String> out = new ArrayList<String>(names == null ? 0 : names.size());
        if (names != null) {
            for (String n : names) out.add(n);
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Sorted list of {@link SkillInfo}(name + description) — convenience for future
     * REPL tab-completion / docs tooling. Empty registry → empty list.
     */
    public List<SkillInfo> listSkills() {
        Set<String> names = toolRegistry.skillNames();
        TreeSet<String> sorted = names == null ? new TreeSet<String>() : new TreeSet<String>(names);
        List<SkillInfo> out = new ArrayList<SkillInfo>(sorted.size());
        for (String n : sorted) {
            Skill s = toolRegistry.findSkill(n);
            out.add(new SkillInfo(n, s == null ? "" : s.description()));
        }
        return out;
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /** Sorted view of {@code toolRegistry.skillNames()} — used in {@code LINGS-S05} hint. */
    private List<String> sortedSkillNames() {
        return listSkillNames();
    }

    /**
     * Sorted view of available skills each prefixed with {@code /} — used in the
     * {@code LINGS-S05} hint so the suggestion reads naturally as {@code [/commit, /review]}.
     */
    private List<String> sortedSkillNamesPrefixed() {
        List<String> names = listSkillNames();
        List<String> out = new ArrayList<String>(names.size());
        for (String n : names) out.add("/" + n);
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Parsed {@code /xxx} command: skill name + rest as input string. */
    public static final class ParsedCommand {
        public final String name;
        public final String args;
        public ParsedCommand(String name, String args) {
            this.name = name == null ? "" : name;
            this.args = args == null ? "" : args;
        }
    }

    /** Public Skill summary — name + description pair. */
    public static final class SkillInfo {
        public final String name;
        public final String description;
        public SkillInfo(String name, String description) {
            this.name = name;
            this.description = description == null ? "" : description;
        }
    }

    // ── CliSkillToolExecutionContext — minimal ctx for single-shot CLI dispatch ──

    /**
     * Minimal {@link ToolExecutionContext} used by {@link #handleUserInput(String, Agent)}.
     *
     * <p>The CLI path runs ONE tool call out-of-band (not as part of a real engine turn).
     * The executor only requires a {@link Session} reference; all sandbox / network /
     * approval surfaces are intentionally defaulted / no-op because this MVP Story
     * does not own a full {@link TurnContext}.
     *
     * <p><b>Why not reuse {@code DefaultToolExecutionContext}:</b> that class wraps a
     * live {@link TurnContext} produced inside the engine. The CLI single-shot mode
     * doesn't have one — there's no active turn to lend. Building a stub here keeps
     * Story #020c strictly additive (no engine surface change).
     *
     * <p><b>Future</b>: a follow-up Story should:
     * (a) extend {@link Agent} with a {@code ToolExecutionContext lendToCli()} helper,
     * (b) wire {@code CliSkillToolExecutionContext} to a real {@code TurnContext},
     * (c) activate the full {@code PermissionPolicy} / {@code Sandbox} gates.
     * For now, user-invoked commands run with no extra check — the user typed
     * them, that's their authorization.
     */
    private static final class CliSkillToolExecutionContext implements ToolExecutionContext {

        private final Session session;

        CliSkillToolExecutionContext(Session session) {
            this.session = session;
        }

        @Override public Session session() { return session; }

        @Override public ToolSink sink() {
            return new ToolSink() {
                @Override public void emitPartial(String partial) {
                    if (LOG.isDebugEnabled()) LOG.debug("cli-skill partial: {}", partial);
                }
                @Override public void emitProgress(String progress) {
                    if (LOG.isDebugEnabled()) LOG.debug("cli-skill progress: {}", progress);
                }
            };
        }

        @Override public Path workingDirectory() {
            return Paths.get(System.getProperty("user.dir"));
        }

        @Override public FileSystem fs() { return FileSystems.getDefault(); }

        @Override public NetworkClient http() {
            return new NetworkClient() {
                @Override public String get(String url) throws IOException {
                    throw new UnsupportedOperationException(
                        "HTTP tool calls are wired in Story #016 — currently unsupported");
                }
                @Override public String post(String url, String body) throws IOException {
                    throw new UnsupportedOperationException(
                        "HTTP tool calls are wired in Story #016 — currently unsupported");
                }
                @Override public InputStream getStream(String url) throws IOException {
                    throw new UnsupportedOperationException(
                        "HTTP tool calls are wired in Story #016 — currently unsupported");
                }
            };
        }

        @Override public ApprovalGate approval() {
            // No approval flow in CLI single-shot mode — user-initiated commands are
            // implicitly user-approved.
            return new ApprovalGate() {
                @Override public Decision ask(Decision.AskUser ask) {
                    return new Decision.Deny(
                        "CLI Skill dispatch does not support AskUser approval (Story #020c MVP)");
                }
            };
        }

        @Override public CancellationToken cancellation() {
            // No external cancellation in single-shot mode (no Ctrl+C propagation to a
            // pinned turn); the CLI thread fully blocks until dispatch returns.
            return new CancellationToken() {
                @Override public boolean isCancelled() { return false; }
                @Override public Runnable onCancel(Runnable callback) {
                    return new Runnable() {
                        @Override public void run() { /* no-op unregister */ }
                    };
                }
                @Override public void fire() { /* no-op */ }
            };
        }

        @Override public ToolCallConfig callConfig() {
            // Generous default — CLI single-shot commands shouldn't fail by timeout.
            // Per-CLI-config tuning is a future Story.
            return new ToolCallConfig(60, 0, 0);
        }
    }
}
