package ai.lingshu.core.tenant;

import ai.lingshu.core.impl.session.DefaultInMemorySessionStore;
import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #006 — L1 unit tests for per-tenant session-key isolation (US5, FR-002,
 * AC-05).
 *
 * <p>Two assertions (L1-016, L1-017) prove that two tenants with the same logical
 * {@code sessionId} ("s1") never see each other's checkpoints:
 * <ul>
 *   <li><b>L1-016</b>: alice saves, bob cannot read it (even with the same id).</li>
 *   <li><b>L1-017</b>: bob saves a different snapshot for "s1", and a subsequent
 *       alice load still returns alice's version (last-write per tenant).</li>
 * </ul>
 *
 * <p>Single-tenant fallback is covered as an Edge Case.
 */
class SessionKeyIsolationTest {

    private DefaultInMemorySessionStore store;

    @BeforeEach
    void setUp() {
        store = new DefaultInMemorySessionStore();
    }

    @AfterEach
    void tearDown() {
        while (TenantContext.current() != null) {
            TenantContext.clear();
        }
    }

    private static Checkpoint checkpoint(String sessionId, String marker) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("marker", marker);
        return new Checkpoint(sessionId, Collections.<Message>emptyList(),
            Collections.unmodifiableMap(metadata), Instant.now());
    }

    @Test
    @DisplayName("L1-016: aliceSaves_bobCannotRead_evenWithSameSessionId")
    void aliceSaves_bobCannotRead_evenWithSameSessionId() {
        Checkpoint aliceS1 = checkpoint("s1", "alice");

        TenantContext.runAs("alice", () -> store.save(aliceS1));

        // bob's load of the same logical sessionId must not see alice's checkpoint.
        boolean aliceSeenFromBob = TenantContext.runAs("bob", () -> store.load("s1").isPresent());
        assertThat(aliceSeenFromBob).isFalse();
        assertThat(store.load("s1")).isEmpty(); // no tenant context — also empty

        // alice can still load her own.
        boolean aliceSeenFromAlice = TenantContext.runAs("alice",
            () -> store.load("s1").isPresent());
        assertThat(aliceSeenFromAlice).isTrue();
        assertThat(TenantContext.runAs("alice", () -> store.load("s1").get()))
            .extracting(Checkpoint::getMetadata)
            .extracting(m -> m.get("marker"))
            .isEqualTo("alice");
    }

    @Test
    @DisplayName("L1-017: aliceAndBobSaveToSameSessionId_eachGetsTheirOwn")
    void aliceAndBobSaveToSameSessionId_eachGetsTheirOwn() {
        Checkpoint aliceS1 = checkpoint("s1", "alice");
        Checkpoint bobS1 = checkpoint("s1", "bob");

        TenantContext.runAs("alice", () -> store.save(aliceS1));
        TenantContext.runAs("bob", () -> store.save(bobS1));

        // Both have a checkpoint under "s1" — but they're stored in separate buckets.
        assertThat(store.size()).isEqualTo(2);

        Checkpoint aliceLoaded = TenantContext.runAs("alice", () -> store.load("s1").get());
        Checkpoint bobLoaded = TenantContext.runAs("bob", () -> store.load("s1").get());

        assertThat(aliceLoaded).isSameAs(aliceS1);
        assertThat(bobLoaded).isSameAs(bobS1);
        assertThat(aliceLoaded.getMetadata().get("marker")).isEqualTo("alice");
        assertThat(bobLoaded.getMetadata().get("marker")).isEqualTo("bob");

        // Overwriting alice's "s1" with a new checkpoint doesn't touch bob's.
        Checkpoint aliceS1V2 = checkpoint("s1", "alice-v2");
        TenantContext.runAs("alice", () -> store.save(aliceS1V2));
        Checkpoint bobAfter = TenantContext.runAs("bob", () -> store.load("s1").get());
        Checkpoint aliceAfter = TenantContext.runAs("alice", () -> store.load("s1").get());
        assertThat(bobAfter).isSameAs(bobS1);
        assertThat(aliceAfter).isSameAs(aliceS1V2);
        assertThat(aliceAfter.getMetadata().get("marker")).isEqualTo("alice-v2");
    }

    @Test
    @DisplayName("Edge: noTenantContext_keysAreBareSessionId")
    void noTenantContext_keysAreBareSessionId() {
        Checkpoint plain = checkpoint("plain-s1", "marker");

        // No TenantContext → key is the bare sessionId, not "null:plain-s1".
        store.save(plain);

        assertThat(store.load("plain-s1")).isPresent()
            .get().extracting(Checkpoint::getMetadata).extracting(m -> m.get("marker"))
            .isEqualTo("marker");
        assertThat(store.load("ghost")).isEmpty();
    }

    @Test
    @DisplayName("Edge: loadUnknownSessionId_returnsEmpty")
    void loadUnknownSessionId_returnsEmpty() {
        assertThat(TenantContext.runAs("alice", () -> store.load("never-saved")))
            .isEmpty();
        assertThat(store.load(null)).isEmpty();
    }

    @Test
    @DisplayName("Edge: saveNullCheckpoint_throwsIAE")
    void saveNullCheckpoint_throwsIAE() {
        assertThatExceptionFromIAE(() -> store.save(null));
    }

    private static void assertThatExceptionFromIAE(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }
}
