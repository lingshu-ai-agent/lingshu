package ai.lingshu.core.slot;

/**
 * Slot 4 marker — Skill is a Tool variant with extra discovery semantics, not a separate type.
 *
 * <p>Runtime behaviour is identical to {@link Tool}: the model can call it via FunctionCalling,
 * and {@code ToolExecutor.dispatch} runs the same 5-step pipeline. The distinction is purely
 * organizational — three concrete differences:
 * <ul>
 *   <li>SkillLoader auto-discovers them from configured sources (classpath SKILL.md files,
 *       directory scans, or hard-coded {@code @Component} beans — see dsh §6.4).</li>
 *   <li>CLI surfaces them under {@code /xxx} command syntax; the CLI layer intercepts and
 *       constructs a synthetic {@code ToolCall} (rather than waiting for the LLM).</li>
 *   <li>They appear in a second registry index (skillRegistry) for autocomplete / listing.</li>
 * </ul>
 *
 * <p>v1 leaves no additional methods — {@code Skill} is currently a marker interface.
 * Future extensions (user-level aliases {@code /c → commit}, permission-tier
 * "user-only-triggerable", danger-level hints feeding the approval gate) can hang off this
 * interface without breaking implementers that only need {@link Tool} semantics.
 */
public interface Skill extends Tool {
}