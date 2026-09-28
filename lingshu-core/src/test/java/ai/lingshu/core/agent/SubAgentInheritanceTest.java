package ai.lingshu.core.agent;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentConfig.ClaudeMd;
import ai.lingshu.core.runtime.AgentConfig.Identity;
import ai.lingshu.core.runtime.AgentConfig.Instructions;
import ai.lingshu.core.runtime.AgentConfig.Memory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #023 — L1 Unit tests for {@link SubAgentInheritance} (dsh v1.5.40 §6.6.1 L5131-5146).
 *
 * <p>Eight cases covering the field-level merge semantics:
 * <ul>
 *   <li>Identity — full replace when child has own, name suffix when inheriting</li>
 *   <li>Identity — each of three {@link SubAgentType} values appends its configKey</li>
 *   <li>Identity — parent-null fallback to {@link Identity#defaults()} (no suffix)</li>
 *   <li>Instructions — full replace when child has own</li>
 *   <li>Instructions — inherit parent verbatim</li>
 *   <li>Instructions — parent-null fallback to {@link Instructions#empty()}</li>
 *   <li>Memory — full replace when child has own</li>
 *   <li>Identity / Instructions / Memory trio — null + null + null yields defaults</li>
 * </ul>
 */
class SubAgentInheritanceTest {

    // ── Test fixtures — minimal AgentConfig builders (mirrors TestAgentConfigs.baseline) ─

    private static AgentConfig.Identity makeIdentity(String name) {
        return new AgentConfig.Identity(name, "engineer", "en",
            Arrays.asList("curious"), "neutral", null);
    }

    private static AgentConfig.Instructions makeInstructions() {
        return new AgentConfig.Instructions(Paths.get("/tmp/instructions.md"),
            null, "none", Collections.<String, String>emptyMap());
    }

    private static AgentConfig.Memory makeMemory() {
        return new AgentConfig.Memory(
            new AgentConfig.ClaudeMd(true, Paths.get("./CLAUDE.md"), Paths.get("/user/CLAUDE.md")),
            Collections.<String>emptyList());
    }

    /** Minimal parent AgentConfig with a populated Identity (so suffix logic is observable). */
    private static AgentConfig makeParentWithIdentity() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "claude-3-5-sonnet-latest", 8192, 0.7),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), 5),
            "default",
            new AgentConfig.Sandbox("strict", "chroot", Paths.get("/tmp"),
                Arrays.asList("ls", "cat"), Collections.<String>emptyList()),
            null, null, null, null, null,
            8, 30, 60, 120, 30, 50,
            makeIdentity("parent-agent"),
            makeInstructions(),
            makeMemory(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults()
        );
    }

    /** Minimal parent AgentConfig with null Identity (forces defaults() fallback). */
    private static AgentConfig makeParentWithNullIdentity() {
        AgentConfig baseline = makeParentWithIdentity();
        // Reconstruct with null identity to exercise the defaults-fallback branch.
        return new AgentConfig(
            baseline.getFlowEngine(), baseline.getLlm(), baseline.getPrompt(),
            baseline.getToolExecutor(), baseline.getSandbox(),
            baseline.getCompactor(), baseline.getSessionStore(),
            baseline.getDelegate(), baseline.getMcp(), baseline.getSkills(),
            baseline.getToolParallelism(), baseline.getToolTimeoutSeconds(),
            baseline.getApprovalTimeoutSeconds(), baseline.getTurnTimeoutSeconds(),
            baseline.getLlmTimeoutSeconds(), baseline.getReactMaxSteps(),
            null, // <-- null identity
            baseline.getInstructions(), baseline.getMemory(),
            baseline.getA2aTransport(), baseline.getTenants(), baseline.getA2a(),
            baseline.getCompactorConfig(), baseline.getTools()
        );
    }

    /** Empty-child skeleton (everything null/0). Used to isolate "inherit from parent" branches. */
    private static AgentConfig makeEmptyChild() {
        return new AgentConfig(
            null, null, null, null, null,
            null, null, null, null, null,
            0, 0, 0, 0, 0, 0,
            null, null, null,
            null, null, null,
            null, null
        );
    }

    private static AgentConfig makeChildWith(Identity id, Instructions inst, Memory mem) {
        return new AgentConfig(
            null, null, null, null, null,
            null, null, null, null, null,
            0, 0, 0, 0, 0, 0,
            id, inst, mem,
            null, null, null,
            null, null
        );
    }

    // ── Identity merge ───────────────────────────────────────────────────

    @Test
    @DisplayName("AC-023-SI-1: identity_fullReplaceWhenChildHasOwn")
    void identity_fullReplaceWhenChildHasOwn() {
        AgentConfig parent = makeParentWithIdentity();
        Identity childId = makeIdentity("child-only");
        AgentConfig child = makeChildWith(childId, null, null);

        AgentConfig merged = SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.EXPLORE);
        Identity mergedId = merged.getIdentity();

        // Child identity is used verbatim — no suffix appended
        assertThat(mergedId.getName()).isEqualTo("child-only");
        assertThat(mergedId.getRole()).isEqualTo("engineer");
        assertThat(mergedId.getLanguage()).isEqualTo("en");
    }

    @Test
    @DisplayName("AC-023-SI-2: identity_nameSuffix_whenInheritingFromParent")
    void identity_nameSuffix_whenInheritingFromParent() {
        AgentConfig parent = makeParentWithIdentity(); // name = "parent-agent"
        AgentConfig child = makeEmptyChild();

        AgentConfig merged = SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.ENGINEER);
        Identity mergedId = merged.getIdentity();

        // Parent's identity reused but name has sub-agent suffix
        assertThat(mergedId.getName()).isEqualTo("parent-agent (Sub-agent: engineer)");
        // Other 5 fields remain verbatim from parent
        assertThat(mergedId.getRole()).isEqualTo("engineer");
        assertThat(mergedId.getLanguage()).isEqualTo("en");
        assertThat(mergedId.getTraits()).containsExactly("curious");
        assertThat(mergedId.getTone()).isEqualTo("neutral");
        assertThat(mergedId.getAvatar()).isNull();
    }

    @Test
    @DisplayName("AC-023-SI-3: identity_eachSubAgentType_appendsItsOwnConfigKey")
    void identity_eachSubAgentType_appendsItsOwnConfigKey() {
        AgentConfig parent = makeParentWithIdentity();
        for (SubAgentType t : SubAgentType.values()) {
            AgentConfig merged = SubAgentInheritance.inheritFromParent(
                parent, makeEmptyChild(), t);
            assertThat(merged.getIdentity().getName())
                .as("identity.name for type %s", t)
                .isEqualTo("parent-agent (Sub-agent: " + t.configKey() + ")");
        }
    }

    @Test
    @DisplayName("AC-023-SI-4: identity_parentNull_fallsBackToDefaults_withoutSuffix")
    void identity_parentNull_fallsBackToDefaults_withoutSuffix() {
        AgentConfig parent = makeParentWithNullIdentity();
        AgentConfig child = makeEmptyChild(); // child.identity == null

        AgentConfig merged = SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.REVIEWER);
        Identity mergedId = merged.getIdentity();

        // Identity.defaults() used; the default name has no sub-agent suffix
        assertThat(mergedId.getName()).isEqualTo("lingShu-agent");
        assertThat(mergedId.getName()).doesNotContain("(Sub-agent:");
    }

    // ── Instructions merge ────────────────────────────────────────────────

    @Test
    @DisplayName("AC-023-SI-5: instructions_fullReplaceWhenChildHasOwn")
    void instructions_fullReplaceWhenChildHasOwn() {
        AgentConfig parent = makeParentWithIdentity();
        Instructions childInst = new Instructions(
            Paths.get("/child/instructions.md"), null, "mustache",
            Collections.singletonMap("var", "child"));
        AgentConfig child = makeChildWith(null, childInst, null);

        AgentConfig merged = SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.EXPLORE);
        assertThat(merged.getInstructions()).isSameAs(childInst);
        assertThat(merged.getInstructions().getFile()).isEqualTo(Paths.get("/child/instructions.md"));
    }

    @Test
    @DisplayName("AC-023-SI-6: instructions_inheritParentVerbatim")
    void instructions_inheritParentVerbatim() {
        AgentConfig parent = makeParentWithIdentity(); // instructions at /tmp/instructions.md
        AgentConfig child = makeEmptyChild();

        AgentConfig merged = SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.ENGINEER);
        assertThat(merged.getInstructions()).isSameAs(parent.getInstructions());
        assertThat(merged.getInstructions().getFile()).isEqualTo(Paths.get("/tmp/instructions.md"));
    }

    // ── Memory merge ──────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-023-SI-7: memory_fullReplaceWhenChildHasOwn")
    void memory_fullReplaceWhenChildHasOwn() {
        AgentConfig parent = makeParentWithIdentity();
        Memory childMem = new Memory(
            new ClaudeMd(false, Paths.get("/child/CLAUDE.md"), Paths.get("/u/CLAUDE.md")),
            Collections.singletonList("conversation"));
        AgentConfig child = makeChildWith(null, null, childMem);

        AgentConfig merged = SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.REVIEWER);
        assertThat(merged.getMemory()).isSameAs(childMem);
    }

    @Test
    @DisplayName("AC-023-SI-8: trio_parentNull_fallsBackToDefaultsEmptyDefaults")
    void trio_parentNull_fallsBackToDefaultsEmptyDefaults() {
        // Parent with all three null AND child with all three null
        AgentConfig parent = makeParentWithIdentity();
        AgentConfig parentWithNulls = new AgentConfig(
            parent.getFlowEngine(), parent.getLlm(), parent.getPrompt(),
            parent.getToolExecutor(), parent.getSandbox(),
            null, null, null, null, null,
            parent.getToolParallelism(), parent.getToolTimeoutSeconds(),
            parent.getApprovalTimeoutSeconds(), parent.getTurnTimeoutSeconds(),
            parent.getLlmTimeoutSeconds(), parent.getReactMaxSteps(),
            null, null, null,
            null, null, parent.getA2a(),
            parent.getCompactorConfig(), parent.getTools()
        );
        AgentConfig childAllNull = makeEmptyChild();

        AgentConfig merged = SubAgentInheritance.inheritFromParent(
            parentWithNulls, childAllNull, SubAgentType.EXPLORE);

        // Defaults for the trio
        assertThat(merged.getIdentity()).isEqualTo(Identity.defaults());
        assertThat(merged.getInstructions()).isEqualTo(Instructions.empty());
        assertThat(merged.getMemory()).isEqualTo(Memory.defaults());
    }

    // ── Argument validation (boundary) ─────────────────────────────────────

    @Test
    @DisplayName("AC-023-SI-BND: null_parentOrChildOrType_throwsIAE")
    void null_parentOrChildOrType_throwsIAE() {
        AgentConfig parent = makeParentWithIdentity();
        AgentConfig child = makeEmptyChild();
        assertThatThrownBy(() -> SubAgentInheritance.inheritFromParent(null, child, SubAgentType.EXPLORE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubAgentInheritance.inheritFromParent(parent, null, SubAgentType.EXPLORE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubAgentInheritance.inheritFromParent(parent, child, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Helper for path assertions (unused at class-load; kept for diff-friendliness vs. Spec).
     */
    @SuppressWarnings("unused")
    private static Path tmpPath(String s) { return Paths.get(s); }
}
