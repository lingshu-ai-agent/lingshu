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
package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.decision.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #030 — L1 unit tests for {@link ApprovalRegistry} (4 cases).
 *
 * <p>Verifies the in-memory map: register / consume (atomic, single-use) /
 * idempotent re-register / evict-by-prefix.
 */
class ApprovalRegistryTest {

    private ApprovalRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ApprovalRegistry();
    }

    @Test
    @DisplayName("AC-030-8: registerThenConsume_returnsSameContinuationOnce")
    void registerThenConsume_returnsSameContinuationOnce() {
        AtomicReference<Decision> target = new AtomicReference<Decision>();
        registry.register("approval-1", target::set);
        assertThat(registry.size()).isEqualTo(1);

        java.util.function.Consumer<Decision> c1 = registry.consume("approval-1");
        assertThat(c1).isNotNull();
        assertThat(registry.size()).isEqualTo(0);

        // Second consume returns null — atomic, single-use
        java.util.function.Consumer<Decision> c2 = registry.consume("approval-1");
        assertThat(c2).isNull();
    }

    @Test
    @DisplayName("AC-030-9: consume_unknown_returnsNull")
    void consume_unknown_returnsNull() {
        assertThat(registry.consume("never-registered")).isNull();
    }

    @Test
    @DisplayName("AC-030-10: register_nullIdOrContinuation_isNoOp")
    void register_nullIdOrContinuation_isNoOp() {
        registry.register(null, d -> {});
        registry.register("ok", null);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("AC-030-11: evictBySessionPrefix_returnsCountAndClears")
    void evictBySessionPrefix_returnsCountAndClears() {
        registry.register("approval-1", d -> {});
        registry.register("approval-2", d -> {});
        assertThat(registry.size()).isEqualTo(2);

        int evicted = registry.evictBySessionPrefix("session-X");
        assertThat(evicted).isEqualTo(2);
        assertThat(registry.size()).isEqualTo(0);
    }
}