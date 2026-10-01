package ai.lingshu.core.slot;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.spi.ContractVersionRef;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Slot 2 — A single callable tool. Stateless across calls except for any
 * internal resources the implementation opens in its constructor.
 *
 * <p>Three input-schema sources are valid and indistinguishable at this contract level
 * (dsh §4.6 + §6.5):
 * <ul>
 *   <li>Hand-written JSON (built-in Read / Write / Edit / Bash)</li>
 *   <li>MCP server {@code tools/list} (McpToolAdapter wraps it)</li>
 *   <li>Spring AI {@code @Tool} annotation reflection (schema generation only — execution
 *       still flows through {@link ToolExecutor}, see dsh §4.10.1 硬规则 2)</li>
 * </ul>
 *
 * <p>The {@link ToolExecutor} never calls {@code tool.execute()} directly; it dispatches through
 * the 5-step pipeline (permission → registry lookup → timeout → sandbox → execute → checkpoint).
 * Direct invocation is reserved for tests only.
 *
 * <p><b>🆕 Story #031 — {@link #sourceCategory()} default method</b> (zero-intrusion SPI extension).
 * Returns the source category of this {@link Tool} so {@link ai.lingshu.core.slot.PermissionPolicy}
 * implementations can apply category-prefix patterns (e.g. {@code "mcp:*"}, {@code "skill:*"})
 * when matching {@link AgentConfig.tools#getAllowList() allow-list} / {@link AgentConfig.tools#getDenyList()
 * deny-list} entries (dsh §5.5 + §4.7 + §15.4 P 段).
 *
 * <p><b>Built-in category namespace</b> (5 reserved strings):
 * <ul>
 *   <li>{@code "local"}    — hand-written {@code @Component} Tools (Read / Write / Edit / Bash / 4 demo-product
 *       local Tools / Spring AI {@code @AgentTool} adapter etc.)</li>
 *   <li>{@code "mcp"}      — {@link ai.lingshu.core.mcp.McpToolAdapter} (Story #021b)</li>
 *   <li>{@code "skill"}    — {@link Skill} typed tools, including {@link ai.lingshu.core.impl.skill.SkillTool}
 *       and {@code @Component implements Skill} (Story #020a/b/c)</li>
 *   <li>{@code "a2a"}      — {@link ai.lingshu.a2a.client.RemoteAgentTool} (Story #009c/d + #009e)</li>
 *   <li>{@code "delegate"} — {@link ai.lingshu.core.agent.DelegateTool} (Story #023)</li>
 * </ul>
 *
 * <p><b>Plugin author freedom:</b> plugin authors are free to use custom category strings
 * (e.g. {@code "rag"}, {@code "browser"}, {@code "git"}) — {@code StrictPermissionPolicy}
 * does not validate the string; the framework only checks String equality. Categories are
 * matched as exact strings; case-sensitive.
 *
 * <p><b>Back-compat:</b> default returns {@code "local"} so existing {@link Tool}
 * implementations (4 built-in + Spring AI adapters + custom user Tools) continue to work
 * without modification. New code may override to assign a more specific category.
 */
public interface Tool {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /** Unique tool name; the LLM sees this in {@code ToolSpec.name}. */
    String name();

    /** Human-readable description; surfaced to the model so it can decide when to call. */
    String description();

    /**
     * JSON Schema (draft 2020-12 / OpenAI function-calling compatible) describing the input shape.
     * Sourced however the implementation wants; returned as a parsed {@code JsonNode}.
     */
    JsonNode inputSchema();

    /**
     * Execute the call. The {@link ai.lingshu.core.slot.ToolExecutionContext} provides sandbox,
     * cancellation token, progress sink, and approval gate. Long-running tools should emit
     * progress events and respect cancellation.
     */
    ToolResult execute(ToolCall call, ToolExecutionContext ctx);

    /**
     * 🆕 Story #031 — Source category of this Tool, for category-prefix pattern matching in
     * {@link ai.lingshu.core.slot.PermissionPolicy} allow/deny lists. See class-level Javadoc
     * for the 5 built-in category strings + plugin-author freedom.
     *
     * <p>Default returns {@code "local"} for back-compat — existing {@link Tool} implementations
     * do not need to override. {@link McpToolAdapter} / {@link Skill} (and its subclasses) /
     * {@link RemoteAgentTool} / {@link DelegateTool} override to return their respective categories.
     *
     * @return a non-null, non-empty category string; case-sensitive
     */
    default String sourceCategory() {
        return "local";
    }
}