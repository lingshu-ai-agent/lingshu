package ai.lingshu.core.impl.skill;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * /commit Skill — built-in example: tells the Agent to generate a Conventional Commits
 * style commit message (dsh §6.4 L4380-4409).
 *
 * <p>Does not depend on a SKILL.md file — hard-coded via {@code @Component}. Suitable for
 * built-in commands that need access to Java APIs (git, lint, HTTP, etc.) and for
 * rapid-iteration prototype Skills before promoting to SKILL.md.
 *
 * <p><b>Two Skill registration paths (dsh §6.4):</b>
 * <pre>
 * ┌─────────────────┬───────────────────────────────────────────────────────────┐
 * │ SkillTool       │ {@code @Component implements Skill} (本例)                       │
 * ├─────────────────┼───────────────────────────────────────────────────────────┤
 * │ 来源 = SKILL.md │ 来源 = 代码 @Component                                     │
 * │ 热加载 = 支持   │ 热加载 = 不支持(改代码 → 重编译 → 重启)                    │
 * │ 适合 = 用户     │ 适合 = 内置命令(代码里 hardcode)/ 频繁迭代阶段 prototype    │
 * │ 配置 = yaml     │ 配置 = 无(yaml 只控制 SkillTool 加载路径)                  │
 * │ 推荐 = 生产态   │ 推荐 = 开发态 prototype / 内置命令(本例 /commit)            │
 * └─────────────────┴───────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Bean name "commitSkill" (NOT "commit"):</b> the Spring container's bean ID is
 * decoupled from the Skill's {@code name()} (the LLM/CLI-visible identifier). Future
 * SKILL.md-derived Skills may also use {@code name() = "commit"} (Story #020b
 * {@code CompositeSkillLoader.putIfAbsent}); reserving the bare name for {@code name()}
 * avoids bean-name collisions with the file path version.
 *
 * <p><b>Selection guide:</b>
 * <ul>
 *   <li>Change Skill <i>behaviour</i> (text template, prompt wording) → use SKILL.md path
 *       (no recompile; hot-reloadable when paired with directory watcher).</li>
 *   <li>Change Skill <i>implementation</i> (call git / lint / HTTP API) → use {@code @Component}
 *       (this class) — Java code can call arbitrary APIs.</li>
 *   <li>Same {@code name()} exists in both paths → first-registered wins (Story #020b
 *       {@code CompositeSkillLoader.putIfAbsent}). {@code @Component} Skills register at
 *       Spring startup (Story #020a); SKILL.md Skills register later when the loader runs.</li>
 * </ul>
 */
@Component("commitSkill")
public class CommitSkill implements Skill {

    /**
     * Conventional Commits template body. Lives in code (not yml) because the structure
     * is part of the contract — the LLM cannot fall back to a sane commit if this changes.
     */
    private static final String TEMPLATE_BODY =
        "按 Conventional Commits 风格生成 commit message:\n"
        + "- 格式:<type>(<scope>): <subject>\n"
        + "- type:feat / fix / docs / refactor / test / chore\n"
        + "- subject 不超过 50 字符,祈使语气\n"
        + "- body 72 字符换行,说明 what + why(不写 how)";

    private final JsonNode inputSchema;

    public CommitSkill() {
        try {
            // Reuse SkillTool's canonical schema string — keeps SKILL.md-derived Skills
            // and @Component Skills schema-identical, so the same CLI /xxx invocation
            // works regardless of source.
            this.inputSchema = new ObjectMapper().readTree(SkillTool.FIXED_INPUT_SCHEMA_JSON);
        } catch (IOException e) {
            throw new IllegalStateException("commit skill schema invalid", e);
        }
    }

    @Override public String name() { return "commit"; }

    @Override public String description() {
        return "按 Conventional Commits 风格生成 commit message";
    }

    @Override public JsonNode inputSchema() { return inputSchema; }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        String diff = (input != null && input.hasNonNull("input"))
            ? input.get("input").asText() : "";
        String body = TEMPLATE_BODY
            + (diff.isEmpty() ? "" : "\n\nStaged diff:\n```\n" + diff + "\n```");
        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId(call.getId())
            .content(body)
            .isError(false)
            .build();
    }
}
