package ai.lingshu.core.impl.prompt;

import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.ModelHints;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.PromptBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default 5-segment prompt builder (dsh §4.5.1) — Story #001 minimum viable.
 *
 * <p>Assembles a single system message from (in order):
 * <ol>
 *   <li>{@code [ROLE]} — identity.name + role + traits + tone + language</li>
 *   <li>{@code [INSTRUCTIONS]} — file or inline (mustache rendering skipped in v1)</li>
 *   <li>{@code [PROJECT MEMORY]} — {@code ./CLAUDE.md} + {@code ~/.lingshu/CLAUDE.md} + extras</li>
 *   <li>{@code [CONVERSATION HISTORY]} — session history (read-only view of prior messages)</li>
 *   <li>{@code [USER MESSAGE]} — the current turn's input</li>
 * </ol>
 *
 * <p>Empty segments are skipped silently — the system message stays compact.
 * Tool schemas are returned in the {@link Prompt#tools} field (not stuffed into the
 * system text) per dsh §4.5.1 v1.5.13.
 */
public class DefaultPromptBuilder implements PromptBuilder {

    @Override
    public Prompt build(TurnContext ctx) {
        AgentConfig cfg = ctx.config();
        AgentConfig.Identity id = cfg.getIdentity() != null ? cfg.getIdentity() : AgentConfig.Identity.defaults();
        AgentConfig.Instructions ins = cfg.getInstructions() != null ? cfg.getInstructions() : AgentConfig.Instructions.empty();
        AgentConfig.Memory mem = cfg.getMemory() != null ? cfg.getMemory() : AgentConfig.Memory.defaults();

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
            if (sys.length() > 0) sys.append("\n\n");
            sys.append(insText);
        }

        // ── [PROJECT MEMORY] ────────────────────────────────────────
        if (mem.getClaudeMd() != null && mem.getClaudeMd().isEnabled()) {
            appendFileIfExists(sys, mem.getClaudeMd().getProject());
            appendFileIfExists(sys, mem.getClaudeMd().getUser());
        }
        if (mem.getExtras() != null) {
            for (String extraPath : mem.getExtras()) {
                appendFileIfExists(sys, Paths.get(extraPath));
            }
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
        // Story #001 demo path: no tools registered yet, so this stays empty.
        // Story #002 / #004 wire actual tools into the registry.
        List<ToolSpec> toolSpecs = Collections.emptyList();

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

    private static void appendFileIfExists(StringBuilder sb, Path p) {
        if (p == null) return;
        try {
            if (!Files.exists(p)) return;
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (isBlank(content)) return;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(content);
        } catch (IOException ex) {
            // Silent skip — missing / unreadable memory files don't break the build.
        }
    }

    private static String readInstructions(AgentConfig.Instructions ins) {
        if (ins.getFile() != null && Files.exists(ins.getFile())) {
            try {
                return new String(Files.readAllBytes(ins.getFile()), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                // fall through to inline
            }
        }
        return ins.getInline();
    }
}