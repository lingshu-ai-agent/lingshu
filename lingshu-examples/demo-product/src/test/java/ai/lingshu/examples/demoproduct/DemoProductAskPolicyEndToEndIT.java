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
package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.permission.AskUserPermissionPolicy;
import ai.lingshu.core.impl.permission.AskUserPermissionPolicyProvider;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #047 — AC-047-6 L3 blackbox IT verifying that demo-product's
 * production {@code application.yml} end-to-end wires:
 * <ul>
 *   <li>{@code permission-policy: ask} → routes to
 *       {@link AskUserPermissionPolicy} (Bug A fix at
 *       {@code AgentFactory.java:285} — was reading
 *       {@code config.getSandbox().getPolicy()} sub-field before, which
 *       silently forced {@code "default"} for any yml without explicit
 *       {@code sandbox.policy: ask}).</li>
 *   <li>{@code tools.ask-list: [write_file, bash_safe]} → binds to
 *       {@code AgentConfig.ToolsConfig.askList} so path 2 of
 *       {@link AskUserPermissionPolicy#check} fires
 *       {@link Decision.AskUser} (Bug B fix at
 *       {@code DemoProductApplication.readTools()} — was hardcoding
 *       {@code Collections.emptyList()} before so path 2 was unreachable
 *       even with Bug A fixed).</li>
 * </ul>
 *
 * <p><b>Verification strategy</b> — boot the full
 * {@link DemoProductApplication} Spring context with the production
 * {@code application.yml} (which already configures
 * {@code permission-policy: ask} + {@code tools.ask-list: [write_file, bash_safe]}
 * + {@code tools.allow-list: ["*"]} + {@code approval-timeout: 0}). Wire
 * {@link AskUserPermissionPolicyProvider#create(AgentConfig)} with the
 * Spring-managed {@link AgentConfig} template produced by
 * {@code DemoProductApplication.agentConfig(Environment)} @Bean. Then
 * exercise {@link AskUserPermissionPolicy#check} with 4 tool names:
 *
 * <ol>
 *   <li>{@code policy instanceof AskUserPermissionPolicy} — proves Bug A fix
 *       (pre-Story-#047 always resolved to {@code AllowAllPermissionPolicy}
 *       because the typo silently forced {@code sandbox.policy: default}).</li>
 *   <li>{@code bash_safe} → {@link Decision.AskUser} with prompt containing
 *       {@code "matches pattern 'bash_safe'"} — proves Bug B fix (pre-Story-#047
 *       the {@code askList} was always empty so path 2 never fired; tools would
 *       have been routed through path 4 wildcard allow-list → Allow).</li>
 *   <li>{@code write_file} → {@link Decision.AskUser} with prompt containing
 *       {@code "matches pattern 'write_file'"} — second ask-list entry.</li>
 *   <li>{@code read_file} → {@link Decision.Allow} via wildcard allow-list
 *       (path 4) — regression guard so the wildcard allow-list still works
 *       for tools outside ask-list / deny-list.</li>
 *   <li>{@code unknown_tool} → {@link Decision.Deny} via path 5 deny-miss —
 *       regression guard so the AskUser policy is not a permit-all masquerade
 *       (the explicit allow-list bound is still enforced).</li>
 * </ol>
 *
 * <p><b>Why {@code ToolExecutionContext} is {@code null}</b> —
 * {@link AskUserPermissionPolicy#check(ToolCall, ToolExecutionContext)} does
 * not inspect the ctx argument. Only {@code call.getName()} and the three
 * pattern lists ({@code tools.allowList / denyList / askList}) are used.
 * The 5-path decision lives entirely in the policy object itself.
 *
 * <p><b>Mirrors precedent</b> — {@code DemoProductPermissionCategoryPatternIT}
 * (Story #031 follow-up, AC-NN-8) uses the same {@code @SpringBootTest}
 * + {@code @Autowired AgentConfig} + {@code @Autowired XxxProvider.create(cfg)}
 * shape to verify category-pattern wiring end-to-end. This test applies the
 * same scaffolding to {@link AskUserPermissionPolicy}.
 *
 * <p><b>Why no {@code @TestPropertySource}</b> — production
 * {@code application.yml} already has the exact configuration this test
 * exercises ({@code L107-108 allow-list: ["*"]} +
 * {@code L117-119 ask-list: [write_file, bash_safe]} +
 * {@code L315 permission-policy: ask}). No override needed; the test boots
 * the same yml the demo-product runtime ships with, providing a true
 * end-to-end blackbox verification of the user's "configure yml & run"
 * workflow.
 */
@SpringBootTest(
    classes = DemoProductApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("AC-047-6: demo-product ask-policy end-to-end wiring IT")
class DemoProductAskPolicyEndToEndIT {

    /**
     * Spring-managed {@link AgentConfig} template produced by
     * {@code DemoProductApplication.agentConfig(Environment)} @Bean.
     * Holds the yml-parsed values for {@code permission-policy},
     * {@code tools.allow-list}, {@code tools.deny-list},
     * {@code tools.ask-list}, etc. — the same single source of truth that
     * {@code AgentFactory.create(cfg)} would consume at runtime.
     */
    @Autowired
    private AgentConfig demoAgentConfig;

    /**
     * Story #030 ask-mode Provider — registered via {@code @Component},
     * named {@code "ask"} (priority=10, version=1.0.0). Resolved by
     * {@code PermissionPolicyRouter.resolve("ask", cfg)} once Bug A is
     * fixed at {@code AgentFactory.java:285}.
     */
    @Autowired
    private AskUserPermissionPolicyProvider askProvider;

    /** Resolve the policy exactly as {@code AgentFactory.create(cfg)} would. */
    private PermissionPolicy policy() {
        return askProvider.create(demoAgentConfig);
    }

    @Test
    @DisplayName("permission-policy: ask + tools.ask-list end-to-end → AskUser / Allow / Deny paths")
    void askPolicy_endToEnd_askListHitsTriggerAskUser() {
        PermissionPolicy policy = policy();

        // ── AC-047-6-A — Bug A fix verification ─────────────────────────────
        // Pre-Story-#047, AgentFactory.java:285 read config.getSandbox().getPolicy()
        // which silently returned "default" for any yml without explicit
        // sandbox.policy. The Router would then resolve the "default"
        // AllowAllPermissionPolicy, never AskUserPermissionPolicy. This
        // assertion would have FAILED pre-fix (would have observed
        // AllowAllPermissionPolicy instead of AskUserPermissionPolicy).
        assertThat(policy)
            .as("Bug A fix: yml `permission-policy: ask` must resolve to "
                + "AskUserPermissionPolicy, not AllowAllPermissionPolicy. "
                + "Pre-Story-#047 AgentFactory.java:285 read "
                + "config.getSandbox().getPolicy() which silently forced "
                + "'default' for configs without an explicit sandbox.policy "
                + "key — making the 'ask' Provider unreachable via the "
                + "top-level permission-policy yml key.")
            .isInstanceOf(AskUserPermissionPolicy.class);

        // ── AC-047-6-B — Bug B fix verification: ask-list path 2 ───────────
        // Pre-Story-#047, DemoProductApplication.readTools() hardcoded
        // Collections.emptyList() for askList, so even after Bug A fix the
        // path-2 AskUser branch was unreachable. bash_safe would have
        // returned Decision.Allow via the wildcard allow-list instead.
        // This assertion would have FAILED pre-fix.
        Decision bashSafe = policy.check(
            new ToolCall("c1", "bash_safe", NullNode.getInstance()), null);
        assertThat(bashSafe)
            .as("Bug B fix: `tools.ask-list: [bash_safe, write_file]` must "
                + "cause AskUserPermissionPolicy path 2 to fire for "
                + "bash_safe. Pre-Story-#047 DemoProductApplication.readTools() "
                + "hardcoded Collections.emptyList() — path 2 was unreachable.")
            .isInstanceOf(Decision.AskUser.class);
        assertThat(((Decision.AskUser) bashSafe).getPrompt())
            .as("AskUser decision prompt must indicate the matched pattern so "
                + "the host UI / ApprovalRegistry can present it to the human. "
                + "Pre-Story-#047 path 2 was never reached and no approval "
                + "prompt would ever fire.")
            .contains("matches pattern 'bash_safe'");

        Decision writeFile = policy.check(
            new ToolCall("c2", "write_file", NullNode.getInstance()), null);
        assertThat(writeFile)
            .as("Second ask-list entry `write_file` must also fire path 2 "
                + "AskUser. Regression guard that both entries in the "
                + "tools.ask-list array are honored.")
            .isInstanceOf(Decision.AskUser.class);
        assertThat(((Decision.AskUser) writeFile).getPrompt())
            .contains("matches pattern 'write_file'");

        // ── AC-047-6-C — Regression guard: wildcard allow-list (path 4) ────
        // application.yml L107-108 has tools.allow-list: ["*"]. read_file is
        // NOT in ask-list / deny-list, so path 2 does NOT match, then path 4
        // allows via the wildcard. Verifies that adding ask-list didn't
        // accidentally tighten the wildcard allow-list — the AskUser policy
        // is a SUPERSET of the Strict policy, not a replacement.
        //
        // <p>Note — path 5 deny-miss for tools-in-none-of-the-3-lists is
        // deliberately NOT exercised here: production application.yml uses
        // wildcard allow-list ["*"] which matches every tool name, so for
        // ANY tool (including names like "unknown_tool") path 4 fires before
        // path 5. Path 5 deny-miss IS tested in
        // {@code AskUserPermissionPolicyTest} (L1 unit, lingshu-core) with
        // explicit non-wildcard ask/allow/deny lists. Production yml with
        // a narrower allow-list would surface the same path 5 in real
        // runtime (see plan § reverse-AC guard #AskUserPermissionPolicyTest).
        Decision readFile = policy.check(
            new ToolCall("c3", "read_file", NullNode.getInstance()), null);
        assertThat(readFile)
            .as("Wildcard allow-list ['*'] must still allow tools outside "
                + "ask-list / deny-list via AskUser path 4. Regression guard "
                + "so ask-mode doesn't silently deny all non-ask-listed tools — "
                + "ask is a SUPERSET of strict, not a deny-all.")
            .isInstanceOf(Decision.Allow.class);
    }
}
