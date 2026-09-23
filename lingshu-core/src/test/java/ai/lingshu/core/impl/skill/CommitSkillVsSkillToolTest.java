package ai.lingshu.core.impl.skill;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.slot.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020a — L2 coexistence test for {@link CommitSkill} vs {@link SkillTool}
 * under the {@link DefaultToolRegistry} first-wins contract.
 *
 * <p>EC-020a-3: when two Skills with the same {@code name()} are registered into the
 * same registry, {@code putIfAbsent} semantics apply — the first registration wins.
 * Subsequent attempts are ignored (with a WARN log) but the registry retains only one
 * entry. This is the contract {@code CompositeSkillLoader} (Story #020b) will rely on
 * when mixing {@code @Component} Skills and SKILL.md-derived Skills.
 */
class CommitSkillVsSkillToolTest {

    @Test
    @DisplayName("EC-020a-3: register CommitSkill first → SkillTool with same name is ignored")
    void commitSkillFirst_wins() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        CommitSkill commit = new CommitSkill();
        Skill toolFromMd = SkillTool.fromMarkdown("commit", "# test\nbody");

        r.register(commit);
        r.register(toolFromMd);

        assertThat(r.findByName("commit")).isSameAs(commit);
        assertThat(r.findSkill("commit")).isSameAs(commit);
        assertThat(r.skillNames()).containsExactly("commit");
        assertThat(r.names()).containsExactly("commit");
    }

    @Test
    @DisplayName("EC-020a-3: register SkillTool first → CommitSkill with same name is ignored")
    void skillToolFirst_wins() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        Skill toolFromMd = SkillTool.fromMarkdown("commit", "# test\nbody");
        CommitSkill commit = new CommitSkill();

        r.register(toolFromMd);
        r.register(commit);

        assertThat(r.findByName("commit")).isSameAs(toolFromMd);
        assertThat(r.findSkill("commit")).isSameAs(toolFromMd);
        assertThat(r.skillNames()).containsExactly("commit");
    }

    @Test
    @DisplayName("EC-020a-3: different names → both Skills visible")
    void differentNames_bothRegistered() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register(new CommitSkill());                                       // "commit"
        r.register(SkillTool.fromMarkdown("other", "# other\nbody"));        // "other"

        assertThat(r.skillNames()).containsExactlyInAnyOrder("commit", "other");
        assertThat(r.names()).containsExactlyInAnyOrder("commit", "other");
    }

    @Test
    @DisplayName("EC-020a-3: re-register the SAME instance → idempotent (no warn, same reference)")
    void sameInstanceReRegistered_idempotent() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        CommitSkill commit = new CommitSkill();
        r.register(commit);
        r.register(commit);    // same instance, putIfAbsent → no warning

        assertThat(r.findSkill("commit")).isSameAs(commit);
        assertThat(r.skillNames()).containsExactly("commit");
    }
}
