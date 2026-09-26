package ai.lingshu.core.reload;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.impl.runtime.DefaultAgent;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #007 — AC-06 black-box end-to-end integration test.
 *
 * <p>Wires the full YAML hot-reload pipeline end-to-end without Spring:
 * <ol>
 *   <li>Real {@code application.yml} on disk (TempDir)</li>
 *   <li>Real {@link YamlWatcher} polling mtime + delegating to a real
 *       {@link AgentFactory#loadYamlAndValidate} (subclass with null routers
 *       because loadYamlAndValidate never touches them)</li>
 *   <li>Real {@link AgentConfigRegistry} publishing the new config</li>
 *   <li>Real {@link DefaultAgent} (with a stub FlowEngine that captures the
 *       frozen {@link TurnContext} for inspection)</li>
 * </ol>
 *
 * <p>AC-06 acceptance criteria exercised here:
 * <ol>
 *   <li><b>T1 freezes old config</b> — when the agent starts turn 1 with cfg1,
 *       the captured TurnContext holds cfg1 even after the yml on disk changes.</li>
 *   <li><b>T2 sees new config</b> — when the agent starts turn 2 AFTER a
 *       successful publish of cfg2, the captured TurnContext holds cfg2.</li>
 *   <li><b>T1's snapshot is immutable</b> — cfg1 stays cfg1 throughout, even
 *       after cfg2 has been published (Java reference freeze + @Value).</li>
 * </ol>
 *
 * <p>Test wall-clock budget: &lt; 1 s (no @SpringBootTest, direct wiring).
 */
class YamlHotReloadIT {

    @TempDir
    Path tempDir;

    private Path ymlPath;
    private AgentConfigRegistry registry;
    private YamlWatcher watcher;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    @Test
    @DisplayName("AC-06: T1 freezes old yml config, T2 sees new yml config after hot-reload")
    void t1FreezesOldConfig_t2SeesNewConfig_acrossYamlHotReload() throws Exception {
        ymlPath = tempDir.resolve("application.yml");
        // v1 yml: whitelist = [ls, cat]
        writeYaml(initialYaml());

        registry = new AgentConfigRegistry();
        RealYamlAgentFactory factory = new RealYamlAgentFactory();

        // Caller seeds the registry with the initial config (parsed from v1 yml).
        // This mirrors production: a Spring ContextLoader also seeds on startup.
        AgentConfig cfg1 = factory.loadYamlAndValidate(ymlPath);
        registry.publishInitial(cfg1);

        // Wire watcher — it will detect subsequent edits and publish them.
        watcher = new YamlWatcher(ymlPath, registry, factory, 60L);
        watcher.start();  // captures lastSeen = mtime of v1 yml

        // Wire a DefaultAgent (with a capturing FlowEngine) onto the registry.
        CapturingEngine engine = new CapturingEngine();
        DefaultAgent agent = new DefaultAgent(new DefaultSession(), cfg1, engine, registry);

        // ── T1 ────────────────────────────────────────────────────────────
        agent.runBlocking("first user input");
        assertThat(engine.captured).hasSize(1);
        AgentConfig frozenT1 = engine.captured.get(0).config();
        assertThat(frozenT1).isSameAs(cfg1);
        assertThat(frozenT1.getSandbox().getCommandWhitelist())
            .containsExactly("ls", "cat");

        // ── Hot-reload: edit yml on disk ─────────────────────────────────
        Files.write(ymlPath, updatedYaml().getBytes(StandardCharsets.UTF_8));
        // Bump mtime 5 s forward to guarantee > lastSeen (fs resolution).
        long bumpedMtime = Files.getLastModifiedTime(ymlPath).toMillis() + 5_000;
        Files.setLastModifiedTime(ymlPath, java.nio.file.attribute.FileTime.fromMillis(bumpedMtime));

        // Watcher detects the change → validates → publishes cfg2.
        watcher.poll();
        AgentConfig cfg2 = registry.current();
        assertThat(cfg2).isNotNull();
        assertThat(cfg2).isNotSameAs(cfg1);
        assertThat(cfg2.getSandbox().getCommandWhitelist())
            .containsExactly("ls", "cat", "git");

        // ── T1's frozen snapshot is STILL cfg1 (immutability) ────────────
        assertThat(frozenT1).isSameAs(cfg1);
        assertThat(frozenT1.getSandbox().getCommandWhitelist())
            .containsExactly("ls", "cat");

        // ── T2 ────────────────────────────────────────────────────────────
        agent.runBlocking("second user input");
        assertThat(engine.captured).hasSize(2);
        AgentConfig frozenT2 = engine.captured.get(1).config();
        assertThat(frozenT2).isSameAs(cfg2);
        assertThat(frozenT2.getSandbox().getCommandWhitelist())
            .containsExactly("ls", "cat", "git");
    }

    @Test
    @DisplayName("AC-06: invalid yml keeps old config in registry (rollback)")
    void invalidYaml_keepsOldConfigPublished() throws Exception {
        ymlPath = tempDir.resolve("application.yml");
        writeYaml(initialYaml());

        registry = new AgentConfigRegistry();
        RealYamlAgentFactory factory = new RealYamlAgentFactory();

        // Caller seeds the initial config (matches production startup).
        AgentConfig cfg1 = factory.loadYamlAndValidate(ymlPath);
        registry.publishInitial(cfg1);

        watcher = new YamlWatcher(ymlPath, registry, factory, 60L);
        watcher.start();
        long mtimeBefore = watcher.getLastSeen();
        assertThat(mtimeBefore).isGreaterThan(0L);  // file present at start()

        // Corrupt the yml on disk (orphan list item under no parent)
        writeYaml("- foo\n- bar\n");

        // Bump mtime so watcher sees the change
        Files.setLastModifiedTime(ymlPath, java.nio.file.attribute.FileTime.fromMillis(mtimeBefore + 5_000));

        // Poll: validation must FAIL, registry must NOT change
        watcher.poll();
        assertThat(registry.current()).isSameAs(cfg1);

        // lastSeen unchanged → next poll retries
        assertThat(watcher.getLastSeen()).isEqualTo(mtimeBefore);

        // Fix the yml + bump mtime again
        writeYaml(updatedYaml());
        Files.setLastModifiedTime(ymlPath, java.nio.file.attribute.FileTime.fromMillis(mtimeBefore + 10_000));

        // Poll: validates + publishes cfg2
        watcher.poll();
        AgentConfig cfg2 = registry.current();
        assertThat(cfg2).isNotSameAs(cfg1);
        assertThat(cfg2.getSandbox().getCommandWhitelist())
            .containsExactly("ls", "cat", "git");
    }

    @Test
    @DisplayName("Story #026: ${user.dir} placeholder resolves across YAML hot-reload")
    void placeholderEnvVar_resolvesAcrossYamlHotReload() throws Exception {
        ymlPath = tempDir.resolve("application.yml");
        // v1 yml: literal /tmp
        writeYaml(initialYaml());

        registry = new AgentConfigRegistry();
        RealYamlAgentFactory factory = new RealYamlAgentFactory();

        AgentConfig cfg1 = factory.loadYamlAndValidate(ymlPath);
        registry.publishInitial(cfg1);
        assertThat(cfg1.getSandbox().getWorkingDirectory().toString()).isEqualTo("/tmp");

        watcher = new YamlWatcher(ymlPath, registry, factory, 60L);
        watcher.start();

        // ── Hot-reload: edit yml to use ${user.dir} ────────────────────
        String expectedUserDir = System.getProperty("user.dir");
        assertThat(expectedUserDir).as("JVM user.dir must be set for this test").isNotEmpty();
        writeYaml(placeholderYaml());
        long bumpedMtime = Files.getLastModifiedTime(ymlPath).toMillis() + 5_000;
        Files.setLastModifiedTime(ymlPath, java.nio.file.attribute.FileTime.fromMillis(bumpedMtime));

        watcher.poll();
        AgentConfig cfg2 = registry.current();
        assertThat(cfg2).isNotSameAs(cfg1);
        // Cross-path parity check (the bug Story #026 fixes):
        //   ${user.dir} resolves to the actual user.dir instead of literal "${user.dir}".
        assertThat(cfg2.getSandbox().getWorkingDirectory().toString())
            .isEqualTo(expectedUserDir);
    }

    @Test
    @DisplayName("Story #026: unresolved ${X} placeholder keeps old config in registry (rollback)")
    void unresolvedPlaceholder_keepsOldConfigPublished() throws Exception {
        ymlPath = tempDir.resolve("application.yml");
        writeYaml(initialYaml());

        registry = new AgentConfigRegistry();
        RealYamlAgentFactory factory = new RealYamlAgentFactory();

        AgentConfig cfg1 = factory.loadYamlAndValidate(ymlPath);
        registry.publishInitial(cfg1);
        long mtimeBefore = Files.getLastModifiedTime(ymlPath).toMillis();

        watcher = new YamlWatcher(ymlPath, registry, factory, 60L);
        watcher.start();
        assertThat(watcher.getLastSeen()).isEqualTo(mtimeBefore);

        // Edit yml with unresolved placeholder — ${LINGS_TEST_UNSET_X_NOT_RESOLVED}
        // is not in env / sys-prop, so the resolver must throw LINGS-C03.
        writeYaml(unresolvedPlaceholderYaml());
        Files.setLastModifiedTime(ymlPath, java.nio.file.attribute.FileTime.fromMillis(mtimeBefore + 5_000));

        // Watcher catches the exception (any Exception → log + skip publish)
        // and rolls back: registry keeps cfg1, lastSeen unchanged so next poll retries.
        watcher.poll();
        assertThat(registry.current()).isSameAs(cfg1);
        assertThat(watcher.getLastSeen()).isEqualTo(mtimeBefore);

        // Fix the yml back to a valid form — next poll should publish cfg2.
        writeYaml(updatedYaml());
        Files.setLastModifiedTime(ymlPath, java.nio.file.attribute.FileTime.fromMillis(mtimeBefore + 10_000));
        watcher.poll();
        AgentConfig cfg2 = registry.current();
        assertThat(cfg2).isNotSameAs(cfg1);
        assertThat(cfg2.getSandbox().getCommandWhitelist())
            .containsExactly("ls", "cat", "git");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void writeYaml(String content) throws IOException {
        Files.write(ymlPath, content.getBytes(StandardCharsets.UTF_8));
    }

    private String initialYaml() {
        return "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n" +
            "  sandbox:\n" +
            "    policy: strict\n" +
            "    runtime: chroot\n" +
            "    working-directory: /tmp\n" +
            "    command-whitelist:\n" +
            "      - ls\n" +
            "      - cat\n";
    }

    private String updatedYaml() {
        return "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n" +
            "  sandbox:\n" +
            "    policy: strict\n" +
            "    runtime: chroot\n" +
            "    working-directory: /tmp\n" +
            "    command-whitelist:\n" +
            "      - ls\n" +
            "      - cat\n" +
            "      - git\n";
    }

    /** Story #026 — working-directory uses ${user.dir} placeholder. */
    private String placeholderYaml() {
        return "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n" +
            "  sandbox:\n" +
            "    policy: strict\n" +
            "    runtime: chroot\n" +
            "    working-directory: ${user.dir}\n" +
            "    command-whitelist:\n" +
            "      - ls\n" +
            "      - cat\n" +
            "      - git\n";
    }

    /** Story #026 — working-directory references an unset placeholder (fail-fast). */
    private String unresolvedPlaceholderYaml() {
        return "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n" +
            "  sandbox:\n" +
            "    policy: strict\n" +
            "    runtime: chroot\n" +
            "    working-directory: ${LINGS_TEST_UNSET_X_NOT_RESOLVED}\n" +
            "    command-whitelist:\n" +
            "      - ls\n" +
            "      - cat\n";
    }

    /** Records every {@link TurnContext} passed to {@link #runTurn}. */
    private static final class CapturingEngine implements FlowEngine {
        final List<TurnContext> captured = new ArrayList<>();

        @Override
        public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
            captured.add(ctx);
            sink.onSubscribe(new Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            sink.onNext(new AgentEvent.TurnCompleted(StopReason.END_TURN, Usage.zero()));
            sink.onComplete();
        }
    }

    /**
     * Subclass of {@link AgentFactory} with null routers — only
     * {@link AgentFactory#loadYamlAndValidate} is exercised by the watcher,
     * and that method never touches the routers.
     */
    private static final class RealYamlAgentFactory extends AgentFactory {
        RealYamlAgentFactory() {
            super(null, null, null, null, null, null);
        }
        // loadYamlAndValidate inherited unchanged.
    }
}
