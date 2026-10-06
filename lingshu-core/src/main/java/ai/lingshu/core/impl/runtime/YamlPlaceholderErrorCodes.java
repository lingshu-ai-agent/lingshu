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

/**
 * Story #026 — ErrorCodes for the {@code MinimalYamlParser} placeholder resolver
 * ({@link PlaceholderResolver}). Two new codes in the Config domain (dsh §15
 * letter {@code C}):
 *
 * <ul>
 *   <li>{@link #LINGS_C03} — {@code YAML_PLACEHOLDER_UNRESOLVED}: a {@code ${X}}
 *       reference (or its nested inner {@code ${Y}}) was not found in either
 *       the process environment ({@link System#getenv(String)}) or the JVM
 *       system properties ({@link System#getProperty(String)}). Fail-fast
 *       semantics — we deliberately <b>do not</b> coerce to {@code null} or
 *       empty string, since silent substitution makes debugging agent
 *       startup pathologies far harder than a clear stack trace.</li>
 *
 *   <li>{@link #LINGS_C04} — {@code YAML_PLACEHOLDER_CYCLE}: the resolver
 *       detected a cycle (e.g. {@code ${A}} → {@code ${B}} → {@code ${A}}).
 *       Cycle detection uses a bounded recursion depth (32) plus a per-resolve
 *       visited-set so the call always terminates with this code rather than
 *       overflowing the stack.</li>
 * </ul>
 *
 * <p>Existing {@link ai.lingshu.core.exception.LingsConfigException} is reused
 * for throw site — only the {@code code} string is new.
 */
public final class YamlPlaceholderErrorCodes {

    /** {@code ${X}} reference not found in env / system-property (fail-fast). */
    public static final String LINGS_C03 = "LINGS-C03";

    /** Recursive placeholder resolution cycle detected. */
    public static final String LINGS_C04 = "LINGS-C04";

    private YamlPlaceholderErrorCodes() {
        throw new AssertionError("YamlPlaceholderErrorCodes is a constants holder — do not instantiate");
    }
}