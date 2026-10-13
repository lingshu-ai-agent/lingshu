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
package ai.lingshu.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 *
 * <p><b>🆕 Story #014 — Jackson round-trip:</b> the {@code @JsonCreator} +
 * {@code @JsonProperty} annotations on the all-args constructor enable Jackson
 * to reconstruct a {@code Checkpoint} from the JSON file written by
 * {@code FileSessionStore.save}. Lombok's {@code @Value} generates a final class
 * with an all-args constructor but no default constructor, so without these
 * annotations Jackson would report {@code "Cannot construct instance ...
 * (no Creators, like default constructor, exist)"}. The annotations are
 * additive — the public field getters / setters remain unchanged.
 */
@Value
public class Checkpoint {

    @JsonCreator
    public Checkpoint(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("history") List<Message> history,
        @JsonProperty("metadata") Map<String, String> metadata,
        @JsonProperty("savedAt") Instant savedAt) {
        this.sessionId = sessionId;
        this.history = history;
        this.metadata = metadata;
        this.savedAt = savedAt;
    }
    /** Session id this checkpoint belongs to. */
    String sessionId;
    /** Full ordered message history at the moment of checkpoint. */
    List<Message> history;
    /** Free-form metadata (model used, token totals, tenant id, etc.). */
    Map<String, String> metadata;
    /** Wall-clock time the snapshot was created. */
    Instant savedAt;
}