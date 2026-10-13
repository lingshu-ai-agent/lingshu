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
package ai.lingshu.core.impl.sessionstore;

import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.slot.SessionStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story #046 test fixture — in-memory {@link SessionStore} that records every
 * {@code save} call. Lets tests assert that the engine invoked persistence at
 * each turn-completion point without needing a real filesystem or the
 * {@code FileSessionStore} atomic-write protocol.
 *
 * <p>Mirrors {@code RecordingPromptBuilder} (Story #004): minimal surface, no
 * external dependencies, thread-safe via {@link ConcurrentHashMap}.
 *
 * <p>Optional {@link #throwOnSave} flag injects an arbitrary exception into
 * the {@link #save} path so the engine's "swallow IO errors during save"
 * guarantee can be verified end-to-end.
 */
public class RecordingSessionStore implements SessionStore {

    private final Map<String, Checkpoint> saved = new ConcurrentHashMap<String, Checkpoint>();
    private final List<Checkpoint> saveHistory =
        Collections.synchronizedList(new ArrayList<Checkpoint>());
    private volatile RuntimeException throwOnSave;

    /** Set to inject a failure into the next {@link #save} call (one-shot). */
    public void throwOnSave(RuntimeException ex) {
        this.throwOnSave = ex;
    }

    /** Number of distinct session ids that have been saved at least once. */
    public int savedSessionCount() {
        return saved.size();
    }

    /** Ordered list of every checkpoint passed to {@link #save} (insertion order). */
    public List<Checkpoint> saveHistory() {
        synchronized (saveHistory) {
            return new ArrayList<Checkpoint>(saveHistory);
        }
    }

    @Override
    public void save(Checkpoint checkpoint) {
        RuntimeException toThrow = this.throwOnSave;
        if (toThrow != null) {
            this.throwOnSave = null;
            throw toThrow;
        }
        saved.put(checkpoint.getSessionId(), checkpoint);
        saveHistory.add(checkpoint);
    }

    @Override
    public Optional<Checkpoint> load(String sessionId) {
        return Optional.ofNullable(saved.get(sessionId));
    }
}
