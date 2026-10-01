package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.permission.StrictPermissionPolicy;
import ai.lingshu.core.impl.permission.StrictPermissionPolicyProvider;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #031 — L3 blackbox IT verifying the demo-product yml's wildcard
 * {@code tools.allow-list: ["*"]} pattern allows every Tool regardless of
 * source category (AC-NN-7).
 *
 * <p>Boots the full {@link DemoProductApplication} Spring context so the
 * entire wiring tree — {@code @Component} Tools (ProductTools /
 * ProductAgentTools) + {@code SkillAutoConfiguration} (classpath SKILL.md →
 * SkillTool.fromMarkdown) + {@code AgentToolScanner} (@AgentTool reflection)
 * + {@code PermissionPolicyAutoConfiguration#strictPermissionPolicyProvider()}
 * — runs exactly as in production.
 *
 * <p>The 12 representative Tool names below cover all five reserved source
 * categories:
 * <ul>
 *   <li><b>local</b> ({@code read_file}, {@code write_file}, {@code list_dir},
 *       {@code bash_safe}) — registered by {@link ProductTools} as
 *       {@code @Component implements Tool}, default category {@code "local"}.</li>
 *   <li><b>local via @AgentTool</b> ({@code time}, {@code calc}, {@code random},
 *       {@code uuid}) — registered by {@code AgentToolScanner} via
 *       {@code @AgentTool}-annotated methods on {@link ProductAgentTools},
 *       default category {@code "local"}.</li>
 *   <li><b>skill</b> ({@code agent}, {@code help}, {@code clear},
 *       {@code compact}) — registered by {@code SkillAutoConfiguration}
 *       from {@code src/main/resources/skills/*.md} via
 *       {@code SkillTool.fromMarkdown()}, category {@code "skill"}.</li>
 *   <li><b>mcp / a2a / delegate</b> — omitted here because their Tool beans
 *       depend on async connection establishment (MCP stdio spawn / A2A
 *       AgentCard fetch / Delegate sub-agent init), which is not
 *       deterministic in a blackbox context test. The wildcard pattern
 *       {@code "*"} allows them regardless of whether they appear in
 *       {@code nameToCategory}, so the 12 cases above are sufficient to
 *       pin down the contract.</li>
 * </ul>
 *
 * <p><b>What this test does NOT verify</b>: that {@code nameToCategory} is
 * populated with a non-empty map (that's {@code AgentFactoryPatternMatchingIT}
 * in lingshu-core). Here we trust the wiring and assert the externally
 * observable behaviour — every {@code Decision} is {@link Decision.Allow}.
 *
 * <p><b>Why {@code WebEnvironment.NONE}</b>: we don't need an HTTP server;
 * the {@code PermissionPolicy} bean is registered in the application context
 * regardless of web tier. Skipping Tomcat keeps the test fast.
 */
@SpringBootTest(
    classes = DemoProductApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("AC-031-7 demo-product: 'allow-list: [\"*\"]' allows every Tool")
class DemoProductPermissionWildcardIT {

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
    @DisplayName("StrictPermissionPolicy wired + every registered Tool is Allowed under '*'")
    void wildcardAllowsEveryRegisteredTool() {
        PermissionPolicy policy = policy();

        // Sanity: the StrictPermissionPolicyProvider wired into the application
        // context produces a StrictPermissionPolicy (not the AllowAll default).
        // This catches regressions where someone reverts PermissionPolicyAutoConfiguration
        // to the AllowAllProvider — the wildcard pattern in the yml would then
        // be vacuously Allow for a different reason.
        assertThat(policy)
            .as("PermissionPolicy resolved from Router should be StrictPermissionPolicy "
                + "given demo yml `permission-policy: strict`")
            .isInstanceOf(StrictPermissionPolicy.class);

        // 12 representative Tool names spanning local / @AgentTool / skill
        // categories. Wildcard '*' matches regardless of category, so all
        // 12 (and any other name) MUST be Allow.
        List<String> twelveTools = Arrays.asList(
            // 4 local @Component Tools (ProductTools.java)
            "read_file", "write_file", "list_dir", "bash_safe",
            // 4 @AgentTool methods (ProductAgentTools.java)
            "time", "calc", "random", "uuid",
            // 4 Skill from /skills/*.md (SkillAutoConfiguration)
            "agent", "help", "clear", "compact");

        for (String toolName : twelveTools) {
            Decision d = policy.check(
                new ToolCall("call-1", toolName, NullNode.getInstance()), null);
            assertThat(d)
                .as("wildcard '*' must allow Tool '%s'", toolName)
                .isInstanceOf(Decision.Allow.class);
        }

        // Wildcard also matches tools not in nameToCategory (any_random_tool,
        // echo, remote_agent, Task, etc.) — by design. This is the entire
        // point of the Story #031 wildcard form: one yml entry whitelists
        // ALL tools (current AND future — new MCP servers / Skills /
        // RemoteAgents added after this yml edit are auto-included).
        assertThat(policy.check(
                new ToolCall("call-2", "any_random_tool", NullNode.getInstance()), null))
            .isInstanceOf(Decision.Allow.class);
    }
}