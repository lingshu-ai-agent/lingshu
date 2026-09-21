package ai.lingshu.core.reload;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #007 — AC-06 L1 unit tests for {@link YamlWatcher}.
 *
 * <p>US2 (P1) — file mtime change triggers publish after successful validation.
 * US4 (P2) — failure modes preserve previous config (per spec §FR-007).
 *
 * <p>Tests use a {@link StubAgentFactory} (subclass of {@link AgentFactory}) that
 * lets each test drive {@code loadYamlAndValidate} outcomes via a mutable
 * {@link Loader} — no Mockito, no Spring context. The factory's routers stay
 * {@code null} because the watcher's only factory call is
 * {@code loadYamlAndValidate}, which never touches them.
 *
 * <p>Coverage (5 cases total):
 * <ul>
 *   <li>{@code pollMtimeChange_triggersPublishAfterValidation} (US2 happy path)</li>
 *   <li>{@code pollInvalidYaml_keepsOldConfig} (US4 yaml parse failure)</li>
 *   <li>{@code pollValidationFail_keepsOldConfig} (US4 validation failure)</li>
 *   <li>{@code pollMissingFile_retriesNextTime} (US4 missing file → retry)</li>
 *   <li>{@code pollBackToValid_republishesNewConfig} (US4 recovery path)</li>
 * </ul>
 */
class YamlWatcherTest {

    @TempDir
    Path tempDir;

    private Path ymlPath;
    private AgentConfigRegistry registry;
    private StubAgentFactory factory;
    private YamlWatcher watcher;

    @BeforeEach
    void setUp() {
        ymlPath = tempDir.resolve("application.yml");
        registry = new AgentConfigRegistry();
        factory = new StubAgentFactory();
    }

    /** 60 s interval — tests drive {@link YamlWatcher#poll()} directly,
     *  scheduler never fires during test runtime. */
    private void createWatcher() {
        watcher = new YamlWatcher(ymlPath, registry, factory, 60L);
    }

    /** Call {@link YamlWatcher#start()} when yml file is absent — captures
     *  {@code lastSeen = 0} so first poll triggers if file appears later. */
    private void createAndStartWithoutFile() {
        createWatcher();
        watcher.start();  // file missing → lastSeen = 0
    }

    // ── US2 (P1) — happy path: mtime change → validate → publish ─────────

    @Test
    @DisplayName("US2.1 — mtime change → validate → publish new config")
    void pollMtimeChange_triggersPublishAfterValidation() throws Exception {
        // Pre-create the file so start() captures a non-zero initial mtime.
        Files.write(ymlPath, "agent:\n  llm:\n    provider: anthropic\n"
            .getBytes(StandardCharsets.UTF_8));
        FileTime initialMtime = Files.getLastModifiedTime(ymlPath);

        AgentConfig oldCfg = TestAgentConfigs.baseline();
        AgentConfig newCfg = TestAgentConfigs.extended();
        registry.publishInitial(oldCfg);
        factory.loader = p -> newCfg;

        createWatcher();
        watcher.start();
        long lastSeenAfterStart = watcher.getLastSeen();
        assertThat(lastSeenAfterStart).isEqualTo(initialMtime.toMillis());

        // Modify content + bump mtime 5 s forward to bypass fs resolution.
        Files.write(ymlPath, "agent:\n  llm:\n    provider: openai\n"
            .getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(ymlPath, FileTime.fromMillis(initialMtime.toMillis() + 5_000));

        watcher.poll();

        assertThat(factory.loadCalls).isEqualTo(1);
        assertThat(registry.current()).isSameAs(newCfg);
        assertThat(watcher.getLastSeen()).isGreaterThan(lastSeenAfterStart);
    }

    // ── US4 (P2) — failure modes preserve previous config ────────────────

    @Test
    @DisplayName("US4.1 — YAML parse error → keep old config, lastSeen unchanged (retry)")
    void pollInvalidYaml_keepsOldConfig() throws Exception {
        AgentConfig oldCfg = TestAgentConfigs.baseline();
        registry.publishInitial(oldCfg);
        Files.write(ymlPath, "this is: [unclosed list\n".getBytes(StandardCharsets.UTF_8));
        factory.loader = p -> {
            throw new IllegalStateException("yml parse error: unclosed list");
        };

        createAndStartWithoutFile();
        long lastSeenBefore = watcher.getLastSeen();  // 0

        watcher.poll();

        // Old config preserved
        assertThat(registry.current()).isSameAs(oldCfg);
        // lastSeen unchanged → next poll retries
        assertThat(watcher.getLastSeen()).isEqualTo(lastSeenBefore);
    }

    @Test
    @DisplayName("US4.2 — validation failure ([C02] missing field) → keep old config, lastSeen unchanged")
    void pollValidationFail_keepsOldConfig() throws Exception {
        AgentConfig oldCfg = TestAgentConfigs.baseline();
        registry.publishInitial(oldCfg);
        Files.write(ymlPath, "agent:\n  llm:\n    provider: anthropic\n"
            .getBytes(StandardCharsets.UTF_8));
        factory.loader = p -> {
            throw new IllegalStateException(
                "[C02] missing required field: agent.sandbox.command-whitelist");
        };

        createAndStartWithoutFile();
        long lastSeenBefore = watcher.getLastSeen();

        watcher.poll();

        assertThat(registry.current()).isSameAs(oldCfg);
        assertThat(watcher.getLastSeen()).isEqualTo(lastSeenBefore);
    }

    @Test
    @DisplayName("US4.3 — missing file → WARN skip → file reappears → publish")
    void pollMissingFile_retriesNextTime() throws Exception {
        AgentConfig cfg = TestAgentConfigs.baseline();
        registry.publishInitial(cfg);
        factory.loader = p -> cfg;

        // ymlPath does NOT exist yet
        createAndStartWithoutFile();  // lastSeen = 0

        // First poll: file absent → WARN skip
        watcher.poll();
        assertThat(registry.current()).isSameAs(cfg);  // still initial seed
        assertThat(watcher.getLastSeen()).isEqualTo(0L);

        // File appears
        Files.write(ymlPath, "agent:\n  llm:\n    provider: anthropic\n"
            .getBytes(StandardCharsets.UTF_8));

        // Second poll: file present → publish
        watcher.poll();
        assertThat(factory.loadCalls).isEqualTo(1);
        assertThat(watcher.getLastSeen()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("US4.4 — invalid → keep old → fix → publish new (recovery)")
    void pollBackToValid_republishesNewConfig() throws Exception {
        AgentConfig oldCfg = TestAgentConfigs.baseline();
        AgentConfig newCfg = TestAgentConfigs.extended();
        registry.publishInitial(oldCfg);

        // Step 1: write initial (invalid) YAML, start watcher (captures mtime)
        Files.write(ymlPath, "this is: [broken\n".getBytes(StandardCharsets.UTF_8));
        FileTime initialMtime = Files.getLastModifiedTime(ymlPath);
        factory.loader = p -> {
            throw new IllegalStateException("yml parse error: unclosed list");
        };

        createWatcher();
        watcher.start();
        long lastSeenAfterStart = watcher.getLastSeen();
        assertThat(lastSeenAfterStart).isEqualTo(initialMtime.toMillis());

        // Step 2: poll with invalid YAML → keep old, lastSeen unchanged
        watcher.poll();
        assertThat(registry.current()).isSameAs(oldCfg);
        assertThat(watcher.getLastSeen()).isEqualTo(lastSeenAfterStart);

        // Step 3: fix the file with valid YAML + bump mtime forward
        Files.write(ymlPath, "agent:\n  llm:\n    provider: openai\n"
            .getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(ymlPath, FileTime.fromMillis(initialMtime.toMillis() + 5_000));

        // Step 4: reconfigure loader to return new config
        factory.loader = p -> newCfg;

        // Step 5: second poll → publish new
        watcher.poll();
        assertThat(registry.current()).isSameAs(newCfg);
        assertThat(watcher.getLastSeen()).isGreaterThan(lastSeenAfterStart);
    }

    // ── Test-only AgentFactory subclass ──────────────────────────────────

    /** Subclass with mutable loader — used to drive
     *  {@link AgentFactory#loadYamlAndValidate} outcomes from tests. */
    static final class StubAgentFactory extends AgentFactory {
        Loader loader = p -> {
            throw new IllegalStateException("loader not configured for path " + p);
        };
        int loadCalls = 0;

        StubAgentFactory() {
            super(null, null, null, null, null, null);
        }

        @Override
        public AgentConfig loadYamlAndValidate(Path ymlPath) throws IOException {
            loadCalls++;
            return loader.load(ymlPath);
        }
    }

    @FunctionalInterface
    interface Loader {
        AgentConfig load(Path p) throws IOException;
    }
}
