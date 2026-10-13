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
package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;

/**
 * 🆕 Story #045 — Sentinel {@link Compactor} for the back-compat legacy
 * {@code LinearTurnEngine} constructors (5-arg / 6-arg without {@code Compactor}).
 * Always says "no" to {@link #shouldCompact(Prompt)} and is a no-op for
 * {@link #compact(TurnContext)}, so the engine never invokes compression when
 * no compactor has been wired in.
 *
 * <p>Mirrors the {@code @Nullable} precedent from Story #030
 * ({@code ApprovalRegistry}): the engine must tolerate a missing dependency
 * rather than throwing, and {@code NullCompactor.INSTANCE} is the canonical
 * "no-op" stand-in.
 *
 * <p>SPI contract (§4.1 must-preserve): {@link Compactor#CONTRACT_VERSION} is
 * not overridden — the sentinel intentionally reports the same contract version
 * as any concrete compactor, since it satisfies the interface fully.
 *
 * @since 0.1.0
 */
public final class NullCompactor implements Compactor {

    /** Singleton — no state, no need for more than one. */
    public static final NullCompactor INSTANCE = new NullCompactor();

    private NullCompactor() {}

    @Override
    public boolean shouldCompact(Prompt prompt) {
        return false;
    }

    @Override
    public void compact(TurnContext ctx) {
        // intentional no-op
    }
}
