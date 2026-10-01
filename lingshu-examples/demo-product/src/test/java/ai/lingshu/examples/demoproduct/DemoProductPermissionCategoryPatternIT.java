package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.permission.StrictPermissionPolicy;
import ai.lingshu.core.impl.permission.StrictPermissionPolicyProvider;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #031 — L3 blackbox IT verifying that {@code <category>:*} patterns
 * filter Tools by their {@link Tool#sourceCategory()} value (AC-NN-8).
 *
 * <p>Boots the full {@link DemoProductApplication} Spring context with a
 * {@code @TestPropertySource} override of {@code agent.tools.allow-list}
 * (the production yml uses wildcard {@code "*"}; this test exercises the
 * category-prefix form). Other yml fields (skills, mcp, a2a, delegate,
 * etc.) are inherited as-is from {@code application.yml}.
 *
 * <p><b>Why we register fake Tool beans via {@link TestToolsConfig}</b> —
 * the category-pattern decision path consults the {@code nameToCategory}
 * map populated by {@code StrictPermissionPolicyProvider.create()} from
 * {@code ToolRegistry.findAll()}. For three test cases ({@code echo → mcp},
 * {@code remote_agent → a2a}, {@code Task → delegate}), the corresponding
 * real Tool beans are registered only <em>after</em> an async connection
 * is established:
 * <ul>
 *   <li>{@code echo} — registered by {@code McpTransport}'s
 *       {@code onConnectionStateChange(CONNECTED → register)} hook, which
 *       fires only after the stdio subprocess answers the MCP handshake.</li>
 *   <li>{@code remote_agent} — registered by
 *       {@code RemoteAgentToolAutoConfiguration}'s {@code SmartLifecycle},
 *       wired at startup but the bean is always present (no async gating).
 *       However {@code AgentCard} fetch may fail in a test env without
 *       a real A2A server, so its presence in the registry is more
 *       deterministic than MCP but still not 100% guaranteed.</li>
 *   <li>{@code Task} — registered by {@code DelegateAutoConfiguration}
 *       (Spring {@code InitializingBean} @eager), so it IS present at
 *       startup. But its constructor requires 3 sub-agent configs wired
 *       via {@code delegate.types}, and any error there would crash the
 *       whole context.</li>
 * </ul>
 *
 * <p>To keep the test deterministic and self-contained (no flake from
 * missing MCP servers, no dependence on the demo-product-a2a-server
 * companion module, no risk of DelegateConfig validation failure),
 * {@link TestToolsConfig} registers three lightweight stub Tool beans
 * with explicit {@code sourceCategory()} overrides. These beans are
 * discovered by Spring's {@code @ComponentScan} (via
 * {@link TestConfiguration} → {@code @Bean}), get registered into the
 * {@code DefaultToolRegistry} at startup, and are visible to
 * {@code ToolRegistry.findAll()} when {@code StrictPermissionPolicyProvider.create}
 * builds its {@code nameToCategory} map.
 *
 * <p>Six inline cases:
 * <ol>
 *   <li>{@code echo} → matches {@code mcp:*} → Allow</li>
 *   <li>{@code agent} → matches {@code skill:*} → Allow (real Skill from
 *       {@code /skills/agent/SKILL.md})</li>
 *   <li>{@code read_file} → matches {@code read_file} exact-name form →
 *       Allow (real local @Component Tool)</li>
 *   <li>{@code write_file} → not in allow-list, category=local → Deny</li>
 *   <li>{@code remote_agent} → not in allow-list, category=a2a → Deny</li>
 *   <li>{@code Task} → not in allow-list, category=delegate → Deny</li>
 * </ol>
 */
@SpringBootTest(
    classes = DemoProductApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(DemoProductPermissionCategoryPatternIT.TestToolsConfig.class)
@TestPropertySource(properties = {
    // Replace the production yml's wildcard with category-prefix patterns.
    // `agent.tools.allow-list[0]` would mean "first entry of the existing
    // list" — but Spring's @TestPropertySource REPLACES the yml list
    // entirely when set this way. List contents actually
    // (cleared via the empty `agent.tools.deny-list` for symmetry).
    "agent.tools.allow-list[0]=mcp:*",
    "agent.tools.allow-list[1]=skill:*",
    "agent.tools.allow-list[2]=read_file",
    "agent.tools.deny-list[0]=",
    // Disable MCP stdio subprocess spawn (python3 path may be unavailable
    // in CI). With no MCP servers McpTransport idles, McpToolAdapter is
    // not registered — but our TestToolsConfig echoes a fake `echo`
    // tool with category="mcp", so the category-pattern decision still
    // works regardless of MCP's connection state.
    "agent.mcp.servers="
})
@DisplayName("AC-031-8 demo-product: '<category>:*' + exact-name pattern filters correctly")
class DemoProductPermissionCategoryPatternIT {

    @Autowired
    private StrictPermissionPolicyProvider strictProvider;

    @Autowired
    private AgentConfig demoAgentConfig;

    /**
     * Resolve the configured permission-policy via the Provider — this
     * mirrors what {@code AgentFactory.create(cfg)} does at runtime:
     * call {@code StrictPermissionPolicyProvider.create(cfg)} and use
     * the returned {@link StrictPermissionPolicy}.
     */
    private PermissionPolicy policy() {
        return strictProvider.create(demoAgentConfig);
    }

    @Test
    @DisplayName("mcp:* + skill:* + read_file allow-list → 6 cases match design")
    void categoryPatterns_filterCorrectly() {
        PermissionPolicy policy = policy();

        // Sanity: the policy resolved from the demo yml is StrictPermissionPolicy
        // (otherwise AllowAll would silently pass all 6 cases).
        assertThat(policy)
            .as("PermissionPolicy must be StrictPermissionPolicy")
            .isInstanceOf(StrictPermissionPolicy.class);

        // Case 1: echo (category=mcp via stub) matches `mcp:*` → Allow
        Decision echo = policy.check(
            new ToolCall("call-e", "echo", NullNode.getInstance()), null);
        assertThat(echo)
            .as("echo is registered as category=mcp, must match `mcp:*`")
            .isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) echo).getReason()).contains("matches pattern 'mcp:*'");

        // Case 2: agent (category=skill via /skills/agent/SKILL.md) matches `skill:*` → Allow
        Decision agent = policy.check(
            new ToolCall("call-a", "agent", NullNode.getInstance()), null);
        assertThat(agent)
            .as("agent is a Skill from /skills/agent/SKILL.md, category=skill, must match `skill:*`")
            .isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) agent).getReason()).contains("matches pattern 'skill:*'");

        // Case 3: read_file (local @Component) matches exact-name form → Allow
        Decision readFile = policy.check(
            new ToolCall("call-r", "read_file", NullNode.getInstance()), null);
        assertThat(readFile)
            .as("read_file matches exact-name pattern `read_file`")
            .isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) readFile).getReason()).contains("matches pattern 'read_file'");

        // Case 4: write_file (local @Component) NOT in allow-list → Deny with category=local
        Decision writeFile = policy.check(
            new ToolCall("call-w", "write_file", NullNode.getInstance()), null);
        assertThat(writeFile)
            .as("write_file is local, not in [mcp:*, skill:*, read_file]")
            .isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) writeFile).getReason())
            .contains("not in allow-list")
            .contains("category=local");

        // Case 5: remote_agent (category=a2a via stub) NOT in allow-list → Deny with category=a2a
        Decision remote = policy.check(
            new ToolCall("call-ra", "remote_agent", NullNode.getInstance()), null);
        assertThat(remote)
            .as("remote_agent is a2a, not in [mcp:*, skill:*, read_file]")
            .isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) remote).getReason())
            .contains("not in allow-list")
            .contains("category=a2a");

        // Case 6: Task (category=delegate via stub) NOT in allow-list → Deny with category=delegate
        Decision task = policy.check(
            new ToolCall("call-t", "Task", NullNode.getInstance()), null);
        assertThat(task)
            .as("Task is delegate, not in [mcp:*, skill:*, read_file]")
            .isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) task).getReason())
            .contains("not in allow-list")
            .contains("category=delegate");
    }

    /**
     * Test-time {@code @TestConfiguration} supplying stub Tool beans for the
     * three categories whose real beans depend on async/remote startup.
     *
     * <p>Each stub is the minimum needed for {@code ToolRegistry.findAll()}
     * to surface them with the correct {@code sourceCategory()} override:
     * {@code name()} returns the canonical name, {@code sourceCategory()}
     * returns the category the test expects, and {@code execute()} returns
     * a trivial SUCCESS (never invoked by these tests — we only call
     * {@code PermissionPolicy.check()} which does not invoke the Tool).
     */
    @TestConfiguration
    static class TestToolsConfig {

        private static Tool stub(String toolName, String category) {
            return new Tool() {
                @Override public String name() { return toolName; }
                @Override public String description() { return "stub for " + toolName; }
                @Override public JsonNode inputSchema() {
                    ObjectNode s = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
                    s.put("type", "object");
                    return s;
                }
                @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
                    return ToolResult.builder()
                        .status(ToolResult.Status.SUCCESS)
                        .toolUseId(call.getId())
                        .content("stub")
                        .isError(false)
                        .build();
                }
                @Override public String sourceCategory() { return category; }
            };
        }

        /** Stub echoing an `echo` tool with category="mcp" — substitutes for
         * the MCP-stdio-server-derived McpToolAdapter when MCP subprocess
         * startup is suppressed in the test env. */
        @Bean(name = "echoStub")
        public Tool echoStub() {
            return stub("echo", "mcp");
        }

        /** Stub echoing a `remote_agent` tool with category="a2a" — substitutes
         * for the real RemoteAgentTool when no A2A server is reachable. */
        @Bean(name = "remoteAgentStub")
        public Tool remoteAgentStub() {
            return stub("remote_agent", "a2a");
        }

        /** Stub echoing a `Task` tool with category="delegate" — substitutes
         * for the real DelegateTool when DelegateAutoConfiguration's eager
         * validation can't run (3 sub-agent configs needed). */
        @Bean(name = "taskStub")
        public Tool taskStub() {
            return stub("Task", "delegate");
        }
    }
}