package ai.lingshu.core.message;

import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of session state, persisted via {@code SessionStore.save}.
 *
 * <p>A {@code Checkpoint} is what {@code Session.checkpoint()} returns and what
 * {@code SessionStore.load(id)} reconstructs. It captures everything needed to
 * resume a turn across process restarts.
 */
@Value
public class Checkpoint {
    /** Session id this checkpoint belongs to. */
    String sessionId;
    /** Full ordered message history at the moment of checkpoint. */
    List<Message> history;
    /** Free-form metadata (model used, token totals, tenant id, etc.). */
    Map<String, String> metadata;
    /** Wall-clock time the snapshot was created. */
    Instant savedAt;
}