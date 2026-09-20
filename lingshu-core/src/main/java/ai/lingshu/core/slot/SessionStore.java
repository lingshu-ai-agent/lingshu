package ai.lingshu.core.slot;

import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.spi.ContractVersionRef;

import java.util.Optional;

/**
 * Slot 5 — Persists session checkpoints (dsh §4.8).
 *
 * <p>Three backends are valid and indistinguishable to callers:
 * <ul>
 *   <li>In-memory (default for testing; lost on restart)</li>
 *   <li>File (one file per session id, JSON-serialized)</li>
 *   <li>Redis / JDBC (network-backed, shared across instances)</li>
 * </ul>
 *
 * <p>Implementations are responsible for serialization (Jackson) and any locking needed for
 * concurrent writes from the same session. The interface stays minimal because the choice
 * of backend is configuration, not code.
 */
public interface SessionStore {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /** Persist the snapshot; replaces any prior checkpoint for the same session id. */
    void save(Checkpoint checkpoint);

    /**
     * Retrieve the latest snapshot for {@code sessionId}, or empty if none.
     *
     * @return empty when the session has never been checkpointed (first turn of a brand-new session)
     */
    Optional<Checkpoint> load(String sessionId);
}