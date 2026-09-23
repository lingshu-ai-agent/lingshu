package ai.lingshu.core.impl.skill;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * Default {@link Skill} implementation — turns SKILL.md text into a callable Skill.
 *
 * <p><b>Two source paths (dsh §6.4):</b>
 * <ul>
 *   <li><b>SKILL.md path</b> — constructed via {@link #fromMarkdown(String, String)} by
 *       {@code ClasspathSkillSource} (Story #020b) or {@code DirectorySkillSource} (Story #020b).
 *       First line {@code # title} → {@code description}; remaining body → {@code content};
 *       {@code inputSchema} fixed at {@code { "input": string }}.</li>
 *   <li><b>@Component path</b> — concrete class implementing {@link Skill} directly
 *       (e.g. {@link CommitSkill}). No SKILL.md file dependency.</li>
 * </ul>
 *
 * <p><b>Why a fixed inputSchema:</b> the CLI {@code /xxx <arg>} convention and the
 * LLM FunctionCalling convention both expect a single string argument. Forcing
 * {@code {"input": string}} avoids per-Skill schema divergence and lets {@code CLI dispatcher}
 * (Story #020c) parse user input uniformly.
 *
 * <p><b>Why no Lombok {@code @Value}:</b> the constructor performs JSON parsing
 * ({@link IOException} → {@link IllegalStateException}) and {@link Objects#requireNonNull}
 * validation, neither of which fit Lombok's all-args-only style. Manual getters are
 * three lines; the trade-off is worth the explicit error contract.
 *
 * <p><b>Threading:</b> SkillTool is stateless and immutable after construction. {@link #execute}
 * is safe to call concurrently; the only mutable state is local to each call.
 */
public class SkillTool implements Skill {

    /**
     * Canonical input schema for SKILL.md-derived Skills — aligns with {@code /xxx <arg>}
     * CLI convention (Story #020c) and OpenAI/Anthropic FunctionCalling {@code {"input": string}}.
     * Consumed verbatim by {@link #fromMarkdown} and reused by {@link CommitSkill}.
     */
    static final String FIXED_INPUT_SCHEMA_JSON =
        "{ \"type\": \"object\", \"properties\": { \"input\": { \"type\": \"string\" } }, \"required\": [\"input\"] }";

    private final String name;
    private final String description;
    private final String content;
    private final JsonNode inputSchema;

    /**
     * Full constructor — used directly by {@code CompositeSkillLoader}-style callers that
     * already have the four pieces parsed. {@code description} defaults to {@code name} when
     * null, {@code content} defaults to {@code ""} when null.
     *
     * @throws IllegalStateException if {@code jsonSchema} is not parseable JSON
     */
    public SkillTool(String name, String description, String content, String jsonSchema) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? name : description;
        this.content = content == null ? "" : content;
        try {
            this.inputSchema = new ObjectMapper().readTree(jsonSchema);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid schema for skill " + name, e);
        }
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public JsonNode inputSchema() { return inputSchema; }

    /**
     * Run the Skill. Returns {@link ToolResult#success} with {@code content + optional User input}.
     *
     * <p>The dispatcher (CLI or {@code LinearTurnEngine}) is responsible for deciding what
     * to do with the result — the CLI layer wraps it as a User message and calls
     * {@code agent.continueWithUserMessage(result.getContent())} (Story #020c); the ReAct
     * engine feeds it back through the tool-result message channel.
     */
    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        String userInput = (input != null && input.hasNonNull("input"))
            ? input.get("input").asText() : "";
        String body = content
            + (userInput.isEmpty() ? "" : "\n\nUser input:\n" + userInput);
        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId(call.getId())
            .content(body)
            .isError(false)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Static factory: SKILL.md → SkillTool
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Construct a {@link Skill} from SKILL.md raw text. Called by
     * {@code ClasspathSkillSource.discover()} and {@code DirectorySkillSource.discover()}
     * (both Story #020b).
     *
     * <p><b>Parsing rules</b> (dsh §6.4 L4320-4327):
     * <ul>
     *   <li>{@code content} split on first line terminator ({@code \R} regex):
     *       <ul>
     *         <li>First line stripped of leading {@code #+ \s*} (one or more {@code #} then
     *             optional whitespace) and {@link String#trim()}'d → {@code description}.</li>
     *         <li>If the stripped first line is empty, {@code description} falls back to {@code name}.</li>
     *       </ul>
     *   </li>
     *   <li>Remainder (after first line) trimmed → {@code content}. If only one line,
     *       {@code content = ""}.</li>
     *   <li>{@code inputSchema} hardcoded to {@link #FIXED_INPUT_SCHEMA_JSON} (matches the
     *       CLI {@code /xxx <arg>} convention).</li>
     * </ul>
     *
     * @param name             the Skill name (typically the parent directory name of {@code SKILL.md})
     * @param markdownContent  full text of the {@code SKILL.md} file (UTF-8); may be empty
     * @return a fresh {@link SkillTool} instance
     */
    public static Skill fromMarkdown(String name, String markdownContent) {
        Objects.requireNonNull(name, "name");
        String body = markdownContent == null ? "" : markdownContent;
        String[] lines = body.split("\\R", 2);
        String first = lines[0].replaceFirst("^#+\\s*", "").trim();
        String description = first.isEmpty() ? name : first;
        String remaining = lines.length > 1 ? lines[1].trim() : "";
        return new SkillTool(name, description, remaining, FIXED_INPUT_SCHEMA_JSON);
    }
}
