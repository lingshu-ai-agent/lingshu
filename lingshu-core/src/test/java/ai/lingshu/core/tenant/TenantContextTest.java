package ai.lingshu.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #006 — L1 unit tests for {@link TenantContext}.
 *
 * <p>Covers FR-001 (basic push/pop), FR-005 (try-finally), FR-006
 * (cross-thread snapshot), FR-015 (no InheritableThreadLocal), plus the
 * Edge Case for invalid tenantId format.
 */
class TenantContextTest {

    @AfterEach
    void cleanup() {
        // Each test runs on the same JUnit thread — make sure no test pollutes
        // the next by clearing any leftover state.
        while (TenantContext.current() != null) {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("L1-001: current_initialState_isNull")
    void current_initialState_isNull() {
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("L1-002: set_thenCurrent_returnsSetValue")
    void set_thenCurrent_returnsSetValue() {
        TenantContext.set("alice");
        try {
            assertThat(TenantContext.current()).isEqualTo("alice");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("L1-003: runAs_basicBlock_clearsAfterCallback")
    void runAs_basicBlock_clearsAfterCallback() {
        String result = TenantContext.runAs("alice", () -> {
            assertThat(TenantContext.current()).isEqualTo("alice");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("L1-004: runAs_nested_innerDoesNotPolluteOuter")
    void runAs_nested_innerDoesNotPolluteOuter() {
        TenantContext.runAs("alice", () -> {
            assertThat(TenantContext.current()).isEqualTo("alice");

            TenantContext.runAs("bob", () -> {
                assertThat(TenantContext.current()).isEqualTo("bob");
            });

            // After inner runAs exits, outer scope must see 'alice' again.
            assertThat(TenantContext.current()).isEqualTo("alice");
            return null;
        });

        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("L1-005: runAs_cleanupOnException")
    void runAs_cleanupOnException() {
        RuntimeException boom = new RuntimeException("boom");
        assertThatThrownBy(() -> TenantContext.runAs("alice", () -> {
            throw boom;
        }))
            .isSameAs(boom);

        // Even though the supplier threw, the stack must be cleared (try-finally).
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("L1-006: runWithSnapshot_crossThreadRestore")
    void runWithSnapshot_crossThreadRestore() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> childSaw = new AtomicReference<>();

            TenantContext.runAs("alice", () -> {
                String snap = TenantContext.snapshot();
                assertThat(snap).isEqualTo("alice");
                // Without runWithSnapshot, child would see null.
                assertThat(TenantContext.current()).isEqualTo("alice");
                pool.submit(() -> {
                    try {
                        // Explicit transfer — this is the safe pattern.
                        TenantContext.runWithSnapshot(snap, () -> {
                            childSaw.set(TenantContext.current());
                            return null;
                        });
                    } finally {
                        latch.countDown();
                    }
                });
                return null;
            });

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(childSaw.get()).isEqualTo("alice");
            assertThat(TenantContext.current()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge: set_invalidTenantId_throwsIAE_doesNotMutateStack")
    void set_invalidTenantId_throwsIAE_doesNotMutateStack() {
        // Empty string
        assertThatThrownBy(() -> TenantContext.set(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId");

        // Too long
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) sb.append('a');
        String tooLong = sb.toString();
        assertThatThrownBy(() -> TenantContext.set(tooLong))
            .isInstanceOf(IllegalArgumentException.class);

        // Contains forbidden character (colon — would clash with session-key prefix)
        assertThatThrownBy(() -> TenantContext.set("alice:bob"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alice:bob");

        // Stack must still be empty after every rejection.
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("Edge: runWithSnapshot_null_throwsIAE")
    void runWithSnapshot_null_throwsIAE() {
        assertThatThrownBy(() -> TenantContext.runWithSnapshot(null, () -> {
            // Should never run.
            throw new AssertionError("supplier must not run when snapshot is null");
        }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("snapshot");
    }

    @Test
    @DisplayName("Edge: childThread_withoutSnapshot_seesNull")
    void childThread_withoutSnapshot_seesNull() throws Exception {
        final boolean[] ok = {false};
        TenantContext.runAs("alice", () -> {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<String> childSaw = new AtomicReference<>("unset");
                pool.submit(() -> {
                    try {
                        // Deliberately omit runWithSnapshot — child must see null.
                        childSaw.set(TenantContext.current());
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    ok[0] = latch.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
                assertThat(ok[0]).isTrue();
                assertThat(childSaw.get()).isNull();
            } finally {
                pool.shutdownNow();
            }
            return null;
        });
    }
}