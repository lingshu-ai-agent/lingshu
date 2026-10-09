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
package ai.lingshu.core.impl.session;

import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.spi.LingsSessionStoreException;
import ai.lingshu.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #014 — L1 unit tests for {@link FileSessionStore} persistence contract.
 *
 * <p>Locks the wire format (Jackson JSON of {@link Checkpoint}), the atomic write
 * protocol (Files.write → Files.move ATOMIC_MOVE), the multi-tenant key isolation
 * (mirrors {@link DefaultInMemorySessionStoreTest} precedents), the sessionId
 * validation (path traversal rejection), and the {@code IOException} → {@code
 * [LINGS-X01]} wrapping (mirror of {@code LingsLlmProviderException} from #027a).
 *
 * <p>Each test uses a fresh {@link TempDir} so concurrent test execution on the
 * same machine does not interfere (no cross-test pollution).
 */
class FileSessionStoreTest {

    @TempDir
    Path baseDir;

    private FileSessionStore store;

    @BeforeEach
    void setUp() {
        // No active tenant — single-tenant mode writes under "_default/".
        TenantContext.clear();
        store = new FileSessionStore(baseDir);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Build a synthetic Checkpoint mirroring what Story #001's Session.checkpoint()
     *  would produce. Plain values, no real history. */
    private static Checkpoint fixture(String sessionId, String userText) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("model", "claude-test");
        metadata.put("user", userText);
        return new Checkpoint(
            sessionId,
            Collections.<Message>singletonList(new Message.User(userText)),
            metadata,
            java.time.Instant.parse("2026-10-09T00:00:00Z"));
    }

    // ── I-1: save + load round-trip preserves identity ──────────────────────

    @Test
    @DisplayName("AC-014-1: saveThenLoad_returnsSameCheckpointFields")
    void saveThenLoad_returnsSameCheckpointFields() {
        Checkpoint original = fixture("sess-1", "hello");

        store.save(original);
        Optional<Checkpoint> loaded = store.load("sess-1");

        assertThat(loaded).isPresent();
        Checkpoint actual = loaded.get();
        assertThat(actual.getSessionId()).isEqualTo("sess-1");
        assertThat(actual.getHistory()).hasSize(1);
        assertThat(actual.getHistory().get(0).role()).isEqualTo("user");
        assertThat(actual.getHistory().get(0)).isInstanceOf(Message.User.class);
        assertThat(((Message.User) actual.getHistory().get(0)).getContent())
            .isEqualTo("hello");
        assertThat(actual.getMetadata()).containsEntry("model", "claude-test");
        assertThat(actual.getSavedAt()).isEqualTo(java.time.Instant.parse("2026-10-09T00:00:00Z"));
    }

    // ── I-2: alice's save invisible to bob's load (multi-tenant isolation) ─

    @Test
    @DisplayName("AC-014-2: aliceSaves_thenBobLoadsSameSessionId_returnsEmpty")
    void aliceSaves_thenBobLoadsSameSessionId_returnsEmpty() {
        TenantContext.runAs("alice", () -> {
            store.save(fixture("shared-sid", "alice's payload"));
        });
        TenantContext.runAs("bob", () -> {
            // Bob sees no checkpoint for "shared-sid" — alice's save is in
            // baseDir/alice/shared-sid.json, not baseDir/bob/shared-sid.json.
            assertThat(store.load("shared-sid")).isEmpty();
        });
        // Sanity: alice's load still works.
        TenantContext.runAs("alice", () -> {
            assertThat(store.load("shared-sid")).isPresent();
        });
    }

    // ── I-3: single-tenant mode → files land under _default/ ───────────────

    @Test
    @DisplayName("AC-014-3: singleTenantMode_writesToDefaultSubdir")
    void singleTenantMode_writesToDefaultSubdir() throws IOException {
        store.save(fixture("solo-sid", "single-tenant"));

        // _default/ subdirectory must exist and contain the JSON file.
        Path defaultDir = baseDir.resolve(FileSessionStore.SINGLE_TENANT_DIR);
        assertThat(Files.isDirectory(defaultDir)).isTrue();
        assertThat(Files.exists(defaultDir.resolve("solo-sid.json"))).isTrue();
        // And the baseDir root itself must NOT contain any .json file (would be
        // a sign that the tenant prefix was skipped).
        try (java.util.stream.Stream<Path> walk = Files.list(baseDir)) {
            assertThat(walk.filter(Files::isRegularFile).count()).isZero();
        }
    }

    // ── I-4: load on unknown sessionId returns Optional.empty() ─────────────

    @Test
    @DisplayName("AC-014-4: loadUnknownSessionId_returnsEmptyOptional")
    void loadUnknownSessionId_returnsEmptyOptional() {
        assertThat(store.load("never-saved")).isEmpty();
    }

    @Test
    @DisplayName("AC-014-5: loadNullSessionId_returnsEmptyOptional_noThrow")
    void loadNullSessionId_returnsEmptyOptional_noThrow() {
        assertThat(store.load(null)).isEmpty();
    }

    // ── I-5: sessionId validation rejects path traversal payloads ──────────

    @Test
    @DisplayName("EC-014-1: pathTraversalSessionId_throwsIllegalArgument")
    void pathTraversalSessionId_throwsIllegalArgument() {
        // Attempt to escape the tenant dir via a classic traversal payload.
        // Validation must fire BEFORE any filesystem call.
        assertThatThrownBy(() -> store.load("../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> store.save(fixture("../etc/passwd", "evil")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match");
    }

    @Test
    @DisplayName("EC-014-2: emptyOrNullSessionId_throwsIllegalArgument")
    void emptyOrNullSessionId_throwsIllegalArgument() {
        assertThatThrownBy(() -> store.load(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(fixture("", "x")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── I-6: IOException on corrupt file → [LINGS-X01] wrapped exception ──

    @Test
    @DisplayName("EC-014-3: loadCorruptJsonFile_throwsLingsSessionStoreException_X01")
    void loadCorruptJsonFile_throwsLingsSessionStoreException_X01() throws IOException {
        // Pre-stage: write a non-JSON file at the expected path, bypassing save().
        Path tenantDir = baseDir.resolve(FileSessionStore.SINGLE_TENANT_DIR);
        Files.createDirectories(tenantDir);
        Path corrupt = tenantDir.resolve("corrupt-sid.json");
        Files.write(corrupt, "this is not valid JSON {[".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> store.load("corrupt-sid"))
            .isInstanceOf(LingsSessionStoreException.class)
            .satisfies(t -> assertThat(((LingsSessionStoreException) t).getCode())
                .isEqualTo("LINGS-X01"))
            .hasMessageContaining("[LINGS-X01]")
            .hasMessageContaining("deserialize");
    }

    // ── I-7: save is last-write-wins (overwrites cleanly without corrupting) ─

    @Test
    @DisplayName("AC-014-6: saveOverwritesPriorFile_atomically")
    void saveOverwritesPriorFile_atomically() {
        store.save(fixture("reused-sid", "first"));
        store.save(fixture("reused-sid", "second"));

        Optional<Checkpoint> loaded = store.load("reused-sid");
        assertThat(loaded).isPresent();
        assertThat(((Message.User) loaded.get().getHistory().get(0)).getContent())
            .isEqualTo("second");
        // No .tmp file leaked.
        Path tenantDir = baseDir.resolve(FileSessionStore.SINGLE_TENANT_DIR);
        assertThat(Files.exists(tenantDir.resolve("reused-sid.json.tmp"))).isFalse();
    }

    // ── I-8: save(null) rejects early with IAE (no FS call attempted) ──────

    @Test
    @DisplayName("EC-014-4: saveNullCheckpoint_throwsIllegalArgument")
    void saveNullCheckpoint_throwsIllegalArgument() {
        assertThatThrownBy(() -> store.save(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }

    // ── Constructor contract: null baseDir rejected ────────────────────────

    @Test
    @DisplayName("EC-014-5: ctorNullBaseDir_throwsIllegalArgument")
    void ctorNullBaseDir_throwsIllegalArgument() {
        assertThatThrownBy(() -> new FileSessionStore(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("baseDir must not be null");
    }

    // ── Visible-for-testing helpers: listSessionIds / size / clear / delete ─

    @Test
    @DisplayName("AC-014-7: listSessionIds_size_clear_helpersWork")
    void listSessionIds_size_clear_helpersWork() {
        assertThat(store.size()).isZero();

        store.save(fixture("a", "x"));
        store.save(fixture("b", "y"));
        store.save(fixture("c", "z"));

        assertThat(store.size()).isEqualTo(3);
        assertThat(store.listSessionIds())
            .containsExactly("a.json", "b.json", "c.json"); // alphabetical order

        store.delete("b");
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.listSessionIds()).containsExactly("a.json", "c.json");

        store.clear();
        assertThat(store.size()).isZero();
        assertThat(store.listSessionIds()).isEmpty();
    }
}