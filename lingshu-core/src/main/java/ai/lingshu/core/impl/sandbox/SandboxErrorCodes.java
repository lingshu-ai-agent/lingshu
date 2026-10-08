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
package ai.lingshu.core.impl.sandbox;

/**
 * Story #028 — Sandbox-domain ErrorCode constants (dsh §15.5 域字母 S 段).
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code AccessDeniedException} ({@link ai.lingshu.core.slot.AccessDeniedException})
 *       — 4 throw sites prefix their message with {@code [LINGS-S01]}.</li>
 *   <li>(reserved for §14.10 audit-log Story #016 to filter sandbox deny events)</li>
 * </ul>
 *
 * <p>🆕 Story #028 — domain letter {@code S} (Sandbox) is enabled (dsh §15.4 域字母
 * 表 9 → 10 域 — adds to {@code C/S/L/T/X/R/A/Z/D} the {@code S}-letter row). Only
 * {@code S01} is allocated in this Story; {@code S02+} remain reserved for follow-up
 * sandbox concerns (resource exhaustion, command timeouts, network rate limits, etc.).
 */
public final class SandboxErrorCodes {

    private SandboxErrorCodes() {}

    /**
     * SANDBOX_ACCESS_DENIED — see dsh §15.5 S 段 1 号 + §6.3 ChrootRuntimeSandbox.
     * Emitted by {@code AccessDeniedException} at 4 throw sites.
     */
    public static final String LINGS_S01 = "LINGS-S01";
}