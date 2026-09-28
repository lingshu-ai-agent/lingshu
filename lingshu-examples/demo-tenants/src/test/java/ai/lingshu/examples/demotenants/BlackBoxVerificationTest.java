package ai.lingshu.examples.demotenants;

import ai.lingshu.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #006 AC-05 skeleton — TenantContext 4 维隔离(nested / exception / cross-thread / null-validation)。
 *
 * <p>骨架阶段不集成 AgentFactory(避免 6-Router ctor 拖慢 JUnit 单测);
 * 直接测 TenantContext static utility,后续 Stage B 把 tenant 注入到 AgentFactory.description() 9 行验证完整 AC-05。
 */
class BlackBoxVerificationTest {

    @AfterEach
    void cleanup() {
        // Defensive: ensure no tenant leaks between tests
        while (TenantContext.current() != null) {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("AC-05: basic runAs — current() == tenantId inside block")
    void ac05_basicRunAs() {
        TenantContext.runAs("alice", () -> {
            assertThat(TenantContext.current()).isEqualTo("alice");
            return null;
        });
        // I-3: cleared after runAs
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("AC-05: nested runAs — outer scope unchanged after inner exits (I-2)")
    void ac05_nestedRunAs() {
        TenantContext.runAs("alice", () -> {
            TenantContext.runAs("bob", () -> {
                assertThat(TenantContext.current()).isEqualTo("bob");
                return null;
            });
            // Inner exited — back to outer
            assertThat(TenantContext.current()).isEqualTo("alice");
            return null;
        });
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("AC-05: exception-safe — clear() called even when work throws (I-3)")
    void ac05_exceptionSafe() {
        assertThatThrownBy(() -> TenantContext.runAs("alice", () -> {
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class).hasMessage("boom");
        // Stack must be empty after exception
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("AC-05: cross-thread — snapshot + runWithSnapshot explicit propagation (I-4)")
    void ac05_crossThread() throws Exception {
        AtomicReference<String> childTenant = new AtomicReference<>();
        Thread t = new Thread(() -> {
            TenantContext.runWithSnapshot("alice", () -> {
                childTenant.set(TenantContext.current());
                return null;
            });
        });
        t.start();
        t.join(2000);
        assertThat(childTenant.get()).isEqualTo("alice");
    }

    @Test
    @DisplayName("AC-05: validation — null/empty/invalid tenantId rejected (I-6)")
    void ac05_validation() {
        assertThatThrownBy(() -> TenantContext.set(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.set(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.set("alice bob"))
            .isInstanceOf(IllegalArgumentException.class);
        // Stack unchanged on validation failure
        assertThat(TenantContext.current()).isNull();
    }
}