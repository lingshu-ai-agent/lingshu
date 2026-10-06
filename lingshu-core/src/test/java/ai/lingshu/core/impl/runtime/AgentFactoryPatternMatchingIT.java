/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.permission.StrictPermissionPolicy;
import ai.lingshu.core.impl.permission.StrictPermissionPolicyProvider;
import ai.lingshu.core.impl.skill.SkillTool;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.impl.mcp.McpTransport;
import ai.lingshu.core.mcp.McpToolAdapter;
import ai.lingshu.core.mcp.McpToolDescriptor;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #031 — L2 slice test for {@link StrictPermissionPolicyProvider#create(AgentConfig)}
 * wiring the {@code name → sourceCategory} map from a {@link DefaultToolRegistry}.
 *
 * <p>Verifies end-to-end pattern matching:
 * <ul>
 *   <li>Provider populates nameToCategory with the correct category for each registered Tool</li>
 *   <li>{@link StrictPermissionPolicy#check} then matches {@code "mcp:*"}, {@code "skill:*"},
 *       {@code "*"} patterns using the populated map</li>
 *   <li>Demo product scenarios: 6 representative Tools (2 mcp + 2 skill + 1 local + 1 delegate)</li>
 * </ul>
 *
 * <p><b>Note</b>: This test bypasses Spring Boot DI entirely (per Story #023 precedent —
 * Mockito 5.x + JDK 23 inline mockmaker incompat). We use a real {@link DefaultToolRegistry}
 * (no mocking) so the registry's contract is exercised honestly. AgentConfig is constructed
 * via reflection on {@link AgentConfig#defaults()} — actually {@code AgentConfig.defaults()}
 * does not exist (only the nested classes have defaults()), so we use the
 * {@link AgentConfig.ToolsConfig#defaults()} for the tools portion and replace the
 * {@code tools} field on AgentConfig via reflection.
 */
class AgentFactoryPatternMatchingIT {

    private static ObjectNode objSchema() {
        ObjectNode s = new ObjectMapper().createObjectNode();
        s.put("type", "object");
        return s;
    }

    private static AgentConfig.ToolsConfig toolsAllowStar() {
        return new AgentConfig.ToolsConfig(
            true, Arrays.asList("*"), Collections.<String>emptyList(),
            Collections.<String>emptyList(),  // 🆕 Story #030 — askList
            200_000, 1_000_000);
    }

    private static AgentConfig.ToolsConfig toolsAllowMcpSkillsAndLocalRead() {
        return new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("mcp:*", "skill:*", "read_file"),
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),  // 🆕 Story #030 — askList
            200_000, 1_000_000);
    }

    /**
     * Reflectively swap the {@code tools} field on an AgentConfig instance.
     * AgentConfig is {@code @Value}-immutable (no setter, no builder), so the test
     * uses reflection to construct a config with a non-default ToolsConfig.
     */
    private static AgentConfig withTools(AgentConfig cfg, AgentConfig.ToolsConfig tools) {
        try {
            Field f = AgentConfig.class.getDeclaredField("tools");
            f.setAccessible(true);
            f.set(cfg, tools);
            return cfg;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set tools field via reflection", e);
        }
    }

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, objSchema());
    }

    private static Tool stubLocalTool(String toolName) {
        return new Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return toolName; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return objSchema(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
                return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content("ok").build();
            }
        };
    }

    private static Tool stubCategorizedTool(String toolName, String category) {
        return new Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return toolName; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return objSchema(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
                return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content("ok").build();
            }
            @Override public String sourceCategory() { return category; }
        };
    }

    private static ToolRegistry buildDemoRegistry() {
        ToolRegistry registry = new DefaultToolRegistry();
        // 2 MCP tools (registry override → category = "mcp").
        // Use simple namespacedName = "echo" / "timestamp" for test simplicity (in production
        // these are prefixed with the MCP server name, e.g. "echo-stdio:echo").
        McpToolDescriptor echoDesc = McpToolDescriptor.builder()
            .name("echo").description("echo").inputSchema(objSchema()).build();
        McpToolDescriptor tsDesc = McpToolDescriptor.builder()
            .name("timestamp").description("ts").inputSchema(objSchema()).build();
        registry.register(new McpToolAdapter(
            new McpTransport() {}, "echo-stdio", "echo", echoDesc));
        registry.register(new McpToolAdapter(
            new McpTransport() {}, "echo-stdio", "timestamp", tsDesc));
        // 2 Skill tools (SkillTool override → category = "skill")
        registry.register(SkillTool.fromMarkdown("agent", "# Agent\nAgent skill"));
        registry.register(SkillTool.fromMarkdown("help", "# Help\nHelp skill"));
        // 1 local tool (read_file) — default category "local"
        registry.register(stubLocalTool("read_file"));
        // 1 delegate tool (Task) — categorized as "delegate" via stub override
        registry.register(stubCategorizedTool("Task", "delegate"));
        return registry;
    }

    @Test
    @DisplayName("AC-031-6: allow-list='*' allows every registered Tool via Provider")
    void wildcardAllowsEveryRegisteredTool() {
        ToolRegistry registry = buildDemoRegistry();
        StrictPermissionPolicyProvider provider = new StrictPermissionPolicyProvider(registry);
        AgentConfig cfg = withTools(blankConfig(), toolsAllowStar());
        PermissionPolicy policy = provider.create(cfg);

        // All 6 registered Tools are Allowed under "*"
        assertThat(policy.check(call("echo"), null)).isInstanceOf(Decision.Allow.class);
        assertThat(policy.check(call("timestamp"), null)).isInstanceOf(Decision.Allow.class);
        assertThat(policy.check(call("agent"), null)).isInstanceOf(Decision.Allow.class);
        assertThat(policy.check(call("help"), null)).isInstanceOf(Decision.Allow.class);
        assertThat(policy.check(call("read_file"), null)).isInstanceOf(Decision.Allow.class);
        assertThat(policy.check(call("Task"), null)).isInstanceOf(Decision.Allow.class);
        // "*" matches every tool (including unregistered ones) — by design. This is
        // the wildcard semantic: a single "*" entry in the allow-list whitelists all tools.
        assertThat(policy.check(call("any_random_tool"), null)).isInstanceOf(Decision.Allow.class);
    }

    @Test
    @DisplayName("AC-031-6b: mcp:* + skill:* + read_file allow-lists filter by category")
    void categoryPatterns_filterCorrectly() {
        ToolRegistry registry = buildDemoRegistry();
        StrictPermissionPolicyProvider provider = new StrictPermissionPolicyProvider(registry);
        AgentConfig cfg = withTools(blankConfig(), toolsAllowMcpSkillsAndLocalRead());
        PermissionPolicy policy = provider.create(cfg);

        // mcp:* → echo is Allowed
        assertThat(policy.check(call("echo"), null)).isInstanceOf(Decision.Allow.class);
        // skill:* → agent is Allowed
        assertThat(policy.check(call("agent"), null)).isInstanceOf(Decision.Allow.class);
        // read_file (exact-name) → Allowed
        assertThat(policy.check(call("read_file"), null)).isInstanceOf(Decision.Allow.class);
        // delegate Task → not in any pattern → Denied
        assertThat(policy.check(call("Task"), null)).isInstanceOf(Decision.Deny.class);
        // The "not in allow-list" reason should mention "category=delegate"
        Decision denied = policy.check(call("Task"), null);
        assertThat(((Decision.Deny) denied).getReason()).contains("category=delegate");
    }

    /**
     * Build a "blank" AgentConfig (all-null fields) for testing. Only the {@code tools}
     * field is meaningful; everything else is unused by {@link StrictPermissionPolicyProvider#create}.
     * Field order matches {@link AgentConfig} declaration: flowEngine, llm, prompt, toolExecutor,
     * sandbox, compactor, sessionStore, delegate, mcp, skills, toolParallelism, toolTimeoutSeconds,
     * approvalTimeoutSeconds, turnTimeoutSeconds, llmTimeoutSeconds, reactMaxSteps, identity,
     * instructions, memory, a2aTransport, tenants, a2a, compactorConfig, tools, permissionPolicy.
     */
    private static AgentConfig blankConfig() {
        return new AgentConfig(
            null, null, null, null, null, null, null, null, null, null,  // 1-10
            0, 0, 0, 0, 0, 0,                                            // 11-16
                        null, null, null, null, null, null, null, null, "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }
}