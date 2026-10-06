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
package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #030 — L1/L2 tests for {@link AskUserPermissionPolicyProvider} (3 cases).
 *
 * <p>Verifies SPI metadata (name="ask", priority=10, version="1.0.0") and
 * {@code create()} wiring the {@code name → sourceCategory} map.
 */
class AskUserPermissionPolicyProviderTest {

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, NullNode.getInstance());
    }

    private static Tool stubCategorizedTool(String name, String category) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext c) {
                return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content("ok")
                    .build();
            }
            @Override public String sourceCategory() { return category; }
        };
    }

    private static AgentConfig.ToolsConfig toolsWithAsk() {
        return new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("*"),
            Collections.<String>emptyList(),
            Arrays.asList("bash_safe"),
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

    /**
     * Build a "blank" AgentConfig (all-null fields) for testing. Only the {@code tools}
     * field is meaningful; everything else is unused by {@link AskUserPermissionPolicyProvider#create}.
     * Field order matches {@link AgentConfig} declaration.
     */
    private static AgentConfig blankConfig() {
        return new AgentConfig(
            null, null, null, null, null, null, null, null, null, null,  // 1-10
            0, 0, 0, 0, 0, 0,                                            // 11-16
                        null, null, null, null, null, null, null, null, "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    @Test
    @DisplayName("AC-030-5: provider_spiMetadataIsStable")
    void provider_spiMetadataIsStable() {
        AskUserPermissionPolicyProvider provider = new AskUserPermissionPolicyProvider();

        assertThat(provider.name()).isEqualTo("ask");
        assertThat(provider.priority()).isEqualTo(10);
        assertThat(provider.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("AC-030-6: provider_create_wiresToolsAndCategoryMap")
    void provider_create_wiresToolsAndCategoryMap() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(stubCategorizedTool("bash_safe", "delegate"));
        AskUserPermissionPolicyProvider provider = new AskUserPermissionPolicyProvider(registry);

        AgentConfig cfg = withTools(blankConfig(), toolsWithAsk());
        PermissionPolicy policy = provider.create(cfg);

        Decision d = policy.check(call("bash_safe"), null);
        assertThat(d).isInstanceOf(Decision.AskUser.class);
        assertThat(((Decision.AskUser) d).getPrompt()).contains("bash_safe");
    }

    @Test
    @DisplayName("AC-030-7: provider_create_nullRegistry_usesDefaultCategory")
    void provider_create_nullRegistry_usesDefaultCategory() {
        AskUserPermissionPolicyProvider provider = new AskUserPermissionPolicyProvider(); // null registry

        AgentConfig cfg = withTools(blankConfig(), toolsWithAsk());
        PermissionPolicy policy = provider.create(cfg);

        // bash_safe has no registered category → falls back to "local" but ask-list
        // pattern "bash_safe" is exact-name match so still hits.
        Decision d = policy.check(call("bash_safe"), null);
        assertThat(d).isInstanceOf(Decision.AskUser.class);
    }
}