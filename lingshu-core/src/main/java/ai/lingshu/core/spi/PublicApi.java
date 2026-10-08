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
package ai.lingshu.core.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🆕 v0.1.0 — Public API stability marker (dsh §16 + D4 release decision).
 *
 * <p>Every type or method carrying {@code @PublicApi} is part of the published LingShu contract.
 * Consumers (plugin authors, integrators, downstream teams) may rely on these surfaces across
 * minor releases per the level declared in {@link #value()}.
 *
 * <p><b>Stability levels</b>:
 * <ul>
 *   <li>{@link Level#STABLE} — backwards-compatible across minor releases; additive only.
 *       Breaking changes require a major version bump and a deprecation cycle. This is the
 *       default stability tier for the 12 SPIs listed in the v0.1.0 README
 *       (<i>Stable SPI (since 0.1.0)</i> section).</li>
 *   <li>{@link Level#INCUBATING} — provisional; the contract may change in a future minor
 *       release. Methods or types marked this way should not yet be relied on by downstream
 *       code beyond experiments and prototypes. Once stabilized, the marker is replaced
 *       with {@link Level#STABLE} on the same release line.</li>
 *   <li>{@link Level#INTERNAL} — not part of the public contract; the annotation is present
 *       only to make the boundary explicit to readers and tooling. Renames, signature
 *       changes, and removals may happen in any release without notice.</li>
 * </ul>
 *
 * <p><b>Scope of {@code STABLE}</b> on a Type — guarantees cover:
 * <ol>
 *   <li>The fully qualified name of the type itself</li>
 *   <li>The signature of every {@code public} / {@code protected} method declared on the type</li>
 *   <li>For interfaces, the binary shape used by Spring DI / Java SPI / reflective lookup</li>
 * </ol>
 *
 * <p>It does <b>not</b> cover implementation classes behind a {@code Provider} (those are
 * resolved at runtime by name; the {@code Provider} SPI is the stable surface). It also
 * does not cover fields unless separately annotated, nor Javadoc text.
 *
 * <p><b>Compatibility promise</b> (dsh §16.1 + D4):
 * <ul>
 *   <li>Within a major version: no removal of {@code STABLE} APIs without a 2-release
 *       deprecation cycle (annotation flips to {@code @Deprecated forRemoval=false}).</li>
 *   <li>Within a major version: no signature-breaking changes; new methods may be added to
 *       interfaces with a default implementation, but never removed.</li>
 *   <li>Across major versions: breaking changes are allowed after a published migration
 *       guide and a one-version overlap window where both old and new APIs coexist.</li>
 * </ul>
 *
 * <p><b>Usage</b>:
 * <pre>{@code
 * @PublicApi(stable = PublicApi.Level.STABLE)
 * public interface LlmProvider { ... }
 * }</pre>
 *
 * <p>For incubating APIs:
 * <pre>{@code
 * @PublicApi(stable = PublicApi.Level.INCUBATING)
 * public interface NewExperimental { ... }
 * }</pre>
 *
 * <p>For internal types (still annotated to make the boundary explicit):
 * <pre>{@code
 * @PublicApi(stable = PublicApi.Level.INTERNAL)
 * public class InternalHook { ... }
 * }</pre>
 *
 * @see <a href="https://github.com/lingshu-ai-agent/lingshu/blob/main/README.md#-stable-spi-since-010">
 *      README — Stable SPI (since 0.1.0)</a>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.TYPE,
        ElementType.METHOD,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD
})
public @interface PublicApi {

    /**
     * Stability level for the annotated element. Defaults to {@link Level#STABLE} when omitted
     * (the {@code @PublicApi} shorthand).
     */
    Level value() default Level.STABLE;

    /**
     * Stability levels. Mirrors the canonical Gradle / JetBrains convention so that
     * tooling familiar with either ecosystem recognizes the tier at a glance.
     */
    enum Level {
        /** Backwards-compatible across minor releases; additive only. */
        STABLE,
        /** Provisional; contract may change in a future minor release. */
        INCUBATING,
        /** Internal; not part of the public contract. */
        INTERNAL
    }
}
