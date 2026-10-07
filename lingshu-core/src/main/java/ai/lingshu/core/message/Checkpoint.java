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