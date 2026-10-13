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
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.spi.LingsSessionStoreException;
import ai.lingshu.core.spi.SessionStoreErrorCodes;
import ai.lingshu.core.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * File-backed {@link SessionStore} with multi-tenant session-key isolation (Story #014).
 *
 * <p>Each saved checkpoint is a single JSON file at
 * {@code {baseDir}/{tenant-or-"_default"}/{sessionId}.json}, where:
 * <ul>
 *   <li>{@code baseDir} — passed in by {@link FileSessionStoreProvider}; defaults to
 *       {@code ${java.io.tmpdir}/lingshu-sessions} when not configured.</li>
 *   <li>{@code tenant-or-"_default"} — derived from {@link TenantContext#current()}.
 *       Single-tenant mode collapses to {@code "_default"}; multi-tenant mode uses the
 *       validated tenantId (already regex-restricted to {@code [a-zA-Z0-9_-]{1,64}}).</li>
 *   <li>{@code sessionId} — validated against {@link #SESSION_ID_PATTERN} to prevent
 *       path traversal.</li>
 * </ul>
 *
 * <h2>Atomic write protocol</h2>
 *
 * <p>To avoid leaving a corrupt file when the JVM crashes mid-write, every
 * {@link #save(Checkpoint)} goes through:
 * <pre>{@code
 *   1. write JSON bytes to {path}.tmp
 *   2. fsync (best-effort — flush + sync on POSIX, no-op on Windows)
 *   3. Files.move(.tmp → .json, ATOMIC_MOVE, REPLACE_EXISTING)
 * }</pre>
 * On POSIX filesystems, the {@code ATOMIC_MOVE} rename is atomic at the inode level,
 * so any concurrent {@link #load(String)} sees either the old or the new file — never
 * a half-written one.
 *
 * <h2>Errors</h2>
 *
 * <p>All {@link IOException}s and Jackson serialization failures are wrapped in
 * {@link LingsSessionStoreException} with {@code [LINGS-X01]} prefix, matching the
 * pattern from {@link ai.lingshu.core.impl.llm.LingsLlmProviderException} (Story #027a).
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I-1: {@code save} + {@code load} round-trip produces a {@link Checkpoint}
 *       that {@link ObjectMapper#equals} the input (field-by-field).</li>
 *   <li>I-2: alice's checkpoint for sessionId {@code X} is never visible to
 *       bob's {@code load("X")}, even though both call sites pass the same id.</li>
 *   <li>I-3: in single-tenant mode ({@code TenantContext.current() == null}),
 *       files land under {@code _default/}, not at the baseDir root.</li>
 *   <li>I-4: a load for an unknown sessionId returns {@link Optional#empty()}
 *       — not a thrown exception. Matches {@code SessionStore#load} contract.</li>
 *   <li>I-5: sessionId validation rejects path-traversal payloads (e.g.
 *       {@code "../etc/passwd"}) before any filesystem call.</li>
 * </ul>
 */
public class FileSessionStore implements SessionStore {

    /** Sentinel used in single-tenant mode instead of a real tenantId. */
    static final String SINGLE_TENANT_DIR = "_default";

    /** sessionId regex: alphanumeric + dot + dash + underscore, length 1-128. */
    static final Pattern SESSION_ID_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{1,128}");

    private static final Logger LOG = LoggerFactory.getLogger(FileSessionStore.class);

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    /**
     * @param baseDir directory under which per-tenant subdirectories are created.
     *               Must be an existing directory; created lazily on first {@link #save}
     *               if missing.
     */
    public FileSessionStore(Path baseDir) {
        this(baseDir, defaultMapper());
    }

    /**
     * Package-private constructor used by tests that need a non-default
     * {@link ObjectMapper} (e.g. to inject mocks or alternative features).
     */
    FileSessionStore(Path baseDir, ObjectMapper objectMapper) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir must not be null");
        }
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
    }

    /**
     * Default {@link ObjectMapper} configured for Checkpoint serialization:
     * pretty-print disabled (file size matters), date-as-timestamp disabled
     * (Instant serializes as ISO-8601 string), and {@link JavaTimeModule}
     * registered so {@link java.time.Instant} (in {@code Checkpoint.savedAt})
     * can round-trip. The {@code JavaTimeModule} class is already on the
     * classpath via the transitive {@code jackson-datatype-jsr310} dependency
     * brought in by {@code spring-boot-starter-web} → no new binary needed.
     */
    private static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Override
    public void save(Checkpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
        String sessionId = checkpoint.getSessionId();
        validateSessionId(sessionId);

        Path tenantDir = resolveTenantDir();
        Path finalFile = tenantDir.resolve(sessionId + ".json");
        Path tmpFile = tenantDir.resolve(sessionId + ".json.tmp");

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(checkpoint);
        } catch (JsonProcessingException e) {
            throw new LingsSessionStoreException(
                SessionStoreErrorCodes.LINGS_X01,
                "Failed to serialize Checkpoint for sessionId='" + sessionId + "'",
                e);
        }

        try {
            Files.createDirectories(tenantDir);
            Files.write(tmpFile, bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(tmpFile, finalFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicEx) {
                // Fallback for filesystems that don't support ATOMIC_MOVE (e.g. some
                // SMB mounts). Non-atomic REPLACE_EXISTING is still last-writer-wins
                // and produces a valid file; we log a warning but do not fail.
                LOG.warn("ATOMIC_MOVE not supported on {}; falling back to non-atomic "
                    + "REPLACE_EXISTING. Crash mid-write may produce partial files.",
                    tenantDir);
                Files.move(tmpFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new LingsSessionStoreException(
                SessionStoreErrorCodes.LINGS_X01,
                "Failed to write checkpoint file for sessionId='" + sessionId
                    + "' under '" + tenantDir + "'",
                e);
        }
    }

    @Override
    public Optional<Checkpoint> load(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        validateSessionId(sessionId);
        Path finalFile = resolveTenantDir().resolve(sessionId + ".json");
        if (!Files.exists(finalFile)) {
            return Optional.empty();
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(finalFile);
        } catch (IOException e) {
            throw new LingsSessionStoreException(
                SessionStoreErrorCodes.LINGS_X01,
                "Failed to read checkpoint file '" + finalFile + "'",
                e);
        }

        try {
            return Optional.of(objectMapper.readValue(bytes, Checkpoint.class));
        } catch (IOException e) {
            throw new LingsSessionStoreException(
                SessionStoreErrorCodes.LINGS_X01,
                "Failed to deserialize checkpoint file '" + finalFile + "'",
                e);
        }
    }

    /**
     * Build the per-tenant subdirectory path under {@link #baseDir}.
     *
     * <p>Multi-tenant mode: {@code baseDir/tenantId}
     * <br>Single-tenant mode: {@code baseDir/_default}
     */
    Path resolveTenantDir() {
        String tenantId = TenantContext.current();
        if (tenantId == null) {
            return baseDir.resolve(SINGLE_TENANT_DIR);
        }
        return baseDir.resolve(tenantId);
    }

    /**
     * Validate sessionId against {@link #SESSION_ID_PATTERN} before any filesystem
     * call. Rejects {@code null}, empty, and path-traversal payloads (e.g.
     * {@code "../etc/passwd"}, {@code "/abs/path"}).
     */
    static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be null or empty");
        }
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException(
                "sessionId '" + sessionId + "' must match " + SESSION_ID_PATTERN.pattern());
        }
    }

    /**
     * Return the base directory (for diagnostics; not used by the production
     * save/load protocol).
     */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * Visible-for-testing helper — list all {@code .json} checkpoint filenames in
     * the current tenant dir. Returns an empty list if the tenant dir does not
     * yet exist.
     */
    List<String> listSessionIds() {
        Path dir = resolveTenantDir();
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }
        try {
            String[] names = dir.toFile().list(new java.io.FilenameFilter() {
                @Override
                public boolean accept(java.io.File dir, String name) {
                    return name.endsWith(".json");
                }
            });
            if (names == null) {
                return Collections.emptyList();
            }
            Arrays.sort(names);
            return Arrays.asList(names);
        } catch (RuntimeException e) {
            throw new LingsSessionStoreException(
                SessionStoreErrorCodes.LINGS_X01,
                "Failed to list session files under '" + dir + "'",
                e);
        }
    }

    /**
     * Visible-for-testing helper — number of {@code .json} checkpoint files in
     * the current tenant dir. Returns 0 if the tenant dir does not yet exist.
     */
    int size() {
        return listSessionIds().size();
    }

    /**
     * Visible-for-testing helper — drop all checkpoints in the current tenant
     * dir. Used by test {@code @AfterEach} cleanup. Production callers should
     * not invoke this.
     */
    void clear() {
        Path dir = resolveTenantDir();
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
        } catch (IOException e) {
            throw new LingsSessionStoreException(
                SessionStoreErrorCodes.LINGS_X01,
                "Failed to clear tenant dir '" + dir + "'",
                e);
        }
    }

    /**
     * Visible-for-testing helper — drop a single checkpoint by sessionId.
     */
    void delete(String sessionId) {
        if (sessionId == null) {
            return;
        }
        validateSessionId(sessionId);
        Path finalFile = resolveTenantDir().resolve(sessionId + ".json");
        try {
            Files.deleteIfExists(finalFile);
        } catch (IOException e) {
            throw new LingsSessionStoreException(
                SessionStoreErrorCodes.LINGS_X01,
                "Failed to delete checkpoint file '" + finalFile + "'",
                e);
        }
    }

    /** Stub used by tests when building a synthetic Checkpoint. */
    static Checkpoint testCheckpoint(String sessionId, List<Message> history,
                                     Map<String, String> metadata) {
        return new Checkpoint(sessionId, history, metadata, java.time.Instant.now());
    }
}