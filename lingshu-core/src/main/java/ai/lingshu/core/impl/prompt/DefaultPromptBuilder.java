package ai.lingshu.core.impl.prompt;

import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.ModelHints;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Default 5-segment prompt builder (dsh §4.5.1) — Story #001 + #002 + #024.
 *
 * <p>Assembles a single system message from (in order):
 * <ol>
 *   <li>{@code [ROLE]} — identity.name + role + traits + tone + language</li>
 *   <li>{@code [INSTRUCTIONS]} — file or inline (mustache {@code {{var}}} rendering
 *       applied if {@code instructions.templateEngine == "mustache"})</li>
 *   <li>{@code [PROJECT MEMORY]} — concatenated {@link MemorySource#load} results
 *       from the resolved MemorySource list (Story #002); joined by
 *       {@code \n\n── separator ──\n\n} between non-null blocks</li>
 *   <li>{@code [CONVERSATION HISTORY]} — session history (read-only view of prior messages)</li>
 *   <li>{@code [USER MESSAGE]} — the current turn's input</li>
 * </ol>
 *
 * <p>Empty segments are skipped silently — the system message stays compact.
 * Tool schemas are returned in the {@link Prompt#getTools() tools} field (not stuffed
 * into the system text) per dsh §4.5.1 v1.5.13.
 *
 * <p>Story #002: the MemorySource list is provided at construction time (resolved
 * once by {@code DefaultPromptBuilderProvider} via {@code MemorySourceRouter}).
 * Each {@code build(ctx)} call iterates the same list and calls {@code load(ctx)}
 * on each source — sources are expected to be re-entrant (read-only over ctx).
 *
 * <p>🆕 Story #024 — {@code [TOOL SCHEMAS]} wiring: the constructor now also accepts
 * a {@link ToolRegistry} (typically the shared
 * {@link ai.lingshu.core.impl.tool.DefaultToolRegistry} Spring singleton). Each
 * {@code build(ctx)} call reads the registry's {@link ToolRegistry#modelVisibleSpecs()}
 * — a sorted snapshot of all registered {@code Tool}/{@code Skill} schemas — into
 * {@link Prompt#getTools() tools}. This closes the loop on dsh §4.5.1 + §6.4
 * (Skill schemas) + §6.5 (1) (local) + (2) (MCP) + (3) (Spring AI {@code @AgentTool})
 * + §5.6.3 (RemoteAgentTool) — every tool source registered with the registry now
 * becomes visible to the model in the same prompt.
 *
 * <p>OQ-5 (dsh §5.6.3.0) — originally "PromptBuilder {@code [TOOL SCHEMAS]} should
 * integrate {@code RemoteAgentSchemaBuilder}": <b>resolved by this Story</b>. The
 * chosen design (dsh §5.6.3 + §5.6.3.0) uses a single {@code RemoteAgentTool} that
 * wraps each remote agent; per-skill schemas are surfaced via
 * {@code RemoteAgentTool.description()} (HINT mode when count ≤
 * {@code a2a.descriptionSkillLimit}; otherwise a count + LIST truncation). With
 * this Story's wiring, the {@code RemoteAgentTool} registers via
 * {@code RemoteAgentToolLifecycle} (Story #009e) and becomes visible to the LLM
 * through the registry — the {@code RemoteAgentSchemaBuilder} (Story #009d) is its
 * upstream source for the per-skill schema list.
 */
public class DefaultPromptBuilder implements PromptBuilder {

    /** Separator inserted between non-null {@code [PROJECT MEMORY]} blocks. */
    private static final String MEMORY_SEPARATOR = "\n\n── separator ──\n\n";

    private final List<MemorySource> memorySources;
    /** 🆕 Story #024 — may be {@code null} for tests / back-compat (treated as empty). */
    private final ToolRegistry toolRegistry;

    /**
     * Backward-compat constructor (Story #001 / #002 / #019 era) — produces an
     * empty {@code [TOOL SCHEMAS]} segment regardless of the runtime registry.
     * Kept so existing tests and any user code that constructed a builder
     * directly (no Spring injection) still compile.
     *
     * <p>New code paths should use {@link #DefaultPromptBuilder(List, ToolRegistry)}
     * to get real tool-schema injection.
     */
    public DefaultPromptBuilder(List<MemorySource> memorySources) {
        this(memorySources, null);
    }

    /**
     * Full constructor (Story #024) — injects the shared {@link ToolRegistry} so
     * {@link #build(TurnContext)} can read {@link ToolRegistry#modelVisibleSpecs()}
     * into the {@link Prompt#getTools() tools} field on every turn.
     *
     * @param memorySources resolved memory-source list (eagerly resolved by
     *                      {@code DefaultPromptBuilderProvider}); null → empty.
     * @param toolRegistry  shared registry bean (typically
     *                      {@code DefaultToolRegistry}); null → empty tools segment
     *                      (matches the back-compat 1-arg ctor behavior).
     */
    public DefaultPromptBuilder(List<MemorySource> memorySources, ToolRegistry toolRegistry) {
        this.memorySources = memorySources != null
            ? Collections.unmodifiableList(new ArrayList<>(memorySources))
            : Collections.<MemorySource>emptyList();
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Prompt build(TurnContext ctx) {
        AgentConfig cfg = ctx.config();
        AgentConfig.Identity id = cfg.getIdentity() != null ? cfg.getIdentity() : AgentConfig.Identity.defaults();
        AgentConfig.Instructions ins = cfg.getInstructions() != null ? cfg.getInstructions() : AgentConfig.Instructions.empty();

        StringBuilder sys = new StringBuilder();

        // ── [ROLE] ──────────────────────────────────────────────────
        appendIfPresent(sys, "你是 " + id.getName()
            + (isBlank(id.getRole()) ? "" : "," + id.getRole()) + "。");
        if (!isBlank(id.getLanguage())) {
            appendIfPresent(sys, "输出语言:" + id.getLanguage());
        }
        if (id.getTraits() != null && !id.getTraits().isEmpty()) {
            appendIfPresent(sys, "人格特质:" + String.join("、", id.getTraits()));
        }
        if (!isBlank(id.getTone())) {
            appendIfPresent(sys, "语气:" + id.getTone());
        }

        // ── [INSTRUCTIONS] ──────────────────────────────────────────
        String insText = readInstructions(ins);
        if (!isBlank(insText)) {
            insText = renderTemplate(insText, ins.getVariables(), ins.getTemplateEngine());
            if (!isBlank(insText)) {
                if (sys.length() > 0) sys.append("\n\n");
                sys.append(insText);
            }
        }

        // ── [PROJECT MEMORY] ────────────────────────────────────────
        // Story #002: iterate resolved MemorySource list, join non-null with separator
        StringBuilder memSb = new StringBuilder();
        for (MemorySource ms : memorySources) {
            String block = ms.load(ctx);
            if (isBlank(block)) continue;
            if (memSb.length() > 0) memSb.append(MEMORY_SEPARATOR);
            memSb.append(block);
        }
        if (memSb.length() > 0) {
            if (sys.length() > 0) sys.append("\n\n");
            sys.append(memSb.toString());
        }

        // ── Build messages list ─────────────────────────────────────
        List<Message> messages = new ArrayList<>();
        if (sys.length() > 0) {
            messages.add(new Message.System(sys.toString(), "default"));
        }
        // [CONVERSATION HISTORY] — read view of prior messages
        // We intentionally do NOT include the current User input here; we add it explicitly
        // after the assistant's first response so the history view is consistent across turns.
        // For a fresh turn where the user input was just appended by Agent.run, we still
        // include everything currently in history (the latest User message is duplicated —
        // dsh §6.1 LinearTurnEngine accepts this redundancy).
        for (Message m : ctx.session().history()) {
            messages.add(m);
        }
        // [USER MESSAGE] — current turn's input
        messages.add(new Message.User(ctx.userInput() == null ? "" : ctx.userInput()));

        // ── [TOOL SCHEMAS] — independent Prompt.tools field ─────────
        // Story #001 / #002: no tools registered yet, so this stays empty.
        // Story #004 wires actual tools into the registry.
        // 🆕 Story #024 — read the shared registry's sorted snapshot every turn.
        // A null registry (legacy 1-arg ctor) collapses to an empty list, matching
        // the pre-#024 behavior so existing tests stay green.
        List<ToolSpec> toolSpecs = toolRegistry == null
            ? Collections.<ToolSpec>emptyList()
            : toolRegistry.modelVisibleSpecs();

        // ── ModelHints ──────────────────────────────────────────────
        ModelHints hints = new ModelHints(
            cfg.getLlm().getModel(),
            cfg.getLlm().getTemperature(),
            cfg.getLlm().getMaxTokens());

        return Prompt.builder()
            .messages(messages)
            .tools(toolSpecs)
            .hints(hints)
            .build();
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static void appendIfPresent(StringBuilder sb, String line) {
        if (isBlank(line)) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(line);
    }

    private static String readInstructions(AgentConfig.Instructions ins) {
        if (ins.getFile() != null && java.nio.file.Files.exists(ins.getFile())) {
            try {
                return new String(java.nio.file.Files.readAllBytes(ins.getFile()),
                    java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                // fall through to inline
            }
        }
        return ins.getInline();
    }

    /**
     * Render {@code {{var}}} placeholders from {@code variables} map.
     *
     * <p>Story #002 v1 implementation (research.md D-01): uses {@link String#replace}
     * with literal {@code {{var}}} → {@code vars.get(var)}. Unknown placeholders are
     * left as literal text (no exception) so the LLM sees debuggable input.
     *
     * @param input  the template text (may be null → returned as-is)
     * @param vars   placeholder values (null or empty → no rendering)
     * @param engine {@code "mustache"} to apply; anything else (including null,
     *               {@code "none"}, {@code ""}) → passthrough
     * @return rendered text, or the original {@code input} if rendering is disabled
     */
    static String renderTemplate(String input, Map<String, String> vars, String engine) {
        if (input == null) return null;
        if (vars == null || vars.isEmpty()) return input;
        if (engine == null || !engine.equalsIgnoreCase("mustache")) return input;

        // LinkedHashMap iteration order = declaration order — prevents later
        // replacement from clobbering earlier placeholders if values contain {{...}}.
        String result = input;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey() == null) continue;
            String value = e.getValue() == null ? "" : e.getValue();
            result = result.replace("{{" + e.getKey() + "}}", value);
        }
        return result;
    }
}
