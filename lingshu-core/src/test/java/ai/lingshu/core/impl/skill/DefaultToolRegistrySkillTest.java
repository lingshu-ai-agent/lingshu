package ai.lingshu.core.impl.skill;

import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #020a — L2 behavior tests for {@link ai.lingshu.core.impl.tool.DefaultToolRegistry}
 * Skill double-index (dsh §6.4 L3980-4010).
 *
 * <p>Cover AC-020a-7 (skillsByName lookup / findSkill / findByName), AC-020a-8
 * (modelVisibleSpecs includes Skills + sorted), AC-020a-9 (findSkill null vs findByName
 * IAE), AC-020a-10 (ConcurrentHashMap thread-safe).
 */
class DefaultToolRegistrySkillTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────
    //  AC-020a-7: register populates both indices; lookup / findSkill / findByName
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-020a-7: register Skill → registry + skillsByName both populated")
    void register_skillPopulatesBothIndices() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        Skill commit = new CommitSkill();
        r.register(commit);

        // Skill appears in both indices
        assertThat(r.lookup("commit")).isSameAs(commit);
        assertThat(r.findSkill("commit")).isSameAs(commit);
        assertThat(r.findByName("commit")).isSameAs(commit);
        assertThat(r.skillNames()).containsExactly("commit");
        assertThat(r.names()).containsExactly("commit");
    }

    @Test
    @DisplayName("AC-020a-7: register plain Tool → registry only (no skillsByName)")
    void register_plainTool_onlyInRegistry() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        Tool plain = plainTool("plain", "a non-skill tool");
        r.register(plain);

        assertThat(r.lookup("plain")).isSameAs(plain);
        assertThat(r.findByName("plain")).isSameAs(plain);
        // Not a Skill → not in skillsByName
        assertThat(r.findSkill("plain")).isNull();
        assertThat(r.skillNames()).isEmpty();
        assertThat(r.names()).containsExactly("plain");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  AC-020a-8: modelVisibleSpecs includes Skills, sorted by name
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-020a-8: modelVisibleSpecs includes Skills and plain Tools, sorted by name")
    void modelVisibleSpecs_includesAllSortedByName() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        r.register(new CommitSkill());                                  // "commit"
        r.register(plainTool("zeta", "z-desc"));                       // "zeta"
        r.register(plainTool("alpha", "a-desc"));                      // "alpha"

        List<ToolSpec> specs = r.modelVisibleSpecs();
        assertThat(specs).hasSize(3);
        // Sorted by name: alpha < commit < zeta
        assertThat(specs.get(0).getName()).isEqualTo("alpha");
        assertThat(specs.get(1).getName()).isEqualTo("commit");
        assertThat(specs.get(2).getName()).isEqualTo("zeta");
        // Each spec has description + inputSchema non-null
        for (ToolSpec s : specs) {
            assertThat(s.getName()).isNotNull();
            assertThat(s.getDescription()).isNotNull();
            assertThat(s.getInputSchema()).isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  AC-020a-9: findSkill returns null; findByName throws IAE
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-020a-9: findSkill(unknown) returns null (no exception)")
    void findSkill_unknown_returnsNull() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        assertThat(r.findSkill("nope")).isNull();
    }

    @Test
    @DisplayName("AC-020a-9: findByName(unknown) throws IllegalArgumentException")
    void findByName_unknown_throwsIAE() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        assertThatThrownBy(() -> r.findByName("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown tool: nope");
    }

    @Test
    @DisplayName("AC-020a-9: lookup(unknown) returns null (existing contract, unchanged)")
    void lookup_unknown_returnsNull() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        assertThat(r.lookup("nope")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  AC-020a-10: ConcurrentHashMap thread-safety — duplicate registration
    //               from N threads yields first-wins + N-1 warnings
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-020a-10: concurrent register of same-named Skill → only first wins")
    void concurrent_register_sameName_firstWins() throws InterruptedException {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        int N = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        ExecutorService pool = Executors.newFixedThreadPool(N);
        try {
            for (int i = 0; i < N; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        r.register(new CommitSkill());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Only one entry should remain (first-wins)
        assertThat(r.skillNames()).containsExactly("commit");
        assertThat(r.names()).containsExactly("commit");
        assertThat(r.findSkill("commit")).isNotNull();
    }

    @Test
    @DisplayName("AC-020a-10: concurrent register of N distinct Skills → all N visible")
    void concurrent_register_distinctSkills_allVisible() throws InterruptedException {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        int N = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Throwable> errs = new ArrayList<>();
        try {
            for (int i = 0; i < N; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        Skill s = new SkillTool("skill-" + idx, "d", "c", SkillTool.FIXED_INPUT_SCHEMA_JSON);
                        r.register(s);
                    } catch (Throwable t) {
                        synchronized (errs) {
                            errs.add(t);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(errs).isEmpty();
        assertThat(r.skillNames()).hasSize(N);
        assertThat(r.names()).hasSize(N);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EC-020a-4: manually-newed SkillTool registers cleanly
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-020a-4: manual new SkillTool registers cleanly")
    void manual_skillTool_registers() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        Skill s = SkillTool.fromMarkdown("echo", "# echo back\nEcho the user input.");
        r.register(s);

        assertThat(r.findSkill("echo")).isSameAs(s);
        assertThat(r.skillNames()).containsExactly("echo");
    }

    @Test
    @DisplayName("EC-020a-4: register(null) throws IllegalArgumentException")
    void register_null_throws() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        assertThatThrownBy(() -> r.register(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tool must not be null");
    }

    @Test
    @DisplayName("skillNames() returns unmodifiable Set (defensive copy)")
    void skillNames_unmodifiable() {
        ai.lingshu.core.impl.tool.DefaultToolRegistry r = new ai.lingshu.core.impl.tool.DefaultToolRegistry();
        r.register(new CommitSkill());
        Set<String> names = r.skillNames();
        assertThatThrownBy(() -> names.add("rogue"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Tool plainTool(String name, String desc) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                try {
                    return new ObjectMapper().readTree(
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            @Override public ai.lingshu.core.message.ToolResult execute(
                    ai.lingshu.core.message.ToolCall call,
                    ai.lingshu.core.slot.ToolExecutionContext ctx) {
                ObjectNode input = MAPPER.createObjectNode();
                input.put("ok", true);
                return ai.lingshu.core.message.ToolResult.builder()
                    .status(ai.lingshu.core.message.ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content("ok")
                    .isError(false)
                    .build();
            }
        };
    }
}
