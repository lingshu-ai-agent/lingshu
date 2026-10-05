package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DefaultSession#fork(String)} + {@link DefaultSession#checkpoint()}
 * sub-agent metadata tagging contract.
 *
 * <p>Background: the previous implementation unconditionally wrote
 * {@code meta.put("subagent", "")} on every checkpoint — a dead-code marker that
 * (a) was unread by any consumer and (b) made parent and child sessions
 * indistinguishable. The new contract stamps the actual {@code SubAgentType.configKey()}
 * (e.g. {@code "explore"} / {@code "engineer"} / {@code "reviewer"}) on forked
 * sessions only; top-level sessions produce a clean metadata map with no
 * {@code "subagent"} key at all.
 */
@DisplayName("DefaultSession — fork() + checkpoint() sub-agent metadata")
class DefaultSessionTest {

    // ── AC-DS-1: top-level checkpoint metadata is clean (no dead "subagent" key) ──

    @Test
    @DisplayName("AC-DS-1: topLevel_checkpoint_doesNotEmitDeadSubagentKey")
    void topLevel_checkpoint_doesNotEmitDeadSubagentKey() {
        DefaultSession parent = new DefaultSession("parent-1");
        parent.append(user("hi"));

        Checkpoint cp = parent.checkpoint();

        // The metadata must NOT contain the "subagent" key — it was meaningless
        // before and there is still no reader, so we omit it entirely on parent
        // checkpoints to keep the contract honest.
        assertThat(cp.getMetadata()).doesNotContainKey("subagent");
    }

    // ── AC-DS-2: fork stamps the actual SubAgentType configKey on the child ──

    @Test
    @DisplayName("AC-DS-2: fork_explore_propagatesConfigKey_intoCheckpointMetadata")
    void fork_explore_propagatesConfigKey_intoCheckpointMetadata() {
        DefaultSession parent = new DefaultSession("parent-2");
        parent.append(user("hello"));

        // Mirrors SubAgentType.EXPLORE.configKey()
        DefaultSession child = (DefaultSession) parent.fork("explore");

        // child id encodes the lineage
        assertThat(child.id()).isEqualTo("parent-2:explore");
        // checkpoint metadata stamps the same value under the "subagent" key
        assertThat(child.checkpoint().getMetadata())
            .containsEntry("subagent", "explore");
    }

    // ── AC-DS-3: nested fork — grandchild gets its OWN type, not parent's ──

    @Test
    @DisplayName("AC-DS-3: nestedFork_grandchildType_overridesParentType")
    void nestedFork_grandchildType_overridesParentType() {
        // parent → child(explore) → grandchild(engineer)
        DefaultSession parent = new DefaultSession("p");
        DefaultSession child = (DefaultSession) parent.fork("explore");
        DefaultSession grandchild = (DefaultSession) child.fork("engineer");

        // grandchild id reflects the full lineage
        assertThat(grandchild.id()).isEqualTo("p:explore:engineer");

        // grandchild metadata is tagged with the grandchild's own type, NOT
        // inherited from the parent's "explore" — each fork carries the role
        // it was forked FOR, not the role of its parent.
        assertThat(grandchild.checkpoint().getMetadata())
            .containsEntry("subagent", "engineer");

        // The intermediate child still reports its own type
        assertThat(child.checkpoint().getMetadata())
            .containsEntry("subagent", "explore");
    }

    // ── AC-DS-4: fork shares history snapshot but appending to child does not
    //             leak into parent (and vice versa) ──

    @Test
    @DisplayName("AC-DS-4: fork_sharesHistorySnapshot_butAppendsAreIsolated")
    void fork_sharesHistorySnapshot_butAppendsAreIsolated() {
        DefaultSession parent = new DefaultSession("p4");
        parent.append(user("p1"));
        parent.append(user("p2"));

        DefaultSession child = (DefaultSession) parent.fork("reviewer");
        // child inherits history at fork time
        assertThat(child.history()).hasSize(2);

        // append to child does NOT leak to parent
        child.append(user("c-only"));
        assertThat(child.history()).hasSize(3);
        assertThat(parent.history()).hasSize(2);

        // append to parent does NOT leak to child (copy-on-write, not live-share)
        parent.append(user("p3"));
        assertThat(parent.history()).hasSize(3);
        // child still has the snapshot it took at fork time — "c-only" only
        assertThat(child.history()).hasSize(3);
    }

    // ── AC-DS-5: parent's checkpoint stays clean even after fork creates children ──

    @Test
    @DisplayName("AC-DS-5: parentCheckpoint_neverGainsSubagentKey_evenAfterChildrenForked")
    void parentCheckpoint_neverGainsSubagentKey_evenAfterChildrenForked() {
        DefaultSession parent = new DefaultSession("p5");
        parent.append(user("seed"));

        // Fork several children of various types — parent's state must not change
        parent.fork("explore");
        parent.fork("engineer");
        parent.fork("reviewer");

        // Parent's own checkpoint must still have NO "subagent" key
        assertThat(parent.checkpoint().getMetadata()).doesNotContainKey("subagent");
        // And the parent id is unchanged
        assertThat(parent.id()).isEqualTo("p5");
    }

    // ── AC-DS-6: default no-arg ctor also produces clean metadata ──

    @Test
    @DisplayName("AC-DS-6: noArgCtor_producesCleanCheckpointMetadata")
    void noArgCtor_producesCleanCheckpointMetadata() {
        DefaultSession s = new DefaultSession(); // UUID id
        s.append(user("hi"));

        Checkpoint cp = s.checkpoint();
        assertThat(cp.getMetadata()).doesNotContainKey("subagent");
        // The id is a non-blank UUID (sanity check that the default ctor actually ran)
        assertThat(s.id()).isNotBlank();
    }

    // ── AC-DS-7: 4-arg ctor lets callers stamp an explicit subagentType
    //             without going through fork() ──

    @Test
    @DisplayName("AC-DS-7: fourArgCtor_stampsExplicitSubagentType_evenWithoutForking")
    void fourArgCtor_stampsExplicitSubagentType_evenWithoutForking() {
        // The full-control ctor is the seam that fork() uses internally; verify it
        // works in isolation so callers (e.g. future DelegateTool session wiring)
        // can stamp the role directly without going through fork().
        DefaultSession s = new DefaultSession("direct", Collections.<Message>emptyList(), "explore");

        assertThat(s.id()).isEqualTo("direct");
        assertThat(s.checkpoint().getMetadata()).containsEntry("subagent", "explore");
    }

    // ── helpers ──

    /** Local factory — keeps the test free of Lombok-generated Message.User constructor noise. */
    private static Message user(String content) {
        return new Message.User(content);
    }
}