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

import java.util.Optional;

/**
 * 🆕 Story #046 — Sentinel {@link SessionStore} for the back-compat legacy
 * {@code LinearTurnEngine} constructors that pre-date Story #046. {@link #save}
 * is a no-op and {@link #load} always returns {@link Optional#empty}, so the
 * engine never raises a checkpoint-related error when no {@code SessionStore}
 * has been wired in.
 *
 * <p>Mirrors the {@link ai.lingshu.core.impl.compaction.NullCompactor} precedent
 * from Story #045 — the engine must tolerate a missing dependency rather than
 * throwing, and {@link #INSTANCE} is the canonical "no-op" stand-in injected
 * by the legacy 5-arg / 6-arg / 7-arg constructors of {@code LinearTurnEngine}.
 *
 * <p>SPI contract (§4.1 must-preserve): {@link SessionStore#CONTRACT_VERSION}
 * is not overridden — the sentinel intentionally reports the same contract
 * version as any concrete store, since it satisfies the interface fully.
 *
 * @since 0.1.0
 */
public final class NullSessionStore implements SessionStore {

    /** Singleton — no state, no need for more than one. */
    public static final NullSessionStore INSTANCE = new NullSessionStore();

    private NullSessionStore() {}

    @Override
    public void save(Checkpoint checkpoint) {
        // intentional no-op
    }

    @Override
    public Optional<Checkpoint> load(String sessionId) {
        return Optional.empty();
    }
}
