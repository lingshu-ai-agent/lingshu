package ai.lingshu.core.impl.tool;

import ai.lingshu.core.impl.skill.CommitSkill;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DefaultToolRegistry#unregister(String)} (Story #021b, T-10).
 *
 * <p>Covers the 4 contract invariants:
 * <ol>
 *   <li>Existing name → removed from {@code registry} + returns {@code true}.</li>
 *   <li>Unknown name → no-op + returns {@code false}.</li>
 *   <li>{@code null} name → no-op + returns {@code false} (no exception).</li>
 *   <li>Skill tool → also removed from the parallel {@code skillsByName} index.</li>
 * </ol>
 * Plus a thread-safety smoke test (50 names, concurrent unregister).
 */
@DisplayName("DefaultToolRegistry.unregister")
class DefaultToolRegistryUnregisterTest {

    private DefaultToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
    }

    /**
     * Minimal in-test {@link Tool} stub — must NOT be a {@link Skill} (so the
     * Skill dual-index branch is not exercised).
     */
    static class PlainTool implements Tool {
        private final String name;
        PlainTool(String name) { this.name = name; }
        @Override public String name()        { return name; }
        @Override public String description() { return "plain"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return null;
        }
        @Override
        public ai.lingshu.core.message.ToolResult execute(
                ai.lingshu.core.message.ToolCall call,
                ai.lingshu.core.slot.ToolExecutionContext ctx) {
            return ai.lingshu.core.message.ToolResult.builder()
                .status(ai.lingshu.core.message.ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content("ok")
                .isError(false)
                .build();
        }
    }

    @Test
    @DisplayName("unregister(existingName) → true and removes from registry")
    void unregisterExistingNameReturnsTrue() {
        PlainTool plain = new PlainTool("Read");
        registry.register(plain);
        assertTrue(registry.lookup("Read") instanceof Tool,
            "lookup must return a Tool-typed value (sanity check)");
        assertEquals(plain, registry.lookup("Read"));
        boolean removed = registry.unregister("Read");
        assertTrue(removed, "unregister must return true for a registered name");
        assertNull(registry.lookup("Read"), "lookup must return null after unregister");
    }

    @Test
    @DisplayName("unregister(unknownName) → false and does nothing")
    void unregisterUnknownNameReturnsFalse() {
        boolean removed = registry.unregister("NonExistent");
        assertFalse(removed);
        // Empty registry stays empty
        assertEquals(0, registry.names().size());
    }

    @Test
    @DisplayName("unregister(null) → false (no exception)")
    void unregisterNullNameReturnsFalse() {
        // Should NOT throw IllegalArgumentException — silent no-op per plan §3.6.1
        boolean removed = registry.unregister(null);
        assertFalse(removed);
    }

    @Test
    @DisplayName("unregister(Skill) → also removes from skillsByName (findSkill returns null)")
    void unregisterSkillToolAlsoRemovesFromSkillsByName() throws Exception {
        // CommitSkill is a concrete @Component Skill from Story #020a — it implements Skill.
        Skill skill = new CommitSkill();
        registry.register(skill);
        // Sanity: both indices see the skill
        assertEquals(skill, registry.lookup(skill.name()));
        assertEquals(skill, registry.findSkill(skill.name()));
        // Unregister
        boolean removed = registry.unregister(skill.name());
        assertTrue(removed);
        // Both indices must lose it
        assertNull(registry.lookup(skill.name()));
        assertNull(registry.findSkill(skill.name()),
            "skillsByName must be cleared in lock-step (plan §3.6.2)");
    }

    @Test
    @DisplayName("unregister is idempotent — second call returns false")
    void unregisterIsIdempotent() {
        registry.register(new PlainTool("Read"));
        assertTrue(registry.unregister("Read"));
        // Second unregister of the same name → silent no-op (no exception)
        assertFalse(registry.unregister("Read"));
    }

    @Test
    @DisplayName("concurrent unregister across 50 tools is thread-safe")
    void unregisterConcurrentCallsThreadSafe() throws Exception {
        int n = 50;
        Set<String> names = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String name = "Tool-" + i;
            names.add(name);
            registry.register(new PlainTool(name));
        }
        assertEquals(n, registry.names().size());

        ExecutorService exec = Executors.newFixedThreadPool(8);
        try {
            Set<Future<Integer>> futures = new java.util.HashSet<>();
            for (final String name : names) {
                Callable<Integer> task = new Callable<Integer>() {
                    @Override public Integer call() {
                        return registry.unregister(name) ? 1 : 0;
                    }
                };
                futures.add(exec.submit(task));
            }
            int removedCount = 0;
            for (Future<Integer> f : futures) {
                removedCount += f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(n, removedCount,
                "All " + n + " unregisters must succeed (first call wins)");
            assertEquals(0, registry.names().size(),
                "Final registry must be empty");
        } finally {
            exec.shutdownNow();
            exec.awaitTermination(1, TimeUnit.SECONDS);
        }
    }
}