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
import ai.lingshu.core.message.Message;
import ai.lingshu.core.slot.SessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story #046 — L1 unit tests for {@link NullSessionStore}.
 *
 * <p>AC-046-10: the sentinel is a no-op stand-in. Saving never throws; loading
 * always returns {@link Optional#empty}; the singleton is reusable.
 */
class NullSessionStoreTest {

    @Test
    @DisplayName("AC-046-10: INSTANCE is the singleton — referential equality between lookups")
    void singletonReusable() {
        SessionStore a = NullSessionStore.INSTANCE;
        SessionStore b = NullSessionStore.INSTANCE;
        assertThat(a).isSameAs(b);
    }

    @Test
    @DisplayName("AC-046-10: save(Checkpoint) is a no-op — never throws, accepts any payload")
    void saveIsNoOp() {
        Checkpoint cp = new Checkpoint(
            "session-1",
            Collections.<Message>emptyList(),
            Collections.<String, String>emptyMap(),
            Instant.now());
        assertThatCode(() -> NullSessionStore.INSTANCE.save(cp)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-046-10: load(any sessionId) always returns Optional.empty()")
    void loadAlwaysEmpty() {
        Optional<Checkpoint> result = NullSessionStore.INSTANCE.load("any-session-id");
        assertThat(result).isEmpty();
    }
}
